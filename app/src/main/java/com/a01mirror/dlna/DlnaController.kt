package com.a01mirror.dlna

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.regex.Pattern

object DlnaController {
    data class Renderer(
        val name: String,
        val host: String,
        val location: String,
        val avTransportControlUrl: String,
        val avTransportServiceType: String,
        val connectionManagerControlUrl: String? = null,
        val sinkProtocolInfo: String? = null
    )

    fun discover(): List<Renderer> {
        val locations = LinkedHashSet<String>()
        val sts = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "upnp:rootdevice",
            "ssdp:all"
        )
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            for (st in sts) {
                val request = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 1\r\n")
                    append("ST: ").append(st).append("\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                val deadline = System.currentTimeMillis() + 950L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val buf = ByteArray(8192)
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        val headers = parseHeaders(text)
                        headers["location"]?.let { locations.add(it) }
                    } catch (_: Exception) {
                        // Timeout / malformed SSDP packet; continue discovery.
                    }
                }
            }
        }

        return locations.mapNotNull { location ->
            try {
                parseRenderer(location)
            } catch (_: Exception) {
                null
            }
        }.filter { it.avTransportControlUrl.isNotBlank() }
    }

    fun playLive(renderer: Renderer, streamUrl: String): Result<Unit> {
        if (renderer.avTransportControlUrl.isBlank()) return Result.failure(IllegalStateException("AVTransport tidak tersedia"))
        val metadata = didlMetadata(streamUrl)
        return try {
            soap(
                renderer.avTransportControlUrl,
                renderer.avTransportServiceType,
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${xml(streamUrl)}</CurrentURI>" +
                    "<CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>"
            )
            soap(
                renderer.avTransportControlUrl,
                renderer.avTransportServiceType,
                "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>"
            )
            Result.success(Unit)
        } catch (first: Exception) {
            // Cheap DLNA firmware sometimes rejects DIDL metadata. Retry with an empty metadata field.
            try {
                soap(
                    renderer.avTransportControlUrl,
                    renderer.avTransportServiceType,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>${xml(streamUrl)}</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>"
                )
                soap(
                    renderer.avTransportControlUrl,
                    renderer.avTransportServiceType,
                    "Play",
                    "<InstanceID>0</InstanceID><Speed>1</Speed>"
                )
                Result.success(Unit)
            } catch (second: Exception) {
                Result.failure(IllegalStateException("DLNA gagal: ${second.message ?: first.message}"))
            }
        }
    }

    fun stop(renderer: Renderer) {
        try {
            soap(
                renderer.avTransportControlUrl,
                renderer.avTransportServiceType,
                "Stop",
                "<InstanceID>0</InstanceID>"
            )
        } catch (_: Exception) {
        }
    }

    private fun parseRenderer(location: String): Renderer {
        val xmlText = httpGet(location)
        val friendly = tag(xmlText, "friendlyName").ifBlank { "DLNA Renderer" }
        val serviceBlockPattern = Pattern.compile(
            "<service\\b[^>]*>(.*?)</service>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val matcher = serviceBlockPattern.matcher(xmlText)
        var avControl = ""
        var avType = "urn:schemas-upnp-org:service:AVTransport:1"
        var cmControl: String? = null
        while (matcher.find()) {
            val service = matcher.group(1)
            val type = tag(service, "serviceType")
            val control = tag(service, "controlURL")
            if (type.contains("AVTransport", ignoreCase = true) && control.isNotBlank()) {
                avControl = resolveUrl(location, control)
                avType = type
            }
            if (type.contains("ConnectionManager", ignoreCase = true) && control.isNotBlank()) {
                cmControl = resolveUrl(location, control)
            }
        }

        var sink: String? = null
        if (cmControl != null) {
            try {
                val response = soap(
                    cmControl!!,
                    "urn:schemas-upnp-org:service:ConnectionManager:1",
                    "GetProtocolInfo",
                    ""
                )
                sink = tag(response, "Sink")
            } catch (_: Exception) {
            }
        }

        return Renderer(
            name = friendly,
            host = URI(location).host ?: location,
            location = location,
            avTransportControlUrl = avControl,
            avTransportServiceType = avType,
            connectionManagerControlUrl = cmControl,
            sinkProtocolInfo = sink
        )
    }

    private fun soap(controlUrl: String, serviceType: String, action: String, args: String): String {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="$serviceType">$args</u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code $text")
        return text
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        return try {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun didlMetadata(url: String): String {
        val profile = "DLNA.ORG_OP=00;DLNA.ORG_CI=0"
        return """
            <DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"
                xmlns:dc=\"http://purl.org/dc/elements/1.1/\"
                xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">
              <item id=\"1\" parentID=\"-1\" restricted=\"1\">
                <dc:title>A01 Mirror Live</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo=\"http-get:*:video/mp2t:$profile\">$url</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun tag(text: String, name: String): String {
        val p = Pattern.compile("<$name[^>]*>(.*?)</$name>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        return p.matcher(text).let { if (it.find()) htmlUnescape(it.group(1).trim()) else "" }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        return text.split("\r\n").drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
    }

    private fun resolveUrl(base: String, path: String): String =
        URI(base).resolve(path).toString()

    private fun htmlUnescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    private fun xml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
