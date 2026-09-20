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

private const val TS_CHUNK_BYTES = 188 * 32
private const val CLIENT_QUEUE_CAPACITY = 4
private const val MAX_CLIENT_PACKET_AGE_NS = 180_000_000L
private const val RECORD_QUEUE_MAX_BYTES = 2L * 1024L * 1024L
private const val TARGET_AUDIO_BITRATE = 128_000L
private val CRLF = byteArrayOf(13, 10)

class TsBroadcaster(
    private val resolver: ContentResolver
) {
    private val muxer = MpegTsMuxer()
    private val clients = CopyOnWriteArrayList<Client>()
    private var output: OutputStream? = null
    private var pendingUri = android.net.Uri.EMPTY
    private var lastTablesNs = 0L

    @Volatile private var latestKeyFrameTs: ByteArray? = null
    @Volatile private var targetVideoBitrate = 2_000_000L
    @Volatile private var recordingFailed = false
    private val recordingQueue = ArrayBlockingQueue<ByteArray>(256)
    @Volatile private var recordingQueuedBytes = 0L
    @Volatile private var recordingRunning = false
    private var recordingThread: Thread? = null

    @Volatile var bytesPublished: Long = 0L
        private set
    @Volatile var videoFrames: Long = 0L
        private set
    @Volatile var droppedVideoFrames: Long = 0L
        private set
    @Volatile var droppedClientPackets: Long = 0L
        private set

    val sessionToken: String = UUID.randomUUID().toString().replace("-", "")

    init {
        openRecording()
        startRecordingWriter()
    }

    fun setTargetVideoBitrate(bitrate: Int) {
        targetVideoBitrate = bitrate.coerceAtLeast(600_000).toLong()
    }

    @Synchronized
    fun publishVideo(accessUnit: ByteArray, pts90k: Long, keyFrame: Boolean) {
        val packet = muxer.videoPes(accessUnit, pts90k, keyFrame)
        if (keyFrame) latestKeyFrameTs = packet
        publish(packet, keyFrame)
        videoFrames += 1
    }

    @Synchronized
    fun publishAudio(mp3: ByteArray, pts90k: Long) {
        if (mp3.isNotEmpty()) publish(muxer.audioPes(mp3, pts90k), false)
    }

    @Synchronized
    private fun publish(packet: ByteArray, keyFrame: Boolean) {
        if (packet.isEmpty()) return
        bytesPublished += packet.size.toLong()

        val now = System.nanoTime()
        if (now - lastTablesNs >= 250_000_000L) {
            val pat = muxer.patPacket()
            val pmt = muxer.pmtPacket()
            writeRecording(pat)
            writeRecording(pmt)
            // PAT/PMT are control tables, NOT keyframes. Marking them as keyframes
            // used to flush the 4-slot live queue every 250 ms and caused visible stutter.
            broadcast(pat, false)
            broadcast(pmt, false)
            lastTablesNs = now
        }

        writeRecording(packet)
        broadcast(packet, keyFrame)
    }

    /** Drop only recording pressure; the live DLNA path is never stopped for a slow disk. */
    private fun writeRecording(data: ByteArray) {
        if (output == null || recordingFailed || !recordingRunning) return
        val newSize = recordingQueuedBytes + data.size
        if (newSize > RECORD_QUEUE_MAX_BYTES || !recordingQueue.offer(data)) {
            recordingFailed = true
            recordingQueue.clear()
            recordingQueuedBytes = 0L
        } else {
            recordingQueuedBytes = newSize
        }
    }

    private fun startRecordingWriter() {
        if (output == null || recordingRunning) return
        recordingRunning = true
        recordingThread = Thread {
            try {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
                var lastFlushNs = System.nanoTime()
                while (recordingRunning || recordingQueue.isNotEmpty()) {
                    val data = recordingQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    recordingQueuedBytes = (recordingQueuedBytes - data.size).coerceAtLeast(0L)
                    val out = output ?: break
                    if (!recordingFailed) {
                        try {
                            out.write(data)
                            val now = System.nanoTime()
                            if (now - lastFlushNs >= 750_000_000L) {
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

    private fun broadcast(data: ByteArray, keyFrame: Boolean) {
        for (client in clients) {
            if (!client.offer(data, keyFrame)) {
                client.close()
                clients.remove(client)
            }
        }
    }

    @Synchronized
    fun resetLiveForCodecRecovery() {
        latestKeyFrameTs = null
        for (client in clients) client.resetForRecovery()
    }

    @Synchronized
    fun attach(socket: Socket, outputStream: OutputStream) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = 16 * 1024
            socket.receiveBufferSize = 16 * 1024
        } catch (_: Exception) {}
        try { socket.trafficClass = 0x10 } catch (_: Exception) {}

        val client = Client(
            socket,
            outputStream,
            { targetVideoBitrate + TARGET_AUDIO_BITRATE },
            { droppedClientPackets += 1L }
        )
        try {
            // Bootstrap synchronously: A01 receives PAT/PMT + the newest IDR before
            // the asynchronous live writer begins, avoiding an extra startup backlog.
            client.bootstrap(muxer.patPacket(), muxer.pmtPacket(), latestKeyFrameTs)
            clients.add(client)
            client.start()
        } catch (_: Throwable) {
            client.close()
        }
    }

    @Synchronized
    fun trimRecordingPressure() {
        recordingQueue.clear()
        recordingQueuedBytes = 0L
        recordingFailed = true
    }

    @Synchronized
    fun close() {
        for (client in clients) client.close()
        clients.clear()

        recordingRunning = false
        try { recordingThread?.interrupt() } catch (_: Exception) {}
        try { recordingThread?.join(900) } catch (_: InterruptedException) {}
        recordingThread = null
        recordingQueue.clear()
        recordingQueuedBytes = 0L

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
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "A01Mirror_$stamp.ts")
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
                resolver.openOutputStream(pendingUri, "w")?.let { BufferedOutputStream(it, 64 * 1024) }
            } catch (_: Exception) {
                null
            }
        } else null
    }

    private class Client(
        private val socket: Socket,
        outputStream: OutputStream,
        private val bitrateProvider: () -> Long,
        private val onDrop: () -> Unit
    ) {
        private val output: OutputStream = BufferedOutputStream(outputStream, 16 * 1024)

        private data class QueuedPacket(
            val data: ByteArray,
            val keyFrame: Boolean,
            val enqueuedNs: Long
        )

        private val queue = ArrayBlockingQueue<QueuedPacket>(CLIENT_QUEUE_CAPACITY)
        @Volatile private var closed = false
        private var writer: Thread? = null
        private var nextWireNs = 0L

        fun bootstrap(pat: ByteArray, pmt: ByteArray, keyFrame: ByteArray?) {
            writeChunked(pat)
            writeChunked(pmt)
            keyFrame?.let(::writeChunked)
            output.flush()
        }

        fun offer(bytes: ByteArray, keyFrame: Boolean): Boolean {
            if (closed) return false
            val item = QueuedPacket(bytes, keyFrame, System.nanoTime())
            if (queue.offer(item)) return true

            if (keyFrame) {
                if (queue.isNotEmpty()) onDrop()
                queue.clear()
                return queue.offer(item)
            }

            // Keep the newest live data. Prefer removing an old non-keyframe packet
            // rather than preserving stale data just because it arrived first.
            val iterator = queue.iterator()
            var removed = false
            while (iterator.hasNext()) {
                val old = iterator.next()
                if (!old.keyFrame) {
                    removed = queue.remove(old)
                    if (removed) {
                        onDrop()
                        break
                    }
                }
            }
            if (removed) return queue.offer(item)
            onDrop()
            return true
        }

        fun resetForRecovery() {
            queue.clear()
            nextWireNs = 0L
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
                        if (!queued.keyFrame && age > MAX_CLIENT_PACKET_AGE_NS) continue

                        // If the client has no backlog, don't manufacture latency. If it has
                        // backlog, smooth the wire rate close to the actual multiplexed bitrate
                        // so the A01 doesn't receive a huge burst that its player then buffers.
                        if (queue.isNotEmpty() || nextWireNs > System.nanoTime()) {
                            paceAndWrite(queued.data)
                        } else {
                            writeChunked(queued.data)
                            nextWireNs = System.nanoTime()
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

        private fun paceAndWrite(packet: ByteArray) {
            var offset = 0
            val rateBytesPerSec = (bitrateProvider().coerceAtLeast(700_000L) * 1.20 / 8.0).toLong().coerceAtLeast(1L)
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
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: video/mpeg\r\n")
                    append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    append("Pragma: no-cache\r\n")
                    append("Connection: close\r\n")
                    append("Transfer-Encoding: chunked\r\n")
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

                broadcaster.attach(socket, output)
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
