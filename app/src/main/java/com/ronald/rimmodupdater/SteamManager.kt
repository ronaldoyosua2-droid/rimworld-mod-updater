package com.ronald.rimmodupdater

import android.content.Context
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.concurrent.thread

/** Login Steam + download mod Workshop memakai JavaSteam. */
object SteamManager {

    class LoginRequired : Exception("Perlu login Steam")

    private var client: SteamClient? = null
    private var manager: CallbackManager? = null
    private var user: SteamUser? = null
    @Volatile private var loopRunning = false

    @Volatile var loggedIn = false
        private set
    @Volatile var accountName: String? = null
        private set
    private var licenses: List<License> = emptyList()

    private var connectedSignal: CompletableDeferred<Unit>? = null
    private var logonSignal: CompletableDeferred<EResult>? = null
    private var licenseSignal: CompletableDeferred<List<License>>? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("steam", Context.MODE_PRIVATE)

    fun savedAccount(ctx: Context): String? = prefs(ctx).getString("account", null)

    private fun ensureClient() {
        if (client != null) return
        val c = SteamClient()
        val m = CallbackManager(c)
        user = c.getHandler(SteamUser::class.java)

        m.subscribe(ConnectedCallback::class.java) { connectedSignal?.complete(Unit) }
        m.subscribe(DisconnectedCallback::class.java) {
            loggedIn = false
            AppLog.w("Terputus dari Steam")
            val err = Exception("Koneksi ke Steam terputus")
            connectedSignal?.completeExceptionally(err)
            logonSignal?.completeExceptionally(err)
            licenseSignal?.completeExceptionally(err)
        }
        m.subscribe(LoggedOnCallback::class.java) { cb -> logonSignal?.complete(cb.result) }
        m.subscribe(LoggedOffCallback::class.java) { loggedIn = false }
        m.subscribe(LicenseListCallback::class.java) { cb ->
            if (cb.result == EResult.OK) {
                licenses = cb.licenseList
                licenseSignal?.complete(cb.licenseList)
            }
        }

        client = c
        manager = m
        loopRunning = true
        thread(name = "steam-callbacks", isDaemon = true) {
            while (loopRunning) {
                try {
                    manager?.runWaitCallbacks(1000L)
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun connect() {
        ensureClient()
        val c = client!!
        if (c.isConnected) return
        val sig = CompletableDeferred<Unit>()
        connectedSignal = sig
        AppLog.i("Menghubungkan ke Steam…")
        c.connect()
        withTimeout(30_000) { sig.await() }
    }

    private suspend fun logOn(account: String, refreshToken: String): EResult {
        val logon = CompletableDeferred<EResult>()
        val lic = CompletableDeferred<List<License>>()
        logonSignal = logon
        licenseSignal = lic
        user!!.logOn(LogOnDetails().apply {
            username = account
            accessToken = refreshToken
            shouldRememberPassword = true
            loginID = 0x52444D55 // nilai unik supaya tidak bentrok dengan sesi Steam lain
        })
        val result = withTimeout(30_000) { logon.await() }
        AppLog.i("Logon Steam ($account): $result")
        if (result == EResult.OK) {
            withTimeout(30_000) { lic.await() }
            loggedIn = true
            accountName = account
        }
        return result
    }

    /** Login pakai token tersimpan. Kalau gagal / belum ada token → LoginRequired. */
    suspend fun ensureLoggedIn(ctx: Context) = withContext(Dispatchers.IO) {
        if (loggedIn && client?.isConnected == true) return@withContext
        val account = prefs(ctx).getString("account", null)
        val token = prefs(ctx).getString("token", null)
        if (account == null || token == null) throw LoginRequired()
        connect()
        val result = logOn(account, token)
        if (result != EResult.OK) {
            prefs(ctx).edit().remove("token").apply()
            throw LoginRequired()
        }
    }

    /** Login pertama kali pakai username + password (+ Steam Guard lewat [auth]). */
    suspend fun loginWithPassword(ctx: Context, username: String, password: String, auth: IAuthenticator) =
        withContext(Dispatchers.IO) {
            connect()
            val details = AuthSessionDetails().apply {
                this.username = username
                this.password = password
                persistentSession = true
                deviceFriendlyName = "RimMod Updater (Android)"
                authenticator = auth
            }
            val session = client!!.authentication.beginAuthSessionViaCredentials(details).await()
            val poll = session.pollingWaitForResult().await()
            val result = logOn(poll.accountName, poll.refreshToken)
            if (result != EResult.OK) throw Exception("Login Steam gagal: $result")
            prefs(ctx).edit()
                .putString("account", poll.accountName)
                .putString("token", poll.refreshToken)
                .apply()
        }

    fun logout(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        try {
            user?.logOff()
        } catch (_: Exception) {
        }
        loggedIn = false
        accountName = null
    }

    /**
     * Download 1 mod Workshop ke [dir] (folder kosong di penyimpanan internal aplikasi).
     * Melempar exception kalau gagal atau macet terlalu lama.
     */
    suspend fun downloadMod(workshopId: Long, dir: File, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        val c = client ?: throw LoginRequired()
        if (!loggedIn) throw LoginRequired()

        val done = CompletableDeferred<Unit>()
        val activity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        val dd = DepotDownloader(
            steamClient = c,
            licenses = licenses,
            debug = true,
            useLanCache = false,
            maxDownloads = 4,
            maxDecompress = 2,
            maxFileWrites = 1,
        )
        dd.addListener(object : IDownloadListener {
            override fun onDownloadCompleted(item: DownloadItem) {
                done.complete(Unit)
            }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                AppLog.e("DepotDownloader gagal untuk $workshopId", error)
                done.completeExceptionally(error)
            }

            override fun onStatusUpdate(message: String) {
                activity.set(System.currentTimeMillis())
                onStatus(message)
            }

            override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                activity.set(System.currentTimeMillis())
                onStatus("${(depotPercentComplete * 100).toInt()}% • $fileName")
            }

            override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                activity.set(System.currentTimeMillis())
            }
        })
        dd.getCompletion().whenComplete { _, err ->
            if (err != null) done.completeExceptionally(err) else done.complete(Unit)
        }

        try {
            dd.add(
                PubFileItem(
                    appId = WorkshopApi.RIMWORLD_APP_ID,
                    pubFile = workshopId,
                    installToGameNameDirectory = false,
                    installDirectory = dir.absolutePath,
                    verify = false,
                    downloadManifestOnly = false,
                )
            )
            dd.finishAdding()
            // Tunggu selesai, tapi gagal kalau 3 menit tidak ada kemajuan sama sekali.
            while (!done.isCompleted) {
                delay(500)
                if (System.currentTimeMillis() - activity.get() > 180_000) {
                    throw Exception("Download macet (tidak ada kemajuan 3 menit)")
                }
            }
            done.await()
            AppLog.i("Download $workshopId selesai: ${ModFiles.downloadedFiles(dir).size} file")
            if (ModFiles.downloadedFiles(dir).isEmpty()) {
                throw Exception("Download selesai tapi tidak ada file. Mungkin akunmu tidak punya RimWorld, atau mod-nya dihapus.")
            }
        } finally {
            try {
                dd.close()
            } catch (_: Exception) {
            }
        }
    }
}
