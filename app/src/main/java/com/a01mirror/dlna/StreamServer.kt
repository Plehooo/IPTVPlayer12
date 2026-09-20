package com.a01mirror.dlna

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.InputStreamReader
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
import java.util.concurrent.CopyOnWriteArrayList

class TsBroadcaster(
    private val resolver: ContentResolver,
    private val width: Int,
    private val height: Int
) {
    private val muxer = MpegTsMuxer()
    private val clients = CopyOnWriteArrayList<Client>()
    private val startedAt = System.nanoTime()
    private var lastTablesNs = 0L
    private var output: OutputStream? = null
    private var pendingUri = android.net.Uri.EMPTY

    val sessionToken: String = UUID.randomUUID().toString().replace("-", "")

    init {
        openRecording()
    }

    @Synchronized
    fun publishVideo(accessUnit: ByteArray, pts90k: Long, keyFrame: Boolean) {
        val packet = muxer.videoPes(accessUnit, pts90k, keyFrame)
        publish(packet)
    }

    @Synchronized
    fun publishAudio(mp3: ByteArray, pts90k: Long) {
        if (mp3.isEmpty()) return
        val packet = muxer.audioPes(mp3, pts90k)
        publish(packet)
    }

    @Synchronized
    private fun publish(packet: ByteArray) {
        if (System.nanoTime() - lastTablesNs > 500_000_000L) {
            publishRaw(muxer.patPacket())
            publishRaw(muxer.pmtPacket())
            lastTablesNs = System.nanoTime()
        }
        publishRaw(packet)
    }

    private fun publishRaw(data: ByteArray) {
        output?.let {
            try {
                it.write(data)
                it.flush()
            } catch (_: IOException) {
            }
        }

        for (client in clients) {
            try {
                client.output.write(data)
                client.output.flush()
            } catch (_: IOException) {
                client.close()
                clients.remove(client)
            }
        }
    }

    fun attach(socket: Socket, output: OutputStream) {
        val client = Client(socket, output)
        clients.add(client)
        try {
            output.write(muxer.patPacket())
            output.write(muxer.pmtPacket())
            output.flush()
        } catch (_: IOException) {
            client.close()
            clients.remove(client)
        }
    }

    fun close() {
        for (client in clients) client.close()
        clients.clear()
        synchronized(this) {
            try { output?.flush() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            output = null
            if (!pendingUri.equals(android.net.Uri.EMPTY)) {
                resolver.update(pendingUri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
        }
    }

    private fun openRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "A01Mirror_${stamp}.ts")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/A01Mirror")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        pendingUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: android.net.Uri.EMPTY
        output = if (!pendingUri.equals(android.net.Uri.EMPTY)) {
            resolver.openOutputStream(pendingUri, "w")
        } else {
            null
        }
    }

    private data class Client(val socket: Socket, val output: OutputStream) {
        fun close() {
            try { output.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
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
        }.also { it.name = "A01-HTTP"; it.start() }
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
            socket.soTimeout = 3000
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                val first = reader.readLine() ?: run { socket.close(); return@Thread }
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                val path = first.split(' ').getOrNull(1) ?: ""
                val expectedPath = "/a01/$token/stream.ts"
                val output = socket.getOutputStream()

                if (path != expectedPath) {
                    val body = "A01 Mirror"
                    output.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    output.flush()
                    socket.close()
                    return@Thread
                }

                val header = buildString {
                    append("HTTP/1.0 200 OK\r\n")
                    append("Content-Type: video/mp2t\r\n")
                    append("TransferMode.DLNA.ORG: Streaming\r\n")
                    append("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0\r\n")
                    append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    append("Pragma: no-cache\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(header.toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                broadcaster.attach(socket, output)
                // Keep this handler alive while the broadcaster writes to its socket.
                while (running && !socket.isClosed) Thread.sleep(1000)
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }.also { it.name = "A01-HTTP-client"; it.start() }
    }
}

fun localIpv4(): String {
    return try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress ?: continue
            }
        }
        "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}
