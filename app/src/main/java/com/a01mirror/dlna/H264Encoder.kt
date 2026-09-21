package com.a01mirror.dlna

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.nio.ByteBuffer

class H264Encoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val densityDpi: Int,
    private val broadcaster: TsBroadcaster,
    private val onFailure: (Throwable) -> Unit,
    // Batas atas bitrate (mis. dari kecepatan link Wi-Fi). Nilai default = tanpa batas tambahan.
    private val maxBitrate: Int = Int.MAX_VALUE,
    // Dipanggil saat frame rate berubah otomatis (naik/turun mengikuti beban HP dan jaringan).
    private val onQuality: ((String) -> Unit)? = null
) {
    /** Hasil penyesuaian permintaan resolusi/fps ke kemampuan encoder HP ini. */
    class Fit(val width: Int, val height: Int, val fps: Int, val note: String)

    private var codec: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var firstPtsUs = Long.MIN_VALUE
    private var firstWallNs = Long.MIN_VALUE
    private var baseBitrate = 2_000_000
    private var currentBitrate = 2_000_000
    private var lateStreak = 0
    private var healthySinceNs = Long.MIN_VALUE
    private var lastBitrateChangeNs = 0L
    private var lastSyncRequestNs = 0L
    private var lastClientDrops = 0L
    private var recoveries = 0
    private var anchorNs = 0L
    private var lastVideoPts90k = -1L
    private val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

    // --- Adaptasi real-time (semua dihitung dari kondisi HP/jaringan saat ini, tidak terkunci angka tetap) ---
    @Volatile private var activeFps = fps
    private val fpsSteps: IntArray = buildFpsSteps(fps)
    private var fpsIndex = 0
    private var fpsChangeRequest = -1
    private var lastFpsChangeNs = 0L
    private var overloadSinceNs = Long.MIN_VALUE
    private var steadySinceNs = Long.MIN_VALUE
    private var pressureHoldUntilNs = 0L
    @Volatile private var thermalStatus = 0
    @Volatile private var thermalFactor = 1.0

    companion object {
        private const val AUDIO_BPS = 128_000L

        /** Tangga frame rate untuk naik/turun otomatis: dimulai dari permintaan, turun bertahap sampai 15 fps. */
        private fun buildFpsSteps(requested: Int): IntArray {
            val steps = ArrayList<Int>()
            steps.add(requested)
            for (candidate in intArrayOf(60, 30, 25, 20, 15)) {
                if (candidate < requested && !steps.contains(candidate)) steps.add(candidate)
            }
            return steps.toIntArray()
        }

        private val SIZE_LADDER = arrayOf(
            intArrayOf(1920, 1080),
            intArrayOf(1280, 720),
            intArrayOf(960, 540),
            intArrayOf(848, 480),
            intArrayOf(640, 360),
            intArrayOf(512, 288)
        )
        private val FPS_LADDER = intArrayOf(60, 30, 25, 24, 20, 15)

        /**
         * Permintaan "Otomatis": pilih resolusi/fps awal dari kelas HP (RAM, jumlah inti CPU,
         * media performance class). Selalu konservatif agar STB tidak buffering di HP mana pun.
         * Hasil: [lebar, tinggi, fps].
         */
        fun autoRequest(context: Context): IntArray {
            var ramGb = 4.0
            var lowRam = false
            try {
                val am = context.getSystemService(ActivityManager::class.java)
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                ramGb = info.totalMem / 1073741824.0
                lowRam = am.isLowRamDevice
            } catch (_: Throwable) {
            }
            val cores = Runtime.getRuntime().availableProcessors()
            val perfClass = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
            return when {
                lowRam || ramGb < 2.2 -> intArrayOf(848, 480, 25)
                // Awal yang realistis; setelah itu adaptasi real-time (bitrate/fps) yang menentukan.
                ramGb < 3.2 || cores < 6 -> intArrayOf(960, 540, 25)
                perfClass >= 31 || (ramGb >= 5.0 && cores >= 8) -> intArrayOf(1280, 720, 30)
                else -> intArrayOf(1280, 720, 25)
            }
        }

        /**
         * Turunkan fps dulu (jaga ketajaman teks), baru resolusi, sampai kombinasi didukung encoder
         * hardware HP ini. Tidak ada angka yang dikunci ke satu merek/tipe HP.
         */
        fun fitToDevice(width: Int, height: Int, fps: Int): Fit {
            var video: MediaCodecInfo.VideoCapabilities? = null
            try {
                val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    .sortedWith(
                        compareBy<MediaCodecInfo> { !runCatching { it.isHardwareAccelerated }.getOrDefault(false) }
                            .thenBy { runCatching { !it.isVendor }.getOrDefault(true) }
                            .thenBy { it.name.contains("google", ignoreCase = true) }
                    )
                for (info in infos) {
                    val caps = try {
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
                    video = caps.videoCapabilities
                    break
                }
            } catch (_: Throwable) {
            }
            val caps = video ?: return Fit(width, height, fps, "")

            val requestedPixels = width.toLong() * height.toLong()
            val sizes = ArrayList<IntArray>()
            sizes.add(intArrayOf(width, height))
            for (s in SIZE_LADDER) {
                if (s[0].toLong() * s[1].toLong() < requestedPixels) sizes.add(s)
            }
            val rates = ArrayList<Int>()
            rates.add(fps)
            for (f in FPS_LADDER) if (f < fps) rates.add(f)

            // Putaran 1 menjaga fps >= 24; putaran 2 (darurat) menerima fps berapa pun.
            for (minFps in intArrayOf(24, 1)) {
                for (s in sizes) {
                    for (f in rates) {
                        if (f < minFps) continue
                        if (fits(caps, s[0], s[1], f)) {
                            val same = s[0] == width && s[1] == height && f == fps
                            val note = if (same) "" else
                                "Disesuaikan ke ${s[0]}×${s[1]}@${f}fps (HP ini tidak sanggup ${width}×${height}@${fps}fps)."
                            return Fit(s[0], s[1], f, note)
                        }
                    }
                }
            }
            return Fit(width, height, fps, "")
        }

        private fun fits(caps: MediaCodecInfo.VideoCapabilities, w: Int, h: Int, f: Int): Boolean {
            try {
                if (!caps.areSizeAndRateSupported(w, h, f.toDouble())) return false
                val achievable = try { caps.getAchievableFrameRatesFor(w, h) } catch (_: Throwable) { null }
                return achievable == null || achievable.upper >= f * 0.85
            } catch (_: Throwable) {
                return true
            }
        }
    }

    @Synchronized
    fun start() {
        check(!running) { "encoder already running" }
        resetClock()
        lastVideoPts90k = -1L
        codec = createConfiguredCodec()
        inputSurface = codec!!.createInputSurface()
        codec!!.start()
        publishQuality(false)

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                running = false
                try { onFailure(IllegalStateException("Screen capture dihentikan oleh sistem.")) } catch (_: Exception) {}
            }
        }
        projection.registerCallback(projectionCallback!!, null)

        virtualDisplay = projection.createVirtualDisplay(
            "A01Mirror",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        running = true
        thread = Thread {
            // Thread encoder diberi prioritas tinggi supaya frame tetap mengalir saat aplikasi berat berjalan.
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
            drainLoop()
        }.also {
            it.name = "A01-H264"
            it.start()
        }
    }

    private fun buildFormat(
        strict: Boolean,
        encoderCapabilities: MediaCodecInfo.EncoderCapabilities? = null
    ): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Conservative live bitrates keep inexpensive Wi-Fi/DLNA renderers close to the live edge.
            // Quality is still high enough for UI/text at 720p while leaving headroom for audio + jitter.
            val bitrate = when {
                width >= 1920 && activeFps >= 60 -> 5_500_000
                width >= 1920 -> 4_200_000
                width >= 1280 && activeFps >= 30 -> 2_700_000
                width >= 1280 -> 2_500_000
                width >= 960 -> 1_800_000
                width >= 848 -> 1_450_000
                width >= 640 -> 1_050_000
                else -> 750_000
            }.coerceAtMost(maxBitrate).coerceAtLeast(minBitrate())
            baseBitrate = bitrate
            currentBitrate = bitrate
            broadcaster.setTargetVideoBitrate(bitrate)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, activeFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
            if (strict) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, activeFps)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, activeFps.toFloat())
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, avcLevel())
                val frameDropCbr = if (Build.VERSION.SDK_INT >= 31 &&
                    encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD) == true
                ) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                }
                setInteger(MediaFormat.KEY_BITRATE_MODE, frameDropCbr)
            }
        }

    private fun avcLevel(): Int {
        val mbs = ((width + 15) / 16) * ((height + 15) / 16)
        val rate = mbs * activeFps
        return when {
            mbs <= 3600 && rate <= 108_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            mbs <= 5120 && rate <= 216_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32
            mbs <= 8192 && rate <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
        }
    }

    /** Prefer a real hardware AVC encoder with Surface input; fall back safely if vendor hints are rejected. */
    private fun createConfiguredCodec(): MediaCodec {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val candidates = codecList.codecInfos
                    .asSequence()
                    .filter { it.isEncoder }
                    .filter { it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    .filter { runCatching { it.isHardwareAccelerated }.getOrDefault(false) }
                    .sortedWith(compareBy<MediaCodecInfo> {
                        runCatching { !it.isVendor }.getOrDefault(true)
                    }.thenBy { it.name.contains("google", ignoreCase = true) })
                    .toList()

                for (info in candidates) {
                    try {
                        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
                        val candidate = MediaCodec.createByCodecName(info.name)
                        try {
                            candidate.configure(
                                buildFormat(true, caps.encoderCapabilities),
                                null,
                                null,
                                MediaCodec.CONFIGURE_FLAG_ENCODE
                            )
                            return candidate
                        } catch (_: Throwable) {
                            try { candidate.release() } catch (_: Exception) {}
                        }

                        // Some vendor codecs reject one optional strict hint (for example
                        // CBR-FD/profile/latency) while still supporting hardware Surface input.
                        // Retry the same hardware codec with the conservative format before ever
                        // falling back to a generic encoder that might be software-only.
                        try {
                            val relaxed = MediaCodec.createByCodecName(info.name)
                            try {
                                relaxed.configure(
                                    buildFormat(false, caps.encoderCapabilities),
                                    null,
                                    null,
                                    MediaCodec.CONFIGURE_FLAG_ENCODE
                                )
                                return relaxed
                            } catch (_: Throwable) {
                                try { relaxed.release() } catch (_: Exception) {}
                            }
                        } catch (_: Throwable) {
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        var c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(buildFormat(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Throwable) {
            try { c.release() } catch (_: Exception) {}
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(buildFormat(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return c
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        try { thread?.join(1200) } catch (_: InterruptedException) {}
        thread = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projectionCallback?.let { projection.unregisterCallback(it) } } catch (_: Exception) {}
        projectionCallback = null
        virtualDisplay = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    private fun resetClock() {
        firstPtsUs = Long.MIN_VALUE
        firstWallNs = Long.MIN_VALUE
        lateStreak = 0
        healthySinceNs = Long.MIN_VALUE
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val c = codec ?: break
                // Klien STB baru / antrean kirim macet meminta IDR baru (resync bersih, bukan buang P-frame).
                if (broadcaster.consumeKeyFrameRequest()) {
                    if (!requestSyncFrame(c, System.nanoTime(), 250_000_000L)) broadcaster.requestKeyFrame()
                }
                if (fpsChangeRequest >= 0) {
                    // Ganti frame rate = buat ulang codec (sesi MediaProjection tetap); resolusi tidak berubah
                    // sehingga decoder STB tidak perlu inisialisasi ulang ukuran gambar.
                    val step = fpsChangeRequest
                    fpsChangeRequest = -1
                    applyFpsStep(step)
                    continue
                }
                when (val index = c.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = c.outputFormat
                        outFormat.getByteBuffer("csd-0")?.let(::setConfig)
                        outFormat.getByteBuffer("csd-1")?.let(::setConfig)
                    }
                    else -> if (index >= 0) {
                        val buffer = c.getOutputBuffer(index)
                        try {
                            if (buffer != null && info.size > 0) {
                                val data = ByteArray(info.size)
                                val oldPos = buffer.position()
                                buffer.position(info.offset)
                                buffer.get(data)
                                buffer.position(oldPos)

                                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    setConfig(ByteBuffer.wrap(data))
                                } else if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                                    val annexB = normalizeH264(data)
                                    val isKey =
                                        (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || containsIdrFast(annexB)
                                    val rawPtsUs = info.presentationTimeUs.coerceAtLeast(0L)

                                    if (firstPtsUs == Long.MIN_VALUE) {
                                        firstPtsUs = rawPtsUs
                                        firstWallNs = System.nanoTime()
                                        // Timestamp Surface memakai jam monotonic yang sama dengan System.nanoTime();
                                        // bila vendor memakai jam lain (selisih > 2 dtk) pakai jam dinding.
                                        val captureNs = rawPtsUs * 1000L
                                        val skew = if (captureNs > firstWallNs) captureNs - firstWallNs else firstWallNs - captureNs
                                        anchorNs = if (skew < 2_000_000_000L) captureNs else firstWallNs
                                    }

                                    val normalizedPtsUs = (rawPtsUs - firstPtsUs).coerceAtLeast(0L)
                                    val nowNs = System.nanoTime()
                                    val elapsedUs = ((nowNs - firstWallNs).coerceAtLeast(0L)) / 1_000L
                                    val lagUs = elapsedUs - normalizedPtsUs

                                    if (lagUs > 90_000L) {
                                        lateStreak++
                                        healthySinceNs = Long.MIN_VALUE
                                    } else {
                                        lateStreak = 0
                                        if (healthySinceNs == Long.MIN_VALUE) healthySinceNs = nowNs
                                    }

                                    val clientDrops = broadcaster.droppedClientPackets
                                    val networkPressure = clientDrops != lastClientDrops
                                    lastClientDrops = clientDrops
                                    if (networkPressure) pressureHoldUntilNs = nowNs + 1_500_000_000L
                                    maybeAdaptBitrate(c, lagUs, nowNs, networkPressure)
                                    evaluateFps(nowNs, lagUs)

                                    // Frame P tidak lagi dibuang di sini (membuat blok artefak). Kalau jaringan
                                    // tak sanggup, lapisan kirim yang meminta IDR baru dan resync bersih.
                                    val packet = buildAccessUnit(annexB, isKey)
                                    val ptsNs = (anchorNs - broadcaster.clockOriginNs) + normalizedPtsUs * 1000L
                                    var pts = ptsNs.coerceAtLeast(0L) * 9L / 100_000L
                                    if (pts <= lastVideoPts90k) pts = lastVideoPts90k + 1L
                                    lastVideoPts90k = pts
                                    broadcaster.publishVideo(packet, pts, isKey)
                                }
                            }
                        } finally {
                            try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            } catch (t: Throwable) {
                if (!running) break
                val codecError = t as? MediaCodec.CodecException
                if (codecError != null && (codecError.isRecoverable || codecError.isTransient)) {
                    if (recoverCodec()) continue
                }
                onFailure(t)
                break
            }
        }
    }

    private fun requestSyncFrame(c: MediaCodec, nowNs: Long, minGapNs: Long = 700_000_000L): Boolean {
        if (nowNs - lastSyncRequestNs <= minGapNs) return false
        try {
            c.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Throwable) {
        }
        lastSyncRequestNs = nowNs
        return true
    }

    /** Batas bawah bitrate mengikuti resolusi (bukan angka tetap untuk semua HP). */
    private fun minBitrate(): Int = when {
        width >= 1920 -> 1_200_000
        width >= 1280 -> 800_000
        width >= 960 -> 600_000
        else -> 450_000
    }

    /**
     * Dipanggil MirrorService dari listener suhu HP. Panas => plafon bitrate turun (dan fps bisa turun);
     * setelah dingin plafon naik kembali.
     */
    fun setThermalStatus(status: Int) {
        thermalStatus = status
        thermalFactor = when {
            status >= 4 -> 0.45
            status == 3 -> 0.6
            status == 2 -> 0.8
            else -> 1.0
        }
    }

    private fun applyBitrate(c: MediaCodec, next: Int, nowNs: Long) {
        try {
            c.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next)
            })
            currentBitrate = next
            broadcaster.setTargetVideoBitrate(next)
        } catch (_: Throwable) {
        }
        lastBitrateChangeNs = nowNs
    }

    /**
     * Bitrate naik-turun real-time dari tiga sumber: (1) kecepatan kirim yang benar-benar terukur ke STB,
     * (2) keterlambatan encoder (CPU/GPU HP sedang dipakai aplikasi berat), (3) suhu HP. Turun cepat,
     * naik pelan (probing 15% tiap ≥4 dtk saat sehat) sehingga tidak berosilasi.
     */
    private fun maybeAdaptBitrate(c: MediaCodec, lagUs: Long, nowNs: Long, networkPressure: Boolean) {
        val floor = minBitrate()
        val ceiling = maxOf(floor, (baseBitrate * thermalFactor).toInt())
        val goodput = broadcaster.measuredGoodputBps()
        val linkLimit = if (goodput > 0L) {
            (goodput * 8L / 10L - AUDIO_BPS).coerceIn(floor.toLong(), Int.MAX_VALUE.toLong()).toInt()
        } else {
            Int.MAX_VALUE
        }
        val tooLate = lateStreak >= 3 || lagUs >= 300_000L || networkPressure
        val sinceChange = nowNs - lastBitrateChangeNs

        var target = currentBitrate
        if (currentBitrate > ceiling) target = ceiling
        if (currentBitrate > linkLimit) target = minOf(target, linkLimit)
        if (tooLate) target = minOf(target, (currentBitrate * 0.82).toInt())
        target = target.coerceAtLeast(floor)

        if (target < currentBitrate) {
            if (sinceChange > 1_000_000_000L) {
                applyBitrate(c, target, nowNs)
                healthySinceNs = Long.MIN_VALUE
            }
            return
        }

        if (currentBitrate < ceiling &&
            healthySinceNs != Long.MIN_VALUE &&
            nowNs - healthySinceNs > 4_000_000_000L &&
            sinceChange > 4_000_000_000L
        ) {
            var up = minOf(ceiling, (currentBitrate * 1.15).toInt())
            if (goodput > 0L) up = minOf(up, linkLimit)
            if (up > currentBitrate) {
                applyBitrate(c, up, nowNs)
                healthySinceNs = nowNs
            }
        }
    }

    /**
     * Frame rate naik-turun otomatis: turun bila encoder terus tertinggal / jaringan tetap tertekan / HP
     * terlalu panas (≥3 dtk berturut-turut, jeda ≥10 dtk antar perubahan); naik lagi bila sehat ≥30 dtk.
     */
    private fun evaluateFps(nowNs: Long, lagUs: Long) {
        if (fpsSteps.size <= 1 || fpsChangeRequest >= 0) return
        val pressureActive = nowNs < pressureHoldUntilNs
        val overloaded = lagUs >= 400_000L || lateStreak >= 6 || thermalStatus >= 3 ||
            (pressureActive && currentBitrate * 10 <= baseBitrate * 6)

        if (overloaded) {
            steadySinceNs = Long.MIN_VALUE
            if (overloadSinceNs == Long.MIN_VALUE) overloadSinceNs = nowNs
            if (nowNs - overloadSinceNs >= 3_000_000_000L &&
                nowNs - lastFpsChangeNs >= 10_000_000_000L &&
                fpsIndex < fpsSteps.size - 1
            ) {
                fpsChangeRequest = fpsIndex + 1
            }
            return
        }

        overloadSinceNs = Long.MIN_VALUE
        val healthy = lagUs < 90_000L && lateStreak == 0 && thermalStatus < 2 &&
            !pressureActive && currentBitrate * 100 >= baseBitrate * 95
        if (!healthy) {
            steadySinceNs = Long.MIN_VALUE
            return
        }
        if (steadySinceNs == Long.MIN_VALUE) steadySinceNs = nowNs
        if (fpsIndex > 0 &&
            nowNs - steadySinceNs >= 30_000_000_000L &&
            nowNs - lastFpsChangeNs >= 30_000_000_000L
        ) {
            fpsChangeRequest = fpsIndex - 1
        }
    }

    private fun applyFpsStep(index: Int) {
        val safeIndex = index.coerceIn(0, fpsSteps.size - 1)
        val target = fpsSteps[safeIndex]
        if (target == activeFps) return
        val previous = activeFps
        activeFps = target
        fpsIndex = safeIndex
        lastFpsChangeNs = System.nanoTime()
        overloadSinceNs = Long.MIN_VALUE
        steadySinceNs = Long.MIN_VALUE
        if (!recoverCodec()) {
            activeFps = previous
            throw IllegalStateException("Gagal mengganti frame rate ke $target fps")
        }
        publishQuality(true)
    }

    private fun publishQuality(changed: Boolean) {
        broadcaster.updateLiveQuality("${width}×${height} • ${activeFps} fps")
        if (changed) {
            try {
                onQuality?.invoke("Frame rate disesuaikan otomatis: ${activeFps} fps (mengikuti beban HP/jaringan).")
            } catch (_: Throwable) {
            }
        }
    }

    /** Replace only codec/surface. MediaProjection and its VirtualDisplay remain the same capture session. */
    @Synchronized
    private fun recoverCodec(): Boolean {
        if (!running) return false
        return try {
            broadcaster.resetLiveForCodecRecovery()

            val oldCodec = codec
            val oldSurface = inputSurface
            codec = null
            inputSurface = null
            try { oldSurface?.release() } catch (_: Exception) {}
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}

            val replacement = createConfiguredCodec()
            val replacementSurface = replacement.createInputSurface()
            replacement.start()
            codec = replacement
            inputSurface = replacementSurface
            virtualDisplay?.setSurface(replacementSurface)

            sps = null
            pps = null
            resetClock()
            lastSyncRequestNs = 0L
            lastClientDrops = broadcaster.droppedClientPackets
            recoveries++
            requestSyncFrame(replacement, System.nanoTime())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun setConfig(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        val originalPosition = buffer.position()
        buffer.get(bytes)
        try { buffer.position(originalPosition) } catch (_: Exception) {}
        if (bytes.isEmpty()) return

        if (isAnnexB(bytes)) {
            for (nal in splitAnnexB(bytes)) {
                if (nal.isEmpty()) continue
                when (nal[0].toInt() and 0x1F) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
            }
            return
        }

        when (bytes[0].toInt() and 0x1F) {
            7 -> { sps = bytes; return }
            8 -> { pps = bytes; return }
        }

        val pair = extractConfig(bytes)
        if (pair.first != null) sps = pair.first
        if (pair.second != null) pps = pair.second
    }

    private fun extractConfig(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        if (isAnnexB(data)) {
            val nals = splitAnnexB(data)
            return nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 } to
                nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        }
        if (data.size >= 7 && data[0].toInt() == 1) {
            var p = 5
            val spsCount = data[p++].toInt() and 0x1F
            var spsValue: ByteArray? = null
            repeat(spsCount) {
                if (p + 2 <= data.size) {
                    val len = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                    p += 2
                    if (p + len <= data.size) {
                        if (spsValue == null) spsValue = data.copyOfRange(p, p + len)
                        p += len
                    }
                }
            }
            if (p < data.size) {
                val ppsCount = data[p++].toInt() and 0xFF
                var ppsValue: ByteArray? = null
                repeat(ppsCount) {
                    if (p + 2 <= data.size) {
                        val len = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                        p += 2
                        if (p + len <= data.size) {
                            if (ppsValue == null) ppsValue = data.copyOfRange(p, p + len)
                            p += len
                        }
                    }
                }
                return spsValue to ppsValue
            }
        }
        return null to null
    }

    /**
     * Susun satu access unit sesuai kebiasaan ffmpeg (tar v6): AUD di depan, lalu SPS/PPS
     * sebelum setiap IDR. AUD membantu decoder STB murahan menemukan batas frame.
     */
    private fun buildAccessUnit(input: ByteArray, isKey: Boolean): ByteArray {
        val annexB = stripLeadingAud(input)
        val s = sps
        val p = pps
        val addConfig = isKey && s != null && p != null && firstNalType(annexB) != 7
        val configSize = if (addConfig && s != null && p != null) 8 + s.size + p.size else 0
        val out = ByteArray(aud.size + configSize + annexB.size)
        var pos = 0
        System.arraycopy(aud, 0, out, pos, aud.size)
        pos += aud.size
        if (configSize > 0 && s != null && p != null) {
            out[pos++] = 0; out[pos++] = 0; out[pos++] = 0; out[pos++] = 1
            System.arraycopy(s, 0, out, pos, s.size)
            pos += s.size
            out[pos++] = 0; out[pos++] = 0; out[pos++] = 0; out[pos++] = 1
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        System.arraycopy(annexB, 0, out, pos, annexB.size)
        return out
    }

    /** Buang AUD bawaan encoder (bila ada) supaya tidak ganda dengan AUD buatan kita. */
    private fun stripLeadingAud(data: ByteArray): ByteArray {
        val start = findStartCode(data, 0)
        if (start < 0) return data
        val codeLen = if (start + 2 < data.size && data[start + 2].toInt() == 1) 3 else 4
        val nalStart = start + codeLen
        if (nalStart >= data.size || (data[nalStart].toInt() and 0x1F) != 9) return data
        val next = findStartCode(data, nalStart)
        return if (next >= 0) data.copyOfRange(next, data.size) else data
    }

    private fun firstNalType(data: ByteArray): Int {
        val start = findStartCode(data, 0)
        if (start < 0) return -1
        val codeLen = if (start + 2 < data.size && data[start + 2].toInt() == 1) 3 else 4
        val index = start + codeLen
        return if (index < data.size) data[index].toInt() and 0x1F else -1
    }

    private fun normalizeH264(data: ByteArray): ByteArray {
        if (isAnnexB(data)) return data

        var p = 0
        var total = 0
        var count = 0
        while (p + 4 <= data.size) {
            val len = ((data[p].toInt() and 0xFF) shl 24) or
                ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or
                (data[p + 3].toInt() and 0xFF)
            p += 4
            if (len <= 0 || p + len > data.size) break
            total += 4 + len
            count++
            p += len
        }
        if (count == 0 || total <= 0) return data

        val out = ByteArray(total)
        p = 0
        var w = 0
        repeat(count) {
            val len = ((data[p].toInt() and 0xFF) shl 24) or
                ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or
                (data[p + 3].toInt() and 0xFF)
            p += 4
            out[w++] = 0; out[w++] = 0; out[w++] = 0; out[w++] = 1
            System.arraycopy(data, p, out, w, len)
            w += len
            p += len
        }
        return out
    }

    private fun isAnnexB(data: ByteArray): Boolean =
        data.size >= 4 &&
            ((data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1) ||
                (data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1))

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var start = findStartCode(data, 0)
        while (start >= 0) {
            val codeLen = if (start + 2 < data.size && data[start + 2].toInt() == 1) 3 else 4
            val nalStart = start + codeLen
            val next = findStartCode(data, nalStart)
            val end = if (next >= 0) next else data.size
            if (nalStart < end) result += data.copyOfRange(nalStart, end)
            start = next
        }
        return result
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                (data[i + 2].toInt() == 1 ||
                    (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))) {
                return i
            }
            i++
        }
        return -1
    }

    /** Hanya memeriksa beberapa NAL pertama (AUD/SEI/SPS/PPS lalu slice) — tidak memindai seluruh frame. */
    private fun containsIdrFast(data: ByteArray): Boolean {
        var start = findStartCode(data, 0)
        var guard = 0
        while (start >= 0 && guard++ < 8) {
            val codeLen = if (start + 2 < data.size && data[start + 2].toInt() == 1) 3 else 4
            val nalStart = start + codeLen
            if (nalStart >= data.size) return false
            when (data[nalStart].toInt() and 0x1F) {
                5 -> return true
                1 -> return false
            }
            start = findStartCode(data, nalStart)
        }
        return false
    }
}
