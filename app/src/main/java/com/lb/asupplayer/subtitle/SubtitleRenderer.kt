package com.lb.asupplayer.subtitle

import android.widget.TextView
import androidx.core.view.isVisible

class SubtitleRenderer(private val view: TextView) {

    var activeTrack: SubtitleTrack? = null

    fun onTimeChanged(timeMs: Long) {
        val entry = activeTrack?.getActiveEntry(timeMs)
        if (entry != null) {
            view.text = entry.text
            view.isVisible = true
        } else {
            view.isVisible = false
        }
    }

    fun reset() {
        activeTrack = null
        view.isVisible = false
    }
}
