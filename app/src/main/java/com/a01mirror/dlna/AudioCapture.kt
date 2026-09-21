package com.a01mirror.dlna

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder

class AudioCapture(
    private val projection: MediaProjection,
    private val broadcaster: TsBroadcaster,
    private val onFailure: (Throwable) -> Unit
) {
    private var record: AudioRecord? = null
    private var lame: AndroidLame? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var sampleRate = 48000
    private var channels = 2

    fun start() {
        if (running) return
        if (android.os.Build.VERSION.SDK_INT < 29) return

        // 48 kHz dulu (standar TV/DVB, sama dengan -ar 48000 di tar v6), 44.1 kHz cadangan.
        val rates = intArrayOf(48000, 44100, 32000)
        val channelMasks = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        var ar: AudioRecord? = null
        var selectedRate = 48000
        var selectedChannels = 2
        for (rate in rates) {
            for (mask in channelMasks) {
                try {
                    val minBuffer = AudioRecord.getMinBufferSize(
                        rate,
                        mask,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuffer <= 0) continue

                    val config = AudioPlaybackCaptureConfigurationCompat.build(projection)
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build()

                    ar = AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(minBuffer))
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                    selectedRate = rate
                    selectedChannels = if (mask == AudioFormat.CHANNEL_IN_MONO) 1 else 2
                    break
                } catch (_: Throwable) {
                    try { ar?.release() } catch (_: Exception) {}
                    ar = null
                }
            }
            if (ar != null) break
        }

        if (ar == null) {
            onFailure(IllegalStateException("Audio internal tidak tersedia di perangkat ini."))
            startSilence()
            return
        }

        sampleRate = selectedRate
        channels = selectedChannels
        record = ar
        lame = try {
            LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutChannels(2) // selalu stereo; sumber mono di-upmix di loop()
            .setOutBitrate(128)
            .setOutSampleRate(sampleRate)
            .setQuality(lameQuality())
            .build()
        } catch (t: Throwable) {
            // Library MP3 native tidak bisa dimuat di ABI HP ini: tetap siarkan video + audio senyap.
            try { record?.release() } catch (_: Exception) {}
            record = null
            onFailure(t)
            startSilence()
            return
        }

        running = true
        thread = Thread { loop() }.also { it.name = "A01-Audio"; it.start() }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.interrupt()
        try { thread?.join(1000) } catch (_: InterruptedException) {}
        thread = null
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { lame?.close() } catch (_: Exception) {}
        lame = null
    }

    private fun loop() {
        val rec = record ?: return
        val enc = lame ?: return
        // Dua frame MP3 (2304 sampel/channel) ≈ 48 ms pada 48 kHz.
        // Batch ini menjaga encoder/network tetap irit wake-up tanpa membuat audio menunggu ratusan ms.
        val framesPerRead = 1152
        val pcm = ShortArray(framesPerRead * channels)
        // encodeBufferInterLeaved selalu mengasumsikan PCM stereo; sumber mono digandakan ke L/R
        // (kalau tidak, LAME membaca tiap sampel kedua dan audio terdengar 2x lebih cepat).
        val stereo = if (channels == 1) ShortArray(framesPerRead * 2) else null
        val mp3 = ByteArray(12_288 + pcm.size * 2)
        var samplesPerChannel = 0L
        var lastPts90k = 0L

        try {
            // Audio tidak boleh tersendat walau game/aplikasi berat sedang memakai CPU.
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            rec.startRecording()

            // AudioTimestamp memakai TIMEBASE_MONOTONIC, yaitu jam yang sama dengan System.nanoTime().
            // Dari sini kita hitung posisi frame-0 secara akurat, sehingga PTS audio tidak drift terhadap video.
            val audioTimestamp = AudioTimestamp()
            var audioOriginNs = System.nanoTime()
            if (rec.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                audioTimestamp.framePosition >= 0L
            ) {
                audioOriginNs = audioTimestamp.nanoTime -
                    (audioTimestamp.framePosition * 1_000_000_000L / sampleRate)
            }
            var lastTimestampCheckNs = System.nanoTime()
            var anchor90k = (audioOriginNs - broadcaster.clockOriginNs).coerceAtLeast(0L) * 90_000L / 1_000_000_000L

            while (running) {
                val shorts = rec.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (shorts < 0) throw IllegalStateException("AudioRecord.read gagal ($shorts)")
                if (shorts == 0) {
                    Thread.sleep(3)
                    continue
                }
                val perChannel = shorts / channels
                val input = if (stereo != null) {
                    for (i in 0 until perChannel) {
                        val sample = pcm[i]
                        stereo[2 * i] = sample
                        stereo[2 * i + 1] = sample
                    }
                    stereo
                } else {
                    pcm
                }
                val encoded = enc.encodeBufferInterLeaved(input, perChannel, mp3)

                // Koreksi clock kira-kira 4× per detik. Jangan koreksi setiap frame karena itu bisa
                // membuat PTS loncat-loncat dan terdengar seperti audio patah.
                val nowNs = System.nanoTime()
                if (nowNs - lastTimestampCheckNs >= 250_000_000L &&
                    rec.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                    audioTimestamp.framePosition >= 0L
                ) {
                    val candidateOriginNs = audioTimestamp.nanoTime -
                        (audioTimestamp.framePosition * 1_000_000_000L / sampleRate)
                    val currentOrigin = audioOriginNs
                    val error = candidateOriginNs - currentOrigin
                    // Selisih kecil dikoreksi halus (maks 4 ms per 250 ms); selisih besar (>200 ms, mis. audio
                    // sempat tertahan CPU) langsung disejajarkan supaya audio tidak tertinggal dari video terus-menerus.
                    audioOriginNs = if (error > 200_000_000L || error < -200_000_000L) {
                        candidateOriginNs
                    } else {
                        currentOrigin + (error / 4L).coerceIn(-4_000_000L, 4_000_000L)
                    }
                    anchor90k = (audioOriginNs - broadcaster.clockOriginNs).coerceAtLeast(0L) * 90_000L / 1_000_000_000L
                    lastTimestampCheckNs = nowNs
                }

                if (encoded > 0) {
                    val bytes = mp3.copyOf(encoded)
                    val pts = anchor90k + samplesPerChannel * 90_000L / sampleRate
                    val safePts = maxOf(pts, lastPts90k + if (lastPts90k > 0L) 1L else 0L)
                    broadcaster.publishAudio(bytes, safePts)
                    lastPts90k = safePts
                }
                samplesPerChannel += perChannel
            }
            val flushed = enc.flush(mp3)
            if (flushed > 0) {
                val pts = anchor90k + samplesPerChannel * 90_000L / sampleRate
                broadcaster.publishAudio(mp3.copyOf(flushed), maxOf(pts, lastPts90k + 1L))
            }
        } catch (t: Throwable) {
            if (running) {
                onFailure(t)
                // Jangan biarkan STB menunggu audio yang berhenti (penyebab buffering): lanjut audio senyap.
                silenceLoop(lastPts90k + 2160L)
            }
        }
    }

    private fun startSilence() {
        running = true
        thread = Thread { silenceLoop(0L) }.also { it.name = "A01-AudioSilence"; it.start() }
    }

    /**
     * Cadangan bila audio internal tidak tersedia (izin ditolak, HP tidak mendukung, library MP3 gagal).
     * Mengirim frame MP3 senyap secara real-time supaya STB tidak menunggu audio lalu buffering.
     */
    private fun silenceLoop(minPts90k: Long) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
        val frame = silentFrame(sampleRate)
        val startNs = System.nanoTime()
        val anchor90k = maxOf((startNs - broadcaster.clockOriginNs).coerceAtLeast(0L) * 9L / 100_000L, minPts90k)
        var samples = 0L
        try {
            while (running) {
                broadcaster.publishAudio(frame, anchor90k + samples * 90000L / sampleRate)
                samples += 1152L
                val waitNs = startNs + samples * 1_000_000_000L / sampleRate - System.nanoTime()
                if (waitNs > 0L) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
            }
        } catch (_: InterruptedException) {
        }
    }

    /** HP lemah (inti sedikit / heap kecil) memakai preset LAME yang lebih cepat agar encoder MP3 ringan. */
    private fun lameQuality(): Int {
        val weak = Runtime.getRuntime().availableProcessors() < 6 ||
            Runtime.getRuntime().maxMemory() < 200L * 1024L * 1024L
        return if (weak) 7 else 5
    }

    /** Satu frame MPEG-1 Layer III senyap; header sample-rate harus cocok dengan frame sebenarnya. */
    private fun silentFrame(rate: Int): ByteArray {
        // Tabel MPEG-1: 00 = 44,1 kHz, 01 = 48 kHz, 10 = 32 kHz (diverifikasi dengan ffprobe).
        val rateIndex = when (rate) {
            44100 -> 0
            48000 -> 1
            else -> 2
        }
        val bitrateIndex = 9 // 128 kbps pada MPEG-1 Layer III.
        val frame = ByteArray(144 * 128000 / rate)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = ((bitrateIndex shl 4) or (rateIndex shl 2)).toByte()
        return frame
    }
}

private object AudioPlaybackCaptureConfigurationCompat {
    fun build(projection: MediaProjection): android.media.AudioPlaybackCaptureConfiguration =
        android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
}
