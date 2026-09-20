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
