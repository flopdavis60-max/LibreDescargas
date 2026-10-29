package com.libre.descargas

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bloqueador de anuncios y rastreadores por dominio.
 * Trae una lista básica incluida y puede descargar una lista ampliada (StevenBlack hosts).
 */
object AdBlocker {

    private const val LIST_FILE = "lista_bloqueo.txt"
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    /** Siempre bloqueados (y sus subdominios). */
    private val builtIn = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adservice.google.com", "app-measurement.com", "imasdk.googleapis.com",
        "adnxs.com", "adsrvr.org", "amazon-adsystem.com", "taboola.com", "outbrain.com",
        "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "casalemedia.com", "scorecardresearch.com", "quantserve.com", "moatads.com",
        "adcolony.com", "applovin.com", "unityads.unity3d.com", "popads.net", "popcash.net",
        "propellerads.com", "exoclick.com", "juicyads.com", "mgid.com", "revcontent.com",
        "zedo.com", "hotjar.com", "mixpanel.com", "adform.net", "smartadserver.com",
        "yieldmo.com", "sharethrough.com", "teads.tv", "media.net", "bidswitch.net",
        "33across.com", "onclickads.net", "adsterra.com", "hilltopads.net", "a-ads.com",
        "trafficjunky.net", "clickadu.com", "connect.facebook.net", "analytics.tiktok.com",
        "analytics-sg.tiktok.com", "ads-api.tiktok.com", "adskeeper.com", "admaven.com",
        "pushwoosh.com", "onesignal.com", "inmobi.com", "startappservice.com",
        "adsafeprotected.com", "doubleverify.com", "2mdn.net", "serving-sys.com",
        "advertising.com", "yieldlove.net", "lijit.com", "sovrn.com", "contextweb.com"
    )

    /** Fragmentos de ruta de anuncios en YouTube y Google. */
    private val pathPatterns = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info",
        "/youtubei/v1/log_event", "/generate_204?ad", "&ad_type=", "/ad_status"
    )

    /** Nunca se bloquean con la lista ampliada (para no romper las páginas principales). */
    private val neverBlock = hashSetOf(
        "youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "google.com", "gstatic.com",
        "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "tiktok.com",
        "tiktokcdn.com", "tiktokv.com", "duckduckgo.com", "github.com", "githubusercontent.com"
    )

    @Volatile
    private var extra: Set<String> = emptySet()

    val extraCount: Int get() = extra.size

    fun load(context: Context) {
        val f = File(context.filesDir, LIST_FILE)
        if (f.exists()) {
            extra = runCatching { parse(f.readText()) }.getOrDefault(emptySet())
        }
    }

    /** Descarga la lista ampliada. Devuelve cuántos dominios contiene. Llamar fuera del hilo principal. */
    fun update(context: Context): Int {
        val conn = URL(LIST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val parsed = parse(text)
        if (parsed.size < 1000) throw IllegalStateException("La lista descargada parece incompleta")
        File(context.filesDir, LIST_FILE).writeText(text)
        extra = parsed
        return parsed.size
    }

    private fun parse(text: String): Set<String> {
        val out = HashSet<String>(200_000)
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                val host = parts[1].lowercase()
                if (host != "localhost" && host != "0.0.0.0" && host.contains('.')) out.add(host)
            }
        }
        return out
    }

    fun isBlocked(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val full = uri.toString()
        if (pathPatterns.any { full.contains(it) } &&
            (host.endsWith("youtube.com") || host.endsWith("google.com") || host.endsWith("doubleclick.net"))
        ) return true

        val protected = matches(host, neverBlock)
        var h = host
        while (true) {
            if (h in builtIn) return true
            if (!protected && h in extra) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (!h.contains('.')) return false
        }
    }

    private fun matches(host: String, set: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }
}
