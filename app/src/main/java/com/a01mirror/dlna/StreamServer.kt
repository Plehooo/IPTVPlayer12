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
import java.util.concurrent.locks.LockSupport

private const val TS_CHUNK_BYTES = 188 * 7
private const val TS_FLUSH_BYTES = TS_CHUNK_BYTES

// Antrean per klien: kecil di RAM (hanya beberapa frame), tetapi cukup dalam untuk menyerap lonjakan
// Wi-Fi/CPU beberapa ratus ms tanpa resync. Umur maksimum dinilai HANYA saat sebuah frame mulai dikirim,
// jadi IDR besar tidak pernah dipotong di tengah jalan.
private const val CLIENT_VIDEO_QUEUE_CAPACITY = 30
private const val CLIENT_AUDIO_QUEUE_CAPACITY = 64
private const val CLIENT_TABLE_QUEUE_CAPACITY = 8
private const val MAX_CLIENT_VIDEO_AGE_NS = 800_000_000L
private const val MAX_CLIENT_KEY_START_AGE_NS = 1_500_000_000L
private const val MAX_CLIENT_AUDIO_AGE_NS = 2_000_000_000L

// Pacing: rata-rata 2x bitrate (halus), 4x saat ada backlog atau saat mengirim IDR supaya IDR besar
// tidak menahan frame P di belakangnya dan tidak memicu resync berulang.
private const val PACE_MULTIPLIER_STEADY = 2.0
private const val PACE_MULTIPLIER_BACKLOG = 4.0
private const val PACE_MULTIPLIER_KEY = 4.0

