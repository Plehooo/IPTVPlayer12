package com.a01mirror.dlna

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

// Palet warna UI (gelap, aksen indigo + cyan) — sama dengan logo aplikasi.
private val C_BG = 0xFF0E1120.toInt()
private val C_CARD = 0xFF181D36.toInt()
private val C_FIELD = 0xFF10142A.toInt()
private val C_STROKE = 0xFF283056.toInt()
private val C_ACCENT = 0xFF5C6BF0.toInt()
private val C_ACCENT_SOFT = 0xFF8C98FF.toInt()
private val C_TEXT = 0xFFF1F3FF.toInt()
private val C_MUTED = 0xFF9AA3C7.toInt()
private val C_GREEN = 0xFF2ECC8F.toInt()
private val C_AMBER = 0xFFFFB74D.toInt()
private val C_RED = 0xFFFF5C6C.toInt()

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val renderers = mutableListOf<DlnaController.Renderer>()
    private var selected: DlnaController.Renderer? = null
    private var waitingForAudioPermission = false
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var deviceText: TextView
    private lateinit var ipInput: EditText
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var statePill: TextView
    private lateinit var liveText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var lastBytes = 0L
    private var lastBytesMs = 0L
    private var shownError = ""
    private var shownLine = ""

    private val ticker = object : Runnable {
        override fun run() {
            refreshLive()
            ui.postDelayed(this, 700L)
        }
    }

    private companion object {
        const val REQUEST_CAPTURE = 2001
        const val REQUEST_AUDIO = 1001
        const val REQUEST_NOTIFICATIONS = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DlnaController.setDiscoveryContext(this)
        buildUi()
        status.text = "Siap. Sambungkan HP dan STP-A01 ke Wi-Fi yang sama."
        refreshLive()

        // Android 13+: tanpa izin ini notifikasi (dan tombol Stop di dalamnya) tidak tampil.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun styledButton(label: String, fill: Int, outline: Boolean = false, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.isAllCaps = false
        button.textSize = 15f
        button.typeface = Typeface.DEFAULT_BOLD
        button.stateListAnimator = null
        button.minHeight = dp(50)
        button.minimumHeight = dp(50)
        val shape = if (outline) {
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x00000000)
                setStroke(dp(2), fill)
            }
        } else {
            rounded(fill, 14)
        }
        button.setTextColor(if (outline) fill else 0xFFFFFFFF.toInt())
        button.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        button.setOnClickListener { onClick() }
        return button
    }

    private fun addCard(root: LinearLayout, title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(C_CARD, 18, C_STROKE)
        }
        val heading = TextView(this).apply {
            text = title.uppercase(Locale.getDefault())
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_ACCENT_SOFT)
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(heading)
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return card
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(C_MUTED)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun setPill(text: String, color: Int) {
        statePill.text = text
        statePill.setTextColor(color)
        statePill.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor((color and 0x00FFFFFF) or 0x26000000)
            setStroke(dp(1), color)
        }
    }

    @Suppress("DEPRECATION")
    private fun systemInsets(insets: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(i.left, i.top, i.right, i.bottom)
        } else {
            intArrayOf(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            gravity = Gravity.TOP
        }

        // ---- Header: logo + judul + status ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_logo) }
        header.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titles.addView(TextView(this).apply {
            text = "A01 Mirror"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
        })
        titles.addView(TextView(this).apply {
            text = "Layar HP + audio → STB DLNA • rekam .TS"
            textSize = 12f
            setTextColor(C_MUTED)
        })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        statePill = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        header.addView(statePill, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        setPill("SIAGA", C_MUTED)

        // ---- Kartu 1: STB ----
        val stbCard = addCard(root, "STB DLNA")
        ipInput = EditText(this).apply {
            hint = "IP STB (opsional), mis. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(getSharedPreferences("a01mirror", Context.MODE_PRIVATE).getString("stb_ip", "") ?: "")
            setTextColor(C_TEXT)
            setHintTextColor(C_MUTED)
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(C_FIELD, 12, C_STROKE)
        }
        stbCard.addView(ipInput, LinearLayout.LayoutParams(-1, -2))

        val stbButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stbButtons.addView(
            styledButton("Cari STB", C_ACCENT) { scanDlna() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }
        )
        stbButtons.addView(
            styledButton("Pilih / Hubungkan", C_ACCENT, outline = true) { chooseRenderer() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) }
        )
        stbCard.addView(stbButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        deviceText = TextView(this).apply {
            text = "Perangkat: belum dipilih"
            textSize = 14f
            setTextColor(C_TEXT)
            setPadding(0, dp(12), 0, 0)
        }
        stbCard.addView(deviceText)

        // ---- Kartu 2: kualitas ----
        val qualityCard = addCard(root, "Kualitas")
        qualityCard.addView(label("Resolusi"))
        resolutionSpinner = Spinner(this)
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("1280×720 (stabil, disarankan)", "1920×1080 (berat)")
        )
        qualityCard.addView(resolutionSpinner, LinearLayout.LayoutParams(-1, -2))

        qualityCard.addView(label("Frame rate"))
        fpsSpinner = Spinner(this)
        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("25 fps (sesuai STB T2, paling stabil)", "30 fps", "60 fps (uji perangkat)")
        )
        qualityCard.addView(fpsSpinner, LinearLayout.LayoutParams(-1, -2))

        // ---- Kartu 3: kontrol ----
        val controlCard = addCard(root, "Kontrol")
        startButton = styledButton("▶  Mulai Mirror + Rekam", C_ACCENT) { requestCapture() }
        controlCard.addView(startButton, LinearLayout.LayoutParams(-1, -2))
        stopButton = styledButton("■  Stop", C_RED, outline = true) {
            stopService(Intent(this@MainActivity, MirrorService::class.java))
            status.text = "Mirror dihentikan."
        }
        controlCard.addView(stopButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // ---- Kartu 4: live ----
        val liveCard = addCard(root, "Live")
        liveText = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(C_TEXT)
        }
        liveCard.addView(liveText)

        // ---- Kartu 5: status + alat ----
        val statusCard = addCard(root, "Status")
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(C_ACCENT_SOFT)
        }
        statusCard.addView(status)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(
            styledButton("Log DLNA", C_ACCENT, outline = true) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Log DLNA")
                    .setMessage(DlnaController.debugLog())
                    .setPositiveButton("Tutup", null)
                    .show()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }
        )
        tools.addView(
            styledButton("Izin baterai", C_ACCENT, outline = true) { openBatterySettings() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) }
        )
        statusCard.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val note = TextView(this).apply {
            text = "Catatan: DLNA STP-A01 bukan receiver Miracast. Aplikasi ini mengirim live H.264 + MP3 " +
                "melalui HTTP/DLNA. Agar tidak tersendat saat membuka aplikasi berat, set baterai aplikasi ini " +
                "ke \"Tanpa batasan\" (tombol Izin baterai). Hasil akhir bergantung firmware STP-A01."
            textSize = 11.5f
            setTextColor(C_MUTED)
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        root.addView(note)

        // Layar bisa penuh (kartu-kartu di atas), jadi dibuat bisa di-scroll.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(C_BG)
            addView(root)
        }
        // Android 15+ memaksa edge-to-edge: geser isi agar tidak tertutup status bar / navigasi.
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = systemInsets(insets)
            root.setPadding(dp(18) + bars[0], dp(18) + bars[1], dp(18) + bars[2], dp(24) + bars[3])
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Buka Pengaturan → Baterai → A01 Mirror → Tanpa batasan.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Dipanggil tiap 0,7 dtk saat layar tampil: status, statistik live, dan tombol aktif/nonaktif. */
    private fun refreshLive() {
        val running = MirrorService.running
        val b = MirrorService.activeBroadcaster

        startButton.isEnabled = !running
        startButton.alpha = if (running) 0.4f else 1f
        stopButton.isEnabled = running
        stopButton.alpha = if (running) 1f else 0.4f

        val error = MirrorService.lastError
        when {
            running && b != null && b.clientCount > 0 -> setPill("● LIVE", C_GREEN)
            running -> setPill("● MENUNGGU STB", C_AMBER)
            error.isNotEmpty() -> setPill("GAGAL", C_RED)
            else -> setPill("SIAGA", C_MUTED)
        }

        if (running && b != null) {
            val now = SystemClock.elapsedRealtime()
            val bytes = b.bytesPublished
            val mbps = if (lastBytesMs > 0L && now > lastBytesMs && bytes >= lastBytes) {
                (bytes - lastBytes) * 8.0 / ((now - lastBytesMs) * 1000.0)
            } else 0.0
            lastBytes = bytes
            lastBytesMs = now

            val recLine = if (b.recordingActive() || b.recordingStatus() == "menunggu keyframe") {
                "aktif • " + String.format(Locale.US, "%.1f MB", b.recordingWrittenBytes / 1048576.0)
            } else {
                b.recordingStatus()
            }
            liveText.text = buildString {
                append("Video  : ").append(b.videoFrames).append(" frame • ")
                    .append(String.format(Locale.US, "%.2f Mbps", mbps)).append('\n')
                append("STB    : ").append(b.clientCount).append(" terhubung\n")
                append("Drop   : ").append(b.droppedClientPackets).append(" paket kirim\n")
                append("Rekam  : ").append(recLine)
                if (b.recordingGaps > 0L) append(" • celah ").append(b.recordingGaps)
                if (b.recordingName.isNotEmpty()) append("\nFile   : ").append(b.recordingName)
            }
        } else {
            lastBytesMs = 0L
            liveText.text = "Belum tayang. Pilih STB lalu tekan Mulai."
        }

        val line = MirrorService.statusLine
        if (line.isNotEmpty() && line != shownLine) {
            shownLine = line
            status.text = line
        }
        if (error.isNotEmpty() && error != shownError) {
            shownError = error
            status.text = "⚠ $error"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Alur DLNA / capture (tidak berubah)
    // ---------------------------------------------------------------------------------------

    private fun scanDlna() {
        val manualIp = ipInput.text.toString().trim()
        getSharedPreferences("a01mirror", Context.MODE_PRIVATE).edit().putString("stb_ip", manualIp).apply()
        status.text = if (manualIp.isEmpty()) "Mencari perangkat DLNA…" else "Mencari STB di $manualIp…"
        executor.execute {
            val found = DlnaController.discover(manualIp)
            runOnUiThread {
                renderers.clear()
                renderers.addAll(found)
                if (found.isEmpty()) {
                    status.text = "STB tidak ditemukan. Pastikan HP dan STB satu Wi-Fi/hotspot, DLNA/DMR aktif di STB, " +
                        "lalu isi IP STB (lihat di layar STB) dan cari lagi.\n\n" + DlnaController.lastReport
                } else {
                    status.text = "Ditemukan ${found.size} perangkat DLNA."
                    chooseRenderer()
                }
            }
        }
    }

    private fun chooseRenderer() {
        if (renderers.isEmpty()) {
            Toast.makeText(this, "Tekan Cari STB DLNA dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = renderers.map { "${it.name}\n${it.host}\n${it.location}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih perangkat DLNA")
            .setItems(names) { _, which ->
                selected = renderers[which]
                deviceText.text = "Perangkat: ${selected!!.name}\n${selected!!.host}"
                status.text = "STB siap. Tekan Mulai Mirror + Rekam."
                probeSelected()
            }
            .show()
    }

    /** Seperti connect_renderer() di tar v6: GetTransportInfo + GetProtocolInfo saat STB dipilih. */
    private fun probeSelected() {
        val renderer = selected ?: return
        executor.execute {
            val text = DlnaController.probe(renderer)
            runOnUiThread {
                status.text = "STB siap. Tekan Mulai Mirror + Rekam.\n\n$text"
            }
        }
    }

    private fun requestCapture() {
        if (selected == null) {
            Toast.makeText(this, "Pilih STB DLNA dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waitingForAudioPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            status.text = "Izinkan audio untuk melanjutkan…"
            return
        }
        val pm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun startMirrorService(resultCode: Int, data: Intent, renderer: DlnaController.Renderer) {
        val width = if (resolutionSpinner.selectedItemPosition == 0) 1280 else 1920
        val height = if (resolutionSpinner.selectedItemPosition == 0) 720 else 1080
        val fps = when (fpsSpinner.selectedItemPosition) {
            0 -> 25
            1 -> 30
            else -> 60
        }

        val intent = Intent(this, MirrorService::class.java).apply {
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_RESULT_DATA, data)
            putExtra(MirrorService.EXTRA_WIDTH, width)
            putExtra(MirrorService.EXTRA_HEIGHT, height)
            putExtra(MirrorService.EXTRA_FPS, fps)
            putExtra(MirrorService.EXTRA_RENDERER_LOCATION, renderer.location)
            putExtra(MirrorService.EXTRA_RENDERER_CONTROL_URL, renderer.avTransportControlUrl)
            putExtra(MirrorService.EXTRA_RENDERER_SERVICE_TYPE, renderer.avTransportServiceType)
            putExtra(MirrorService.EXTRA_RENDERER_NAME, renderer.name)
        }
        MirrorService.lastError = ""
        MirrorService.statusLine = ""
        shownError = ""
        shownLine = ""
        startForegroundService(intent)
        status.text = "Menyiapkan mirror…"
    }


    @Deprecated("Use Activity Result APIs in a future UI refactor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture dibatalkan."
            return
        }
        val renderer = selected
        if (renderer == null) {
            status.text = "Pilih STB DLNA dulu."
            return
        }
        startMirrorService(resultCode, data, renderer)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && waitingForAudioPermission) {
                waitingForAudioPermission = false
                requestCapture()
            } else if (!granted) {
                waitingForAudioPermission = false
                status.text = "Audio tidak diizinkan. Melanjutkan mirror tanpa audio internal…"
                val pm = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CAPTURE)
            }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        executor.shutdownNow()
        super.onDestroy()
    }
}
