// PvZ2 Gardendless Android Port
// Copyright (C) 2026  Open Source Gardendless Contributors
// License: GPL-3.0

package com.pvzge.gardendless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class GameActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val FILE_CHOOSER_RESULT_CODE = 101
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var extractionDialog: android.app.Dialog? = null
    private val NOTIFICATION_CHANNEL_ID = "gardendless_extraction"
    private val NOTIFICATION_ID = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setupFullScreen()
        createNotificationChannel()

        // Pre-warm WebView engine during extraction
        webView = MouseGameWebView(this)

        val sp = getSharedPreferences("app_data", MODE_PRIVATE)
        val savedVersion = sp.getInt("extracted_version", 0)
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

        if (savedVersion != currentVersion) {
            if (savedVersion > 0) {
                showPreUpdateWarning(currentVersion, sp)
            } else {
                checkAndExtractAssets(currentVersion, sp)
            }
        } else {
            setupWebview()
        }
    }

    private fun checkAndExtractAssets(currentVersion: Int, sp: android.content.SharedPreferences) {
        // Get total size for progress tracking
        val zipPath = File(filesDir, "temp_game.zip")
        try {
            assets.open("pvzge_web.zip").use { input ->
                zipPath.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            showExtractionError(currentVersion, sp)
            return
        }

        val zipFile = ZipFile(zipPath)
        val totalEntries = zipFile.entries().toList().size
        val totalSize = zipFile.entries().asSequence().map { it.size }.sum()
        zipFile.close()

        // Build extraction dialog with determinate progress
        val progressLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val progressText = TextView(this).apply {
            text = getString(R.string.unzipping)
            setPadding(0, 10, 0, 0)
        }
        progressLayout.addView(progressBar)
        progressLayout.addView(progressText)

        extractionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unzipping)
            .setMessage(getString(R.string.description) + "\nv" + packageManager.getPackageInfo(packageName, 0).versionName)
            .setView(progressLayout)
            .setCancelable(false)
            .create()
        extractionDialog?.show()

        // Atomic extraction: extract to temp dir, swap on success, rollback on failure
        lifecycleScope.launch(Dispatchers.IO) {
            val destNew = File(filesDir, "pvzge_web_new")
            val destCurrent = File(filesDir, "pvzge_web")
            val destBackup = File(filesDir, "pvzge_web_backup")

            try {
                // Clean up stale temp dirs from previous failed attempts
                destNew.deleteRecursively()
                destBackup.deleteRecursively()

                // Extract to temp directory first
                var bytesExtracted = 0L
                ZipInputStream(zipPath.inputStream()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val file = File(destNew, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { zis.copyTo(it) }
                            bytesExtracted += entry.size
                        }
                        // Update progress
                        if (totalSize > 0) {
                            val pct = ((bytesExtracted * 100) / totalSize).toInt()
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = pct
                                    progressText.text = getString(R.string.extraction_progress, pct)
                                }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }

                // Atomic swap: backup old → move new into place → delete backup
                if (destCurrent.exists()) {
                    destCurrent.renameTo(destBackup)
                }
                val success = destNew.renameTo(destCurrent)
                if (!success) throw IllegalStateException("Failed to rename extraction directory")
                destBackup.deleteRecursively()

                sp.edit().putInt("extracted_version", currentVersion).apply()
                zipPath.delete()

                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    withContext(Dispatchers.Main) {
                        extractionDialog?.dismiss()
                        extractionDialog = null
                        setupWebview()
                        showExtractionNotification()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Rollback: restore backup if it exists
                destNew.deleteRecursively()
                if (destBackup.exists() && !destCurrent.exists()) {
                    destBackup.renameTo(destCurrent)
                }
                zipPath.delete()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    withContext(Dispatchers.Main) {
                        extractionDialog?.dismiss()
                        extractionDialog = null
                        showExtractionError(currentVersion, sp)
                    }
                }
            }
        }
    }

    private fun showExtractionError(currentVersion: Int, sp: android.content.SharedPreferences) {
        // Error UI with retry
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hint)
            .setMessage(R.string.extraction_error)
            .setPositiveButton(R.string.retry) { _, _ ->
                checkAndExtractAssets(currentVersion, sp)
            }
            .setNegativeButton(R.string.close_app) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows a warning dialog before updating game files. Save data may become
     * incompatible between game versions — same risk as noted in release notes.
     */
    private fun showPreUpdateWarning(currentVersion: Int, sp: android.content.SharedPreferences) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_warning)
            .setPositiveButton(R.string.update_continue) { _, _ ->
                checkAndExtractAssets(currentVersion, sp)
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                // Keep using the old version this session
                setupWebview()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupWebview() {
        // WebView was pre-warmed in onCreate
        // Apply GPU and security settings

        val container = object : android.widget.FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val sw = measuredWidth
                val sh = measuredHeight
                val tw: Int
                val th: Int
                if (sw * 9 > sh * 16) {
                    tw = sh * 16 / 9
                    th = sh
                } else {
                    tw = sw
                    th = sw * 9 / 16
                }
                getChildAt(0).measure(
                    MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY)
                )
            }
        }
        container.setBackgroundColor(android.graphics.Color.BLACK)
        val lp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER }
        container.addView(webView, lp)
        setContentView(container)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler(
                "/",
                InternalStoragePathHandler(this, File(filesDir, "pvzge_web"))
            )
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            // GPU performance settings
            @Suppress("DEPRECATION")
            setAlgorithmicDarkeningAllowed(false)
            safeBrowsingEnabled = false
        }

        // Remote debugging in debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // GPU process priority — requires androidx.webkit 1.16+
        // TODO: Enable when webkit dependency is updated
        // if (Build.VERSION.SDK_INT >= 34) {
        //     webView.setRenderProcessPriority(WebView.RENDERER_PRIORITY_IMPORTANT)
        // }

        // Save file export (timestamped filename)
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.startsWith("data:")) {
                try {
                    val data: String
                    // Proper base64 decoding
                    if (url.contains(";base64,")) {
                        val base64Part = url.substringAfter(";base64,").split(",").firstOrNull() ?: return@setDownloadListener
                        data = String(Base64.decode(base64Part, Base64.DEFAULT))
                    } else {
                        val parts = url.split(",")
                        if (parts.size < 2) return@setDownloadListener
                        data = Uri.decode(parts.subList(1, parts.size).joinToString(","))
                    }
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val cacheFile = File(cacheDir, "gardendless_save_${timestamp}.json")
                    cacheFile.writeText(data)
                    val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cacheFile)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.export)))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://appassets.androidplatform.net/")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    return true
                } catch (e: Exception) { return false }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                error?.let {
                    android.util.Log.e("Gardendless", "WebView error: ${it.errorCode} ${it.description} for ${request?.url}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
            ) {
                android.util.Log.e("Gardendless", "HTTP ${errorResponse?.statusCode} for ${request?.url}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Show gesture hints on first launch with a short delay
                webView.postDelayed({
                    val sp = getSharedPreferences("app_data", MODE_PRIVATE)
                    if (sp.getInt("extracted_version", 0) == 1) {
                        showGestureHints()
                    }
                }, 3000)
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val response = assetLoader.shouldInterceptRequest(request.url) ?: return null
                // Inject touch-blocking script into index.html before browser parses it
                if (request.url.toString() == "https://appassets.androidplatform.net/index.html") {
                    return injectTouchBlockerIntoHtml(response)
                }
                return response
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let { android.util.Log.d("Gardendless", "[${it.messageLevel()}] ${it.message()}") }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                this@GameActivity.filePathCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/octet-stream", "text/plain"))
                }
                return try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE)
                    true
                } catch (e: Exception) {
                    this@GameActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/index.html")
        setupBackNavigation()
        // Check for app updates in background (doesn't block game)
        checkForAppUpdate()
    }

    /**
     * Injects touch-blocking JavaScript directly into index.html before the browser
     * parses it. Uses a MutationObserver to attach capture-phase listeners the moment
     * GameCanvas is created — zero polling delay, works for both WebGL and Canvas2D.
     */
    private fun injectTouchBlockerIntoHtml(response: WebResourceResponse): WebResourceResponse {
        val html = response.data.bufferedReader().readText()
        val tag = """
<script>
(function(){
var o=new MutationObserver(function(){
var c=document.getElementById('GameCanvas');
if(c){
c.addEventListener('touchstart',function(e){e.preventDefault();e.stopImmediatePropagation()},{capture:true,passive:false});
c.addEventListener('touchmove',function(e){e.preventDefault();e.stopImmediatePropagation()},{capture:true,passive:false});
c.addEventListener('touchend',function(e){e.preventDefault();e.stopImmediatePropagation()},{capture:true,passive:false});
o.disconnect()}});
o.observe(document.body||document.documentElement,{childList:true,subtree:true})})();
</script>
</body>""".trimIndent()
        val modified = html.replace("</body>", tag)
        return WebResourceResponse(
            response.mimeType,
            response.encoding,
            modified.byteInputStream()
        )
    }
    // Gesture hint overlay for first-time users
    private fun showGestureHints() {
        val js = """
(function() {
    var overlay = document.createElement('div');
    overlay.id = 'gardendless-gesture-hints';
    overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;' +
        'background:rgba(0,0,0,0.7);z-index:99999;display:flex;flex-direction:column;' +
        'align-items:center;justify-content:center;color:white;font-family:sans-serif;' +
        'transition:opacity 0.5s;pointer-events:none;';
    overlay.innerHTML = '<div style="font-size:24px;margin-bottom:30px;">Controls</div>' +
        '<div style="font-size:16px;margin:10px;">&#128070; ' +
            '${getString(R.string.gesture_click)}</div>' +
        '<div style="font-size:16px;margin:10px;">&#9995; ' +
            '${getString(R.string.gesture_scroll)}</div>' +
        '<div style="font-size:16px;margin:10px;">&#128076; ' +
            '${getString(R.string.gesture_rightclick)}</div>' +
        '<div style="font-size:16px;margin:10px;">&#9201; ' +
            '${getString(R.string.longpress_rightclick)}</div>';
    document.body.appendChild(overlay);

    function dismiss() {
        overlay.style.opacity = '0';
        setTimeout(function() { overlay.remove(); }, 500);
    }
    setTimeout(dismiss, 8000);
    document.addEventListener('touchstart', function() { dismiss(); }, { once: true });
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            val result = if (data == null || resultCode != RESULT_OK) null else arrayOf(data.data!!)
            filePathCallback?.onReceiveValue(result as Array<Uri>?)
            filePathCallback = null
        }
    }

    private fun setupFullScreen() {
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // FLAG_SECURE disabled by default, configurable via SharedPreferences
        if (getSharedPreferences("app_data", MODE_PRIVATE).getBoolean("flag_secure", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hint)
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> finish() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // Local notification when extraction completes
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_extraction),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Extraction progress notifications" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun showExtractionNotification() {
        val intent = Intent(this, GameActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_extraction_done))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    // --- In-app updater ---

    private var updateCheckInProgress = false

    private fun checkForAppUpdate() {
        if (updateCheckInProgress) return
        val sp = getSharedPreferences("app_data", MODE_PRIVATE)
        val lastCheck = sp.getLong("last_update_check", 0)
        if (System.currentTimeMillis() - lastCheck < 24 * 60 * 60 * 1000) return // once per day

        updateCheckInProgress = true
        sp.edit().putLong("last_update_check", System.currentTimeMillis()).apply()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://api.github.com/repos/MrPurple666/pvzge-android/releases/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) { conn.disconnect(); return@launch }
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val tagName = json.optString("tag_name", "").removePrefix("v")
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: return@launch
                if (tagName == currentVersion) return@launch

                val remote = tagName.split(".").map { it.toIntOrNull() ?: 0 }
                val local = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
                val isNewer = (0 until maxOf(remote.size, local.size)).any { i ->
                    (remote.getOrElse(i) { 0 }) > (local.getOrElse(i) { 0 })
                }
                if (!isNewer) return@launch

                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                var apkSize = 0L
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        apkSize = asset.getLong("size")
                        break
                    }
                }
                if (apkUrl == null) return@launch

                val body = json.optString("body", "").take(300)
                withContext(Dispatchers.Main) {
                    showUpdateDialog(tagName, body, apkUrl, apkSize)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                updateCheckInProgress = false
            }
        }
    }

    private fun showUpdateDialog(version: String, changelog: String, apkUrl: String, apkSize: Long) {
        val sizeStr = when {
            apkSize > 1_000_000_000 -> "${"%.1f".format(apkSize / 1_000_000_000.0)} GB"
            apkSize > 1_000_000 -> "${"%.1f".format(apkSize / 1_000_000.0)} MB"
            else -> "${apkSize / 1024} KB"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("v$version available ($sizeStr)")
            .setMessage(changelog.ifEmpty { "New version available." })
            .setPositiveButton("Download") { _, _ -> downloadAndInstallApk(apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstallApk(url: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading update…")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notif)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                val total = conn.contentLength.toLong()
                val apkFile = File(cacheDir, "update.apk")

                var downloaded = 0L
                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                withContext(Dispatchers.Main) {
                                    val progress = NotificationCompat.Builder(this@GameActivity, NOTIFICATION_CHANNEL_ID)
                                        .setSmallIcon(android.R.drawable.stat_sys_download)
                                        .setContentTitle("Downloading update…")
                                        .setProgress(100, pct, false)
                                        .setOngoing(true)
                                        .build()
                                    nm.notify(NOTIFICATION_ID + 1, progress)
                                }
                            }
                        }
                    }
                }

                nm.cancel(NOTIFICATION_ID + 1)

                withContext(Dispatchers.Main) {
                    val apkUri = FileProvider.getUriForFile(
                        this@GameActivity, "${packageName}.fileprovider", apkFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                nm.cancel(NOTIFICATION_ID + 1)
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(this@GameActivity)
                        .setTitle("Download failed")
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
}
