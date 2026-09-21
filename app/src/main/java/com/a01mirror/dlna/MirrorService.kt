package com.a01mirror.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.util.Locale

class MirrorService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUTO_SIZE = "auto_size"
        const val EXTRA_AUTO_FPS = "auto_fps"
        const val EXTRA_RECORD = "record"
        const val EXTRA_RENDERER_LOCATION = "renderer_location"
        const val EXTRA_RENDERER_CONTROL_URL = "renderer_control_url"
        const val EXTRA_RENDERER_SERVICE_TYPE = "renderer_service_type"
        const val EXTRA_RENDERER_NAME = "renderer_name"

        private const val NOTIFICATION_ID = 4101
        private const val ERROR_NOTIFICATION_ID = 4102
        private const val CHANNEL_ID = "a01_mirror"
        private const val ERROR_CHANNEL_ID = "a01_mirror_error"
        private const val ACTION_STOP = "com.a01mirror.dlna.STOP"
        const val ACTION_SCREEN_OFF_PRIVILEGED = "com.a01mirror.dlna.SCREEN_OFF_PRIVILEGED"
        const val ACTION_SCREEN_ON_PRIVILEGED = "com.a01mirror.dlna.SCREEN_ON_PRIVILEGED"

        // Status yang dibaca MainActivity (service kini satu proses dengan UI, jadi bisa dibagi langsung).
        @Volatile var running: Boolean = false
        @Volatile var lastError: String = ""
        @Volatile var statusLine: String = ""
        @Volatile var activeBroadcaster: TsBroadcaster? = null
        @Volatile var screenOffActive: Boolean = false
    }

    private var projection: MediaProjection? = null
    private var encoder: H264Encoder? = null
    private var audio: AudioCapture? = null
    private var broadcaster: TsBroadcaster? = null
    private var http: LiveHttpServer? = null
    private var renderer: DlnaController.Renderer? = null
    private var mirrorThread: Thread? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wifiHighPerfLock: android.net.wifi.WifiManager.WifiLock? = null
    private var cpuWakeLock: android.os.PowerManager.WakeLock? = null
    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null
    private var screenPowerController: ScreenPowerController? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var notifyBase = "A01 Mirror"

    /** Perbarui notifikasi tiap 2 dtk dengan status live + rekaman (bukan teks "Rekam berjalan" tetap). */
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            val b = broadcaster
            if (b != null) updateNotification(liveSummary(b))
            mainHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Penting: SOAP Play/Stop dan penghitungan IP HP di service harus diikat ke jaringan Wi-Fi
        // (sebelumnya konteks ini hanya diset di MainActivity sehingga service memakai jaringan default).
        DlnaController.setDiscoveryContext(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SCREEN_OFF_PRIVILEGED -> {
                if (!running) {
                    postError("Mulai mirror dulu sebelum mengaktifkan layar OFF.")
                    return START_NOT_STICKY
                }
                if (screenPowerController == null) {
                    screenPowerController = ScreenPowerController(this) { message ->
                        statusLine = message
                        if (message.contains("aktif", ignoreCase = true)) {
                            screenOffActive = true
                        } else if (message.contains("dimatikan", ignoreCase = true)) {
                            screenOffActive = false
                        }
                    }
                }
                screenOffActive = screenPowerController?.start() == true
                return START_NOT_STICKY
            }
            ACTION_SCREEN_ON_PRIVILEGED -> {
                screenPowerController?.stop(restoreScreen = true)
                screenOffActive = false
                return START_NOT_STICKY
            }
        }

        if (projection != null) return START_NOT_STICKY

        try {
            lastError = ""
            statusLine = "Menyiapkan mirror…"
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = intent?.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                ?: throw IllegalArgumentException("Data MediaProjection hilang")
            // Mode "Otomatis": awal dari kelas HP; lalu SEMUA pilihan disesuaikan ke kemampuan encoder
            // HP ini (fps turun dulu, baru resolusi) agar berjalan di HP apa pun tanpa buffering.
            val auto = H264Encoder.autoRequest(this)
            val requestedWidth = if (intent.getBooleanExtra(EXTRA_AUTO_SIZE, false)) auto[0] else intent.getIntExtra(EXTRA_WIDTH, 1280)
            val requestedHeight = if (intent.getBooleanExtra(EXTRA_AUTO_SIZE, false)) auto[1] else intent.getIntExtra(EXTRA_HEIGHT, 720)
            val requestedFps = if (intent.getBooleanExtra(EXTRA_AUTO_FPS, false)) auto[2] else intent.getIntExtra(EXTRA_FPS, 30)
            val fit = H264Encoder.fitToDevice(requestedWidth, requestedHeight, requestedFps)
            val width = fit.width
            val height = fit.height
            val fps = fit.fps
            val fitNote = fit.note
            val name = intent.getStringExtra(EXTRA_RENDERER_NAME) ?: "DLNA Renderer"
            val location = intent.getStringExtra(EXTRA_RENDERER_LOCATION) ?: ""
            val control = intent.getStringExtra(EXTRA_RENDERER_CONTROL_URL) ?: ""
            val serviceType = intent.getStringExtra(EXTRA_RENDERER_SERVICE_TYPE)
                ?: "urn:schemas-upnp-org:service:AVTransport:1"

            val target = DlnaController.Renderer(name, hostFrom(location), location, control, serviceType)
            renderer = target
            acquirePerformanceLocks()

            // Android 14+: permission/type requirements for a mediaProjection FGS are mandatory.
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Menyiapkan ${name}"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            val pm = getSystemService(MediaProjectionManager::class.java)
            projection = pm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection tidak tersedia")

            val recordEnabled = intent.getBooleanExtra(EXTRA_RECORD, true)
            val stream = TsBroadcaster(contentResolver, enableRecording = recordEnabled)
            broadcaster = stream
            activeBroadcaster = stream
            http = LiveHttpServer(stream, stream.sessionToken).also { it.start() }

            // IP HP dihitung ke arah STB (bukan asal ambil wlan0) supaya URL stream terbaca STB.
            val ip = localIpv4For(this, target.host)
            val streamUrl = "http://$ip:${http!!.port}/a01/${stream.sessionToken}/stream.ts"
            notifyBase = "Live ${width}×${height}@${fps}fps" + if (recordEnabled) " • Rekam" else " • Mirror saja"
            updateNotification(liveSummary(stream))

            encoder = H264Encoder(
                projection = projection!!,
                width = width,
                height = height,
                fps = fps,
                densityDpi = resources.configuration.densityDpi,
                broadcaster = stream,
                maxBitrate = wifiBitrateCap(),
                onFailure = {
                    postError("Video: ${it.message ?: "gagal"}")
                    stopSelf()
                },
                onQuality = { statusLine = it }
            ).also { it.start() }

            // Suhu HP: saat panas, bitrate/fps diturunkan otomatis dan naik lagi setelah dingin.
            try {
                val power = getSystemService(android.os.PowerManager::class.java)
                val listener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
                    encoder?.setThermalStatus(status)
                }
                power.addThermalStatusListener(mainExecutor, listener)
                thermalListener = listener
                encoder?.setThermalStatus(power.currentThermalStatus)
            } catch (_: Throwable) {
                thermalListener = null
            }

            audio = AudioCapture(
                projection = projection!!,
                broadcaster = stream,
                onFailure = {
                    postError("Audio internal tidak tersedia (${it.message ?: "tidak didukung"}). Video tetap tayang, audio senyap.")
                }
            ).also { it.start() }

            running = true
            mainHandler.postDelayed(ticker, 2000L)

            mirrorThread = Thread {
                try {
                    // Jangan menunggu berlebihan: server sudah mengirim PAT/PMT saat STB connect,
                    // lalu cukup pastikan frame video pertama sudah tersedia sebelum Play.
                    val deadline = System.currentTimeMillis() + 1200L
                    while (System.currentTimeMillis() < deadline && stream.videoFrames < 1L) {
                        Thread.sleep(30)
                    }
                    // Buffer awal pendek: cukup untuk melewatkan PAT/PMT + IDR pertama tanpa menambah
                    // detik latency seperti versi sebelumnya.
                    Thread.sleep(20)
                    val r = renderer
                    if (r != null && streamUrl.startsWith("http://") && !streamUrl.startsWith("http://127.")) {
                        val result = DlnaController.playLive(r, streamUrl, "${width}x${height}")
                        if (result.isSuccess) {
                            val state = DlnaController.lastTransportState
                            statusLine = "Tayang ke ${r.name}" + (if (state.isNotBlank()) " ($state)" else "") +
                                " • ${width}×${height}@${fps}fps" + (if (fitNote.isNotEmpty()) "\n$fitNote" else "")
                        } else {
                            postError(result.exceptionOrNull()?.message ?: "DLNA gagal")
                        }
                    } else if (r != null) {
                        postError("IP HP tidak terbaca ($streamUrl). Hubungkan HP ke Wi-Fi/hotspot yang sama dengan STB.")
                    }
                } catch (_: InterruptedException) {
                    // Service dihentikan saat menunggu.
                }
            }.also { it.name = "A01-DLNA"; it.start() }

            return START_NOT_STICKY
        } catch (t: Throwable) {
            postError(t.message ?: "Gagal memulai mirror")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Under memory pressure, sacrifice only the optional recording backlog.
        // The live DLNA path remains active.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try { broadcaster?.trimRecordingPressure() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        activeBroadcaster = null
        try { screenPowerController?.stop(restoreScreen = true) } catch (_: Throwable) {}
        screenPowerController = null
        screenOffActive = false
        mainHandler.removeCallbacks(ticker)
        val r = renderer
        val stopThread = if (r != null) {
            Thread { try { DlnaController.stop(r) } catch (_: Exception) {} }
                .also { it.name = "A01-DLNA-stop"; it.start() }
        } else null
        try { mirrorThread?.interrupt() } catch (_: Exception) {}
        try {
            thermalListener?.let { getSystemService(android.os.PowerManager::class.java).removeThermalStatusListener(it) }
        } catch (_: Throwable) {
        }
        thermalListener = null
        try { audio?.stop() } catch (_: Exception) {}
        audio = null
        try { encoder?.stop() } catch (_: Exception) {}
        encoder = null
        try { http?.stop() } catch (_: Exception) {}
        http = null
        try { broadcaster?.close() } catch (_: Exception) {}
        broadcaster = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        releasePerformanceLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Beri kesempatan singkat agar perintah Stop ke STB terkirim sebelum proses didinginkan sistem.
        try { stopThread?.join(1200) } catch (_: InterruptedException) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun liveSummary(b: TsBroadcaster): String {
        val rec = if (b.recordingActive()) {
            "Rekam " + String.format(Locale.US, "%.1f MB", b.recordingWrittenBytes / 1048576.0)
        } else {
            "Rekam " + b.recordingStatus()
        }
        return "$notifyBase • ${b.clientCount} STB • $rec"
    }

    @Suppress("DEPRECATION")
    private fun acquirePerformanceLocks() {
        try {
            val power = getSystemService(android.os.PowerManager::class.java)
            cpuWakeLock = power.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "A01Mirror::CapturePipeline"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            cpuWakeLock = null
        }

        try {
            val manager = getSystemService(android.net.wifi.WifiManager::class.java)
            val lock = manager.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "A01Mirror-LowLatency"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (_: Exception) {
            wifiLock = null
        }

        // Mode low-latency dipertahankan untuk foreground; high-perf di bawah membantu saat user
        // berpindah ke aplikasi lain sehingga service mirror tidak kehilangan jalur Wi-Fi.
        try {
            val manager = getSystemService(android.net.wifi.WifiManager::class.java)
            val lock = manager.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "A01Mirror-HighPerf"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiHighPerfLock = lock
        } catch (_: Exception) {
            wifiHighPerfLock = null
        }
    }

    @Suppress("DEPRECATION")
    private fun releasePerformanceLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null

        try {
            wifiHighPerfLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiHighPerfLock = null

        try {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        cpuWakeLock = null
    }

    private fun launchIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MirrorService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle("A01 Mirror")
            .setContentText(text)
            .setContentIntent(launchIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "A01 Mirror", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(ERROR_CHANNEL_ID, "A01 Mirror - masalah", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /**
     * Error dipublikasikan ke: status bersama (dibaca layar utama), Toast lewat thread utama
     * (dulu dipanggil dari thread lain sehingga gagal diam-diam), dan notifikasi terpisah yang tetap
     * ada walaupun service berhenti.
     */
    private fun postError(text: String) {
        lastError = text
        updateNotification("Perhatian: $text")
        mainHandler.post {
            try { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
        try {
            val notification = Notification.Builder(this, ERROR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mirror)
                .setContentTitle("A01 Mirror — ada masalah")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(launchIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    /**
     * Batas bitrate awal dari kecepatan link Wi-Fi HP (bila terbaca). Link lambat = bitrate awal lebih
     * rendah, jadi STB tidak langsung kebanjiran data dan buffering. Mode hotspot/tidak terbaca = tanpa batas.
     */
    @Suppress("DEPRECATION")
    private fun wifiBitrateCap(): Int {
        return try {
            val manager = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            val link = manager.connectionInfo?.linkSpeed ?: -1
            if (link <= 0) Int.MAX_VALUE else (link * 200_000).coerceIn(1_500_000, 8_000_000)
        } catch (_: Throwable) {
            Int.MAX_VALUE
        }
    }

    private fun hostFrom(location: String): String {
        return try { java.net.URI(location).host ?: location } catch (_: Exception) { location }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)
