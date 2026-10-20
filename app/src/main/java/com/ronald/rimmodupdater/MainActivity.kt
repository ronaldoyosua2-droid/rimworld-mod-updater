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

    /** Game yang sedang dipilih di panel samping. */
    private var game = Game.RIMWORLD
    /** Mod milik game yang sedang aktif. */
    private val visible: List<ModEntry> get() = mods.filter { it.game == game.key }

    /** Daftar file Workshop yang baru diambil (dipakai ulang supaya tidak download dua kali). */
    private val manifestCache = java.util.concurrent.ConcurrentHashMap<Long, List<ModFiles.RemoteFile>>()
    private var manifestCacheTime = 0L

    private lateinit var steamStatus: TextView
    private lateinit var steamButton: MaterialButton
    private lateinit var panel: ProgressPanel
    private lateinit var listContainer: LinearLayout
    private val actionButtons = mutableListOf<View>()

    private val dateFmt get() = SimpleDateFormat("dd MMM yyyy, HH:mm", Lang.locale)
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
            store.setOutputFor(game.key, uri.toString())
            toast(tr("Folder hasil disimpan", "Output folder saved"))
            afterOutputPicked?.invoke()
        }
        afterOutputPicked = null
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        Lang.init(this)
        SteamManager.installCrypto()
        store = ModStore(this)
        mods.addAll(store.load())
        game = Game.of(store.currentGame)
        SteamManager.appId = game.appId
        buildUi()
        refreshSteamStatus()
        renderList()
        AppLog.takeLastCrash()?.let { crash ->
            CmdDialog(this)
                .setTitle(tr("Aplikasi sempat crash", "The app crashed"))
                .setMessage(tr("Sebelumnya aplikasi tertutup karena error. Kirim log ke Claude supaya bisa diperbaiki?\n\n", "The app closed because of an error last time. Send the log to Claude so it can be fixed?\n\n") + crash.take(600))
                .setPositiveButton(tr("Kirim log", "Send log")) { _, _ -> shareLog() }
                .setNegativeButton(tr("Nanti", "Later"), null)
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

    /** Tombol bergaya terminal: bingkai hijau, atau "solid" untuk aksi utama. */
    private fun button(text: String, solid: Boolean = false, small: Boolean = false, onClick: () -> Unit) =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            typeface = Hx.MONO_BOLD
            textSize = if (small) 10.5f else 11.5f
            letterSpacing = 0.02f
            cornerRadius = px(4)
            insetTop = 0
            insetBottom = 0
            minHeight = px(if (small) 34 else 44)
            minimumHeight = px(if (small) 34 else 44)
            setPadding(px(4), 0, px(4), 0)
            strokeWidth = px(1)
            strokeColor = Hx.states(Hx.GREEN, Hx.DARK)
            rippleColor = android.content.res.ColorStateList.valueOf(0x5539FF88)
            if (solid) {
                backgroundTintList = Hx.states(Hx.GREEN, Hx.DARK)
                setTextColor(Hx.states(Hx.ON_GREEN, Hx.DIM))
            } else {
                backgroundTintList = Hx.states(0x1439FF88, 0x0539FF88)
                setTextColor(Hx.states(Hx.GREEN, Hx.DARK))
            }
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, px(6))
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = px(6)
            })
        }
    }

    private fun term(text: CharSequence, size: Float = 11.5f, color: Int = Hx.DIM, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) Hx.MONO_BOLD else Hx.MONO
        }

    private fun boxBg(fill: Int = Hx.CARD, stroke: Int = Hx.DARK, radius: Int = 6) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setStroke(px(1), stroke)
            cornerRadius = px(radius).toFloat()
        }

    private lateinit var drawer: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var targetLabel: TextView
    private lateinit var gameList: LinearLayout
    private lateinit var drawerFooter: TextView
    private lateinit var headerReveal: ScanReveal
    private var cursorOn = true
    private var animateList = true
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private fun buildUi() {
        // ---------- konten utama
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Hx.BG)
        }

        val burger = TextView(this).apply {
            text = "☰"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Hx.GREEN)
            Hx.glow(this, pulse = true)
            background = boxBg(0x1039FF88, Hx.DIM)
            setOnClickListener { if (!busy) drawer.openDrawer(Gravity.START) }
        }
        titleView = term("STEAMOD_UPDATER", 19f, Hx.GREEN, bold = true).apply {
            letterSpacing = 0.06f
            Hx.glow(this, pulse = true)
        }
        subtitleView = term("", 10.5f, Hx.DIM)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(burger, LinearLayout.LayoutParams(px(40), px(40)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), 0, 0, 0)
                addView(titleView)
                addView(subtitleView)
            })
        }

        targetLabel = term(game.title.uppercase() + " [${game.appId}]", 12f, Hx.BRIGHT, bold = true).apply { Hx.glow(this, pulse = true) }
        val target = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(9), px(10), px(9))
            background = boxBg(0x0F39FF88, Hx.DIM)
            addView(term("> target_game:", 12f, Hx.GREEN), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(targetLabel)
            addView(term("  ▾", 12f, Hx.GREEN))
            setOnClickListener { if (!busy) drawer.openDrawer(Gravity.START) }
        }

        steamStatus = term("", 11.5f, Hx.DIM)
        steamButton = button("LOGIN", small = true) { onSteamButton() }
        val steamRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(8), 0, px(8))
            addView(steamStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(steamButton, LinearLayout.LayoutParams(px(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val bFolder = button("[+] FOLDER") { pickFolder.launch(null) }
        val bZip = button("[+] ZIP") { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        val bLink = button("[+] LINK/ID") { askAddLink() }
        val bCheck = button("SCAN UPDATE", solid = true) { checkUpdates() }
        val bAll = button("UPDATE ALL") { updateAll() }
        val bLog = button("LOG") { showLogDialog() }
        actionButtons += listOf(bFolder, bZip, bLink, bCheck, bAll)

        headerReveal = ScanReveal(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(target, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = px(12)
                })
                addView(steamRow)
                addView(row(bFolder, bZip, bLink))
                addView(row(bCheck, bAll, bLog))
            })
        }
        content.addView(headerReveal)

        panel = ProgressPanel(this)
        content.addView(panel.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(4)
            bottomMargin = px(8)
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listContainer)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------- panel samping (pilih game)
        gameList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerFooter = term("", 10.5f, Hx.DIM).apply { setLineSpacing(px(3).toFloat(), 1f) }
        val drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF020604.toInt())
            setPadding(px(16), px(24), px(16), px(16))
            isClickable = true
            addView(term("// SELECT TARGET", 12f, Hx.GREEN, bold = true).apply { letterSpacing = 0.15f; Hx.glow(this, pulse = true) })
            addView(term("> loading game profiles...\n> ${Game.entries.size} profiles found OK", 10f, Hx.DIM).apply {
                setPadding(0, px(4), 0, px(16))
            })
            addView(gameList)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(term("> set output_dir", 11f, Hx.GREEN).apply {
                setPadding(0, px(10), 0, px(10))
                setOnClickListener { drawer.closeDrawers(); afterOutputPicked = null; pickOutput.launch(null) }
            })
            addView(term("> settings · ${if (Lang.en) "EN" else "ID"}", 11f, Hx.GREEN).apply {
                setPadding(0, px(6), 0, px(10))
                setOnClickListener { drawer.closeDrawers(); showSettings() }
            })
            addView(term("> view log", 11f, Hx.GREEN).apply {
                setPadding(0, px(6), 0, px(14))
                setOnClickListener { drawer.closeDrawers(); showLogDialog() }
            })
            addView(View(this@MainActivity).apply { setBackgroundColor(Hx.DARK) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)))
            addView(drawerFooter.apply { setPadding(0, px(10), 0, 0) })
        }
        // garis hijau menyala di tepi kanan panel
        val drawerFrame = android.widget.FrameLayout(this).apply {
            addView(drawerPanel, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(this@MainActivity).apply { setBackgroundColor(Hx.GREEN) },
                android.widget.FrameLayout.LayoutParams(px(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        }

        val main = android.widget.FrameLayout(this).apply {
            addView(content)
            addView(CrtOverlay(this@MainActivity))
        }
        drawer = androidx.drawerlayout.widget.DrawerLayout(this).apply {
            setScrimColor(0x99000000.toInt())
            addView(main, androidx.drawerlayout.widget.DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(drawerFrame, androidx.drawerlayout.widget.DrawerLayout.LayoutParams(px(296), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            })
            addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) = renderGames(animate = true)
            })
        }

        // Android 15+ menggambar sampai ke balik status bar & tombol navigasi: beri jarak sesuai insets.
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(px(14) + bars.left, px(14) + bars.top, px(14) + bars.right, px(8) + maxOf(bars.bottom, ime.bottom))
            drawerPanel.setPadding(px(16), px(20) + bars.top, px(16), px(16) + bars.bottom)
            insets
        }
        setContentView(drawer)
        bootAnimation()
    }

    /** Animasi pembuka: judul "dekripsi", header di-render garis, teks prompt diketik, kursor berkedip. */
    private fun bootAnimation() {
        headerReveal.reveal(0, 500)
        Decrypt.run(titleView, "STEAMOD_UPDATER", 800)
        val prompt = "build ${BuildConfig.VERSION_CODE} · root@android:~$ "
        var i = 0
        val type = object : Runnable {
            override fun run() {
                if (i <= prompt.length) {
                    subtitleView.text = prompt.substring(0, i) + "█"
                    i++
                    ui.postDelayed(this, 22)
                } else {
                    ui.post(blink)
                }
            }
        }
        ui.postDelayed(type, 250)
    }

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            val base = "build ${BuildConfig.VERSION_CODE} · root@android:~$ "
            subtitleView.text = base + if (cursorOn) "█" else " "
            ui.postDelayed(this, 530)
        }
    }

    /** Isi panel samping dengan daftar game. */
    private fun renderGames(animate: Boolean = false) {
        gameList.removeAllViews()
        Game.entries.forEachIndexed { idx, g ->
            val on = g == game
            val count = mods.count { it.game == g.key }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12), px(12), px(12), px(12))
                background = boxBg(if (on) 0x1439FF88 else 0x00000000, if (on) Hx.GREEN else Hx.DARK)
                addView(term(g.short, 13f, Hx.GREEN, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = boxBg(0x00000000, Hx.DIM)
                    Hx.glow(this)
                }, LinearLayout.LayoutParams(px(42), px(42)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(12), 0, 0, 0)
                    addView(term(g.title, 13.5f, Hx.BRIGHT, bold = true))
                    addView(term("app ${g.appId} · $count mods", 10.5f, Hx.DIM))
                })
                setOnClickListener { switchGame(g) }
            }
            val wrap = ScanReveal(this).apply { addView(item) }
            gameList.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = px(10)
            })
            if (animate) wrap.revealLine(120L + idx * 120L, 380)
        }
        val acc = SteamManager.accountName ?: SteamManager.savedAccount(this)
        drawerFooter.text = "> steam: ${acc ?: "-"} ${if (SteamManager.loggedIn) "● online" else "○ offline"}\n> ${AppLog.buildLabel}"
    }

    private fun switchGame(g: Game) {
        drawer.closeDrawers()
        if (g == game || busy) return
        game = g
        store.currentGame = g.key
        SteamManager.appId = g.appId
        AppLog.i("Ganti game: ${g.title}")
        targetLabel.text = "SWITCHING…"
        ui.postDelayed({
            Decrypt.run(targetLabel, g.title.uppercase() + " [${g.appId}]", 500)
            headerReveal.reveal(0, 450)
            animateList = true
            renderList()
        }, 220)
    }

    private fun setBusy(b: Boolean, msg: String? = null) {
        busy = b
        actionButtons.forEach { it.isEnabled = !b }
        steamButton.isEnabled = !b
        drawer.setDrawerLockMode(
            if (b) androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            else androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
        )
        if (b) {
            panel.start("", msg ?: tr("Memproses…", "Working…"), "")
            AppLog.tap = { level, line -> terminalFromLog(level, line) }
        } else {
            AppLog.tap = null
            panel.finish()
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
            line.contains("epot key", ignoreCase = true) -> panel.log("KEY", "depot ${game.appId} ········", "GRANTED")
        }
    }

    private fun toast(msg: String) {
        AppLog.i("Pesan: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ log

    // ------------------------------------------------------------------ settings

    private fun showSettings() {
        val langs = arrayOf("Bahasa Indonesia", "English")
        CmdDialog(this)
            .setTitle(tr("Setting · Bahasa", "Settings · Language"))
            .setSingleChoiceItems(langs, if (Lang.en) 1 else 0) { d, which ->
                d.dismiss()
                val english = which == 1
                if (english == Lang.en) return@setSingleChoiceItems
                if (busy) {
                    toast(tr("Tunggu proses selesai dulu", "Wait for the current task to finish"))
                    return@setSingleChoiceItems
                }
                Lang.set(this, english)
                AppLog.i("Bahasa diganti ke ${Lang.name}")
                recreate()
            }
            .setNegativeButton(tr("Tutup", "Close"), null)
            .show()
    }

    private fun showLogDialog() {
        val text = AppLog.recentImportant().ifBlank { tr("(log masih kosong)", "(log is empty)") }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(px(16), px(8), px(16), px(8))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        CmdDialog(this)
            .setTitle("Log • ${AppLog.buildLabel}")
            .setView(scroll)
            .setPositiveButton(tr("Kirim log", "Send log")) { _, _ -> shareLog() }
            .setNeutralButton(tr("Hapus log", "Clear log")) { _, _ -> AppLog.clear(); toast(tr("Log dihapus", "Log cleared")) }
            .setNegativeButton(tr("Tutup", "Close"), null)
            .show()
    }

    private fun shareLog() {
        try {
            val f = AppLog.exportFile(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Log Steamod Updater ${AppLog.buildLabel}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, tr("Kirim log ke…", "Send log to…")))
        } catch (e: Exception) {
            AppLog.e("Gagal membagikan log", e)
            Toast.makeText(this, tr("Gagal membagikan log: ${e.message}", "Couldn't share the log: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    /** Durasi yang enak dibaca: 45 dtk, 2 mnt 5 dtk, 1 jam 3 mnt. */
    private fun fmtDur(secs: Long): String = when {
        secs < 60 -> "$secs ${tr("dtk", "s")}"
        secs < 3600 -> "${secs / 60} ${tr("mnt", "min")}" + if (secs % 60 != 0L) " ${secs % 60} ${tr("dtk", "s")}" else ""
        else -> "${secs / 3600} ${tr("jam", "h")}" + if ((secs % 3600) / 60 != 0L) " ${(secs % 3600) / 60} ${tr("mnt", "min")}" else ""
    }

    private fun fmt(t: Long) = if (t <= 0) "-" else dateFmt.format(Date(t * 1000))

    private var lastRender = 0L
    private var renderPending = false

    /** Gambar ulang daftar mod, paling sering ~3x per detik (supaya tidak lag saat cek banyak mod). */
    private fun renderList() {
        if (renderPending) return
        val wait = 350L - (android.os.SystemClock.uptimeMillis() - lastRender)
        if (wait <= 0) {
            doRenderList()
        } else {
            renderPending = true
            ui.postDelayed({ renderPending = false; doRenderList() }, wait)
        }
    }

    private fun doRenderList() {
        lastRender = android.os.SystemClock.uptimeMillis()
        listContainer.removeAllViews()
        // urutan: merah (gagal / ID belum ketemu) → kuning (ada update) → lainnya
        val list = visible.sortedBy { severity(it) }
        val anim = animateList
        animateList = false
        if (list.isEmpty()) {
            val hint = if (game == Game.ZOMBOID)
                "> no mods loaded for PROJECT ZOMBOID\n\n" + tr(
                    "[+] FOLDER : pilih folder Zomboid/mods (mis. di Zomdroid). ID Workshop dicari otomatis.\n" +
                    "[+] LINK/ID: tempel link Workshop untuk download baru.\n\n> lalu tekan SCAN UPDATE_",
                    "[+] FOLDER : pick the Zomboid/mods folder (e.g. in Zomdroid). Workshop IDs are found automatically.\n" +
                    "[+] LINK/ID: paste a Workshop link to download a new mod.\n\n> then press SCAN UPDATE_")
            else
                "> no mods loaded for RIMWORLD\n\n" + tr(
                    "[+] FOLDER : pilih folder 1 mod, atau folder Mods berisi banyak mod.\n" +
                    "[+] ZIP    : pilih zip mod lama.\n" +
                    "[+] LINK/ID: tempel link Workshop untuk download baru.\n\n> lalu tekan SCAN UPDATE_",
                    "[+] FOLDER : pick one mod folder, or a Mods folder with many mods.\n" +
                    "[+] ZIP    : pick an old mod zip.\n" +
                    "[+] LINK/ID: paste a Workshop link to download a new mod.\n\n> then press SCAN UPDATE_")
            val v = ScanReveal(this).apply {
                addView(term(hint, 12f, Hx.DIM).apply { setPadding(px(2), px(18), px(2), 0); setLineSpacing(px(3).toFloat(), 1f) })
            }
            listContainer.addView(v)
            if (anim) v.reveal(150, 600)
            return
        }
        list.forEachIndexed { i, m ->
            val card = try {
                modCard(m)
            } catch (e: Exception) {
                AppLog.e("Gagal menampilkan kartu ${m.workshopId} (${m.name}) status='${m.status}'", e)
                return@forEachIndexed
            }
            listContainer.addView(card)
            if (anim && i < 14) card.reveal(120L + i * 70L, 380)
        }
    }

    private fun isFailed(m: ModEntry) =
        StatusKind.isFailed(m.status)

    private fun severity(m: ModEntry) = when {
        isFailed(m) -> 0
        m.updateAvailable -> 1
        else -> 2
    }

    private fun modCard(m: ModEntry): ScanReveal {
        val accent = when {
            m.updateAvailable -> Hx.AMBER
            StatusKind.isFailed(m.status) -> Hx.RED
            else -> Hx.GREEN
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(10))
        }
        box.addView(term(m.displayName + "  ⧉", 14f, Hx.BRIGHT, bold = true).apply {
            // ketuk = salin nama, tekan lama = menu (salin folder, cari di Workshop, buka halaman)
            setOnClickListener { copyText(m.displayName, tr("Nama mod", "Mod name")) }
            setOnLongClickListener { modMenu(m); true }
        })
        val src = when (m.sourceType) {
            "folder" -> "folder"
            "zip" -> "zip"
            else -> "link"
        }
        val ver = when {
            m.installedVersion.isEmpty() -> "UNKNOWN"
            m.installedVersion == OUTDATED -> "OUTDATED"
            m.remoteVersion.isEmpty() || m.installedVersion == m.remoteVersion -> "MATCH"
            else -> "OUTDATED"
        }
        val size = if (m.remoteSize > 0) " · ${fmtSize(m.remoteSize)}" else ""
        val extra = if (m.game == Game.ZOMBOID.key) {
            "\nmod_folder: ${m.subPath.ifBlank { "-" }}" + (m.remoteTitle?.let { "\nworkshop_item: $it" } ?: "")
        } else ""
        box.addView(term(
            "id:${m.workshopId.ifBlank { "?" }} · src:$src$size\nworkshop_updated: ${fmt(m.remoteTime)}\nlocal_version: $ver$extra",
            10.5f, Hx.DIM,
        ).apply { setLineSpacing(px(2).toFloat(), 1f); setPadding(0, px(4), 0, 0) })
        val statusText = when {
            m.updateAvailable -> "!! " + StatusText.localize(m.status)
            StatusKind.isOk(m.status) -> "✓ " + StatusText.localize(m.status)
            StatusKind.isFailed(m.status) -> "✗ " + StatusText.localize(m.status)
            else -> "> " + StatusText.localize(m.status)
        }
        box.addView(term(statusText, 11.5f, accent, bold = true).apply {
            setPadding(0, px(6), 0, px(6))
            Hx.glow(this, accent, 8f)
        })
        val label = if (m.sourceType == "none") "DOWNLOAD" else "UPDATE"
        val bUpdate = button(label, small = true) { updateMods(listOf(m)) }.apply {
            isEnabled = !busy && m.workshopId.isNotBlank() && (m.updateAvailable || m.sourceType == "none" || m.installedVersion.isEmpty())
        }
        val bForce = button("FORCE", small = true) {
            CmdDialog(this).setTitle(tr("Download ulang?", "Download again?"))
                .setMessage(tr("Download ulang ${m.displayName} dari Workshop walaupun sudah terbaru?", "Download ${m.displayName} from the Workshop again even though it's up to date?"))
                .setPositiveButton("Ya") { _, _ -> updateMods(listOf(m)) }
                .setNegativeButton(tr("Batal", "Cancel"), null).show()
        }.apply { isEnabled = !busy && m.workshopId.isNotBlank() }
        val needSearch = m.game == Game.ZOMBOID.key && m.sourceType == "folder" && (m.workshopId.isBlank() || StatusKind.isSuspect(m.status))
        val bId = button(if (needSearch) "FIND ID" else "EDIT ID", small = true) {
            if (needSearch) {
                CmdDialog(this).setTitle("ID Workshop: ${m.displayName}")
                    .setPositiveButton(tr("Cari otomatis", "Auto search")) { _, _ -> reverifyIds(listOf(m)) }
                    .setNegativeButton(tr("Isi manual", "Enter manually")) { _, _ -> askEditId(m) }
                    .setNeutralButton(tr("Batal", "Cancel"), null).show()
            } else {
                askEditId(m)
            }
        }.apply { isEnabled = !busy }
        val bDel = button("DEL", small = true) { askDelete(m) }.apply { isEnabled = !busy }
        box.addView(row(bUpdate, bForce, bId, bDel).apply { setPadding(0, 0, 0, 0) })

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = boxBg()
            addView(View(this@MainActivity).apply {
                setBackgroundColor(accent)
            }, LinearLayout.LayoutParams(px(2), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = px(8); bottomMargin = px(8)
            })
            addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return ScanReveal(this).apply {
            addView(inner)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(4); bottomMargin = px(4)
            }
        }
    }

    // ------------------------------------------------------------------ salin & cari

    /** Variasi kata kunci pencarian: judul mod.info, nama folder, nama folder dipisah per kata, id mod. */
    private fun searchQueries(m: ModEntry): List<String> {
        fun split(s: String) = s.replace(Regex("""([a-z])([A-Z])"""), "$1 $2")
            .replace(Regex("""([A-Za-z])(\d)"""), "$1 $2")
            .replace(Regex("""[_\-.]+"""), " ").trim()
        return listOf(m.name, m.subPath, split(m.subPath), m.modId, split(m.modId), split(m.name))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
    }

    private fun copyText(text: String, label: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, tr("$label disalin: $text", "$label copied: $text"), Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(tr("Tidak bisa membuka browser: ${e.message}", "Can't open the browser: ${e.message}"))
        }
    }

    private fun workshopSearchUrl(q: String) =
        "https://steamcommunity.com/workshop/browse/?appid=${game.appId}&searchtext=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&browsesort=textsearch&section=readytouseitems"

    private fun modMenu(m: ModEntry) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += tr("Salin nama mod", "Copy mod name") to { copyText(m.displayName, tr("Nama mod", "Mod name")) }
        if (m.subPath.isNotBlank() && m.subPath != m.displayName) items += tr("Salin nama folder", "Copy folder name") to { copyText(m.subPath, tr("Nama folder", "Folder name")) }
        if (m.modId.isNotBlank()) items += tr("Salin id mod (mod.info)", "Copy mod id (mod.info)") to { copyText(m.modId, tr("ID mod", "Mod ID")) }
        if (m.workshopId.isNotBlank()) items += tr("Salin ID Workshop", "Copy Workshop ID") to { copyText(m.workshopId, tr("ID Workshop", "Workshop ID")) }
        items += tr("Cari di Steam Workshop", "Search Steam Workshop") to { openUrl(workshopSearchUrl(m.displayName)) }
        if (m.workshopId.isNotBlank()) items += tr("Buka halaman Workshop", "Open Workshop page") to {
            openUrl("https://steamcommunity.com/sharedfiles/filedetails/?id=${m.workshopId}")
        }
        if (m.game == Game.ZOMBOID.key && m.sourceType == "folder") items += tr("Cari ID otomatis lagi", "Auto-find ID again") to { reverifyIds(listOf(m)) }
        items += tr("Isi / ubah ID Workshop", "Set / change Workshop ID") to { askEditId(m) }
        CmdDialog(this)
            .setTitle(m.displayName)
            .setItems(items.map { "> " + it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    // ------------------------------------------------------------------ hapus mod

    private fun askDelete(m: ModEntry) {
        val hasFiles = m.sourceType == "folder" || m.sourceType == "zip"
        val b = CmdDialog(this)
            .setTitle("DEL · ${m.displayName}")
            .setMessage(
                if (hasFiles) tr("Hapus dari daftar saja, atau hapus juga file mod-nya dari HP?\n\n" +
                    "Kalau file dihapus, mod ini hilang dari game.", "Remove from the list only, or also delete the mod files from your phone?\n\n" +
                    "If the files are deleted, this mod disappears from the game.") + (if (m.sourceType == "zip") tr("\n(file zip-nya yang dihapus)", "\n(the zip file is deleted)") else "")
                else tr("Hapus mod ini dari daftar?", "Remove this mod from the list?")
            )
            .setNeutralButton(tr("Batal", "Cancel"), null)
            .setNegativeButton(tr("Dari daftar saja", "List only")) { _, _ -> removeFromList(m) }
        if (hasFiles) {
            b.setPositiveButton(tr("Hapus file juga", "Delete files too")) { _, _ ->
                CmdDialog(this)
                    .setTitle(tr("Yakin hapus permanen?", "Delete permanently?"))
                    .setMessage(tr("Folder/file mod \"${m.displayName}\" akan dihapus dari HP dan tidak bisa dikembalikan.", "The mod folder/file \"${m.displayName}\" will be deleted from your phone and can't be restored."))
                    .setPositiveButton(tr("Ya, hapus", "Yes, delete")) { _, _ -> deleteModFiles(m) }
                    .setNegativeButton(tr("Batal", "Cancel"), null)
                    .show()
            }
        }
        b.show()
    }

    private fun removeFromList(m: ModEntry) {
        mods.remove(m)
        store.save(mods)
        AppLog.i("Dihapus dari daftar: ${m.displayName} (${m.workshopId})")
        renderList()
    }

    private fun deleteModFiles(m: ModEntry) {
        if (busy) return
        lifecycleScope.launch {
            setBusy(true, tr("Hapus mod", "Delete mod"))
            panel.start("DELETE · ${game.title.uppercase()}", m.displayName, "DELETE")
            try {
                val ok = withContext(Dispatchers.IO) {
                    when (m.sourceType) {
                        "folder" -> {
                            val dir = ModFiles.locateFolder(this@MainActivity, m)
                            if (dir == null) {
                                // sudah dicek ulang: foldernya memang tidak ada → cukup buang dari daftar
                                panel.log("SCAN", tr("folder ${m.subPath} tidak ada (sudah dicek ulang)", "folder ${m.subPath} not found (rescanned)"))
                                return@withContext true
                            }
                            panel.log("RM", "-rf ${m.subPath.ifBlank { dir.name ?: "mod" }}/")
                            dir.delete()
                        }
                        "zip" -> {
                            val doc = DocumentFile.fromSingleUri(this@MainActivity, Uri.parse(m.sourceUri))
                                ?: throw Exception(tr("File zip tidak ditemukan", "Zip file not found"))
                            panel.log("RM", doc.name ?: "mod.zip")
                            doc.delete()
                        }
                        else -> true
                    }
                }
                if (!ok) throw Exception(tr("Android menolak menghapus (izin folder hilang?)", "Android refused to delete (folder permission lost?)"))
                panel.log("DONE", tr("${m.displayName} dihapus", "${m.displayName} deleted"), "✓")
                AppLog.i("File mod dihapus: ${m.displayName} (${m.workshopId}) ${m.sourceUri}/${m.subPath}")
                mods.remove(m)
                store.save(mods)
                panel.badge(tr("SELESAI", "DONE"))
                panel.fraction(1f)
                toast(tr("${m.displayName} dihapus", "${m.displayName} deleted"))
            } catch (e: Exception) {
                AppLog.e("Gagal hapus file mod ${m.displayName}", e)
                panel.log("FAIL", e.message ?: tr("gagal", "failed"), error = true)
                toast(tr("Gagal hapus: ${e.message}", "Delete failed: ${e.message}"))
            }
            setBusy(false)
        }
    }

    // ------------------------------------------------------------------ add mods

    private fun upsert(entry: ModEntry) {
        val zomboid = entry.game == Game.ZOMBOID.key
        fun same(it: ModEntry) = it.game == entry.game && (
            (it.sourceUri != null && it.sourceUri == entry.sourceUri && it.subPath == entry.subPath) ||
                // Zomboid: folder mod dengan nama sama = mod yang sama, walau dipilih lewat jalur folder lain
                (zomboid && it.sourceType == "folder" && entry.subPath.isNotBlank() && it.subPath.equals(entry.subPath, ignoreCase = true)) ||
                // RimWorld: satu ID = satu mod. Zomboid: satu item Workshop bisa berisi beberapa folder mod.
                (!zomboid && entry.workshopId.isNotBlank() && it.workshopId == entry.workshopId)
            )
        val existing = mods.indexOfFirst { same(it) }
        if (existing >= 0) {
            // pertahankan info versi yang sudah diketahui untuk folder yang sama
            val old = mods[existing]
            // buang duplikat lain (sisa tambah folder sebelumnya)
            for (i in mods.indices.reversed()) if (i != existing && same(mods[i])) mods.removeAt(i)
            val oldSameFolder = (old.sourceUri == entry.sourceUri || zomboid) && old.subPath.equals(entry.subPath, ignoreCase = true)
            if (oldSameFolder && entry.workshopId.isBlank() && old.workshopId.isNotBlank()) {
                // folder yang sama ditambah ulang: ID (termasuk yang diisi manual) & info versi tetap dipakai
                entry.workshopId = old.workshopId
                entry.contentPrefix = old.contentPrefix
                entry.installedVersion = old.installedVersion
                entry.remoteVersion = old.remoteVersion
                entry.remoteTitle = old.remoteTitle
                entry.remoteTime = old.remoteTime
                entry.remoteSize = old.remoteSize
                entry.remoteFileCount = old.remoteFileCount
                entry.updateAvailable = old.updateAvailable
                entry.status = old.status
            }
            mods[mods.indexOfFirst { it === old }] = entry
        } else {
            mods.add(entry)
        }
    }

    private fun addFromFolder(uri: Uri) {
        persist(uri)
        lifecycleScope.launch {
            setBusy(true, tr("Membaca folder…", "Reading folder…"))
            if (game == Game.ZOMBOID) {
                addZomboidFolder(uri)
                return@launch
            }
            try {
                val found = withContext(Dispatchers.IO) { ModFiles.scanTree(this@MainActivity, uri) }
                if (found.isEmpty()) {
                    toast(tr("Tidak ada mod di folder itu (tidak ketemu folder About).", "No mods in that folder (no About folder found)."))
                } else {
                    found.forEach {
                        upsert(ModEntry(
                            workshopId = it.workshopId ?: "",
                            name = it.name,
                            sourceType = "folder",
                            sourceUri = uri.toString(),
                            subPath = it.subPath,
                            localTime = it.localTime,
                            status = if (it.workshopId == null) tr("ID Workshop tidak ketemu, tekan Ubah ID", "Workshop ID not found, tap EDIT ID") else tr("Belum dicek", "Not checked"),
                        ))
                    }
                    store.save(mods)
                    toast(tr("${found.size} mod ditambahkan", "${found.size} mods added"))
                }
            } catch (e: Exception) {
                AppLog.e("Gagal membaca folder $uri", e)
                toast(tr("Gagal membaca folder: ${e.message}", "Couldn't read folder: ${e.message}"))
            }
            setBusy(false)
            if (visible.any { StatusKind.isUnchecked(it.status) }) checkUpdates()
        }
    }

    /** Tambah folder mod Project Zomboid, lalu cari ID Workshop-nya otomatis. */
    private suspend fun addZomboidFolder(uri: Uri) {
        val found = try {
            panel.start("SCAN · PROJECT ZOMBOID", tr("Membaca folder mod", "Reading mod folders"), "SCAN")
            withContext(Dispatchers.IO) { ModFiles.scanZomboid(this@MainActivity, uri) { progress(it) } }
        } catch (e: Exception) {
            AppLog.e("Gagal membaca folder Zomboid $uri", e)
            toast(tr("Gagal membaca folder: ${e.message}", "Couldn't read folder: ${e.message}"))
            setBusy(false)
            return
        }
        if (found.isEmpty()) {
            toast(tr("Tidak ada mod Zomboid di folder itu (tidak ketemu mod.info).", "No Zomboid mods in that folder (no mod.info found)."))
            setBusy(false)
            return
        }
        found.forEach {
            upsert(ModEntry(
                workshopId = "",
                name = it.name,
                sourceType = "folder",
                sourceUri = uri.toString(),
                subPath = it.subPath,
                status = tr("Mencari ID Workshop…", "Searching Workshop ID…"),
                game = Game.ZOMBOID.key,
                modId = it.modId,
            ))
        }
        store.save(mods)
        panel.log("SCAN", tr("${found.size} folder mod ditemukan", "${found.size} mod folders found"), "OK")
        AppLog.i("Zomboid: ${found.size} mod di $uri")
        setBusy(false)
        val noId = visible.filter { it.workshopId.isBlank() && it.sourceType == "folder" }
        if (noId.isNotEmpty()) discoverZomboidIds(noId) else checkUpdates()
    }

    /**
     * Cari ID Workshop untuk folder mod Zomboid: cari nama mod di Workshop, lalu pastikan item itu
     * benar-benar berisi folder "mods/<nama folder>/" lewat daftar file resminya.
     */
    /** ID lama yang ikut dicek ulang saat "Cari ID otomatis lagi". */
    private val hintIds = HashMap<ModEntry, Long>()

    /** Cari ulang ID: ID sekarang dikosongkan tapi tetap ikut dibandingkan sebagai kandidat. */
    private fun reverifyIds(list: List<ModEntry>) {
        if (busy) return
        list.forEach { m ->
            m.workshopId.toLongOrNull()?.let { hintIds[m] = it }
            m.workshopId = ""
            m.contentPrefix = ""
            m.installedVersion = ""
            m.updateAvailable = false
        }
        discoverZomboidIds(list)
    }

    private fun discoverZomboidIds(list: List<ModEntry>) {
        if (busy || list.isEmpty()) return
        lifecycleScope.launch {
            setBusy(true, tr("Cari ID Workshop", "Find Workshop ID"))
            val t0 = System.currentTimeMillis()
            panel.start(tr("CARI ID WORKSHOP · ${list.size} MOD", "FIND WORKSHOP ID · ${list.size} MODS"), "Steam Workshop", "SEARCH")
            if (!ensureSteam()) {
                list.forEach { it.status = tr("ID belum dicari. Login Steam lalu tekan CARI ID.", "ID not searched yet. Log in to Steam, then tap FIND ID.") }
                store.save(mods)
                setBusy(false)
                return@launch
            }
            refreshSteamStatus()
            // 1) cari kandidat lewat pencarian Workshop
            val cands = java.util.concurrent.ConcurrentHashMap<ModEntry, List<Long>>()
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            withContext(Dispatchers.IO) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                try {
                    pool.invokeAll(list.map { m ->
                        java.util.concurrent.Callable {
                            val ids = LinkedHashSet<Long>()
                            hintIds.remove(m)?.let { ids += it }
                            for (q in searchQueries(m)) {
                                try {
                                    WorkshopSearch.search(game.appId, q, 4).forEach { ids += it.toLong() }
                                } catch (e: Exception) {
                                    AppLog.w("Cari '$q' gagal: ${e.message}")
                                }
                                if (ids.size >= 8) break
                            }
                            cands[m] = ids.toList()
                            val n = done.incrementAndGet()
                            panel.log("SEARCH", tr("${m.name} → ${ids.size} kandidat", "${m.name} → ${ids.size} candidates"), if (ids.isEmpty()) null else "OK")
                            setFraction(0.3f * n / list.size)
                            panel.stats(
                                "$n/${list.size}" to tr("dicari", "searched"),
                                fmtDur((System.currentTimeMillis() - t0) / 1000) to tr("berjalan", "elapsed"),
                            )
                        }
                    }).forEach { it.get() }
                } finally {
                    pool.shutdown()
                }
            }
            // 2) verifikasi lewat daftar file (kandidat ke-1, lalu ke-2, ke-3 untuk yang belum cocok)
            panel.badge("VERIFY")
            panel.title(tr("Cocokkan isi item Workshop", "Matching Workshop item contents"))
            val mfDir = File(cacheDir, "mfz")
            var matched = 0
            // skor kecocokan terbaik per mod: (ID, awalan folder, skor 0..1)
            val best = HashMap<ModEntry, Triple<Long, String, Double>>()
            val localOf = HashMap<ModEntry, Map<String, Long>>()
            for (round in 0 until 5) {
                // lanjut cari kandidat lain selama belum ada yang benar-benar cocok
                val pending = list.filter { it.workshopId.isBlank() && (best[it]?.third ?: 0.0) < 0.9 }
                if (pending.isEmpty()) break
                val ids = pending.mapNotNull { cands[it]?.getOrNull(round) }.filter { !manifestCache.containsKey(it) }.distinct()
                if (ids.isNotEmpty()) {
                    panel.caption(tr("VERIFY · PUTARAN ${round + 1} · ${ids.size} ITEM", "VERIFY · ROUND ${round + 1} · ${ids.size} ITEMS"))
                    try {
                        val res = SteamManager.fetchManifests(
                            this@MainActivity, ids, mfDir,
                            onStatus = { progress(it) },
                            onFraction = { f -> setFraction(0.3f + 0.7f * (round + f) / 5f) },
                            onManifest = { id, r ->
                                r.onSuccess { manifestCache[id] = it; panel.log("MANIFEST", "$id · ${it.size} files", "OK") }
                                r.onFailure { panel.log("MANIFEST", "$id · ${it.message}", error = true) }
                            },
                        )
                        res.forEach { (id, r) -> r.getOrNull()?.let { manifestCache[id] = it } }
                        manifestCacheTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        AppLog.e("Verifikasi ID gagal", e)
                    }
                }
                withContext(Dispatchers.IO) {
                    pending.forEach { m ->
                        val loc = localOf.getOrPut(m) {
                            try { ModFiles.locateFolder(this@MainActivity, m)?.let { ModFiles.localSizes(this@MainActivity, it) } } catch (_: Exception) { null } ?: emptyMap()
                        }
                        // mod lokal B42 → item yang hanya berisi versi B41 dilewati
                        val localB42 = ModFiles.isB42Layout(loc.keys)
                        for (cid in cands[m].orEmpty()) {
                            val mf = manifestCache[cid] ?: continue
                            val pre = ModFiles.findModPrefix(mf, m.subPath) ?: continue
                            val scoped = ModFiles.scope(mf, pre)
                            if (localB42 && !ModFiles.isB42Layout(scoped.map { it.path })) {
                                panel.log("SKIP", "${m.name} → $cid (B41)")
                                continue
                            }
                            val sc = ModFiles.similarity(loc, scoped, mf.size)
                            if (sc > (best[m]?.third ?: -1.0)) best[m] = Triple(cid, pre, sc)
                        }
                    }
                }
            }
            mfDir.deleteRecursively()
            // pilih kandidat dengan isi paling mirip; kalau terlalu beda, jangan dipasang (bisa versi B41/modpack lain)
            list.filter { it.workshopId.isBlank() }.forEach { m ->
                val b = best[m] ?: return@forEach
                val pct = (b.third * 100).toInt().coerceAtMost(100)
                if (b.third >= 0.5) {
                    m.workshopId = b.first.toString()
                    m.contentPrefix = b.second
                    m.installedVersion = ""
                    m.coverageChecked = false
                    m.status = tr("Belum dicek", "Not checked")
                    matched++
                    panel.log("MATCH", "${m.name} → ${b.first} ($pct%)", "OK")
                } else {
                    m.status = tr("ID Workshop belum pasti: kandidat terbaik ${b.first} hanya $pct% cocok. Cek di Workshop lalu isi manual.",
                        "Workshop ID uncertain: best candidate ${b.first} is only $pct% similar. Check the Workshop and enter it manually.")
                    panel.log("WEAK", "${m.name} → ${b.first} ($pct%)", error = true)
                }
            }
            store.save(mods)
            renderList()
            list.filter { it.workshopId.isBlank() && best[it] == null }.forEach {
                val n = cands[it].orEmpty().size
                it.status = if (n == 0)
                    tr("ID Workshop belum ketemu: tidak ada hasil pencarian. Tekan lama judul → Cari di Workshop.", "Workshop ID not found: no search results. Long-press the title → Search Steam Workshop.")
                else
                    tr("ID Workshop belum ketemu: $n hasil dicek, isinya tidak cocok. Tekan lama judul → Cari di Workshop.", "Workshop ID not found: $n results checked, contents don't match. Long-press the title → Search Steam Workshop.")
                panel.log("MISS", "${it.name} (${if (n == 0) "no result" else "$n no match"})", error = true)
            }
            store.save(mods)
            AppLog.i("Cari ID Zomboid: $matched/${list.size} cocok dalam ${(System.currentTimeMillis() - t0) / 1000} dtk")
            panel.caption(tr("CARI ID · SELESAI", "FIND ID · DONE"))
            panel.title(tr("$matched dari ${list.size} ketemu", "$matched of ${list.size} found"))
            panel.badge(tr("SELESAI", "DONE"))
            panel.fraction(1f)
            setBusy(false)
            // cek update juga untuk mod yang ID-nya sudah tersimpan sebelumnya
            if (matched > 0 || visible.any { it.workshopId.isNotBlank() }) checkUpdates()
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
            if (game == Game.ZOMBOID) {
                toast(tr("File zip belum didukung untuk Project Zomboid. Pakai [+] FOLDER.", "Zip files aren't supported for Project Zomboid yet. Use [+] FOLDER."))
                return@launch
            }
            setBusy(true, tr("Membaca zip…", "Reading zip…"))
            var added = 0
            for (uri in uris) {
                try {
                    val name = fileName(uri)
                    val s = withContext(Dispatchers.IO) { ModFiles.scanZip(this@MainActivity, uri, name) }
                    if (s == null) {
                        toast("${name ?: "Zip"}: " + tr("bukan mod RimWorld (tidak ada About/About.xml)", "not a RimWorld mod (no About/About.xml)"))
                        continue
                    }
                    upsert(ModEntry(
                        workshopId = s.workshopId ?: "",
                        name = s.name,
                        sourceType = "zip",
                        sourceUri = uri.toString(),
                        zipPrefix = s.zipPrefix,
                        localTime = s.localTime,
                        status = if (s.workshopId == null) tr("ID Workshop tidak ketemu, tekan Ubah ID", "Workshop ID not found, tap EDIT ID") else tr("Belum dicek", "Not checked"),
                    ))
                    added++
                } catch (e: Exception) {
                    AppLog.e("Gagal membaca zip $uri", e)
                    toast(tr("Gagal membaca zip: ${e.message}", "Couldn't read zip: ${e.message}"))
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
        CmdDialog(this)
            .setTitle(tr("Tambah dari link Workshop", "Add from Workshop link"))
            .setMessage(tr("Tempel link atau ID mod. Bisa beberapa sekaligus (pisahkan dengan baris baru). Hasilnya disimpan di \"Folder hasil\".", "Paste a mod link or ID. You can add several at once (one per line). Downloads are saved to the output folder."))
            .setView(padded(input))
            .setPositiveButton(tr("Tambah", "Add")) { _, _ ->
                val ids = input.text.toString().split(Regex("""[\s,]+""")).mapNotNull { WorkshopApi.parseId(it) }
                if (ids.isEmpty()) {
                    toast(tr("Link/ID tidak dikenali", "Link/ID not recognized"))
                } else {
                    ids.forEach { id ->
                        if (visible.none { it.workshopId == id && it.sourceType == "none" }) {
                            mods.add(ModEntry(workshopId = id, name = "Mod $id", sourceType = "none", game = game.key))
                        }
                    }
                    store.save(mods)
                    renderList()
                    checkUpdates()
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun askEditId(m: ModEntry) {
        val input = EditText(this).apply {
            setText(m.workshopId)
            hint = tr("Link atau ID Workshop", "Workshop link or ID")
        }
        CmdDialog(this)
            .setTitle("ID Workshop: ${m.displayName}")
            .setMessage(tr("Tempel link atau ID Workshop. Belum tahu? Tekan \"Cari\" untuk mencari nama mod ini di Steam Workshop, lalu salin link-nya.", "Paste a Workshop link or ID. Don't know it? Tap \"Search\" to look up this mod on Steam Workshop, then copy its link."))
            .setNeutralButton(tr("Cari", "Search")) { _, _ -> openUrl(workshopSearchUrl(m.displayName)) }
            .setView(padded(input))
            .setPositiveButton(tr("Simpan", "Save")) { _, _ ->
                val id = WorkshopApi.parseId(input.text.toString())
                if (id == null) {
                    toast(tr("Link/ID tidak dikenali", "Link/ID not recognized"))
                } else {
                    m.workshopId = id
                    m.status = tr("Belum dicek", "Not checked")
                    m.remoteTime = 0
                    m.contentPrefix = ""
                    m.installedVersion = ""
                    store.save(mods)
                    renderList()
                    checkUpdates()
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(px(20), px(8), px(20), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ check

    private fun checkUpdates() {
        if (busy) return
        val targets = visible.filter { it.workshopId.isNotBlank() }
        if (targets.isEmpty()) {
            toast(tr("Belum ada mod dengan ID Workshop", "No mods with a Workshop ID yet"))
            return
        }
        lifecycleScope.launch {
            setBusy(true, "Steam Workshop")
            val t0 = System.currentTimeMillis()
            panel.start(tr("CEK UPDATE · ${targets.size} MOD", "CHECK UPDATES · ${targets.size} MODS"), "Steam Workshop", tr("CEK VERSI", "VERSIONS"))
            panel.log("QUERY", "PublishedFileDetails × ${targets.size}")
            try {
                AppLog.i("Cek update ${targets.size} mod")
                val details = withContext(Dispatchers.IO) { WorkshopApi.getDetails(targets.map { it.workshopId }) }
                val needContent = mutableListOf<ModEntry>()
                targets.forEach { m ->
                    val d = details[m.workshopId]
                    when {
                        d == null || !d.ok -> {
                            m.status = tr("Error: mod tidak ditemukan di Workshop (dihapus/privat?)", "Error: mod not found on the Workshop (removed/private?)")
                            m.updateAvailable = false
                        }
                        d.appId != 0 && d.appId != game.appId -> {
                            m.status = tr("Error: ini bukan mod ${game.title}", "Error: this is not a ${game.title} mod")
                            m.updateAvailable = false
                        }
                        else -> {
                            m.remoteTitle = d.title
                            m.remoteTime = d.timeUpdated
                            m.remoteVersion = d.contentId
                            m.remoteSize = d.fileSize
                            when {
                                // versi lama yang belum pernah dicocokkan isinya: cek isi sekali (deteksi ID salah)
                                m.installedVersion == OUTDATED && !m.coverageChecked && m.sourceType != "none" -> needContent += m
                                m.installedVersion.isNotEmpty() -> {
                                    m.updateAvailable = m.installedVersion != d.contentId
                                    m.status = if (m.updateAvailable) tr("Ada update!", "Update available!") else tr("Terbaru ✓", "Up to date ✓")
                                }
                                m.sourceType == "none" -> {
                                    m.updateAvailable = true
                                    m.status = tr("Siap di-download", "Ready to download")
                                }
                                else -> needContent += m
                            }
                        }
                    }
                }
                store.save(mods)
                renderList()
                panel.log("RESULT", tr("${details.size}/${targets.size} mod ditemukan", "${details.size}/${targets.size} mods found"), "OK")

                // Mod yang versinya belum diketahui: bandingkan isi file dengan daftar file resmi di Steam.
                if (needContent.isNotEmpty()) {
                    AppLog.i("${needContent.size} mod perlu dicek isinya")
                    ModFiles.clearRescanCache()
                    showProgress(tr("${needContent.size} mod perlu dicek isinya, butuh login Steam…", "${needContent.size} mods need a content check, Steam login required…"))
                    if (!ensureSteam()) {
                        needContent.forEach {
                            it.updateAvailable = false
                            it.status = tr("Versi belum diketahui. Login Steam lalu tekan Cek update lagi.", "Version unknown. Log in to Steam, then check again.")
                        }
                    } else {
                        refreshSteamStatus()
                        // 1) Ambil semua daftar file sekaligus dalam satu sesi Steam (jauh lebih cepat).
                        val mfDir = File(cacheDir, "mf")
                        panel.title(tr("Daftar file resmi", "Official file lists"))
                        panel.badge("MANIFEST")
                        val nameOf = needContent.associate { it.workshopId.toLong() to it.displayName }
                        var mfDone = 0
                        // pakai daftar file yang baru diambil (mis. saat cari ID) kalau masih segar
                        val fresh = System.currentTimeMillis() - manifestCacheTime < 15 * 60_000
                        val cached = if (fresh) needContent.map { it.workshopId.toLong() }.filter { manifestCache.containsKey(it) }.toSet() else emptySet()
                        val toFetch = needContent.map { it.workshopId.toLong() }.distinct().filter { it !in cached }
                        val manifests = try {
                            cached.associateWith { Result.success(manifestCache[it]!!) } +
                            if (toFetch.isEmpty()) emptyMap() else SteamManager.fetchManifests(
                                this@MainActivity,
                                toFetch,
                                mfDir,
                                onStatus = { progress(it) },
                                onFraction = { setFraction(it * 0.5f) },
                                onManifest = { id, r ->
                                    mfDone++
                                    panel.stats(
                                        "$mfDone/${needContent.size}" to tr("daftar file", "file lists"),
                                        "4" to tr("jalur paralel", "parallel lanes"),
                                        fmtDur((System.currentTimeMillis() - t0) / 1000) to tr("berjalan", "elapsed"),
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
                        var removedDup = 0
                        panel.badge(tr("CEK ISI", "CONTENT"))
                        needContent.forEachIndexed { i, m ->
                            val prefix = ""
                            panel.caption(tr("CEK ISI · ${i + 1} DARI ${needContent.size}", "CONTENT CHECK · ${i + 1} OF ${needContent.size}"))
                            panel.title(m.displayName)
                            panel.stats(
                                "${i + 1}/${needContent.size}" to "mod",
                                "$diffMods" to tr("perlu update", "need update"),
                                fmtDur((System.currentTimeMillis() - t0) / 1000) to tr("berjalan", "elapsed"),
                            )
                            progress(tr("Membandingkan file…", "Comparing files…"))
                            setFraction(0.5f + 0.5f * i / needContent.size)
                            try {
                                val remote = manifests[m.workshopId.toLong()]?.getOrThrow()
                                    ?: throw Exception(tr("daftar file tidak didapat dari Steam", "file list not received from Steam"))
                                if (remote.isEmpty()) throw Exception(tr("daftar file dari Steam kosong", "file list from Steam is empty"))
                                val r = compareContent(m, remote) { progress(prefix + it) }
                                m.remoteFileCount = r.total
                                m.coverageChecked = true
                                if (!r.same && r.coverage < 0.5) {
                                    // sebagian besar file lokal tidak ada di item ini → hampir pasti item Workshop yang salah
                                    val pct = (r.coverage * 100).toInt()
                                    m.updateAvailable = false
                                    m.installedVersion = ""
                                    m.status = tr("ID kemungkinan salah: hanya $pct% file lokal ada di item Workshop ini (versi B41/B42 lain atau modpack?). Tekan FIND ID.",
                                        "ID probably wrong: only $pct% of local files exist in this Workshop item (other B41/B42 version or a modpack?). Tap FIND ID.")
                                    panel.log("WRONG", "${m.displayName} · $pct% match", error = true)
                                    AppLog.i("${m.displayName}: ID ${m.workshopId} kemungkinan salah, cakupan $pct%")
                                } else if (r.same) {
                                    m.installedVersion = m.remoteVersion
                                    m.updateAvailable = false
                                    m.status = tr("Terbaru ✓ (isi sama dengan Workshop)", "Up to date ✓ (same contents as Workshop)")
                                    panel.log("SHA1", "${m.displayName} · ${r.total} files", "MATCH")
                                } else {
                                    diffMods++
                                    panel.log("DIFF", tr("${m.displayName} · ${r.different.size}/${r.total} beda", "${m.displayName} · ${r.different.size}/${r.total} differ"), "UPDATE")
                                    m.updateAvailable = true
                                    // Tandai versi lama, supaya cek berikutnya tidak perlu bandingkan isi lagi.
                                    m.installedVersion = OUTDATED
                                    m.status = tr("Ada update! (${r.different.size} dari ${r.total} file berbeda)", "Update available! (${r.different.size} of ${r.total} files differ)")
                                    AppLog.i("${m.displayName}: file berbeda ${r.different.take(15)}")
                                }
                            } catch (e: FolderMissing) {
                                // folder hilang: kalau ada entri lain untuk folder yang sama (duplikat), buang yang ini
                                val dup = mods.any { o -> o !== m && o.game == m.game && o.sourceType == "folder" &&
                                    o.subPath.equals(m.subPath, ignoreCase = true) && (m.subPath.isNotBlank() || o.name == m.name) }
                                if (dup) {
                                    mods.remove(m)
                                    removedDup++
                                    AppLog.i("Duplikat dibuang (folder lama tidak ada): ${m.displayName} ${m.sourceUri}")
                                    panel.log("CLEAN", tr("${m.displayName}: duplikat lama dibuang", "${m.displayName}: old duplicate removed"), "OK")
                                } else {
                                    AppLog.w("Cek isi ${m.workshopId} (${m.displayName}): folder tidak ditemukan ${m.sourceUri} / ${m.subPath}")
                                    panel.log("FAIL", tr("${m.displayName}: folder tidak ada (scan ulang)", "${m.displayName}: folder missing (rescanned)"), error = true)
                                    m.updateAvailable = false
                                    m.status = tr("Gagal: folder mod tidak ada di HP (sudah dicek ulang). Tekan DEL untuk buang dari daftar.", "Failed: mod folder is not on the phone (rescanned). Tap DEL to remove it from the list.")
                                }
                            } catch (e: Exception) {
                                AppLog.e("Cek isi ${m.workshopId} (${m.displayName}) gagal", e)
                                panel.log("FAIL", "${m.displayName}: ${e.message}", error = true)
                                m.updateAvailable = false
                                m.status = tr("Gagal cek isi: ${e.message ?: e.javaClass.simpleName}", "Failed content check: ${e.message ?: e.javaClass.simpleName}")
                            }
                            store.save(mods)
                            renderList()
                        }
                        mfDir.deleteRecursively()
                        if (removedDup > 0) toast(tr("$removedDup duplikat lama dibuang dari daftar", "$removedDup old duplicates removed from the list"))
                    }
                }
                store.save(mods)
                renderList()
                val updates = targets.count { it.updateAvailable && it in mods }
                panel.caption(tr("CEK UPDATE · SELESAI", "CHECK UPDATES · DONE"))
                panel.title(if (updates == 0) tr("Semua terbaru", "All up to date") else tr("$updates mod perlu update", "$updates mods need updating"))
                panel.badge(tr("SELESAI", "DONE"))
                panel.fraction(1f)
                panel.detail("")
                panel.log("DONE", tr("${targets.size} mod dicek dalam ${fmtDur((System.currentTimeMillis() - t0) / 1000)}", "${targets.size} mods checked in ${fmtDur((System.currentTimeMillis() - t0) / 1000)}"), "✓")
                setBusy(false)
                toast(if (updates == 0) tr("Semua mod sudah terbaru", "All mods are up to date") else tr("$updates mod perlu diupdate", "$updates mods need updating"))
            } catch (e: Exception) {
                AppLog.e("Gagal cek update", e)
                setBusy(false)
                toast(tr("Gagal cek: ${e.message}. Cek koneksi internet.", "Check failed: ${e.message}. Check your internet connection."))
            }
        }
    }

    /** Bandingkan file mod lokal dengan daftar file resmi (manifest) dari Steam. */
    /** Ambil bagian daftar file yang milik mod ini (Zomboid: hanya folder mods/<folder>/). */
    private fun scopeFor(m: ModEntry, remote: List<ModFiles.RemoteFile>): List<ModFiles.RemoteFile> {
        if (m.game != Game.ZOMBOID.key) return remote
        if (m.contentPrefix.isEmpty() || remote.none { it.path.startsWith(m.contentPrefix) }) {
            m.contentPrefix = ModFiles.findModPrefix(remote, m.subPath.ifBlank { m.name })
                ?: throw Exception(tr("Item Workshop ini tidak berisi folder mod \"${m.subPath}\". Cek ID-nya.", "This Workshop item doesn't contain the mod folder \"${m.subPath}\". Check the ID."))
        }
        return ModFiles.scope(remote, m.contentPrefix)
    }

    private class FolderMissing : Exception(tr("Folder mod tidak ditemukan / izin hilang. Tambahkan ulang foldernya.", "Mod folder not found / permission lost. Add the folder again."))

    private suspend fun compareContent(
        m: ModEntry,
        remoteAll: List<ModFiles.RemoteFile>,
        onProgress: (String) -> Unit,
    ): ModFiles.CompareResult = withContext(Dispatchers.IO) {
        val remote = scopeFor(m, remoteAll)
        when (m.sourceType) {
            "folder" -> {
                val dir = ModFiles.locateFolder(this@MainActivity, m)
                    ?: throw FolderMissing()
                ModFiles.compareFolder(this@MainActivity, dir, remote, onProgress)
            }
            "zip" -> ModFiles.compareZip(this@MainActivity, Uri.parse(m.sourceUri), m.zipPrefix, remote, onProgress)
            else -> throw Exception(tr("Tidak ada file lokal untuk dibandingkan", "No local files to compare"))
        }
    }

    // ------------------------------------------------------------------ update

    private fun updateAll() {
        val list = visible.filter { it.updateAvailable && it.workshopId.isNotBlank() }
        if (list.isEmpty()) {
            toast(tr("Tidak ada mod yang perlu diupdate. Tekan \"Cek update\" dulu.", "No mods need updating. Tap SCAN UPDATE first."))
            return
        }
        val total = list.sumOf { it.remoteSize }
        CmdDialog(this)
            .setTitle(tr("Update ${list.size} mod?", "Update ${list.size} mods?"))
            .setMessage(
                (if (total > 0) tr("Total download sekitar ${fmtSize(total)}.\n\n", "Total download about ${fmtSize(total)}.\n\n") else "") +
                    tr("Biarkan aplikasi tetap terbuka sampai selesai.", "Keep the app open until it finishes.")
            )
            .setPositiveButton("Update") { _, _ -> updateMods(list) }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun updateMods(list: List<ModEntry>) {
        if (busy) return
        if (list.any { it.sourceType == "none" } && outputRoot() == null) {
            CmdDialog(this)
                .setTitle(tr("Pilih folder hasil", "Choose output folder"))
                .setMessage(
                    if (game == Game.ZOMBOID) tr("Mod dari link akan disimpan di folder yang kamu pilih. Pilih folder Zomboid/mods milik Zomdroid supaya langsung terpasang.", "Mods from links are saved to the folder you choose. Pick Zomdroid's Zomboid/mods folder so they're installed right away.")
                    else tr("Mod dari link akan disimpan di folder yang kamu pilih (misalnya Download/RimMods).", "Mods from links are saved to the folder you choose (for example Download/RimMods).")
                )
                .setPositiveButton(tr("Pilih", "Choose")) { _, _ ->
                    afterOutputPicked = { updateMods(list) }
                    pickOutput.launch(null)
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
            return
        }
        lifecycleScope.launch {
            setBusy(true, tr("Menghubungkan ke Steam…", "Connecting to Steam…"))
            panel.start(tr("UPDATE MOD · ${list.size} MOD", "UPDATE · ${list.size} MODS"), tr("Menghubungkan ke Steam…", "Connecting to Steam…"), "LOGIN")
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
                panel.caption(tr("UPDATE MOD · ${i + 1} DARI ${list.size}", "UPDATE · ${i + 1} OF ${list.size}"))
                panel.title(m.displayName)
                panel.badge("DOWNLOAD")
                AppLog.i("Mulai update ${m.workshopId} (${m.displayName}), sumber=${m.sourceType}")
                try {
                    updateOne(m, { progress(it) }) { f -> setFraction((i + f) / list.size) }
                    ok++
                    states[i] = m.displayName to ProgressPanel.ItemState.DONE
                    panel.log("DONE", m.displayName, "✓")
                } catch (e: SteamManager.LoginRequired) {
                    m.status = tr("Gagal: perlu login Steam lagi", "Failed: Steam login needed again")
                    states[i] = m.displayName to ProgressPanel.ItemState.FAILED
                    panel.log("FAIL", tr("${m.displayName}: perlu login lagi", "${m.displayName}: login needed again"), error = true)
                } catch (e: Exception) {
                    AppLog.e("Update ${m.workshopId} (${m.displayName}) gagal", e)
                    m.status = tr("Gagal: ${e.message ?: e.javaClass.simpleName}", "Failed: ${e.message ?: e.javaClass.simpleName}")
                    states[i] = m.displayName to ProgressPanel.ItemState.FAILED
                    panel.log("FAIL", "${m.displayName}: ${e.message}", error = true)
                }
                panel.items(states.toList())
                store.save(mods)
                runOnUiThread { renderList() }
            }
            panel.caption(tr("UPDATE MOD · SELESAI", "UPDATE · DONE"))
            panel.title(tr("$ok dari ${list.size} berhasil", "$ok of ${list.size} succeeded"))
            panel.badge(tr("SELESAI", "DONE"))
            panel.fraction(1f)
            panel.detail("")
            setBusy(false)
            toast(tr("Selesai: $ok dari ${list.size} mod berhasil", "Done: $ok of ${list.size} mods succeeded"))
        }
    }

    /** Teruskan pesan salin ke status dan ke terminal. */
    private fun copyLog(onProgress: (String) -> Unit): (String) -> Unit = { msg ->
        onProgress(msg)
        when {
            msg.startsWith(tr("Menyalin ", "Copying ")) -> panel.log("COPY", msg.removePrefix(tr("Menyalin ", "Copying ")), "✓")
            msg.startsWith(tr("Mengemas ", "Packing ")) -> panel.log("ZIP", msg.removePrefix(tr("Mengemas ", "Packing ")), "✓")
            msg.startsWith(tr("Menghapus file lama", "Removing old files")) -> panel.log("CLEAN", tr("hapus file versi lama", "remove old version files"))
            msg.startsWith(tr("Menghapus ", "Removing ")) -> panel.log("DEL", msg.removePrefix(tr("Menghapus ", "Removing ")), "✓")
        }
    }

    private fun outputRoot(): DocumentFile? =
        store.outputFor(game.key)?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }?.takeIf { it.canWrite() }

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
            // Untuk folder mod yang sudah ada: cari dulu file mana saja yang berubah, lalu download itu saja.
            var plan: ModFiles.UpdatePlan? = null
            var target: DocumentFile? = null
            if (m.sourceType == "folder") {
                target = ModFiles.locateFolder(this@MainActivity, m)
                    ?: throw Exception(tr("Folder mod lama tidak ditemukan / izin hilang. Tambahkan ulang foldernya.", "Old mod folder not found / permission lost. Add the folder again."))
                if (!target.canWrite()) throw Exception(tr("Tidak ada izin menulis ke folder mod lama", "No permission to write to the old mod folder"))
                panel.badge(tr("CEK ISI", "CONTENT"))
                onProgress(tr("Ambil daftar file dari Steam…", "Getting file list from Steam…"))
                val mfDir = File(cacheDir, "mf1/${m.workshopId}")
                try {
                    val remote = scopeFor(m, SteamManager.fetchManifest(this@MainActivity, m.workshopId.toLong(), mfDir) {})
                    panel.log("MANIFEST", "${remote.size} files", "OK")
                    m.remoteFileCount = remote.size
                    onProgress(tr("Membandingkan dengan file di HP…", "Comparing with files on the phone…"))
                    val p = ModFiles.planFolderUpdate(this@MainActivity, target, remote, onProgress)
                    plan = p
                    panel.log("PLAN", tr("${p.changed.size} file berubah · ${p.obsolete.size} dihapus", "${p.changed.size} files changed · ${p.obsolete.size} removed"), "OK")
                    AppLog.i("Rencana update ${m.workshopId}: ${p.changed.size} berubah, ${p.obsolete.size} usang (dari ${remote.size})")
                } catch (e: Exception) {
                    AppLog.e("Cek isi sebelum update gagal, download semua file", e)
                    panel.log("PLAN", tr("gagal (${e.message}), download semua", "failed (${e.message}), downloading everything"), error = true)
                } finally {
                    mfDir.deleteRecursively()
                }
                if (plan != null && plan.isEmpty) {
                    m.updateAvailable = false
                    m.installedVersion = m.remoteVersion
                    m.status = tr("Sudah terbaru ✓ (isi sama dengan Workshop)", "Already up to date ✓ (same contents as Workshop)")
                    onFraction(1f)
                    return@withContext
                }
            }
            panel.badge("DOWNLOAD")
            onProgress(tr("Download dari Steam…", "Downloading from Steam…"))
            // Download = 0..80%, salin ke folder = 80..100%
            val t0 = System.currentTimeMillis()
            val bytes = java.util.concurrent.atomic.AtomicLong(0)
            val files = java.util.concurrent.atomic.AtomicInteger(0)
            val chunks = java.util.concurrent.atomic.AtomicInteger(0)
            val size = plan?.changed?.sumOf { it.size }?.takeIf { it > 0 } ?: m.remoteSize
            val fileTotal = plan?.changed?.size ?: m.remoteFileCount
            // kecepatan dihitung dari 4 detik terakhir (bukan sejak mulai, karena awal-awal ada proses login/daftar file)
            val samples = ArrayDeque<Pair<Long, Long>>()
            fun dlStats() {
                val got = bytes.get()
                val now = System.currentTimeMillis()
                val speed: Double
                synchronized(samples) {
                    samples.addLast(now to got)
                    while (samples.size > 2 && now - samples.first().first > 4000) samples.removeFirst()
                    val (t1, b1) = samples.first()
                    speed = if (now - t1 >= 300) (got - b1) * 1000.0 / (now - t1) else 0.0
                }
                val left = if (size > 0 && speed > 0) ((size - got).coerceAtLeast(0) / speed).toLong() else -1
                panel.stats(
                    (if (fileTotal > 0) "${files.get()}/$fileTotal" else "${files.get()}") to "file",
                    fmtSize(got) to (if (size > 0) tr("dari ${fmtSize(size)}", "of ${fmtSize(size)}") else tr("terunduh", "downloaded")),
                    "${fmtSize(speed.toLong())}/s" to tr("kecepatan", "speed"),
                    (if (left >= 0) "~${fmtDur(left)}" else "-") to tr("sisa", "left"),
                )
                onProgress("Download ${fmtSize(got)}" + if (size > 0) " / ${fmtSize(size)}" else "")
            }
            val partial = SteamManager.downloadMod(
                this@MainActivity,
                m.workshopId.toLong(),
                work,
                onlyFiles = plan?.changed?.map { m.contentPrefix + it.path }?.toSet(),
                onStatus = {},
                onFraction = { f ->
                    onFraction(f * 0.8f)
                    // Ukuran terunduh dihitung dari persentase (info byte per chunk tidak selalu dikirim library).
                    if (size > 0) bytes.accumulateAndGet((f.coerceIn(0f, 1f) * size).toLong()) { x, y -> maxOf(x, y) }
                    dlStats()
                },
                onChunk = { cumulative ->
                    // nilai dari library = total kumulatif depot, bukan per chunk
                    val prev = bytes.getAndAccumulate(cumulative) { x, y -> maxOf(x, y) }
                    val delta = (cumulative - prev).coerceAtLeast(0)
                    panel.log("CHUNK", "#${chunks.incrementAndGet()}  +${fmtSize(delta)} → zstd", "✓")
                    dlStats()
                },
                onFile = { name ->
                    files.incrementAndGet()
                    val rel = name.substringAfter(work.absolutePath).trimStart('/', '\\')
                    panel.log("FILE", rel.ifBlank { name.substringAfterLast('/') }, "✓")
                    dlStats()
                },
            )
            panel.badge(tr("MENYALIN", "COPYING"))
            val copyTotal = ModFiles.downloadedFiles(work).size
            val copyFrac: (Float) -> Unit = { f ->
                onFraction(0.8f + f * 0.2f)
                panel.stats(
                    "${(f * copyTotal).toInt()}/$copyTotal" to tr("file disalin", "files copied"),
                    fmtSize(bytes.get().coerceAtLeast(size)) to tr("ukuran", "size"),
                    fmtDur((System.currentTimeMillis() - t0) / 1000) to tr("berjalan", "elapsed"),
                )
            }
            // Zomboid: isi folder mod ada di dalam "mods/<folder>/" pada item Workshop
            val src = if (m.contentPrefix.isNotEmpty()) File(work, m.contentPrefix) else work
            if (!partial && m.game == Game.RIMWORLD.key) ModFiles.ensurePublishedId(work, m.workshopId)

            when (m.sourceType) {
                "folder" -> {
                    val dir = target!!
                    if (partial && plan != null) {
                        // hanya tulis file yang berubah + hapus file usang
                        ModFiles.applyChanges(this@MainActivity, dir, src, plan, copyLog(onProgress), copyFrac)
                    } else {
                        if (!src.isDirectory) throw Exception(tr("Folder mod tidak ada di hasil download (${m.contentPrefix})", "Mod folder missing from the download (${m.contentPrefix})"))
                        ModFiles.replaceFolder(this@MainActivity, dir, src, copyLog(onProgress), copyFrac)
                    }
                }
                "zip" -> {
                    ModFiles.rewriteZip(this@MainActivity, Uri.parse(m.sourceUri), m.zipPrefix, work, copyLog(onProgress), copyFrac)
                }
                else -> {
                    val out = outputRoot() ?: throw Exception(tr("Folder hasil belum dipilih", "Output folder not chosen yet"))
                    val zMods = work.walkTopDown().maxDepth(3)
                        .firstOrNull { it.isDirectory && it.name.equals("mods", true) }
                    if (m.game == Game.ZOMBOID.key && zMods != null) {
                        // Zomboid: setiap folder di dalam "mods/" dipasang sebagai folder mod tersendiri
                        val subs = zMods.listFiles()?.filter { it.isDirectory }.orEmpty()
                        subs.forEachIndexed { i, d ->
                            ModFiles.writeNewFolder(this@MainActivity, out, d.name, d, copyLog(onProgress)) { f ->
                                copyFrac((i + f) / subs.size.coerceAtLeast(1))
                            }
                        }
                    } else {
                        val folderName = ModFiles.safeFolderName(m.displayName, m.workshopId)
                        ModFiles.writeNewFolder(this@MainActivity, out, folderName, work, copyLog(onProgress), copyFrac)
                    }
                }
            }
            m.localTime = maxOf(System.currentTimeMillis() / 1000, m.remoteTime)
            m.updateAvailable = false
            m.installedVersion = m.remoteVersion
            AppLog.i("Berhasil update ${m.workshopId} (${m.displayName})")
            m.status = tr("Berhasil diupdate ✓ (${fmt(m.remoteTime)})", "Updated ✓ (${fmt(m.remoteTime)})")
        } finally {
            work.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ Steam login

    private fun refreshSteamStatus() {
        val acc = SteamManager.accountName ?: SteamManager.savedAccount(this)
        if (acc != null) {
            val on = SteamManager.loggedIn
            val t = android.text.SpannableStringBuilder("> steam_session: ")
            val st = t.length
            t.append("$acc ${if (on) "● ONLINE" else "○ OFFLINE"}")
            t.setSpan(android.text.style.ForegroundColorSpan(if (on) Hx.GREEN else Hx.BRIGHT), st, t.length, 0)
            t.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, t.length, 0)
            steamStatus.text = t
            steamButton.text = "LOGOUT"
        } else {
            steamStatus.text = "> steam_session: NONE\n  " + tr("(perlu login untuk download)", "(login needed to download)")
            steamButton.text = "LOGIN"
        }
    }

    private fun onSteamButton() {
        if (SteamManager.savedAccount(this) != null || SteamManager.loggedIn) {
            SteamManager.logout(this)
            refreshSteamStatus()
            toast(tr("Sudah logout", "Logged out"))
        } else {
            lifecycleScope.launch {
                setBusy(true, tr("Login Steam…", "Steam login…"))
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
            toast(tr("Tidak bisa tersambung ke Steam: ${e.message}", "Can't connect to Steam: ${e.message}"))
            return false
        }
        while (true) {
            val cred = askCredentials() ?: return false
            try {
                progress(tr("Login sebagai ${cred.first}…", "Logging in as ${cred.first}…"))
                SteamManager.loginWithPassword(this, cred.first, cred.second, UiLogin())
                refreshSteamStatus()
                toast(tr("Login berhasil", "Login successful"))
                return true
            } catch (e: Exception) {
                val cause = (e.cause ?: e)
                if (e is SteamManager.LoginCancelled || cause is CancellationException) {
                    AppLog.i("Login dibatalkan")
                    return false
                }
                AppLog.e("Login Steam gagal", e)
                toast(tr("Login gagal: ${cause.message ?: cause.javaClass.simpleName}", "Login failed: ${cause.message ?: cause.javaClass.simpleName}"))
            }
        }
    }

    private suspend fun askCredentials(): Pair<String, String>? = suspendCancellableCoroutine { cont ->
        val user = EditText(this).apply {
            hint = tr("Username Steam", "Steam username")
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
                text = tr("Login langsung ke server Steam (sama seperti RimDroid). Password tidak disimpan; yang disimpan hanya token login di HP ini. Akun harus punya ${game.title}.", "Logs in directly to Steam's servers (same as RimDroid). The password isn't stored; only a login token is kept on this phone. The account must own ${game.title}.")
                textSize = 12f
                alpha = 0.7f
                setPadding(0, px(8), 0, 0)
            })
        }
        var resumed = false
        CmdDialog(this)
            .setTitle(tr("Login Steam", "Steam login"))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Login") { _, _ ->
                val u = user.text.toString().trim()
                val p = pass.text.toString()
                resumed = true
                cont.resume(if (u.isBlank() || p.isBlank()) null else u to p)
            }
            .setNegativeButton(tr("Batal", "Cancel")) { _, _ -> resumed = true; cont.resume(null) }
            .setOnDismissListener { if (!resumed) cont.resume(null) }
            .show()
    }

    /** Dialog verifikasi Steam (Steam Guard) yang dipakai selama login. */
    private inner class UiLogin : SteamManager.LoginUi {

        private suspend fun <T> dialog(build: (CmdDialog, (T) -> Unit) -> Unit): T =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    val finish: (T) -> Unit = { v -> if (!done) { done = true; cont.resume(v) } }
                    val b = CmdDialog(this@MainActivity).setCancelable(false)
                    build(b, finish)
                    b.show()
                }
            }

        override suspend fun chooseApproveInApp(): Boolean = dialog { b, finish ->
            b.setTitle(tr("Verifikasi Steam Guard", "Steam Guard verification"))
                .setMessage(tr("Pilih cara verifikasi login:", "Choose how to verify the login:"))
                .setPositiveButton(tr("Setujui di app Steam", "Approve in the Steam app")) { _, _ -> finish(true) }
                .setNegativeButton(tr("Masukkan kode", "Enter code")) { _, _ -> finish(false) }
        }

        override suspend fun waitApproved(): Boolean = dialog { b, finish ->
            b.setTitle(tr("Setujui di aplikasi Steam", "Approve in the Steam app"))
                .setMessage(
                    tr("1. Buka aplikasi Steam di HP-mu.\n" +
                        "2. Tekan \"Approve / Setujui\" pada permintaan login \"Steamod Updater\".\n" +
                        "3. Kembali ke sini, lalu tekan tombol \"Sudah disetujui\".\n\n" +
                        "Tidak apa-apa kalau koneksi sempat putus saat kamu pindah aplikasi, nanti otomatis tersambung lagi.",
                        "1. Open the Steam app on your phone.\n" +
                        "2. Tap \"Approve\" on the \"Steamod Updater\" sign-in request.\n" +
                        "3. Come back here and tap \"I approved it\".\n\n" +
                        "It's fine if the connection drops while you switch apps; it reconnects automatically.")
                )
                .setPositiveButton(tr("Sudah disetujui", "I approved it")) { _, _ -> finish(true) }
                .setNegativeButton(tr("Batal", "Cancel")) { _, _ -> finish(false) }
        }

        override suspend fun askCode(isEmail: Boolean, hint: String?, previousWrong: Boolean): String? = dialog { b, finish ->
            val input = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                this.hint = tr("Kode", "Code")
            }
            val msg = (if (previousWrong) tr("Kode salah, coba lagi.\n", "Wrong code, try again.\n") else "") +
                if (isEmail) tr("Steam mengirim kode ke email ${hint ?: "kamu"}.", "Steam sent a code to ${hint ?: "your"} email.") else tr("Buka aplikasi Steam → Steam Guard, lalu masukkan kode 5 karakternya.", "Open the Steam app → Steam Guard, then enter the 5-character code.")
            b.setTitle(if (isEmail) tr("Kode dari email", "Code from email") else tr("Kode Steam Guard", "Steam Guard code"))
                .setMessage(msg)
                .setView(padded(input))
                .setPositiveButton("OK") { _, _ -> finish(input.text.toString().trim().uppercase().ifBlank { null }) }
                .setNegativeButton(tr("Batal", "Cancel")) { _, _ -> finish(null) }
        }
    }
}
