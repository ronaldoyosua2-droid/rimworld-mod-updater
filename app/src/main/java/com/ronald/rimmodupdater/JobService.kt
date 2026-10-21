package com.ronald.rimmodupdater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Keadaan proses yang sedang berjalan, dibaca layanan latar untuk notifikasi. */
object JobState {
    @Volatile var title = ""
    @Volatile var text = ""
    @Volatile var fraction: Float? = null
    /** Dipanggil saat tombol CANCEL di notifikasi ditekan. */
    @Volatile var onCancel: (() -> Unit)? = null
}

/**
 * Layanan latar (foreground service) supaya scan / update tetap berjalan walaupun pengguna
 * pindah ke aplikasi lain atau layar mati. Android mewajibkan notifikasi selama layanan ini aktif.
 */
class JobService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() {
            notifyProgress()
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                JobState.onCancel?.invoke()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }
        ensureChannels(this)
        val n = build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID_PROGRESS, n)
        } catch (e: Exception) {
            AppLog.w("Layanan latar tidak bisa dimulai: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "steamod:job").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L) // batas aman 3 jam
                }
        }
        main.removeCallbacks(ticker)
        main.post(ticker)
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, JobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(JobState.title.ifBlank { "Steamod Updater" })
            .setContentText(JobState.text.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(JobState.text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(Hx.GREEN)
            .addAction(Notification.Action.Builder(null, tr("BUKA", "OPEN"), open).build())
            .addAction(Notification.Action.Builder(null, tr("BATAL", "CANCEL"), cancel).build())
        val f = JobState.fraction
        if (f == null) b.setProgress(0, 0, true) else b.setProgress(1000, (f * 1000).toInt(), false)
        return b.build()
    }

    private fun notifyProgress() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_PROGRESS, build())
        } catch (_: Exception) {
        }
    }

    private fun stopEverything() {
        main.removeCallbacks(ticker)
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CH_PROGRESS = "job_progress"
        private const val CH_DONE = "job_done"
        private const val ID_PROGRESS = 41
        private const val ID_DONE = 42
        private const val ACTION_CANCEL = "com.ronald.rimmodupdater.CANCEL"
        private const val ACTION_STOP = "com.ronald.rimmodupdater.STOP"

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, tr("Proses berjalan", "Running tasks"), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, tr("Proses selesai", "Finished tasks"), NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Mulai layanan latar (dipanggil saat proses mulai). */
        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, JobService::class.java))
            } catch (e: Exception) {
                AppLog.w("Gagal memulai layanan latar: ${e.message}")
            }
        }

        /** Hentikan layanan latar; [doneTitle] ≠ null → tampilkan notifikasi ringkasan. */
        fun stop(ctx: Context, doneTitle: String?, doneText: String?) {
            try {
                ctx.startService(Intent(ctx, JobService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                ctx.stopService(Intent(ctx, JobService::class.java))
            }
            if (doneTitle.isNullOrBlank()) return
            try {
                ensureChannels(ctx)
                val open = PendingIntent.getActivity(
                    ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = Notification.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(doneTitle)
                    .setContentText(doneText ?: tr("Ketuk untuk membuka aplikasi", "Tap to open the app"))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setColor(Hx.GREEN)
                    .build()
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_DONE, n)
            } catch (_: Exception) {
            }
        }
    }
}
