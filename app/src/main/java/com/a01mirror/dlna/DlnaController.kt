package com.a01mirror.dlna

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * SSDP + UPnP AVTransport control point untuk renderer DLNA murahan (STB T2 Advance A01 / STP-A01).
 *
 * Gabungan:
 *  - versi "A01-DLNA-FIXED": ikat soket ke jaringan Wi-Fi, sapuan unicast subnet, pilih AVTransport
 *    terendah, Stop defensif, DIDL dengan/tanpa metadata;
 *  - format kawat dari advance01-media-center-v6 (tar, Termux) yang terbukti jalan di STB:
 *    DIDL tanpa DLNA.ORG_PN, OP=01 + FLAGS, jeda SetAVTransportURI -> Play, cek GetTransportInfo,
 *    IP HP dihitung ke arah STB (UDP connect);
 *  - pencarian per-interface (Wi-Fi, hotspot HP, LAN), IP STB manual, tebak alamat deskripsi UPnP,
 *    dan log SOAP untuk laporan error.
 */
object DlnaController {
    /** Sama persis dengan DLNA_FEATURES di server.py (tar v6). */
    const val DLNA_FEATURES =
        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val USER_AGENT = "A01Mirror/1.1 UPnP/1.1"
    private const val HTTP_AGENT = "Advance01-MediaCenter/6.0 A01Mirror/1.1"
    private const val SEARCH_MX = 2
    private const val DISCOVERY_WAIT_MS = 2500L
    private const val UNICAST_SWEEP_LIMIT = 512L
    private const val AV_TYPE_DEFAULT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val CM_TYPE_DEFAULT = "urn:schemas-upnp-org:service:ConnectionManager:1"

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:device:MediaRenderer:2",
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

    private data class WifiNetworkInfo(
        val network: Network,
        val address: Inet4Address,
        val prefixLength: Int
    )

    /** Ringkasan proses pencarian terakhir (untuk ditampilkan saat STB tidak ketemu). */
    @Volatile
    var lastReport: String = ""
        private set

    /** Status transport STB setelah Play (mis. PLAYING / TRANSITIONING / STOPPED). */
    @Volatile
    var lastTransportState: String = ""
        private set

    private val soapTrace: MutableList<String> = Collections.synchronizedList(ArrayList<String>())

    /** Laporan pencarian + 30 SOAP terakhir; ditampilkan lewat tombol "Log DLNA". */
    fun debugLog(): String {
        val trace = synchronized(soapTrace) { soapTrace.toList() }
        return buildString {
            if (lastReport.isNotBlank()) append(lastReport).append("\n\n")
            append("--- SOAP terakhir ---\n")
            append(if (trace.isEmpty()) "(belum ada)" else trace.joinToString("\n"))
        }
    }

    private fun trace(action: String, ok: Boolean, detail: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp $action ${if (ok) "OK" else "GAGAL"} ${detail.take(300)}"
        synchronized(soapTrace) {
            soapTrace.add(line)
            while (soapTrace.size > 30) soapTrace.removeAt(0)
        }
    }

    /**
     * Cari renderer DLNA. [manualIp] opsional: IP STB yang tampil di layar STB.
     * Bila diisi, dicoba dulu SSDP unicast + tebak alamat deskripsi; multicast tetap jadi cadangan.
     * Urutan: IP manual -> multicast per-interface -> sapuan unicast subnet.
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
            // Sebagian firmware Wi-Fi/router menyaring multicast SSDP: sapu subnet lokal secara unicast.
            if (replies.isEmpty()) scanUnicastSweep(replies, log)

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
    fun setDiscoveryContext(context: Context) {
        DiscoveryContextHolder.context = context.applicationContext
    }

    /**
     * Set URI live pada DMR lalu Play.
     *
     * Urutan pertama meniru set_and_play() di tar v6 (SetAVTransportURI -> jeda 0.5 dtk -> Play ->
     * jeda 0.7 dtk -> GetTransportInfo). Bila ditolak dicoba ulang dengan Stop defensif, tipe
     * service alternatif, dan variasi metadata (tar, profil DLNA, tanpa metadata).
     */
    fun playLive(
        renderer: Renderer,
        streamUrl: String,
        streamResolution: String = ""
    ): Result<Unit> {
        if (renderer.avTransportControlUrl.isBlank()) {
            return Result.failure(IllegalStateException("AVTransport tidak tersedia"))
        }
        if (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("URL stream tidak valid: $streamUrl"))
        }

        val serviceTypes = linkedSetOf<String>().apply {
            add(renderer.avTransportServiceType.ifBlank { AV_TYPE_DEFAULT })
            add(AV_TYPE_DEFAULT)
        }

