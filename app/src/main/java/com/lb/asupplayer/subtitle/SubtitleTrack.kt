package com.lb.asupplayer.subtitle

data class SubtitleTrack(
    val id: Int,
    val name: String,
    val entries: List<SubtitleEntry>,
) {
    fun getActiveEntry(timeMs: Long): SubtitleEntry? {
        if (entries.isEmpty()) return null
        var lo = 0
        var hi = entries.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].startMs <= timeMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        val candidate = entries[found]
        return if (timeMs < candidate.endMs) candidate else null
    }
}
