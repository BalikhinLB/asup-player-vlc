package com.lb.asupplayer

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import org.videolan.libvlc.interfaces.IMedia
import androidx.core.graphics.drawable.toDrawable

class MainActivity : ComponentActivity() {
    private lateinit var libVlc: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoSurfaceView: SurfaceView
    private lateinit var subtitlesSurfaceView: SurfaceView
    private lateinit var controlsContainer: FrameLayout
    private lateinit var openVideoButton: Button
    private lateinit var playerControls: FrameLayout
    private lateinit var playbackSeek: SeekBar
    private lateinit var playbackTime: TextView
    private lateinit var playPauseButton: Button
    private lateinit var settingsButton: ImageButton
    private var currentVideoDescriptor: AssetFileDescriptor? = null
    private var settingsPopup: PopupWindow? = null
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        hidePlayerControls()
    }

    private var currentVideoUri: Uri? = null
    private var externalSubtitleUri: Uri? = null
    private var restorePositionMs: Long = 0L
    private var isSeeking = false
    private var knownLengthMs = 0L
    private var subtitleSizePercent = DEFAULT_SUBTITLE_SIZE_PERCENT
    private var subtitlePosition = SubtitlePosition.BOTTOM

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
                externalSubtitleUri = null
                playVideo(uri, startPositionMs = 0L)
            }
        }

    private val openSubtitleDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadAccess(uri)
                externalSubtitleUri = uri
                addExternalSubtitle(uri)
                showPlayerControls()
                Toast.makeText(
                    this,
                    getString(R.string.subtitle_loaded, uri.displayName()),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentVideoUri = savedInstanceState?.getParcelableCompat(KEY_VIDEO_URI)
        externalSubtitleUri = savedInstanceState?.getParcelableCompat(KEY_SUBTITLE_URI)
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
        settingsButton = findViewById(R.id.settings_button)

        subtitlesSurfaceView.setZOrderMediaOverlay(true)
        subtitlesSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        applySubtitleSurfaceLayout()
        attachPlayerViews()

        openVideoButton.setOnClickListener {
            openDocument.launch(arrayOf("video/*"))
        }
        controlsContainer.setOnClickListener {
            showPlayerControls()
        }
        playerControls.setOnClickListener {
            hidePlayerControls()
        }

        playPauseButton.setOnClickListener {
            togglePlayback()
        }
        settingsButton.setOnClickListener {
            showPlayerControls()
            showSettingsPopup()
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
                    cancelControlsAutoHide()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    mediaPlayer.time = progressToTime(seekBar.progress)
                    isSeeking = false
                    updatePlaybackProgress()
                    scheduleControlsAutoHide()
                }
            },
        )

        mediaPlayer.setEventListener { event ->
            runOnUiThread {
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        showPlayerControls()
                        setKeepScreenOn(true)
                        updatePlaybackState()
                    }

                    MediaPlayer.Event.Paused -> {
                        setKeepScreenOn(false)
                        hidePlayerControls()
                        stopProgressUpdates()
                        updatePlaybackState()
                    }

                    MediaPlayer.Event.Stopped,
                    MediaPlayer.Event.EndReached,
                    MediaPlayer.Event.EncounteredError,
                    -> {
                        setKeepScreenOn(false)
                        hidePlayerControls()
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
        applySubtitleSurfaceLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentVideoUri?.let { outState.putParcelable(KEY_VIDEO_URI, it) }
        externalSubtitleUri?.let { outState.putParcelable(KEY_SUBTITLE_URI, it) }
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
        settingsPopup?.dismiss()
        controlsHandler.removeCallbacks(progressUpdater)
        controlsHandler.removeCallbacks(hideControlsRunnable)
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

            val media = Media(libVlc, videoDescriptor)
            applyDecoderOptions(media)
            applySubtitleMediaOptions(media)
            mediaPlayer.media = media
            media.release()
            attachPlayerViews()
            applySubtitleSurfaceLayout()
            mediaPlayer.play()
            externalSubtitleUri?.let(::addExternalSubtitle)
            updatePlaybackState()
            showPlayerControls()
            startProgressUpdates()

            if (startPositionMs > 0L) {
                videoSurfaceView.post {
                    mediaPlayer.time = startPositionMs
                }
            }
        } catch (e: Exception) {
            if (e !is IOException && e !is SecurityException) throw e
            onVideoOpenFailed()
        }
    }

    private fun onVideoOpenFailed() {
        currentVideoUri = null
        openVideoButton.visibility = View.VISIBLE
        hidePlayerControls()
        setKeepScreenOn(false)
        Toast.makeText(this, R.string.video_open_error, Toast.LENGTH_SHORT).show()
    }

    private fun persistReadAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
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

    private fun applySubtitleSurfaceLayout() {
        val playerHeight = controlsContainer.height
            .takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val surfaceHeight = (playerHeight * subtitlePosition.surfaceHeightPercent) / 100
        val scale = subtitleSizePercent / 100f

        subtitlesSurfaceView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            surfaceHeight.coerceAtLeast(dp(1)),
        ).apply {
            gravity = Gravity.TOP
        }
        subtitlesSurfaceView.pivotX = resources.displayMetrics.widthPixels / 2f
        subtitlesSurfaceView.pivotY = when (subtitlePosition) {
            SubtitlePosition.TOP -> 0f
            SubtitlePosition.CENTER -> surfaceHeight / 2f
            SubtitlePosition.BOTTOM -> surfaceHeight.toFloat()
        }
        subtitlesSurfaceView.scaleX = scale
        subtitlesSurfaceView.scaleY = scale
        updateVideoOutputSize()
    }

    private fun applySubtitleMediaOptions(media: Media) {
        val fontSize = (DEFAULT_VLC_SUBTITLE_FONT_SIZE * subtitleSizePercent) /
            DEFAULT_SUBTITLE_SIZE_PERCENT
        media.addOption(":freetype-rel-fontsize=${fontSize.coerceIn(8, 32)}")
        media.addOption(":sub-margin=${dp(subtitlePosition.bottomMarginDp)}")
    }

    private fun applyDecoderOptions(media: Media) {
        val useHardwareDecoder = Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU
        media.setHWDecoderEnabled(useHardwareDecoder, false)
        if (!useHardwareDecoder) {
            media.addOption(":avcodec-hw=none")
        }
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
            hidePlayerControls()
        } else {
            attachPlayerViews()
            mediaPlayer.play()
            setKeepScreenOn(true)
            startProgressUpdates()
            showPlayerControls()
        }
        updatePlaybackState()
    }

    private fun showSettingsPopup() {
        settingsPopup?.dismiss()
        cancelControlsAutoHide()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_popup)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        addAudioSection(content)
        addDivider(content)
        addSubtitleSection(content)

        val popupWidth = dp(320)
            .coerceAtMost(resources.displayMetrics.widthPixels - dp(32))
            .coerceAtLeast(dp(240))

        settingsPopup = PopupWindow(
            content,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setOnDismissListener {
                settingsPopup = null
                scheduleControlsAutoHide()
            }
        }

        settingsPopup?.showAtLocation(
            controlsContainer,
            Gravity.BOTTOM or Gravity.END,
            dp(16),
            controlsContainer.paddingBottom + dp(88),
        )
    }

    private fun addAudioSection(parent: LinearLayout) {
        addSectionTitle(parent, getString(R.string.audio_tracks))
        val audioTracks = mediaPlayer.audioTracks.toTrackMenuItems()
        if (audioTracks.isEmpty()) {
            addDisabledRow(parent, getString(R.string.tracks_empty))
        } else {
            audioTracks.forEach { track ->
                addMenuRow(
                    parent = parent,
                    text = track.name.ifBlank { track.id.toString() },
                    isSelected = track.id == mediaPlayer.audioTrack,
                ) {
                    val currentTime = mediaPlayer.time
                    mediaPlayer.audioTrack = track.id
                    mediaPlayer.time = currentTime
                    updatePlaybackProgress()
                    showSettingsPopup()
                }
            }
        }
    }

    private fun addSubtitleSection(parent: LinearLayout) {
        addSectionTitle(parent, getString(R.string.subtitle_tracks))
        addMenuRow(
            parent = parent,
            text = getString(R.string.load_subtitles),
            isSelected = externalSubtitleUri != null,
        ) {
            settingsPopup?.dismiss()
            openSubtitleDocument.launch(SUBTITLE_MIME_TYPES)
        }
        addSubtitleTrackRows(parent)
        addSubtitleSizeRow(parent)
        addSectionTitle(parent, getString(R.string.subtitle_position))
        SubtitlePosition.entries.forEach { position ->
            addMenuRow(
                parent = parent,
                text = getString(position.labelRes),
                isSelected = position == subtitlePosition,
            ) {
                subtitlePosition = position
                applySubtitleSurfaceLayout()
                showSettingsPopup()
            }
        }
    }

    private fun addSectionTitle(parent: LinearLayout, text: String) {
        parent.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(6), dp(8), dp(4))
            },
            popupRowParams(),
        )
    }

    private fun addMenuRow(
        parent: LinearLayout,
        text: String,
        isSelected: Boolean,
        onClick: () -> Unit,
    ) {
        parent.addView(
            TextView(this).apply {
                this.text = if (isSelected) "✓ $text" else "   $text"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { onClick() }
            },
            popupRowParams(),
        )
    }

    private fun addDisabledRow(parent: LinearLayout, text: String) {
        parent.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(Color.LTGRAY)
                textSize = 14f
                setPadding(dp(8), dp(10), dp(8), dp(10))
            },
            popupRowParams(),
        )
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(
            View(this).apply {
                setBackgroundColor(Color.argb(64, 255, 255, 255))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            },
        )
    }

    private fun addSubtitleSizeRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        row.addView(
            TextView(this).apply {
                text = getString(R.string.subtitle_size)
                setTextColor(Color.WHITE)
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        row.addView(createPopupButton("-") {
            updateSubtitleSize(SUBTITLE_SIZE_STEP_PERCENT.unaryMinus())
        })
        row.addView(
            TextView(this).apply {
                text = "$subtitleSizePercent%"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        row.addView(createPopupButton("+") {
            updateSubtitleSize(SUBTITLE_SIZE_STEP_PERCENT)
        })

        parent.addView(row, popupRowParams())
    }

    private fun popupRowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun createPopupButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun updateSubtitleSize(deltaPercent: Int) {
        subtitleSizePercent = (subtitleSizePercent + deltaPercent)
            .coerceIn(MIN_SUBTITLE_SIZE_PERCENT, MAX_SUBTITLE_SIZE_PERCENT)
        applySubtitleSurfaceLayout()
        showSettingsPopup()
    }

    private fun addSubtitleTrackRows(parent: LinearLayout) {
        val subtitleTracks = subtitleTracksWithOff()
        if (subtitleTracks.isEmpty()) {
            addDisabledRow(parent, getString(R.string.tracks_empty))
            return
        }

        subtitleTracks.forEach { track ->
            addMenuRow(
                parent = parent,
                text = track.name.ifBlank { track.id.toString() },
                isSelected = track.id == mediaPlayer.spuTrack,
            ) {
                mediaPlayer.spuTrack = track.id
                updatePlaybackProgress()
                showSettingsPopup()
            }
        }
    }

    private fun subtitleTracksWithOff(): List<TrackMenuItem> =
        listOf(TrackMenuItem(DISABLED_TRACK_ID, getString(R.string.subtitles_off))) +
            mediaPlayer.spuTracks.toTrackMenuItems()

    private fun addExternalSubtitle(uri: Uri) {
        val added = mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, uri, true)
        if (!added) {
            Toast.makeText(this, R.string.subtitle_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun Uri.displayName(): String {
        contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex).orEmpty().ifBlank { lastPathSegment.orEmpty() }
                    }
                }
            }
        return lastPathSegment.orEmpty().ifBlank { toString() }
    }

    private fun Array<MediaPlayer.TrackDescription>?.toTrackMenuItems(): List<TrackMenuItem> =
        orEmpty().map { track -> TrackMenuItem(track.id, track.name) }

    private fun startProgressUpdates() {
        controlsHandler.removeCallbacks(progressUpdater)
        controlsHandler.post(progressUpdater)
    }

    private fun stopProgressUpdates() {
        controlsHandler.removeCallbacks(progressUpdater)
        updatePlaybackProgress()
    }

    private fun showPlayerControls() {
        if (currentVideoUri == null) {
            return
        }

        playerControls.visibility = View.VISIBLE
        scheduleControlsAutoHide()
    }

    private fun hidePlayerControls() {
        cancelControlsAutoHide()
        playerControls.visibility = View.GONE
    }

    private fun scheduleControlsAutoHide() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_DELAY_MS)
    }

    private fun cancelControlsAutoHide() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
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
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key)
        }

    private enum class SubtitlePosition(
        val labelRes: Int,
        val surfaceHeightPercent: Int,
        val bottomMarginDp: Int,
    ) {
        TOP(R.string.subtitle_position_top, surfaceHeightPercent = 32, bottomMarginDp = 420),
        CENTER(R.string.subtitle_position_center, surfaceHeightPercent = 60, bottomMarginDp = 180),
        BOTTOM(R.string.subtitle_position_bottom, surfaceHeightPercent = 100, bottomMarginDp = 32),
    }

    private data class TrackMenuItem(
        val id: Int,
        val name: String,
    )

    private companion object {
        const val KEY_VIDEO_URI = "video_uri"
        const val KEY_SUBTITLE_URI = "subtitle_uri"
        const val KEY_POSITION_MS = "position_ms"
        const val MILLIS_IN_SECOND = 1000L
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        const val CONTROLS_AUTO_HIDE_DELAY_MS = 2000L
        const val DEFAULT_SUBTITLE_SIZE_PERCENT = 100
        const val MIN_SUBTITLE_SIZE_PERCENT = 75
        const val MAX_SUBTITLE_SIZE_PERCENT = 150
        const val SUBTITLE_SIZE_STEP_PERCENT = 10
        const val DEFAULT_VLC_SUBTITLE_FONT_SIZE = 16
        const val DISABLED_TRACK_ID = -1
        const val SECONDS_IN_HOUR = 3600L
        const val SECONDS_IN_MINUTE = 60L
        val SUBTITLE_MIME_TYPES = arrayOf(
            "application/x-subrip",
            "text/plain",
            "text/vtt",
            "text/*",
            "*/*",
        )
    }
}
