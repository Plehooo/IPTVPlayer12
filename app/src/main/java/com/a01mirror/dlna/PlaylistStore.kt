package com.a01mirror.dlna

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Lightweight M3U playlist model/parser. Keeps the main DLNA/mirroring pipeline independent. */
data class PlaylistItem(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = ""
)

object PlaylistStore {
    private const val PREFS = "a01mirror"
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
        items.take(2000).forEach { item ->
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
            buildList(minOf(array.length(), 2000)) {
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

    /**
     * Parses common #EXTM3U/#EXTINF files. It intentionally accepts loose playlists found in IPTV
     * lists while keeping a hard limit so a broken URL cannot allocate unbounded memory.
     */
    fun parse(text: String, baseUrl: String = ""): List<PlaylistItem> {
        val lines = text.removePrefix("\uFEFF").lineSequence()
            .map { it.trim().trimStart('\uFEFF') }
            .filter { it.isNotEmpty() }
            .toList()
        val out = ArrayList<PlaylistItem>(minOf(lines.size / 2, 2000))
        var pendingName = ""
        var pendingGroup = ""
        var pendingLogo = ""

        fun attr(line: String, key: String): String {
            val regex = Regex("$key\\s*=\\s*\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
            return regex.find(line)?.groupValues?.getOrNull(1).orEmpty()
        }

        fun titleFromExtinf(line: String): String {
            val comma = line.indexOf(',')
            return if (comma >= 0) line.substring(comma + 1).trim() else ""
        }

        fun resolveUrl(raw: String): String {
            val value = try { URLDecoder.decode(raw.trim(), StandardCharsets.UTF_8.name()) } catch (_: Throwable) { raw.trim() }
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
            return try {
                if (baseUrl.isBlank()) value else java.net.URI(baseUrl).resolve(value).toString()
            } catch (_: Throwable) {
                value
            }
        }

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", true) -> {
                    pendingName = titleFromExtinf(line)
                    pendingGroup = attr(line, "group-title")
                    pendingLogo = attr(line, "tvg-logo")
                }
                line.startsWith("#EXTGRP:", true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolveUrl(line)
                    if ((url.startsWith("http://", true) || url.startsWith("https://", true)) && out.size < 2000) {
                        val name = pendingName.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "Channel ${out.size + 1}" } }
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
