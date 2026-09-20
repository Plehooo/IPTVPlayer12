package com.a01mirror.dlna

import java.io.ByteArrayOutputStream

/**
 * Compact MPEG-TS muxer for the Advance A01 renderer.
 * Video: H.264 (PID 0x0100), Audio: MPEG-1 Layer III (PID 0x0101).
 *
 * The hot path allocates exactly one ByteArray for the complete TS access unit.
 * No ByteArray is allocated per 188-byte transport packet.
 */
class MpegTsMuxer {
    companion object {
        private const val TS_SIZE = 188
        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100
        private const val AUDIO_PID = 0x0101
        private const val PROGRAM_NUMBER = 1
        private const val PES_HEADER_SIZE = 14
    }

    private val continuity = HashMap<Int, Int>()

    fun patPacket(): ByteArray = buildPat()
    fun pmtPacket(): ByteArray = buildPmt()

    fun videoPes(annexBAccessUnit: ByteArray, pts90k: Long, keyFrame: Boolean): ByteArray =
        pesToTs(VIDEO_PID, 0xE0, annexBAccessUnit, pts90k, includePcr = true, randomAccess = keyFrame)

    fun audioPes(mp3: ByteArray, pts90k: Long): ByteArray =
        pesToTs(AUDIO_PID, 0xC0, mp3, pts90k, includePcr = false, randomAccess = false)

    private fun buildPat(): ByteArray {
        val section = ByteArrayOutputStream(16)
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
        writeInt(section, crc32(section.toByteArray()))
        return psiToTs(PAT_PID, section.toByteArray())
    }

    private fun buildPmt(): ByteArray {
        val section = ByteArrayOutputStream(32)
        section.write(0x02)
        section.write(0xB0)
        section.write(0x17)
        section.write(0x00)
        section.write(PROGRAM_NUMBER)
        section.write(0xC1)
        section.write(0x00)
        section.write(0x00)
        section.write(0xE0 or (VIDEO_PID shr 8))
        section.write(VIDEO_PID and 0xFF)
        section.write(0xF0)
        section.write(0x00)
        section.write(0x1B) // H.264 AVC
        section.write(0xE0 or (VIDEO_PID shr 8))
        section.write(VIDEO_PID and 0xFF)
        section.write(0xF0)
        section.write(0x00)
        section.write(0x03) // MPEG-1 Layer III audio
        section.write(0xE0 or (AUDIO_PID shr 8))
        section.write(AUDIO_PID and 0xFF)
        section.write(0xF0)
        section.write(0x00)
        writeInt(section, crc32(section.toByteArray()))
        return psiToTs(PMT_PID, section.toByteArray())
    }

