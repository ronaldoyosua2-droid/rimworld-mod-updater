package com.ronald.rimmodupdater

import android.content.Context
import java.util.Locale

/** Bahasa tampilan aplikasi: Indonesia (bawaan) atau English. */
object Lang {
    @Volatile var en = false
        private set

    fun init(ctx: Context) {
        en = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "id") == "en"
    }

    fun set(ctx: Context, english: Boolean) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", if (english) "en" else "id").apply()
        en = english
    }

    val locale: Locale get() = if (en) Locale.ENGLISH else Locale.forLanguageTag("id")
    val name: String get() = if (en) "English" else "Bahasa Indonesia"
}

/** Teks sesuai bahasa yang dipilih: [id] = Indonesia, [en] = English. */
fun tr(id: String, en: String): String = if (Lang.en) en else id

/** Status mod (tersimpan dalam bahasa saat dicek) — dikenali dalam kedua bahasa. */
object StatusKind {
    private val failed = listOf("Gagal", "Error", "ID Workshop belum", "Failed", "Workshop ID not")
    private val ok = listOf("Terbaru", "Berhasil", "Sudah", "Up to date", "Updated", "Already")
    private val unchecked = setOf("Belum dicek", "Not checked")

    fun isFailed(s: String) = failed.any { s.startsWith(it) }
    fun isOk(s: String) = ok.any { s.startsWith(it) }
    fun isUnchecked(s: String) = s in unchecked
}
