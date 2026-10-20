package com.ronald.rimmodupdater

import android.content.Context
import android.os.Build
import android.util.Log
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan log aplikasi (disimpan di file), termasuk log Steam dan crash.
 * Bisa dibagikan dari tombol "Log" supaya bug gampang dilacak.
 */
object AppLog {
    private const val MAX_BYTES = 1_500_000L
    private lateinit var file: File
    private lateinit var oldFile: File
    private lateinit var crashFile: File
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var ready = false

    /** Penerus baris log (dipakai panel terminal). Dipanggil dari thread mana pun. */
    @Volatile var tap: ((level: String, msg: String) -> Unit)? = null
    private var writer: java.io.Writer? = null
    private var written = 0L

    @Synchronized
    fun flush() {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    val buildLabel: String
        get() = "Build ${BuildConfig.VERSION_CODE} (v${BuildConfig.VERSION_NAME}, ${BuildConfig.BUILD_TIME})"

    fun init(ctx: Context) {
        if (ready) return
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
        oldFile = File(dir, "app.prev.log")
        crashFile = File(dir, "last_crash.txt")
        ready = true

        // Tangkap crash: tulis ke log sebelum aplikasi tertutup.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = "CRASH di thread '${t.name}'\n${stack(e)}"
                write("FATAL", text)
                flush()
                crashFile.writeText("${timeFmt.format(Date())}\n$text")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }

        // Log dari library Steam (JavaSteam).
        LogManager.addListener(object : LogListener {
            override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
                write("steam", "[${clazz.simpleName}] ${message ?: ""}" + (throwable?.let { "\n" + stack(it) } ?: ""))
            }

            override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
                write("STEAM-ERR", "[${clazz.simpleName}] ${message ?: ""}" + (throwable?.let { "\n" + stack(it) } ?: ""))
            }
        })

        i("=== Aplikasi dibuka • $buildLabel • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    fun i(msg: String) = write("info", msg)
    fun w(msg: String) = write("WARN", msg)
    fun e(msg: String, err: Throwable? = null) = write("ERROR", msg + (err?.let { "\n" + stack(it) } ?: ""))

    @Synchronized
    private fun write(level: String, msg: String) {
        try {
            tap?.invoke(level, msg)
        } catch (_: Throwable) {
        }
        Log.println(if (level == "info" || level == "steam") Log.INFO else Log.ERROR, "RimModUpdater", msg)
        if (!ready) return
        try {
            if (writer == null) writer = java.io.BufferedWriter(java.io.FileWriter(file, true), 32 * 1024)
            val w = writer!!
            w.write("${timeFmt.format(Date())} $level  $msg\n")
            written += msg.length + 40
            if (level != "steam") w.flush()
            if (written > 200_000) {
                written = 0
                w.flush()
                if (file.length() > MAX_BYTES) {
                    w.close()
                    writer = null
                    oldFile.delete()
                    file.renameTo(oldFile)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Crash dari sesi sebelumnya (kalau ada), lalu dihapus. */
    fun takeLastCrash(): String? {
        if (!ready || !crashFile.exists()) return null
        val t = crashFile.readText()
        crashFile.delete()
        return t
    }

    /** Teks log lengkap untuk dilihat / dibagikan. */
    @Synchronized
    fun fullText(): String = buildString {
        flush()
        append("Steamod Updater log\n")
        append("$buildLabel\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Diekspor: ${timeFmt.format(Date())}\n")
        append("========================================\n")
        if (oldFile.exists()) append(oldFile.readText())
        if (file.exists()) append(file.readText())
    }

    /** Ringkasan untuk ditampilkan di layar (yang penting saja, bukan log Steam yang ramai). */
    fun recentImportant(maxLines: Int = 300): String {
        flush()
        if (!file.exists()) return ""
        return file.readLines().filter { !it.contains(" steam  ") }.takeLast(maxLines).joinToString("\n")
    }

    /** Tulis log ke file sementara untuk dibagikan. */
    fun exportFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "rimmodupdater_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        return File(dir, name).apply { writeText(fullText()) }
    }

    @Synchronized
    fun clear() {
        if (!ready) return
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        file.delete()
        oldFile.delete()
        i("Log dihapus • $buildLabel")
    }
}
