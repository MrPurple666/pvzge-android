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
import org.json.JSONArray
import org.json.JSONObject
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

        // Pre-warm WebView engine during extraction (#4)
        webView = MouseGameWebView(this)

        val sp = getSharedPreferences("app_data", MODE_PRIVATE)
        val savedVersion = sp.getInt("extracted_version", 0)
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

        if (savedVersion != currentVersion) {
            checkAndExtractAssets(currentVersion, sp)
        } else {
            setupWebview()
        }
    }

    private fun checkAndExtractAssets(currentVersion: Int, sp: android.content.SharedPreferences) {
        // Get total size for progress tracking (#1)
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

        // Build extraction dialog with determinate progress (#1)
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
            .setView(progressLayout)
            .setCancelable(false)
            .create()
        extractionDialog?.show()

        // Delete old extraction first (#3)
        lifecycleScope.launch(Dispatchers.IO) {  // #5: lifecycleScope not GlobalScope
            try {
                val oldExtraction = File(filesDir, "pvzge_web")
                if (oldExtraction.exists()) {
                    oldExtraction.deleteRecursively()
                }

                val destRoot = File(filesDir, "pvzge_web")
                var bytesExtracted = 0L

                ZipInputStream(zipPath.inputStream()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val file = File(destRoot, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { zis.copyTo(it) }
                            bytesExtracted += entry.size
                        }

                        // Update progress (#1)
                        if (totalSize > 0) {
                            val pct = ((bytesExtracted * 100) / totalSize).toInt()
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {  // #6: destroy guard
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = pct
                                    progressText.text = getString(R.string.extraction_progress, pct)
                                }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }

                sp.edit().putInt("extracted_version", currentVersion).apply()

                // Clean up temp zip
                zipPath.delete()

                // Configure remote resource loading from CDN (#23)
                patchSettingsForRemoteResources(destRoot)

                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    withContext(Dispatchers.Main) {
                        extractionDialog?.dismiss()
                        extractionDialog = null
                        setupWebview()
                        showExtractionNotification() // #17
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                zipPath.delete()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    withContext(Dispatchers.Main) {
                        extractionDialog?.dismiss()
                        extractionDialog = null
                        showExtractionError(currentVersion, sp)  // #2: retry UI
                    }
                }
            }
        }
    }

    private fun showExtractionError(currentVersion: Int, sp: android.content.SharedPreferences) {
        // #2: Error UI with retry
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
     * Patches the game's settings.json to load the 1.2 GB resources bundle
     * from the CDN instead of expecting it locally. Cocos natively handles
     * remote bundle loading — no custom download code needed.
     */
    private fun patchSettingsForRemoteResources(destRoot: File) {
        try {
            val settingsFile = File(destRoot, "src/settings.json")
            if (!settingsFile.exists()) return

            val json = JSONObject(settingsFile.readText())
            val assets = json.getJSONObject("assets")
            assets.put("server", "https://play.pvzge.com")
            val remoteBundles = JSONArray()
            remoteBundles.put("resources")
            assets.put("remoteBundles", remoteBundles)

            // Remove resources from preloadBundles to avoid downloading 1.2 GB
            // before showing anything. Resources load on demand from CDN instead.
            val preloadBundles = assets.getJSONArray("preloadBundles")
            val newPreload = JSONArray()
            for (i in 0 until preloadBundles.length()) {
                val bundle = preloadBundles.getJSONObject(i)
                if (bundle.getString("bundle") != "resources") {
                    newPreload.put(bundle)
                }
            }
            assets.put("preloadBundles", newPreload)

            settingsFile.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun setupWebview() {
        // WebView was pre-warmed in onCreate (#4)
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
            // GPU improvements (#20, #22)
            @Suppress("DEPRECATION")
            setAlgorithmicDarkeningAllowed(false)
            safeBrowsingEnabled = false
        }

        // #13: Remote debugging in debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // #19: GPU process priority — requires androidx.webkit 1.16+
        // TODO: Enable when webkit dependency is updated
        // if (Build.VERSION.SDK_INT >= 34) {
        //     webView.setRenderProcessPriority(WebView.RENDERER_PRIORITY_IMPORTANT)
        // }

        // Save file export (#15: timestamped filename)
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.startsWith("data:")) {
                try {
                    val data: String
                    // #14: Proper base64 decoding
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

            // #12: onPageFinished with polling instead of fixed 8s delay
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                showLoadingIndicator()
                injectTouchBlocker(0)
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
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
    }

    // #12: Poll for GameCanvas existence instead of fixed delay
    private fun injectTouchBlocker(attempt: Int) {
        if (attempt >= 10) return
        webView.evaluateJavascript(
            "(function(){ return document.getElementById('GameCanvas') !== null ? 'true' : 'false'; })()"
        ) { result ->
            if (result == "true") {
                val js = """
(function() {
    var target = document.getElementById("GameCanvas");
    if (!target) return;
    target.addEventListener("touchstart", function(e) {
        e.preventDefault();
        e.stopImmediatePropagation();
    }, { capture: true, passive: false });
    target.addEventListener("touchmove", function(e) {
        e.preventDefault();
        e.stopImmediatePropagation();
    }, { capture: true, passive: false });
    target.addEventListener("touchend", function(e) {
        e.preventDefault();
        e.stopImmediatePropagation();
    }, { capture: true, passive: false });
})();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
                val sp = getSharedPreferences("app_data", MODE_PRIVATE)
                if (sp.getInt("extracted_version", 0) == 1) {
                    showGestureHints()
                }
            } else {
                webView.postDelayed({ injectTouchBlocker(attempt + 1) }, 500)
            }
        }
    }

    private fun showLoadingIndicator() {
        val js = """
(function() {
    if (document.getElementById('gardendless-loading')) return;
    var div = document.createElement('div');
    div.id = 'gardendless-loading';
    div.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;' +
        'background:#040909;z-index:99998;display:flex;flex-direction:column;' +
        'align-items:center;justify-content:center;color:#aaa;font-family:sans-serif;' +
        'font-size:18px;pointer-events:none;transition:opacity 0.5s;';
    div.innerHTML = '<div style="font-size:48px;margin-bottom:20px;">&#127793;</div>' +
        '<div>Loading game\u2026</div>';
    document.body.appendChild(div);
    var checks = 0;
    var interval = setInterval(function() {
        checks++;
        var canvas = document.getElementById('GameCanvas');
        if (canvas && canvas.width > 0 && canvas.height > 0) {
            var ctx = canvas.getContext('2d', { willReadFrequently: false });
            if (ctx) {
                try {
                    var pixel = ctx.getImageData(canvas.width/2, canvas.height/2, 1, 1).data;
                    if (pixel[0] !== 4 || pixel[1] !== 9 || pixel[2] !== 10) {
                        div.style.opacity = '0';
                        setTimeout(function() { div.remove(); }, 500);
                        clearInterval(interval);
                    }
                } catch(e) {}
            }
        }
        if (checks > 120) { clearInterval(interval); div.remove(); }
    }, 500);
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // #16: Gesture hint overlay for first-time users
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
        // #18: FLAG_SECURE disabled by default, configurable via SharedPreferences
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

    // #17: Local notification when extraction completes
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
}
