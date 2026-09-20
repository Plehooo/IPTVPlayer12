package com.a01mirror.dlna

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.regex.Pattern

/**
 * SSDP + UPnP AVTransport control point tuned for the inexpensive DLNA DMR
 * implementations commonly found in set-top boxes such as the Advance A01.
 *
 * The class keeps the original public surface but makes discovery/control much
 * more tolerant of Android phones that have mobile data, VPNs or multiple
 * network interfaces active at the same time.
 */
object DlnaController {
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val USER_AGENT = "A01Mirror/1.1 UPnP/1.1"
    private const val SEARCH_MX = 2
    private const val DISCOVERY_WAIT_MS = 2500L
    private const val UNICAST_SWEEP_LIMIT = 512L

    data class Renderer(
        val name: String,
        val host: String,
        val location: String,
        val avTransportControlUrl: String,
        val avTransportServiceType: String,
        val connectionManagerControlUrl: String? = null,
        val sinkProtocolInfo: String? = null
    )

    /** Discover DLNA renderers on the current Wi-Fi/LAN. */
    fun discover(): List<Renderer> {
        val context = DiscoveryContextHolder.context
        val wifi = context?.let { findWifiNetwork(it) }
        val lock = context?.let { getMulticastLock(it) }
        val locations = LinkedHashSet<String>()

        try {
            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 300

                // Important on Android with both Wi-Fi and mobile/VPN interfaces:
                // force the SSDP socket onto the Wi-Fi network that contains the STB.
                if (wifi?.network != null) {
                    try {
                        wifi.network.bindSocket(socket)
                    } catch (_: Throwable) {
                        // Fall back to the OS-selected route if binding is unavailable.
                    }
                }

                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:2",
                    "upnp:rootdevice",
                    "ssdp:all"
                )

                // UPnP recommends sending M-SEARCH more than once because UDP is
                // unreliable. Send two quick copies, then listen for the whole MX window.
                repeat(2) {
                    for (st in targets) {
                        try {
                            sendSearch(socket, st, InetAddress.getByName(SSDP_HOST), SSDP_PORT)
                        } catch (_: Exception) {
                        }
                    }
                    if (it == 0) Thread.sleep(40)
                }

                collectResponses(socket, DISCOVERY_WAIT_MS, locations)
            }
        } catch (_: Exception) {
            // A multicast failure must not prevent the unicast fallback below.
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }

        // Some home/ISP Wi-Fi firmware filters SSDP multicast. Because the phone
        // and STB are explicitly expected to be on the same LAN, probe the local
        // IPv4 subnet as a second path. This is only enabled for reasonably sized
        // home LANs so the app never performs a huge address sweep.
        if (locations.isEmpty() && wifi != null) {
            locations.addAll(unicastDiscovery(wifi))
        }

        return locations.asSequence()
            .mapNotNull { location ->
                try { parseRenderer(location) } catch (_: Exception) { null }
            }
            .filter { it.avTransportControlUrl.isNotBlank() }
            .distinctBy {
                "${it.host.lowercase()}|${it.avTransportControlUrl.lowercase()}"
            }
            .toList()
    }

    /** Supply an application context before calling discover(). */
    fun setDiscoveryContext(context: Context) {
        DiscoveryContextHolder.context = context.applicationContext
    }

    /**
     * Set a live URI on the DMR and start playback.
     *
     * A01-class renderers are often happier when the transport is explicitly
     * stopped before changing URI, and a few firmwares accept the URI with
     * metadata while others only accept the URI without metadata. We try both
     * without changing the external API used by the rest of the app.
     */
    fun playLive(renderer: Renderer, streamUrl: String): Result<Unit> {
        if (renderer.avTransportControlUrl.isBlank()) {
            return Result.failure(IllegalStateException("AVTransport tidak tersedia"))
        }
        if (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("URL stream tidak valid"))
        }

        val serviceTypes = linkedSetOf<String>().apply {
            add(renderer.avTransportServiceType.ifBlank {
                "urn:schemas-upnp-org:service:AVTransport:1"
            })
            add("urn:schemas-upnp-org:service:AVTransport:1")
        }

        val metadataAttempts = listOf(
            didlMetadata(streamUrl),
            ""
        )
        var lastError: Throwable? = null

        for (serviceType in serviceTypes) {
            for (metadata in metadataAttempts) {
                repeat(2) {
                    try {
                        // Defensive Stop: some DMRs return 705/transport-locked if
                        // SetAVTransportURI arrives while a previous item is active.
                        bestEffortStop(renderer.avTransportControlUrl, serviceType)
                        Thread.sleep(180)

                        soap(
                            renderer.avTransportControlUrl,
                            serviceType,
                            "SetAVTransportURI",
                            "<InstanceID>0</InstanceID>" +
                                "<CurrentURI>${xml(streamUrl)}</CurrentURI>" +
                                "<CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>"
                        )

                        // Give the A01 HTTP server a short head start after the URI
                        // is accepted, then issue the standard Play action.
                        Thread.sleep(450)
                        soap(
                            renderer.avTransportControlUrl,
                            serviceType,
                            "Play",
                            "<InstanceID>0</InstanceID><Speed>1</Speed>"
                        )
                        Thread.sleep(250)

                        return Result.success(Unit)
                    } catch (t: Throwable) {
                        lastError = t
                        try { Thread.sleep(250) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return Result.failure(IllegalStateException("DLNA dibatalkan"))
                        }
                    }
                }
            }
        }

        return Result.failure(
            IllegalStateException(
                "DLNA gagal ke ${renderer.name} (${renderer.host}): " +
                    (lastError?.message ?: "renderer menolak stream")
            )
        )
    }

    fun stop(renderer: Renderer) {
        val serviceTypes = linkedSetOf<String>().apply {
            add(renderer.avTransportServiceType)
            add("urn:schemas-upnp-org:service:AVTransport:1")
        }
        for (serviceType in serviceTypes) {
            try {
                soap(
                    renderer.avTransportControlUrl,
                    serviceType,
                    "Stop",
                    "<InstanceID>0</InstanceID>"
                )
                break
            } catch (_: Exception) {
                // A Stop on an idle renderer can legitimately return a SOAP fault.
            }
        }
    }

    private data class WifiNetworkInfo(
        val network: Network,
        val address: Inet4Address,
        val prefixLength: Int
    )

    private fun findWifiNetwork(context: Context): WifiNetworkInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val candidates = ArrayList<Network>()
        cm.activeNetwork?.let { candidates.add(it) }
        try {
            cm.allNetworks.forEach { network -> if (!candidates.contains(network)) candidates.add(network) }
        } catch (_: Throwable) {
        }

        for (network in candidates) {
            try {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val wifiOrEthernet =
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!wifiOrEthernet) continue

                val lp = cm.getLinkProperties(network) ?: continue
                val match = lp.linkAddresses.firstOrNull { link ->
                    val a = link.address
                    a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress
                } ?: continue

                return WifiNetworkInfo(
                    network = network,
                    address = match.address as Inet4Address,
                    prefixLength = match.prefixLength
                )
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun sendSearch(
        socket: DatagramSocket,
        searchTarget: String,
        address: InetAddress,
        port: Int
    ) {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: ").append(address.hostAddress).append(":").append(port).append("\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: ").append(SEARCH_MX).append("\r\n")
            append("ST: ").append(searchTarget).append("\r\n")
            append("USER-AGENT: ").append(USER_AGENT).append("\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)

        socket.send(DatagramPacket(request, request.size, address, port))
    }

    private fun collectResponses(
        socket: DatagramSocket,
        windowMs: Long,
        locations: MutableSet<String>
    ) {
        val deadline = System.currentTimeMillis() + windowMs
        val buffer = ByteArray(16 * 1024)
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, StandardCharsets.US_ASCII)
                val headers = parseHeaders(text)
                headers["location"]?.takeIf { it.isNotBlank() }?.let(locations::add)
            } catch (_: java.net.SocketTimeoutException) {
                // Keep listening until the complete MX window has elapsed.
            } catch (_: Exception) {
                // Ignore malformed/unsupported SSDP responses.
            }
        }
    }

    private fun unicastDiscovery(wifi: WifiNetworkInfo): List<String> {
        val hostValues = ipv4HostRange(wifi.address, wifi.prefixLength)
        if (hostValues.isEmpty()) return emptyList()

        val locations = LinkedHashSet<String>()
        val lock = DiscoveryContextHolder.context?.let { getMulticastLock(it) }
        try {
            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 250
                try { wifi.network.bindSocket(socket) } catch (_: Throwable) {}

                for (value in hostValues) {
                    try {
                        val address = InetAddress.getByName(intToIpv4(value))
                        sendSearch(
                            socket,
                            "ssdp:all",
                            address,
                            SSDP_PORT
                        )
                    } catch (_: Exception) {
                    }
                }

                collectResponses(socket, DISCOVERY_WAIT_MS, locations)
            }
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
        return locations.toList()
    }

    private fun ipv4HostRange(address: Inet4Address, prefixLength: Int): List<Long> {
        if (prefixLength < 20 || prefixLength > 30) return emptyList()
        val ip = ipv4ToInt(address)
        val mask = if (prefixLength == 0) 0L
        else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val first = network + 1L
        val last = broadcast - 1L
        if (last < first) return emptyList()
        if (last - first + 1L > UNICAST_SWEEP_LIMIT) return emptyList()
        val out = ArrayList<Long>((last - first + 1L).toInt())
        var current = first
        while (current <= last) {
            out.add(current)
            current++
        }
        return out
    }

    private fun ipv4ToInt(address: Inet4Address): Long {
        val b = address.address
        return ((b[0].toLong() and 0xFF) shl 24) or
            ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or
            (b[3].toLong() and 0xFF)
    }

    private fun intToIpv4(value: Long): String =
        listOf(
            (value ushr 24) and 0xFF,
            (value ushr 16) and 0xFF,
            (value ushr 8) and 0xFF,
            value and 0xFF
        ).joinToString(".")

    private fun parseRenderer(location: String): Renderer {
        val xmlText = httpGet(location)
        val friendly = tag(xmlText, "friendlyName").ifBlank { "DLNA Renderer" }
        val serviceBlockPattern = Pattern.compile(
            "<(?:[A-Za-z0-9_.-]+:)?service\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?service>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val matcher = serviceBlockPattern.matcher(xmlText)
        val avCandidates = mutableListOf<Pair<String, String>>()
        var cmControl: String? = null

        while (matcher.find()) {
            val service = matcher.group(1) ?: continue
            val type = tag(service, "serviceType")
            val control = tag(service, "controlURL")
            when {
                type.contains("AVTransport", ignoreCase = true) && control.isNotBlank() -> {
                    avCandidates += type to resolveUrl(location, control)
                }
                type.contains("ConnectionManager", ignoreCase = true) && control.isNotBlank() -> {
                    if (cmControl == null) cmControl = resolveUrl(location, control)
                }
            }
        }

        // Prefer AVTransport:1, then the lowest advertised version. This avoids
        // accidentally selecting a secondary vendor service that does not control
        // the actual media renderer transport.
        val selectedAv = avCandidates
            .sortedWith(compareBy<Pair<String, String>>(
                { versionOf(it.first).let { v -> if (v == 1) 0 else 1 } },
                { versionOf(it.first) },
                { it.first }
            ))
            .firstOrNull()

        var sink: String? = null
        if (cmControl != null) {
            try {
                val response = soap(
                    cmControl,
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
            avTransportControlUrl = selectedAv?.second ?: "",
            avTransportServiceType = selectedAv?.first
                ?: "urn:schemas-upnp-org:service:AVTransport:1",
            connectionManagerControlUrl = cmControl,
            sinkProtocolInfo = sink
        )
    }

    private fun versionOf(serviceType: String): Int {
        return serviceType.substringAfterLast(':', "1").toIntOrNull() ?: 1
    }

    private fun bestEffortStop(controlUrl: String, serviceType: String) {
        try {
            soap(
                controlUrl,
                serviceType,
                "Stop",
                "<InstanceID>0</InstanceID>"
            )
        } catch (_: Exception) {
        }
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

        val connection = openHttp(controlUrl)
        connection.connectTimeout = 3500
        connection.readTimeout = 8000
        connection.requestMethod = "POST"
        connection.doInput = true
        connection.doOutput = true
        connection.useCaches = false
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("Accept", "text/xml, */*")
        connection.setRequestProperty("User-Agent", USER_AGENT)

        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException(
                    "HTTP $code ${response.replace(Regex("\\s+"), " ").take(500)}"
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttp(url: String): HttpURLConnection {
        val context = DiscoveryContextHolder.context
        if (context != null) {
            val wifi = findWifiNetwork(context)
            if (wifi != null) {
                try {
                    return wifi.network.openConnection(URL(url)) as HttpURLConnection
                } catch (_: Throwable) {
                }
            }
        }
        return URL(url).openConnection() as HttpURLConnection
    }

    private fun httpGet(url: String): String {
        val connection = openHttp(url)
        connection.connectTimeout = 3500
        connection.readTimeout = 6000
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun didlMetadata(url: String): String {
        // The APK stream is H.264 + MPEG-1 Layer III inside a 188-byte MPEG-2 TS.
        // AVC_TS_MP_HD_MPEG1_L3 is the matching DLNA profile for that combination.
        val protocolInfo =
            "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_HD_MPEG1_L3;DLNA.ORG_OP=00;DLNA.ORG_CI=0"
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="1" parentID="-1" restricted="1">
                <dc:title>A01 Mirror Live</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="$protocolInfo">${xml(url)}</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun tag(text: String, name: String): String {
        val pattern = Pattern.compile(
            "<(?:[A-Za-z0-9_.-]+:)?$name\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?$name>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        return pattern.matcher(text).let {
            if (it.find()) htmlUnescape(it.group(1).trim()) else ""
        }
    }

    private fun parseHeaders(text: String): Map<String, String> =
        text.split("\r\n").drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null
            else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }.toMap()

    private fun resolveUrl(base: String, path: String): String = URI(base).resolve(path).toString()

    private fun htmlUnescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun getMulticastLock(context: Context): android.net.wifi.WifiManager.MulticastLock? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as? android.net.wifi.WifiManager
            wifi?.createMulticastLock("A01Mirror-SSDP")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }

    private object DiscoveryContextHolder {
        @Volatile var context: Context? = null
    }
}
