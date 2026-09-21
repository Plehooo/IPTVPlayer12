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

private const val TS_CHUNK_BYTES = 188 * 7
private const val TS_FLUSH_BYTES = TS_CHUNK_BYTES

// Antrean per klien: cukup dalam supaya lonjakan Wi-Fi / CPU (game berat) tidak langsung memutus stream.
private const val CLIENT_VIDEO_QUEUE_CAPACITY = 24
private const val CLIENT_AUDIO_QUEUE_CAPACITY = 48
private const val CLIENT_TABLE_QUEUE_CAPACITY = 8
// Keep the renderer close to the live edge. A long queue is what turns a short CPU/Wi-Fi hiccup
// into several seconds of perceived delay on cheap DLNA firmware.
private const val MAX_CLIENT_VIDEO_AGE_NS = 300_000_000L
private const val MAX_CLIENT_AUDIO_AGE_NS = 500_000_000L
private const val LIVE_PACE_MULTIPLIER = 1.03
private const val MAX_AUDIO_BEHIND_VIDEO_90K = 27_000L // 300 ms
private const val RESYNC_AUDIO_MAX_ITEMS = 10 // ~240 ms @ 48 kHz / 1152 sampel per MP3 frame

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
         * Video dan audio sengaja punya antrean terpisah. Satu access-unit IDR bisa ratusan KB;
         * bila FIFO tunggal dipakai, audio dapat terjebak di belakang IDR dan terdengar patah.
         * Writer mengambil unit 7 TS (1316 byte) bergantian berdasarkan PTS, sehingga audio dapat
         * menyisip di tengah IDR tanpa mengubah struktur MPEG-TS.
         */
        private val videoQueue = ArrayBlockingQueue<QueuedPacket>(CLIENT_VIDEO_QUEUE_CAPACITY)
        private val audioQueue = ArrayBlockingQueue<QueuedPacket>(CLIENT_AUDIO_QUEUE_CAPACITY)
        private val tableQueue = ArrayBlockingQueue<QueuedPacket>(CLIENT_TABLE_QUEUE_CAPACITY)

        @Volatile private var closed = false
        @Volatile private var waitingForKey = false
        private var writer: Thread? = null
        private var nextWireNs = 0L
        private var lastVideoPts90k = -1L
        private var currentVideo: QueuedPacket? = null
        private var currentAudio: QueuedPacket? = null
        private var currentVideoOffset = 0
        private var currentAudioOffset = 0
        private var pendingFlushBytes = 0

        /** Isi awal (tanpa menulis ke socket): PAT/PMT, plus IDR bila masih segar. */
        fun prime(pat: ByteArray, pmt: ByteArray, keyFrame: ByteArray?, keyPts90k: Long) {
            val now = System.nanoTime()
            tableQueue.offer(QueuedPacket(pat, KIND_TABLE, false, -1L, now))
            tableQueue.offer(QueuedPacket(pmt, KIND_TABLE, false, -1L, now))
            if (keyFrame != null) {
                videoQueue.offer(QueuedPacket(keyFrame, KIND_VIDEO, true, keyPts90k, now))
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
                    kind == KIND_TABLE -> return tableQueue.offer(
                        QueuedPacket(bytes, kind, keyFrame, pts90k, System.nanoTime())
                    )
                    keyFrame -> {
                        waitingForKey = false
                        // Jangan buang antrean audio: audio yang sudah direkam sebelum IDR baru masih
                        // lebih berguna daripada membuat silence gap. Packet audio yang terlalu tua tetap
                        // dibuang oleh age/PTS guard di writer.
                        videoQueue.clear()
                        currentVideo = null
                        currentVideoOffset = 0
                        lastVideoPts90k = pts90k
                    }
                    kind == KIND_AUDIO -> {
                        while (audioQueue.size >= RESYNC_AUDIO_MAX_ITEMS) {
                            audioQueue.poll()
                            onDropAudio()
                        }
                        audioQueue.offer(QueuedPacket(bytes, kind, keyFrame, pts90k, System.nanoTime()))
                        return true
                    }
                    else -> return true // Saat resync, buang video P dan tunggu satu IDR baru.
                }
            }

            if (kind == KIND_AUDIO && lastVideoPts90k >= 0L && pts90k >= 0L &&
                pts90k + MAX_AUDIO_BEHIND_VIDEO_90K < lastVideoPts90k
            ) {
                onDropAudio()
                return true
            }

            if (kind == KIND_VIDEO && pts90k >= 0L) {
                if (!keyFrame && lastVideoPts90k >= 0L && pts90k + 4_500L < lastVideoPts90k) {
                    onDropVideo()
                    return true
                }
                lastVideoPts90k = pts90k
            }

            val item = QueuedPacket(bytes, kind, keyFrame, pts90k, System.nanoTime())
            return when (kind) {
                KIND_TABLE -> {
                    // PAT/PMT dikirim berulang. Bila slot tabel sedang penuh, cukup buang tabel ini;
                    // jangan memutus klien yang masih memiliki video/audio yang valid.
                    tableQueue.offer(item)
                    true
                }
                KIND_VIDEO -> {
                    if (videoQueue.offer(item)) {
                        true
                    } else if (keyFrame) {
                        // IDR harus selalu mendapat slot: buang backlog video dan masuk live-edge; audio segar dipertahankan.
                        videoQueue.clear()
                        currentVideo = null
                        currentVideoOffset = 0
                        videoQueue.offer(item).also { accepted ->
                            onDropVideo()
                            if (!accepted) {
                                waitingForKey = true
                                onNeedKey()
                            }
                        }
                    } else {
                        waitingForKey = true
                        videoQueue.clear()
                        currentVideo = null
                        currentVideoOffset = 0
                        currentAudio = null
                        currentAudioOffset = 0
                        while (audioQueue.size > RESYNC_AUDIO_MAX_ITEMS) {
                            audioQueue.poll()
                            onDropAudio()
                        }
                        onDropVideo()
                        onNeedKey()
                        true
                    }
                }
                KIND_AUDIO -> {
                    if (audioQueue.offer(item)) {
                        true
                    } else {
                        // Audio loss tidak boleh memicu penurunan bitrate video.
                        audioQueue.poll()
                        onDropAudio()
                        audioQueue.offer(item)
                    }
                }
                else -> true
            }
        }

        fun resetForRecovery() {
            waitingForKey = true
            videoQueue.clear()
            tableQueue.clear()
            currentVideo = null
            currentVideoOffset = 0
            while (audioQueue.size > RESYNC_AUDIO_MAX_ITEMS) {
                audioQueue.poll()
                onDropAudio()
            }
            currentAudio = null
            currentAudioOffset = 0
            nextWireNs = 0L
            lastVideoPts90k = -1L
            pendingFlushBytes = 0
        }

        fun start() {
            writer = Thread {
                try {
                    // Network writer jangan memakai DISPLAY priority; itu bisa merebut waktu CPU/UI
                    // dari game/browser yang sedang dibuka user.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                } catch (_: Throwable) {}

                try {
                    while (!closed) {
                        if (waitingForKey) {
                            // Tahan audio yang sudah ter-buffer sampai IDR baru masuk; ini menghindari
                            // silence gap yang panjang ketika video harus resync.
                            currentVideo = null
                            currentVideoOffset = 0
                            currentAudio = null
                            currentAudioOffset = 0
                        }

                        val wrote = writeOneSmoothChunk()
                        if (!wrote) {
                            // Tunggu singkat, tetapi jangan sleep 30-50 ms karena akan terasa sebagai audio gap.
                            if (tableQueue.isEmpty() && videoQueue.isEmpty() && audioQueue.isEmpty()) {
                                Thread.sleep(2)
                            } else {
                                Thread.yield()
                            }
                        }
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

        private fun writeOneSmoothChunk(): Boolean {
            // Tabel selalu didahulukan, seperti flush_headers pada ffmpeg tar v6.
            val table = tableQueue.poll()
            if (table != null) {
                val age = System.nanoTime() - table.enqueuedNs
                if (age <= 1_000_000_000L) {
                    writeRawChunk(table.data, 0, table.data.size)
                    flushIfNeeded(force = true)
                    return true
                }
            }

            ensureActivePackets()
            if (currentVideo == null && currentAudio == null) return false

            val chooseAudio = when {
                currentVideo == null -> true
                currentAudio == null -> false
                else -> currentAudio!!.pts90k <= currentVideo!!.pts90k + 2_250L // ≈25 ms
            }

            val packet: QueuedPacket
            val offset: Int
            if (chooseAudio) {
                packet = currentAudio ?: return false
                offset = currentAudioOffset
            } else {
                packet = currentVideo ?: return false
                offset = currentVideoOffset
            }

            val age = System.nanoTime() - packet.enqueuedNs
            if (packet.kind == KIND_VIDEO && packet.keyFrame && age > 600_000_000L) {
                // Jangan pernah mengirim IDR yang sudah tua dengan burst untuk mengejar live edge.
                // Lebih baik minta IDR baru dan tetap menjaga audio yang masih segar.
                onDropVideo()
                waitingForKey = true
                videoQueue.clear()
                currentVideo = null
                currentVideoOffset = 0
                while (audioQueue.size > RESYNC_AUDIO_MAX_ITEMS) {
                    audioQueue.poll()
                    onDropAudio()
                }
                onNeedKey()
                return true
            }
            if (packet.kind == KIND_VIDEO && !packet.keyFrame && age > MAX_CLIENT_VIDEO_AGE_NS) {
                onDropVideo()
                waitingForKey = true
                videoQueue.clear()
                currentVideo = null
                currentVideoOffset = 0
                while (audioQueue.size > RESYNC_AUDIO_MAX_ITEMS) {
                    audioQueue.poll()
                    onDropAudio()
                }
                onNeedKey()
                return true
            }
            if (packet.kind == KIND_AUDIO && age > MAX_CLIENT_AUDIO_AGE_NS) {
                onDropAudio()
                currentAudio = null
                currentAudioOffset = 0
                return true
            }
            if (packet.kind == KIND_AUDIO && packet.pts90k >= 0L && lastVideoPts90k >= 0L &&
                packet.pts90k + MAX_AUDIO_BEHIND_VIDEO_90K < lastVideoPts90k
            ) {
                onDropAudio()
                currentAudio = null
                currentAudioOffset = 0
                return true
            }

            val len = minOf(TS_CHUNK_BYTES, packet.data.size - offset)
            if (len <= 0) {
                if (packet === currentVideo) {
                    currentVideo = null
                    currentVideoOffset = 0
                } else if (packet === currentAudio) {
                    currentAudio = null
                    currentAudioOffset = 0
                }
                return true
            }

            paceAndWriteChunk(packet.data, offset, len)
            if (packet === currentVideo) {
                currentVideoOffset += len
                if (currentVideoOffset >= packet.data.size) {
                    currentVideo = null
                    currentVideoOffset = 0
                }
            } else {
                currentAudioOffset += len
                if (currentAudioOffset >= packet.data.size) {
                    currentAudio = null
                    currentAudioOffset = 0
                }
            }
            return true
        }

        private fun ensureActivePackets() {
            if (currentVideo == null) {
                currentVideo = videoQueue.poll()
                currentVideoOffset = 0
            }
            if (currentAudio == null) {
                currentAudio = audioQueue.poll()
                currentAudioOffset = 0
            }
        }

        private fun paceAndWriteChunk(packet: ByteArray, offset: Int, len: Int) {
            val rateBytesPerSec = (bitrateProvider().coerceAtLeast(700_000L) * LIVE_PACE_MULTIPLIER / 8.0)
                .toLong().coerceAtLeast(1L)
            var now = System.nanoTime()
            // Bila writer sempat tertahan, jangan mengejar backlog dengan burst. Reset jadwal ke
            // waktu sekarang setelah >8 ms terlambat, lalu kembali ke jarak wire normal.
            if (nextWireNs == 0L || nextWireNs < now - 8_000_000L) nextWireNs = now
            val waitNs = nextWireNs - now
            if (waitNs > 0L) sleepNanos(waitNs)
            writeRawChunk(packet, offset, len)
            now = System.nanoTime()
            val durationNs = (len.toDouble() * 1_000_000_000.0 / rateBytesPerSec)
                .toLong().coerceAtLeast(100_000L)
            nextWireNs = maxOf(nextWireNs + durationNs, now)
            pendingFlushBytes += len
            flushIfNeeded(force = false)
        }

        private fun flushIfNeeded(force: Boolean) {
            if (force || pendingFlushBytes >= TS_FLUSH_BYTES) {
                output.flush()
                pendingFlushBytes = 0
            }
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

        private fun writeRawChunk(packet: ByteArray, offset: Int, len: Int) {
            if (!chunked) {
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
            try { output.flush() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            videoQueue.clear()
            audioQueue.clear()
            tableQueue.clear()
            currentVideo = null
            currentAudio = null
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
