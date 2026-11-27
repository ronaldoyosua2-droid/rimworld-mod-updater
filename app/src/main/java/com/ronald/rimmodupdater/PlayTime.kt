package com.ronald.rimmodupdater

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Jam main per save RimWorld.
 * - "aktif"  = realPlayTimeInteracting dari RimWorld sendiri (berhenti setelah ±90 dtk tanpa sentuhan).
 * - "played" = modsyncRealSeconds dari mod ModSync PlayTime (di dalam save, AFK & pause ikut).
 */
object PlayTime {
    const val MOD_FOLDER = "ModSyncPlayTime"
    const val MOD_PACKAGE = "ronald.modsync.playtime"
    private const val ASSET_DIR = "playtime_mod/$MOD_FOLDER"

    class SaveInfo(
        val name: String,
        val modified: Long,
        val activeSec: Double?,
        val trackedSec: Double?,
        val ticks: Long?,
    ) {
        val playedSec: Double get() = trackedSec ?: activeSec ?: 0.0
    }

    private class Doc(val id: String, val name: String, val isDir: Boolean, val modified: Long, val size: Long)

    private fun children(ctx: Context, tree: Uri, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Doc>()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Doc(c.getString(0), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
            }
        }
        return out
    }

    private fun rootId(tree: Uri): String =
        try { DocumentsContract.getDocumentId(tree) } catch (_: Exception) { DocumentsContract.getTreeDocumentId(tree) }

    /** Semua save (.rws) di folder Saves, terbaru dulu. Hasil baca disimpan supaya file yang tidak berubah tidak dibaca ulang. */
    fun readSaves(ctx: Context, savesTree: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<SaveInfo> {
        val tree = Uri.parse(savesTree)
        val files = children(ctx, tree, rootId(tree))
            .filter { !it.isDir && it.name.endsWith(".rws", ignoreCase = true) }
            .sortedByDescending { it.modified }
        val cache = ctx.getSharedPreferences("playtime", Context.MODE_PRIVATE)
        val edit = cache.edit()
        val out = files.mapIndexed { i, f ->
            onProgress(i + 1, files.size)
            val key = "rws|" + f.name
            val sig = "${f.modified}|${f.size}"
            val hit = cache.getString(key, null)?.split('|')?.takeIf { it.size == 5 && "${it[0]}|${it[1]}" == sig }
            val name = f.name.substring(0, f.name.length - 4)
            if (hit != null) {
                SaveInfo(name, f.modified, hit[2].toDoubleOrNull(), hit[3].toDoubleOrNull(), hit[4].toLongOrNull())
            } else {
                val s = parse(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, f.id), name, f.modified)
                edit.putString(key, "$sig|${s.activeSec ?: ""}|${s.trackedSec ?: ""}|${s.ticks ?: ""}")
                s
            }
        }
        edit.apply()
        return out
    }

    /** Baca file save baris per baris; berhenti begitu ketiga nilai ketemu. */
    private fun parse(ctx: Context, uri: Uri, name: String, modified: Long): SaveInfo {
        var active: Double? = null
        var tracked: Double? = null
        var ticks: Long? = null
        fun value(line: String, tag: String): String? {
            val open = "<$tag>"
            val a = line.indexOf(open)
            if (a < 0) return null
            val b = line.indexOf("</$tag>", a)
            return if (b < 0) null else line.substring(a + open.length, b).trim()
        }
        try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8, 256 * 1024)?.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.indexOf('<') < 0) continue
                    if (active == null) value(line, "realPlayTimeInteracting")?.let { active = it.toDoubleOrNull() }
                    if (ticks == null) value(line, "ticksGame")?.let { ticks = it.toLongOrNull() }
                    if (tracked == null) value(line, "modsyncRealSeconds")?.let { tracked = it.toDoubleOrNull() }
                    if (active != null && ticks != null && tracked != null) break
                }
            }
        } catch (e: Exception) {
            AppLog.e("Gagal baca save $name: ${e.message}")
        }
        return SaveInfo(name, modified, active, tracked, ticks)
    }

    /** Folder mod ModSync PlayTime ada di folder Mods? */
    fun modInstalled(ctx: Context, modsTree: String?): Boolean = try {
        if (modsTree == null) false else {
            val tree = Uri.parse(modsTree)
            children(ctx, tree, rootId(tree)).any { it.isDir && it.name.equals(MOD_FOLDER, ignoreCase = true) }
        }
    } catch (_: Exception) {
        false
    }

    /** Salin isi mod dari dalam APK ke folder sementara (untuk dipasang ke folder Mods). */
    fun extractMod(ctx: Context): File {
        val out = File(ctx.cacheDir, "playtime_mod/$MOD_FOLDER")
        out.deleteRecursively()
        fun copy(asset: String, dest: File) {
            val kids = ctx.assets.list(asset).orEmpty()
            if (kids.isEmpty()) {
                dest.parentFile?.mkdirs()
                ctx.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } else {
                dest.mkdirs()
                kids.forEach { copy("$asset/$it", File(dest, it)) }
            }
        }
        copy(ASSET_DIR, out)
        if (!File(out, "About/About.xml").exists()) throw Exception(tr("File mod tidak ada di dalam aplikasi", "Mod files are missing from the app"))
        return out
    }

    // ---------- format

    fun hours(sec: Double): String {
        val m = (sec / 60).toLong()
        return "${m / 60}${tr("j", "h")} ${"%02d".format(m % 60)}m"
    }

    /** 60.000 tick = 1 hari, 60 hari = 1 tahun (kalender RimWorld). */
    fun inGame(ticks: Long): String {
        val days = ticks / 60_000
        val y = days / 60
        val d = days % 60
        return if (y > 0) tr("$y tahun $d hari", "$y years $d days") else tr("$d hari", "$d days")
    }
}
