package com.ramesh.piradio

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var connectivityManager: ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var hasError = false
    private val retryRunnable = Runnable { retryPlayback() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (hasError) {
                    handler.removeCallbacks(retryRunnable)
                    retryPlayback()
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            hasError = true
            val delayMs = (RETRY_BASE_DELAY_MS shl minOf(retryCount, 4)).coerceAtMost(RETRY_MAX_DELAY_MS)
            retryCount++
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, delayMs)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                hasError = false
                retryCount = 0
                handler.removeCallbacks(retryRunnable)
            }
        }
    }

    // The stream always comes from prefs, so controllers can't race the service with a stale URL.
    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mutableListOf(buildMediaItem()))
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        player.setMediaItem(buildMediaItem())
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(RadioPrefs.getUrl(this))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(RadioPrefs.getStationName(this))
                    .setArtist(getString(R.string.artist_live_stream))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

    private fun retryPlayback() {
        if (!player.playWhenReady) return
        player.prepare()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        player.removeListener(playerListener)
        mediaSession.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    private companion object {
        const val RETRY_BASE_DELAY_MS = 2_000L
        const val RETRY_MAX_DELAY_MS = 30_000L
    }
}
