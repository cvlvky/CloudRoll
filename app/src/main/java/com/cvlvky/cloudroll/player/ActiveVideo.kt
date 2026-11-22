package com.cvlvky.cloudroll.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.mutableStateOf
import androidx.media3.exoplayer.ExoPlayer
import com.cvlvky.cloudroll.data.Prefs

/** The video playing in the viewer, so the app can shrink it into picture-in-picture. */
object ActiveVideo {
    var player: ExoPlayer? = null
        private set
    private var width = 16
    private var height = 9

    /** True while the app shows only the video in a small window. */
    val inPip = mutableStateOf(false)

    fun set(player: ExoPlayer?, width: Int, height: Int) {
        this.player = player
        if (width > 0 && height > 0) {
            this.width = width
            this.height = height
        }
    }

    fun clear(player: ExoPlayer) {
        if (this.player === player) this.player = null
    }

    private fun aspect(): Rational {
        // Android allows 1:2.39 to 2.39:1 for the small window.
        val r = width.toFloat() / height
        return when {
            r > 2.39f -> Rational(239, 100)
            r < 1 / 2.39f -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    fun params(autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(aspect())
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter)
        return b.build()
    }

    /** Keeps Android's picture-in-picture settings in step with the video (Android 12 and newer enter it by themselves). */
    fun sync(activity: Activity?, playing: Boolean) {
        if (activity == null || Build.VERSION.SDK_INT < 31) return
        try {
            activity.setPictureInPictureParams(params(Prefs.pip.value && playing && player != null))
        } catch (e: Exception) {
            // Not supported on this device.
        }
    }

    /** Android 10 and 11: enter by hand when the user leaves the app during a video. */
    fun enterIfPlaying(activity: Activity) {
        val p = player ?: return
        if (!Prefs.pip.value || !p.isPlaying || Build.VERSION.SDK_INT >= 31) return
        try {
            activity.enterPictureInPictureMode(params(false))
        } catch (e: Exception) {
            // Not supported on this device.
        }
    }
}
