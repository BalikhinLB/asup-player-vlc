package com.lb.asupplayer

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lb.asupplayer.subtitle.MkvSubtitleExtractor
import com.lb.asupplayer.subtitle.SrtParser
import com.lb.asupplayer.subtitle.SubtitleRenderer
import com.lb.asupplayer.subtitle.SubtitleTrack
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class MainActivity : ComponentActivity() {
    private lateinit var libVlc: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoSurfaceView: SurfaceView
    private lateinit var controlsContainer: FrameLayout
    private lateinit var openVideoButton: Button
    private lateinit var playerControls: FrameLayout
    private lateinit var playbackSeek: SeekBar
    private lateinit var playbackTime: TextView
    private lateinit var playPauseButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var subtitleView: TextView
    private lateinit var subtitleIndexingIndicator: View
    private lateinit var indexingFileNameView: TextView
    private lateinit var subtitleRenderer: SubtitleRenderer
    private var currentVideoDescriptor: AssetFileDescriptor? = null
    private var settingsPopup: PopupWindow? = null
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hidePlayerControls() }

    private var currentVideoUri: Uri? = null
    private var externalSubtitleUri: Uri? = null
    private var restorePositionMs: Long = 0L
    private var isSeeking = false
    private var knownLengthMs = 0L
    private var subtitleSizePercent = DEFAULT_SUBTITLE_SIZE_PERCENT
    private var subtitlePosition = SubtitlePosition.BOTTOM

    private var pausedPositionMs = -1L
    private var internalSubtitleTracks: List<SubtitleTrack> = emptyList()
    private var externalSubtitleTrack: SubtitleTrack? = null
    private var activeSubtitleTrack: SubtitleTrack? = null
    private val subtitleExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var currentVideoJobId = 0

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
                parseExternalSubtitle(uri, currentVideoJobId)
                showPlayerControls()
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
        controlsContainer = findViewById(R.id.controls_container)
        openVideoButton = findViewById(R.id.open_video_button)
        playerControls = findViewById(R.id.player_controls)
        playbackSeek = findViewById(R.id.playback_seek)
        playbackTime = findViewById(R.id.playback_time)
        playPauseButton = findViewById(R.id.play_pause_button)
        settingsButton = findViewById(R.id.settings_button)
        subtitleView = findViewById(R.id.subtitle_view)
        subtitleIndexingIndicator = findViewById(R.id.subtitle_indexing_indicator)
        indexingFileNameView = findViewById(R.id.indexing_file_name)
        subtitleRenderer = SubtitleRenderer(subtitleView)

        applySubtitlePosition()
        applySubtitleTextSize()

        videoSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (currentVideoUri != null) attachPlayerViews()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Size is handled in attachPlayerViews() and onConfigurationChanged().
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Pause before detaching: VLC keeps rendering for ~20ms after detach,
                // causing BufferQueue abandoned errors and H.264 reference frame corruption.
                pausedPositionMs = mediaPlayer.time.coerceAtLeast(0L)
                mediaPlayer.pause()
                mediaPlayer.vlcVout.detachViews()
            }
        })

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
        updateVideoOutputSize()
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
        subtitleExecutor.shutdown()
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

            internalSubtitleTracks = emptyList()
            externalSubtitleTrack = null
            activeSubtitleTrack = null
            subtitleRenderer.reset()

            val media = Media(libVlc, videoDescriptor)
            applyDecoderOptions(media)
            mediaPlayer.media = media
            media.release()
            attachPlayerViews()
            applySubtitlePosition()

            val jobId = ++currentVideoJobId
            indexingFileNameView.text = uri.displayName()
            subtitleIndexingIndicator.visibility = View.VISIBLE
            startMkvSubtitleExtraction(uri, jobId, startPositionMs)
            externalSubtitleUri?.let { parseExternalSubtitle(it, jobId) }
        } catch (e: Exception) {
            if (e !is IOException && e !is SecurityException) throw e
            onVideoOpenFailed()
        }
    }

    private fun startMkvSubtitleExtraction(uri: Uri, jobId: Int, startPositionMs: Long) {
        subtitleExecutor.execute {
            val tracks = try {
                MkvSubtitleExtractor().extract(applicationContext, uri)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                subtitleIndexingIndicator.visibility = View.GONE
                if (currentVideoJobId == jobId) {
                    internalSubtitleTracks = tracks
                    mediaPlayer.play()
                    updatePlaybackState()
                    showPlayerControls()
                    startProgressUpdates()
                    if (startPositionMs > 0L) {
                        videoSurfaceView.post { mediaPlayer.time = startPositionMs }
                    }
                }
            }
        }
    }

    private fun parseExternalSubtitle(uri: Uri, jobId: Int) {
        subtitleExecutor.execute {
            try {
                val entries = contentResolver.openInputStream(uri)?.use { SrtParser.parse(it) }
                    ?: emptyList()
                val name = uri.displayName()
                val track = SubtitleTrack(EXTERNAL_TRACK_ID, name, entries)
                runOnUiThread {
                    if (currentVideoJobId == jobId) {
                        externalSubtitleTrack = track
                        activeSubtitleTrack = track
                        subtitleRenderer.activeTrack = track
                        Toast.makeText(
                            this,
                            getString(R.string.subtitle_loaded, name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (currentVideoJobId == jobId) {
                        Toast.makeText(this, R.string.subtitle_open_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun onVideoOpenFailed() {
        currentVideoUri = null
        subtitleIndexingIndicator.visibility = View.GONE
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
        if (mediaPlayer.vlcVout.areViewsAttached()) return
        mediaPlayer.vlcVout.setVideoView(videoSurfaceView)
        mediaPlayer.vlcVout.attachViews()
        updateVideoOutputSize()
    }

    private fun applySubtitlePosition() {
        val params = subtitleView.layoutParams as FrameLayout.LayoutParams
        params.gravity = subtitlePosition.gravity
        params.topMargin = if (subtitlePosition == SubtitlePosition.TOP) dp(16) else 0
        params.bottomMargin = if (subtitlePosition == SubtitlePosition.BOTTOM) dp(80) else 0
        subtitleView.layoutParams = params
    }

    private fun applySubtitleTextSize() {
        subtitleView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            BASE_SUBTITLE_SP * subtitleSizePercent / 100f,
        )
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
            val seekTo = pausedPositionMs
            pausedPositionMs = -1L
            mediaPlayer.play()
            if (seekTo > 0L) mediaPlayer.time = seekTo
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
                applySubtitlePosition()
                showSettingsPopup()
            }
        }
    }

    private fun addSubtitleTrackRows(parent: LinearLayout) {
        val tracks = internalSubtitleTracks + listOfNotNull(externalSubtitleTrack)
        if (tracks.isEmpty()) {
            addDisabledRow(parent, getString(R.string.tracks_empty))
            return
        }
        addMenuRow(
            parent = parent,
            text = getString(R.string.subtitles_off),
            isSelected = activeSubtitleTrack == null,
        ) {
            activeSubtitleTrack = null
            subtitleRenderer.activeTrack = null
            showSettingsPopup()
        }
        tracks.forEach { track ->
            addMenuRow(
                parent = parent,
                text = track.name,
                isSelected = track.id == activeSubtitleTrack?.id,
            ) {
                activeSubtitleTrack = track
                subtitleRenderer.activeTrack = track
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
        applySubtitleTextSize()
        showSettingsPopup()
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
        if (currentVideoUri == null) return
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
        subtitleRenderer.onTimeChanged(currentTimeMs)
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

    private enum class SubtitlePosition(val labelRes: Int, val gravity: Int) {
        TOP(R.string.subtitle_position_top, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        CENTER(R.string.subtitle_position_center, Gravity.CENTER),
        BOTTOM(R.string.subtitle_position_bottom, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
    }

    private data class TrackMenuItem(val id: Int, val name: String)

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
        const val BASE_SUBTITLE_SP = 18f
        const val EXTERNAL_TRACK_ID = Int.MAX_VALUE
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
