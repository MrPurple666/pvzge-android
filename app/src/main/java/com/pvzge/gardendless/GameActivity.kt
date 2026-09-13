// PvZ2 Gardendless Android Port
// Copyright (C) 2026  Open Source Gardendless Contributors
// Fullscreen and export bridge adapted from Caten Hu, copyright (C) 2026.
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
import android.widget.Toast
import android.webkit.WebChromeClient.CustomViewCallback
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
import kotlinx.coroutines.ensureActive
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class GameActivity : AppCompatActivity() {

    private lateinit var aspectContainer: AspectRatioFrameLayout
    private val prefs by lazy { getSharedPreferences("app_data", MODE_PRIVATE) }
    private val EXPORT_SAVE_RESULT_CODE = 102
    private var pendingExport: ByteArray? = null
    @Volatile private var pendingExportName: String? = null
    private var fullscreenCallback: CustomViewCallback? = null

    private companion object {
        const val PREF_FULLSCREEN = "webview_fullscreen"
    }

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

        aspectContainer = AspectRatioFrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            fullscreen = prefs.getBoolean(PREF_FULLSCREEN, false)
            addView(webView, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER })
        }
        setContentView(aspectContainer)

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

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setFullscreen(value: Boolean) {
                webView.post { setWebviewFullscreen(value) }
            }

            @JavascriptInterface
            fun setExportName(name: String) {
                pendingExportName = name.substringAfterLast('/').substringAfterLast('\\')
            }
        }, "GardendlessBridge")

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (!url.startsWith("data:") || pendingExport != null) return@setDownloadListener
            try {
                val comma = url.indexOf(',')
                require(comma >= 0) { "Invalid data URI" }
                val payload = url.substring(comma + 1)
                pendingExport = if (url.substring(0, comma).endsWith(";base64", true)) {
                    Base64.decode(Uri.decode(payload), Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray(Charsets.UTF_8)
                }
                val fallbackType = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val name = pendingExportName?.takeIf { it.isNotBlank() }
                    ?: suggestFileName(contentDisposition, fallbackType)
                pendingExportName = null
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = mimeFromExtension(name) ?: fallbackType
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                startActivityForResult(intent, EXPORT_SAVE_RESULT_CODE)
            } catch (e: Exception) {
                pendingExport = null
                pendingExportName = null
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://appassets.androidplatform.net/")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    return true
                } catch (e: Exception) { /* Keep external pages out of the bridged WebView. */ }
                return true
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
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenCallback != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenCallback = callback
                setWebviewFullscreen(true)
            }

            override fun onHideCustomView() {
                val callback = fullscreenCallback ?: return
                fullscreenCallback = null
                callback.onCustomViewHidden()
                setWebviewFullscreen(false)
            }

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
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        val bridgeTag = "<script>" + gameBridgeHookJs + "</script>"
        val hookedHtml = if (head != null) {
            html.replaceRange(head.range, head.value + bridgeTag)
        } else {
            bridgeTag + html
        }
        val modified = hookedHtml.replace("</body>", tag)
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
        when (requestCode) {
            FILE_CHOOSER_RESULT_CODE -> {
                val result = data?.data?.takeIf { resultCode == RESULT_OK }?.let { arrayOf(it) }
                filePathCallback?.onReceiveValue(result)
                filePathCallback = null
            }
            EXPORT_SAVE_RESULT_CODE -> {
                val bytes = pendingExport
                pendingExport = null
                val uri = data?.data
                if (resultCode == RESULT_OK && bytes != null && uri != null) {
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                                    ?: throw java.io.IOException("Cannot open export destination")
                            }.isSuccess
                        }
                        Toast.makeText(this@GameActivity,
                            if (ok) R.string.export_done else R.string.export_failed,
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        updateDialog?.dismiss()
        updateDialog = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("GardendlessBridge")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun suggestFileName(contentDisposition: String?, mimeType: String): String {
        contentDisposition
            ?.let { Regex("""filename\*?=(?:UTF-8''|utf-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val time = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "gardendless_$time.${mimeToExtension(mimeType)}"
    }

    private fun mimeToExtension(mimeType: String): String = when (val type = mimeType.substringBefore(';').trim()) {
        "application/json" -> "json"
        "text/plain" -> "txt"
        "application/octet-stream" -> "bin"
        else -> type.substringAfter('/').takeIf { it.all(Char::isLetterOrDigit) } ?: "bin"
    }

    private fun mimeFromExtension(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it != fileName } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
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

    private val gameBridgeHookJs = """
(function() {
    if (window.__gdHooked) return;
    window.__gdHooked = true;

    var bridge = window.GardendlessBridge;


    // Ignore the initial game startup sync so the saved fullscreen preference survives.
    var bootGuard = true;
    setTimeout(function() { bootGuard = false; }, 6000);

    var setFullscreen = function(v) {
        if (bootGuard && !v) { bootGuard = false; return; }
        bootGuard = false;
        try { bridge.setFullscreen(!!v); } catch (e) { /* Bridge unavailable. */ }
    };

    var setExportName = function(name) {
        if (!name) return;
        try { bridge.setExportName(String(name)); } catch (e) { /* Bridge unavailable. */ }
    };

    var isDataHref = function(el) {
        return (el && el.getAttribute && (el.getAttribute('href') || '').indexOf('data:') === 0);
    };
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        if (this.download && isDataHref(this)) setExportName(this.download);
        return origClick.apply(this, arguments);
    };
    document.addEventListener('click', function(e) {
        var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
        if (a && isDataHref(a)) setExportName(a.getAttribute('download'));
    }, true);

    var ep = Element.prototype;
    var req = ep.requestFullscreen || ep.webkitRequestFullscreen || ep.webkitRequestFullScreen;
    if (req) {
        ep.requestFullscreen = function() { setFullscreen(true); return req.apply(this, arguments); };
    }
    var dp = Document.prototype;
    var exit = dp.exitFullscreen || dp.webkitExitFullscreen || dp.webkitCancelFullScreen;
    if (exit) {
        dp.exitFullscreen = function() { setFullscreen(false); return exit.apply(this, arguments); };
    }

    var patchTauri = function() {
        var ti = window.__TAURI_INTERNALS__;
        if (!ti || !ti.invoke || ti.__gdHooked) return false;
        ti.__gdHooked = true;
        var orig = ti.invoke;
        ti.invoke = function(cmd, args) {
            if (cmd === 'plugin:window|set_fullscreen') {
                setFullscreen(!!(args && args.value));
                return Promise.resolve(null);
            }
            if (cmd === 'plugin:dialog|save') {

                var raw = args && (args.defaultPath || (args.options && args.options.defaultPath));
                var name = raw ? String(raw).split(/[\\/]/).pop() : '';
                if (name) {
                    setExportName(name);
                    return Promise.resolve(name);
                }
            }
            return orig.apply(this, arguments);
        };
        return true;
    };

    if (!patchTauri()) {
        var tries = 0;
        var timer = setInterval(function() {
            if (patchTauri() || ++tries > 100) clearInterval(timer);
        }, 50);
    }
})();
    """.trimIndent()

    private fun setWebviewFullscreen(enabled: Boolean) {
        aspectContainer.fullscreen = enabled
        prefs.edit().putBoolean(PREF_FULLSCREEN, enabled).apply()
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

    private var updateCheckInProgress = false
    private var updateDownloadInProgress = false
    private var updateDialog: android.app.Dialog? = null
    private val updateApk by lazy { File(cacheDir, "updates/update.apk") }
    private val installPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (packageManager.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        } else {
            showUpdateError(getString(R.string.update_permission_required)) { installDownloadedUpdate() }
        }
    }

    private fun checkForAppUpdate() {
        if (updateCheckInProgress || updateDownloadInProgress) return
        val elapsed = System.currentTimeMillis() - prefs.getLong("last_update_check", 0)
        if (elapsed in 0 until 24 * 60 * 60 * 1000L) return
        updateCheckInProgress = true
        lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    val conn = java.net.URL(
                        "https://api.github.com/repos/MrPurple666/pvzge-android/releases/latest"
                    ).openConnection() as java.net.HttpURLConnection
                    try {
                        conn.setRequestProperty("Accept", "application/vnd.github+json")
                        conn.setRequestProperty("User-Agent", "Gardendless-Android")
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                        conn.inputStream.bufferedReader().use { org.json.JSONObject(it.readText()) }
                    } finally {
                        conn.disconnect()
                    }
                }
                val version = release.getString("tag_name").removePrefix("v")
                val installed = packageManager.getPackageInfo(packageName, 0).versionName
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@launch
                if (!UpdateVersion.isNewer(version, installed)) {
                    prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                    return@launch
                }
                val assets = release.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "app-release.apk" }
                    ?: (0 until assets.length()).map { assets.getJSONObject(it) }
                        .singleOrNull { it.optString("name").endsWith(".apk", true) }
                    ?: return@launch
                val url = apk.getString("browser_download_url")
                require(url.startsWith("https://github.com/MrPurple666/pvzge-android/releases/download/"))
                val size = apk.getLong("size")
                require(size > 0)
                lifecycle.withResumed {
                    showUpdateDialog(version, release.optString("body").take(1000), url, size)
                }
                prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Gardendless", "Update check failed", e)
            } finally {
                updateCheckInProgress = false
            }
        }
    }

    private fun showUpdateDialog(version: String, changelog: String, url: String, size: Long) {
        if (updateDialog?.isShowing == true) return
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available, version,
                android.text.format.Formatter.formatFileSize(this, size)))
            .setMessage(changelog.ifBlank { getString(R.string.update_available_message) })
            .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstallApk(url, size) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstallApk(url: String, expectedSize: Long) {
        if (updateDownloadInProgress) return
        updateDownloadInProgress = true
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_downloading)
            .setView(progress)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val partial = File(cacheDir, "updates/update.apk.part")
            try {
                withContext(Dispatchers.IO) {
                    partial.parentFile?.mkdirs()
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    try {
                        conn.connectTimeout = 30000
                        conn.readTimeout = 30000
                        if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                        var downloaded = 0L
                        var lastPercent = -1
                        conn.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    downloaded += count
                                    if (downloaded > expectedSize) throw java.io.IOException("Unexpected APK size")
                                    output.write(buffer, 0, count)
                                    val percent = (downloaded * 100 / expectedSize).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        withContext(Dispatchers.Main) { progress.progress = percent }
                                    }
                                }
                            }
                        }
                        if (downloaded != expectedSize) throw java.io.IOException("Incomplete APK download")
                        validateUpdateApk(partial)
                        if (updateApk.exists() && !updateApk.delete()) throw java.io.IOException("Cannot replace APK")
                        if (!partial.renameTo(updateApk)) throw java.io.IOException("Cannot save APK")
                    } finally {
                        conn.disconnect()
                    }
                }
                updateDialog?.dismiss()
                lifecycle.withResumed { installDownloadedUpdate() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Gardendless", "Update download failed", e)
                prefs.edit().remove("last_update_check").apply()
                updateDialog?.dismiss()
                lifecycle.withResumed {
                    showUpdateError(getString(R.string.update_download_failed))
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { partial.delete() }
                updateDownloadInProgress = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateUpdateApk(file: File) {
        val archive = packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw java.io.IOException("Invalid APK")
        val installed = packageManager.getPackageInfo(packageName, 0)
        val archiveCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive)
        val installedCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed)
        if (archive.packageName != packageName || archiveCode <= installedCode) {
            throw java.io.IOException("APK is not an upgrade for this application")
        }
    }

    private fun installDownloadedUpdate() {
        try {
            validateUpdateApk(updateApk)
            if (!packageManager.canRequestPackageInstalls()) {
                installPermissionLauncher.launch(Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri()
                ))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", updateApk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("Update APK", uri)
            })
        } catch (e: Exception) {
            android.util.Log.w("Gardendless", "Cannot install update", e)
            showUpdateError(getString(R.string.update_install_failed))
        }
    }

    private fun showUpdateError(message: String, retry: () -> Unit = {
        prefs.edit().remove("last_update_check").apply()
        checkForAppUpdate()
    }) {
        if (isFinishing || isDestroyed) return
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_error)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> retry() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
