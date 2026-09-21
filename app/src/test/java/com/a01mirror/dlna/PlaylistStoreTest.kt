package com.a01mirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistStoreTest {
    @Test
    fun titleKeepsCommasInsideAttributes() {
        val text = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-name=\"RCTI, HD\" group-title=\"Lokal\",Keliling Desa\n" +
            "http://example.com/live/1.ts\n"
        val items = PlaylistStore.parse(text)
        assertEquals(1, items.size)
        assertEquals("Keliling Desa", items[0].name)
        assertEquals("Lokal", items[0].group)
    }

    @Test
    fun hlsManifestBecomesSingleItem() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"
        val items = PlaylistStore.parse(text, "http://example.com/live/index.m3u8")
        assertEquals(1, items.size)
    }

    @Test
    fun relativeUrlsAreResolvedAndHeadersDropped() {
        val text = "#EXTM3U\n#EXTINF:-1,A\nch/a.ts|User-Agent=X\n"
        val items = PlaylistStore.parse(text, "http://example.com/list/all.m3u")
        assertEquals("http://example.com/list/ch/a.ts", items[0].url)
    }
}
