package com.winlator.store

import android.content.Context
import android.util.Log
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import com.winlator.R
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Steam depot download engine — uses JavaSteam's built-in DepotDownloader.
 *
 * Replaces the hand-rolled HTTP approach. DepotDownloader handles:
 *   - manifest request codes (CM connection)
 *   - CDN auth tokens (CM connection)
 *   - depot key requests (CM connection)
 *   - chunk downloading via Ktor CIO HTTP
 *   - AES-ECB decryption + VZip/LZMA decompression
 */
object SteamDepotDownloader {

    private const val TAG = "SteamDepot"

    // -------------------------------------------------------------------------
    // Active download tracking — used by UI to detect stale DL_DOWNLOADING rows
    // -------------------------------------------------------------------------

    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<Int, Unit>()

    /** True if a download for this appId is currently running in this process. */
    @JvmStatic fun isDownloading(appId: Int): Boolean = activeDownloads.containsKey(appId)

    // -------------------------------------------------------------------------
    // Debug log — written to getExternalFilesDir/steam_debug.txt
    // -------------------------------------------------------------------------

    private var debugLogFile: File? = null
    val debugLogPath: String get() = debugLogFile?.absolutePath ?: "(not initialized)"

    private fun initDebugLog(ctx: Context) {
        try {
            val dir = ctx.getExternalFilesDir(null)
            if (dir != null) {
                debugLogFile = File(dir, "steam_debug.txt")
                BufferedWriter(FileWriter(debugLogFile!!, false)).use { w ->
                    w.write("=== Steam DepotDownloader Debug Log (JavaSteam native) ===\n")
                    w.write("Engine: JavaSteam DepotDownloader (Ktor CIO)\n")
                    w.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
                }
                dlog("Debug log: ${debugLogFile!!.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create debug log: ${e.message}")
        }
    }

    private fun dlog(msg: String) {
        Log.i(TAG, msg)
        debugLogFile ?: return
        try {
            BufferedWriter(FileWriter(debugLogFile!!, true)).use { w ->
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                w.write("[$ts] $msg\n")
            }
        } catch (_: Exception) {}
    }

    private fun dlogError(msg: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        dlog("$msg: ${t.message}")
        dlog("Stack: $sw")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returned by installApp() / resumeApp() — provides independent cancel and pause controls. */
    class DownloadControl(val cancel: Runnable, val pause: Runnable)

    /** Java-compatible singleton accessor. */
    @JvmStatic fun getInstance(): SteamDepotDownloader = this

    /**
     * Start a fresh install. Returns a DownloadControl with cancel + pause Runnables.
     * @param threads number of parallel chunk downloads + decompression workers (4 / 8 / 16)
     */
    fun installApp(appId: Int, ctx: Context, threads: Int = 4): DownloadControl =
        buildControl(appId, ctx, threads, isResume = false)

    /**
     * Resume a previously paused install. Keeps the existing DB row (bytes intact).
     * DepotDownloader will re-verify and skip already-written chunks where possible.
     */
    fun resumeApp(appId: Int, ctx: Context, threads: Int = 4): DownloadControl =
        buildControl(appId, ctx, threads, isResume = true)

    private fun buildControl(appId: Int, ctx: Context, threads: Int, isResume: Boolean): DownloadControl {
        val cancelled     = AtomicBoolean(false)
        val paused        = AtomicBoolean(false)
        val downloaderRef = AtomicReference<DepotDownloader?>(null)

        CoroutineScope(Dispatchers.IO).launch {
            runInstall(appId, ctx, cancelled, paused, downloaderRef, threads, isResume)
        }

        return DownloadControl(
            cancel = Runnable {
                if (cancelled.compareAndSet(false, true)) {
                    paused.set(false)  // cancel overrides pause
                    dlog("Cancel requested for appId=$appId")
                    downloaderRef.get()?.let { try { it.close() } catch (_: Exception) {} }
                }
            },
            pause = Runnable {
                if (!cancelled.get() && paused.compareAndSet(false, true)) {
                    dlog("Pause requested for appId=$appId")
                    downloaderRef.get()?.let { try { it.close() } catch (_: Exception) {} }
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // Core install logic
    // -------------------------------------------------------------------------

    private fun runInstall(
        appId: Int,
        ctx: Context,
        cancelled: AtomicBoolean,
        paused: AtomicBoolean,
        downloaderRef: AtomicReference<DepotDownloader?>,
        threads: Int = 4,
        isResume: Boolean = false,
    ) {
        activeDownloads[appId] = Unit
        initDebugLog(ctx)
        dlog("=== Starting install: appId=$appId ===")

        val repo = SteamRepository.getInstance()
        val steamClient = repo.steamClient
        if (steamClient == null) {
            dlog("FAIL: SteamClient is null — not connected to Steam")
            emitFailed(appId, ctx.getString(R.string.steam_not_connected))
            return
        }
        dlog("SteamClient: connected=${repo.isConnected}, loggedIn=${repo.isLoggedIn}")

        // DepotDownloader performs live CM jobs (app info, PICS access tokens,
        // depot keys, CDN auth) through the shared client. JavaSteam fails every
        // job instantly with AsyncJobFailedException when the client is not
        // connected, so make sure the session is live — with auto-login from the
        // saved refresh token — before touching DepotDownloader.
        if (!repo.isConnected || !repo.isLoggedIn) {
            dlog("Session not live — calling repo.connect() and waiting for login...")
            try {
                repo.connect()
            } catch (e: Exception) {
                dlog("repo.connect() threw: ${e.message}")
            }
            val deadline = System.currentTimeMillis() + 45_000L
            while (System.currentTimeMillis() < deadline) {
                if (cancelled.get()) {
                    dlog("Reconnect wait cancelled by user")
                    emitFailed(appId, ctx.getString(R.string.store_download_cancelled))
                    return
                }
                if (repo.isConnected && repo.isLoggedIn) break
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    emitFailed(appId, ctx.getString(R.string.store_download_cancelled))
                    return
                }
            }
            dlog("Session after wait: connected=${repo.isConnected}, loggedIn=${repo.isLoggedIn}")
            if (!repo.isConnected || !repo.isLoggedIn) {
                emitFailed(appId, ctx.getString(R.string.steam_not_connected_retry))
                return
            }
        }

        var licenses = repo.getLicenses()
        dlog("Licenses: ${licenses.size} entries")
        if (licenses.isEmpty()) {
            // LicenseListCallback arrives right after logon; give the sync a
            // grace period before proceeding (owned games need their licenses
            // for PICS access tokens and depot keys).
            dlog("License list empty — waiting briefly for post-login sync...")
            val licDeadline = System.currentTimeMillis() + 10_000L
            while (licenses.isEmpty() && System.currentTimeMillis() < licDeadline && !cancelled.get()) {
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                licenses = repo.getLicenses()
            }
            dlog("Licenses after wait: ${licenses.size} entries")
        }
        if (licenses.isEmpty()) {
            dlog("WARNING: license list is empty — DepotDownloader may not find any depots")
        }

        val db = repo.database
        val row = db.getGame(appId)
        if (row == null) {
            dlog("FAIL: appId=$appId not found in database")
            emitFailed(appId, ctx.getString(R.string.steam_no_db_game))
            return
        }
        dlog("Game: name='${row.name}' type=${row.type} sizeBytes=${row.sizeBytes}")

        // Sanitise game name for directory usage
        val safeName = row.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val installDir = File(StorePaths.storeDir(ctx, StorePaths.STEAM_DIR), safeName)
        dlog("Install dir: ${installDir.absolutePath}")

        // total bytes from PICS size data (falls back to depot manifest sum)
        val hasPicsSize: Boolean
        val totalExpected: Long = if (row.sizeBytes > 0L) {
            hasPicsSize = true
            row.sizeBytes
        } else {
            hasPicsSize = false
            db.getDepotManifests(appId).sumOf { it.sizeBytes }.let { if (it > 0L) it else 1L }
        }
        dlog("Expected total: ${fmtSize(totalExpected)} (hasPicsSize=$hasPicsSize)")

        // Queue in DB so UI shows progress (skip reset on resume — keep existing bytes)
        if (isResume) {
            db.markDownloadResuming(appId)
        } else {
            db.queueDownload(appId, totalExpected, installDir.absolutePath)
        }

        // Track bytes across all depots (DepotDownloader reports per-depot %)
        val bytesDownloaded = AtomicLong(0L)
        // Running total — updated from chunk data when PICS size was wrong/zero
        val totalRunning = AtomicLong(totalExpected)

        dlog("Constructing DepotDownloader(androidEmulation=true, maxDownloads=$threads, maxDecompress=$threads, debug=true)")
        val downloader = try {
            DepotDownloader(
                steamClient = steamClient,
                licenses = licenses,
                debug = true,
                androidEmulation = true,   // forces Windows OS filter — essential for games
                maxDownloads = threads,
                maxDecompress = threads,
                autoStartDownload = false,
            )
        } catch (e: Exception) {
            dlog("FAIL: DepotDownloader constructor threw")
            dlogError("DepotDownloader()", e)
            emitFailed(appId, ctx.getString(R.string.steam_dd_init_failed, e.message ?: ""))
            return
        }
        dlog("DepotDownloader constructed OK")
        downloaderRef.set(downloader)

        downloader.addListener(object : IDownloadListener {
            override fun onDownloadStarted(item: DownloadItem) {
                dlog("onDownloadStarted: appId=${item.appId}")
                repo.emit("DownloadProgress:$appId:0:${totalRunning.get()}")
            }

            override fun onStatusUpdate(message: String) {
                dlog("Status: $message")
            }

            override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                val pct = (depotPercentComplete * 100).toInt()
                dlog("File done: depot=$depotId pct=$pct% file=$fileName")
            }

            override fun onChunkCompleted(
                depotId: Int,
                depotPercentComplete: Float,
                compressedBytes: Long,
                uncompressedBytes: Long,
            ) {
                // uncompressedBytes is cumulative per-depot; update if it grew
                val prev = bytesDownloaded.get()
                if (uncompressedBytes > prev) bytesDownloaded.set(uncompressedBytes)
                val done = bytesDownloaded.get()

                // Only back-calculate when PICS gave no valid size — avoids
                // totalRunning spiking to inflated values (e.g. 81KB / 0.001% = 4.3 GB)
                // on large depots where pct stays near 0 for many chunks.
                if (!hasPicsSize && depotPercentComplete > 0.05f && done > 0L) {
                    val implied = (done.toDouble() / depotPercentComplete).toLong()
                    if (implied > totalRunning.get()) totalRunning.set(implied)
                }
                val total = totalRunning.get()

                // Clamp to 99% — 100% is reserved for onDownloadCompleted
                val pct = minOf((depotPercentComplete * 100).toInt(), 99)
                dlog("Chunk: depot=$depotId pct=$pct% cumulative=${fmtSize(done)}/${fmtSize(total)}")
                repo.emit("DownloadProgress:$appId:$done:$total")
                db.updateDownloadProgress(appId, done)
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                dlog("Depot $depotId complete: ${fmtSize(uncompressedBytes)} uncompressed / ${fmtSize(compressedBytes)} compressed")
            }

            override fun onDownloadCompleted(item: DownloadItem) {
                dlog("=== Download complete: appId=${item.appId} ===")
                val finalBytes = bytesDownloaded.get()
                val finalTotal = totalRunning.get()
                // Emit 100% before switching to installed state
                repo.emit("DownloadProgress:$appId:$finalTotal:$finalTotal")
                db.markInstalled(appId, installDir.absolutePath, finalBytes)
                repo.emit("DownloadComplete:$appId")
            }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                if (cancelled.get()) {
                    // Cancel path: finally block guarantees DownloadCancelled is emitted.
                    dlog("=== Download cancelled by user: appId=${item.appId} ===")
                } else {
                    dlog("=== Download FAILED: appId=${item.appId} ===")
                    dlogError("onDownloadFailed", error)
                    // AsyncJobFailedException with a null message is what JavaSteam
                    // raises when a CM job is attempted while disconnected — surface
                    // that clearly instead of "Unknown error".
                    val reason = if (error is `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException &&
                        !repo.isConnected) {
                        "Lost connection to Steam — retry"
                    } else {
                        error.message ?: "Unknown error"
                    }
                    emitFailed(appId, reason)
                }
            }
        })

        val item = AppItem(
            appId = appId,
            installDirectory = installDir.absolutePath,
            branch = "public",
            // Explicitly request Windows depots — don't let Util.getSteamOS() guess,
            // since androidEmulation only works if IS_OS_ANDROID is true at runtime.
            os = "windows",
            // Skip arch filtering — we always want the game's Windows depots regardless
            // of what os.arch returns on this Android device (arm64, aarch64, armv8l, etc.).
            // Wine/Box64 handles x86_64 translation; arch mismatch would filter all depots.
            downloadAllArchs = true,
        )
        dlog("Adding AppItem: appId=${item.appId} branch=${item.branch} dir=${item.installDirectory}")
        downloader.add(item)
        downloader.finishAdding()

        dlog("Calling startDownloading()...")
        try {
            downloader.startDownloading()
            dlog("startDownloading() returned (download loop running in background)")
        } catch (e: Exception) {
            dlog("FAIL: startDownloading() threw")
            dlogError("startDownloading", e)
            emitFailed(appId, ctx.getString(R.string.steam_dd_start_failed, e.message ?: ""))
            try { downloader.close() } catch (_: Exception) {}
            return
        }

        dlog("Blocking on getCompletion().get()...")
        var completedNormally = false
        try {
            downloader.getCompletion().get()
            completedNormally = true
            dlog("getCompletion() returned — download finished")
        } catch (e: ExecutionException) {
            dlog("getCompletion() ExecutionException: ${e.cause?.message ?: e.message}")
            dlogError("ExecutionException.cause", e.cause ?: e)
        } catch (e: InterruptedException) {
            dlog("getCompletion() interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            dlog("getCompletion() unexpected exception: ${e.message}")
            dlogError("getCompletion unexpected", e)
        } finally {
            activeDownloads.remove(appId)
            dlog("Closing DepotDownloader")
            try { downloader.close() } catch (_: Exception) {}
            downloaderRef.set(null)
            if (!completedNormally) {
                when {
                    paused.get() -> {
                        // Pause path: keep files + DB row, just mark paused
                        dlog("finally: paused=true — marking DL_PAUSED")
                        db.markDownloadPaused(appId, bytesDownloaded.get())
                        repo.emit("DownloadPaused:$appId")
                    }
                    cancelled.get() -> {
                        // Cancel path: delete files + row
                        dlog("finally: cancelled=true — ensuring DownloadCancelled emitted")
                        db.deleteDownload(appId)
                        repo.emit("DownloadCancelled:$appId")
                    }
                }
            }
            dlog("=== runInstall() finished ===")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun emitFailed(appId: Int, reason: String) {
        SteamRepository.getInstance().database.markDownloadFailed(appId, reason)
        SteamRepository.getInstance().emit("DownloadFailed:$appId:$reason")
        Log.e(TAG, "DownloadFailed $appId: $reason")
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1024.0)
    }
}
