package com.ronald.rimmodupdater

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Membaca mod lama (folder / zip) dan memasang hasil download ke tempatnya. */
object ModFiles {

    data class ScannedMod(
        val name: String,
        val workshopId: String?,
        val localTime: Long,
        val subPath: String = "",
        val zipPrefix: String = "",
        val modId: String = "",
    )

    private val nameRegex = Regex("""<name>\s*(.*?)\s*</name>""", RegexOption.DOT_MATCHES_ALL)

    private fun parseName(aboutXml: String?): String? =
        aboutXml?.let { nameRegex.find(it)?.groupValues?.get(1) }
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.trim()?.takeIf { it.isNotBlank() }

    private fun parsePubId(text: String?): String? =
        text?.trim()?.let { Regex("""\d{5,}""").find(it)?.value }

    private fun DocumentFile.child(name: String): DocumentFile? =
        listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun readText(ctx: Context, f: DocumentFile?): String? = try {
        f?.let { ctx.contentResolver.openInputStream(it.uri)?.bufferedReader()?.use { r -> r.readText() } }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- folder

    /** Folder yang dipilih bisa berupa 1 mod (ada folder About) atau folder Mods berisi banyak mod. */
    fun scanTree(ctx: Context, treeUri: Uri): List<ScannedMod> {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        if (root.child("About")?.isDirectory == true) {
            return listOfNotNull(scanModDir(ctx, root, ""))
        }
        return root.listFiles()
            .filter { it.isDirectory && it.child("About")?.isDirectory == true }
            .mapNotNull { scanModDir(ctx, it, it.name ?: "") }
    }

    private fun scanModDir(ctx: Context, dir: DocumentFile, subPath: String): ScannedMod? {
        val about = dir.child("About") ?: return null
        val aboutFiles = about.listFiles()
        val aboutXml = readText(ctx, aboutFiles.firstOrNull { it.name.equals("About.xml", true) })
        val pubId = parsePubId(readText(ctx, aboutFiles.firstOrNull { it.name.equals("PublishedFileId.txt", true) }))
            ?: dir.name?.takeIf { it.matches(Regex("""\d{5,}""")) }
        val times = (aboutFiles.toList() + dir.listFiles().toList()).map { it.lastModified() }
        return ScannedMod(
            name = parseName(aboutXml) ?: dir.name ?: "Mod tanpa nama",
            workshopId = pubId,
            localTime = (times.maxOrNull() ?: 0L) / 1000,
            subPath = subPath,
        )
    }

    fun resolveFolder(ctx: Context, entry: ModEntry): DocumentFile? {
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(entry.sourceUri ?: return null)) ?: return null
        if (entry.subPath.isEmpty()) return root
        return root.child(entry.subPath)
    }

    // ---------------------------------------------------------------- zip

