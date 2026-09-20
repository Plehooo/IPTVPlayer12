package com.a01mirror.dlna

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.BufferedOutputStream
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

/** 188 * 32 byte, sama dengan ukuran baca ffmpeg->TS di tar v6. */
private const val TS_CHUNK_BYTES = 188 * 64

class TsBroadcaster(
    private val resolver: ContentResolver
) {
    private val muxer = MpegTsMuxer()
    private val clients = CopyOnWriteArrayList<Client>()
    private var output: OutputStream? = null
    private var pendingUri = android.net.Uri.EMPTY
    private var lastTablesNs = 0L
    private var recordingFailed = false

    /** Byte TS & frame video yang sudah dihasilkan; dipakai menunggu data pertama sebelum Play. */
    @Volatile var bytesPublished: Long = 0L
        private set
    @Volatile var videoFrames: Long = 0L
        private set

    val sessionToken: String = UUID.randomUUID().toString().replace("-", "")

    init {
        openRecording()
    }

    @Synchronized
    fun publishVideo(accessUnit: ByteArray, pts90k: Long, keyFrame: Boolean) {
        publish(muxer.videoPes(accessUnit, pts90k, keyFrame))
        videoFrames += 1
    }

    @Synchronized
    fun publishAudio(mp3: ByteArray, pts90k: Long) {
        if (mp3.isNotEmpty()) publish(muxer.audioPes(mp3, pts90k))
    }

    @Synchronized
    private fun publish(packet: ByteArray) {
        if (packet.isEmpty()) return
        bytesPublished += packet.size
        if (System.nanoTime() - lastTablesNs >= 250_000_000L) {
            writeRecording(muxer.patPacket())
            writeRecording(muxer.pmtPacket())
            broadcast(muxer.patPacket())
            broadcast(muxer.pmtPacket())
            lastTablesNs = System.nanoTime()
        }
        writeRecording(packet)
        broadcast(packet)
    }

    private fun writeRecording(data: ByteArray) {
        val out = output ?: return
        if (recordingFailed) return
        try {
            out.write(data)
        } catch (_: IOException) {
            recordingFailed = true
        }
    }

    private fun broadcast(data: ByteArray) {
        for (client in clients) {
            if (!client.offer(data)) {
                client.close()
                clients.remove(client)
            }
        }
    }

    @Synchronized
    fun attach(socket: Socket, outputStream: OutputStream) {
        val client = Client(socket, outputStream)
        if (!client.offer(muxer.patPacket()) || !client.offer(muxer.pmtPacket())) {
            client.close()
            return
        }
        clients.add(client)
        client.start()
    }

    @Synchronized
    fun close() {
        for (client in clients) client.close()
        clients.clear()

        try { output?.flush() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        output = null

        if (pendingUri != android.net.Uri.EMPTY) {
            try {
                resolver.update(
                    pendingUri,
                    ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            } catch (_: Exception) {
            }
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
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: android.net.Uri.EMPTY
        } catch (_: Exception) {
            android.net.Uri.EMPTY
        }

        output = if (pendingUri != android.net.Uri.EMPTY) {
            try {
                resolver.openOutputStream(pendingUri, "w")?.let { BufferedOutputStream(it, 64 * 1024) }
            } catch (_: Exception) { null }
        } else null
    }

    private class Client(
        private val socket: Socket,
        private val output: OutputStream
    ) {
        // Antrean kecil menjaga stream tetap dekat realtime. Bila jaringan/STB tertinggal,
        // buang paket paling lama daripada menumpuk beberapa detik latency.
        private val queue = ArrayBlockingQueue<ByteArray>(12)
        @Volatile private var closed = false
        private var writer: Thread? = null

        fun offer(bytes: ByteArray): Boolean {
            if (closed) return false
            if (queue.offer(bytes)) return true
            // Prioritaskan data terbaru agar mirror tidak berubah menjadi delayed playback.
            repeat(4) {
                queue.poll() ?: return@repeat
                if (queue.offer(bytes)) return true
            }
            return queue.offer(bytes)
        }

        fun start() {
            writer = Thread {
                try {
                    while (!closed) {
                        val packet = queue.take()
                        // HTTP/1.1 chunked seperti send_chunk() di tar v6. Frame video besar dipotong
                        // per 188*32 byte (kelipatan paket TS) seperti p.stdout.read(188*32) di tar,
                        // supaya STB dengan buffer kecil tidak tersedak chunk ratusan KB.
                        var offset = 0
                        while (offset < packet.size) {
                            val len = minOf(TS_CHUNK_BYTES, packet.size - offset)
                            val head = (Integer.toHexString(len) + "\r\n").toByteArray(StandardCharsets.US_ASCII)
                            val frame = ByteArray(head.size + len + 2)
                            System.arraycopy(head, 0, frame, 0, head.size)
                            System.arraycopy(packet, offset, frame, head.size, len)
                            frame[frame.size - 2] = 13
                            frame[frame.size - 1] = 10
                            output.write(frame)
                            offset += len
                        }
                        output.flush()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    close()
                }
            }.also {
                it.name = "A01-DLNA-client"
                it.start()
            }
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
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
                )
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
                        headers[line.substring(0, index).trim().lowercase()] =
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

                // Header disamakan dengan begin_stream() di tar v6 (yang jalan di STB):
                // HTTP/1.1 chunked + contentFeatures/transferMode DLNA yang benar.
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: video/mpeg\r\n")
                    append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    append("Pragma: no-cache\r\n")
                    append("Connection: keep-alive\r\n")
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

                // Range probing is intentionally ignored: this is a live source with no fixed length.
                broadcaster.attach(socket, output)

                while (running && !socket.isClosed) {
                    Thread.sleep(1000)
                }
            } catch (_: Exception) {
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
    // Prefer the active Wi-Fi/Ethernet link so the URL advertised to the DLNA box
    // is not accidentally built from a VPN/cellular interface.
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
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

/**
 * IP HP yang dilihat STB. Memakai soket UDP "connect" ke IP STB (seperti local_ip_for_renderer()
 * di tar v6) sehingga benar untuk Wi-Fi biasa maupun hotspot HP; kalau gagal baru pakai
 * pencarian interface biasa.
 */
fun localIpv4For(context: Context, remoteHost: String): String =
    DlnaController.localAddressToward(remoteHost) ?: localIpv4(context)
