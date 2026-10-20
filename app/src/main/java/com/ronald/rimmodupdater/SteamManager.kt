package com.ronald.rimmodupdater

import android.content.Context
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /**
     * Android punya provider kriptografi bawaan bernama "BC" yang sudah dipangkas (tanpa SHA-1, dll.).
     * JavaSteam memakai provider bernama "BC", jadi ganti dengan BouncyCastle lengkap yang ikut di aplikasi.
     * Harus dipanggil sebelum JavaSteam dipakai.
     */
    fun installCrypto() {
        try {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            val ok = try {
                java.security.MessageDigest.getInstance("SHA-1", "BC"); true
            } catch (_: Exception) {
                false
            }
            AppLog.i("Kriptografi BouncyCastle lengkap dipasang (SHA-1: ${if (ok) "OK" else "TIDAK ADA"})")
        } catch (e: Throwable) {
            AppLog.e("Gagal memasang BouncyCastle", e)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("steam", Context.MODE_PRIVATE)

    fun savedAccount(ctx: Context): String? = prefs(ctx).getString("account", null)

    /**
     * Klien HTTP sendiri untuk download dari CDN Steam:
     * - banyak koneksi disimpan siap pakai (bawaan cuma 5, padahal server CDN ~21) supaya tidak jabat tangan HTTPS terus,
     * - executor yang tidak bisa dimatikan, karena JavaSteam mematikan executor klien bersama saat sesi download ditutup.
     */
    private fun fastConfig(): `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration {
        val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "steam-http").apply { isDaemon = true } }
        val neverShutdown = object : java.util.concurrent.AbstractExecutorService() {
            override fun execute(command: Runnable) = pool.execute(command)
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = false
        }
        val dispatcher = okhttp3.Dispatcher(neverShutdown).apply {
            maxRequests = 96
            maxRequestsPerHost = 8
        }
        val http = okhttp3.OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(64, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        return `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration.create { b ->
            b.withHttpClient(http)
        }
    }

    private fun ensureClient() {
        if (client != null) return
        val c = SteamClient(fastConfig())
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

    /** Pertanyaan ke pengguna selama login (dijawab lewat dialog di layar). */
    interface LoginUi {
        /** true = setujui di aplikasi Steam, false = masukkan kode. */
        suspend fun chooseApproveInApp(): Boolean
        /** Ditampilkan sementara pengguna menyetujui di aplikasi Steam. true = sudah disetujui, false = batal. */
        suspend fun waitApproved(): Boolean
        /** Minta kode Steam Guard / email. null = batal. */
        suspend fun askCode(isEmail: Boolean, hint: String?, previousWrong: Boolean): String?
    }

    class LoginCancelled : Exception("Login dibatalkan")

    /** Jalankan [block]; kalau gagal karena koneksi putus, sambung ulang lalu coba lagi. */
    private suspend fun <T> withReconnect(what: String, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                if (client?.isConnected != true) connect()
                return block()
            } catch (e: AuthenticationException) {
                throw e
            } catch (e: Exception) {
                last = e
                AppLog.w("$what gagal (percobaan ${attempt + 1}): ${e.javaClass.simpleName} ${e.message ?: ""}")
                delay(1500)
            }
        }
        throw last ?: Exception("$what gagal")
    }

    /**
     * Login pertama kali pakai username + password.
     * Tahan terhadap koneksi yang putus saat aplikasi ditinggal ke aplikasi Steam untuk menyetujui login.
     */
    suspend fun loginWithPassword(ctx: Context, username: String, password: String, ui: LoginUi) =
        withContext(Dispatchers.IO) {
            connect()
            val details = AuthSessionDetails().apply {
                this.username = username
                this.password = password
                persistentSession = true
                deviceFriendlyName = "Steamod Updater (Android)"
                authenticator = null // verifikasi ditangani sendiri di bawah
            }
            val session = withReconnect("Mulai login") {
                client!!.authentication.beginAuthSessionViaCredentials(details).await()
            }
            val confirmations = session.allowedConfirmations
            val types = confirmations.map { it.confirmationType }
            AppLog.i("Jenis verifikasi Steam: $types, interval ${session.pollingInterval}s")

            if (!types.contains(EAuthSessionGuardType.k_EAuthSessionGuardType_None)) {
                val canApprove = types.contains(EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation)
                val codeConf = confirmations.firstOrNull {
                    it.confirmationType == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode ||
                        it.confirmationType == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                }
                val approve = when {
                    canApprove && codeConf != null -> ui.chooseApproveInApp()
                    canApprove -> true
                    else -> false
                }
                if (approve) {
                    AppLog.i("Menunggu persetujuan di aplikasi Steam")
                    if (!ui.waitApproved()) throw LoginCancelled()
                } else if (codeConf != null) {
                    val isEmail = codeConf.confirmationType == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                    var wrong = false
                    while (true) {
                        val code = ui.askCode(isEmail, codeConf.associatedMessage, wrong) ?: throw LoginCancelled()
                        try {
                            withReconnect("Kirim kode") {
                                session.sendSteamGuardCode(code, codeConf.confirmationType).await()
                            }
                            AppLog.i("Kode Steam Guard diterima")
                            break
                        } catch (e: AuthenticationException) {
                            AppLog.w("Kode ditolak: ${e.result}")
                            if (e.result == EResult.InvalidLoginAuthCode || e.result == EResult.TwoFactorCodeMismatch) {
                                wrong = true
                            } else {
                                throw e
                            }
                        }
                    }
                } else {
                    throw Exception("Jenis verifikasi Steam belum didukung: $types")
                }
            }

            // Cek status persetujuan tiap beberapa detik, sambung ulang kalau koneksi putus.
            val interval = (session.pollingInterval * 1000).toLong().coerceIn(2000L, 10_000L)
            val deadline = System.currentTimeMillis() + 180_000
            var poll: AuthPollResult? = null
            while (poll == null) {
                if (System.currentTimeMillis() > deadline) {
                    throw Exception("Persetujuan login tidak diterima dalam 3 menit. Coba lagi.")
                }
                try {
                    if (client?.isConnected != true) connect()
                    poll = session.pollAuthSessionStatus().await()
                } catch (e: AuthenticationException) {
                    if (e.result == EResult.Expired || e.result == EResult.FileNotFound) {
                        throw Exception("Permintaan login sudah kedaluwarsa. Coba login lagi.")
                    }
                    AppLog.w("Cek status login: ${e.message}")
                } catch (e: Exception) {
                    AppLog.w("Cek status login gagal (${e.javaClass.simpleName}), sambung ulang…")
                }
                if (poll == null) delay(interval)
            }
            val auth = poll!!
            AppLog.i("Persetujuan login diterima untuk ${auth.accountName}")

            val result = withReconnect("Logon") { logOn(auth.accountName, auth.refreshToken) }
            if (result != EResult.OK) throw Exception("Login Steam gagal: $result")
            prefs(ctx).edit()
                .putString("account", auth.accountName)
                .putString("token", auth.refreshToken)
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

    /** Download 1 mod Workshop ke [dir] (folder kosong di penyimpanan internal aplikasi). */
    suspend fun downloadMod(
        ctx: Context,
        workshopId: Long,
        dir: File,
        onStatus: (String) -> Unit,
        onFraction: (Float) -> Unit = {},
        onChunk: (Long) -> Unit = {},
        onFile: (String) -> Unit = {},
        onlyFiles: Set<String>? = null,
    ): Boolean {
        val partial = runDepot(
            ctx, workshopId, dir, manifestOnly = false, onStatus = onStatus, onFraction = onFraction,
            onChunk = onChunk, onFile = onFile, onlyFiles = onlyFiles,
        )
        val count = ModFiles.downloadedFiles(dir).size
        AppLog.i("Download $workshopId selesai: $count file")
        if (count == 0) {
            throw Exception("Download selesai tapi tidak ada file. Mungkin akunmu tidak punya RimWorld, atau mod-nya dihapus.")
        }
        return partial
    }

    /**
     * Aktifkan fitur "hanya file tertentu" di DepotDownloader (ada di library tapi tidak bisa diatur dari luar).
     * true = berhasil, false = tetap download semua file.
     */
    private fun restrictFiles(dd: DepotDownloader, files: Set<String>): Boolean = try {
        val cfgField = DepotDownloader::class.java.getDeclaredField("config").apply { isAccessible = true }
        val cfg = cfgField.get(dd)!!
        val cls = cfg.javaClass
        cls.getDeclaredField("usingFileList").apply { isAccessible = true }.setBoolean(cfg, true)
        @Suppress("UNCHECKED_CAST")
        val set = cls.getDeclaredField("filesToDownload").apply { isAccessible = true }.get(cfg) as java.util.HashSet<String>
        set.clear()
        set.addAll(files)
        val ok = cls.getDeclaredField("usingFileList").apply { isAccessible = true }.getBoolean(cfg)
        AppLog.i("Mode file tertentu: ${if (ok) "aktif" else "GAGAL"} (${files.size} file)")
        ok
    } catch (e: Throwable) {
        AppLog.e("Mode file tertentu gagal diaktifkan, download semua file", e)
        false
    }

    /** Ambil daftar file resmi (manifest) sebuah mod tanpa men-download isinya. */
    suspend fun fetchManifest(ctx: Context, workshopId: Long, dir: File, onStatus: (String) -> Unit): List<ModFiles.RemoteFile> {
        runDepot(ctx, workshopId, dir, manifestOnly = true, onStatus = onStatus)
        val txt = dir.listFiles()?.firstOrNull { it.name.startsWith("manifest_") && it.name.endsWith(".txt") }
            ?: throw Exception("Daftar file mod tidak didapat dari Steam")
        return ModFiles.parseManifest(txt)
    }

    /**
     * Tambalan bug JavaSteam 1.8.0: mod Workshop biasa bertipe 0 ("Community"), tapi library
     * membacanya sebagai "First" (nama lain untuk 0) yang tidak ada di daftar tipe yang didukung,
     * sehingga semua mod RimWorld dilewati. Tambahkan "First" ke daftar itu.
     */
    private val workshopTypePatched: Boolean by lazy {
        try {
            val candidates = listOf(DepotDownloader::class.java) +
                DepotDownloader::class.java.declaredClasses.toList()
            val field = candidates.flatMap { it.declaredFields.toList() }.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    Set::class.java.isAssignableFrom(it.type) &&
                    it.name.contains("Workshop", ignoreCase = true)
            } ?: throw Exception("field tidak ditemukan")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val set = field.get(null) as MutableSet<EWorkshopFileType>
            set.add(EWorkshopFileType.First)
            AppLog.i("Tambalan tipe mod Workshop aktif (${field.name}: $set)")
            true
        } catch (e: Throwable) {
            AppLog.e("Tambalan tipe mod Workshop gagal", e)
            false
        }
    }

    /**
     * Ambil daftar file resmi untuk banyak mod sekaligus, memakai SATU sesi download
     * (info game, kunci akses dan server cukup diminta sekali).
     */
    suspend fun fetchManifests(
        ctx: Context,
        ids: List<Long>,
        baseDir: File,
        onStatus: (String) -> Unit,
        onFraction: (Float) -> Unit = {},
        onManifest: (Long, Result<List<ModFiles.RemoteFile>>) -> Unit = { _, _ -> },
    ): Map<Long, Result<List<ModFiles.RemoteFile>>> = withContext(Dispatchers.IO) {
        if (!loggedIn || client?.isConnected != true) ensureLoggedIn(ctx)
        val all = ids.distinct()
        val started = System.currentTimeMillis()
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val report: () -> Unit = {
            onStatus("Ambil daftar file dari Steam: ${doneCount.get()}/${all.size} mod")
            onFraction(doneCount.get().toFloat() / all.size.coerceAtLeast(1))
        }
        report()
        // Bagi ke beberapa jalur yang berjalan bersamaan (round-robin supaya mod besar tersebar).
        val lanes = minOf(4, all.size).coerceAtLeast(1)
        val groups = (0 until lanes).map { lane -> all.filterIndexed { i, _ -> i % lanes == lane } }
        val merged = kotlinx.coroutines.coroutineScope {
            groups.map { g ->
                async { fetchManifestsLane(g, baseDir, doneCount, report, onManifest) }
            }.awaitAll()
        }.fold(mutableMapOf<Long, Result<List<ModFiles.RemoteFile>>>()) { acc, m -> acc.putAll(m); acc }
        AppLog.i("Ambil ${all.size} daftar file ($lanes jalur) selesai dalam ${(System.currentTimeMillis() - started) / 1000} detik")
        merged
    }

    private suspend fun fetchManifestsLane(
        ids: List<Long>,
        baseDir: File,
        doneCount: java.util.concurrent.atomic.AtomicInteger,
        report: () -> Unit,
        onManifest: (Long, Result<List<ModFiles.RemoteFile>>) -> Unit,
    ): Map<Long, Result<List<ModFiles.RemoteFile>>> = withContext(Dispatchers.IO) {
        val c = client ?: throw LoginRequired()

        val order = ids.distinct()
        val dirs = order.associateWith { File(baseDir, it.toString()).apply { deleteRecursively(); mkdirs() } }
        val results = java.util.concurrent.ConcurrentHashMap<Long, Result<List<ModFiles.RemoteFile>>>()
        val activity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        fun parse(id: Long): Result<List<ModFiles.RemoteFile>> = runCatching {
            val txt = dirs[id]!!.listFiles()?.firstOrNull { it.name.startsWith("manifest_") && it.name.endsWith(".txt") }
                ?: throw Exception("Daftar file tidak didapat dari Steam")
            ModFiles.parseManifest(txt)
        }

        workshopTypePatched
        val dd = DepotDownloader(
            steamClient = c,
            licenses = licenses,
            debug = false,
            useLanCache = false,
            maxDownloads = 4,
            maxDecompress = 2,
            maxFileWrites = 1,
        )
        dd.addListener(object : IDownloadListener {
            override fun onDownloadStarted(item: DownloadItem) {
                activity.set(System.currentTimeMillis())
                val id = (item as? PubFileItem)?.pubFile ?: return
                // Item sebelumnya yang selesai tanpa kabar dianggap gagal.
                for (prev in order.takeWhile { it != id }) {
                    if (!results.containsKey(prev)) parse(prev).let { r -> if (results.putIfAbsent(prev, r) == null) { doneCount.incrementAndGet(); report(); onManifest(prev, r) } }
                }
            }

            override fun onDownloadCompleted(item: DownloadItem) {
                activity.set(System.currentTimeMillis())
                val id = (item as? PubFileItem)?.pubFile ?: return
                parse(id).let { r -> if (results.putIfAbsent(id, r) == null) { doneCount.incrementAndGet(); report(); onManifest(id, r) } }
            }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                activity.set(System.currentTimeMillis())
                val id = (item as? PubFileItem)?.pubFile ?: return
                AppLog.e("Gagal ambil daftar file $id", error)
                Result.failure<List<ModFiles.RemoteFile>>(error).let { r -> if (results.putIfAbsent(id, r) == null) { doneCount.incrementAndGet(); report(); onManifest(id, r) } }
            }

            override fun onStatusUpdate(message: String) {
                activity.set(System.currentTimeMillis())
            }
        })

        try {
            order.forEach { id ->
                dd.add(
                    PubFileItem(
                        appId = WorkshopApi.RIMWORLD_APP_ID,
                        pubFile = id,
                        installToGameNameDirectory = false,
                        installDirectory = dirs[id]!!.absolutePath,
                        verify = false,
                        downloadManifestOnly = true,
                    )
                )
            }
            dd.finishAdding()
            while (results.size < order.size) {
                delay(200)
                if (System.currentTimeMillis() - activity.get() > 45_000) {
                    AppLog.w("Ambil daftar file: tidak ada kemajuan 45 detik, berhenti")
                    break
                }
            }
        } finally {
            try {
                dd.close()
            } catch (_: Exception) {
            }
        }
        order.associateWith { id -> results[id] ?: parse(id).takeIf { it.isSuccess } ?: Result.failure(Exception("Steam tidak merespons")) }
    }

    private suspend fun runDepot(
        ctx: Context,
        workshopId: Long,
        dir: File,
        manifestOnly: Boolean,
        onStatus: (String) -> Unit,
        onFraction: (Float) -> Unit = {},
        onChunk: (Long) -> Unit = {},
        onFile: (String) -> Unit = {},
        onlyFiles: Set<String>? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        // Sambung ulang otomatis (pakai token tersimpan) kalau koneksi sempat putus.
        if (!loggedIn || client?.isConnected != true) ensureLoggedIn(ctx)
        val c = client ?: throw LoginRequired()

        val done = CompletableDeferred<Unit>()
        val activity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        workshopTypePatched
        val dd = DepotDownloader(
            steamClient = c,
            licenses = licenses,
            debug = true,
            useLanCache = false,
            maxDownloads = 48, // potongan mod kecil-kecil: banyak permintaan paralel = jauh lebih cepat
            maxDecompress = 4,
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
                onStatus("Download: $fileName")
                onFraction(depotPercentComplete)
                onFile(fileName)
            }

            override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                activity.set(System.currentTimeMillis())
                onFraction(depotPercentComplete)
                onChunk(uncompressedBytes)
            }
        })
        dd.getCompletion().whenComplete { _, err ->
            if (err != null) done.completeExceptionally(err) else done.complete(Unit)
        }

        val partial = onlyFiles != null && restrictFiles(dd, onlyFiles)

        try {
            dd.add(
                PubFileItem(
                    appId = WorkshopApi.RIMWORLD_APP_ID,
                    pubFile = workshopId,
                    installToGameNameDirectory = false,
                    installDirectory = dir.absolutePath,
                    verify = false,
                    downloadManifestOnly = manifestOnly,
                )
            )
            dd.finishAdding()
            // Tunggu selesai, tapi gagal kalau terlalu lama tidak ada kemajuan.
            val limit = if (manifestOnly) 90_000L else 180_000L
            while (!done.isCompleted) {
                delay(300)
                if (System.currentTimeMillis() - activity.get() > limit) {
                    throw Exception("Steam tidak merespons (macet ${limit / 1000} detik)")
                }
            }
            done.await()
        } finally {
            try {
                dd.close()
            } catch (_: Exception) {
            }
        }
        partial
    }
}
