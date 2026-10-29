package com.libre.descargas

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME = "file:///android_asset/home.html"
        private const val TV = "file:///android_asset/tv.html"
        private const val ASSETS = "file:///android_asset/"
        private const val SEARCH = "https://duckduckgo.com/?q="
        private const val PREFS = "ajustes"
        private const val KEY_LAST_UPDATE = "ultima_actualizacion_motor"
    }

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var fullscreen: FrameLayout

    @Volatile private var currentUrl: String? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        urlBar = findViewById(R.id.url_bar)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab_download)
        fullscreen = findViewById(R.id.fullscreen)

        lifecycleScope.launch(Dispatchers.IO) { AdBlocker.load(applicationContext) }
        setupWebView()

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<ImageButton>(R.id.btn_home).setOnClickListener { web.loadUrl(HOME) }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        fab.setOnClickListener { askDownload(web.url) }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                go(urlBar.text.toString())
                true
            } else false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        requestNeededPermissions()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else if (!handleIntent(intent)) {
            web.loadUrl(HOME)
        }

        autoUpdateEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    // ---------- Navegador ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }
        web.addJavascriptInterface(TvBridge(), "Android")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return if (AdBlocker.isBlocked(request.url)) emptyResponse else null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: return true
                if (scheme == "http" || scheme == "https") {
                    // Bloquea redirecciones a páginas de publicidad
                    return AdBlocker.isBlocked(request.url)
                }
                if (scheme == "file") {
                    // Solo páginas internas de la app (inicio y TV en vivo)
                    return !request.url.toString().startsWith(ASSETS)
                }
                if (scheme == "intent") {
                    // Abre la versión web en vez de saltar a otra app
                    val fallback = Regex("S\\.browser_fallback_url=([^;]+)")
                        .find(request.url.toString())?.groupValues?.get(1)
                    if (fallback != null) view.loadUrl(Uri.decode(fallback))
                }
                return true // ignora market://, snssdk://, etc.
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                showUrl(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                showUrl(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreen.visibility = View.VISIBLE
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        // Descarga de archivos normales (PDF, ZIP, etc.)
        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${Storage.FOLDER}/$name")
                }
                getSystemService(DownloadManager::class.java).enqueue(req)
                toast("Descargando $name")
            } catch (e: Exception) {
                toast("No se pudo descargar este archivo")
            }
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showUrl(url: String?) {
        currentUrl = url
        if (urlBar.hasFocus()) return
        val isHome = url == null || url.startsWith("file:")
        urlBar.setText(if (isHome) "" else url)
        if (isHome) fab.hide() else fab.show()
    }

    private fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> SEARCH + URLEncoder.encode(t, "UTF-8")
        }
        web.loadUrl(target)
        urlBar.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
        web.requestFocus()
    }

    /** Enlaces que llegan al pulsar "Compartir" en otra app o al abrir un link. */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                val url = Regex("https?://\\S+").find(text)?.value ?: return false
                web.loadUrl(url)
                askDownload(url)
                return true
            }
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return false
                web.loadUrl(url)
                return true
            }
        }
        return false
    }

    // ---------- Descargas ----------

    private fun askDownload(url: String?) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("Primero abre el video que quieres descargar")
            return
        }
        val options = arrayOf(
            "🎬  Video · mejor calidad",
            "📺  Video · hasta 720p",
            "📱  Video · hasta 480p (ahorra datos)",
            "🎵  Solo audio (M4A)"
        )
        val qualities = arrayOf(DownloadService.Q_BEST, DownloadService.Q_720, DownloadService.Q_480, DownloadService.Q_AUDIO)
        MaterialAlertDialogBuilder(this)
            .setTitle("Descargar")
            .setItems(options) { _, which ->
                val cookies = CookieManager.getInstance().getCookie(url)
                DownloadService.start(this, url, qualities[which], cookies)
                toast("Descarga iniciada. Verás el progreso en las notificaciones.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Mantiene yt-dlp al día (una vez por día) porque los sitios cambian seguido. */
    private fun autoUpdateEngine() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last < 24L * 60 * 60 * 1000) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                Engine.update(applicationContext)
                prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            }
        }
    }

    // ---------- Menú ----------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 6, 0, "📺 TV en vivo")
        pm.menu.add(0, 1, 0, "Recargar")
        pm.menu.add(0, 2, 1, "Mis descargas")
        pm.menu.add(0, 3, 2, "Actualizar motor de descargas")
        pm.menu.add(0, 4, 3, "Actualizar lista anti-anuncios")
        pm.menu.add(0, 5, 4, "Borrar historial y sesiones")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                6 -> web.loadUrl(TV)
                1 -> web.reload()
                2 -> try {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (e: Exception) {
                    toast("Tus archivos están en Descargas/${Storage.FOLDER}")
                }
                3 -> runInBackground("Actualizando motor…") { "Motor ${Engine.update(applicationContext)}" }
                4 -> runInBackground("Descargando lista…") { "Lista activa: ${AdBlocker.update(applicationContext)} dominios bloqueados" }
                5 -> {
                    web.clearCache(true)
                    web.clearHistory()
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    toast("Datos de navegación borrados")
                    web.loadUrl(HOME)
                }
            }
            true
        }
        pm.show()
    }

    private fun runInBackground(start: String, block: () -> String) {
        toast(start)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            toast(result.getOrElse { "Error: ${it.message ?: "sin conexión"}" })
        }
    }

    // ---------- TV en vivo ----------

    /** Puente para tv.html. Solo responde a páginas internas de la app, nunca a sitios web. */
    inner class TvBridge {
        private fun allowed() = currentUrl?.startsWith(ASSETS) == true

        @JavascriptInterface
        fun fetch(url: String): String {
            if (!allowed()) return "ERROR:no permitido"
            if (!(url.startsWith("https://") || url.startsWith("http://"))) return "ERROR:dirección no válida"
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "LibreDescargas")
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "ERROR:" + (e.message ?: "sin conexión")
            }
        }

        @JavascriptInterface
        fun play(url: String, name: String, ua: String, referrer: String) {
            if (!allowed()) return
            runOnUiThread { PlayerActivity.start(this@MainActivity, url, name, ua, referrer) }
        }
    }

    // ---------- Utilidades ----------

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
