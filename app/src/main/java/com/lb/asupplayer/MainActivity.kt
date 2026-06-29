package com.lb.asupplayer

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.LocaleList
import android.provider.Settings
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.roundToInt
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.TextUtils
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import `is`.xyz.mpv.MPVLib
import com.lb.asupplayer.mpv.MPVView
import com.lb.asupplayer.subtitle.MkvSubtitleExtractor
import com.lb.asupplayer.subtitle.SrtParser
import com.lb.asupplayer.subtitle.SubtitleRenderer
import com.lb.asupplayer.subtitle.SubtitleTrack
import android.os.ParcelFileDescriptor
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var mpvView: MPVView
    private lateinit var controlsContainer: FrameLayout
    private lateinit var openVideoButton: Button
    private lateinit var playerControls: FrameLayout
    private lateinit var playbackSeek: SeekBar
    private lateinit var playbackTime: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var subtitleView: TextView
    private lateinit var subtitleIndexingIndicator: View
    private lateinit var indexingFileNameView: TextView
    private lateinit var subtitleRenderer: SubtitleRenderer
    private lateinit var menuOverlay: FrameLayout
    private var currentSettingsPage: SettingsPage? = null
    private var helpScreenView: View? = null
    private var subtitleSingleTapAction: SubtitleTapAction = SubtitleTapAction.ShowMenu
    private var subtitleDoubleTapAction: SubtitleTapAction = SubtitleTapAction.ShowMenu
    private var subtitleLongPressAction: SubtitleTapAction = SubtitleTapAction.ShowMenu
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hidePlayerControls() }
    private val hideSwipeIndicatorRunnable = Runnable { dismissSwipeIndicator() }

    private enum class SwipeMode { NONE, BRIGHTNESS, VOLUME }
    private var swipeMode = SwipeMode.NONE
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeLastY = 0f
    private var swipeConsumed = false
    private var swipeIndicatorView: LinearLayout? = null
    private var swipeIndicatorIsLeft = false
    private var volumeFraction = -1f   // accumulated float volume, -1 = not yet initialised

    private lateinit var recentFilesStore: RecentFilesStore
    private lateinit var homeScreen: LinearLayout
    private lateinit var recentFilesList: LinearLayout
    private lateinit var openInPlayerButton: ImageButton
    private var currentVideoName: String = ""
    private var pendingSubtitleTrackId: Int = -1
    private var pendingAudioTrackId: Int = RecentFile.AUDIO_NOT_SET
    private var coverHomeScreenUntilPlaying: Boolean = false
    private lateinit var subtitleVisibilityButton: ImageButton
    private var subtitlesHidden: Boolean = false

    private var currentVideoUri: Uri? = null
    private var externalSubtitleUri: Uri? = null
    private var restorePositionMs: Long = 0L
    private var isSeeking = false
    private var knownLengthMs = 0L
    // currentPfd is the fd MPV is actively reading; prevPfd is the fd for the file MPV just
    // stopped (kept alive until MPV_EVENT_END_FILE confirms MPV closed the old stream).
    private var currentPfd: ParcelFileDescriptor? = null
    private var prevPfd: ParcelFileDescriptor? = null
    private var pendingPlay = false  // play after FILE_LOADED (used in cached path)
    private val mpvObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> runOnUiThread {
                    if (!value) {
                        setKeepScreenOn(true)
                        startProgressUpdates()
                        showPlayerControls()
                    } else {
                        setKeepScreenOn(false)
                        cancelControlsAutoHide()
                        stopProgressUpdates()
                    }
                    updatePlaybackState()
                }
                "eof-reached" -> if (value) runOnUiThread {
                    setKeepScreenOn(false)
                    hidePlayerControls()
                    stopProgressUpdates()
                    updatePlaybackState()
                }
            }
        }
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: Double) {
            if (property == "time-pos" && phraseSeekTargetMs < 0L) {
                val ms = (value * 1000).toLong().coerceAtLeast(0L)
                runOnUiThread { subtitleRenderer.onTimeChanged(ms) }
            }
        }
        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> runOnUiThread {
                    // MPV has fully opened the new file — safe to release the old fd now
                    prevPfd?.close(); prevPfd = null
                    if (restorePositionMs > 0L) {
                        mpvView.seekTo(restorePositionMs)
                        restorePositionMs = 0L
                    }
                    if (pendingAudioTrackId != RecentFile.AUDIO_NOT_SET) {
                        mpvView.audioTrack = pendingAudioTrackId
                        pendingAudioTrackId = RecentFile.AUDIO_NOT_SET
                    }
                    if (coverHomeScreenUntilPlaying) {
                        homeScreen.visibility = View.GONE
                        coverHomeScreenUntilPlaying = false
                    }
                    if (pendingPlay) {
                        pendingPlay = false
                        mpvView.play()
                    }
                    updatePlaybackProgress()
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> runOnUiThread {
                    // MPV has stopped the old stream — safe to close the old fd
                    prevPfd?.close(); prevPfd = null
                    setKeepScreenOn(false)
                    hidePlayerControls()
                    stopProgressUpdates()
                    updatePlaybackState()
                }
            }
        }
    }
    private var subtitleSizePercent = DEFAULT_SUBTITLE_SIZE_PERCENT
    private var subtitlePositionPercent: Int = 15

    private var internalSubtitleTracks: List<SubtitleTrack> = emptyList()
    private var externalSubtitleTrack: SubtitleTrack? = null
    private var activeSubtitleTrack: SubtitleTrack? = null
    private val subtitleExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var currentVideoJobId = 0

    private lateinit var bottomControls: View
    private lateinit var doubleTapDetector: GestureDetector

    private var phraseSeekTargetMs = -1L
    private val clearPhraseSeekRunnable = Runnable {
        phraseSeekTargetMs = -1L
        updatePlaybackProgress()
    }

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

    override fun attachBaseContext(newBase: Context) {
        val languageTag = newBase
            .getSharedPreferences(LANGUAGE_PREFS, MODE_PRIVATE)
            .getString(PREF_APP_LANGUAGE_TAG, null)
        super.attachBaseContext(newBase.withAppLocale(languageTag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentVideoUri = savedInstanceState?.getParcelableCompat(KEY_VIDEO_URI)
        externalSubtitleUri = savedInstanceState?.getParcelableCompat(KEY_SUBTITLE_URI)
        restorePositionMs = savedInstanceState?.getLong(KEY_POSITION_MS) ?: 0L

        recentFilesStore = RecentFilesStore(this)

        setContentView(R.layout.activity_main)
        mpvView = findViewById(R.id.video_surface)
        mpvView.initialize(filesDir.absolutePath, cacheDir.absolutePath)
        mpvView.addObserver(mpvObserver)
        controlsContainer = findViewById(R.id.controls_container)
        homeScreen = findViewById(R.id.home_screen)
        if (currentVideoUri != null) homeScreen.visibility = View.GONE
        recentFilesList = findViewById(R.id.recent_files_list)
        openVideoButton = findViewById(R.id.open_video_button)
        playerControls = findViewById(R.id.player_controls)
        openInPlayerButton = findViewById(R.id.open_in_player_button)
        playbackSeek = findViewById(R.id.playback_seek)
        playbackTime = findViewById(R.id.playback_time)
        playPauseButton = findViewById(R.id.play_pause_button)
        settingsButton = findViewById(R.id.settings_button)
        subtitleVisibilityButton = findViewById(R.id.subtitle_visibility_button)
        subtitleView = findViewById(R.id.subtitle_view)
        subtitleIndexingIndicator = findViewById(R.id.subtitle_indexing_indicator)
        indexingFileNameView = findViewById(R.id.indexing_file_name)
        bottomControls = findViewById(R.id.bottom_controls)
        menuOverlay = findViewById(R.id.menu_overlay)
        menuOverlay.setOnClickListener { dismissMenuOverlay() }
        subtitleRenderer = SubtitleRenderer(subtitleView)

        populateHomeScreen()

        doubleTapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (currentVideoUri == null) return false
                    // When paused and tap is on visible subtitle — let the subtitle handler deal with it
                    if (!mpvView.isPlaying &&
                        subtitleView.visibility == View.VISIBLE &&
                        subtitleView.containsRaw(e)
                    ) return true
                    val onButton = playPauseButton.containsRaw(e) || bottomControls.containsRaw(e) || openInPlayerButton.containsRaw(e)
                    if (playerControls.visibility == View.VISIBLE && !onButton) {
                        hidePlayerControls()
                    } else {
                        showPlayerControls()
                    }
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentVideoUri == null) return false
                    if (!mpvView.isPlaying &&
                        subtitleView.visibility == View.VISIBLE &&
                        subtitleView.containsRaw(e)
                    ) return true  // subtitle gesture detector handles it
                    if (e.x < controlsContainer.width / 2f) seekToPrevPhrase()
                    else seekToNextPhrase()
                    return true
                }
            },
        )

        applySubtitlePosition()
        applySubtitleTextSize()

        openVideoButton.setOnClickListener {
            openDocument.launch(arrayOf("video/*"))
        }
        openInPlayerButton.setOnClickListener {
            showOpenFilePopup()
        }
        playPauseButton.setOnClickListener {
            togglePlayback()
        }
        subtitleVisibilityButton.setOnClickListener {
            subtitlesHidden = !subtitlesHidden
            subtitleRenderer.isHidden = subtitlesHidden
            subtitleRenderer.onTimeChanged(mpvView.timeMs)
            subtitleVisibilityButton.setImageResource(
                if (subtitlesHidden) R.drawable.ic_subtitle_visibility_off
                else R.drawable.ic_subtitle_visibility,
            )
            showPlayerControls()
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
                    mpvView.seekTo(progressToTime(seekBar.progress))
                    isSeeking = false
                    updatePlaybackProgress()
                    scheduleControlsAutoHide()
                }
            },
        )

        ViewCompat.setOnApplyWindowInsetsListener(controlsContainer) { view, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            view.setPadding(0, 0, 0, if (isLandscape) 0 else navigationBars.bottom)
            insets
        }

        applySystemBarsMode(resources.configuration)

        currentVideoUri?.let { uri ->
            val cached = recentFilesStore.loadSubtitles(uri)
            recentFilesStore.getAll().find { it.uri == uri }?.let { recent ->
                subtitleSizePercent = recent.subtitleSizePercent
                subtitlePositionPercent = recent.subtitlePositionPercent
                pendingSubtitleTrackId = recent.activeSubtitleTrackId
                pendingAudioTrackId = recent.audioTrackId
                applySubtitleTextSize()
                applySubtitlePosition()
            }
            playVideo(uri, restorePositionMs, cached)
        }

        loadSubtitleTapAction()
        setupSubtitleTapHandler()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsMode(newConfig)
        ViewCompat.requestApplyInsets(controlsContainer)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentVideoUri?.let { outState.putParcelable(KEY_VIDEO_URI, it) }
        externalSubtitleUri?.let { outState.putParcelable(KEY_SUBTITLE_URI, it) }
        outState.putLong(KEY_POSITION_MS, mpvView.timeMs)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            saveCurrentSettings()
            mpvView.pause()
            setKeepScreenOn(false)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarsMode(resources.configuration)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (menuOverlay.visibility != View.VISIBLE) {
            handleSwipeGesture(ev)
            if (!swipeConsumed) doubleTapDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissMenuOverlay()
        controlsHandler.removeCallbacks(progressUpdater)
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.removeCallbacks(hideSwipeIndicatorRunnable)
        mpvView.removeObserver(mpvObserver)
        mpvView.destroy()
        currentPfd?.close()
        prevPfd?.close()
        subtitleExecutor.shutdown()
    }

    private fun playVideo(uri: Uri, startPositionMs: Long, cachedSubtitles: List<SubtitleTrack>? = null) {
        val newPfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) { null }
        if (newPfd == null) { onVideoOpenFailed(); return }

        // Rotate fds: keep old fd alive until MPV_EVENT_END_FILE confirms MPV closed old stream.
        prevPfd?.close()
        prevPfd = currentPfd
        currentPfd = newPfd

        currentVideoUri = uri
        currentVideoName = uri.displayName()
        restorePositionMs = startPositionMs
        coverHomeScreenUntilPlaying = false
        subtitlesHidden = false
        subtitleVisibilityButton.setImageResource(R.drawable.ic_subtitle_visibility)

        internalSubtitleTracks = emptyList()
        externalSubtitleTrack = null
        activeSubtitleTrack = null
        subtitleRenderer.reset()

        // pause=yes so the video starts paused; we call play() when ready.
        MPVLib.setPropertyBoolean("pause", true)
        MPVLib.command(arrayOf("loadfile", "fd://${newPfd.fd}", "replace"))
        applySubtitlePosition()

        val jobId = ++currentVideoJobId
        indexingFileNameView.text = currentVideoName

        if (cachedSubtitles != null) {
            internalSubtitleTracks = cachedSubtitles
            if (pendingSubtitleTrackId >= 0) {
                activeSubtitleTrack = cachedSubtitles.find { it.id == pendingSubtitleTrackId }
                pendingSubtitleTrackId = -1
            }
            subtitleRenderer.activeTrack = activeSubtitleTrack
            applySubtitleTextSize()
            subtitleIndexingIndicator.visibility = View.GONE
            coverHomeScreenUntilPlaying = (homeScreen.visibility == View.VISIBLE)
            pendingPlay = true  // play() will be called in MPV_EVENT_FILE_LOADED
            updatePlaybackState()
        } else {
            pendingPlay = false
            homeScreen.visibility = View.GONE
            subtitleIndexingIndicator.visibility = View.VISIBLE
            startMkvSubtitleExtraction(uri, jobId, startPositionMs)
        }
        externalSubtitleUri?.let { parseExternalSubtitle(it, jobId) }
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
                    recentFilesStore.save(
                        uri = uri,
                        name = currentVideoName,
                        positionMs = 0L,
                        tracks = tracks,
                        subtitleSizePercent = subtitleSizePercent,
                        subtitlePositionPercent = subtitlePositionPercent,
                    )
                    mpvView.play()
                    updatePlaybackState()
                    showPlayerControls()
                    startProgressUpdates()
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
        coverHomeScreenUntilPlaying = false
        subtitleIndexingIndicator.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
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

    private fun applySubtitlePosition() {
        controlsContainer.post {
            val params = subtitleView.layoutParams as FrameLayout.LayoutParams
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.topMargin = 0
            val h = controlsContainer.height
            if (h > 0) {
                val minBottom = dp(25)
                val subtitleH = subtitleView.height.takeIf { it > 0 } ?: dp(40)
                val maxBottom = (h - dp(25) - subtitleH).coerceAtLeast(minBottom)
                params.bottomMargin = minBottom + (maxBottom - minBottom) * subtitlePositionPercent / 100
            } else {
                params.bottomMargin = dp(25)
            }
            subtitleView.layoutParams = params
        }
    }

    private fun applySubtitleTextSize() {
        subtitleView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            BASE_SUBTITLE_SP * subtitleSizePercent / 100f,
        )
    }



    private fun togglePlayback() {
        if (mpvView.isPlaying) {
            mpvView.pause()
        } else {
            mpvView.play()
        }
        updatePlaybackState()
    }

    private fun phraseSeekTo(targetMs: Long) {
        phraseSeekTargetMs = targetMs
        mpvView.seekTo(targetMs)
        subtitleRenderer.onTimeChanged(targetMs)
        controlsHandler.removeCallbacks(clearPhraseSeekRunnable)
        controlsHandler.postDelayed(clearPhraseSeekRunnable, PHRASE_SEEK_SUPPRESS_MS)
    }

    private fun seekToPrevPhrase() {
        val track = activeSubtitleTrack
        if (track == null) {
            phraseSeekTo((mpvView.timeMs - PHRASE_SEEK_FALLBACK_MS).coerceAtLeast(0L))
            return
        }
        val currentMs = mpvView.timeMs
        val entries = track.entries
        val idx = entries.lastIdxAtOrBefore(currentMs)
        if (idx < 0) {
            phraseSeekTo(0L)
            return
        }
        val candidate = entries[idx]
        val targetMs = if (currentMs - candidate.startMs > PHRASE_REPLAY_THRESHOLD_MS) {
            candidate.startMs
        } else {
            if (idx > 0) entries[idx - 1].startMs else candidate.startMs
        }
        phraseSeekTo(targetMs)
    }

    private fun seekToNextPhrase() {
        val track = activeSubtitleTrack
        if (track == null) {
            val target = mpvView.timeMs + PHRASE_SEEK_FALLBACK_MS
            phraseSeekTo(if (knownLengthMs > 0L) target.coerceAtMost(knownLengthMs) else target)
            return
        }
        val currentMs = mpvView.timeMs
        val idx = track.entries.firstIdxAfter(currentMs)
        if (idx >= 0) phraseSeekTo(track.entries[idx].startMs)
    }

    private fun playRecentFile(recent: RecentFile) {
        dismissMenuOverlay()
        recentFilesStore.moveToFront(recent.uri)
        val cached = recentFilesStore.loadSubtitles(recent.uri)
        externalSubtitleUri = null
        subtitleSizePercent = recent.subtitleSizePercent
        subtitlePositionPercent = recent.subtitlePositionPercent
        pendingSubtitleTrackId = recent.activeSubtitleTrackId
        pendingAudioTrackId = recent.audioTrackId
        applySubtitleTextSize()
        applySubtitlePosition()
        playVideo(recent.uri, recent.lastPositionMs, cached)
    }

    private fun populateHomeScreen() {
        val recents = recentFilesStore.getAll()
        recentFilesList.removeAllViews()
        if (recents.isEmpty()) {
            recentFilesList.visibility = View.GONE
        } else {
            addSectionTitle(recentFilesList, getString(R.string.recent_files))
            recents.forEach { addHomeRecentRow(it) }
            recentFilesList.visibility = View.VISIBLE
        }
    }

    private fun addHomeRecentRow(recent: RecentFile) {
        val rippleAttr = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setBackgroundResource(rippleAttr.resourceId)
            isClickable = true
            setOnClickListener { playRecentFile(recent) }
        }
        row.addView(
            TextView(this).apply {
                text = recent.displayName
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (recent.lastPositionMs > 0L) {
            row.addView(
                TextView(this).apply {
                    text = "с ${formatTime(recent.lastPositionMs)}"
                    setTextColor(Color.LTGRAY)
                    textSize = 13f
                },
            )
        }
        recentFilesList.addView(row, popupRowParams())
    }

    private fun showOpenFilePopup() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_popup)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        addMenuRow(content, getString(R.string.open_file), false) {
            dismissMenuOverlay()
            openDocument.launch(arrayOf("video/*"))
        }

        val recents = recentFilesStore.getAll()
        if (recents.isNotEmpty()) {
            addDivider(content)
            addSectionTitle(content, getString(R.string.recent_files))
            recents.forEach { addRecentPopupRow(content, it) }
        }

        val width = dp(320)
            .coerceAtMost(resources.displayMetrics.widthPixels - dp(32))
            .coerceAtLeast(dp(240))

        showMenuOverlay(
            content,
            gravity = Gravity.TOP or Gravity.END,
            width = width,
            marginEnd = dp(8),
            marginTop = dp(64),
        )
    }

    private fun addRecentPopupRow(parent: LinearLayout, recent: RecentFile) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { playRecentFile(recent) }
        }
        row.addView(
            TextView(this).apply {
                text = recent.displayName
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (recent.lastPositionMs > 0L) {
            row.addView(
                TextView(this).apply {
                    text = "с ${formatTime(recent.lastPositionMs)}"
                    setTextColor(Color.LTGRAY)
                    textSize = 12f
                },
            )
        }
        parent.addView(row, popupRowParams())
    }

    private fun saveCurrentSettings() {
        currentVideoUri?.let { uri ->
            recentFilesStore.updatePlaybackState(
                uri = uri,
                positionMs = mpvView.timeMs,
                activeSubtitleTrackId = activeSubtitleTrack?.id ?: -1,
                audioTrackId = mpvView.audioTrack,
                subtitleSizePercent = subtitleSizePercent,
                subtitlePositionPercent = subtitlePositionPercent,
            )
        }
    }

    private enum class SettingsPage {
        MAIN, AUDIO, SUBTITLES, SUBTITLE_ACTIONS,
        SUBTITLE_SINGLE_TAP, SUBTITLE_DOUBLE_TAP, SUBTITLE_LONG_PRESS,
        LANGUAGE,
    }

    private data class LanguageOption(val languageTag: String?, val titleResId: Int)
    private data class HelpItem(val iconResId: Int, val titleResId: Int, val bodyResId: Int)

    private fun showSettingsPopup(page: SettingsPage = SettingsPage.MAIN) {
        currentSettingsPage = page

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        when (page) {
            SettingsPage.MAIN -> {
                addMainMenuRow(content, getString(R.string.audio_tracks)) { showSettingsPopup(SettingsPage.AUDIO) }
                addMainMenuRow(content, getString(R.string.subtitle_tracks)) { showSettingsPopup(SettingsPage.SUBTITLES) }
                addDivider(content)
                addMainMenuRow(content, getString(R.string.language_settings)) { showSettingsPopup(SettingsPage.LANGUAGE) }
                addMainMenuRow(content, getString(R.string.feedback_settings)) { openFeedbackEmail() }
                addMainMenuRow(content, getString(R.string.help_settings)) { showHelpScreen() }
            }
            SettingsPage.AUDIO -> {
                addBackRow(content, getString(R.string.audio_tracks)) { showSettingsPopup(SettingsPage.MAIN) }
                addDivider(content)
                addAudioSection(content)
            }
            SettingsPage.SUBTITLES -> {
                addBackRow(content, getString(R.string.subtitle_tracks)) { showSettingsPopup(SettingsPage.MAIN) }
                addDivider(content)
                addSubtitleSection(content)
            }
            SettingsPage.SUBTITLE_ACTIONS -> {
                addBackRow(content, getString(R.string.subtitle_tap_action)) { showSettingsPopup(SettingsPage.SUBTITLES) }
                addDivider(content)
                addNavRow(content, getString(R.string.subtitle_tap_single)) { showSettingsPopup(SettingsPage.SUBTITLE_SINGLE_TAP) }
                addNavRow(content, getString(R.string.subtitle_tap_double)) { showSettingsPopup(SettingsPage.SUBTITLE_DOUBLE_TAP) }
                addNavRow(content, getString(R.string.subtitle_tap_long))   { showSettingsPopup(SettingsPage.SUBTITLE_LONG_PRESS) }
            }
            SettingsPage.SUBTITLE_SINGLE_TAP -> {
                addBackRow(content, getString(R.string.subtitle_tap_single)) { showSettingsPopup(SettingsPage.SUBTITLE_ACTIONS) }
                addDivider(content)
                addSubtitleActionPickerRows(content, subtitleSingleTapAction, PREF_SINGLE_TAP_ACTION, SettingsPage.SUBTITLE_SINGLE_TAP)
            }
            SettingsPage.SUBTITLE_DOUBLE_TAP -> {
                addBackRow(content, getString(R.string.subtitle_tap_double)) { showSettingsPopup(SettingsPage.SUBTITLE_ACTIONS) }
                addDivider(content)
                addSubtitleActionPickerRows(content, subtitleDoubleTapAction, PREF_DOUBLE_TAP_ACTION, SettingsPage.SUBTITLE_DOUBLE_TAP)
            }
            SettingsPage.SUBTITLE_LONG_PRESS -> {
                addBackRow(content, getString(R.string.subtitle_tap_long)) { showSettingsPopup(SettingsPage.SUBTITLE_ACTIONS) }
                addDivider(content)
                addSubtitleActionPickerRows(content, subtitleLongPressAction, PREF_LONG_PRESS_ACTION, SettingsPage.SUBTITLE_LONG_PRESS)
            }
            SettingsPage.LANGUAGE -> {
                addBackRow(content, getString(R.string.language_settings)) { showSettingsPopup(SettingsPage.MAIN) }
                addDivider(content)
                val current = currentLanguageTag()
                languageOptions().forEach { option ->
                    addMenuRow(content, getString(option.titleResId), isSelected = current == option.languageTag) {
                        setAppLanguage(option.languageTag)
                    }
                }
            }
        }

        val menuWidth = if (page == SettingsPage.MAIN) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            dp(300).coerceAtMost(resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(200))
        }

        val popupView = if (page == SettingsPage.MAIN) {
            content.apply { setBackgroundResource(R.drawable.bg_player_popup) }
        } else {
            val maxH = (resources.displayMetrics.heightPixels * 0.7f).toInt()
            android.widget.ScrollView(this).apply {
                setBackgroundResource(R.drawable.bg_player_popup)
                isVerticalScrollBarEnabled = true
                addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }.also { sv ->
                sv.post {
                    if (sv.height > maxH) {
                        val lp = sv.layoutParams
                        lp?.height = maxH
                        sv.layoutParams = lp
                    }
                }
            }
        }

        showMenuOverlay(
            popupView,
            gravity = Gravity.BOTTOM or Gravity.END,
            width = menuWidth,
            marginEnd = dp(16),
            marginBottom = controlsContainer.paddingBottom + dp(88),
        )
    }

    private fun addAudioSection(parent: LinearLayout) {
        val audioTracks = mpvView.getAudioTracks()
        if (audioTracks.isEmpty()) {
            addDisabledRow(parent, getString(R.string.tracks_empty))
        } else {
            audioTracks.forEach { track ->
                addMenuRow(
                    parent = parent,
                    text = track.name,
                    isSelected = track.id == mpvView.audioTrack,
                ) {
                    mpvView.audioTrack = track.id
                    updatePlaybackProgress()
                    saveCurrentSettings()
                    showSettingsPopup(SettingsPage.AUDIO)
                }
            }
        }
    }

    private fun addSubtitleSection(parent: LinearLayout) {
        addMenuRow(
            parent = parent,
            text = getString(R.string.load_subtitles),
            isSelected = externalSubtitleUri != null,
        ) {
            dismissMenuOverlay()
            openSubtitleDocument.launch(SUBTITLE_MIME_TYPES)
        }
        addSubtitleTrackRows(parent)
        addSubtitleSizeRow(parent)
        addSubtitlePositionRow(parent)
        addDivider(parent)
        addNavRow(parent, getString(R.string.subtitle_tap_action)) { showSettingsPopup(SettingsPage.SUBTITLE_ACTIONS) }
    }

    private fun addSubtitlePositionRow(parent: LinearLayout) {
        addStepperRow(
            parent,
            getString(R.string.subtitle_position),
            "$subtitlePositionPercent%",
            valueTag = STEPPER_TAG_POS,
            onDecrement = { updateSubtitlePosition(-SUBTITLE_POS_STEP_PERCENT) },
            onIncrement = { updateSubtitlePosition(SUBTITLE_POS_STEP_PERCENT) },
        )
    }

    private fun updateSubtitlePosition(deltaPercent: Int) {
        subtitlePositionPercent = (subtitlePositionPercent + deltaPercent)
            .coerceIn(0, MAX_SUBTITLE_POS_PERCENT)
        applySubtitlePosition()
        saveCurrentSettings()
        menuOverlay.findViewWithTag<TextView>(STEPPER_TAG_POS)?.text = "$subtitlePositionPercent%"
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
            saveCurrentSettings()
            showSettingsPopup(SettingsPage.SUBTITLES)
        }
        tracks.forEach { track ->
            addMenuRow(
                parent = parent,
                text = track.name,
                isSelected = track.id == activeSubtitleTrack?.id,
            ) {
                activeSubtitleTrack = track
                subtitleRenderer.activeTrack = track
                saveCurrentSettings()
                showSettingsPopup(SettingsPage.SUBTITLES)
            }
        }
    }

    private fun addNavRow(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                this.text = "‹"
                setTextColor(Color.LTGRAY)
                textSize = 20f
                setPadding(0, 0, dp(8), 0)
            },
        )
        row.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        parent.addView(row, popupRowParams())
    }

    private fun addMainMenuRow(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                this.text = "‹"
                setTextColor(Color.LTGRAY)
                textSize = 20f
                setPadding(0, 0, dp(8), 0)
            },
        )
        row.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
            },
        )
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addBackRow(parent: LinearLayout, title: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                this.text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            TextView(this).apply {
                text = "›"
                setTextColor(Color.LTGRAY)
                textSize = 20f
                setPadding(dp(10), 0, 0, 0)
            },
        )
        parent.addView(row, popupRowParams())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSubtitleTapHandler() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val phrase = subtitleView.text?.toString()?.takeIf { it.isNotEmpty() } ?: return true
                handleSubtitleTap(wordAtTap(e) ?: phrase, subtitleSingleTapAction)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val phrase = subtitleView.text?.toString()?.takeIf { it.isNotEmpty() } ?: return true
                handleSubtitleTap(phrase, subtitleDoubleTapAction)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val phrase = subtitleView.text?.toString()?.takeIf { it.isNotEmpty() } ?: return
                handleSubtitleTap(phrase, subtitleLongPressAction)
            }
        })

        subtitleView.setOnTouchListener { _, event ->
            if (mpvView.isPlaying) return@setOnTouchListener false
            if (subtitleView.visibility != View.VISIBLE) return@setOnTouchListener false
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun loadSubtitleTapAction() {
        val prefs = getSharedPreferences(SUBTITLE_ACTION_PREFS, MODE_PRIVATE)
        subtitleSingleTapAction = prefs.getString(PREF_SINGLE_TAP_ACTION, null)
            .toSubtitleTapAction() ?: SubtitleTapAction.ShowMenu
        subtitleDoubleTapAction = prefs.getString(PREF_DOUBLE_TAP_ACTION, null)
            .toSubtitleTapAction() ?: SubtitleTapAction.ShowMenu
        subtitleLongPressAction = prefs.getString(PREF_LONG_PRESS_ACTION, null)
            .toSubtitleTapAction() ?: SubtitleTapAction.ShowMenu
    }

    private fun saveSubtitleTapAction(prefKey: String, action: SubtitleTapAction) {
        when (prefKey) {
            PREF_SINGLE_TAP_ACTION -> subtitleSingleTapAction = action
            PREF_DOUBLE_TAP_ACTION -> subtitleDoubleTapAction = action
            PREF_LONG_PRESS_ACTION -> subtitleLongPressAction = action
        }
        getSharedPreferences(SUBTITLE_ACTION_PREFS, MODE_PRIVATE)
            .edit().putString(prefKey, action.toPreferenceValue()).apply()
    }

    private fun handleSubtitleTap(text: String, action: SubtitleTapAction) {
        when (action) {
            SubtitleTapAction.None -> Unit
            SubtitleTapAction.Copy -> copySubtitleText(text)
            SubtitleTapAction.ShowMenu -> showSubtitleActionMenu(text)
            is SubtitleTapAction.ProcessText -> {
                val ok = availableProcessTextTargets()
                    .any { it.packageName == action.packageName && it.activityName == action.activityName }
                if (ok) startProcessText(action.packageName, action.activityName, text)
                else showSubtitleActionMenu(text)
            }
        }
    }

    private fun showSubtitleActionMenu(phraseText: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_popup)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val targets = availableProcessTextTargets()
        if (targets.isEmpty()) {
            addDisabledRow(content, getString(R.string.no_apps_available))
        } else {
            targets.forEach { target ->
                addMenuRow(content, target.label, false) {
                    dismissMenuOverlay()
                    startProcessText(target.packageName, target.activityName, phraseText)
                }
            }
        }
        addDivider(content)
        addMenuRow(content, getString(R.string.subtitle_tap_copy), false) {
            dismissMenuOverlay()
            copySubtitleText(phraseText)
        }

        showMenuOverlay(
            content,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            marginBottom = controlsContainer.paddingBottom + dp(80),
        )
    }

    private fun copySubtitleText(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("subtitle", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun startProcessText(packageName: String, activityName: String, text: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .setClassName(packageName, activityName)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_apps_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun availableProcessTextTargets(): List<ProcessTextTarget> {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager).toString().takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                ProcessTextTarget(info.activityInfo.packageName, info.activityInfo.name, label)
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun wordAtTap(event: MotionEvent): String? {
        val layout = subtitleView.layout ?: return null
        val x = event.x.toInt() - subtitleView.totalPaddingLeft + subtitleView.scrollX
        val y = event.y.toInt() - subtitleView.totalPaddingTop + subtitleView.scrollY
        if (y < 0 || y > layout.height) return null
        val text = subtitleView.text?.toString() ?: return null
        if (text.isEmpty()) return null
        val line = layout.getLineForVertical(y)
        val rawOffset = layout.getOffsetForHorizontal(line, x.toFloat())
        val charIndex = when {
            rawOffset < text.length && text[rawOffset].isWordChar() -> rawOffset
            rawOffset > 0 && text[rawOffset - 1].isWordChar() -> rawOffset - 1
            else -> return null
        }
        var start = charIndex
        while (start > 0 && text[start - 1].isWordChar()) start--
        var end = charIndex + 1
        while (end < text.length && text[end].isWordChar()) end++
        return text.substring(start, end)
    }

    private fun Char.isWordChar() = isLetterOrDigit() || this == '\'' || this == '\'' || this == '-'

    private fun String?.toSubtitleTapAction(): SubtitleTapAction? = when (this) {
        null -> null
        ACTION_VALUE_NONE -> SubtitleTapAction.None
        ACTION_VALUE_COPY -> SubtitleTapAction.Copy
        ACTION_VALUE_MENU -> SubtitleTapAction.ShowMenu
        else -> if (startsWith(ACTION_VALUE_PROCESS_PREFIX)) {
            val parts = removePrefix(ACTION_VALUE_PROCESS_PREFIX).split("/", limit = 2)
            if (parts.size == 2) SubtitleTapAction.ProcessText(parts[0], parts[1]) else null
        } else null
    }

    private fun addSubtitleActionPickerRows(
        parent: LinearLayout,
        current: SubtitleTapAction,
        prefKey: String,
        returnPage: SettingsPage,
    ) {
        val targets = availableProcessTextTargets()
        targets.forEach { target ->
            val sel = current is SubtitleTapAction.ProcessText &&
                current.packageName == target.packageName &&
                current.activityName == target.activityName
            addMenuRow(parent, target.label, sel) {
                saveSubtitleTapAction(prefKey, SubtitleTapAction.ProcessText(target.packageName, target.activityName))
                showSettingsPopup(returnPage)
            }
        }
        if (targets.isNotEmpty()) addDivider(parent)
        addMenuRow(parent, getString(R.string.subtitle_tap_show_menu), current == SubtitleTapAction.ShowMenu) {
            saveSubtitleTapAction(prefKey, SubtitleTapAction.ShowMenu)
            showSettingsPopup(returnPage)
        }
        addMenuRow(parent, getString(R.string.subtitle_tap_copy), current == SubtitleTapAction.Copy) {
            saveSubtitleTapAction(prefKey, SubtitleTapAction.Copy)
            showSettingsPopup(returnPage)
        }
        addMenuRow(parent, getString(R.string.subtitle_tap_none), current == SubtitleTapAction.None) {
            saveSubtitleTapAction(prefKey, SubtitleTapAction.None)
            showSettingsPopup(returnPage)
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
        addStepperRow(
            parent,
            getString(R.string.subtitle_size),
            "$subtitleSizePercent%",
            valueTag = STEPPER_TAG_SIZE,
            onDecrement = { updateSubtitleSize(-SUBTITLE_SIZE_STEP_PERCENT) },
            onIncrement = { updateSubtitleSize(SUBTITLE_SIZE_STEP_PERCENT) },
        )
    }

    private fun addStepperRow(
        parent: LinearLayout,
        label: String,
        value: String,
        valueTag: Any? = null,
        onDecrement: () -> Unit,
        onIncrement: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        row.addView(
            TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(createStepperButton("−", onDecrement))
        row.addView(
            TextView(this).apply {
                text = value
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                tag = valueTag
            },
            LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        row.addView(createStepperButton("+", onIncrement))
        parent.addView(row, popupRowParams())
    }

    private fun createStepperButton(symbol: String, onClick: () -> Unit): TextView {
        val size = dp(32)
        return TextView(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_settings_button)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun popupRowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun updateSubtitleSize(deltaPercent: Int) {
        subtitleSizePercent = (subtitleSizePercent + deltaPercent)
            .coerceIn(MIN_SUBTITLE_SIZE_PERCENT, MAX_SUBTITLE_SIZE_PERCENT)
        applySubtitleTextSize()
        saveCurrentSettings()
        menuOverlay.findViewWithTag<TextView>(STEPPER_TAG_SIZE)?.text = "$subtitleSizePercent%"
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
        playPauseButton.setImageResource(if (mpvView.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updatePlaybackProgress() {
        knownLengthMs = mpvView.durationMs
        val currentTimeMs = mpvView.timeMs
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

    private fun showMenuOverlay(
        contentView: View,
        gravity: Int,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        marginStart: Int = 0,
        marginEnd: Int = 0,
        marginTop: Int = 0,
        marginBottom: Int = 0,
    ) {
        val isNew = menuOverlay.visibility != View.VISIBLE
        menuOverlay.removeAllViews()
        contentView.isClickable = true
        contentView.elevation = dp(8).toFloat()
        menuOverlay.addView(
            contentView,
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                this.gravity = gravity
                this.marginStart = marginStart
                this.marginEnd = marginEnd
                topMargin = marginTop
                bottomMargin = marginBottom
            },
        )
        if (isNew) {
            cancelControlsAutoHide()
            menuOverlay.visibility = View.VISIBLE
        }
    }

    private fun dismissMenuOverlay() {
        if (menuOverlay.visibility == View.GONE) return
        currentSettingsPage = null
        menuOverlay.visibility = View.GONE
        menuOverlay.removeAllViews()
        scheduleControlsAutoHide()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentSettingsPage) {
            null -> if (menuOverlay.visibility == View.VISIBLE) dismissMenuOverlay() else super.onBackPressed()
            SettingsPage.MAIN -> dismissMenuOverlay()
            SettingsPage.AUDIO, SettingsPage.SUBTITLES -> showSettingsPopup(SettingsPage.MAIN)
            SettingsPage.SUBTITLE_ACTIONS -> showSettingsPopup(SettingsPage.SUBTITLES)
            SettingsPage.SUBTITLE_SINGLE_TAP, SettingsPage.SUBTITLE_DOUBLE_TAP, SettingsPage.SUBTITLE_LONG_PRESS ->
                showSettingsPopup(SettingsPage.SUBTITLE_ACTIONS)
            SettingsPage.LANGUAGE -> showSettingsPopup(SettingsPage.MAIN)
        }
    }

    private fun handleSwipeGesture(ev: MotionEvent) {
        if (currentVideoUri == null) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeLastY = ev.y
                swipeMode = SwipeMode.NONE
                swipeConsumed = false
                volumeFraction = -1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeMode == SwipeMode.NONE) {
                    val totalDy = abs(ev.y - swipeDownY)
                    val totalDx = abs(ev.x - swipeDownX)
                    // Don't activate in bottom controls area
                    val inControls = swipeDownY > controlsContainer.height - bottomControls.height - dp(16)
                    if (!inControls && totalDy > dp(20) && totalDy > totalDx * 1.5f) {
                        swipeMode = if (swipeDownX < controlsContainer.width / 2f) {
                            SwipeMode.BRIGHTNESS
                        } else {
                            SwipeMode.VOLUME
                        }
                        swipeConsumed = true
                    }
                }
                if (swipeMode != SwipeMode.NONE) {
                    val delta = (swipeLastY - ev.y) / controlsContainer.height  // + = up = increase
                    swipeLastY = ev.y
                    when (swipeMode) {
                        SwipeMode.BRIGHTNESS -> adjustBrightness(delta)
                        SwipeMode.VOLUME -> adjustVolume(delta)
                        else -> {}
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swipeConsumed) {
                    controlsHandler.removeCallbacks(hideSwipeIndicatorRunnable)
                    controlsHandler.postDelayed(hideSwipeIndicatorRunnable, 1500)
                }
                swipeMode = SwipeMode.NONE
            }
        }
    }

    private fun adjustBrightness(deltaFraction: Float) {
        val lp = window.attributes
        val current = if (lp.screenBrightness < 0f) {
            try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (_: Exception) { 0.5f }
        } else {
            lp.screenBrightness
        }
        lp.screenBrightness = (current + deltaFraction).coerceIn(0.01f, 1.0f)
        window.attributes = lp
        showSwipeIndicator(R.drawable.ic_brightness, lp.screenBrightness, isLeft = false)
    }

    private fun adjustVolume(deltaFraction: Float) {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (volumeFraction < 0f) {
            volumeFraction = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }
        volumeFraction = (volumeFraction + deltaFraction).coerceIn(0f, 1f)
        val newVolume = (volumeFraction * max).roundToInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        showSwipeIndicator(R.drawable.ic_volume, volumeFraction, isLeft = true)
    }

    private fun showSwipeIndicator(iconRes: Int, level: Float, isLeft: Boolean) {
        controlsHandler.removeCallbacks(hideSwipeIndicatorRunnable)

        if (swipeIndicatorView != null && swipeIndicatorIsLeft != isLeft) {
            dismissSwipeIndicator()
        }

        val indicator = swipeIndicatorView ?: run {
            val icon = ImageView(this).apply {
                setColorFilter(Color.WHITE)
            }
            val label = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
            }
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_player_popup)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                gravity = Gravity.CENTER
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = dp(6) })
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or (if (isLeft) Gravity.START else Gravity.END)
                val margin = dp(24)
                if (isLeft) marginStart = margin else marginEnd = margin
            }
            controlsContainer.addView(view, lp)
            swipeIndicatorView = view
            swipeIndicatorIsLeft = isLeft
            view
        }

        (indicator.getChildAt(0) as ImageView).setImageResource(iconRes)
        (indicator.getChildAt(1) as TextView).text = "${(level * 100).roundToInt()}%"
    }

    private fun dismissSwipeIndicator() {
        swipeIndicatorView?.let { controlsContainer.removeView(it) }
        swipeIndicatorView = null
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

    private fun View.containsRaw(e: MotionEvent): Boolean {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return e.rawX >= loc[0] && e.rawX <= loc[0] + width &&
               e.rawY >= loc[1] && e.rawY <= loc[1] + height
    }

    /** Last index where startMs <= ms, or -1. */
    private fun List<com.lb.asupplayer.subtitle.SubtitleEntry>.lastIdxAtOrBefore(ms: Long): Int {
        var lo = 0; var hi = size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].startMs <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** First index where startMs > ms, or -1. */
    private fun List<com.lb.asupplayer.subtitle.SubtitleEntry>.firstIdxAfter(ms: Long): Int {
        var lo = 0; var hi = size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].startMs > ms) { found = mid; hi = mid - 1 } else lo = mid + 1
        }
        return found
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

    private sealed class SubtitleTapAction {
        object None : SubtitleTapAction()
        object Copy : SubtitleTapAction()
        object ShowMenu : SubtitleTapAction()
        data class ProcessText(val packageName: String, val activityName: String) : SubtitleTapAction()

        fun toPreferenceValue(): String = when (this) {
            is None -> ACTION_VALUE_NONE
            is Copy -> ACTION_VALUE_COPY
            is ShowMenu -> ACTION_VALUE_MENU
            is ProcessText -> "$ACTION_VALUE_PROCESS_PREFIX$packageName/$activityName"
        }
    }

    private data class ProcessTextTarget(val packageName: String, val activityName: String, val label: String)

    // ── Language ──────────────────────────────────────────────────────────────

    private fun languageOptions() = listOf(
        LanguageOption(null, R.string.language_system),
        LanguageOption(LANGUAGE_TAG_ENGLISH, R.string.language_english),
        LanguageOption(LANGUAGE_TAG_RUSSIAN, R.string.language_russian),
    )

    private fun currentLanguageTag(): String? {
        return getSharedPreferences(LANGUAGE_PREFS, MODE_PRIVATE).getString(PREF_APP_LANGUAGE_TAG, null)
    }

    @Suppress("DEPRECATION")
    private fun setAppLanguage(languageTag: String?) {
        getSharedPreferences(LANGUAGE_PREFS, MODE_PRIVATE).edit {
            if (languageTag == null) remove(PREF_APP_LANGUAGE_TAG) else putString(PREF_APP_LANGUAGE_TAG, languageTag)
        }
        val locale = if (languageTag.isNullOrEmpty()) Locale.getDefault()
                     else Locale.forLanguageTag(languageTag)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        showSettingsPopup(SettingsPage.LANGUAGE)
    }

    private fun Context.withAppLocale(languageTag: String?): Context {
        if (languageTag.isNullOrEmpty()) return this
        val config = Configuration(resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(languageTag))
        return createConfigurationContext(config)
    }

    // ── Feedback ──────────────────────────────────────────────────────────────

    private fun openFeedbackEmail() {
        val subject = Uri.encode(getString(R.string.feedback_email_subject))
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$FEEDBACK_EMAIL?subject=$subject")
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.feedback_email_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private fun showHelpScreen() {
        dismissMenuOverlay()
        dismissHelpScreen()
        val root = findViewById<FrameLayout>(R.id.root)
        val view = buildHelpScreen()
        helpScreenView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.requestFocus()
    }

    private fun dismissHelpScreen() {
        helpScreenView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        helpScreenView = null
    }

    private fun buildHelpScreen(): View {
        val hPad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissHelpScreen(); true
                } else false
            }
        }

        // Header
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(hPad, dp(14), dp(8), dp(14))
                addView(
                    TextView(context).apply {
                        setText(R.string.help_title)
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    ImageButton(context).apply {
                        setImageResource(R.drawable.ic_close)
                        setColorFilter(Color.WHITE)
                        background = null
                        contentDescription = getString(R.string.help_close)
                        setOnClickListener { dismissHelpScreen() }
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Scrollable content
        val items = listOf(
            HelpItem(R.drawable.ic_open_video,         R.string.help_open_video_title,      R.string.help_open_video_body),
            HelpItem(R.drawable.ic_playback_controls,  R.string.help_playback_title,         R.string.help_playback_body),
            HelpItem(R.drawable.ic_subtitle_visibility,R.string.help_subtitle_tracks_title,  R.string.help_subtitle_tracks_body),
            HelpItem(R.drawable.ic_subtitle_tap,       R.string.help_subtitle_tap_title,     R.string.help_subtitle_tap_body),
            HelpItem(R.drawable.ic_gestures,           R.string.help_gestures_title,         R.string.help_gestures_body),
        )
        val itemList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad, 0, hPad, dp(20))
            items.forEach { item -> addView(buildHelpItem(item)) }
        }
        root.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                addView(itemList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun buildHelpItem(item: HelpItem): View {
        val iconSize = dp(24)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            addView(
                ImageView(context).apply {
                    setImageResource(item.iconResId)
                    setColorFilter(Color.WHITE)
                },
                LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(14); topMargin = dp(2) },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        setText(item.titleResId)
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    })
                    addView(TextView(context).apply {
                        setText(item.bodyResId)
                        setTextColor(Color.argb(190, 255, 255, 255))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setLineSpacing(0f, 1.1f)
                    })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

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
        const val SUBTITLE_POS_STEP_PERCENT = 5
        const val MAX_SUBTITLE_POS_PERCENT = 95
        const val BASE_SUBTITLE_SP = 18f
        const val EXTERNAL_TRACK_ID = Int.MAX_VALUE
        const val SUBTITLE_ACTION_PREFS = "subtitle_action_prefs"
        const val PREF_SINGLE_TAP_ACTION = "single_tap_action"
        const val PREF_DOUBLE_TAP_ACTION = "double_tap_action"
        const val PREF_LONG_PRESS_ACTION = "long_press_action"
        const val ACTION_VALUE_NONE = "none"
        const val ACTION_VALUE_COPY = "copy"
        const val ACTION_VALUE_MENU = "menu"
        const val ACTION_VALUE_PROCESS_PREFIX = "process:"
        const val STEPPER_TAG_SIZE = "stepper_size"
        const val STEPPER_TAG_POS  = "stepper_pos"
        const val LANGUAGE_PREFS = "language_prefs"
        const val PREF_APP_LANGUAGE_TAG = "app_language_tag"
        const val LANGUAGE_TAG_ENGLISH = "en"
        const val LANGUAGE_TAG_RUSSIAN = "ru"
        const val FEEDBACK_EMAIL = "balikhin.lb@gmail.com"
        const val PHRASE_REPLAY_THRESHOLD_MS = 1_500L
        const val PHRASE_SEEK_FALLBACK_MS = 10_000L
        const val PHRASE_SEEK_SUPPRESS_MS = 600L
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
