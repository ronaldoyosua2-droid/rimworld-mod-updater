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
    private val failed = listOf("Gagal", "Error", "ID Workshop belum", "ID kemungkinan salah", "Failed", "Workshop ID not", "Workshop ID uncertain", "ID probably wrong")
    private val suspect = listOf("ID kemungkinan salah", "ID probably wrong", "ID Workshop belum pasti", "Workshop ID uncertain")
    private val ok = listOf("Terbaru", "Berhasil", "Sudah", "Up to date", "Updated", "Already")
    private val unchecked = setOf("Belum dicek", "Not checked")

    fun isFailed(s: String) = failed.any { s.startsWith(it) }
    fun isOk(s: String) = ok.any { s.startsWith(it) }
    fun isUnchecked(s: String) = s in unchecked
    fun isSuspect(s: String) = suspect.any { s.startsWith(it) }
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
        "ID Workshop belum pasti: kandidat terbaik {0} hanya {1}% cocok. Cek di Workshop lalu isi manual." to
            "Workshop ID uncertain: best candidate {0} is only {1}% similar. Check the Workshop and enter it manually.",
        "ID kemungkinan salah: hanya {0}% file lokal ada di item Workshop ini (versi B41/B42 lain atau modpack?). Tekan FIND ID." to
            "ID probably wrong: only {0}% of local files exist in this Workshop item (other B41/B42 version or a modpack?). Tap FIND ID.",
        "Gagal: {0}" to "Failed: {0}",
    )

    /** Pecah template jadi potongan teks tetap: "a {0} b {1} c" → [a , b , c]. */
    private fun pieces(tpl: String): List<String> {
        val out = mutableListOf<String>()
        var rest = tpl
        var n = 0
        while (true) {
            val k = rest.indexOf("{$n}")
            if (k < 0) { out += rest; return out }
            out += rest.substring(0, k)
            rest = rest.substring(k + "{$n}".length)
            n++
        }
    }

    /** Cocokkan [s] dengan template; hasilnya isi tiap {n}, atau null kalau tidak cocok. */
    private fun match(s: String, tpl: String): List<String>? {
        val p = pieces(tpl)
        if (p.size == 1) return if (s == tpl) emptyList() else null
        if (!s.startsWith(p.first()) || !s.endsWith(p.last()) || s.length < p.first().length + p.last().length) return null
        val vals = mutableListOf<String>()
        var pos = p.first().length
        val end = s.length - p.last().length
        for (i in 1 until p.size - 1) {
            val k = s.indexOf(p[i], pos)
            if (k < 0 || k > end) return null
            vals += s.substring(pos, k)
            pos = k + p[i].length
        }
        if (pos > end) return null
        vals += s.substring(pos, end)
        return vals
    }

    fun localize(s: String): String = try {
        var result = s
        for ((id, en) in pairs) {
            val (from, to) = if (Lang.en) id to en else en to id
            val vals = match(s, from) ?: continue
            var out = to
            vals.forEachIndexed { i, v -> out = out.replace("{$i}", v) }
            result = out
            break
        }
        result
    } catch (e: Exception) {
        s
    }
}
