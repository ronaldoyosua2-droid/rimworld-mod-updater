package com.ronald.rimmodupdater

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cari item Steam Workshop berdasarkan teks (halaman publik Workshop, tanpa login). */
object WorkshopSearch {

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) SteamodUpdater")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** ID mod yang dibutuhkan ("Required items") dari halaman Workshop item [id]. */
    fun requiredItems(id: String): List<String> {
        val html = get("https://steamcommunity.com/sharedfiles/filedetails/?id=$id")
        val start = html.indexOf("id=\"RequiredItems\"")
        if (start < 0) return emptyList()
        // bagian daftar "Required items" cukup pendek; batasi supaya tidak ikut link lain di halaman
        val end = listOf(html.indexOf("workshopItemDescriptionTitle", start), html.indexOf("id=\"highlightContent\"", start), start + 6000)
            .filter { it > start }.minOrNull() ?: (start + 6000)
        val part = html.substring(start, end.coerceAtMost(html.length))
        return Regex("""filedetails/\?id=(\d{6,})""").findAll(part).map { it.groupValues[1] }.filter { it != id }.distinct().toList()
    }

    /** Satu halaman hasil Workshop: ID item (urut) dan jumlah total item (0 = tidak diketahui). */
    data class Page(val ids: List<String>, val total: Int)

    /**
     * Halaman Workshop (30 item): pencarian nama ([query] tidak kosong) atau daftar populer
     * selama [days] hari terakhir ([days] = -1 → sepanjang masa). [tag] mis. "Build 42".
     */
    fun browse(appId: Int, query: String?, days: Int, page: Int, tag: String? = null): Page {
        val sb = StringBuilder("https://steamcommunity.com/workshop/browse/?appid=$appId&section=readytouseitems&numperpage=30&p=$page")
        if (!query.isNullOrBlank()) {
            sb.append("&searchtext=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            sb.append("&browsesort=textsearch&actualsort=textsearch")
        } else {
            sb.append("&browsesort=trend&actualsort=trend&days=").append(days)
        }
        if (!tag.isNullOrBlank()) sb.append("&requiredtags%5B%5D=").append(URLEncoder.encode(tag, "UTF-8"))
        val html = get(sb.toString())
        val ids = Regex("""sharedfiles/filedetails/\?id=(\d{6,})""").findAll(html).map { it.groupValues[1] }.distinct().take(30).toList()
        val total = Regex("""of ([\d,.]+) entries""").find(html)?.groupValues?.get(1)?.replace(",", "")?.replace(".", "")?.toIntOrNull() ?: 0
        return Page(ids, total)
    }

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
