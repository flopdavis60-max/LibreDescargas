package com.libre.descargas

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** Motor de descargas (yt-dlp + ffmpeg). */
object Engine {

    @Volatile
    private var ready = false

    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }

    /** Actualiza yt-dlp a la última versión estable. Llamar fuera del hilo principal. */
    fun update(context: Context): String {
        ensure(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
        return when (status?.toString()) {
            "DONE" -> "actualizado"
            "ALREADY_UP_TO_DATE" -> "ya estaba al día"
            null -> "sin cambios"
            else -> status.toString()
        }
    }
}
