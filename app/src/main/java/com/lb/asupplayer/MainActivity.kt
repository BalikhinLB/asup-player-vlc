package com.lb.asupplayer

import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.IOException
import java.util.Locale
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class MainActivity : ComponentActivity() {
    private lateinit var libVlc: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoSurfaceView: SurfaceView
    private lateinit var subtitlesSurfaceView: SurfaceView
    private lateinit var controlsContainer: FrameLayout
    private lateinit var openVideoButton: Button
    private lateinit var playerControls: LinearLayout
    private lateinit var playbackSeek: SeekBar
    private lateinit var playbackTime: TextView
    private lateinit var playPauseButton: Button
    private lateinit var audioTrackButton: Button
    private lateinit var subtitleTrackButton: Button
    private var currentVideoDescriptor: AssetFileDescriptor? = null
    private val controlsHandler = Handler(Looper.getMainLooper())

    private var currentVideoUri: Uri? = null
    private var restorePositionMs: Long = 0L
    private var isSeeking = false
    private var knownLengthMs = 0L

    private val progressUpdater = object : Runnable {
        override fun run() {
            updatePlaybackProgress()
            controlsHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadAccess(uri)
                playVideo(uri, startPositionMs = 0L)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentVideoUri = savedInstanceState?.getVideoUri()
        restorePositionMs = savedInstanceState?.getLong(KEY_POSITION_MS) ?: 0L

        libVlc = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        mediaPlayer = MediaPlayer(libVlc)

        setContentView(R.layout.activity_main)
        videoSurfaceView = findViewById(R.id.video_surface)
        subtitlesSurfaceView = findViewById(R.id.subtitles_surface)
        controlsContainer = findViewById(R.id.controls_container)
        openVideoButton = findViewById(R.id.open_video_button)
        playerControls = findViewById(R.id.player_controls)
        playbackSeek = findViewById(R.id.playback_seek)
        playbackTime = findViewById(R.id.playback_time)
        playPauseButton = findViewById(R.id.play_pause_button)
        audioTrackButton = findViewById(R.id.audio_track_button)
        subtitleTrackButton = findViewById(R.id.subtitle_track_button)

        subtitlesSurfaceView.setZOrderMediaOverlay(true)
        subtitlesSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        attachPlayerViews()

        openVideoButton.setOnClickListener {
            openDocument.launch(arrayOf("video/*"))
        }

        playPauseButton.setOnClickListener {
            togglePlayback()
        }
        audioTrackButton.setOnClickListener {
            showTrackMenu(audioTrackButton, mediaPlayer.audioTracks.toTrackMenuItems(), mediaPlayer.audioTrack) { trackId ->
                mediaPlayer.audioTrack = trackId
            }
        }
        subtitleTrackButton.setOnClickListener {
            showTrackMenu(subtitleTrackButton, subtitleTracksWithOff(), mediaPlayer.spuTrack) { trackId ->
                mediaPlayer.spuTrack = trackId
            }
        }
        playbackSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        updatePlaybackTime(progressToTime(progress), knownLengthMs)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    mediaPlayer.time = progressToTime(seekBar.progress)
                    isSeeking = false
                    updatePlaybackProgress()
                }
            },
        )

        mediaPlayer.setEventListener { event ->
            runOnUiThread {
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        playerControls.visibility = View.VISIBLE
                        setKeepScreenOn(true)
                        updatePlaybackState()
                    }

                    MediaPlayer.Event.Paused -> {
                        setKeepScreenOn(false)
                        stopProgressUpdates()
                        updatePlaybackState()
                    }

                    MediaPlayer.Event.Stopped,
                    MediaPlayer.Event.EndReached,
                    MediaPlayer.Event.EncounteredError,
                    -> {
                        setKeepScreenOn(false)
                        stopProgressUpdates()
                        updatePlaybackState()
                    }

                    MediaPlayer.Event.LengthChanged,
                    MediaPlayer.Event.TimeChanged,
                    MediaPlayer.Event.ESAdded,
                    MediaPlayer.Event.ESDeleted,
                    MediaPlayer.Event.ESSelected,
                    -> {
                        updatePlaybackProgress()
                    }
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(controlsContainer) { view, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            view.setPadding(0, 0, 0, if (isLandscape) 0 else navigationBars.bottom)
            insets
        }

        applySystemBarsMode(resources.configuration)

        currentVideoUri?.let { uri ->
            playVideo(uri, restorePositionMs)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsMode(newConfig)
        ViewCompat.requestApplyInsets(controlsContainer)
        updateVideoOutputSize()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentVideoUri?.let { outState.putParcelable(KEY_VIDEO_URI, it) }
        outState.putLong(KEY_POSITION_MS, mediaPlayer.time.coerceAtLeast(0L))
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            mediaPlayer.pause()
            setKeepScreenOn(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controlsHandler.removeCallbacks(progressUpdater)
        mediaPlayer.vlcVout.detachViews()
        mediaPlayer.release()
        libVlc.release()
        closeCurrentVideoDescriptor()
    }

    private fun playVideo(uri: Uri, startPositionMs: Long) {
        try {
            val videoDescriptor = contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Content resolver returned null descriptor for $uri")

            mediaPlayer.stop()
            closeCurrentVideoDescriptor()

            currentVideoUri = uri
            restorePositionMs = startPositionMs
            currentVideoDescriptor = videoDescriptor
            openVideoButton.visibility = View.GONE
            playerControls.visibility = View.VISIBLE

            val media = Media(libVlc, videoDescriptor)
            media.setHWDecoderEnabled(true, false)
            mediaPlayer.media = media
            media.release()
            attachPlayerViews()
            updateVideoOutputSize()
            mediaPlayer.play()
            updatePlaybackState()
            startProgressUpdates()

            if (startPositionMs > 0L) {
                videoSurfaceView.post {
                    mediaPlayer.time = startPositionMs
                }
            }
        } catch (_: IOException) {
            currentVideoUri = null
            openVideoButton.visibility = View.VISIBLE
            playerControls.visibility = View.GONE
            setKeepScreenOn(false)
            Toast.makeText(this, R.string.video_open_error, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            currentVideoUri = null
            openVideoButton.visibility = View.VISIBLE
            playerControls.visibility = View.GONE
            setKeepScreenOn(false)
            Toast.makeText(this, R.string.video_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistReadAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                IntentFlags.READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers return one-time access only.
        }
    }

    private fun closeCurrentVideoDescriptor() {
        try {
            currentVideoDescriptor?.close()
        } catch (_: IOException) {
            // The descriptor is already unusable; there is nothing actionable for the UI.
        } finally {
            currentVideoDescriptor = null
        }
    }

    private fun attachPlayerViews() {
        if (mediaPlayer.vlcVout.areViewsAttached()) {
            return
        }

        mediaPlayer.vlcVout.setVideoView(videoSurfaceView)
        mediaPlayer.vlcVout.setSubtitlesView(subtitlesSurfaceView)
        mediaPlayer.vlcVout.attachViews()
    }

    private fun updateVideoOutputSize() {
        videoSurfaceView.post {
            val width = videoSurfaceView.width
            val height = videoSurfaceView.height
            if (width > 0 && height > 0 && mediaPlayer.vlcVout.areViewsAttached()) {
                mediaPlayer.vlcVout.setWindowSize(width, height)
            }
        }
    }

    private fun togglePlayback() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            setKeepScreenOn(false)
        } else {
            attachPlayerViews()
            mediaPlayer.play()
            setKeepScreenOn(true)
            startProgressUpdates()
        }
        updatePlaybackState()
    }

    private fun showTrackMenu(
        anchor: View,
        tracks: List<TrackMenuItem>,
        selectedTrackId: Int,
        onTrackSelected: (Int) -> Unit,
    ) {
        val popup = PopupMenu(this, anchor)
        if (tracks.isEmpty()) {
            popup.menu.add(getString(R.string.tracks_empty)).isEnabled = false
        } else {
            tracks.forEach { track ->
                val item = popup.menu.add(0, track.id, 0, track.name.ifBlank { track.id.toString() })
                item.isCheckable = true
                item.isChecked = track.id == selectedTrackId
            }
        }
        popup.setOnMenuItemClickListener { item ->
            onTrackSelected(item.itemId)
            updatePlaybackProgress()
            true
        }
        popup.show()
    }

    private fun subtitleTracksWithOff(): List<TrackMenuItem> =
        listOf(TrackMenuItem(DISABLED_TRACK_ID, getString(R.string.subtitles_off))) +
            mediaPlayer.spuTracks.toTrackMenuItems()

    private fun Array<MediaPlayer.TrackDescription>.toTrackMenuItems(): List<TrackMenuItem> =
        map { track -> TrackMenuItem(track.id, track.name) }

    private fun startProgressUpdates() {
        controlsHandler.removeCallbacks(progressUpdater)
        controlsHandler.post(progressUpdater)
    }

    private fun stopProgressUpdates() {
        controlsHandler.removeCallbacks(progressUpdater)
        updatePlaybackProgress()
    }

    private fun updatePlaybackState() {
        playPauseButton.setText(if (mediaPlayer.isPlaying) R.string.pause else R.string.play)
    }

    private fun updatePlaybackProgress() {
        knownLengthMs = mediaPlayer.length.coerceAtLeast(0L)
        val currentTimeMs = mediaPlayer.time.coerceAtLeast(0L)
        if (!isSeeking) {
            playbackSeek.progress = timeToProgress(currentTimeMs, knownLengthMs)
        }
        updatePlaybackTime(currentTimeMs, knownLengthMs)
        updatePlaybackState()
    }

    private fun updatePlaybackTime(currentTimeMs: Long, lengthMs: Long) {
        playbackTime.text = "${formatTime(currentTimeMs)} / ${formatTime(lengthMs)}"
    }

    private fun timeToProgress(timeMs: Long, lengthMs: Long): Int =
        if (lengthMs > 0L) {
            ((timeMs.coerceIn(0L, lengthMs) * playbackSeek.max) / lengthMs).toInt()
        } else {
            0
        }

    private fun progressToTime(progress: Int): Long =
        if (knownLengthMs > 0L) {
            (progress.coerceIn(0, playbackSeek.max).toLong() * knownLengthMs) / playbackSeek.max
        } else {
            0L
        }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / MILLIS_IN_SECOND).coerceAtLeast(0L)
        val seconds = totalSeconds % SECONDS_IN_MINUTE
        val minutes = (totalSeconds / SECONDS_IN_MINUTE) % SECONDS_IN_MINUTE
        val hours = totalSeconds / SECONDS_IN_HOUR
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applySystemBarsMode(configuration: Configuration) {
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        WindowCompat.setDecorFitsSystemWindows(window, !isLandscape)

        if (isLandscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.decorView.windowInsetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    private object IntentFlags {
        const val READ_URI_PERMISSION = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
    }

    private data class TrackMenuItem(
        val id: Int,
        val name: String,
    )

    private fun Bundle.getVideoUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(KEY_VIDEO_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(KEY_VIDEO_URI)
        }

    private companion object {
        const val DISABLED_TRACK_ID = -1
        const val KEY_VIDEO_URI = "video_uri"
        const val KEY_POSITION_MS = "position_ms"
        const val MILLIS_IN_SECOND = 1000L
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        const val SECONDS_IN_HOUR = 3600L
        const val SECONDS_IN_MINUTE = 60L
    }
}
