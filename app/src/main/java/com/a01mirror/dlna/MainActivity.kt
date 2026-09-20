package com.a01mirror.dlna

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val renderers = mutableListOf<DlnaController.Renderer>()
    private var selected: DlnaController.Renderer? = null
    private var waitingForAudioPermission = false

    private lateinit var status: TextView
    private lateinit var deviceText: TextView
    private lateinit var ipInput: EditText
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner

    private companion object {
        const val REQUEST_CAPTURE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DlnaController.setDiscoveryContext(this)
        buildUi()
        status.text = "Siap. Sambungkan HP dan STP-A01 ke Wi-Fi yang sama."
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 28)
            gravity = Gravity.TOP
        }

        val title = TextView(this).apply {
            text = "A01 Mirror"
            textSize = 28f
            setTextColor(0xFF202124.toInt())
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val subtitle = TextView(this).apply {
            text = "Screen asli + audio internal → DLNA STP-A01\n720p/1080p • 30/60 fps • rekam .TS"
            textSize = 14f
            setTextColor(0xFF5F6368.toInt())
            setPadding(0, 4, 0, 22)
        }
        root.addView(subtitle)

        ipInput = EditText(this).apply {
            hint = "IP STB (opsional), mis. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(getSharedPreferences("a01mirror", Context.MODE_PRIVATE).getString("stb_ip", "") ?: "")
        }
        root.addView(ipInput, LinearLayout.LayoutParams(-1, -2))

        val scan = Button(this).apply {
            text = "Cari STB DLNA"
            setOnClickListener { scanDlna() }
        }
        root.addView(scan, LinearLayout.LayoutParams(-1, -2))

        deviceText = TextView(this).apply {
            text = "Perangkat: belum dipilih"
            textSize = 16f
            setPadding(0, 18, 0, 18)
        }
        root.addView(deviceText)

        val connect = Button(this).apply {
            text = "Pilih / Hubungkan STB"
            setOnClickListener { chooseRenderer() }
        }
        root.addView(connect, LinearLayout.LayoutParams(-1, -2))

        resolutionSpinner = Spinner(this)
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("1280×720 (ringan)", "1920×1080 (maksimal)")
        )
        root.addView(resolutionSpinner, LinearLayout.LayoutParams(-1, -2))

        fpsSpinner = Spinner(this)
        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("25 fps (sesuai STB T2, disarankan)", "30 fps", "60 fps (uji perangkat)")
        )
        root.addView(fpsSpinner, LinearLayout.LayoutParams(-1, -2))

        val start = Button(this).apply {
            text = "▶ Mulai Mirror + Rekam"
            setOnClickListener { requestCapture() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, -2))

        val stop = Button(this).apply {
            text = "■ Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MirrorService::class.java))
                status.text = "Mirror dihentikan."
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
            setTextColor(0xFF3949AB.toInt())
        }
        root.addView(status)

        val note = TextView(this).apply {
            text = "Catatan: DLNA STP-A01 bukan receiver Miracast. Aplikasi ini mengirim live H.264 + MP3 melalui HTTP/DLNA. Hasil akhir bergantung firmware STP-A01."
            textSize = 12f
            setTextColor(0xFF6D6D6D.toInt())
            setPadding(0, 26, 0, 0)
        }
        root.addView(note)

        // Layar bisa penuh (kolom IP + laporan pencarian), jadi dibuat bisa di-scroll.
        setContentView(ScrollView(this).apply { addView(root) })
    }

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
            }
            .show()
    }

    private fun requestCapture() {
        if (selected == null) {
            Toast.makeText(this, "Pilih STB DLNA dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waitingForAudioPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
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
        if (requestCode == 1001) {
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
        executor.shutdownNow()
        super.onDestroy()
    }
}
