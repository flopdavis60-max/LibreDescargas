package com.libre.descargas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Reproductor de canales en vivo (HLS / MPEG-TS / MP4) con ExoPlayer. */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_UA = "ua"
        private const val EXTRA_REF = "ref"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        fun start(context: Context, url: String, name: String, ua: String?, referrer: String?) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_UA, ua)
                    .putExtra(EXTRA_REF, referrer)
            )
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view = findViewById(R.id.player_view)
        title = findViewById(R.id.player_title)
        title.text = intent.getStringExtra(EXTRA_NAME) ?: ""
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            title.visibility = v
        })
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    private fun initPlayer() {
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val ua = intent.getStringExtra(EXTRA_UA)?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
        val ref = intent.getStringExtra(EXTRA_REF)?.takeIf { it.isNotBlank() }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        if (ref != null) http.setDefaultRequestProperties(mapOf("Referer" to ref))

        val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
            if (url.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Este canal no está disponible ahora (puede estar caído o bloqueado en tu país).",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        })
        view.player = p
        p.setMediaItem(item)
        p.playWhenReady = true
        p.prepare()
        player = p
    }
}
