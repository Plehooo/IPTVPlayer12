package com.a01mirror.dlna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Best-effort physical-display power controller for rooted devices.
 *
 * The normal MediaProjection API does not expose the privileged physical-display
 * power operation. On Android 15+, the shell path `cmd display power-off 0` is
 * available; rooted devices can execute it with uid 0. This controller is
 * deliberately opt-in and only invokes `su` after the user presses the screen-off
 * button while a mirror session is running.
 */
class ScreenPowerController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var rootAvailable = false

    private var receiverRegistered = false
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "A01-ScreenPowerCmd").apply { isDaemon = true }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active || intent?.action != Intent.ACTION_SCREEN_ON) return
            // Physical POWER can wake the panel again. Re-apply the privileged
            // display-power state once the system reports SCREEN_ON.
            commandExecutor.execute {
                try {
                    Thread.sleep(80L)
                    if (active) powerOff()
                } catch (_: InterruptedException) {
                } catch (_: Throwable) {
                }
            }
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (active) return true

        if (!rootAvailable && !probeRoot()) {
            onStatus("Mode layar OFF butuh akses root/privileged shell.")
            return false
        }

        if (!powerOff()) {
            onStatus("Perintah layar OFF ditolak oleh ROM/perangkat ini.")
            return false
        }

        registerScreenReceiver()
        active = true
        // A physical display may transition to ON between the command above and
        // receiver registration; apply one more command after activation.
        commandExecutor.execute {
            if (active) powerOff()
        }

        onStatus(if (Build.VERSION.SDK_INT >= 35) {
            "Layar fisik OFF aktif (root/privileged). Mirror tetap berjalan selama ROM mendukung capture saat display power-off."
        } else {
            "Layar fisik OFF aktif (root/privileged). Dukungan bergantung ROM/perangkat."
        })
        return true
    }

    @Synchronized
    fun stop(restoreScreen: Boolean = true) {
        active = false
        unregisterScreenReceiver()
        if (restoreScreen && rootAvailable) {
            try { commandExecutor.submit { powerOn() }.get(1800L, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        }
        onStatus("Mode layar OFF dimatikan.")
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Throwable) {
            receiverRegistered = false
        }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        receiverRegistered = false
    }

    private fun probeRoot(): Boolean {
        val result = execRoot("id", 2500L) ?: return false
        rootAvailable = result.exitCode == 0 && result.output.contains("uid=0")
        return rootAvailable
    }

    private fun powerOff(): Boolean =
        execRoot("cmd display power-off 0", 1800L)?.exitCode == 0

    private fun powerOn(): Boolean =
        execRoot("cmd display power-on 0", 1800L)?.exitCode == 0


    private data class CommandResult(val exitCode: Int, val output: String)

    /** Run a bounded root command so a broken su/daemon can never block the service. */
    private fun execRoot(command: String, timeoutMs: Long): CommandResult? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val readerThread = Thread {
                try {
                    reader.forEachLine { line ->
                        if (output.length < 4096) {
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(line)
                        }
                    }
                } catch (_: Throwable) {
                }
            }.also {
                it.name = "A01-SuReader"
                it.isDaemon = true
                it.start()
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                try { process.destroyForcibly() } catch (_: Throwable) {}
                return null
            }
            try { readerThread.join(150L) } catch (_: InterruptedException) {}
            CommandResult(process.exitValue(), output.toString())
        } catch (_: Throwable) {
            null
        }
    }
}