    private fun psiToTs(pid: Int, section: ByteArray): ByteArray {
        val packet = ByteArray(TS_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (((pid shr 8) and 0x1F) or 0x40).toByte()
        packet[2] = (pid and 0xFF).toByte()
        val cc = continuity[pid] ?: 0
        continuity[pid] = (cc + 1) and 0x0F
        packet[3] = (0x10 or cc).toByte()
        packet[4] = 0
        val n = minOf(section.size, TS_SIZE - 5)
        System.arraycopy(section, 0, packet, 5, n)
        return packet
    }

    private fun pesToTs(
        pid: Int,
        streamId: Int,
        payload: ByteArray,
        pts90k: Long,
        includePcr: Boolean,
        randomAccess: Boolean
    ): ByteArray {
        val safePts = pts90k.coerceAtLeast(0L)
        val pesBytes = PES_HEADER_SIZE + payload.size
        val packetCount = countPackets(pesBytes, includePcr)
        val out = ByteArray(packetCount * TS_SIZE) { 0xFF.toByte() }

        var pesPos = 0
        for (packetIndex in 0 until packetCount) {
            val packetOff = packetIndex * TS_SIZE
            out[packetOff] = 0x47
            out[packetOff + 1] = (((if (packetIndex == 0) 0x40 else 0x00) or ((pid shr 8) and 0x1F))).toByte()
            out[packetOff + 2] = (pid and 0xFF).toByte()

            val cc = continuity[pid] ?: 0
            continuity[pid] = (cc + 1) and 0x0F

            val remaining = pesBytes - pesPos
            val isFirst = packetIndex == 0
            val hasPcr = isFirst && includePcr
            val adaptationBytes: Int

            if (hasPcr && remaining >= 176) {
                adaptationBytes = 8
            } else if (hasPcr) {
                adaptationBytes = maxOf(8, 184 - remaining)
            } else if (remaining >= 184) {
                adaptationBytes = 0
            } else {
                // A TS adaptation field needs at least a flags byte. With 183 bytes
                // remaining, 182 bytes fit beside a two-byte adaptation field, leaving
                // one byte for the next TS packet. This keeps every packet standards-shaped.
                adaptationBytes = maxOf(2, 184 - remaining)
            }

            if (adaptationBytes > 0) {
                out[packetOff + 3] = (0x30 or cc).toByte()
                out[packetOff + 4] = (adaptationBytes - 1).toByte()
                var flags = 0
                if (hasPcr) flags = flags or 0x10
                if (isFirst && randomAccess) flags = flags or 0x40
                out[packetOff + 5] = flags.toByte()
                var cursor = packetOff + 6
                if (hasPcr) {
                    writePcr(out, cursor, safePts)
                    cursor += 6
                }
                val stuffingEnd = packetOff + 4 + adaptationBytes
                while (cursor < stuffingEnd) {
                    out[cursor++] = 0xFF.toByte()
                }
            } else {
                out[packetOff + 3] = (0x10 or cc).toByte()
            }

            var dataOff = packetOff + if (adaptationBytes > 0) 4 + adaptationBytes else 4
            var capacity = TS_SIZE - (dataOff - packetOff)

            if (isFirst) {
                writePesHeader(out, dataOff, streamId, payload.size, safePts)
                dataOff += PES_HEADER_SIZE
                capacity -= PES_HEADER_SIZE
                pesPos += PES_HEADER_SIZE
            }

            val payloadIndex = pesPos - PES_HEADER_SIZE
            val payloadRemaining = payload.size - payloadIndex
            val take = minOf(capacity, payloadRemaining)
            if (take > 0) {
                System.arraycopy(payload, payloadIndex, out, dataOff, take)
                pesPos += take
            }
        }
        return out
    }

    private fun countPackets(pesBytes: Int, includePcr: Boolean): Int {
        var remaining = pesBytes
        var count = 0
        while (remaining > 0) {
            val first = count == 0
            val hasPcr = first && includePcr
            if (hasPcr) {
                val take = if (remaining >= 176) 176 else remaining
                remaining -= take
            } else if (remaining >= 184) {
                remaining -= 184
            } else {
                val adaptation = maxOf(2, 184 - remaining)
                val take = TS_SIZE - 4 - adaptation
                remaining -= minOf(take, remaining)
            }
            count++
        }
        return maxOf(count, 1)
    }

    private fun writePesHeader(out: ByteArray, off: Int, streamId: Int, payloadSize: Int, pts90k: Long) {
        out[off] = 0x00
        out[off + 1] = 0x00
        out[off + 2] = 0x01
        out[off + 3] = streamId.toByte()
        val lengthValue = if (streamId in 0xE0..0xEF) 0 else (3 + 5 + payloadSize).coerceAtMost(0xFFFF)
        out[off + 4] = (lengthValue shr 8).toByte()
        out[off + 5] = lengthValue.toByte()
        out[off + 6] = 0x80.toByte()
        out[off + 7] = (0x80 or if (streamId in 0xE0..0xEF) 0x04 else 0x00).toByte()
        out[off + 8] = 0x05
        writePts(out, off + 9, pts90k)
    }

    private fun writePts(out: ByteArray, off: Int, pts: Long) {
        val v = pts and ((1L shl 33) - 1)
        out[off] = (0x21 or (((v shr 30) and 0x07).toInt() shl 1)).toByte()
        out[off + 1] = (v shr 22).toByte()
        out[off + 2] = ((((v shr 15) and 0x7F).toInt() shl 1) or 0x01).toByte()
        out[off + 3] = (v shr 7).toByte()
        out[off + 4] = (((v and 0x7F).toInt() shl 1) or 0x01).toByte()
    }

    private fun writePcr(packet: ByteArray, off: Int, pts90k: Long) {
        val base = pts90k and ((1L shl 33) - 1)
        packet[off] = (base shr 25).toByte()
        packet[off + 1] = (base shr 17).toByte()
        packet[off + 2] = (base shr 9).toByte()
        packet[off + 3] = (base shr 1).toByte()
        packet[off + 4] = (((base and 0x01) shl 7) or 0x7E).toByte()
        packet[off + 5] = 0
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
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }
}
