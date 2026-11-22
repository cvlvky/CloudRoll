package com.cvlvky.cloudroll.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cvlvky.cloudroll.MainActivity
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TdDataSource
import com.cvlvky.cloudroll.tg.tdFileUri
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Plays Telegram music and voice messages in the background, with lock-screen controls. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Tg.init(applicationContext)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdDataSource.Factory()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map { withUri(it) }.toMutableList())
            })
            .build()
    }

    /** The media id is "chatId:messageId:fileId"; the file id becomes the address the player reads. */
    private fun withUri(item: MediaItem): MediaItem {
        val fileId = item.mediaId.substringAfterLast(':').toIntOrNull() ?: return item
        val mime = item.mediaMetadata.extras?.getString("mime")?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
        return item.buildUpon().setUri(tdFileUri(fileId, mime)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
