package com.a01mirror.dlna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * No-root screen blackout controller.
 *
 * Android does not expose the privileged physical-display power-off operation to
 * ordinary apps. Instead of asking for root/shell access, this controller keeps
 * the device interactive and drives the main display backlight to its minimum
 * system value. The MediaProjection pipeline keeps capturing the actual screen
 * content, while the physical panel is visually black/dark.
 *
 * The controller also watches ACTION_SCREEN_OFF. If the user presses the
 * physical POWER key, Android can make the device non-interactive; a short,
 * bounded wake-up is requested immediately so the mirror session remains alive.
 * This is best-effort and OEM-dependent, but it requires no root or privileged
 * shell. The user must grant WRITE_SETTINGS once so the app can control the
 * global backlight level and restore the previous value safely.
 */
class ScreenPowerController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    var active: Boolean = false
        private set

    companion object {
        private const val TAG = "A01Mirror::ScreenBlackout"
        private const val PREFS = "a01mirror_screen_blackout"
        private const val KEY_ACTIVE = "active"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_BRIGHTNESS_MODE = "brightness_mode"
        // Android documents 1 as the minimum system backlight value.
        private const val MIN_BRIGHTNESS = 1

        /**
         * Recover brightness if Android killed the process while blackout mode was active.
         * Called opportunistically by the app/service on the next launch.
         */
        @JvmStatic
        fun recoverStaleState(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.System.canWrite(context)) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return
            try {
                val resolver = context.contentResolver
                val brightness = prefs.getInt(KEY_BRIGHTNESS, -1)
                val mode = prefs.getInt(KEY_BRIGHTNESS_MODE, -1)
                if (brightness >= 1) {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness.coerceIn(1, 255))
                }
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ||
                    mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                ) {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
                }
            } catch (_: Throwable) {
            } finally {
                prefs.edit().clear().apply()
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "A01-ScreenBlackout").apply { isDaemon = true }
    }

    private var receiverRegistered = false
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var savedBrightness: Int? = null
    private var savedBrightnessMode: Int? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // ACTION_SCREEN_OFF is a protected system broadcast and is
                    // delivered to context-registered receivers. Keep the work
                    // bounded and off the receiver's main thread.
                    commandExecutor.execute {
                        try {
                            Thread.sleep(35L)
                            if (!active) return@execute
                            acquireScreenWakeLock()
                            forceMinimumBrightness()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (_: Throwable) {
                            // OEM-specific power behavior must never crash mirror.
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    commandExecutor.execute {
                        if (!active) return@execute
                        try {
                            forceMinimumBrightness()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    fun canUse(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(appContext)
    }

    /** Returns true when the user needs to grant the one-time system-settings access. */
    fun needsWriteSettings(): Boolean = !canUse()

    /**
     * Start no-root blackout mode. No `su`, `cmd display`, hidden API, or shell
     * command is used anywhere in this class.
     */
    @Synchronized
    fun start(): Boolean {
        if (active) return true

        if (!canUse()) {
            onStatus("Izin Ubah Setelan Sistem diperlukan sekali untuk mode layar gelap tanpa root.")
            return false
        }

        try {
            snapshotBrightness()
            forceMinimumBrightness()
            acquireScreenWakeLock()
            registerScreenReceiver()
            active = true

            // Re-apply after activation to cover a race where the system changes
            // interactive state while the receiver is being registered.
            commandExecutor.execute {
                if (!active) return@execute
                try {
                    forceMinimumBrightness()
                    acquireScreenWakeLock()
                } catch (_: Throwable) {
                }
            }

            onStatus("Mode layar gelap aktif • TANPA ROOT • mirror tetap berjalan.")
            return true
        } catch (t: Throwable) {
            active = false
            unregisterScreenReceiver()
            releaseScreenWakeLock()
            restoreBrightness()
            onStatus("Mode layar gelap gagal: ${t.message ?: "perangkat menolak kontrol brightness"}")
            return false
        }
    }

    @Synchronized
    fun stop(restoreScreen: Boolean = true) {
        active = false
        unregisterScreenReceiver()

        if (restoreScreen) {
            // Wake first so restoring brightness is visible immediately even if
            // the user just pressed the power key.
            acquireScreenWakeLock()
            restoreBrightness()
        }

        releaseScreenWakeLock()
        onStatus("Mode layar gelap dimatikan.")
    }

    fun shutdown() {
        try { stop(restoreScreen = true) } catch (_: Throwable) {}
        commandExecutor.shutdownNow()
    }

    private fun snapshotBrightness() {
        if (savedBrightness == null) {
            savedBrightness = try {
                Settings.System.getInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (_: Throwable) {
                null
            }
        }
        if (savedBrightnessMode == null) {
            savedBrightnessMode = try {
                Settings.System.getInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE
                )
            } catch (_: Throwable) {
                null
            }
        }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_BRIGHTNESS, savedBrightness ?: 1)
            .putInt(KEY_BRIGHTNESS_MODE, savedBrightnessMode ?: Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            .apply()
    }

    private fun forceMinimumBrightness() {
        if (!canUse()) return
        val resolver = appContext.contentResolver
        try {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        } catch (_: Throwable) {
        }
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            MIN_BRIGHTNESS
        )
    }

    private fun restoreBrightness() {
        if (!canUse()) return
        val resolver = appContext.contentResolver
        try {
            savedBrightness?.let {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, it.coerceIn(1, 255))
            }
            savedBrightnessMode?.let {
                if (it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ||
                    it == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                ) {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, it)
                }
            }
        } catch (_: Throwable) {
        } finally {
            savedBrightness = null
            savedBrightnessMode = null
            prefs.edit().clear().apply()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        try {
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE
            screenWakeLock = powerManager.newWakeLock(flags, TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
            screenWakeLock = null
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        } finally {
            screenWakeLock = null
        }
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(
                    screenReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Throwable) {
            receiverRegistered = false
        }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try { appContext.unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        receiverRegistered = false
    }
}
