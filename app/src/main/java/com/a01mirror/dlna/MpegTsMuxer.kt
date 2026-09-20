package com.a01mirror.dlna

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Small ISO/IEC 13818-1 MPEG-TS muxer tailored for the A01:
 * H.264 video (PID 0x0100) + MPEG-1 Layer III audio (PID 0x0101).
 */
class MpegTsMuxer {
    companion object {
        private const val TS_SIZE = 188
        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100
        private const val AUDIO_PID = 0x0101
        private const val PROGRAM_NUMBER = 1
    }

    private val continuity = HashMap<Int, Int>()

    fun patPacket(): ByteArray = buildPat()
    fun pmtPacket(): ByteArray = buildPmt()

    fun videoPes(annexBAccessUnit: ByteArray, pts90k: Long, keyFrame: Boolean): ByteArray =
        pesToTs(VIDEO_PID, 0xE0, annexBAccessUnit, pts90k, includePcr = true)

    fun audioPes(mp3: ByteArray, pts90k: Long): ByteArray =
        pesToTs(AUDIO_PID, 0xC0, mp3, pts90k, includePcr = false)

    private fun buildPat(): ByteArray {
        val section = ByteArrayOutputStream()
        section.write(0x00)
        section.write(0xB0)
        section.write(0x0D)
        section.write(0x00)
        section.write(PROGRAM_NUMBER)
        section.write(0xC1)
        section.write(0x00)
        section.write(0x00)
        section.write(0x00)
        section.write(PROGRAM_NUMBER)
        section.write(0xE0 or (PMT_PID shr 8))
        section.write(PMT_PID and 0xFF)
        val crc = crc32(section.toByteArray())
        writeInt(section, crc)
        return psiToTs(PAT_PID, section.toByteArray())
    }

    private fun buildPmt(): ByteArray {
        val section = ByteArrayOutputStream()
        section.write(0x02) // table_id
        section.write(0xB0)
        section.write(0x17) // section_length = 23
        section.write(0x00)
        section.write(PROGRAM_NUMBER)
        section.write(0xC1)
        section.write(0x00)
        section.write(0x00)
        section.write(0xE0 or (VIDEO_PID shr 8)) // PCR PID
        section.write(VIDEO_PID and 0xFF)
        section.write(0xF0)
        section.write(0x00) // program info length
        // H.264
        section.write(0x1B)
        section.write(0xE0 or (VIDEO_PID shr 8))
        section.write(VIDEO_PID and 0xFF)
        section.write(0xF0)
        section.write(0x00)
        // MPEG-1 Layer III
        section.write(0x03)
        section.write(0xE0 or (AUDIO_PID shr 8))
        section.write(AUDIO_PID and 0xFF)
        section.write(0xF0)
        section.write(0x00)
        val crc = crc32(section.toByteArray())
        writeInt(section, crc)
        return psiToTs(PMT_PID, section.toByteArray())
    }

    private fun psiToTs(pid: Int, section: ByteArray): ByteArray {
        val packet = ByteArray(TS_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = ((pid shr 8) and 0x1F or 0x40).toByte()
        packet[2] = (pid and 0xFF).toByte()
        val cc = continuity[pid] ?: 0
        continuity[pid] = (cc + 1) and 0x0F
        packet[3] = (0x10 or cc).toByte()
        packet[4] = 0x00 // pointer_field
        val n = min(section.size, TS_SIZE - 5)
        System.arraycopy(section, 0, packet, 5, n)
        return packet
    }

    private fun pesToTs(
        pid: Int,
        streamId: Int,
        payload: ByteArray,
        pts90k: Long,
        includePcr: Boolean
    ): ByteArray {
        val pes = ByteArrayOutputStream(payload.size + 32)
        pes.write(0x00); pes.write(0x00); pes.write(0x01)
        pes.write(streamId)
        val pesLength = 3 + 5 + payload.size
        val lengthValue = if (streamId in 0xE0..0xEF) 0 else pesLength.coerceAtMost(0xFFFF)
        pes.write((lengthValue shr 8) and 0xFF)
        pes.write(lengthValue and 0xFF)
        pes.write(0x80) // '10'
        pes.write(0x80) // PTS only
        pes.write(0x05)
        writePts(pes, pts90k)
        pes.write(payload)

        val bytes = pes.toByteArray()
        val out = ByteArrayOutputStream(((bytes.size + 183) / 184) * TS_SIZE + 32)
        var pos = 0
        var first = true
        while (pos < bytes.size) {
            val packet = ByteArray(TS_SIZE) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (((if (first) 0x40 else 0x00) or ((pid shr 8) and 0x1F))).toByte()
            packet[2] = (pid and 0xFF).toByte()
            val cc = continuity[pid] ?: 0
            continuity[pid] = (cc + 1) and 0x0F

            val remaining = bytes.size - pos
            val needPcr = first && includePcr
            val adaptationBytes = if (needPcr) 8 else if (remaining < 184) TS_SIZE - 4 - remaining else 0

            if (adaptationBytes > 0) {
                packet[3] = (0x30 or cc).toByte()
                packet[4] = (adaptationBytes - 1).toByte()
                if (needPcr) {
                    packet[5] = 0x10
                    writePcr(packet, 6, pts90k)
                    for (i in 12 until 4 + adaptationBytes) packet[i] = 0xFF.toByte()
                } else {
                    packet[5] = 0x00
                    for (i in 6 until 4 + adaptationBytes) packet[i] = 0xFF.toByte()
                }
            } else {
                packet[3] = (0x10 or cc).toByte()
            }

            val payloadOffset = if (adaptationBytes > 0) 4 + adaptationBytes else 4
            val capacity = TS_SIZE - payloadOffset
            val take = min(capacity, remaining)
            System.arraycopy(bytes, pos, packet, payloadOffset, take)
            pos += take
            out.write(packet)
            first = false
        }
        return out.toByteArray()
    }

    private fun writePts(out: ByteArrayOutputStream, pts: Long) {
        val v = pts and ((1L shl 33) - 1)
        out.write(0x21 or (((v shr 30) and 0x07).toInt() shl 1))
        out.write((v shr 22).toInt() and 0xFF)
        out.write((((v shr 15) and 0x7F).toInt() shl 1) or 0x01)
        out.write((v shr 7).toInt() and 0xFF)
        out.write((((v and 0x7F).toInt() shl 1) or 0x01))
    }

    private fun writePcr(packet: ByteArray, off: Int, pts90k: Long) {
        val base = pts90k.coerceAtLeast(0) and ((1L shl 33) - 1)
        val ext = 0
        packet[off] = (base shr 25).toByte()
        packet[off + 1] = (base shr 17).toByte()
        packet[off + 2] = (base shr 9).toByte()
        packet[off + 3] = (base shr 1).toByte()
        packet[off + 4] = (((base and 0x01) shl 7) or 0x7E or (ext shr 8).toLong()).toByte()
        packet[off + 5] = ext.toByte()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if ((crc and 0x80000000.toInt()) != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else crc shl 1
            }
        }
        return crc
    }
}
