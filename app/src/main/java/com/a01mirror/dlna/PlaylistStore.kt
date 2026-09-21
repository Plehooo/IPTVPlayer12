package com.a01mirror.dlna

import android.content.Context
import java.io.BufferedReader
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight M3U playlist model/parser. Keeps the main DLNA/mirroring pipeline independent. */
data class PlaylistItem(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = ""
)

object PlaylistStore {
    private const val PREFS = "a01mirror"
    private const val MAX_ITEMS = 5000
    private const val KEY_URL = "playlist_url"
    private const val KEY_ITEMS = "playlist_items"

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim())
            .apply()
    }

    fun loadUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, "")?.trim().orEmpty()

    fun saveItems(context: Context, items: List<PlaylistItem>) {
        val array = JSONArray()
        items.take(MAX_ITEMS).forEach { item ->
            array.put(JSONObject().apply {
                put("name", item.name)
                put("url", item.url)
                put("group", item.group)
                put("logo", item.logo)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    fun loadItems(context: Context): List<PlaylistItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(minOf(array.length(), MAX_ITEMS)) {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    val url = o.optString("url").trim()
                    if (name.isNotEmpty() &&
                        (url.startsWith("http://", true) || url.startsWith("https://", true))) {
                        add(PlaylistItem(name, url, o.optString("group"), o.optString("logo")))
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private val GROUP_RE = Regex("(?:^|\\s)group-title\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val LOGO_RE = Regex("(?:^|\\s)tvg-logo\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /** Kompatibel dengan versi lama: memproses teks utuh lewat parser streaming. */
    fun parse(text: String, baseUrl: String = ""): List<PlaylistItem> =
        parseReader(BufferedReader(StringReader(text)), baseUrl)

    /**
     * Parser streaming #EXTM3U/#EXTINF: membaca baris demi baris, jadi RAM yang dipakai hanya item hasil
     * parse (bukan salinan teks 16 MB berkali-kali). Menerima daftar IPTV yang longgar, dengan batas
     * jumlah item dan batas ukuran agar URL rusak tidak bisa memakan memori tanpa batas.
     */
    fun parseReader(
        reader: BufferedReader,
        baseUrl: String = "",
        maxChars: Long = 16L * 1024L * 1024L
    ): List<PlaylistItem> {
        val out = ArrayList<PlaylistItem>(256)
        var pendingName = ""
        var pendingGroup = ""
        var pendingLogo = ""
        var total = 0L

        // Judul EXTINF = teks setelah koma PERTAMA di luar tanda kutip. Koma di dalam atribut
        // (mis. tvg-name="RCTI, HD") tidak lagi memotong nama channel.
        fun titleFromExtinf(line: String): String {
            var inQuote = false
            for (i in line.indices) {
                val ch = line[i]
                if (ch == '"') inQuote = !inQuote
                else if (ch == ',' && !inQuote) return line.substring(i + 1).trim()
            }
            return ""
        }

        fun resolveUrl(raw: String): String {
            // IPTV M3U sering memakai `url|User-Agent=...&Referer=...`. Standar DLNA
            // AVTransport hanya menerima URL media; header tidak bisa ikut dikirim sebagai bagian URL.
            val value = raw.trim().substringBefore('|').trim()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
            return try {
                if (baseUrl.isBlank()) value else java.net.URI(baseUrl).resolve(value).toString()
            } catch (_: Throwable) {
                value
            }
        }

        while (true) {
            val raw = reader.readLine() ?: break
            total += raw.length + 1L
            if (total > maxChars) throw IllegalStateException("playlist terlalu besar (>${maxChars / 1048576L} MB)")
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isEmpty()) continue

            // Manifest tunggal (bukan daftar channel): tag-nya selalu muncul sebelum URL apa pun.
            if (line.startsWith("<") && line.contains("<MPD", true)) {
                val label = baseUrl.substringAfterLast('/').substringBefore('?').ifBlank { "DASH Stream" }
                return listOf(PlaylistItem("DASH • $label", baseUrl))
            }
            if (line.startsWith("#EXT-X-TARGETDURATION", true) ||
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", true) ||
                line.startsWith("#EXT-X-STREAM-INF", true) ||
                line.startsWith("#EXT-X-ENDLIST", true)
            ) {
                val label = baseUrl.substringAfterLast('/').substringBefore('?').ifBlank { "HLS Stream" }
                return listOf(PlaylistItem("HLS • $label", baseUrl))
            }

            when {
                line.startsWith("#EXTINF", true) -> {
                    pendingName = titleFromExtinf(line)
                    pendingGroup = GROUP_RE.find(line)?.groupValues?.getOrNull(1).orEmpty()
                    pendingLogo = LOGO_RE.find(line)?.groupValues?.getOrNull(1).orEmpty()
                }
                line.startsWith("#EXTGRP:", true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolveUrl(line)
                    if ((url.startsWith("http://", true) || url.startsWith("https://", true)) && out.size < MAX_ITEMS) {
                        val name = pendingName.ifBlank {
                            url.substringAfterLast('/').substringBefore('?').ifBlank { "Channel ${out.size + 1}" }
                        }
                        out += PlaylistItem(name, url, pendingGroup, pendingLogo)
                    }
                    pendingName = ""
                    pendingGroup = ""
                    pendingLogo = ""
                }
            }
        }
        return out
    }
}
