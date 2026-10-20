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

/**
 * Status mod tersimpan dalam bahasa saat terakhir dicek. Saat ditampilkan, ubah ke bahasa yang
 * sedang dipilih (mod tanpa ID tidak ikut SCAN UPDATE, jadi statusnya tidak akan diperbarui sendiri).
 */
object StatusText {
    // {0}, {1} = bagian yang berubah-ubah. Urutan: yang spesifik dulu, yang umum terakhir.
    private val pairs = listOf(
        "Belum dicek" to "Not checked",
        "ID Workshop tidak ketemu, tekan Ubah ID" to "Workshop ID not found, tap EDIT ID",
        "Mencari ID Workshop…" to "Searching Workshop ID…",
        "ID belum dicari. Login Steam lalu tekan CARI ID." to "ID not searched yet. Log in to Steam, then tap FIND ID.",
        "ID Workshop belum ketemu: tidak ada hasil pencarian. Tekan lama judul → Cari di Workshop." to
            "Workshop ID not found: no search results. Long-press the title → Search Steam Workshop.",
        "ID Workshop belum ketemu: {0} hasil dicek, isinya tidak cocok. Tekan lama judul → Cari di Workshop." to
            "Workshop ID not found: {0} results checked, contents don't match. Long-press the title → Search Steam Workshop.",
        "Error: mod tidak ditemukan di Workshop (dihapus/privat?)" to "Error: mod not found on the Workshop (removed/private?)",
        "Error: ini bukan mod {0}" to "Error: this is not a {0} mod",
        "Terbaru ✓ (isi sama dengan Workshop)" to "Up to date ✓ (same contents as Workshop)",
        "Sudah terbaru ✓ (isi sama dengan Workshop)" to "Already up to date ✓ (same contents as Workshop)",
        "Terbaru ✓" to "Up to date ✓",
        "Ada update! ({0} dari {1} file berbeda)" to "Update available! ({0} of {1} files differ)",
        "Ada update!" to "Update available!",
        "Siap di-download" to "Ready to download",
        "Versi belum diketahui. Login Steam lalu tekan Cek update lagi." to "Version unknown. Log in to Steam, then check again.",
        "Gagal: folder mod tidak ada di HP (sudah dicek ulang). Tekan DEL untuk buang dari daftar." to
            "Failed: mod folder is not on the phone (rescanned). Tap DEL to remove it from the list.",
        "Gagal cek isi: Folder mod tidak ditemukan / izin hilang. Tambahkan ulang foldernya." to
            "Failed content check: Mod folder not found / permission lost. Add the folder again.",
        "Gagal cek isi: {0}" to "Failed content check: {0}",
        "Gagal: perlu login Steam lagi" to "Failed: Steam login needed again",
        "Berhasil diupdate ✓ ({0})" to "Updated ✓ ({0})",
        "Gagal: {0}" to "Failed: {0}",
    )

    private fun regex(tpl: String) = Regex(
        "^" + tpl.split(Regex("""\{\d}""")).joinToString("(.*?)") { Regex.escape(it) } + "$",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val compiled = pairs.map { (id, en) -> Triple(regex(id), regex(en), id to en) }

    fun localize(s: String): String {
        for ((rid, ren, p) in compiled) {
            val (from, to) = if (Lang.en) rid to p.second else ren to p.first
            val m = from.find(s) ?: continue
            var out = to
            m.groupValues.drop(1).forEachIndexed { i, v -> out = out.replace("{$i}", v) }
            return out
        }
        return s
    }
}
