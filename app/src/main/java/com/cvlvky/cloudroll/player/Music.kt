package com.cvlvky.cloudroll.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.Kind
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the music player is doing, for the mini player and the full player screen. */
data class NowPlaying(
    val active: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val key: String = "",
    val index: Int = 0,
    val count: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val sleepAt: Long = 0L,
    val sleepAtEnd: Boolean = false,
)

/** The app's side of the music player: a controller connected to [PlaybackService]. */
object Music {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var sleepJob: Job? = null

    /** The tracks of the current queue, by media id, for artwork and favourites. */
    private val queueMedia = HashMap<String, TgMedia>()

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    fun current(): TgMedia? = queueMedia[_state.value.key]

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    fun connect(ctx: Context) {
        if (future != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (e: Exception) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            refresh(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun withController(ctx: Context, action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            action(c)
        } else {
            pending.add(action)
            connect(ctx)
        }
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        val md = p.mediaMetadata
        _state.value = _state.value.copy(
            active = p.mediaItemCount > 0,
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            key = item?.mediaId?.substringBeforeLast(':').orEmpty(),
            index = p.currentMediaItemIndex,
            count = p.mediaItemCount,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            speed = p.playbackParameters.speed,
        )
    }

    private fun toItem(m: TgMedia): MediaItem {
        val artist = when {
            m.performer.isNotBlank() -> m.performer
            else -> Tg.chatTitle(m.chatId)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(m.displayName)
            .setArtist(artist)
            .setAlbumTitle(Tg.chatTitle(m.chatId))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply { putLong("chatId", m.chatId) })
            .build()
        return MediaItem.Builder()
            .setMediaId("${m.chatId}:${m.messageId}:${m.file.id}")
            .setMediaMetadata(meta)
            .build()
    }

    /** Plays [list] from [index], replacing the queue. */
    fun play(ctx: Context, list: List<TgMedia>, index: Int, shuffle: Boolean = false) {
        val tracks = list.filter { it.isAudio }
        if (tracks.isEmpty()) return
        val start = tracks.indexOfFirst { it.key == list.getOrNull(index)?.key }.coerceAtLeast(0)
        queueMedia.clear()
        tracks.forEach { queueMedia[it.key] = it }
        withController(ctx) { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(tracks.map(::toItem), if (shuffle) 0 else start, 0L)
            c.prepare()
            c.play()
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 4_000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun position(): Long = controller?.currentPosition ?: 0L

    fun duration(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    fun stop() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        cancelSleep()
    }

    /** Pauses after [minutes] minutes, or at the end of this track when [minutes] is 0. */
    fun sleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes == 0) {
            _state.value = _state.value.copy(sleepAt = 0L, sleepAtEnd = true)
            val startIndex = controller?.currentMediaItemIndex ?: return
            sleepJob = scope.launch {
                while (true) {
                    delay(500)
                    val c = controller ?: break
                    val left = c.duration - c.currentPosition
                    if (c.currentMediaItemIndex != startIndex || (c.duration > 0 && left < 600)) {
                        c.pause()
                        break
                    }
                }
                _state.value = _state.value.copy(sleepAtEnd = false)
            }
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepAt = at, sleepAtEnd = false)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _state.value = _state.value.copy(sleepAt = 0L)
        }
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        _state.value = _state.value.copy(sleepAt = 0L, sleepAtEnd = false)
    }

    fun isFavourite(key: String) = Prefs.isFavourite(key)

    fun kindOfCurrent(): Kind? = current()?.kind
}
