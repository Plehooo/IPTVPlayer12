package com.a01mirror.dlna

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * SSDP + UPnP AVTransport client untuk renderer DLNA murahan (STB T2 Advance A01 / STP-A01).
 *
 * Format kawat (DIDL, header, urutan SOAP) disamakan dengan advance01-media-center-v6 (Termux)
 * yang sudah terbukti jalan di STB. Pencarian SSDP dikirim per-interface (Wi-Fi, hotspot, LAN)
 * supaya tetap ketemu walau data seluler jadi jaringan default. Bisa juga lewat IP manual.
 */
object DlnaController {
    /** Sama persis dengan DLNA_FEATURES di server.py (tar v6). */
    const val DLNA_FEATURES =
        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "upnp:rootdevice",
        "ssdp:all"
    )

    private val SKIP_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "tun", "dummy", "v4-", "clat", "ppp", "ipsec")

    // Port/path umum untuk deskripsi UPnP di STB murahan (dipakai bila SSDP tidak dibalas).
    private val PROBE_PORTS = intArrayOf(
        49152, 49153, 49154, 49155, 52235, 52323, 8200, 8080, 5000, 7676, 8000, 9000, 2869, 60006, 8060, 80
    )
    private val PROBE_PATHS = listOf(
        "/description.xml", "/dmr.xml", "/rootDesc.xml", "/DeviceDescription.xml", "/desc.xml",
        "/upnp/desc.xml", "/dev/desc.xml", "/MediaRenderer/desc.xml", "/device.xml", "/ssdp/device-desc.xml"
    )

    private val IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$")

    data class Renderer(
        val name: String,
        val host: String,
        val location: String,
        val avTransportControlUrl: String,
        val avTransportServiceType: String,
        val connectionManagerControlUrl: String? = null,
        val sinkProtocolInfo: String? = null
    )

    private class LocalIf(
        val name: String,
        val ni: NetworkInterface?,
        val address: Inet4Address,
        val prefix: Int,
        val network: Network?
    )

    /** Ringkasan proses pencarian terakhir (untuk ditampilkan saat STB tidak ketemu). */
    @Volatile
    var lastReport: String = ""
        private set

    /**
     * Cari renderer DLNA. [manualIp] opsional: IP STB yang tampil di layar STB.
     * Bila diisi, dicoba dulu SSDP unicast + tebak alamat deskripsi; multicast tetap jadi cadangan.
     */
    fun discover(manualIp: String? = null): List<Renderer> {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val replies = ConcurrentHashMap<String, String>() // location -> IP pengirim balasan
        val context = DiscoveryContextHolder.context
        val lock = context?.let { getMulticastLock(it) }
        try {
            val ip = manualIp?.trim().orEmpty()
            if (ip.isNotEmpty()) {
                if (IPV4.matcher(ip).matches()) scanByIp(ip, replies, log)
                else log.add("IP manual tidak valid: $ip")
            }
            if (replies.isEmpty()) scanMulticast(replies, log)

            val found = resolveRenderers(replies, log)
            log.add(
                if (found.isEmpty()) "Tidak ada perangkat DMR/AVTransport yang ditemukan."
                else "DMR: " + found.joinToString { "${it.name} (${it.host})" }
            )
            lastReport = log.joinToString("\n")
            return found
        } catch (t: Throwable) {
            log.add("Pencarian gagal: ${t.message}")
            lastReport = log.joinToString("\n")
            return emptyList()
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    /** Supply an application context before calling discover(). */
    fun setDiscoveryContext(context: android.content.Context) {
        DiscoveryContextHolder.context = context.applicationContext
    }

    fun playLive(renderer: Renderer, streamUrl: String): Result<Unit> {
        if (renderer.avTransportControlUrl.isBlank()) {
            return Result.failure(IllegalStateException("AVTransport tidak tersedia"))
        }

        val attempts = listOf(
            didlMetadata(streamUrl),
            "",
        )
        var lastError: Throwable? = null

        for (metadata in attempts) {
            try {
                soap(
                    renderer.avTransportControlUrl,
                    renderer.avTransportServiceType,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>${xml(streamUrl)}</CurrentURI>" +
                        "<CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>"
                )
                // Sama dengan set_and_play() di tar v6: jeda sebelum Play.
                Thread.sleep(500)
                soap(
                    renderer.avTransportControlUrl,
                    renderer.avTransportServiceType,
                    "Play",
                    "<InstanceID>0</InstanceID><Speed>1</Speed>"
                )
                Thread.sleep(700)
                try {
                    soap(
                        renderer.avTransportControlUrl,
                        renderer.avTransportServiceType,
                        "GetTransportInfo",
                        "<InstanceID>0</InstanceID>"
                    )
                } catch (_: Exception) {
                }
                return Result.success(Unit)
            } catch (t: Throwable) {
                lastError = t
            }
        }

        return Result.failure(
            IllegalStateException("DLNA gagal: ${lastError?.message ?: "renderer menolak stream"}")
        )
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

    /**
     * IP HP yang dilihat STB: soket UDP "connect" ke STB lalu baca alamat lokalnya
     * (sama dengan local_ip_for_renderer() di tar v6). Cocok untuk Wi-Fi maupun hotspot HP.
     */
    fun localAddressToward(host: String): String? {
        try {
            DatagramSocket(null as SocketAddress?).use { s ->
                val net = networkFor(host)
                if (net != null) {
                    try { net.bindSocket(s) } catch (_: Exception) {}
                }
                s.connect(InetAddress.getByName(host), SSDP_PORT)
                val a = s.localAddress
                val text = a?.hostAddress
                if (a != null && !a.isAnyLocalAddress && !a.isLoopbackAddress && !text.isNullOrBlank()) return text
            }
        } catch (_: Exception) {
        }
        // Cadangan: interface lokal yang satu subnet dengan STB.
        try {
            val target = InetAddress.getByName(host)
            for (li in localInterfaces()) {
                if (sameSubnet(li.address, target, li.prefix)) return li.address.hostAddress
            }
        } catch (_: Exception) {
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Pencarian
    // ---------------------------------------------------------------------------------------

    private fun scanMulticast(replies: MutableMap<String, String>, log: MutableList<String>) {
        val interfaces = localInterfaces()
        if (interfaces.isEmpty()) {
            log.add("Interface Wi-Fi/hotspot/LAN aktif tidak terdeteksi (pakai jalur default).")
        } else {
            log.add("Interface dipindai: " + interfaces.joinToString { "${it.name} ${it.address.hostAddress}" })
        }

        val threads = ArrayList<Thread>()
        for (li in interfaces) {
            threads.add(Thread { searchOn(li, replies, log) }.also { it.name = "A01-SSDP-${li.name}"; it.start() })
        }
        // Jalur default tetap dicoba sebagai cadangan.
        threads.add(Thread { searchOn(null, replies, log) }.also { it.name = "A01-SSDP-default"; it.start() })
        for (t in threads) {
            try { t.join(8000) } catch (_: InterruptedException) {}
        }
        log.add("Balasan SSDP: ${replies.size} perangkat")
        for ((loc, from) in replies) log.add("  $from -> $loc")
    }

    private fun searchOn(li: LocalIf?, replies: MutableMap<String, String>, log: MutableList<String>) {
        val label = li?.name ?: "default"
        var socket: MulticastSocket? = null
        try {
            val s = MulticastSocket(null as SocketAddress?)
            socket = s
            s.reuseAddress = true
            val net = li?.network
            if (net != null) {
                try { net.bindSocket(s) } catch (_: Exception) {}
            }
            s.bind(InetSocketAddress(li?.address, 0))
            val ni = li?.ni
            if (ni != null) {
                try { s.setNetworkInterface(ni) } catch (_: Exception) {}
            }
            try { s.setTimeToLive(2) } catch (_: Exception) {}
            s.soTimeout = 300

            val group = InetAddress.getByName(SSDP_ADDRESS)
            for (round in 0 until 2) {
                for (st in SEARCH_TARGETS) {
                    val data = msearch(st)
                    try { s.send(DatagramPacket(data, data.size, group, SSDP_PORT)) } catch (_: Exception) {}
                }
                collect(s, if (round == 0) 1500L else 2500L, replies)
            }
        } catch (t: Exception) {
            log.add("SSDP $label gagal: ${t.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun scanByIp(ip: String, replies: MutableMap<String, String>, log: MutableList<String>) {
        log.add("IP manual: $ip")
        var socket: DatagramSocket? = null
        try {
            val s = DatagramSocket(null as SocketAddress?)
            socket = s
            val net = networkFor(ip)
            if (net != null) {
                try { net.bindSocket(s) } catch (_: Exception) {}
            }
            s.bind(InetSocketAddress(0))
            s.soTimeout = 300
            val target = InetAddress.getByName(ip)
            for (round in 0 until 2) {
                for (st in SEARCH_TARGETS) {
                    val data = msearch(st)
                    try { s.send(DatagramPacket(data, data.size, target, SSDP_PORT)) } catch (_: Exception) {}
                }
                collect(s, 1500L, replies)
            }
        } catch (t: Exception) {
            log.add("SSDP unicast ke $ip gagal: ${t.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }

        if (replies.isEmpty()) {
            log.add("STB $ip tidak membalas SSDP unicast, mencoba tebak alamat deskripsi…")
            probeDescription(ip, replies, log)
        }
    }

    private fun probeDescription(ip: String, replies: MutableMap<String, String>, log: MutableList<String>) {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val open: MutableList<Int> = Collections.synchronizedList(ArrayList<Int>())
            val portTasks = PROBE_PORTS.map { port ->
                Callable<Unit> {
                    if (tcpOpen(ip, port, 600)) open.add(port)
                    Unit
                }
            }
            pool.invokeAll(portTasks, 6, TimeUnit.SECONDS)
            val openSnapshot = synchronized(open) { open.sorted() }
            log.add("Port terbuka di $ip: " + (if (openSnapshot.isEmpty()) "-" else openSnapshot.joinToString()))

            val pathTasks = openSnapshot.flatMap { port ->
                PROBE_PATHS.map { path ->
                    Callable<Unit> {
                        val url = "http://$ip:$port$path"
                        try {
                            val body = httpGet(url, 1500)
                            if (body.contains("urn:schemas-upnp-org:device", ignoreCase = true)) {
                                replies.putIfAbsent(url, ip)
                            }
                        } catch (_: Exception) {
                        }
                        Unit
                    }
                }
            }
            if (pathTasks.isNotEmpty()) pool.invokeAll(pathTasks, 12, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun collect(socket: DatagramSocket, durationMs: Long, replies: MutableMap<String, String>) {
        val deadline = System.currentTimeMillis() + durationMs
        val buffer = ByteArray(16 * 1024)
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                val location = parseHeaders(text)["location"]?.trim().orEmpty()
                val from = packet.address?.hostAddress
                if (location.isNotEmpty() && !from.isNullOrBlank()) {
                    replies.putIfAbsent(fixHost(location, from), from)
                }
            } catch (_: SocketTimeoutException) {
                // Lanjut sampai jendela pencarian habis.
            } catch (_: Exception) {
                // Abaikan balasan rusak dan terus memindai.
            }
        }
    }

    private fun resolveRenderers(replies: Map<String, String>, log: MutableList<String>): List<Renderer> {
        if (replies.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(6)
        val parsed = ArrayList<Renderer>()
        try {
            val tasks = replies.entries.map { entry ->
                val location = entry.key
                val from = entry.value
                Callable<Renderer?> {
                    try {
                        parseRenderer(location, from)
                    } catch (t: Exception) {
                        log.add("Gagal baca $location: ${t.message}")
                        null
                    }
                }
            }
            for (future in pool.invokeAll(tasks, 12, TimeUnit.SECONDS)) {
                try {
                    if (!future.isCancelled) future.get()?.let { parsed.add(it) }
                } catch (_: Exception) {
                }
            }
        } finally {
            pool.shutdownNow()
        }

        for (r in parsed) {
            if (r.avTransportControlUrl.isBlank()) log.add("Bukan DMR (tanpa AVTransport): ${r.name} (${r.host})")
        }
        return parsed
            .filter { it.avTransportControlUrl.isNotBlank() }
            .distinctBy { it.avTransportControlUrl.lowercase() }
    }

    private fun msearch(st: String): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: 239.255.255.250:1900\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: ").append(st).append("\r\n")
        append("USER-AGENT: Android/UPnP/1.1 A01Mirror/1.0\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.US_ASCII)

    // ---------------------------------------------------------------------------------------
    // Interface / jaringan
    // ---------------------------------------------------------------------------------------

    private fun connectivity(): android.net.ConnectivityManager? =
        DiscoveryContextHolder.context
            ?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager

    @Suppress("DEPRECATION")
    private fun allNetworks(): List<Network> = try {
        connectivity()?.allNetworks?.toList() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun localInterfaces(): List<LocalIf> {
        val found = LinkedHashMap<String, LocalIf>()
        val cm = connectivity()
        val networks = allNetworks()

        try {
            val all = NetworkInterface.getNetworkInterfaces()
            if (all != null) {
                for (ni in Collections.list(all)) {
                    try {
                        if (!ni.isUp || ni.isLoopback) continue
                        val lower = ni.name.lowercase()
                        if (SKIP_INTERFACE_PREFIXES.any { lower.startsWith(it) }) continue
                        val net = networks.firstOrNull { cm?.getLinkProperties(it)?.interfaceName == ni.name }
                        for (ia in ni.interfaceAddresses) {
                            val a = ia.address
                            if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                                val key = a.hostAddress ?: continue
                                found.putIfAbsent(key, LocalIf(ni.name, ni, a, ia.networkPrefixLength.toInt(), net))
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Cadangan bila enumerasi NetworkInterface dibatasi sistem: ambil dari ConnectivityManager.
        if (cm != null) {
            for (n in networks) {
                try {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    val isLan = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (!isLan) continue
                    val lp = cm.getLinkProperties(n) ?: continue
                    val ni = try { lp.interfaceName?.let { NetworkInterface.getByName(it) } } catch (_: Exception) { null }
                    for (la in lp.linkAddresses) {
                        val a = la.address
                        if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                            val key = a.hostAddress ?: continue
                            found.putIfAbsent(key, LocalIf(lp.interfaceName ?: "wifi", ni, a, la.prefixLength, n))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return found.values.toList()
    }

    /** Network (Wi-Fi/LAN) yang punya rute lokal ke [host]; null bila tidak ada (mis. hotspot HP). */
    @Suppress("DEPRECATION")
    private fun networkFor(host: String): Network? {
        return try {
            val cm = connectivity() ?: return null
            val address = InetAddress.getByName(host)
            cm.allNetworks.firstOrNull { n ->
                val lp = cm.getLinkProperties(n)
                lp != null && lp.routes.any { !it.isDefaultRoute && it.matches(address) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sameSubnet(local: Inet4Address, remote: InetAddress, prefix: Int): Boolean {
        if (remote !is Inet4Address || prefix !in 1..32) return false
        val a = local.address
        val b = remote.address
        var bits = prefix
        var i = 0
        while (bits > 0 && i < 4) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    private fun tcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                val net = networkFor(ip)
                if (net != null) {
                    try { net.bindSocket(s) } catch (_: Exception) {}
                }
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------------------
    // UPnP
    // ---------------------------------------------------------------------------------------

    /** Perbaiki host LOCATION yang kosong/0.0.0.0/127.x dengan IP pengirim balasan SSDP. */
    private fun fixHost(url: String, fromIp: String): String {
        return try {
            val uri = URI(url)
            val h = uri.host
            if (h.isNullOrBlank() || h == "0.0.0.0" || h == "localhost" || h.startsWith("127.")) {
                URI(uri.scheme ?: "http", uri.userInfo, fromIp, uri.port, uri.path, uri.query, uri.fragment).toString()
            } else {
                url
            }
        } catch (_: Exception) {
            Regex("^(https?://)[^/:]*").replace(url) { m -> m.groupValues[1] + fromIp }
        }
    }

    private fun parseRenderer(location: String, fromIp: String): Renderer {
        val xmlText = httpGet(location)
        val friendly = tag(xmlText, "friendlyName").ifBlank { "DLNA Renderer" }
        val urlBase = tag(xmlText, "URLBase")
        val base = if (urlBase.isNotBlank() && fixHost(urlBase, fromIp) == urlBase) urlBase else location
        val serviceBlockPattern = Pattern.compile(
            "<service\\b[^>]*>(.*?)</service>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val matcher = serviceBlockPattern.matcher(xmlText)
        var avControl = ""
        var avType = "urn:schemas-upnp-org:service:AVTransport:1"
        var cmControl: String? = null

        while (matcher.find()) {
            val service = matcher.group(1) ?: continue
            val type = tag(service, "serviceType")
            val control = tag(service, "controlURL")
            when {
                type.contains("AVTransport", ignoreCase = true) && control.isNotBlank() -> {
                    avControl = fixHost(resolveUrl(base, control), fromIp)
                    avType = type
                }
                type.contains("ConnectionManager", ignoreCase = true) && control.isNotBlank() -> {
                    cmControl = fixHost(resolveUrl(base, control), fromIp)
                }
            }
        }

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
            host = fromIp,
            location = location,
            avTransportControlUrl = avControl,
            avTransportServiceType = avType,
            connectionManagerControlUrl = cmControl,
            sinkProtocolInfo = sink
        )
    }

    private fun openConnection(url: String): HttpURLConnection {
        val u = URL(url)
        val net = networkFor(u.host)
        return (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }

    private fun soap(controlUrl: String, serviceType: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$args</u:$action></s:Body></s:Envelope>"

        val connection = openConnection(controlUrl)
        connection.connectTimeout = 3000
        connection.readTimeout = 8000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.useCaches = false
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("User-Agent", "Advance01-MediaCenter/6.0 A01Mirror")
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val code = connection.responseCode
        val input = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${response.take(300)}")
        return response
    }

    private fun httpGet(url: String, timeoutMs: Int = 3000): String {
        val connection = openConnection(url)
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs + 2000
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Advance01-MediaCenter/6.0 A01Mirror")
        return try {
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Metadata satu baris, persis seperti metadata_for() di tar v6 (tanpa DLNA.ORG_PN). */
    private fun didlMetadata(url: String): String {
        val protocolInfo = "http-get:*:video/mpeg:$DLNA_FEATURES"
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>A01 Mirror Live</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"${xml(protocolInfo)}\">${xml(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    private fun tag(text: String, name: String): String {
        val pattern = Pattern.compile(
            "<$name[^>]*>(.*?)</$name>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        return pattern.matcher(text).let {
            if (it.find()) htmlUnescape(it.group(1).trim()) else ""
        }
    }

    private fun parseHeaders(text: String): Map<String, String> =
        text.split("\n").drop(1).mapNotNull { raw ->
            val line = raw.trim()
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

    private fun getMulticastLock(context: android.content.Context): android.net.wifi.WifiManager.MulticastLock? {
        return try {
            val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
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
        @Volatile var context: android.content.Context? = null
    }
}
