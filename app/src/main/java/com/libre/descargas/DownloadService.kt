package com.libre.descargas

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Servicio que descarga en segundo plano y muestra el progreso en una notificación. */
class DownloadService : Service() {

    companion object {
        const val Q_BEST = "best"
        const val Q_720 = "720"
        const val Q_480 = "480"
        const val Q_AUDIO = "audio"

        private const val EXTRA_URL = "url"
        private const val EXTRA_Q = "q"
        private const val EXTRA_COOKIES = "cookies"
        private const val CHANNEL = "descargas"
        private const val FG_ID = 1

        fun start(context: Context, url: String, quality: String, cookies: String?) {
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_Q, quality)
                .putExtra(EXTRA_COOKIES, cookies)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicInteger(0)
    private val ids = AtomicInteger(100)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, FG_ID, progressNotification("Preparando descarga…", null, -1), type)

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (running.get() == 0) stopEverything()
            return START_NOT_STICKY
        }
        val quality = intent.getStringExtra(EXTRA_Q) ?: Q_BEST
        val cookies = intent.getStringExtra(EXTRA_COOKIES)

        running.incrementAndGet()
        val id = ids.incrementAndGet()
        scope.launch {
            try {
                val saved = download(id, url, quality, cookies)
                notifyResult(id, "✅ Descarga completa", saved.joinToString("\n"))
            } catch (e: Exception) {
                notifyResult(id, "❌ No se pudo descargar", friendlyError(e))
            } finally {
                if (running.decrementAndGet() == 0) stopEverything()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun download(id: Int, url: String, quality: String, cookies: String?): List<String> {
        Engine.ensure(applicationContext)

        val tmp = File(cacheDir, "dl/$id").apply { deleteRecursively(); mkdirs() }
        val cookieFile = File(cacheDir, "cookies_$id.txt")
        try {
            val req = YoutubeDLRequest(url)
            req.addOption("-o", tmp.absolutePath + "/%(title).80B [%(id)s].%(ext)s")
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")

            if (quality == Q_AUDIO) {
                req.addOption("-f", "ba[ext=m4a]/ba/b")
            } else {
                val h = when (quality) {
                    Q_720 -> "[height<=720]"
                    Q_480 -> "[height<=480]"
                    else -> ""
                }
                req.addOption("-f", "bv*$h[ext=mp4]+ba[ext=m4a]/b$h[ext=mp4]/bv*$h+ba/b$h/b")
                req.addOption("--merge-output-format", "mp4")
            }

            if (!cookies.isNullOrBlank() && writeCookies(cookieFile, url, cookies)) {
                req.addOption("--cookies", cookieFile.absolutePath)
            }

            nm.notify(FG_ID, progressNotification("Descargando…", url, -1))

            var lastShown = -1
            var lastTime = 0L
            YoutubeDL.getInstance().execute(req, "dl$id", callback = { progress, _, _ ->
                val p = progress.toInt()
                val now = SystemClock.elapsedRealtime()
                if (p != lastShown && now - lastTime > 700) {
                    lastShown = p
                    lastTime = now
                    nm.notify(FG_ID, progressNotification("Descargando… $p%", url, p))
                }
            })

            nm.notify(FG_ID, progressNotification("Guardando…", null, -1))
            val files = tmp.listFiles()
                ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                .orEmpty()
            if (files.isEmpty()) throw IllegalStateException("No se generó ningún archivo")
            return files.map { Storage.saveToDownloads(this, it) }
        } finally {
            tmp.deleteRecursively()
            cookieFile.delete()
        }
    }

    /** Pasa la sesión del navegador (si iniciaste sesión) a yt-dlp en formato Netscape. */
    private fun writeCookies(file: File, url: String, cookies: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        val parts = host.split('.')
        val base = if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        var count = 0
        cookies.split(';').forEach { c ->
            val kv = c.trim()
            val i = kv.indexOf('=')
            if (i > 0) {
                sb.append('.').append(base).append("\tTRUE\t/\tTRUE\t2147483647\t")
                    .append(kv.substring(0, i)).append('\t').append(kv.substring(i + 1)).append('\n')
                count++
            }
        }
        if (count == 0) return false
        file.writeText(sb.toString())
        return true
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("confirm you", true) || msg.contains("not a bot", true) ->
                "YouTube pidió verificación. Inicia sesión en YouTube dentro de la app, pulsa ⋮ → Actualizar motor y vuelve a intentar."
            msg.contains("login", true) || msg.contains("rate-limit", true) || msg.contains("cookies", true) ->
                "Este sitio pide iniciar sesión. Entra a tu cuenta dentro de la app y vuelve a intentar."
            msg.contains("Unsupported URL", true) ->
                "Este enlace no es un video compatible. Abre el video o publicación concreta y vuelve a intentar."
            msg.contains("Unable to resolve host", true) || msg.contains("network", true) ->
                "Sin conexión a internet."
            else -> msg.lines().lastOrNull { it.isNotBlank() }?.take(250) ?: "Error desconocido"
        }
    }

    private fun progressNotification(title: String, text: String?, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .build()

    private fun notifyResult(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
