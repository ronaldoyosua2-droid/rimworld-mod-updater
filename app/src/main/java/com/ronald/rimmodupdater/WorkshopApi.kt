package com.ronald.rimmodupdater

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cek info mod di Steam Workshop lewat Steam Web API publik (tidak perlu login). */
object WorkshopApi {
    const val RIMWORLD_APP_ID = 294100

    data class Details(
        val id: String,
        val ok: Boolean,
        val title: String?,
        val timeUpdated: Long,
        val fileSize: Long,
        val appId: Int,
        /** ID versi konten (manifest) di Steam. Berubah setiap kali mod di-update. */
        val contentId: String,
        val previewUrl: String? = null,
        val subscriptions: Long = 0,
        val tags: List<String> = emptyList(),
    ) {
        /** Versi Project Zomboid dari tag Workshop ("Build 42" / "Build 41"), atau null kalau tidak ada. */
        val pzBuild: String?
            get() {
                val t = tags.joinToString(" ").lowercase() + " " + (title ?: "").lowercase()
                return when {
                    "build 42" in t || "b42" in t -> "B42"
                    "build 41" in t || "b41" in t -> "B41"
                    else -> null
                }
            }
    }

    /** Ambil ID Workshop dari link (…?id=123…) atau angka biasa. */
    fun parseId(input: String): String? {
        val s = input.trim()
        Regex("""[?&]id=(\d{5,})""").find(s)?.let { return it.groupValues[1] }
        Regex("""^\d{5,}$""").find(s)?.let { return it.value }
        Regex("""(\d{7,})""").find(s)?.let { return it.groupValues[1] }
        return null
    }

    fun getDetails(ids: List<String>): Map<String, Details> {
        val result = mutableMapOf<String, Details>()
        ids.distinct().chunked(100).forEach { chunk ->
            val body = buildString {
                append("itemcount=").append(chunk.size)
                chunk.forEachIndexed { i, id -> append("&publishedfileids%5B").append(i).append("%5D=").append(id) }
            }
            val conn = URL("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) throw Exception("Steam API error HTTP ${conn.responseCode}")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(text).getJSONObject("response").optJSONArray("publishedfiledetails")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.optString("publishedfileid")
                        result[id] = Details(
                            id = id,
                            ok = o.optInt("result") == 1,
                            title = o.optString("title").ifBlank { null },
                            timeUpdated = o.optLong("time_updated"),
                            fileSize = o.optString("file_size").toLongOrNull() ?: 0L,
                            appId = o.optInt("consumer_app_id"),
                            contentId = o.optString("hcontent_file"),
                            previewUrl = o.optString("preview_url").ifBlank { null },
                            subscriptions = o.optLong("subscriptions"),
                            tags = o.optJSONArray("tags")?.let { a -> List(a.length()) { a.optJSONObject(it)?.optString("tag") ?: "" } }.orEmpty(),
                        )
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        return result
    }
}
