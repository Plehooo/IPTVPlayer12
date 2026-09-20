package com.a01mirror.dlna

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
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
        val rates = intArrayOf(48000, 44100)
        var ar: AudioRecord? = null
        var selectedRate = 48000
        for (rate in rates) {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) continue

                val config = AudioPlaybackCaptureConfigurationCompat.build(projection)
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                ar = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes((minBuffer * 3 / 2).coerceAtLeast(minBuffer))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                selectedRate = rate
                break
            } catch (_: Throwable) {
                try { ar?.release() } catch (_: Exception) {}
                ar = null
            }
        }

        if (ar == null) {
            onFailure(IllegalStateException("Audio internal tidak tersedia di perangkat ini."))
            return
        }

        sampleRate = selectedRate
        channels = 2
        record = ar
        lame = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutChannels(channels)
            .setOutBitrate(96)
            .setOutSampleRate(sampleRate)
            .setQuality(7)
            .build()

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
        // Satu frame MP3 (1152 sampel ~ 24 ms pada 48 kHz) per pembacaan, agar audio tidak menambah latency.
        val framesPerRead = 1152
        val pcm = ShortArray(framesPerRead * channels)
        val mp3 = ByteArray(7200 + pcm.size * 2)
        var samplesPerChannel = 0L

        try {
            // Audio tidak boleh tersendat walau game/aplikasi berat sedang memakai CPU.
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            rec.startRecording()
            // Jangkar waktu bersama dengan video (jam broadcaster) supaya lip-sync tidak bergeser.
            val anchor90k = (System.nanoTime() - broadcaster.clockOriginNs).coerceAtLeast(0L) * 9L / 100_000L
            while (running) {
                val shorts = rec.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (shorts <= 0) continue
                val perChannel = shorts / channels
                val encoded = enc.encodeBufferInterLeaved(pcm, perChannel, mp3)
                if (encoded > 0) {
                    val bytes = mp3.copyOf(encoded)
                    val pts = anchor90k + samplesPerChannel * 90000L / sampleRate
                    broadcaster.publishAudio(bytes, pts)
                }
                samplesPerChannel += perChannel
            }
            val flushed = enc.flush(mp3)
            if (flushed > 0) {
                broadcaster.publishAudio(mp3.copyOf(flushed), anchor90k + samplesPerChannel * 90000L / sampleRate)
            }
        } catch (t: Throwable) {
            if (running) onFailure(t)
        }
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
