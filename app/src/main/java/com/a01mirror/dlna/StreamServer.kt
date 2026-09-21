package com.a01mirror.dlna

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TS_CHUNK_BYTES = 188 * 32

// Antrean per klien: cukup dalam supaya lonjakan Wi-Fi / CPU (game berat) tidak langsung memutus stream.
private const val CLIENT_QUEUE_CAPACITY = 48
// Keep the renderer close to the live edge. A long queue is what turns a short CPU/Wi-Fi hiccup
// into several seconds of perceived delay on cheap DLNA firmware.
private const val MAX_CLIENT_VIDEO_AGE_NS = 350_000_000L
private const val MAX_CLIENT_AUDIO_AGE_NS = 550_000_000L
private const val LIVE_PACE_MULTIPLIER = 1.15
private const val MAX_AUDIO_BEHIND_VIDEO_90K = 16_200L // 180 ms

// Rekaman: antrean besar (dibatasi memori) dan tidak pernah mati permanen karena disk sesaat lambat.
private const val RECORD_QUEUE_MAX_BYTES = 16L * 1024L * 1024L
private const val RECORD_QUEUE_MAX_ITEMS = 6144

private const val TARGET_AUDIO_BITRATE = 128_000L
private const val KIND_TABLE = 0
private const val KIND_VIDEO = 1
private const val KIND_AUDIO = 2
private val CRLF = byteArrayOf(13, 10)

