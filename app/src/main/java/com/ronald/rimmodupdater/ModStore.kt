package com.ronald.rimmodupdater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Satu mod yang dipantau aplikasi. */
data class ModEntry(
    var workshopId: String,
    var name: String,
    /** "folder" = folder mod (bisa diupdate langsung), "zip" = file zip, "none" = cuma dari link/ID. */
    var sourceType: String,
    /** Untuk folder: URI tree yang dipilih. Untuk zip: URI dokumen zip. */
    var sourceUri: String? = null,
    /** Untuk folder: nama sub-folder di dalam tree ("" kalau tree-nya sendiri adalah mod). */
    var subPath: String = "",
    /** Untuk zip: awalan folder di dalam zip, misal "NamaMod/". */
    var zipPrefix: String = "",
    /** Waktu versi lokal (detik epoch). 0 = tidak diketahui. */
    var localTime: Long = 0,
    var remoteTime: Long = 0,
    var remoteTitle: String? = null,
    var status: String = "Belum dicek",
    var updateAvailable: Boolean = false,
) {
    val displayName: String get() = remoteTitle?.takeIf { it.isNotBlank() } ?: name

    fun toJson(): JSONObject = JSONObject().apply {
        put("workshopId", workshopId)
        put("name", name)
        put("sourceType", sourceType)
        put("sourceUri", sourceUri ?: "")
        put("subPath", subPath)
        put("zipPrefix", zipPrefix)
        put("localTime", localTime)
        put("remoteTime", remoteTime)
        put("remoteTitle", remoteTitle ?: "")
        put("status", status)
        put("updateAvailable", updateAvailable)
    }

    companion object {
        fun fromJson(o: JSONObject) = ModEntry(
            workshopId = o.optString("workshopId"),
            name = o.optString("name"),
            sourceType = o.optString("sourceType", "none"),
            sourceUri = o.optString("sourceUri").ifBlank { null },
            subPath = o.optString("subPath"),
            zipPrefix = o.optString("zipPrefix"),
            localTime = o.optLong("localTime"),
            remoteTime = o.optLong("remoteTime"),
            remoteTitle = o.optString("remoteTitle").ifBlank { null },
            status = o.optString("status", "Belum dicek"),
            updateAvailable = o.optBoolean("updateAvailable"),
        )
    }
}

class ModStore(context: Context) {
    private val prefs = context.getSharedPreferences("mods", Context.MODE_PRIVATE)

    fun load(): MutableList<ModEntry> {
        val raw = prefs.getString("list", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { ModEntry.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<ModEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    var outputTreeUri: String?
        get() = prefs.getString("outputTree", null)
        set(v) = prefs.edit().putString("outputTree", v).apply()
}