    fun scanZip(ctx: Context, uri: Uri, fileName: String?): ScannedMod? {
        var bestPrefix: String? = null
        var aboutXml: String? = null
        val pubIds = mutableMapOf<String, String>()
        var maxTime = 0L
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val n = e.name.replace('\\', '/')
                    if (e.time > maxTime) maxTime = e.time
                    when {
                        n.endsWith("About/About.xml", ignoreCase = true) -> {
                            val prefix = n.substring(0, n.length - "About/About.xml".length)
                            if (bestPrefix == null || prefix.length < bestPrefix!!.length) {
                                bestPrefix = prefix
                                aboutXml = zis.readBytes().toString(Charsets.UTF_8)
                            }
                        }
                        n.endsWith("About/PublishedFileId.txt", ignoreCase = true) -> {
                            val prefix = n.substring(0, n.length - "About/PublishedFileId.txt".length)
                            parsePubId(zis.readBytes().toString(Charsets.UTF_8))?.let { pubIds[prefix] = it }
                        }
                    }
                }
            }
        }
        val prefix = bestPrefix ?: return null
        val folderName = prefix.trimEnd('/').substringAfterLast('/')
        return ScannedMod(
            name = parseName(aboutXml) ?: folderName.ifBlank { fileName ?: "Mod tanpa nama" },
            workshopId = pubIds[prefix] ?: folderName.takeIf { it.matches(Regex("""\d{5,}""")) }
                ?: fileName?.let { Regex("""\d{7,}""").find(it)?.value },
            localTime = maxTime / 1000,
            zipPrefix = prefix,
        )
    }

    // ---------------------------------------------------------------- install

    /** File hasil download (tanpa folder internal .DepotDownloader). */
    fun downloadedFiles(dir: File): List<File> =
        dir.walkTopDown()
            .onEnter { it == dir || it.name != ".DepotDownloader" }
            .filter { it.isFile }
            .toList()

    /** Pastikan About/PublishedFileId.txt ada supaya lain kali ID-nya terbaca otomatis. */
    fun ensurePublishedId(dir: File, workshopId: String) {
        val about = dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("About", true) }
            ?: File(dir, "About").apply { mkdirs() }
        val exists = about.listFiles()?.any { it.name.equals("PublishedFileId.txt", true) } == true
        if (!exists) File(about, "PublishedFileId.txt").writeText(workshopId)
    }

    /** Ganti isi folder mod lama dengan versi baru. */
    fun replaceFolder(
        ctx: Context,
        target: DocumentFile,
        src: File,
        onProgress: (String) -> Unit,
        onFraction: (Float) -> Unit = {},
    ) {
        onProgress("Menghapus file lama…")
        target.listFiles().forEach { it.delete() }
        copyInto(ctx, target, src, src, onProgress, Counter(downloadedFiles(src).size, onFraction))
    }

    /** Taruh mod baru sebagai folder baru di folder hasil. */
    fun writeNewFolder(
        ctx: Context,
        outRoot: DocumentFile,
        folderName: String,
        src: File,
        onProgress: (String) -> Unit,
        onFraction: (Float) -> Unit = {},
    ): DocumentFile {
        outRoot.listFiles().firstOrNull { it.name == folderName }?.delete()
        val dir = outRoot.createDirectory(folderName) ?: throw Exception("Tidak bisa membuat folder $folderName")
        copyInto(ctx, dir, src, src, onProgress, Counter(downloadedFiles(src).size, onFraction))
        return dir
    }

    private class Counter(val total: Int, val onFraction: (Float) -> Unit) {
        var done = 0
        fun tick() {
            done++
            onFraction(done.toFloat() / total.coerceAtLeast(1))
        }
    }

    private fun copyInto(ctx: Context, target: DocumentFile, srcDir: File, root: File, onProgress: (String) -> Unit, counter: Counter) {
        srcDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) {
                if (f.name == ".DepotDownloader") return@forEach
                val sub = target.createDirectory(f.name) ?: throw Exception("Gagal membuat folder ${f.name}")
                copyInto(ctx, sub, f, root, onProgress, counter)
            } else {
                onProgress("Menyalin ${f.relativeTo(root).path}")
                val doc = target.createFile("application/octet-stream", f.name)
                    ?: throw Exception("Gagal membuat file ${f.name}")
                ctx.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                } ?: throw Exception("Gagal menulis ${f.name}")
                counter.tick()
            }
        }
    }

    /** Tulis ulang file zip lama dengan isi versi baru (struktur folder di dalam zip dipertahankan). */
    fun rewriteZip(
        ctx: Context,
        zipUri: Uri,
        prefix: String,
        src: File,
        onProgress: (String) -> Unit,
        onFraction: (Float) -> Unit = {},
    ) {
        val files = downloadedFiles(src)
        val out = ctx.contentResolver.openOutputStream(zipUri, "wt")
            ?: throw Exception("Tidak bisa menulis ke file zip")
        ZipOutputStream(out.buffered()).use { zos ->
            files.forEachIndexed { idx, f ->
                val rel = f.relativeTo(src).path.replace('\\', '/')
                onProgress("Mengemas $rel")
                zos.putNextEntry(ZipEntry(prefix + rel).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                onFraction((idx + 1).toFloat() / files.size.coerceAtLeast(1))
            }
        }
    }

    // ---------------------------------------------------------------- bandingkan isi

    data class RemoteFile(val path: String, val size: Long, val sha1: String)

    data class CompareResult(val total: Int, val different: List<String>) {
        val same: Boolean get() = different.isEmpty()
    }

    /**
     * File kode/data (xml, dll, dll.) dicek hash-nya karena perubahan kecil bisa tidak mengubah ukuran.
     * File aset besar (gambar, suara) cukup dicek ukurannya supaya cepat.
     */
    private val HASH_EXT = setOf("xml", "dll", "txt", "json", "cs", "xaml", "lua", "csv", "ini", "cfg")
    private const val HASH_LIMIT = 8L * 1024 * 1024

    private fun needsHash(rf: RemoteFile): Boolean =
        rf.size in 1..HASH_LIMIT && rf.path.substringAfterLast('.', "").lowercase() in HASH_EXT

    /** Baca file manifest_*.txt buatan DepotDownloader. */
    fun parseManifest(txt: File): List<RemoteFile> {
        val line = Regex("""^\s*(\d+)\s+(\d+)\s+([0-9a-f]{40})\s+([0-9a-f]+)\s+(.+)$""")
        return txt.readLines().mapNotNull { l ->
            val m = line.find(l) ?: return@mapNotNull null
            val flags = m.groupValues[4].toInt(16)
            if (flags and 0x40 != 0) return@mapNotNull null // folder
            RemoteFile(
                path = m.groupValues[5].trim().replace('\\', '/'),
                size = m.groupValues[1].toLong(),
                sha1 = m.groupValues[3],
            )
        }
    }

    private fun sha1(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Daftar semua file di folder mod (cepat, langsung lewat DocumentsContract). */
    private fun listTree(ctx: Context, treeUri: Uri, docId: String, prefix: String, out: MutableMap<String, Pair<Uri, Long>>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name != ".DepotDownloader") listTree(ctx, treeUri, id, "$prefix$name/", out)
                } else {
                    out["$prefix$name"] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to c.getLong(3)
                }
            }
        }
    }

    fun compareFolder(ctx: Context, dir: DocumentFile, remote: List<RemoteFile>, onProgress: (String) -> Unit): CompareResult {
        val local = mutableMapOf<String, Pair<Uri, Long>>()
        listTree(ctx, dir.uri, DocumentsContract.getDocumentId(dir.uri), "", local)
        val lower = local.entries.associate { it.key.lowercase() to it.value }
        // Tahap 1 (cepat): cek file yang hilang / ukurannya beda. Kalau sudah ada beda, tidak perlu hitung hash.
        val diff = mutableListOf<String>()
        val toHash = mutableListOf<Pair<RemoteFile, Uri>>()
        remote.forEach { rf ->
            val l = local[rf.path] ?: lower[rf.path.lowercase()]
            when {
                l == null -> diff += "${rf.path} (tidak ada)"
                l.second != rf.size -> diff += "${rf.path} (ukuran beda)"
                needsHash(rf) -> toHash += rf to l.first
            }
        }
        if (diff.isNotEmpty() || toHash.isEmpty()) return CompareResult(remote.size, diff)

        // Tahap 2: hitung hash file kode/data, beberapa sekaligus.
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll(toHash.map { (rf, uri) ->
                java.util.concurrent.Callable {
                    val h = ctx.contentResolver.openInputStream(uri)?.use { sha1(it) }
                    val n = done.incrementAndGet()
                    if (n % 20 == 0) onProgress("Memeriksa isi file $n/${toHash.size}")
                    if (h != rf.sha1) "${rf.path} (isi beda)" else null
                }
            })
            results.mapNotNullTo(diff) { it.get() }
        } finally {
            pool.shutdown()
        }
        return CompareResult(remote.size, diff)
    }

    fun compareZip(ctx: Context, zipUri: Uri, prefix: String, remote: List<RemoteFile>, onProgress: (String) -> Unit): CompareResult {
        val byPath = remote.associateBy { it.path.lowercase() }
        val seen = mutableSetOf<String>()
        val diff = mutableListOf<String>()
        ctx.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var n = 0
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    if (!name.startsWith(prefix)) continue
                    val key = name.removePrefix(prefix).lowercase()
                    val rf = byPath[key] ?: continue
                    seen += key
                    if (++n % 25 == 0) onProgress("Memeriksa file $n/${remote.size}")
                    if (!needsHash(rf) && e.size >= 0) {
                        if (e.size != rf.size) diff += "${rf.path} (ukuran beda)"
                        continue
                    }
                    val counting = object : java.io.FilterInputStream(zis) {
                        var count = 0L
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also { if (it > 0) count += it }
                        override fun close() {}
                    }
                    val h = sha1(counting)
                    if (counting.count != rf.size) diff += "${rf.path} (ukuran beda)"
                    else if (rf.size > 0 && h != rf.sha1) diff += "${rf.path} (isi beda)"
                }
            }
        } ?: throw Exception("Tidak bisa membuka zip")
        remote.filter { it.path.lowercase() !in seen }.forEach { diff += "${it.path} (tidak ada)" }
        return CompareResult(remote.size, diff)
    }


    // ---------------------------------------------------------------- update sebagian (hanya file yang berubah)

    data class UpdatePlan(
        /** File yang perlu di-download (baru / berubah). */
        val changed: List<RemoteFile>,
        /** File lokal yang sudah tidak ada di versi Workshop (dihapus). */
        val obsolete: List<Pair<String, Uri>>,
    ) {
        val isEmpty get() = changed.isEmpty() && obsolete.isEmpty()
    }

    /** Bandingkan folder mod dengan daftar file resmi; semua file dicek ukuran + hash supaya akurat. */
    fun planFolderUpdate(ctx: Context, dir: DocumentFile, remote: List<RemoteFile>, onProgress: (String) -> Unit): UpdatePlan {
        val local = mutableMapOf<String, Pair<Uri, Long>>()
        listTree(ctx, dir.uri, DocumentsContract.getDocumentId(dir.uri), "", local)
        val lower = local.entries.associate { it.key.lowercase() to it }
        val changed = java.util.Collections.synchronizedList(mutableListOf<RemoteFile>())
        val toHash = mutableListOf<Pair<RemoteFile, Uri>>()
        val matched = mutableSetOf<String>()
        remote.forEach { rf ->
            val e = local[rf.path]?.let { rf.path to it } ?: lower[rf.path.lowercase()]?.let { it.key to it.value }
            if (e == null) {
                changed += rf
            } else {
                matched += e.first
                when {
                    e.second.second != rf.size -> changed += rf
                    rf.size > 0 -> toHash += rf to e.second.first
                }
            }
        }
        if (toHash.isNotEmpty()) {
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                pool.invokeAll(toHash.map { (rf, uri) ->
                    java.util.concurrent.Callable {
                        val h = try {
                            ctx.contentResolver.openInputStream(uri)?.use { sha1(it) }
                        } catch (_: Exception) {
                            null
                        }
                        if (h != rf.sha1) changed += rf
                        val n = done.incrementAndGet()
                        if (n % 50 == 0) onProgress("Memeriksa file lokal $n/${toHash.size}")
                    }
                }).forEach { it.get() }
            } finally {
                pool.shutdown()
            }
        }
        val obsolete = local.filterKeys { it !in matched }.map { it.key to it.value.first }
        return UpdatePlan(changed.toList(), obsolete)
    }

    /** Tulis file yang berubah ke folder mod (menimpa) dan hapus file yang sudah tidak dipakai. */
    fun applyChanges(
        ctx: Context,
        target: DocumentFile,
        src: File,
        plan: UpdatePlan,
        onProgress: (String) -> Unit,
        onFraction: (Float) -> Unit = {},
    ) {
        val dirs = mutableMapOf("" to target)
        fun dirFor(rel: String): DocumentFile {
            dirs[rel]?.let { return it }
            val parent = dirFor(rel.substringBeforeLast('/', ""))
            val name = rel.substringAfterLast('/')
            val d = parent.listFiles().firstOrNull { it.isDirectory && it.name.equals(name, true) }
                ?: parent.createDirectory(name)
                ?: throw Exception("Gagal membuat folder $rel")
            dirs[rel] = d
            return d
        }
        val total = plan.changed.size + plan.obsolete.size
        var done = 0
        plan.changed.forEach { rf ->
            val f = File(src, rf.path)
            if (!f.isFile) throw Exception("File hasil download tidak ada: ${rf.path}")
            val parent = dirFor(rf.path.substringBeforeLast('/', ""))
            val name = rf.path.substringAfterLast('/')
            val doc = parent.findFile(name) ?: parent.createFile("application/octet-stream", name)
                ?: throw Exception("Gagal membuat file ${rf.path}")
            ctx.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } ?: throw Exception("Gagal menulis ${rf.path}")
            onProgress("Menyalin ${rf.path}")
            done++
            onFraction(done.toFloat() / total.coerceAtLeast(1))
        }
        plan.obsolete.forEach { (rel, uri) ->
            try {
                DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            } catch (_: Exception) {
            }
            onProgress("Menghapus $rel")
            done++
            onFraction(done.toFloat() / total.coerceAtLeast(1))
        }
    }


    // ---------------------------------------------------------------- Project Zomboid

    private data class Child(val id: String, val name: String, val isDir: Boolean)

    private fun children(ctx: Context, treeUri: Uri, docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = mutableListOf<Child>()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Child(c.getString(0), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    /** Baca "name=" dan "id=" dari mod.info. */
    private fun readModInfo(ctx: Context, treeUri: Uri, docId: String): Pair<String?, String?> = try {
        ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
            ?.bufferedReader()?.use { r ->
                val lines = r.readLines().map { it.trim() }
                fun v(key: String) = lines.firstOrNull { it.startsWith("$key=", ignoreCase = true) }
                    ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }
                v("name") to v("id")
            } ?: (null to null)
    } catch (_: Exception) {
        null to null
    }

    /** Cari mod.info di folder mod Zomboid (di akar folder, atau di subfolder versi seperti "42"/"common"). */
    private fun findModInfo(ctx: Context, treeUri: Uri, dirId: String): String? {
        val kids = children(ctx, treeUri, dirId)
        kids.firstOrNull { !it.isDir && it.name.equals("mod.info", true) }?.let { return it.id }
        kids.filter { it.isDir && (it.name.matches(Regex("""\d+(\.\d+)*""")) || it.name.equals("common", true)) }
            .forEach { d ->
                children(ctx, treeUri, d.id).firstOrNull { !it.isDir && it.name.equals("mod.info", true) }?.let { return it.id }
            }
        return null
    }

    /**
     * Folder yang dipilih bisa berupa folder "mods" (berisi banyak mod) atau satu folder mod.
     * Nama folder mod dipakai sebagai kunci; ID Workshop dicari terpisah.
     */
    fun scanZomboid(ctx: Context, treeUri: Uri, onProgress: (String) -> Unit = {}): List<ScannedMod> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        findModInfo(ctx, treeUri, rootId)?.let { info ->
            val folder = DocumentFile.fromTreeUri(ctx, treeUri)?.name ?: "mod"
            val (name, id) = readModInfo(ctx, treeUri, info)
            return listOf(ScannedMod(name ?: folder, null, 0, subPath = "", modId = id ?: ""))
        }
        val dirs = children(ctx, treeUri, rootId).filter { it.isDir }
        val out = mutableListOf<ScannedMod>()
        dirs.forEachIndexed { i, d ->
            if (i % 10 == 0) onProgress("Membaca folder mod ${i + 1}/${dirs.size}")
            val info = findModInfo(ctx, treeUri, d.id) ?: return@forEachIndexed
            val (name, id) = readModInfo(ctx, treeUri, info)
            out += ScannedMod(name ?: d.name, null, 0, subPath = d.name, modId = id ?: "")
        }
        return out
    }

    /** Awalan "…/mods/<folder>/" di daftar file item Workshop, atau null kalau folder itu tidak ada. */
    fun findModPrefix(remote: List<RemoteFile>, folder: String): String? {
        val needle = "mods/${folder.lowercase()}/"
        for (rf in remote) {
            val p = rf.path.lowercase()
            val i = p.indexOf(needle)
            if (i >= 0 && (i == 0 || p[i - 1] == '/')) return rf.path.substring(0, i + needle.length)
        }
        return null
    }

    /** Ambil hanya file di bawah [prefix], dengan path relatif terhadap folder mod. */
    fun scope(remote: List<RemoteFile>, prefix: String): List<RemoteFile> =
        if (prefix.isEmpty()) remote
        else remote.filter { it.path.startsWith(prefix) }.map { it.copy(path = it.path.removePrefix(prefix)) }

    fun safeFolderName(name: String, id: String): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "").trim().take(60)
        return if (clean.isBlank()) id else clean
    }
}
