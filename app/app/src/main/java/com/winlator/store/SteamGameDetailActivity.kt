package com.winlator.store

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.net.URL

import com.winlator.R

/**
 * Game detail screen — shows header art, metadata, Install/Cancel/Launch buttons.
 *
 * Cancel: installBtn toggles Install → Cancel while downloading (same pattern as Epic/GOG/Amazon).
 * Launch: uses AmazonLaunchHelper.choosePrimaryExe() + picker dialog for multiple exes.
 */
class SteamGameDetailActivity : Activity(), SteamRepository.SteamEventListener {

    companion object {
        const val EXTRA_APP_ID = "steam_app_id"
        private const val COLOR_INSTALL   = 0xFF1565C0.toInt()
        private const val COLOR_CANCEL    = 0xFFCC3333.toInt()
        private const val COLOR_UNINSTALL = 0xFFB71C1C.toInt()
        private const val COLOR_LAUNCH    = 0xFF2E7D32.toInt()
        private const val COLOR_PAUSE     = 0xFFE65100.toInt()  // orange
        private const val COLOR_RESUME    = 0xFF2E7D32.toInt()  // green
    }

    private val ui = Handler(Looper.getMainLooper())
    private var appId: Int = 0
    private var game: SteamGame? = null

    @Volatile private var downloadHandle: SteamDepotDownloader.DownloadControl? = null
    private var lastThreadCount = 4

    // views updated after load
    private lateinit var headerImage: ImageView
    private lateinit var nameText: TextView
    private lateinit var typeText: TextView
    private lateinit var sizeText: TextView
    private lateinit var statusText: TextView
    private lateinit var installBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var launchBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getIntExtra(EXTRA_APP_ID, 0)
        if (appId == 0) { finish(); return }