        val metadataAttempts = listOf(
            didlMetadata(streamUrl, streamResolution),
            didlMetadataProfile(streamUrl, streamResolution),
            ""
        )
        var lastError: Throwable? = null

        for (serviceType in serviceTypes) {
            for (metadata in metadataAttempts) {
                for (attempt in 0 until 2) {
                    try {
                        if (attempt > 0) {
                            // Sebagian DMR membalas 705/transport-locked bila URI diganti saat item lama aktif.
                            bestEffortStop(renderer.avTransportControlUrl, serviceType)
                            Thread.sleep(180)
                        }

                        soap(
                            renderer.avTransportControlUrl,
                            serviceType,
                            "SetAVTransportURI",
                            "<InstanceID>0</InstanceID>" +
                                "<CurrentURI>${xml(streamUrl)}</CurrentURI>" +
                                "<CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>"
                        )
                        Thread.sleep(220)
                        soap(
                            renderer.avTransportControlUrl,
                            serviceType,
                            "Play",
                            "<InstanceID>0</InstanceID><Speed>1</Speed>"
                        )
                        Thread.sleep(260)
                        lastTransportState = transportState(renderer, serviceType)
                        return Result.success(Unit)
                    } catch (t: Throwable) {
                        lastError = t
                        // STB tidak terjangkau: mengulang variasi lain hanya buang waktu.
                        if (t is ConnectException || t is SocketTimeoutException || t is NoRouteToHostException) {
                            return Result.failure(
                                IllegalStateException(
                                    "STB ${renderer.host} tidak terjangkau (${t.message ?: t.javaClass.simpleName}). " +
                                        "Cek Wi-Fi/hotspot dan IP STB."
                                )
                            )
                        }
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

    /**
     * Cast media/playlist item directly to the DMR without MediaProjection. This is intentionally
     * separate from live mirror: the STB fetches the public HTTP(S) URL itself.
     */
    fun playDirect(renderer: Renderer, mediaUrl: String, title: String = "Media"): Result<Unit> {
        val cleanMediaUrl = mediaUrl.substringBefore('|').trim()
        if (renderer.avTransportControlUrl.isBlank()) {
            return Result.failure(IllegalStateException("AVTransport tidak tersedia"))
        }
        if (!cleanMediaUrl.startsWith("http://") && !cleanMediaUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("URL media tidak valid"))
        }

        val serviceTypes = linkedSetOf<String>().apply {
            add(renderer.avTransportServiceType.ifBlank { AV_TYPE_DEFAULT })
            add(AV_TYPE_DEFAULT)
        }
        val metadataAttempts = listOf(directDidlMetadata(cleanMediaUrl, title), "")
        var lastError: Throwable? = null

        for (serviceType in serviceTypes) {
            for (metadata in metadataAttempts) {
                try {
                    bestEffortStop(renderer.avTransportControlUrl, serviceType)
                    Thread.sleep(100)
                    soap(
                        renderer.avTransportControlUrl,
                        serviceType,
                        "SetAVTransportURI",
                        "<InstanceID>0</InstanceID>" +
                            "<CurrentURI>${xml(cleanMediaUrl)}</CurrentURI>" +
                            "<CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>"
                    )
                    // A01 firmware needs a short hand-off here, but not the long buffering window used
                    // by older DLNA apps.
                    Thread.sleep(150)
                    soap(
                        renderer.avTransportControlUrl,
                        serviceType,
                        "Play",
                        "<InstanceID>0</InstanceID><Speed>1</Speed>"
                    )
                    lastTransportState = transportState(renderer, serviceType)
                    return Result.success(Unit)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
        }
        return Result.failure(
            IllegalStateException(
                "Gagal memainkan media di ${renderer.name}: ${lastError?.message ?: "renderer menolak URL"}"
            )
        )
    }

    fun stop(renderer: Renderer) {
        val serviceTypes = linkedSetOf<String>().apply {
            add(renderer.avTransportServiceType)
            add(AV_TYPE_DEFAULT)
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
                // Stop pada renderer yang idle boleh saja membalas SOAP fault.
            }
        }
    }

    /**
     * Uji koneksi seperti connect_renderer() di tar v6: GetTransportInfo (AVTransport) dan
     * GetProtocolInfo (ConnectionManager, daftar format yang diterima STB).
     */
    fun probe(renderer: Renderer): String {
        val sb = StringBuilder()
        sb.append("STB: ${renderer.name} (${renderer.host})\n")
        val avType = renderer.avTransportServiceType.ifBlank { AV_TYPE_DEFAULT }
        try {
            val raw = soap(renderer.avTransportControlUrl, avType, "GetTransportInfo", "<InstanceID>0</InstanceID>")
            val state = tag(raw, "CurrentTransportState")
            sb.append("AVTransport: OK").append(if (state.isBlank()) "" else " ($state)").append("\n")
        } catch (t: Exception) {
            sb.append("AVTransport: GAGAL ${t.message}\n")
        }
        val cm = renderer.connectionManagerControlUrl
        if (cm == null) {
            sb.append("ConnectionManager: tidak ada\n")
        } else {
            try {
                val raw = soap(cm, CM_TYPE_DEFAULT, "GetProtocolInfo", "")
                val sink = tag(raw, "Sink")
                val ts = sink.contains("video/mpeg", ignoreCase = true) ||
                    sink.contains("mp2t", ignoreCase = true) ||
                    sink.contains("mpeg-tts", ignoreCase = true) ||
                    sink.contains("AVC_TS", ignoreCase = true)
                sb.append("ConnectionManager: OK\n")
                sb.append("Format MPEG-TS: ").append(if (ts) "tercantum di daftar STB" else "tidak tercantum jelas").append("\n")
                if (sink.isNotBlank()) sb.append("Sink: ").append(sink.take(400)).append("\n")
            } catch (t: Exception) {
                sb.append("ConnectionManager: GAGAL ${t.message}\n")
            }
        }
        return sb.toString().trimEnd()
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
            log.add("Interface dipindai: " + interfaces.joinToString { "${it.name} ${it.address.hostAddress}/${it.prefix}" })
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
        log.add("Balasan SSDP multicast: ${replies.size} perangkat")
        for ((loc, from) in replies) log.add("  $from -> $loc")
    }

    private fun searchOn(li: LocalIf?, replies: MutableMap<String, String>, log: MutableList<String>) {
        val label = li?.name ?: "default"
        var socket: MulticastSocket? = null
        try {
            val s = MulticastSocket(null as SocketAddress?)
            socket = s
            s.reuseAddress = true
            // Penting di Android yang punya Wi-Fi + data seluler/VPN: ikat ke jaringan Wi-Fi STB.
            val net = li?.network
            if (net != null) {
                try { net.bindSocket(s) } catch (_: Throwable) {}
            }
            s.bind(InetSocketAddress(li?.address, 0))
            val ni = li?.ni
            if (ni != null) {
                try { s.setNetworkInterface(ni) } catch (_: Exception) {}
            }
            try { s.setTimeToLive(2) } catch (_: Exception) {}
            s.soTimeout = 300

            // UDP tidak andal: kirim dua putaran, tiap putaran diikuti jendela dengar.
            val group = InetAddress.getByName(SSDP_HOST)
            for (round in 0 until 2) {
                for (st in SEARCH_TARGETS) {
                    try { sendSearch(s, st, group, SSDP_PORT) } catch (_: Exception) {}
                }
                collect(s, if (round == 0) 1500L else DISCOVERY_WAIT_MS, replies)
            }
        } catch (t: Exception) {
            log.add("SSDP $label gagal: ${t.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun scanUnicastSweep(replies: MutableMap<String, String>, log: MutableList<String>) {
        for (li in localInterfaces()) {
            val hostValues = ipv4HostRange(li.address, li.prefix)
            if (hostValues.isEmpty()) {
                log.add("Sapuan unicast ${li.name} dilewati (subnet /${li.prefix} di luar batas)")
                continue
            }
            log.add("Sapuan unicast ${li.name}: ${hostValues.size} alamat")
            var socket: DatagramSocket? = null
            try {
                val s = DatagramSocket(null as SocketAddress?)
                socket = s
                s.reuseAddress = true
                val net = li.network
                if (net != null) {
                    try { net.bindSocket(s) } catch (_: Throwable) {}
                }
                s.bind(InetSocketAddress(li.address, 0))
                s.soTimeout = 250

                val self = ipv4ToInt(li.address)
                for (value in hostValues) {
                    if (value == self) continue
                    try {
                        sendSearch(s, "ssdp:all", InetAddress.getByName(intToIpv4(value)), SSDP_PORT)
                    } catch (_: Exception) {
                    }
                }
                collect(s, DISCOVERY_WAIT_MS, replies)
            } catch (t: Exception) {
                log.add("Sapuan unicast ${li.name} gagal: ${t.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        log.add("Balasan setelah sapuan unicast: ${replies.size} perangkat")
        for ((loc, from) in replies) log.add("  $from -> $loc")
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
                    try { sendSearch(s, st, target, SSDP_PORT) } catch (_: Exception) {}
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
            .distinctBy { "${it.host.lowercase()}|${it.avTransportControlUrl.lowercase()}" }
    }

    // ---------------------------------------------------------------------------------------
    // Interface / jaringan
    // ---------------------------------------------------------------------------------------

    private fun connectivity(): ConnectivityManager? =
        DiscoveryContextHolder.context
            ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

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
                    val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
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

    /** Jaringan Wi-Fi/Ethernet aktif beserta alamat IPv4-nya (dari versi A01-DLNA-FIXED). */
    @Suppress("DEPRECATION")
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

    /**
     * Network yang punya rute lokal ke [host]. Bila tidak ada rute yang cocok tapi [host] satu subnet
     * dengan Wi-Fi aktif, pakai Wi-Fi itu. Null (jalur default) untuk kasus hotspot HP.
     */
    @Suppress("DEPRECATION")
    private fun networkFor(host: String): Network? {
        val address = try { InetAddress.getByName(host) } catch (_: Exception) { return null }
        try {
            val cm = connectivity()
            if (cm != null) {
                val byRoute = cm.allNetworks.firstOrNull { n ->
                    val lp = cm.getLinkProperties(n)
                    lp != null && lp.routes.any { !it.isDefaultRoute && it.matches(address) }
                }
                if (byRoute != null) return byRoute
            }
        } catch (_: Exception) {
        }
        try {
            val context = DiscoveryContextHolder.context
            val wifi = context?.let { findWifiNetwork(it) }
            if (wifi != null && sameSubnet(wifi.address, address, wifi.prefixLength)) return wifi.network
        } catch (_: Exception) {
        }
        return null
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
                    avCandidates += type to fixHost(resolveUrl(base, control), fromIp)
                }
                type.contains("ConnectionManager", ignoreCase = true) && control.isNotBlank() -> {
                    if (cmControl == null) cmControl = fixHost(resolveUrl(base, control), fromIp)
                }
            }
        }

        // Utamakan AVTransport:1, lalu versi terendah yang diiklankan, supaya tidak salah pilih
        // service vendor lain yang bukan transport renderer sebenarnya.
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
                    CM_TYPE_DEFAULT,
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
            avTransportControlUrl = selectedAv?.second ?: "",
            avTransportServiceType = selectedAv?.first ?: AV_TYPE_DEFAULT,
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

    private fun transportState(renderer: Renderer, serviceType: String): String {
        return try {
            val raw = soap(
                renderer.avTransportControlUrl,
                serviceType,
                "GetTransportInfo",
                "<InstanceID>0</InstanceID>"
            )
            tag(raw, "CurrentTransportState").ifBlank { "?" }
        } catch (_: Exception) {
            "?"
        }
    }

    private fun soap(controlUrl: String, serviceType: String, action: String, args: String): String {
        // Satu baris tanpa spasi berlebih, persis seperti soap() di tar v6.
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$args</u:$action></s:Body></s:Envelope>"

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
        connection.setRequestProperty("User-Agent", HTTP_AGENT)

        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            val compact = response.replace(Regex("\\s+"), " ")
            if (code !in 200..299) {
                val message = "HTTP $code ${compact.take(500)}"
                trace(action, false, message)
                throw IllegalStateException(message)
            }
            trace(action, true, compact)
            return response
        } catch (t: Exception) {
            if (t !is IllegalStateException) trace(action, false, t.toString())
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttp(url: String): HttpURLConnection {
        val u = URL(url)
        val net = networkFor(u.host)
        if (net != null) {
            try {
                return net.openConnection(u) as HttpURLConnection
            } catch (_: Throwable) {
            }
        }
        return u.openConnection() as HttpURLConnection
    }

    private fun httpGet(url: String, timeoutMs: Int = 3500): String {
        val connection = openHttp(url)
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs + 2500
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("User-Agent", HTTP_AGENT)
        return try {
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun directDidlMetadata(url: String, title: String): String {
        val cleanUrl = url.substringBefore('|')
        val lowerUrl = cleanUrl.lowercase(Locale.US)
        val mime = when {
            lowerUrl.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            lowerUrl.contains(".mpd") -> "application/dash+xml"
            lowerUrl.contains(".mp4") -> "video/mp4"
            lowerUrl.contains(".m4v") -> "video/mp4"
            lowerUrl.contains(".mkv") -> "video/x-matroska"
            lowerUrl.contains(".webm") -> "video/webm"
            lowerUrl.contains(".mov") -> "video/quicktime"
            lowerUrl.contains(".avi") -> "video/x-msvideo"
            lowerUrl.contains(".ts") || lowerUrl.contains(".m2ts") || lowerUrl.contains(".mpegts") || lowerUrl.contains(".mpeg") || lowerUrl.contains(".mpg") -> "video/mpeg"
            lowerUrl.contains(".mp3") -> "audio/mpeg"
            lowerUrl.contains(".aac") -> "audio/aac"
            lowerUrl.contains(".ac3") -> "audio/ac3"
            lowerUrl.contains(".eac3") -> "audio/eac3"
            lowerUrl.contains(".m4a") -> "audio/mp4"
            lowerUrl.contains(".flac") -> "audio/flac"
            lowerUrl.contains(".wav") -> "audio/wav"
            lowerUrl.contains(".ogg") || lowerUrl.contains(".oga") || lowerUrl.contains(".opus") -> "audio/ogg"
            lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") -> "image/jpeg"
            lowerUrl.contains(".png") -> "image/png"
            else -> "video/mpeg"
        }
        val protocolInfo = "http-get:*:$mime:*"
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${xml(title.ifBlank { "Media" })}</dc:title>" +
            "<upnp:class>${if (mime.startsWith("audio/")) "object.item.audioItem.musicTrack" else "object.item.videoItem"}</upnp:class>" +
            "<res protocolInfo=\"${xml(protocolInfo)}\">${xml(cleanUrl)}</res>" +
            "</item></DIDL-Lite>"
    }

    /** Metadata satu baris, persis seperti metadata_for() di tar v6 (tanpa DLNA.ORG_PN). */
    private fun didlMetadata(url: String, resolution: String = ""): String {
        val protocolInfo = "http-get:*:video/mpeg:$DLNA_FEATURES"
        val resolutionAttr = if (resolution.isNotBlank()) " resolution=\"${xml(resolution)}\"" else ""
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>A01 Mirror Live</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"${xml(protocolInfo)}\"$resolutionAttr>${xml(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    /**
     * Variasi cadangan (dari versi A01-DLNA-FIXED): stream H.264 + MPEG-1 Layer III dalam TS 188-byte
     * memakai profil DLNA AVC_TS_MP_HD_MPEG1_L3. Dipakai bila metadata gaya tar ditolak firmware.
     */
    private fun didlMetadataProfile(url: String, resolution: String = ""): String {
        val protocolInfo =
            "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_HD_MPEG1_L3;DLNA.ORG_OP=00;DLNA.ORG_CI=0"
        val resolutionAttr = if (resolution.isNotBlank()) " resolution=\"${xml(resolution)}\"" else ""
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>A01 Mirror Live</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"${xml(protocolInfo)}\"$resolutionAttr>${xml(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    private fun tag(text: String, name: String): String {
        val pattern = Pattern.compile(
            "<(?:[A-Za-z0-9_.-]+:)?$name\\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?$name>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        return pattern.matcher(text).let {
            if (it.find()) htmlUnescape(it.group(1)?.trim().orEmpty()) else ""
        }
    }

    private fun parseHeaders(text: String): Map<String, String> =
        text.split("\n").drop(1).mapNotNull { raw ->
            val line = raw.trim()
            val index = line.indexOf(':')
            if (index <= 0) null
            else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }.toMap()

    /**
     * Resolve URL/path dari description UPnP. Firmware A01/Xiaomi lama kadang mengirim
     * controlURL sebagai path vendor `_urn:...` (contoh:
     * `_urn:schemas-upnp-org:service:AVTransport_control`). Java URI menganggap bagian
     * sebelum `:` sebagai scheme sehingga `URI.resolve()` melempar
     * "Illegal character in scheme name" karena scheme `_urn` tidak valid.
     *
     * Path vendor tersebut tetap merupakan endpoint HTTP pada host/port yang sama, jadi
     * paksa menjadi absolute-path `/` sebelum di-resolve. Fallback serupa menjaga kompatibilitas
     * bila firmware lain memakai path relatif non-standar yang mengandung `:`.
     */
    private fun resolveUrl(base: String, path: String): String {
        val raw = path.trim()
        if (raw.isBlank()) return base

        val baseUri = URI(base)
        val normalized = when {
            raw.startsWith("_urn:", ignoreCase = true) -> "/" + raw.removePrefix("/")
            else -> raw
        }

        return try {
            baseUri.resolve(normalized).toString()
        } catch (e: IllegalArgumentException) {
            // Be tolerant of vendor control/event URLs such as `urn:...` or other colon-based
            // relative paths. An actual absolute http(s) URL is handled normally by URI.resolve().
            if (!normalized.startsWith("/") && !normalized.contains("://") && normalized.contains(':')) {
                baseUri.resolve("/${normalized.removePrefix("/")}").toString()
            } else {
                throw e
            }
        }
    }

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
