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
        /** Penanda dunia/koloni (seed + nilai acak dunia): autosave & save manual koloni yang sama punya nilai sama. */
        val colony: String?,
    ) {
        val playedSec: Double get() = trackedSec ?: activeSec ?: 0.0
        val isAutosave: Boolean get() = name.startsWith("Autosave", ignoreCase = true)
    }

    /** Beberapa file save dari koloni yang sama: [main] = file terbaru (angka paling baru). */
    class Colony(val main: SaveInfo, val files: List<SaveInfo>) {
        /** Nama tampilan: save manual terbaru kalau ada (autosave tidak punya nama koloni). */
        val name: String get() = files.firstOrNull { !it.isAutosave }?.name ?: main.name
    }

    /** Gabungkan save dengan penanda koloni yang sama; urut dari yang terakhir dimainkan. */
    fun group(saves: List<SaveInfo>): List<Colony> =
        saves.groupBy { it.colony ?: ("file:" + it.name) }.values
            .map { list -> list.sortedByDescending { it.modified }.let { Colony(it.first(), it) } }
            .sortedByDescending { it.main.modified }

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
            val hit = cache.getString(key, null)?.split('|')?.takeIf { it.size == 6 && "${it[0]}|${it[1]}" == sig }
            val name = f.name.substring(0, f.name.length - 4)
            if (hit != null) {
                SaveInfo(name, f.modified, hit[2].toDoubleOrNull(), hit[3].toDoubleOrNull(), hit[4].toLongOrNull(), hit[5].ifBlank { null })
            } else {
                val s = parse(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, f.id), name, f.modified)
                edit.putString(key, "$sig|${s.activeSec ?: ""}|${s.trackedSec ?: ""}|${s.ticks ?: ""}|${s.colony ?: ""}")
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
        var seed: String? = null
        var rnd: String? = null
        fun value(line: String, tag: String): String? {
            val open = "<$tag>"
            val a = line.indexOf(open)
            if (a < 0) return null
            val b = line.indexOf("</$tag>", a)
            return if (b < 0) null else line.substring(a + open.length, b).trim()
        }
        try {
            ctx.contentResolver.openInputStream(uri)?.let { java.io.BufferedReader(java.io.InputStreamReader(it, Charsets.UTF_8), 256 * 1024) }?.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.indexOf('<') < 0) continue
                    if (active == null) value(line, "realPlayTimeInteracting")?.let { active = it.toDoubleOrNull() }
                    if (ticks == null) value(line, "ticksGame")?.let { ticks = it.toLongOrNull() }
                    if (tracked == null) value(line, "modsyncRealSeconds")?.let { tracked = it.toDoubleOrNull() }
                    if (seed == null) value(line, "seedString")?.let { seed = it }
                    if (rnd == null) value(line, "persistentRandomValue")?.let { rnd = it }
                    if (active != null && ticks != null && tracked != null && seed != null && rnd != null) break
                }
            }
        } catch (e: Exception) {
            AppLog.e("Gagal baca save $name: ${e.message}")
        }
        // tanpa nilai acak dunia, seed saja tidak cukup unik (seed bawaan sering sama) → jangan digabung
        val colony = if (rnd != null) "${seed.orEmpty()}#$rnd".replace('|', '/') else null
        return SaveInfo(name, modified, active, tracked, ticks, colony)
    }

    /**
     * Versi mod PlayTime yang terpasang di folder Mods: null = belum terpasang,
     * "1.0.0" = terpasang tapi tanpa <modVersion> (build 56).
     */
    fun installedVersion(ctx: Context, modsTree: String?): String? = try {
        if (modsTree == null) null else {
            val tree = Uri.parse(modsTree)
            val dir = children(ctx, tree, rootId(tree)).firstOrNull { it.isDir && it.name.equals(MOD_FOLDER, ignoreCase = true) }
            if (dir == null) null else {
                val about = children(ctx, tree, dir.id).firstOrNull { it.isDir && it.name.equals("About", ignoreCase = true) }
                val xml = about?.let { a -> children(ctx, tree, a.id).firstOrNull { !it.isDir && it.name.equals("About.xml", ignoreCase = true) } }
                val text = xml?.let { x ->
                    ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, x.id))?.bufferedReader()?.use { it.readText() }
                }
                text?.let { versionIn(it) } ?: "1.0.0"
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun versionIn(xml: String): String? =
        xml.substringAfter("<modVersion>", "").substringBefore("</modVersion>", "").trim().ifBlank { null }

    /** Versi mod di folder [dir] (yang berisi About/About.xml). */
    fun versionOf(dir: File): String = try { versionIn(File(dir, "About/About.xml").readText()) } catch (_: Exception) { null } ?: "1.0.0"

    /** -1 / 0 / 1 untuk "1.2.0" vs "1.10.0". */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private const val REMOTE_ZIP = "https://github.com/ronaldoyosua2-droid/rimworld-mod-updater/releases/latest/download/ModSyncPlayTime.zip"

    /** Download mod PlayTime dari rilis GitHub terbaru (zip kecil). null kalau offline / gagal. */
    fun fetchRemote(ctx: Context): File? = try {
        val base = File(ctx.cacheDir, "playtime_remote")
        base.deleteRecursively()
        base.mkdirs()
        val c = java.net.URL(REMOTE_ZIP).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ModSyncUpdate")
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
        val root = base.canonicalPath + File.separator
        java.util.zip.ZipInputStream(c.inputStream.buffered()).use { z ->
            var total = 0L
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(base, e.name)
                if (!f.canonicalPath.startsWith(root)) throw Exception("bad zip entry")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o -> total += z.copyTo(o) }
                    if (total > 20L * 1024 * 1024) throw Exception("zip too big")
                }
            }
        }
        File(base, MOD_FOLDER).takeIf { File(it, "About/About.xml").exists() }
    } catch (e: Exception) {
        AppLog.w("Cek mod PlayTime di GitHub gagal: ${e.message}")
        null
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
