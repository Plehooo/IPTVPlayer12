package com.a01mirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsMuxerTest {
    @Test
    fun patAndPmtAreValidTsPackets() {
        val muxer = MpegTsMuxer()
        val pat = muxer.patPacket()
        val pmt = muxer.pmtPacket()

        assertEquals(188, pat.size)
        assertEquals(188, pmt.size)
        assertEquals(0x47.toByte(), pat[0])
        assertEquals(0x47.toByte(), pmt[0])
        assertEquals(0, pat[1].toInt() and 0x1F)
        assertEquals(0x10, pat[3].toInt() and 0x30)
        assertEquals(0x10, pmt[3].toInt() and 0x30)
    }

    @Test
    fun videoPesIs188ByteAligned() {
        val muxer = MpegTsMuxer()
        val accessUnit = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, 4)
        val data = muxer.videoPes(accessUnit, 90000L, true)

        assertTrue(data.isNotEmpty())
        assertEquals(0, data.size % 188)
        for (i in data.indices step 188) {
            assertEquals(0x47.toByte(), data[i])
        }
    }

    @Test
    fun videoPesHeaderFlagsFollowSpec() {
        val muxer = MpegTsMuxer()
        val data = muxer.videoPes(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, 4), 180000L, true)

        var index = -1
        for (i in 0 until 180) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte() && data[i + 3] == 0xE0.toByte()
            ) {
                index = i
                break
            }
        }
        assertTrue(index > 0)
        // data_alignment_indicator di byte 6, hanya flag PTS di byte 7, panjang header 5.
        assertEquals(0x84.toByte(), data[index + 6])
        assertEquals(0x80.toByte(), data[index + 7])
        assertEquals(5.toByte(), data[index + 8])
    }

    @Test
    fun audioPesIs188ByteAligned() {
        val muxer = MpegTsMuxer()
        val mp3 = ByteArray(500) { 0x55.toByte() }
        val data = muxer.audioPes(mp3, 90000L)

        assertTrue(data.isNotEmpty())
        assertEquals(0, data.size % 188)
        for (i in data.indices step 188) {
            assertEquals(0x47.toByte(), data[i])
        }
    }
}