        // Defensive init: handle cold-start after process crash
        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)

        setContentView(buildUI())
        SteamRepository.getInstance().addListener(this)
        loadGame()
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // SteamRepository.SteamEventListener
    // -------------------------------------------------------------------------

    override fun onEvent(event: String) {
        when {
            event.startsWith("DownloadProgress:") -> {
                val parts = event.split(":")
                val id    = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val done  = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val total = parts.getOrNull(3)?.toLongOrNull() ?: 1L
                val pct   = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                ui.post {
                    progressBar.visibility  = View.VISIBLE
                    progressBar.progress    = pct
                    progressText.visibility = View.VISIBLE
                    progressText.text       = getString(R.string.steam_dl_progress, pct, fmtSize(done), fmtSize(total))
                    installBtn.isEnabled    = true
                    installBtn.text         = getString(R.string.store_cancel)
                    installBtn.setBackgroundColor(COLOR_CANCEL)
                    pauseBtn.isEnabled      = true
                    pauseBtn.alpha          = 1f
                    pauseBtn.text           = getString(R.string.store_pause)
                    pauseBtn.setBackgroundColor(COLOR_PAUSE)
                }
            }
            event.startsWith("DownloadPaused:") -> {
                val id = event.substringAfter("DownloadPaused:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                ui.post {
                    val dlRow = SteamRepository.getInstance().database.getDownload(appId)
                    val done  = dlRow?.bytesDownloaded ?: 0L
                    val total = dlRow?.bytesTotal ?: 0L
                    val pct   = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                    progressBar.visibility  = View.VISIBLE
                    progressBar.progress    = pct
                    progressText.visibility = View.VISIBLE
                    progressText.text       = getString(R.string.steam_dl_paused, pct, fmtSize(done), fmtSize(total))
                    installBtn.isEnabled    = true
                    installBtn.text         = getString(R.string.store_cancel)
                    installBtn.setBackgroundColor(COLOR_CANCEL)
                    pauseBtn.isEnabled      = true
                    pauseBtn.alpha          = 1f
                    pauseBtn.text           = getString(R.string.store_resume)
                    pauseBtn.setBackgroundColor(COLOR_RESUME)
                }
            }
            event.startsWith("DownloadComplete:") -> {
                val id = event.substringAfter("DownloadComplete:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                ui.post {
                    progressBar.visibility  = View.GONE
                    progressText.visibility = View.GONE
                    resetPauseBtn()
                    loadGame()
                }
            }
            event.startsWith("DownloadCancelled:") -> {
                val id = event.substringAfter("DownloadCancelled:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                ui.post {
                    progressBar.visibility  = View.GONE
                    progressText.visibility = View.GONE
                    statusText.text = getString(R.string.store_download_cancelled)
                    statusText.setTextColor(Color.parseColor("#AAAAAA"))
                    installBtn.isEnabled = true
                    installBtn.text = getString(R.string.store_install)
                    installBtn.setBackgroundColor(COLOR_INSTALL)
                    resetPauseBtn()
                }
            }
            event.startsWith("DownloadFailed:") -> {
                val parts = event.split(":")
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val reason = parts.drop(2).joinToString(":")
                val logPath = SteamDepotDownloader.debugLogPath
                downloadHandle = null
                ui.post {
                    progressBar.visibility  = View.GONE
                    progressText.visibility = View.GONE
                    statusText.text = getString(R.string.steam_dl_failed, reason, logPath)
                    statusText.setTextColor(Color.parseColor("#FF5555"))
                    installBtn.isEnabled = true
                    installBtn.text = getString(R.string.store_retry)
                    installBtn.setBackgroundColor(COLOR_INSTALL)
                    resetPauseBtn()
                }
            }
        }
    }

    private fun resetPauseBtn() {
        pauseBtn.isEnabled = false
        pauseBtn.alpha     = 0.4f
        pauseBtn.text      = getString(R.string.store_pause)
        pauseBtn.setBackgroundColor(COLOR_PAUSE)
    }

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    private fun loadGame() {
        val row = SteamRepository.getInstance().database.getGame(appId)
        if (row == null) { finish(); return }
        game = SteamGame.fromGameRow(row)
        refreshUI()
        loadHeaderImage()

        // Check for an active / paused download
        val dlRow = SteamRepository.getInstance().database.getDownload(appId)
        if (dlRow != null) {
            val pct = if (dlRow.bytesTotal > 0) (dlRow.bytesDownloaded * 100 / dlRow.bytesTotal).toInt().coerceIn(0, 100) else 0
            when (dlRow.status) {
                SteamDatabase.DL_DOWNLOADING -> {
                    if (SteamDepotDownloader.isDownloading(appId)) {
                        progressBar.visibility  = View.VISIBLE
                        progressBar.progress    = pct
                        progressText.visibility = View.VISIBLE
                        progressText.text       = getString(R.string.steam_dl_short, pct)
                        installBtn.isEnabled    = true
                        installBtn.text         = getString(R.string.store_cancel)
                        installBtn.setBackgroundColor(COLOR_CANCEL)
                        pauseBtn.isEnabled      = true
                        pauseBtn.alpha          = 1f
                        pauseBtn.text           = getString(R.string.store_pause)
                        pauseBtn.setBackgroundColor(COLOR_PAUSE)
                    } else {
                        // Stale record (app was killed mid-download) — clean up
                        SteamRepository.getInstance().database.deleteDownload(appId)
                    }
                }
                SteamDatabase.DL_PAUSED -> {
                    progressBar.visibility  = View.VISIBLE
                    progressBar.progress    = pct
                    progressText.visibility = View.VISIBLE
                    progressText.text       = getString(R.string.steam_dl_paused, pct, fmtSize(dlRow.bytesDownloaded), fmtSize(dlRow.bytesTotal))
                    installBtn.isEnabled    = true
                    installBtn.text         = getString(R.string.store_cancel)
                    installBtn.setBackgroundColor(COLOR_CANCEL)
                    pauseBtn.isEnabled      = true
                    pauseBtn.alpha          = 1f
                    pauseBtn.text           = getString(R.string.store_resume)
                    pauseBtn.setBackgroundColor(COLOR_RESUME)
                }
            }
        }
    }

    private fun refreshUI() {
        val g = game ?: return
        nameText.text = g.name.ifEmpty { getString(R.string.steam_app_fallback, g.appId) }
        typeText.text = g.type.uppercase()
        typeText.setTextColor(if (g.type == "game") Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
        sizeText.text = if (g.sizeBytes > 0) getString(R.string.steam_size_approx, fmtSize(g.sizeBytes)) else getString(R.string.steam_size_unknown)

        if (g.isInstalled) {
            statusText.text = getString(R.string.store_installed)
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            installBtn.text = getString(R.string.store_uninstall)
            installBtn.setBackgroundColor(COLOR_UNINSTALL)
            installBtn.isEnabled = true
            launchBtn.isEnabled  = true
            launchBtn.alpha      = 1f
        } else {
            statusText.text = getString(R.string.store_not_installed)
            statusText.setTextColor(Color.parseColor("#AAAAAA"))
            installBtn.text = getString(R.string.store_install)
            installBtn.setBackgroundColor(COLOR_INSTALL)
            installBtn.isEnabled = true
            launchBtn.isEnabled  = false
            launchBtn.alpha      = 0.4f
        }
    }

    private fun loadHeaderImage() {
        val url = game?.headerUrl ?: return
        Thread {
            try {
                val bmp: Bitmap = BitmapFactory.decodeStream(URL(url).openStream())
                ui.post { headerImage.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }.start()
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    private fun onInstallClicked() {
        val g = game ?: return

        // Active download — cancel immediately and reset UI without waiting for event
        val handle = downloadHandle
        if (handle != null) {
            val db = SteamRepository.getInstance().database
            val dir = db.getDownload(appId)?.installDir ?: ""
            handle.cancel.run()
            downloadHandle = null
            if (dir.isNotEmpty()) Thread { File(dir).deleteRecursively() }.start()
            progressBar.visibility  = View.GONE
            progressText.visibility = View.GONE
            statusText.text = getString(R.string.store_download_cancelled)
            statusText.setTextColor(Color.parseColor("#AAAAAA"))
            installBtn.text = getString(R.string.store_install)
            installBtn.setBackgroundColor(COLOR_INSTALL)
            installBtn.isEnabled = true
            resetPauseBtn()
            return
        }

        // Paused download — cancel also deletes files + row
        val db = SteamRepository.getInstance().database
        val dlRow = db.getDownload(appId)
        if (dlRow != null && dlRow.status == SteamDatabase.DL_PAUSED) {
            db.deleteDownload(appId)
            val dir = dlRow.installDir
            if (dir.isNotEmpty()) Thread { File(dir).deleteRecursively() }.start()
            progressBar.visibility  = View.GONE
            progressText.visibility = View.GONE
            statusText.text = getString(R.string.store_download_cancelled)
            statusText.setTextColor(Color.parseColor("#AAAAAA"))
            installBtn.text = getString(R.string.store_install)
            installBtn.setBackgroundColor(COLOR_INSTALL)
            installBtn.isEnabled = true
            resetPauseBtn()
            return
        }

        if (g.isInstalled) {
            // Uninstall — remove from DB and delete files
            SteamRepository.getInstance().database.markUninstalled(appId)
            if (g.installDir.isNotEmpty()) {
                Thread { File(g.installDir).deleteRecursively() }.start()
            }
            loadGame()
        } else {
            showDownloadSpeedPicker()
        }
    }

    private fun onPauseResumeClicked() {
        val handle = downloadHandle
        if (handle != null) {
            // Immediately flip button to Resume — don't wait for DownloadPaused event
            handle.pause.run()
            downloadHandle = null
            pauseBtn.text = getString(R.string.store_resume)
            pauseBtn.setBackgroundColor(COLOR_RESUME)
            pauseBtn.isEnabled = true
            pauseBtn.alpha = 1f
            installBtn.text = getString(R.string.store_cancel)
            installBtn.isEnabled = true
            progressText.text = getString(R.string.steam_pausing)
        } else {
            // Currently paused — resume it
            val dlRow = SteamRepository.getInstance().database.getDownload(appId) ?: return
            if (dlRow.status != SteamDatabase.DL_PAUSED) return
            pauseBtn.isEnabled = false
            pauseBtn.alpha = 0.4f
            pauseBtn.text = getString(R.string.store_resuming)
            installBtn.isEnabled = false
            installBtn.text = getString(R.string.store_starting)
            downloadHandle = SteamDepotDownloader.resumeApp(appId, applicationContext, lastThreadCount)
        }
    }

    private fun onLaunchClicked() {
        val g = game ?: return
        if (!g.isInstalled || g.installDir.isEmpty()) {
            Toast.makeText(this, getString(R.string.store_game_not_installed), Toast.LENGTH_SHORT).show()
            return
        }
        val installDir = File(g.installDir)
        Thread {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)

            if (exeFiles.isEmpty()) {
                ui.post {
                    Toast.makeText(this, getString(R.string.store_no_exe_dir), Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // Sort by score — same heuristic as Epic/Amazon
            val lowerTitle = g.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }

            // Use Steam CDN portrait cover (600x900) — StarLaunchBridge downloads it
            val coverUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${g.appId}/library_600x900.jpg"

            if (exeFiles.size == 1) {
                ui.post { StarLaunchBridge.addToLauncher(this, g.name, exeFiles[0].absolutePath, coverUrl) }
                return@Thread
            }

            // Multiple exes — show picker
            val candidates = exeFiles.map { it.absolutePath }
            showExePicker(candidates) { chosen ->
                ui.post { StarLaunchBridge.addToLauncher(this, g.name, chosen, coverUrl) }
            }
        }.start()
    }


    private fun showDownloadSpeedPicker() {
        val options = arrayOf(
            getString(R.string.steam_speed_safe),
            getString(R.string.steam_speed_normal),
            getString(R.string.steam_speed_fast)
        )
        val threadCounts = intArrayOf(4, 8, 16)
        var selected = 0  // default: Safe

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.steam_speed_title))
            .setSingleChoiceItems(options, selected) { _, which -> selected = which }
            .setPositiveButton(getString(R.string.store_download)) { _, _ ->
                lastThreadCount = threadCounts[selected]
                installBtn.isEnabled = false
                installBtn.text = getString(R.string.store_starting)
                downloadHandle = SteamDepotDownloader.installApp(appId, applicationContext, lastThreadCount)
            }
            .setNegativeButton(getString(R.string.store_cancel), null)
            .show()
    }

    private fun showExePicker(candidates: List<String>, onSelected: (String) -> Unit) {
        val labels = candidates.map { path ->
            val f = File(path)
            val parent = f.parentFile
            if (parent != null) "${parent.name}/${f.name}" else f.name
        }.toTypedArray()

        ui.post {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.store_select_exe))
                .setItems(labels) { _, which ->
                    Thread { onSelected(candidates[which]) }.start()
                }
                .setCancelable(false)
                .show()
        }
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI(): View {
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1B1B1B"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(root)

        // Back button header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#212121"))
        }
        val backBtn = Button(this).apply {
            text = getString(R.string.steam_back)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        header.addView(backBtn)
        root.addView(header)

        // Header image (16:7 aspect ratio approximation)
        headerImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#0D47A1"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180))
        }
        root.addView(headerImage)

        // Info section
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        nameText = TextView(this).apply {
            text = getString(R.string.store_loading)
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        info.addView(nameText)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        typeText = TextView(this).apply {
            text = getString(R.string.steam_type_default)
            textSize = 11f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setBackgroundColor(Color.parseColor("#263238"))
        }
        sizeText = TextView(this).apply {
            text = getString(R.string.steam_size_unknown)
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(12), 0, 0, 0)
        }
        row1.addView(typeText)
        row1.addView(sizeText)
        info.addView(row1)

        statusText = TextView(this).apply {
            text = getString(R.string.store_not_installed)
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(4), 0, dp(12))
        }
        info.addView(statusText)
        root.addView(info)

        // Progress bar (hidden until download starts)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(progressBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        progressText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(16), dp(2), dp(16), dp(4))
            visibility = View.GONE
        }
        root.addView(progressText)

        // Buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        installBtn = Button(this).apply {
            text = getString(R.string.store_install)
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_INSTALL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onInstallClicked() }
        }

        pauseBtn = Button(this).apply {
            text = getString(R.string.store_pause)
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_PAUSE)
            isEnabled = false
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onPauseResumeClicked() }
        }

        launchBtn = Button(this).apply {
            text = getString(R.string.store_launch)
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_LAUNCH)
            isEnabled = false
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onLaunchClicked() }
        }

        btnRow.addView(installBtn)
        btnRow.addView(pauseBtn)
        btnRow.addView(launchBtn)
        root.addView(btnRow)

        return scroll
    }

    private fun fmtSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
            else                    -> "%.0f KB".format(bytes / 1024.0)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
