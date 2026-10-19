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
    fun replaceFolder(ctx: Context, target: DocumentFile, src: File, onProgress: (String) -> Unit) {
        onProgress("Menghapus file lama…")
        target.listFiles().forEach { it.delete() }
        copyInto(ctx, target, src, src, onProgress)
    }

    /** Taruh mod baru sebagai folder baru di folder hasil. */
    fun writeNewFolder(ctx: Context, outRoot: DocumentFile, folderName: String, src: File, onProgress: (String) -> Unit): DocumentFile {
        outRoot.listFiles().firstOrNull { it.name == folderName }?.delete()
        val dir = outRoot.createDirectory(folderName) ?: throw Exception("Tidak bisa membuat folder $folderName")
        copyInto(ctx, dir, src, src, onProgress)
        return dir
    }

    private fun copyInto(ctx: Context, target: DocumentFile, srcDir: File, root: File, onProgress: (String) -> Unit) {
        srcDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) {
                if (f.name == ".DepotDownloader") return@forEach
                val sub = target.createDirectory(f.name) ?: throw Exception("Gagal membuat folder ${f.name}")
                copyInto(ctx, sub, f, root, onProgress)
            } else {
                onProgress("Menyalin ${f.relativeTo(root).path}")
                val doc = target.createFile("application/octet-stream", f.name)
                    ?: throw Exception("Gagal membuat file ${f.name}")
                ctx.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                } ?: throw Exception("Gagal menulis ${f.name}")
            }
        }
    }

    /** Tulis ulang file zip lama dengan isi versi baru (struktur folder di dalam zip dipertahankan). */
    fun rewriteZip(ctx: Context, zipUri: Uri, prefix: String, src: File, onProgress: (String) -> Unit) {
        val files = downloadedFiles(src)
        val out = ctx.contentResolver.openOutputStream(zipUri, "wt")
            ?: throw Exception("Tidak bisa menulis ke file zip")
        ZipOutputStream(out.buffered()).use { zos ->
            files.forEach { f ->
                val rel = f.relativeTo(src).path.replace('\\', '/')
                onProgress("Mengemas $rel")
                zos.putNextEntry(ZipEntry(prefix + rel).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    // ---------------------------------------------------------------- bandingkan isi

    data class RemoteFile(val path: String, val size: Long, val sha1: String)

    data class CompareResult(val total: Int, val different: List<String>) {
        val same: Boolean get() = different.isEmpty()
    }

    /** Batas ukuran file yang dicek hash-nya (file lebih besar cukup dicek ukurannya). */
    private const val HASH_LIMIT = 4L * 1024 * 1024

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
        val diff = mutableListOf<String>()
        remote.forEachIndexed { i, rf ->
            val l = local[rf.path] ?: lower[rf.path.lowercase()]
            when {
                l == null -> diff += "${rf.path} (tidak ada)"
                l.second != rf.size -> diff += "${rf.path} (ukuran beda)"
                rf.size in 1..HASH_LIMIT -> {
                    if (i % 10 == 0) onProgress("Memeriksa file ${i + 1}/${remote.size}")
                    val h = ctx.contentResolver.openInputStream(l.first)?.use { sha1(it) }
                    if (h != rf.sha1) diff += "${rf.path} (isi beda)"
                }
            }
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
                    if (++n % 10 == 0) onProgress("Memeriksa file $n/${remote.size}")
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

    fun safeFolderName(name: String, id: String): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "").trim().take(60)
        return if (clean.isBlank()) id else clean
    }
}
