package com.ronald.rimmodupdater

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume

/** Penanda: isi mod lokal sudah dibandingkan dan ternyata versi lama. */
private const val OUTDATED = "outdated"

class MainActivity : AppCompatActivity() {

    private lateinit var store: ModStore
    private val mods = mutableListOf<ModEntry>()
    private var busy = false

    private lateinit var steamStatus: TextView
    private lateinit var steamButton: MaterialButton
    private lateinit var panel: ProgressPanel
    private lateinit var listContainer: LinearLayout
    private val actionButtons = mutableListOf<View>()

    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("id"))
    private val dp get() = resources.displayMetrics.density

    private val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // ------------------------------------------------------------------ pickers

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFromFolder(uri)
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFromZips(uris)
    }

    private var afterOutputPicked: (() -> Unit)? = null
    private val pickOutput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            store.outputTreeUri = uri.toString()
            toast("Folder hasil disimpan")
            afterOutputPicked?.invoke()
        }
        afterOutputPicked = null
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        SteamManager.installCrypto()
        store = ModStore(this)
        mods.addAll(store.load())
        buildUi()
        refreshSteamStatus()
        renderList()
        AppLog.takeLastCrash()?.let { crash ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Aplikasi sempat crash")
                .setMessage("Sebelumnya aplikasi tertutup karena error. Kirim log ke Claude supaya bisa diperbaiki?\n\n" + crash.take(600))
                .setPositiveButton("Kirim log") { _, _ -> shareLog() }
                .setNegativeButton("Nanti", null)
                .show()
        }
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, rwFlags)
        } catch (_: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun px(v: Int) = (v * dp).toInt()

    private fun button(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setPadding(px(6), 0, px(6), 0)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = px(6)
            })
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(8))
        }

        root.addView(TextView(this).apply {
            text = "RimWorld Mod Updater"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "${AppLog.buildLabel}\nTambahkan mod lama, cek versi terbaru di Workshop, lalu tekan Update."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, px(2), 0, px(10))
        })

        steamStatus = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER_VERTICAL }
        steamButton = button("Login Steam") { onSteamButton() }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(steamStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(steamButton)
            addView(button("Log") { showLogDialog() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
        })

        val bFolder = button("+ Folder mod") { pickFolder.launch(null) }
        val bZip = button("+ File zip") { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        val bLink = button("+ Link / ID") { askAddLink() }
        val bCheck = button("Cek update") { checkUpdates() }
        val bAll = button("Update semua") { updateAll() }
        val bOut = button("Folder hasil") { afterOutputPicked = null; pickOutput.launch(null) }
        actionButtons += listOf(bFolder, bZip, bLink, bCheck, bAll, bOut)
        root.addView(row(bFolder, bZip, bLink))
        root.addView(row(bCheck, bAll, bOut))

        panel = ProgressPanel(this)
        root.addView(panel.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(6)
            bottomMargin = px(8)
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listContainer) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Android 15+ menggambar sampai ke balik status bar & tombol navigasi: beri jarak sesuai insets.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(px(16) + bars.left, px(12) + bars.top, px(16) + bars.right, px(8) + maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
    }

    private fun setBusy(b: Boolean, msg: String? = null) {
        busy = b
        actionButtons.forEach { it.isEnabled = !b }
        steamButton.isEnabled = !b
        if (b) {
            panel.start("", msg ?: "Memproses…", "")
            AppLog.tap = { level, line -> terminalFromLog(level, line) }
        } else {
            AppLog.tap = null
            // biarkan hasil akhir terlihat sebentar
            panel.view.postDelayed({ if (!busy) panel.hide() }, 2500)
        }
        renderList()
    }

    private fun showProgress(msg: String?) = panel.detail(msg ?: "")

    private fun progress(msg: String) = panel.detail(msg)

    /** Atur bar persentase (0..1). null = berjalan tanpa persen. */
    private fun setFraction(f: Float?) = panel.fraction(f)

    /** Terjemahkan log Steam yang menarik jadi baris terminal. */
    private fun terminalFromLog(level: String, line: String) {
        when {
            level == "STEAM-ERR" || level == "ERROR" -> panel.log("ERR", line.lineSequence().first().take(90), error = true)
            level == "WARN" -> panel.log("NET", line.take(90))
            line.contains("Request URL is:") -> {
                val url = Regex("""url=(https?://[^,}\s]+)""").find(line)?.groupValues?.get(1) ?: return
                val host = url.substringAfter("://").substringBefore("/").substringBefore(".")
                val path = url.substringAfter("://").substringAfter("/")
                val parts = path.split("/")
                val kind = parts.getOrNull(2) ?: "data"
                val id = parts.getOrNull(3)?.let { if (it.length > 14) it.take(10) + "…" + it.takeLast(4) else it } ?: ""
                panel.log("GET", "$host /$kind/$id")
            }
            line.contains("] Connected to ") -> panel.log("CONNECT", line.substringAfter("Connected to ").take(60), "OK")
            line.contains("handleLogOnResponse") -> panel.log("LOGON", "steam session", line.substringAfter("response: ").substringBefore(",").take(12))
            line.contains("epot key", ignoreCase = true) -> panel.log("KEY", "depot ${WorkshopApi.RIMWORLD_APP_ID} ········", "GRANTED")
        }
    }

    private fun toast(msg: String) {
        AppLog.i("Pesan: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ log

    private fun showLogDialog() {
        val text = AppLog.recentImportant().ifBlank { "(log masih kosong)" }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(px(16), px(8), px(16), px(8))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Log • ${AppLog.buildLabel}")
            .setView(scroll)
            .setPositiveButton("Kirim log") { _, _ -> shareLog() }
            .setNeutralButton("Hapus log") { _, _ -> AppLog.clear(); toast("Log dihapus") }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun shareLog() {
        try {
            val f = AppLog.exportFile(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Log RimMod Updater ${AppLog.buildLabel}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Kirim log ke…"))
        } catch (e: Exception) {
            AppLog.e("Gagal membagikan log", e)
            Toast.makeText(this, "Gagal membagikan log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun fmt(t: Long) = if (t <= 0) "-" else dateFmt.format(Date(t * 1000))

    private fun renderList() {
        listContainer.removeAllViews()
        if (mods.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Belum ada mod.\n\n• \"+ Folder mod\": pilih folder 1 mod, atau folder Mods yang berisi banyak mod.\n" +
                    "• \"+ File zip\": pilih zip mod lama.\n• \"+ Link / ID\": tempel link Workshop untuk download baru.\n\n" +
                    "Lalu tekan \"Cek update\"."
                textSize = 14f
                alpha = 0.8f
                setPadding(0, px(24), 0, 0)
            })
            return
        }
        mods.forEach { listContainer.addView(modCard(it)) }
    }

    private fun modCard(m: ModEntry): View {
        val card = MaterialCardView(this).apply {
            radius = 12 * dp
            useCompatPadding = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(10))
        }
        box.addView(TextView(this).apply {
            text = m.displayName
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        val src = when (m.sourceType) {
            "folder" -> "folder"
            "zip" -> "zip"
            else -> "link"
        }
        box.addView(TextView(this).apply {
            val ver = when {
                m.installedVersion.isEmpty() -> "belum diketahui (tekan Cek update)"
                m.installedVersion == OUTDATED -> "lebih lama dari Workshop"
                m.remoteVersion.isEmpty() || m.installedVersion == m.remoteVersion -> "sama dengan Workshop"
                else -> "lebih lama dari Workshop"
            }
            val size = if (m.remoteSize > 0) " • ${fmtSize(m.remoteSize)}" else ""
            text = "ID ${m.workshopId.ifBlank { "?" }} • sumber: $src$size\nWorkshop diupdate: ${fmt(m.remoteTime)}\nVersi punyamu: $ver"
            textSize = 12f
            alpha = 0.8f
        })
        box.addView(TextView(this).apply {
            text = m.status
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when {
                    m.updateAvailable -> Color.parseColor("#E08A1E")
                    m.status.startsWith("Terbaru") || m.status.startsWith("Berhasil") -> Color.parseColor("#4E9A3F")
                    m.status.startsWith("Gagal") || m.status.startsWith("Error") -> Color.parseColor("#D0453B")
                    else -> steamStatus.currentTextColor
                }
            )
            setPadding(0, px(4), 0, px(4))
        })
        val label = if (m.sourceType == "none") "Download" else "Update"
        val bUpdate = button(label) { updateMods(listOf(m)) }.apply {
            isEnabled = !busy && m.workshopId.isNotBlank() && (m.updateAvailable || m.sourceType == "none" || m.installedVersion.isEmpty())
        }
        val bForce = button("Paksa") {
            MaterialAlertDialogBuilder(this).setTitle("Download ulang?")
                .setMessage("Download ulang ${m.displayName} dari Workshop walaupun sudah terbaru?")
                .setPositiveButton("Ya") { _, _ -> updateMods(listOf(m)) }
                .setNegativeButton("Batal", null).show()
        }.apply { isEnabled = !busy && m.workshopId.isNotBlank() }
        val bId = button("Ubah ID") { askEditId(m) }.apply { isEnabled = !busy }
        val bDel = button("Hapus") {
            mods.remove(m); store.save(mods); renderList()
        }.apply { isEnabled = !busy }
        box.addView(row(bUpdate, bForce, bId, bDel))
        card.addView(box)
        return card
    }

    // ------------------------------------------------------------------ add mods

    private fun upsert(entry: ModEntry) {
        val existing = mods.indexOfFirst {
            (entry.workshopId.isNotBlank() && it.workshopId == entry.workshopId) ||
                (it.sourceUri != null && it.sourceUri == entry.sourceUri && it.subPath == entry.subPath)
        }
        if (existing >= 0) mods[existing] = entry else mods.add(entry)
    }

    private fun addFromFolder(uri: Uri) {
        persist(uri)
        lifecycleScope.launch {
            setBusy(true, "Membaca folder…")
            try {
                val found = withContext(Dispatchers.IO) { ModFiles.scanTree(this@MainActivity, uri) }
                if (found.isEmpty()) {
                    toast("Tidak ada mod di folder itu (tidak ketemu folder About).")
                } else {
                    found.forEach {
                        upsert(ModEntry(
                            workshopId = it.workshopId ?: "",
                            name = it.name,
                            sourceType = "folder",
                            sourceUri = uri.toString(),
                            subPath = it.subPath,
                            localTime = it.localTime,
                            status = if (it.workshopId == null) "ID Workshop tidak ketemu, tekan Ubah ID" else "Belum dicek",
                        ))
                    }
                    store.save(mods)
                    toast("${found.size} mod ditambahkan")
                }
            } catch (e: Exception) {
                AppLog.e("Gagal membaca folder $uri", e)
                toast("Gagal membaca folder: ${e.message}")
            }
            setBusy(false)
            if (mods.any { it.status == "Belum dicek" }) checkUpdates()
        }
    }

    private fun fileName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun addFromZips(uris: List<Uri>) {
        uris.forEach { persist(it) }
        lifecycleScope.launch {
            setBusy(true, "Membaca zip…")
            var added = 0
            for (uri in uris) {
                try {
                    val name = fileName(uri)
                    val s = withContext(Dispatchers.IO) { ModFiles.scanZip(this@MainActivity, uri, name) }
                    if (s == null) {
                        toast("${name ?: "Zip"}: bukan mod RimWorld (tidak ada About/About.xml)")
                        continue
                    }
                    upsert(ModEntry(
                        workshopId = s.workshopId ?: "",
                        name = s.name,
                        sourceType = "zip",
                        sourceUri = uri.toString(),
                        zipPrefix = s.zipPrefix,
                        localTime = s.localTime,
                        status = if (s.workshopId == null) "ID Workshop tidak ketemu, tekan Ubah ID" else "Belum dicek",
                    ))
                    added++
                } catch (e: Exception) {
                    AppLog.e("Gagal membaca zip $uri", e)
                    toast("Gagal membaca zip: ${e.message}")
                }
            }
            store.save(mods)
            setBusy(false)
            if (added > 0) checkUpdates()
        }
    }

    private fun askAddLink() {
        val input = EditText(this).apply {
            hint = "https://steamcommunity.com/sharedfiles/filedetails/?id=…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tambah dari link Workshop")
            .setMessage("Tempel link atau ID mod. Bisa beberapa sekaligus (pisahkan dengan baris baru). Hasilnya disimpan di \"Folder hasil\".")
            .setView(padded(input))
            .setPositiveButton("Tambah") { _, _ ->
                val ids = input.text.toString().split(Regex("""[\s,]+""")).mapNotNull { WorkshopApi.parseId(it) }
                if (ids.isEmpty()) {
                    toast("Link/ID tidak dikenali")
                } else {
                    ids.forEach { id ->
                        if (mods.none { it.workshopId == id }) {
                            mods.add(ModEntry(workshopId = id, name = "Mod $id", sourceType = "none"))
                        }
                    }
                    store.save(mods)
                    renderList()
                    checkUpdates()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun askEditId(m: ModEntry) {
        val input = EditText(this).apply {
            setText(m.workshopId)
            hint = "Link atau ID Workshop"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("ID Workshop: ${m.displayName}")
            .setView(padded(input))
            .setPositiveButton("Simpan") { _, _ ->
                val id = WorkshopApi.parseId(input.text.toString())
                if (id == null) {
                    toast("Link/ID tidak dikenali")
                } else {
                    m.workshopId = id
                    m.status = "Belum dicek"
                    m.remoteTime = 0
                    store.save(mods)
                    renderList()
                    checkUpdates()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(px(20), px(8), px(20), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ check

    private fun checkUpdates() {
        if (busy) return
        val targets = mods.filter { it.workshopId.isNotBlank() }
        if (targets.isEmpty()) {
            toast("Belum ada mod dengan ID Workshop")
            return
        }
        lifecycleScope.launch {
            setBusy(true, "Steam Workshop")
            val t0 = System.currentTimeMillis()
            panel.start("CEK UPDATE · ${targets.size} MOD", "Steam Workshop", "CEK VERSI")
            panel.log("QUERY", "PublishedFileDetails × ${targets.size}")
            try {
                AppLog.i("Cek update ${targets.size} mod")
                val details = withContext(Dispatchers.IO) { WorkshopApi.getDetails(targets.map { it.workshopId }) }
                val needContent = mutableListOf<ModEntry>()
                targets.forEach { m ->
                    val d = details[m.workshopId]
                    when {
                        d == null || !d.ok -> {
                            m.status = "Error: mod tidak ditemukan di Workshop (dihapus/privat?)"
                            m.updateAvailable = false
                        }
                        d.appId != 0 && d.appId != WorkshopApi.RIMWORLD_APP_ID -> {
                            m.status = "Error: ini bukan mod RimWorld"
                            m.updateAvailable = false
                        }
                        else -> {
                            m.remoteTitle = d.title
                            m.remoteTime = d.timeUpdated
                            m.remoteVersion = d.contentId
                            m.remoteSize = d.fileSize
                            when {
                                m.installedVersion.isNotEmpty() -> {
                                    m.updateAvailable = m.installedVersion != d.contentId
                                    m.status = if (m.updateAvailable) "Ada update!" else "Terbaru ✓"
                                }
                                m.sourceType == "none" -> {
                                    m.updateAvailable = true
                                    m.status = "Siap di-download"
                                }
                                else -> needContent += m
                            }
                        }
                    }
                }
                store.save(mods)
                renderList()
                panel.log("RESULT", "${details.size}/${targets.size} mod ditemukan", "OK")

                // Mod yang versinya belum diketahui: bandingkan isi file dengan daftar file resmi di Steam.
                if (needContent.isNotEmpty()) {
                    AppLog.i("${needContent.size} mod perlu dicek isinya")
                    showProgress("${needContent.size} mod perlu dicek isinya, butuh login Steam…")
                    if (!ensureSteam()) {
                        needContent.forEach {
                            it.updateAvailable = false
                            it.status = "Versi belum diketahui. Login Steam lalu tekan Cek update lagi."
                        }
                    } else {
                        refreshSteamStatus()
                        // 1) Ambil semua daftar file sekaligus dalam satu sesi Steam (jauh lebih cepat).
                        val mfDir = File(cacheDir, "mf")
                        panel.title("Daftar file resmi")
                        panel.badge("MANIFEST")
                        val nameOf = needContent.associate { it.workshopId.toLong() to it.displayName }
                        var mfDone = 0
                        val manifests = try {
                            SteamManager.fetchManifests(
                                this@MainActivity,
                                needContent.map { it.workshopId.toLong() },
                                mfDir,
                                onStatus = { progress(it) },
                                onFraction = { setFraction(it * 0.5f) },
                                onManifest = { id, r ->
                                    mfDone++
                                    panel.stats(
                                        "$mfDone/${needContent.size}" to "daftar file",
                                        "4" to "jalur paralel",
                                        "${(System.currentTimeMillis() - t0) / 1000} dtk" to "berjalan",
                                    )
                                    r.onSuccess { panel.log("MANIFEST", "${nameOf[id] ?: id} · ${it.size} files", "OK") }
                                    r.onFailure { panel.log("MANIFEST", "${nameOf[id] ?: id} · ${it.message}", error = true) }
                                },
                            )
                        } catch (e: Exception) {
                            AppLog.e("Ambil daftar file gagal", e)
                            emptyMap()
                        }
                        // 2) Bandingkan dengan file lokal.
                        var diffMods = 0
                        panel.badge("CEK ISI")
                        needContent.forEachIndexed { i, m ->
                            val prefix = ""
                            panel.caption("CEK ISI · ${i + 1} DARI ${needContent.size}")
                            panel.title(m.displayName)
                            panel.stats(
                                "${i + 1}/${needContent.size}" to "mod",
                                "$diffMods" to "perlu update",
                                "${(System.currentTimeMillis() - t0) / 1000} dtk" to "berjalan",
                            )
                            progress("Membandingkan file…")
                            setFraction(0.5f + 0.5f * i / needContent.size)
                            try {
                                val remote = manifests[m.workshopId.toLong()]?.getOrThrow()
                                    ?: throw Exception("daftar file tidak didapat dari Steam")
                                if (remote.isEmpty()) throw Exception("daftar file dari Steam kosong")
                                val r = compareContent(m, remote) { progress(prefix + it) }
                                m.remoteFileCount = r.total
                                if (r.same) {
                                    m.installedVersion = m.remoteVersion
                                    m.updateAvailable = false
                                    m.status = "Terbaru ✓ (isi sama dengan Workshop)"
                                    panel.log("SHA1", "${m.displayName} · ${r.total} files", "MATCH")
                                } else {
                                    diffMods++
                                    panel.log("DIFF", "${m.displayName} · ${r.different.size}/${r.total} beda", "UPDATE")
                                    m.updateAvailable = true
                                    // Tandai versi lama, supaya cek berikutnya tidak perlu bandingkan isi lagi.
                                    m.installedVersion = OUTDATED
                                    m.status = "Ada update! (${r.different.size} dari ${r.total} file berbeda)"
                                    AppLog.i("${m.displayName}: file berbeda ${r.different.take(15)}")
                                }
                            } catch (e: Exception) {
                                AppLog.e("Cek isi ${m.workshopId} (${m.displayName}) gagal", e)
                                panel.log("FAIL", "${m.displayName}: ${e.message}", error = true)
                                m.updateAvailable = false
                                m.status = "Gagal cek isi: ${e.message ?: e.javaClass.simpleName}"
                            }
                            store.save(mods)
                            renderList()
                        }
                        mfDir.deleteRecursively()
                    }
                }
                store.save(mods)
                val updates = targets.count { it.updateAvailable }
                panel.caption("CEK UPDATE · SELESAI")
                panel.title(if (updates == 0) "Semua terbaru" else "$updates mod perlu update")
                panel.badge("SELESAI")
                panel.fraction(1f)
                panel.detail("")
                panel.log("DONE", "${targets.size} mod dicek dalam ${(System.currentTimeMillis() - t0) / 1000} dtk", "✓")
                setBusy(false)
                toast(if (updates == 0) "Semua mod sudah terbaru" else "$updates mod perlu diupdate")
            } catch (e: Exception) {
                AppLog.e("Gagal cek update", e)
                setBusy(false)
                toast("Gagal cek: ${e.message}. Cek koneksi internet.")
            }
        }
    }

    /** Bandingkan file mod lokal dengan daftar file resmi (manifest) dari Steam. */
    private suspend fun compareContent(
        m: ModEntry,
        remote: List<ModFiles.RemoteFile>,
        onProgress: (String) -> Unit,
    ): ModFiles.CompareResult = withContext(Dispatchers.IO) {
        when (m.sourceType) {
            "folder" -> {
                val dir = ModFiles.resolveFolder(this@MainActivity, m)
                    ?: throw Exception("Folder mod tidak ditemukan / izin hilang. Tambahkan ulang foldernya.")
                ModFiles.compareFolder(this@MainActivity, dir, remote, onProgress)
            }
            "zip" -> ModFiles.compareZip(this@MainActivity, Uri.parse(m.sourceUri), m.zipPrefix, remote, onProgress)
            else -> throw Exception("Tidak ada file lokal untuk dibandingkan")
        }
    }

    // ------------------------------------------------------------------ update

    private fun updateAll() {
        val list = mods.filter { it.updateAvailable && it.workshopId.isNotBlank() }
        if (list.isEmpty()) {
            toast("Tidak ada mod yang perlu diupdate. Tekan \"Cek update\" dulu.")
            return
        }
        val total = list.sumOf { it.remoteSize }
        MaterialAlertDialogBuilder(this)
            .setTitle("Update ${list.size} mod?")
            .setMessage(
                (if (total > 0) "Total download sekitar ${fmtSize(total)}.\n\n" else "") +
                    "Biarkan aplikasi tetap terbuka sampai selesai."
            )
            .setPositiveButton("Update") { _, _ -> updateMods(list) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun updateMods(list: List<ModEntry>) {
        if (busy) return
        if (list.any { it.sourceType == "none" } && outputRoot() == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Pilih folder hasil")
                .setMessage("Mod dari link akan disimpan di folder yang kamu pilih (misalnya Download/RimMods).")
                .setPositiveButton("Pilih") { _, _ ->
                    afterOutputPicked = { updateMods(list) }
                    pickOutput.launch(null)
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        lifecycleScope.launch {
            setBusy(true, "Menghubungkan ke Steam…")
            panel.start("UPDATE MOD · ${list.size} MOD", "Menghubungkan ke Steam…", "LOGIN")
            if (!ensureSteam()) {
                setBusy(false)
                return@launch
            }
            refreshSteamStatus()
            var ok = 0
            val total = list.sumOf { it.remoteSize }
            if (total > 0) AppLog.i("Update ${list.size} mod, total ${fmtSize(total)}")
            val states = list.map { it.displayName to ProgressPanel.ItemState.PENDING }.toMutableList()
            list.forEachIndexed { i, m ->
                states[i] = m.displayName to ProgressPanel.ItemState.CURRENT
                panel.items(states.toList())
                panel.caption("UPDATE MOD · ${i + 1} DARI ${list.size}")
                panel.title(m.displayName)
                panel.badge("DOWNLOAD")
                AppLog.i("Mulai update ${m.workshopId} (${m.displayName}), sumber=${m.sourceType}")
                try {
                    updateOne(m, { progress(it) }) { f -> setFraction((i + f) / list.size) }
                    ok++
                    states[i] = m.displayName to ProgressPanel.ItemState.DONE
                    panel.log("DONE", m.displayName, "✓")
                } catch (e: SteamManager.LoginRequired) {
                    m.status = "Gagal: perlu login Steam lagi"
                    states[i] = m.displayName to ProgressPanel.ItemState.FAILED
                    panel.log("FAIL", "${m.displayName}: perlu login lagi", error = true)
                } catch (e: Exception) {
                    AppLog.e("Update ${m.workshopId} (${m.displayName}) gagal", e)
                    m.status = "Gagal: ${e.message ?: e.javaClass.simpleName}"
                    states[i] = m.displayName to ProgressPanel.ItemState.FAILED
                    panel.log("FAIL", "${m.displayName}: ${e.message}", error = true)
                }
                panel.items(states.toList())
                store.save(mods)
                runOnUiThread { renderList() }
            }
            panel.caption("UPDATE MOD · SELESAI")
            panel.title("$ok dari ${list.size} berhasil")
            panel.badge("SELESAI")
            panel.fraction(1f)
            panel.detail("")
            setBusy(false)
            toast("Selesai: $ok dari ${list.size} mod berhasil")
        }
    }

    /** Teruskan pesan salin ke status dan ke terminal. */
    private fun copyLog(onProgress: (String) -> Unit): (String) -> Unit = { msg ->
        onProgress(msg)
        when {
            msg.startsWith("Menyalin ") -> panel.log("COPY", msg.removePrefix("Menyalin "), "✓")
            msg.startsWith("Mengemas ") -> panel.log("ZIP", msg.removePrefix("Mengemas "), "✓")
            msg.startsWith("Menghapus") -> panel.log("CLEAN", "hapus file versi lama")
        }
    }

    private fun outputRoot(): DocumentFile? =
        store.outputTreeUri?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }?.takeIf { it.canWrite() }

    private suspend fun updateOne(
        m: ModEntry,
        onProgress: (String) -> Unit,
        onFraction: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val work = File(cacheDir, "dl/${m.workshopId}")
        work.deleteRecursively()
        work.mkdirs()
        try {
            if (m.remoteVersion.isEmpty()) {
                WorkshopApi.getDetails(listOf(m.workshopId))[m.workshopId]?.let {
                    m.remoteVersion = it.contentId
                    m.remoteSize = it.fileSize
                    m.remoteTime = it.timeUpdated
                    if (m.remoteTitle == null) m.remoteTitle = it.title
                }
            }
            onProgress("Download dari Steam…")
            // Download = 0..80%, salin ke folder = 80..100%
            val t0 = System.currentTimeMillis()
            val bytes = java.util.concurrent.atomic.AtomicLong(0)
            val files = java.util.concurrent.atomic.AtomicInteger(0)
            val chunks = java.util.concurrent.atomic.AtomicInteger(0)
            val size = m.remoteSize
            val fileTotal = m.remoteFileCount
            fun dlStats() {
                val got = bytes.get()
                val secs = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.5)
                val speed = got / secs
                val left = if (size > 0 && speed > 0) ((size - got).coerceAtLeast(0) / speed).toLong() else -1
                panel.stats(
                    (if (fileTotal > 0) "${files.get()}/$fileTotal" else "${files.get()}") to "file",
                    fmtSize(got) to (if (size > 0) "dari ${fmtSize(size)}" else "terunduh"),
                    "${fmtSize(speed.toLong())}/s" to "kecepatan",
                    (if (left >= 0) "~${left} dtk" else "-") to "sisa",
                )
                onProgress("Download ${fmtSize(got)}" + if (size > 0) " / ${fmtSize(size)}" else "")
            }
            SteamManager.downloadMod(
                this@MainActivity,
                m.workshopId.toLong(),
                work,
                onStatus = {},
                onFraction = { f -> onFraction(f * 0.8f) },
                onChunk = { b ->
                    bytes.addAndGet(b)
                    panel.log("CHUNK", "#${chunks.incrementAndGet()}  ${fmtSize(b)} → zstd", "✓")
                    dlStats()
                },
                onFile = { name ->
                    files.incrementAndGet()
                    panel.log("FILE", name, "✓")
                    dlStats()
                },
            )
            panel.badge("MENYALIN")
            val copyTotal = ModFiles.downloadedFiles(work).size
            val copyFrac: (Float) -> Unit = { f ->
                onFraction(0.8f + f * 0.2f)
                panel.stats(
                    "${(f * copyTotal).toInt()}/$copyTotal" to "file disalin",
                    fmtSize(bytes.get().coerceAtLeast(size)) to "ukuran",
                    "${(System.currentTimeMillis() - t0) / 1000} dtk" to "berjalan",
                )
            }
            ModFiles.ensurePublishedId(work, m.workshopId)

            when (m.sourceType) {
                "folder" -> {
                    val target = ModFiles.resolveFolder(this@MainActivity, m)
                        ?: throw Exception("Folder mod lama tidak ditemukan / izin hilang. Tambahkan ulang foldernya.")
                    if (!target.canWrite()) throw Exception("Tidak ada izin menulis ke folder mod lama")
                    ModFiles.replaceFolder(this@MainActivity, target, work, copyLog(onProgress), copyFrac)
                }
                "zip" -> {
                    ModFiles.rewriteZip(this@MainActivity, Uri.parse(m.sourceUri), m.zipPrefix, work, copyLog(onProgress), copyFrac)
                }
                else -> {
                    val out = outputRoot() ?: throw Exception("Folder hasil belum dipilih")
                    val folderName = ModFiles.safeFolderName(m.displayName, m.workshopId)
                    ModFiles.writeNewFolder(this@MainActivity, out, folderName, work, copyLog(onProgress), copyFrac)
                }
            }
            m.localTime = maxOf(System.currentTimeMillis() / 1000, m.remoteTime)
            m.updateAvailable = false
            m.installedVersion = m.remoteVersion
            AppLog.i("Berhasil update ${m.workshopId} (${m.displayName})")
            m.status = "Berhasil diupdate ✓ (${fmt(m.remoteTime)})"
        } finally {
            work.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ Steam login

    private fun refreshSteamStatus() {
        val acc = SteamManager.accountName ?: SteamManager.savedAccount(this)
        if (acc != null) {
            steamStatus.text = "Steam: $acc" + if (SteamManager.loggedIn) " ✓" else ""
            steamButton.text = "Logout"
        } else {
            steamStatus.text = "Steam: belum login\n(perlu untuk download)"
            steamButton.text = "Login Steam"
        }
    }

    private fun onSteamButton() {
        if (SteamManager.savedAccount(this) != null || SteamManager.loggedIn) {
            SteamManager.logout(this)
            refreshSteamStatus()
            toast("Sudah logout")
        } else {
            lifecycleScope.launch {
                setBusy(true, "Login Steam…")
                ensureSteam()
                setBusy(false)
                refreshSteamStatus()
            }
        }
    }

    /** Pastikan sudah login. Menampilkan dialog login kalau perlu. */
    private suspend fun ensureSteam(): Boolean {
        try {
            SteamManager.ensureLoggedIn(this)
            return true
        } catch (_: SteamManager.LoginRequired) {
        } catch (e: Exception) {
            AppLog.e("Tidak bisa tersambung ke Steam", e)
            toast("Tidak bisa tersambung ke Steam: ${e.message}")
            return false
        }
        while (true) {
            val cred = askCredentials() ?: return false
            try {
                progress("Login sebagai ${cred.first}…")
                SteamManager.loginWithPassword(this, cred.first, cred.second, UiLogin())
                refreshSteamStatus()
                toast("Login berhasil")
                return true
            } catch (e: Exception) {
                val cause = (e.cause ?: e)
                if (e is SteamManager.LoginCancelled || cause is CancellationException) {
                    AppLog.i("Login dibatalkan")
                    return false
                }
                AppLog.e("Login Steam gagal", e)
                toast("Login gagal: ${cause.message ?: cause.javaClass.simpleName}")
            }
        }
    }

    private suspend fun askCredentials(): Pair<String, String>? = suspendCancellableCoroutine { cont ->
        val user = EditText(this).apply {
            hint = "Username Steam"
            setText(SteamManager.savedAccount(this@MainActivity) ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(user); addView(pass)
            addView(TextView(this@MainActivity).apply {
                text = "Login langsung ke server Steam (sama seperti RimDroid). Password tidak disimpan; yang disimpan hanya token login di HP ini. Akun harus punya RimWorld."
                textSize = 12f
                alpha = 0.7f
                setPadding(0, px(8), 0, 0)
            })
        }
        var resumed = false
        MaterialAlertDialogBuilder(this)
            .setTitle("Login Steam")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Login") { _, _ ->
                val u = user.text.toString().trim()
                val p = pass.text.toString()
                resumed = true
                cont.resume(if (u.isBlank() || p.isBlank()) null else u to p)
            }
            .setNegativeButton("Batal") { _, _ -> resumed = true; cont.resume(null) }
            .setOnDismissListener { if (!resumed) cont.resume(null) }
            .show()
    }

    /** Dialog verifikasi Steam (Steam Guard) yang dipakai selama login. */
    private inner class UiLogin : SteamManager.LoginUi {

        private suspend fun <T> dialog(build: (MaterialAlertDialogBuilder, (T) -> Unit) -> Unit): T =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    val finish: (T) -> Unit = { v -> if (!done) { done = true; cont.resume(v) } }
                    val b = MaterialAlertDialogBuilder(this@MainActivity).setCancelable(false)
                    build(b, finish)
                    b.show()
                }
            }

        override suspend fun chooseApproveInApp(): Boolean = dialog { b, finish ->
            b.setTitle("Verifikasi Steam Guard")
                .setMessage("Pilih cara verifikasi login:")
                .setPositiveButton("Setujui di app Steam") { _, _ -> finish(true) }
                .setNegativeButton("Masukkan kode") { _, _ -> finish(false) }
        }

        override suspend fun waitApproved(): Boolean = dialog { b, finish ->
            b.setTitle("Setujui di aplikasi Steam")
                .setMessage(
                    "1. Buka aplikasi Steam di HP-mu.\n" +
                        "2. Tekan \"Approve / Setujui\" pada permintaan login \"RimMod Updater\".\n" +
                        "3. Kembali ke sini, lalu tekan tombol \"Sudah disetujui\".\n\n" +
                        "Tidak apa-apa kalau koneksi sempat putus saat kamu pindah aplikasi, nanti otomatis tersambung lagi."
                )
                .setPositiveButton("Sudah disetujui") { _, _ -> finish(true) }
                .setNegativeButton("Batal") { _, _ -> finish(false) }
        }

        override suspend fun askCode(isEmail: Boolean, hint: String?, previousWrong: Boolean): String? = dialog { b, finish ->
            val input = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                this.hint = "Kode"
            }
            val msg = (if (previousWrong) "Kode salah, coba lagi.\n" else "") +
                if (isEmail) "Steam mengirim kode ke email ${hint ?: "kamu"}." else "Buka aplikasi Steam → Steam Guard, lalu masukkan kode 5 karakternya."
            b.setTitle(if (isEmail) "Kode dari email" else "Kode Steam Guard")
                .setMessage(msg)
                .setView(padded(input))
                .setPositiveButton("OK") { _, _ -> finish(input.text.toString().trim().uppercase().ifBlank { null }) }
                .setNegativeButton("Batal") { _, _ -> finish(null) }
        }
    }
}
