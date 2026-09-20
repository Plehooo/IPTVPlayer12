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
import android.os.IBinder
import android.widget.Toast

class MirrorService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_RENDERER_LOCATION = "renderer_location"
        const val EXTRA_RENDERER_CONTROL_URL = "renderer_control_url"
        const val EXTRA_RENDERER_SERVICE_TYPE = "renderer_service_type"
        const val EXTRA_RENDERER_NAME = "renderer_name"

        private const val NOTIFICATION_ID = 4101
        private const val CHANNEL_ID = "a01_mirror"
        private const val ACTION_STOP = "com.a01mirror.dlna.STOP"
    }

    private var projection: MediaProjection? = null
    private var encoder: H264Encoder? = null
    private var audio: AudioCapture? = null
    private var broadcaster: TsBroadcaster? = null
    private var http: LiveHttpServer? = null
    private var renderer: DlnaController.Renderer? = null
    private var mirrorThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection != null) return START_NOT_STICKY

        try {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = intent?.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                ?: throw IllegalArgumentException("Data MediaProjection hilang")
            val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
            val fps = intent.getIntExtra(EXTRA_FPS, 30)
            val name = intent.getStringExtra(EXTRA_RENDERER_NAME) ?: "DLNA Renderer"
            val location = intent.getStringExtra(EXTRA_RENDERER_LOCATION) ?: ""
            val control = intent.getStringExtra(EXTRA_RENDERER_CONTROL_URL) ?: ""
            val serviceType = intent.getStringExtra(EXTRA_RENDERER_SERVICE_TYPE)
                ?: "urn:schemas-upnp-org:service:AVTransport:1"

            val target = DlnaController.Renderer(name, hostFrom(location), location, control, serviceType)
            renderer = target

            // Android 14+: permission/type requirements for a mediaProjection FGS are mandatory.
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Menyiapkan ${name}"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            val pm = getSystemService(MediaProjectionManager::class.java)
            projection = pm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection tidak tersedia")

            val stream = TsBroadcaster(contentResolver)
            broadcaster = stream
            http = LiveHttpServer(stream, stream.sessionToken).also { it.start() }

            // IP HP dihitung ke arah STB (bukan asal ambil wlan0) supaya URL stream terbaca STB.
            val ip = localIpv4For(this, target.host)
            val streamUrl = "http://$ip:${http!!.port}/a01/${stream.sessionToken}/stream.ts"
            updateNotification("Live ${width}×${height}@${fps}fps • $ip:${http!!.port}")

            encoder = H264Encoder(
                projection = projection!!,
                width = width,
                height = height,
                fps = fps,
                densityDpi = resources.configuration.densityDpi,
                broadcaster = stream,
                onFailure = {
                    postError("Video: ${it.message ?: "gagal"}")
                    stopSelf()
                }
            ).also { it.start() }

            audio = AudioCapture(
                projection = projection!!,
                broadcaster = stream,
                onFailure = { postError("Audio internal: ${it.message ?: "tidak tersedia"}") }
            ).also { it.start() }

            mirrorThread = Thread {
                try {
                    // Jangan menunggu berlebihan: server sudah mengirim PAT/PMT saat STB connect,
                    // lalu cukup pastikan frame video pertama sudah tersedia sebelum Play.
                    val deadline = System.currentTimeMillis() + 1800L
                    while (System.currentTimeMillis() < deadline && stream.videoFrames < 1L) {
                        Thread.sleep(30)
                    }
                    // Buffer awal pendek: cukup untuk melewatkan PAT/PMT + IDR pertama tanpa menambah
                    // detik latency seperti versi sebelumnya.
                    Thread.sleep(140)
                    val r = renderer
                    if (r != null && streamUrl.startsWith("http://") && !streamUrl.startsWith("http://127.")) {
                        val result = DlnaController.playLive(r, streamUrl, "${width}x${height}")
                        if (result.isSuccess) {
                            val state = DlnaController.lastTransportState
                            updateNotification(
                                "Tayang ke ${r.name}" + (if (state.isNotBlank()) " ($state)" else "") + " • Rekam berjalan"
                            )
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

    override fun onDestroy() {
        val r = renderer
        if (r != null) {
            Thread { try { DlnaController.stop(r) } catch (_: Exception) {} }.start()
        }
        try { mirrorThread?.interrupt() } catch (_: Exception) {}
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MirrorService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launch = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("A01 Mirror")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "A01 Mirror", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun postError(text: String) {
        updateNotification("Perhatian: $text")
        // Toast is best-effort because this is a foreground service.
        try { Toast.makeText(this, text, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }

    private fun hostFrom(location: String): String {
        return try { java.net.URI(location).host ?: location } catch (_: Exception) { location }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)