class TsBroadcaster(
    private val resolver: ContentResolver,
    private val enableRecording: Boolean = true
) {
    private val muxer = MpegTsMuxer()
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var output: OutputStream? = null
    private var pendingUri = android.net.Uri.EMPTY
    private var lastTablesNs = 0L

    /** Jam bersama video + audio (System.nanoTime): PTS keduanya dihitung dari titik nol yang sama. */
    val clockOriginNs: Long = System.nanoTime()

    @Volatile private var latestKeyFrameTs: ByteArray? = null
@Volatile private var latestKeyFramePts90k: Long = -1L
    @Volatile private var latestPat: ByteArray? = null
    @Volatile private var latestPmt: ByteArray? = null
    private var pFramesSinceKey = 0
    @Volatile private var keyFrameRequested = false
    @Volatile private var targetVideoBitrate = 2_000_000L

    // --- Rekaman ---
    @Volatile private var recordingFailed = false
    @Volatile private var recordingSkipping = true // mulai menulis tepat di IDR pertama
    private val recordingQueue = ArrayBlockingQueue<ByteArray>(RECORD_QUEUE_MAX_ITEMS)
    private val recordingQueuedBytes = AtomicLong(0L)
    private val recordingWritten = AtomicLong(0L)
    @Volatile private var recordingRunning = false
    private var recordingThread: Thread? = null

    @Volatile var recordingName: String = ""
        private set
    @Volatile var recordingGaps: Long = 0L
        private set
    val recordingWrittenBytes: Long get() = recordingWritten.get()

    @Volatile var bytesPublished: Long = 0L
        private set
    @Volatile var videoFrames: Long = 0L
        private set
    @Volatile var droppedVideoFrames: Long = 0L
        private set
    @Volatile var droppedClientPackets: Long = 0L
        private set

    val clientCount: Int get() = clients.size

    val sessionToken: String = UUID.randomUUID().toString().replace("-", "")

    init {
        if (enableRecording) {
            openRecording()
            startRecordingWriter()
        } else {
            recordingFailed = false
            recordingRunning = false
            recordingSkipping = true
        }
    }

    fun setTargetVideoBitrate(bitrate: Int) {
        targetVideoBitrate = bitrate.coerceAtLeast(600_000).toLong()
    }

    /** Dipanggil lapisan kirim saat klien macet / baru: encoder akan membuat IDR baru. */
    fun requestKeyFrame() {
        keyFrameRequested = true
    }

    fun consumeKeyFrameRequest(): Boolean {
        if (!keyFrameRequested) return false
        keyFrameRequested = false
        return true
    }

    fun recordingActive(): Boolean = enableRecording && output != null && !recordingFailed && recordingRunning

    fun recordingStatus(): String = if (!enableRecording) {
        "dimatikan • mirror live saja"
    } else when {
        !recordingRunning -> "tidak aktif"
        recordingFailed -> "GAGAL menulis file"
        recordingSkipping && recordingWritten.get() > 0L -> "menunggu keyframe"
        else -> "aktif"
    }

    @Synchronized
    fun publishVideo(accessUnit: ByteArray, pts90k: Long, keyFrame: Boolean) {
        val packet = muxer.videoPes(accessUnit, pts90k, keyFrame)
        if (keyFrame) {
            latestKeyFrameTs = packet
            latestKeyFramePts90k = pts90k
            pFramesSinceKey = 0
        } else {
            pFramesSinceKey++
        }
        publish(packet, KIND_VIDEO, keyFrame, pts90k)
        videoFrames += 1
    }

    @Synchronized
    fun publishAudio(mp3: ByteArray, pts90k: Long) {
        if (mp3.isNotEmpty()) publish(muxer.audioPes(mp3, pts90k), KIND_AUDIO, false, pts90k)
    }

    @Synchronized
    private fun publish(packet: ByteArray, kind: Int, keyFrame: Boolean, pts90k: Long = -1L) {
        if (packet.isEmpty()) return
        bytesPublished += packet.size.toLong()

        val now = System.nanoTime()
        val isKeyVideo = kind == KIND_VIDEO && keyFrame
        // PAT/PMT tiap 250 ms dan selalu tepat sebelum IDR (titik masuk klien baru & rekaman).
        if (isKeyVideo || now - lastTablesNs >= 250_000_000L) {
            val pat = muxer.patPacket()
            val pmt = muxer.pmtPacket()
            latestPat = pat
            latestPmt = pmt
            writeRecording(pat)
            writeRecording(pmt)
            // PAT/PMT hanyalah tabel kontrol, bukan keyframe (jangan memicu pembersihan antrean live).
            broadcast(pat, KIND_TABLE, false, -1L)
            broadcast(pmt, KIND_TABLE, false, -1L)
            lastTablesNs = now
        }

        // Rekaman berhenti sementara saat tertinggal; lanjut bersih di IDR berikutnya (PAT/PMT dulu).
        if (isKeyVideo && recordingSkipping && output != null && !recordingFailed && recordingRunning) {
            val pat = latestPat
            val pmt = latestPmt
            if (pat != null && pmt != null && enqueueRecording(pat) && enqueueRecording(pmt)) {
                recordingSkipping = false
            }
        }

        writeRecording(packet)
        broadcast(packet, kind, keyFrame, pts90k)
    }

    private fun enqueueRecording(data: ByteArray): Boolean {
        if (recordingQueuedBytes.get() + data.size > RECORD_QUEUE_MAX_BYTES) return false
        if (!recordingQueue.offer(data)) return false
        recordingQueuedBytes.addAndGet(data.size.toLong())
        return true
    }

    /**
     * Rekaman terpisah dari jalur live. Bila disk sangat lambat dan antrean penuh, rekaman melompat
     * ke IDR berikutnya (ada celah kecil) — tidak pernah mati permanen dan tidak menahan siaran.
     */
    private fun writeRecording(data: ByteArray) {
        if (output == null || recordingFailed || !recordingRunning || recordingSkipping) return
        if (!enqueueRecording(data)) {
            recordingSkipping = true
            recordingGaps += 1L
        }
    }

    private fun startRecordingWriter() {
        if (output == null || recordingRunning) return
        recordingRunning = true
        recordingThread = Thread {
            try {
                // Rekaman dipisahkan dari jalur live; I/O lambat tidak boleh menguasai CPU live.
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
                var lastFlushNs = System.nanoTime()
                while (recordingRunning || recordingQueue.isNotEmpty()) {
                    val data = recordingQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    recordingQueuedBytes.updateAndGet { (it - data.size).coerceAtLeast(0L) }
                    val out = output ?: break
                    if (!recordingFailed) {
                        try {
                            out.write(data)
                            recordingWritten.addAndGet(data.size.toLong())
                            val now = System.nanoTime()
                            if (now - lastFlushNs >= 1_500_000_000L) {
                                out.flush()
                                lastFlushNs = now
                            }
                        } catch (_: IOException) {
                            recordingFailed = true
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.also {
            it.name = "A01-Recorder"
            it.start()
        }
    }

    private fun broadcast(data: ByteArray, kind: Int, keyFrame: Boolean, pts90k: Long) {
        for (client in clients) {
            if (!client.offer(data, kind, keyFrame, pts90k)) {
                client.close()
                clients.remove(client)
            }
        }
    }

    @Synchronized
    fun resetLiveForCodecRecovery() {
        latestKeyFrameTs = null
        latestKeyFramePts90k = -1L
        pFramesSinceKey = 1
        for (client in clients) client.resetForRecovery()
    }

    @Synchronized
    fun attach(socket: Socket, outputStream: OutputStream, chunked: Boolean = true) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = 64 * 1024
            socket.receiveBufferSize = 16 * 1024
        } catch (_: Exception) {}
        try { socket.trafficClass = 0x10 } catch (_: Exception) {}

        val client = Client(
            socket,
            outputStream,
            { targetVideoBitrate + TARGET_AUDIO_BITRATE },
            { droppedClientPackets += 1L },
            { requestKeyFrame() },
            chunked
        )
        try {
            // Tidak ada penulisan socket di sini (dulu memblokir lock broadcaster dan menahan encoder).
            // IDR tersimpan hanya dipakai bila belum ada frame P sesudahnya; kalau sudah ada, klien
            // menunggu IDR baru (diminta ke encoder) supaya tidak ada artefak referensi hilang.
            val pat = latestPat ?: muxer.patPacket()
            val pmt = latestPmt ?: muxer.pmtPacket()
            val cached = latestKeyFrameTs
            val warm = cached != null && pFramesSinceKey == 0
            client.prime(pat, pmt, if (warm) cached else null, latestKeyFramePts90k)
            if (!warm) requestKeyFrame()
            clients.add(client)
            client.start()
        } catch (_: Throwable) {
            client.close()
        }
    }

    /** Di bawah tekanan memori: kosongkan antrean rekaman, lanjut lagi di IDR berikutnya. */
    @Synchronized
    fun trimRecordingPressure() {
        recordingQueue.clear()
        recordingQueuedBytes.set(0L)
        recordingSkipping = true
        recordingGaps += 1L
    }

    @Synchronized
    fun close() {
        for (client in clients) client.close()
        clients.clear()

        // Kuras sisa antrean rekaman dulu (bukan langsung di-interrupt) supaya ekor rekaman tidak hilang.
        recordingRunning = false
        try { recordingThread?.join(1800) } catch (_: InterruptedException) {}
        if (recordingThread?.isAlive == true) {
            try { recordingThread?.interrupt() } catch (_: Exception) {}
            try { recordingThread?.join(300) } catch (_: InterruptedException) {}
        }
        recordingThread = null
        recordingQueue.clear()
        recordingQueuedBytes.set(0L)

        try { output?.flush() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        output = null

        if (pendingUri != android.net.Uri.EMPTY) {
            try {
                resolver.update(
                    pendingUri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (_: Exception) {}
        }
        pendingUri = android.net.Uri.EMPTY
    }

    private fun openRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "A01Mirror_$stamp.ts"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp2t")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/A01Mirror")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        pendingUri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: android.net.Uri.EMPTY
        } catch (_: Exception) {
            android.net.Uri.EMPTY
        }

        output = if (pendingUri != android.net.Uri.EMPTY) {
            try {
                resolver.openOutputStream(pendingUri, "w")?.let { BufferedOutputStream(it, 128 * 1024) }
            } catch (_: Exception) {
                null
            }
        } else null
        if (output != null) recordingName = fileName
    }

    private class Client(
        private val socket: Socket,
        outputStream: OutputStream,
        private val bitrateProvider: () -> Long,
        private val onDrop: () -> Unit,
        private val onNeedKey: () -> Unit,
        private val chunked: Boolean = true
    ) {
        private val output: OutputStream = BufferedOutputStream(outputStream, 32 * 1024)

        private class QueuedPacket(
            val data: ByteArray,
            val kind: Int,
            val keyFrame: Boolean,
            val pts90k: Long,
            val enqueuedNs: Long
        )

        private val queue = ArrayBlockingQueue<QueuedPacket>(CLIENT_QUEUE_CAPACITY)
        @Volatile private var closed = false
        @Volatile private var waitingForKey = false
        private var writer: Thread? = null
        private var nextWireNs = 0L
        private var lastVideoPts90k = -1L

        /** Isi awal (tanpa menulis ke socket): PAT/PMT, plus IDR bila masih segar. */
        fun prime(pat: ByteArray, pmt: ByteArray, keyFrame: ByteArray?, keyPts90k: Long) {
            val now = System.nanoTime()
            queue.offer(QueuedPacket(pat, KIND_TABLE, false, -1L, now))
            queue.offer(QueuedPacket(pmt, KIND_TABLE, false, -1L, now))
            if (keyFrame != null) {
                queue.offer(QueuedPacket(keyFrame, KIND_VIDEO, true, keyPts90k, now))
                lastVideoPts90k = keyPts90k
            } else {
                waitingForKey = true
                lastVideoPts90k = -1L
            }
        }

        fun offer(bytes: ByteArray, kind: Int, keyFrame: Boolean, pts90k: Long): Boolean {
            if (closed) return false

            if (waitingForKey) {
                when {
                    kind == KIND_TABLE -> Unit
                    keyFrame -> {
                        waitingForKey = false
                        lastVideoPts90k = pts90k
                    }
                    else -> return true // Saat resync, buang A/V lama dan tunggu satu IDR baru.
                }
            }

            // Jangan masukkan paket audio yang timestamp-nya sudah jauh tertinggal dari video live.
            // Ini mencegah renderer memainkan potongan audio lama setelah gambar sudah maju.
            if (kind == KIND_AUDIO && lastVideoPts90k >= 0L && pts90k >= 0L &&
                pts90k + MAX_AUDIO_BEHIND_VIDEO_90K < lastVideoPts90k) {
                onDrop()
                return true
            }
            if (kind == KIND_VIDEO && pts90k >= 0L) {
                if (!keyFrame && lastVideoPts90k >= 0L && pts90k + 4_500L < lastVideoPts90k) {
                    onDrop()
                    return true
                }
                lastVideoPts90k = pts90k
            }

            val item = QueuedPacket(bytes, kind, keyFrame, pts90k, System.nanoTime())
            if (queue.offer(item)) return true

            // Antrean penuh = klien tak sanggup mengikuti arus. Utamakan live-edge daripada
            // menyimpan backlog beberapa detik. Audio lama juga dibuang ketika kita melakukan resync.
            onDrop()
            if (keyFrame) {
                removeNonTables()
                if (!queue.offer(item)) {
                    removeOldestAudioOrTable()
                    queue.offer(item)
                }
                waitingForKey = false
                return true
            }
            if (kind == KIND_VIDEO) {
                waitingForKey = true
                queue.clear()
                onNeedKey()
                return true
            }
            // Untuk audio, buang audio tertua. Jika ternyata tabel memenuhi antrean, buang item
            // tertua non-video sebagai pilihan terakhir agar video tidak ikut tersingkir.
            removeOldestAudioOrTable()
            queue.offer(item)
            return true
        }

        private fun removeNonTables() {
            for (old in queue.toList()) {
                if (old.kind != KIND_TABLE) queue.remove(old)
            }
        }

        private fun removeOldestAudioOrTable() {
            for (old in queue.toList()) {
                if (old.kind == KIND_AUDIO && queue.remove(old)) return
            }
            for (old in queue.toList()) {
                if (old.kind == KIND_TABLE && queue.remove(old)) return
            }
            queue.poll()
        }

        fun resetForRecovery() {
            waitingForKey = true
            queue.clear()
            nextWireNs = 0L
            lastVideoPts90k = -1L
        }

        fun start() {
            writer = Thread {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                } catch (_: Throwable) {}

                try {
                    while (!closed) {
                        val queued = queue.take()
                        val age = System.nanoTime() - queued.enqueuedNs

                        if (queued.kind == KIND_VIDEO && !queued.keyFrame && age > MAX_CLIENT_VIDEO_AGE_NS) {
                            // Terlalu jauh tertinggal: resync bersih di IDR berikutnya.
                            onDrop()
                            waitingForKey = true
                            queue.clear()
                            onNeedKey()
                            continue
                        }
                        if (queued.kind == KIND_AUDIO && age > MAX_CLIENT_AUDIO_AGE_NS) continue
                        if (queued.kind == KIND_AUDIO && queued.pts90k >= 0L && lastVideoPts90k >= 0L &&
                            queued.pts90k + MAX_AUDIO_BEHIND_VIDEO_90K < lastVideoPts90k) continue

                        // Selalu keluarkan TS dengan pace real-time. Sebelumnya jalur "queue kosong"
                        // mengirim satu access-unit sekaligus sehingga jaringan/STB menerima burst besar.
                        paceAndWrite(queued.data)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Throwable) {
                    close()
                }
            }.also {
                it.name = "A01-DLNA-client"
                it.start()
            }
        }

        private fun paceAndWrite(packet: ByteArray) {
            var offset = 0
            // Smooth output slightly faster than the encoder's average bitrate instead of a bursty
            // burst. This keeps the STB's input buffer from oscillating and reduces A/V jitter.
            val rateBytesPerSec = (bitrateProvider().coerceAtLeast(700_000L) * LIVE_PACE_MULTIPLIER / 8.0)
                .toLong().coerceAtLeast(1L)
            var schedule = maxOf(nextWireNs, System.nanoTime())
            while (offset < packet.size && !closed) {
                val len = minOf(TS_CHUNK_BYTES, packet.size - offset)
                val durationNs = (len.toDouble() * 1_000_000_000.0 / rateBytesPerSec).toLong().coerceAtLeast(100_000L)
                val now = System.nanoTime()
                val waitNs = schedule - now
                if (waitNs > 0) sleepNanos(waitNs)
                writeChunked(packet, offset, len)
                schedule += durationNs
                offset += len
            }
            output.flush()
            nextWireNs = maxOf(schedule, System.nanoTime())
        }

        private fun sleepNanos(ns: Long) {
            try {
                val ms = ns / 1_000_000L
                val nano = (ns % 1_000_000L).toInt()
                if (ms > 0 || nano > 0) Thread.sleep(ms, nano)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun writeChunked(packet: ByteArray) {
            var offset = 0
            while (offset < packet.size && !closed) {
                val len = minOf(TS_CHUNK_BYTES, packet.size - offset)
                writeChunked(packet, offset, len)
                offset += len
            }
            output.flush()
        }

        private fun writeChunked(packet: ByteArray, offset: Int, len: Int) {
            if (!chunked) {
                // Renderer HTTP/1.0: aliran mentah tanpa framing chunked.
                output.write(packet, offset, len)
                return
            }
            val head = (Integer.toHexString(len) + "\r\n").toByteArray(StandardCharsets.US_ASCII)
            output.write(head)
            output.write(packet, offset, len)
            output.write(CRLF)
        }

        fun close() {
            if (closed) return
            closed = true
            try { writer?.interrupt() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            queue.clear()
        }
    }
}

class LiveHttpServer(
    private val broadcaster: TsBroadcaster,
    private val token: String
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null
    var port: Int = 0
        private set

    fun start() {
        if (running) return
        server = ServerSocket(0)
        port = server!!.localPort
        running = true
        acceptThread = Thread {
            while (running) {
                try {
                    val socket = server?.accept() ?: break
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    handle(socket)
                } catch (_: SocketException) {
                    if (!running) break
                } catch (_: Exception) {
                }
            }
        }.also {
            it.name = "A01-HTTP"
            it.start()
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        acceptThread?.interrupt()
        acceptThread = null
        server = null
    }

    private fun handle(socket: Socket) {
        Thread {
            try {
                socket.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                val requestLine = reader.readLine() ?: run {
                    socket.close()
                    return@Thread
                }

                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val index = line.indexOf(':')
                    if (index > 0) {
                        headers[line.substring(0, index).trim().lowercase(Locale.US)] =
                            line.substring(index + 1).trim()
                    }
                }

                val parts = requestLine.split(' ', limit = 3)
                val method = parts.getOrNull(0)?.uppercase(Locale.US) ?: ""
                val requestedPath = parts.getOrNull(1)?.substringBefore('?') ?: ""
                val expectedPath = "/a01/$token/stream.ts"
                val http10 = parts.getOrNull(2)?.trim().equals("HTTP/1.0", ignoreCase = true)
                val output = socket.getOutputStream()

                if (method != "GET" && method != "HEAD") {
                    writeResponse(output, "405 Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed", close = true)
                    socket.close()
                    return@Thread
                }

                if (requestedPath != expectedPath) {
                    writeResponse(output, "404 Not Found", "text/plain; charset=utf-8", "A01 Mirror", close = true)
                    socket.close()
                    return@Thread
                }

                // Match the working A01 v6 Termux server: chunked HTTP live stream,
                // no-cache, Streaming transfer mode, and Connection: close semantics.
                val header = buildString {
                    append(if (http10) "HTTP/1.0 200 OK\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: video/mpeg\r\n")
                    append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    append("Pragma: no-cache\r\n")
                    append("Connection: close\r\n")
                    if (!http10) append("Transfer-Encoding: chunked\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append("contentFeatures.dlna.org: ").append(DlnaController.DLNA_FEATURES).append("\r\n")
                    append("\r\n")
                }
                output.write(header.toByteArray(StandardCharsets.US_ASCII))
                output.flush()

                if (method == "HEAD") {
                    socket.close()
                    return@Thread
                }

                broadcaster.attach(socket, output, !http10)
                while (running && !socket.isClosed) {
                    Thread.sleep(1000)
                }
            } catch (_: Throwable) {
                try { socket.close() } catch (_: Exception) {}
            }
        }.also {
            it.name = "A01-HTTP-client"
            it.start()
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: String,
        contentType: String,
        body: String,
        close: Boolean
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.0 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: ${if (close) "close" else "keep-alive"}\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}

fun localIpv4(context: Context): String {
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        if (network != null && caps != null && (
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                )) {
            cm.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                val address = link.address
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress ?: "127.0.0.1"
                }
            }
        }
    } catch (_: Exception) {
    }

    return try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val preferred = interfaces.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
        for (network in preferred) {
            if (!network.isUp || network.isLoopback) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress ?: continue
                }
            }
        }
        "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}

fun localIpv4For(context: Context, remoteHost: String): String =
    DlnaController.localAddressToward(remoteHost) ?: localIpv4(context)
