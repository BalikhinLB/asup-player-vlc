package com.lb.asupplayer.subtitle

import android.text.Html
import android.widget.TextView
import androidx.core.view.isVisible

class SubtitleRenderer(private val view: TextView) {

    var activeTrack: SubtitleTrack? = null

    fun onTimeChanged(timeMs: Long) {
        val entry = activeTrack?.getActiveEntry(timeMs)
        if (entry != null) {
            view.text = Html.fromHtml(entry.text.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY)
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