// Rekaman: antrean dibatasi memori (mengikuti heap HP) dan tidak pernah mati permanen karena disk sesaat lambat.
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
    // Satu muxer per kelompok PID: tidak ada lock silang antara muxing video besar, audio kecil,
    // dan PAT/PMT. Continuity counter memang bersifat per-PID, jadi pemisahan ini tetap valid.
    private val videoMuxer = MpegTsMuxer()
    private val audioMuxer = MpegTsMuxer()
    private val tableMuxer = MpegTsMuxer()
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
    // Batas RAM antrean rekaman mengikuti heap aplikasi di HP ini (3-12 MB), bukan angka tetap.
    private val recordQueueMaxBytes: Long =
        (Runtime.getRuntime().maxMemory() / 24L).coerceIn(3L * 1024L * 1024L, 12L * 1024L * 1024L)
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
    @Volatile var droppedClientAudioPackets: Long = 0L
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

    /** Ringkasan kualitas yang sedang berjalan (ditampilkan di layar Live). */
    @Volatile var liveQuality: String = ""
        private set

    fun updateLiveQuality(text: String) {
        liveQuality = text
    }

    val videoBitrateTarget: Long get() = targetVideoBitrate

    /** Throughput kirim yang terukur saat link ke STB membatasi (bit/dtk); 0 = tidak dibatasi / belum diketahui. */
    fun measuredGoodputBps(): Long {
        var best = 0L
        for (client in clients) {
            val value = client.goodputBps
            if (value > 0L && (best == 0L || value < best)) best = value
        }
        return best
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

    fun publishVideo(accessUnit: ByteArray, pts90k: Long, keyFrame: Boolean) {
        // Muxing dilakukan di luar lock publisher. Lock hanya dipakai saat memasukkan access-unit
        // ke jalur live/recording, sehingga thread audio tidak ikut menunggu pembuatan IDR besar.
        val packet = videoMuxer.videoPes(accessUnit, pts90k, keyFrame)
        synchronized(this) {
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
    }

    fun publishAudio(mp3: ByteArray, pts90k: Long) {
        if (mp3.isEmpty()) return
        // Separate audio muxer menghindari contention dengan access-unit video yang bisa besar.
        val packet = audioMuxer.audioPes(mp3, pts90k)
        synchronized(this) {
            publish(packet, KIND_AUDIO, false, pts90k)
        }
    }

    @Synchronized
    private fun publish(packet: ByteArray, kind: Int, keyFrame: Boolean, pts90k: Long = -1L) {
        if (packet.isEmpty()) return
        bytesPublished += packet.size.toLong()

        val now = System.nanoTime()
        val isKeyVideo = kind == KIND_VIDEO && keyFrame
        // PAT/PMT tiap 250 ms dan selalu tepat sebelum IDR (titik masuk klien baru & rekaman).
        if (isKeyVideo || now - lastTablesNs >= 250_000_000L) {
            val pat = tableMuxer.patPacket()
            val pmt = tableMuxer.pmtPacket()
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
        if (recordingQueuedBytes.get() + data.size > recordQueueMaxBytes) return false
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
                // Prioritas normal: thread BACKGROUND kelaparan CPU saat game/YouTube berat sehingga rekaman bolong.
                // Beban CPU-nya kecil (hanya menulis ~300 KB/dtk), I/O lambat tidak menyentuh jalur live.
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) } catch (_: Throwable) {}
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
            socket.sendBufferSize = 128 * 1024
            socket.receiveBufferSize = 16 * 1024
        } catch (_: Exception) {}
        try { socket.trafficClass = 0x10 } catch (_: Exception) {}

        val client = Client(
            socket,
            outputStream,
            { targetVideoBitrate + TARGET_AUDIO_BITRATE },
            { droppedClientPackets += 1L },
            { droppedClientAudioPackets += 1L },
            { requestKeyFrame() },
            chunked
        )
        try {
            // Tidak ada penulisan socket di sini (dulu memblokir lock broadcaster dan menahan encoder).
            // IDR tersimpan hanya dipakai bila belum ada frame P sesudahnya; kalau sudah ada, klien
            // menunggu IDR baru (diminta ke encoder) supaya tidak ada artefak referensi hilang.
            val pat = latestPat ?: tableMuxer.patPacket()
            val pmt = latestPmt ?: tableMuxer.pmtPacket()
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
        private val onDropVideo: () -> Unit,
        private val onDropAudio: () -> Unit,
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

        /*
         * Tiga antrean terpisah (tabel, audio, video). Writer SELALU mengirim tabel lalu audio lebih dulu
         * (kecil, ~6% bandwidth), baru satu potongan 7 paket TS video. Dengan begitu audio tidak pernah
         * menunggu IDR besar selesai (penyebab audio patah tiap GOP). Seluruh state pengiriman hanya
         * disentuh thread writer; thread encoder/audio hanya mengisi antrean dan menaikkan `epoch`
         * bila video harus resync, jadi tidak ada race antar-thread.
         */
        private val videoQueue = ArrayBlockingQueue<QueuedPacket>(CLIENT_VIDEO_QUEUE_CAPACITY)
        private val audioQueue = ArrayBlockingQueue<QueuedPacket>(CLIENT_AUDIO_QUEUE_CAPACITY)
        private val tableQueue = ArrayBlockingQueue<QueuedPacket>(CLIENT_TABLE_QUEUE_CAPACITY)

        @Volatile private var closed = false
        @Volatile private var waitingForKey = false
        @Volatile private var epoch = 0
        @Volatile private var writer: Thread? = null

        /** Throughput terukur saat link membatasi (bit/dtk); 0 = tidak dibatasi / belum diketahui. */
        @Volatile var goodputBps = 0L
            private set

        // --- state milik thread writer ---
        private var seenEpoch = 0
        private var currentVideo: QueuedPacket? = null
        private var currentVideoOffset = 0
        private var nextWireNs = 0L
        private var pendingFlushBytes = 0
        private var windowStartNs = 0L
        private var windowBytes = 0L
        private var windowIoNs = 0L

        /** Isi awal (tanpa menulis ke socket): PAT/PMT, plus IDR bila masih segar. */
        fun prime(pat: ByteArray, pmt: ByteArray, keyFrame: ByteArray?, keyPts90k: Long) {
            val now = System.nanoTime()
            tableQueue.offer(QueuedPacket(pat, KIND_TABLE, false, -1L, now))
            tableQueue.offer(QueuedPacket(pmt, KIND_TABLE, false, -1L, now))
            if (keyFrame != null) {
                videoQueue.offer(QueuedPacket(keyFrame, KIND_VIDEO, true, keyPts90k, now))
            } else {
                waitingForKey = true
            }
        }

        private fun wakeUp() {
            val w = writer
            if (w != null) LockSupport.unpark(w)
        }

        /** Dipanggil thread encoder/audio (di dalam lock broadcaster). Tidak pernah memblokir. */
        fun offer(bytes: ByteArray, kind: Int, keyFrame: Boolean, pts90k: Long): Boolean {
            if (closed) return false
            val now = System.nanoTime()

            if (kind == KIND_TABLE) {
                tableQueue.offer(QueuedPacket(bytes, kind, false, pts90k, now)) // penuh = buang, tabel dikirim ulang tiap 250 ms
                wakeUp()
                return true
            }

            if (kind == KIND_AUDIO) {
                val item = QueuedPacket(bytes, kind, false, pts90k, now)
                if (!audioQueue.offer(item)) {
                    // Audio tidak pernah memicu penurunan bitrate video; cukup buang yang tertua.
                    audioQueue.poll()
                    onDropAudio()
                    audioQueue.offer(item)
                }
                wakeUp()
                return true
            }

            // Video
            if (waitingForKey) {
                if (!keyFrame) return true // frame P tanpa IDR acuan tidak boleh dikirim
                waitingForKey = false
                videoQueue.clear()
                epoch++
            }
            val item = QueuedPacket(bytes, kind, keyFrame, pts90k, now)
            if (videoQueue.offer(item)) {
                wakeUp()
                return true
            }

            // Antrean video penuh = klien tak sanggup mengikuti arus.
            onDropVideo()
            videoQueue.clear()
            epoch++
            if (keyFrame) {
                videoQueue.offer(item)
            } else {
                // Membuang frame P satu per satu membuat artefak; kosongkan, tunggu IDR baru (diminta ke encoder).
                waitingForKey = true
                onNeedKey()
            }
            wakeUp()
            return true
        }

        fun resetForRecovery() {
            waitingForKey = true
            videoQueue.clear()
            tableQueue.clear()
            epoch++
            wakeUp()
        }

        fun start() {
            writer = Thread {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                } catch (_: Throwable) {}

                try {
                    while (!closed) {
                        // Tidak ada data: tidur singkat (bisa dibangunkan offer) — hemat CPU/baterai.
                        if (!writeNext()) LockSupport.parkNanos(4_000_000L)
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

        /** Menulis satu unit kerja. Mengembalikan false bila tidak ada apa pun untuk dikirim. */
        private fun writeNext(): Boolean {
            val currentEpoch = epoch
            if (currentEpoch != seenEpoch) {
                seenEpoch = currentEpoch
                currentVideo = null
                currentVideoOffset = 0
                nextWireNs = 0L
            }

            val table = tableQueue.poll()
            if (table != null) {
                if (System.nanoTime() - table.enqueuedNs <= 1_000_000_000L) {
                    writeRaw(table.data, 0, table.data.size)
                    flushNow()
                }
                return true
            }

            val audio = audioQueue.poll()
            if (audio != null) {
                if (System.nanoTime() - audio.enqueuedNs > MAX_CLIENT_AUDIO_AGE_NS) {
                    onDropAudio()
                } else {
                    var offset = 0
                    while (offset < audio.data.size) {
                        val len = minOf(TS_CHUNK_BYTES, audio.data.size - offset)
                        writeRaw(audio.data, offset, len)
                        offset += len
                    }
                    flushNow()
                }
                return true
            }

            var packet = currentVideo
            if (packet == null) {
                val next = videoQueue.poll() ?: return false
                if (waitingForKey && !next.keyFrame) return true
                val age = System.nanoTime() - next.enqueuedNs
                val tooOld = if (next.keyFrame) age > MAX_CLIENT_KEY_START_AGE_NS else age > MAX_CLIENT_VIDEO_AGE_NS
                if (tooOld) {
                    // Terlalu jauh tertinggal SEBELUM mulai dikirim: resync bersih di IDR berikutnya.
                    onDropVideo()
                    waitingForKey = true
                    videoQueue.clear()
                    onNeedKey()
                    return true
                }
                currentVideo = next
                currentVideoOffset = 0
                packet = next
            }

            val offset = currentVideoOffset
            val len = minOf(TS_CHUNK_BYTES, packet.data.size - offset)
            if (len <= 0) {
                currentVideo = null
                currentVideoOffset = 0
                return true
            }
            paceAndWriteChunk(packet.data, offset, len, packet.keyFrame)
            currentVideoOffset = offset + len
            if (currentVideoOffset >= packet.data.size) {
                currentVideo = null
                currentVideoOffset = 0
            }
            return true
        }

        private fun paceAndWriteChunk(packet: ByteArray, offset: Int, len: Int, keyFrame: Boolean) {
            val multiplier = when {
                keyFrame -> PACE_MULTIPLIER_KEY
                videoQueue.size > 1 -> PACE_MULTIPLIER_BACKLOG
                else -> PACE_MULTIPLIER_STEADY
            }
            val rateBytesPerSec = (bitrateProvider().coerceAtLeast(500_000L) * multiplier / 8.0)
                .toLong().coerceAtLeast(1L)
            var now = System.nanoTime()
            // Bila writer sempat tertahan, jangan mengejar backlog dengan semburan: mulai jadwal baru dari sekarang.
            if (nextWireNs == 0L || nextWireNs < now - 20_000_000L) nextWireNs = now
            val waitNs = nextWireNs - now
            if (waitNs >= 1_000_000L) sleepNanos(waitNs)
            writeRaw(packet, offset, len)
            pendingFlushBytes += len
            now = System.nanoTime()
            val durationNs = (len.toDouble() * 1_000_000_000.0 / rateBytesPerSec).toLong().coerceAtLeast(50_000L)
            nextWireNs = maxOf(nextWireNs + durationNs, now)
            if (pendingFlushBytes >= TS_FLUSH_BYTES) flushNow()
        }

        private fun sleepNanos(ns: Long) {
            try {
                val ms = ns / 1_000_000L
                val nano = (ns % 1_000_000L).toInt()
                if (ms > 0L || nano > 0) Thread.sleep(ms, nano)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun writeRaw(packet: ByteArray, offset: Int, len: Int) {
            val t0 = System.nanoTime()
            if (chunked) {
                val head = (Integer.toHexString(len) + "\r\n").toByteArray(StandardCharsets.US_ASCII)
                output.write(head)
                output.write(packet, offset, len)
                output.write(CRLF)
            } else {
                // Renderer HTTP/1.0: aliran mentah tanpa framing chunked.
                output.write(packet, offset, len)
            }
            windowBytes += len.toLong()
            windowIoNs += System.nanoTime() - t0
        }

        private fun flushNow() {
            val t0 = System.nanoTime()
            output.flush()
            val now = System.nanoTime()
            windowIoNs += now - t0
            pendingFlushBytes = 0
            updateGoodput(now)
        }

        /**
         * Link membatasi bila writer menghabiskan >= 50% waktunya di dalam write/flush socket (TCP penuh).
         * Saat itulah byte terkirim per detik = kecepatan link/STB yang sebenarnya, dipakai encoder untuk
         * menyetel bitrate. Saat link longgar hasilnya 0 (tidak dibatasi).
         */
        private fun updateGoodput(now: Long) {
            if (windowStartNs == 0L) {
                windowStartNs = now
                return
            }
            val elapsed = now - windowStartNs
            if (elapsed < 1_000_000_000L) return
            goodputBps = if (windowBytes > 0L && windowIoNs * 2L >= elapsed) {
                windowBytes * 8L * 1_000_000_000L / elapsed
            } else {
                0L
            }
            windowStartNs = now
            windowBytes = 0L
            windowIoNs = 0L
        }

        fun close() {
            if (closed) return
            closed = true
            val w = writer
            try { w?.interrupt() } catch (_: Exception) {}
            if (w != null) LockSupport.unpark(w)
            try { output.flush() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            videoQueue.clear()
            audioQueue.clear()
            tableQueue.clear()
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
