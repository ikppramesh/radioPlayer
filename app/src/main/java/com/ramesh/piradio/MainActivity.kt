package com.ramesh.piradio

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ramesh.piradio.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.get()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateUiState()
        override fun onPlaybackStateChanged(playbackState: Int) = updateUiState()
        override fun onPlayerError(error: PlaybackException) {
            binding.statusText.text = getString(R.string.status_error)
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateNowPlaying(mediaMetadata)
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateAudioRouteText()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateAudioRouteText()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setupVolumeControls()

        binding.playPauseButton.setOnClickListener {
            controller?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    if (it.playbackState == Player.STATE_IDLE) it.prepare()
                    it.play()
                }
            }
        }

        binding.reconnectButton.setOnClickListener {
            controller?.let {
                it.stop()
                it.seekToDefaultPosition()
                it.prepare()
                it.play()
            }
        }

        binding.bluetoothButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        binding.stationName.text = RadioPrefs.getStationName(this)
        binding.stationName.setOnLongClickListener {
            showEditStreamDialog()
            true
        }
        binding.editStreamButton.setOnClickListener {
            showEditStreamDialog()
        }
    }

    private fun showEditStreamDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.hint_station_name)
            setText(RadioPrefs.getStationName(context))
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.hint_stream_url)
            setText(RadioPrefs.getUrl(context))
        }
        container.addView(nameInput)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_edit_stream)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val newUrl = urlInput.text.toString().trim()
                val newName = nameInput.text.toString().trim()
                    .ifEmpty { getString(R.string.station_name) }
                if (!StreamUrl.isValid(newUrl)) {
                    Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_LONG).show()
                } else {
                    if (StreamUrl.isInsecureRemote(newUrl)) {
                        Toast.makeText(this, R.string.warning_insecure_remote, Toast.LENGTH_LONG).show()
                    }
                    RadioPrefs.save(this, newUrl, newName)
                    applyNewStream(newUrl, newName)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyNewStream(url: String, name: String) {
        binding.stationName.text = name
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(getString(R.string.artist_live_stream))
                    .build()
            )
            .build()
        controller?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            play()
        }
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        updateAudioRouteText()
        initializeController()
    }

    override fun onStop() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        releaseController()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binding.volumeSeekBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun initializeController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            val mediaController = try {
                future.get()
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.status_error)
                return@addListener
            }
            mediaController.addListener(playerListener)
            updateUiState()
            updateNowPlaying(mediaController.mediaMetadata)
        }, MoreExecutors.directExecutor())
    }

    private fun releaseController() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    private fun updateUiState() {
        val mediaController = controller ?: return
        binding.playPauseButton.setImageResource(
            if (mediaController.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.statusText.text = when {
            mediaController.playbackState == Player.STATE_BUFFERING -> getString(R.string.status_buffering)
            mediaController.isPlaying -> getString(R.string.status_playing)
            mediaController.playbackState == Player.STATE_IDLE -> getString(R.string.status_paused)
            else -> getString(R.string.status_paused)
        }
    }

    private fun setupVolumeControls() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSeekBar.max = maxVolume
        binding.volumeSeekBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.volumeUpButton.setOnClickListener {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            binding.volumeSeekBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        binding.volumeDownButton.setOnClickListener {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            binding.volumeSeekBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private fun updateNowPlaying(mediaMetadata: MediaMetadata) {
        val incomingTitle = mediaMetadata.title?.toString()?.trim()
        val stationName = RadioPrefs.getStationName(this)
        binding.nowPlayingText.text = if (!incomingTitle.isNullOrEmpty() && incomingTitle != stationName) {
            incomingTitle
        } else {
            getString(R.string.now_playing_default)
        }
    }

    private fun updateAudioRouteText() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetoothConnected = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        binding.audioRouteText.text = getString(
            if (bluetoothConnected) R.string.audio_route_bluetooth else R.string.audio_route_speaker
        )
    }
}
