package com.lb.asupplayer

import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.IOException
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
    private var currentVideoDescriptor: AssetFileDescriptor? = null

    private var currentVideoUri: Uri? = null
    private var restorePositionMs: Long = 0L

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

        subtitlesSurfaceView.setZOrderMediaOverlay(true)
        subtitlesSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        attachPlayerViews()

        openVideoButton.setOnClickListener {
            openDocument.launch(arrayOf("video/*"))
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
            media.setHWDecoderEnabled(true, false)
            mediaPlayer.media = media
            media.release()
            attachPlayerViews()
            updateVideoOutputSize()
            mediaPlayer.play()

            if (startPositionMs > 0L) {
                videoSurfaceView.post {
                    mediaPlayer.time = startPositionMs
                }
            }
        } catch (_: IOException) {
            currentVideoUri = null
            openVideoButton.visibility = View.VISIBLE
            Toast.makeText(this, R.string.video_open_error, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            currentVideoUri = null
            openVideoButton.visibility = View.VISIBLE
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

    private fun Bundle.getVideoUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(KEY_VIDEO_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(KEY_VIDEO_URI)
        }

    private companion object {
        const val KEY_VIDEO_URI = "video_uri"
        const val KEY_POSITION_MS = "position_ms"
    }
}
