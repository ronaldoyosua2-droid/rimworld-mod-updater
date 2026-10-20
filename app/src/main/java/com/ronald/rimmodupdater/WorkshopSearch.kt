package com.ronald.rimmodupdater

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cari item Steam Workshop berdasarkan teks (halaman publik Workshop, tanpa login). */
object WorkshopSearch {

    fun search(appId: Int, query: String, max: Int = 5): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "https://steamcommunity.com/workshop/browse/?appid=$appId" +
            "&searchtext=" + URLEncoder.encode(q, "UTF-8") +
            "&browsesort=textsearch&section=readytouseitems&actualsort=textsearch&p=1"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) SteamodUpdater")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("""sharedfiles/filedetails/\?id=(\d{6,})""").findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .take(max)
                .toList()
        } finally {
            conn.disconnect()
        }
    }
}
