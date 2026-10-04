package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var surfaceAttached = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var videoOutputSuspended = false
    private var videoOutputNeedsReset = false

    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        MPVLib.create(context)

        /* set normal options (user-supplied config can override) */
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            MPVLib.setOptionString(opt, cacheDir)
        initOptions()

        MPVLib.init()

        /* set hardcoded options */
        postInitOptions()
        // could mess up VO init before surfaceCreated() is called
        MPVLib.setOptionString("force-window", "no")
        // need to idle at least once for playFile() logic to work
        MPVLib.setOptionString("idle", "once")

        holder.addCallback(this)
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable surface callbacks to avoid using uninitialized mpv state
        holder.removeCallback(this)

        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()

    protected abstract fun observeProperties()

    private var filePath: String? = null

    /**
     * Set the first file to be played once the player is ready.
     */
    fun playFile(filePath: String) {
        this.filePath = filePath
    }

    private var voInUse: String = "gpu"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    fun suspendVideoOutput() {
        videoOutputSuspended = true
        videoOutputNeedsReset = true
        // Release GPU/decoder output resources before Android reclaims them in the background.
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
    }

    fun resumeVideoOutput() {
        videoOutputSuspended = false
        restoreVideoOutput()
    }

    fun restoreVideoOutput() {
        if (videoOutputSuspended || !surfaceAttached || !holder.surface.isValid ||
            surfaceWidth <= 0 || surfaceHeight <= 0) return

        if (videoOutputNeedsReset) {
            // A valid Java Surface does not guarantee that the old native/EGL output survived.
            // Reset it once per background round trip, after the surface size is known.
            Log.w(TAG, "reconnecting surface after background")
            MPVLib.setPropertyString("vo", "null")
            MPVLib.detachSurface()
            MPVLib.attachSurface(holder.surface)
            videoOutputNeedsReset = false
        }

        Log.w(TAG, "restoring video output")
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setPropertyString("android-surface-size", "${surfaceWidth}x$surfaceHeight")
        MPVLib.setPropertyString("vo", voInUse)
    }

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val resizedWhilePaused = !videoOutputSuspended && surfaceAttached &&
            surfaceWidth > 0 && surfaceHeight > 0 &&
            (surfaceWidth != width || surfaceHeight != height) &&
            MPVLib.getPropertyBoolean("pause") == true

        // On pause, EGL can keep a buffer with the old geometry after a resize.
        // Recreate the video output so the paused frame uses the new surface size.
        // Do not toggle pause or advance playback to force a redraw.
        if (resizedWhilePaused) MPVLib.setPropertyString("vo", "null")
        surfaceWidth = width
        surfaceHeight = height
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        // surfaceCreated can run before a usable size is available, including on resume.
        restoreVideoOutput()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface")
        MPVLib.attachSurface(holder.surface)
        surfaceAttached = true
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        restoreVideoOutput()

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        surfaceAttached = false
        surfaceWidth = 0
        surfaceHeight = 0
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }

    companion object {
        private const val TAG = "mpv"
    }
}
