package com.a01mirror.dlna

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import java.nio.ByteBuffer

class H264Encoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val densityDpi: Int,
    private val broadcaster: TsBroadcaster,
    private val onFailure: (Throwable) -> Unit
) {
    private var codec: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var projectionCallback: MediaProjection.Callback? = null

    fun start() {
        check(!running) { "encoder already running" }
        codec = createConfiguredCodec()
        inputSurface = codec!!.createInputSurface()
        codec!!.start()

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
        thread = Thread { drainLoop() }.also { it.name = "A01-H264"; it.start() }
    }

    private fun buildFormat(strict: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Tar v6 memakai 2.5-3 Mbps untuk 720p; dongle Wi-Fi STB murahan tidak kuat bitrate besar.
            setInteger(MediaFormat.KEY_BIT_RATE, if (width >= 1920) 6_000_000 else 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Layar statis tidak menghasilkan frame baru; ulangi frame terakhir agar STB tidak kehabisan data.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
            if (strict) {
                // H.264 Main (ffmpeg -profile:v main di tar v6) + CBR untuk aliran live.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, avcLevel())
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }

    /** Level terendah yang cukup untuk resolusi/fps (720p30 = 3.1 seperti tar v6). */
    private fun avcLevel(): Int {
        val mbs = ((width + 15) / 16) * ((height + 15) / 16)
        val rate = mbs * fps
        return when {
            mbs <= 3600 && rate <= 108_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            mbs <= 5120 && rate <= 216_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32
            mbs <= 8192 && rate <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
        }
    }

    /** Coba profil Main + CBR dulu; kalau encoder HP menolak, pakai konfigurasi bawaan. */
    private fun createConfiguredCodec(): MediaCodec {
        var c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(buildFormat(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            try { c.release() } catch (_: Exception) {}
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(buildFormat(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return c
    }

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

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val c = codec ?: break
                when (val index = c.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = c.outputFormat
                        outFormat.getByteBuffer("csd-0")?.let(::setConfig)
                        outFormat.getByteBuffer("csd-1")?.let(::setConfig)
                    }
                    else -> if (index >= 0) {
                        val buffer = c.getOutputBuffer(index)
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
                                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || containsIdr(annexB)
                                val packet = if (isKey) prefixConfig(annexB) else annexB
                                val pts = (info.presentationTimeUs.coerceAtLeast(0L) * 90L / 1000L)
                                broadcaster.publishVideo(packet, pts, isKey)
                            }
                        }
                        c.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            } catch (t: Throwable) {
                if (running) onFailure(t)
                break
            }
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
            return nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 } to nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 }
        }
        // AVCDecoderConfigurationRecord (AVCC).
        if (data.size >= 7 && data[0].toInt() == 1) {
            var p = 5
            val spsCount = data[p++].toInt() and 0x1F
            var spsValue: ByteArray? = null
            repeat(spsCount) {
                if (p + 2 <= data.size) {
                    val len = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                    p += 2
                    if (p + len <= data.size) {
                        val value = data.copyOfRange(p, p + len)
                        if (spsValue == null) spsValue = value
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
                            val value = data.copyOfRange(p, p + len)
                            if (ppsValue == null) ppsValue = value
                            p += len
                        }
                    }
                }
                return spsValue to ppsValue
            }
        }
        return null to null
    }

    private fun prefixConfig(annexB: ByteArray): ByteArray {
        if (sps == null || pps == null) return annexB
        return byteArrayOf(*startCode(), *sps!!, *startCode(), *pps!!, *annexB)
    }

    private fun normalizeH264(data: ByteArray): ByteArray {
        if (isAnnexB(data)) return data
        val out = java.io.ByteArrayOutputStream(data.size + 64)
        var p = 0
        while (p + 4 <= data.size) {
            val len = ((data[p].toInt() and 0xFF) shl 24) or
                ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or
                (data[p + 3].toInt() and 0xFF)
            p += 4
            if (len <= 0 || p + len > data.size) break
            out.write(startCode())
            out.write(data, p, len)
            p += len
        }
        return if (out.size() > 0) out.toByteArray() else data
    }

    private fun isAnnexB(data: ByteArray): Boolean =
        data.size >= 4 && ((data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1) ||
            (data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1))

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = findStartCode(data, 0)
        while (start >= 0) {
            val codeLen = if (start + 3 < data.size && data[start + 2].toInt() == 1) 3 else 4
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
                ((data[i + 2].toInt() == 1) || (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))) return i
            i++
        }
        return -1
    }

    private fun containsIdr(data: ByteArray): Boolean =
        splitAnnexB(data).any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 5 }

    private fun startCode() = byteArrayOf(0, 0, 0, 1)

}
