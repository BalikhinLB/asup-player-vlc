package com.lb.asupplayer.subtitle

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.FileDescriptor
import java.nio.ByteBuffer

class MkvSubtitleExtractor {

    fun extract(fd: FileDescriptor): List<SubtitleTrack> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(fd)
            findTracks(extractor)
        } catch (_: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun findTracks(extractor: MediaExtractor): List<SubtitleTrack> {
        val result = mutableListOf<SubtitleTrack>()
        var nextId = 0

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!isTextSubtitle(mime)) continue

            val name = format.getString(MediaFormat.KEY_LANGUAGE)
                ?.takeIf { it.isNotBlank() && it != "und" }
                ?: "Track ${nextId + 1}"

            val entries = readEntries(extractor, i)
            if (entries.isNotEmpty()) {
                result.add(SubtitleTrack(nextId++, name, entries))
            }
        }

        return result
    }

    private fun isTextSubtitle(mime: String): Boolean =
        mime.startsWith("text/") || mime == "application/x-subrip"

    private fun readEntries(extractor: MediaExtractor, trackIndex: Int): List<SubtitleEntry> {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val raw = mutableListOf<Pair<Long, String>>()
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)

        while (extractor.sampleTrackIndex >= 0) {
            val startUs = extractor.sampleTime
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)

            if (read > 0) {
                val bytes = ByteArray(read)
                buffer.rewind()
                buffer.get(bytes)
                val text = String(bytes, Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    raw.add(startUs / 1_000L to text)
                }
            }

            if (!extractor.advance()) break
        }

        extractor.unselectTrack(trackIndex)

        return raw.mapIndexed { i, (startMs, text) ->
            val endMs = raw.getOrNull(i + 1)?.first ?: (startMs + DEFAULT_DURATION_MS)
            SubtitleEntry(startMs, endMs, text)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_DURATION_MS = 3_000L
    }
}
