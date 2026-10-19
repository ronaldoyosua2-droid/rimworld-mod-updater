package com.ronald.rimmodupdater

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
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

    fun safeFolderName(name: String, id: String): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "").trim().take(60)
        return if (clean.isBlank()) id else clean
    }
}
