package com.lb.asupplayer.mpv

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_FLAG
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_NONE

class MPVView(context: Context, attrs: AttributeSet) : BaseMPVView(context, attrs) {

    override fun initOptions() {
        MPVLib.setOptionString("profile", "fast")
        setVo("gpu")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val refreshRate = ContextCompat.getDisplayOrDefault(context).mode.refreshRate
            MPVLib.setOptionString("display-fps-override", refreshRate.toString())
        }

        MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("tls-verify", "no")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")

        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
    }

    override fun postInitOptions() {
        MPVLib.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() {
        data class Prop(val name: String, val format: Int = MPV_FORMAT_NONE)
        val props = arrayOf(
            Prop("time-pos",        MPV_FORMAT_DOUBLE),
            Prop("duration",        MPV_FORMAT_DOUBLE),
            Prop("pause",           MPV_FORMAT_FLAG),
            Prop("paused-for-cache",MPV_FORMAT_FLAG),
            Prop("track-list",      MPV_FORMAT_NONE),
            Prop("eof-reached",     MPV_FORMAT_FLAG),
            Prop("idle-active",     MPV_FORMAT_FLAG),
        )
        for ((name, format) in props) MPVLib.observeProperty(name, format)
    }

    fun addObserver(o: MPVLib.EventObserver) = MPVLib.addObserver(o)
    fun removeObserver(o: MPVLib.EventObserver) = MPVLib.removeObserver(o)

    // ── Playback state ────────────────────────────────────────────────────────

    val timeMs: Long
        get() = ((MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong().coerceAtLeast(0L)

    val durationMs: Long
        get() = ((MPVLib.getPropertyDouble("duration") ?: 0.0) * 1000).toLong().coerceAtLeast(0L)

    val isPlaying: Boolean
        get() = MPVLib.getPropertyBoolean("pause") == false

    // ── Transport ─────────────────────────────────────────────────────────────

    fun play()  = MPVLib.setPropertyBoolean("pause", false)
    fun pause() = MPVLib.setPropertyBoolean("pause", true)
    fun stop()  = MPVLib.command(arrayOf("stop"))

    fun seekTo(ms: Long) {
        MPVLib.setPropertyDouble("time-pos", ms / 1000.0)
    }

    // ── Audio tracks ──────────────────────────────────────────────────────────

    data class AudioTrack(val id: Int, val name: String)

    var audioTrack: Int
        get() {
            val v = MPVLib.getPropertyString("aid")
            return v?.toIntOrNull() ?: -1
        }
        set(id) {
            if (id == -1) MPVLib.setPropertyString("aid", "no")
            else MPVLib.setPropertyInt("aid", id)
        }

    fun getAudioTracks(): List<AudioTrack> {
        val count = MPVLib.getPropertyInt("track-list/count") ?: return emptyList()
        val result = mutableListOf<AudioTrack>()
        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") != "audio") continue
            val id    = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            val lang  = MPVLib.getPropertyString("track-list/$i/lang")?.takeIf { it.isNotEmpty() }
            val title = MPVLib.getPropertyString("track-list/$i/title")?.takeIf { it.isNotEmpty() }
            val name  = when {
                lang != null && title != null -> "#$id $title ($lang)"
                lang != null                  -> "#$id $lang"
                title != null                 -> "#$id $title"
                else                          -> "#$id"
            }
            result += AudioTrack(id, name)
        }
        return result
    }
}
