package com.lb.asupplayer.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.util.Locale

private const val TAG = "MkvSubtitles"
private const val DEFAULT_DURATION_MS = 3_000L

// Matroska EBML element IDs
private const val EBML          = 0x1A45DFA3L
private const val SEGMENT       = 0x18538067L
private const val TRACKS        = 0x1654AE6BL
private const val TRACK_ENTRY   = 0xAEL
private const val TRACK_NUMBER  = 0xD7L
private const val TRACK_TYPE    = 0x83L
private const val CODEC_ID      = 0x86L
private const val TRACK_NAME    = 0x536EL
private const val LANGUAGE      = 0x22B59CL
private const val LANGUAGE_IETF = 0x22B59DL // BCP-47 (newer Matroska)
private const val CLUSTER       = 0x1F43B675L
private const val CLUSTER_TS    = 0xE7L
private const val SIMPLE_BLOCK  = 0xA3L
private const val BLOCK_GROUP   = 0xA0L
private const val BLOCK         = 0xA1L
private const val TYPE_SUBTITLE = 0x11

// ISO 639-2 (bibliographic + terminological) → ISO 639-1
private val ISO639_2_TO_1 = mapOf(
    "afr" to "af", "alb" to "sq", "sqi" to "sq", "ara" to "ar",
    "arm" to "hy", "hye" to "hy", "bul" to "bg", "cat" to "ca",
    "chi" to "zh", "zho" to "zh", "hrv" to "hr", "ces" to "cs", "cze" to "cs",
    "dan" to "da", "dut" to "nl", "nld" to "nl", "eng" to "en",
    "est" to "et", "fin" to "fi", "fra" to "fr", "fre" to "fr",
    "geo" to "ka", "kat" to "ka", "deu" to "de", "ger" to "de",
    "ell" to "el", "gre" to "el", "heb" to "he", "hun" to "hu",
    "ice" to "is", "isl" to "is", "ind" to "id", "ita" to "it",
    "jpn" to "ja", "kaz" to "kk", "kor" to "ko", "lav" to "lv",
    "lit" to "lt", "mac" to "mk", "mkd" to "mk", "may" to "ms", "msa" to "ms",
    "nob" to "nb", "nor" to "no", "per" to "fa", "fas" to "fa",
    "pol" to "pl", "por" to "pt", "rum" to "ro", "ron" to "ro",
    "rus" to "ru", "srp" to "sr", "slo" to "sk", "slk" to "sk",
    "slv" to "sl", "spa" to "es", "swe" to "sv", "tha" to "th",
    "tur" to "tr", "ukr" to "uk", "vie" to "vi",
)

private fun codeToDisplayName(code: String): String {
    if (code.isBlank() || code == "und") return ""
    val iso1 = when (code.length) {
        2    -> code
        else -> ISO639_2_TO_1[code.lowercase()] ?: return code
    }
    val locale = Locale.forLanguageTag(iso1)
    val display = locale.getDisplayLanguage(Locale.getDefault())
    return if (display != iso1) display.replaceFirstChar { it.titlecase() } else code
}

class MkvSubtitleExtractor {
    fun extract(context: Context, uri: Uri): List<SubtitleTrack> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                MatroskaParser(raw.buffered(65_536)).parse()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "MKV subtitle extraction failed: ${e.message}")
            emptyList()
        }
    }
}

private class MatroskaParser(private val input: InputStream) {
    private var pos = 0L

    private data class TrackInfo(val number: Long, val name: String)
    private val subTracks = mutableListOf<TrackInfo>()
    private val rawBlocks = mutableMapOf<Long, MutableList<Pair<Long, String>>>()

    fun parse(): List<SubtitleTrack> {
        val (id1, sz1) = elem() ?: return emptyList()
        if (id1 != EBML) return emptyList()
        skip(sz1)

        val (id2, sz2) = elem() ?: return emptyList()
        if (id2 != SEGMENT) return emptyList()

        val segEnd = if (sz2 == Long.MAX_VALUE) Long.MAX_VALUE else pos + sz2
        while (pos < segEnd) {
            val (id, sz) = elem() ?: break
            when (id) {
                TRACKS  -> parseTracks(sz)
                CLUSTER -> if (subTracks.isNotEmpty()) parseCluster(sz) else skip(sz)
                else    -> skip(sz)
            }
        }
        return buildResult()
    }

    private fun parseTracks(sz: Long) {
        val end = pos + sz
        while (pos < end) {
            val (id, esz) = elem() ?: break
            if (id == TRACK_ENTRY) parseTrackEntry(esz) else skip(esz)
        }
    }

    private fun parseTrackEntry(sz: Long) {
        val end = pos + sz
        var num = -1L; var type = -1; var codec = ""
        var name = ""; var lang = ""; var langIetf = ""
        while (pos < end) {
            val (id, esz) = elem() ?: break
            when (id) {
                TRACK_NUMBER  -> num = readUint(esz)
                TRACK_TYPE    -> type = readUint(esz).toInt()
                CODEC_ID      -> codec = readStr(esz)
                TRACK_NAME    -> name = readStr(esz)
                LANGUAGE      -> lang = readStr(esz)
                LANGUAGE_IETF -> langIetf = readStr(esz)
                else          -> skip(esz)
            }
        }
        if (type == TYPE_SUBTITLE && isTextCodec(codec)) {
            val dispName = name.takeIf { it.isNotBlank() }
                ?: codeToDisplayName(langIetf).takeIf { it.isNotBlank() }
                ?: codeToDisplayName(lang).takeIf { it.isNotBlank() }
                ?: "Track ${subTracks.size + 1}"
            Log.d(TAG, "Subtitle track: number=$num name=$dispName codec=$codec lang=$lang ietf=$langIetf")
            subTracks += TrackInfo(num, dispName)
            rawBlocks[num] = mutableListOf()
        }
    }

    private fun parseCluster(sz: Long) {
        val end = if (sz == Long.MAX_VALUE) Long.MAX_VALUE else pos + sz
        var ts = 0L
        while (pos < end) {
            val (id, esz) = elem() ?: break
            when (id) {
                CLUSTER_TS   -> ts = readUint(esz)
                SIMPLE_BLOCK -> parseSimpleBlock(esz, ts)
                BLOCK_GROUP  -> parseBlockGroup(esz, ts)
                else         -> skip(esz)
            }
        }
    }

    private fun parseSimpleBlock(sz: Long, clusterTs: Long) {
        val before = pos
        val trackNum = readVint()
        val timeRel  = readInt16()
        skip(1L) // flags byte
        val headerSz = pos - before
        putText(trackNum, clusterTs + timeRel, sz - headerSz)
    }

    private fun parseBlockGroup(sz: Long, clusterTs: Long) {
        val end = pos + sz
        var trackNum = -1L; var startMs = 0L; var text = ""; var hasBlock = false
        while (pos < end) {
            val (id, esz) = elem() ?: break
            if (id == BLOCK) {
                val before = pos
                trackNum = readVint()
                startMs  = clusterTs + readInt16()
                skip(1L) // flags byte
                val headerSz = pos - before
                val raw = rawBlocks[trackNum]
                if (raw != null) text = readStr(esz - headerSz) else skip(esz - headerSz)
                hasBlock = true
            } else skip(esz)
        }
        if (hasBlock && text.isNotEmpty()) rawBlocks[trackNum]?.add(startMs to text)
    }

    private fun putText(trackNum: Long, startMs: Long, dataSz: Long) {
        val raw = rawBlocks[trackNum]
        if (raw != null) {
            val text = readStr(dataSz).trim()
            if (text.isNotEmpty()) raw.add(startMs to text)
        } else skip(dataSz)
    }

    private fun buildResult(): List<SubtitleTrack> {
        val result = mutableListOf<SubtitleTrack>()
        var id = 0
        for ((number, name) in subTracks) {
            val raw = rawBlocks[number]?.sortedBy { it.first } ?: continue
            val entries = raw.mapIndexed { i, (startMs, text) ->
                val endMs = raw.getOrNull(i + 1)?.first ?: (startMs + DEFAULT_DURATION_MS)
                SubtitleEntry(startMs, endMs, text)
            }
            Log.d(TAG, "Track '$name': ${entries.size} entries")
            if (entries.isNotEmpty()) result += SubtitleTrack(id++, name, entries)
        }
        return result
    }

    // EBML primitives

    private fun elem(): Pair<Long, Long>? {
        val id = readId() ?: return null
        val sz = readSize() ?: return null
        return id to sz
    }

    /** Read EBML element ID — width marker is included in the value. */
    private fun readId(): Long? {
        val b0 = read() ?: return null
        if (b0 == 0) return null
        val extra = Integer.numberOfLeadingZeros(b0) - 24
        var v = b0.toLong()
        repeat(extra) { v = (v shl 8) or ((read() ?: return null).toLong()) }
        return v
    }

    /** Read EBML VINT size — strips width marker. Returns Long.MAX_VALUE for unknown size. */
    private fun readSize(): Long? {
        val b0 = read() ?: return null
        if (b0 == 0) return null
        val extra = Integer.numberOfLeadingZeros(b0) - 24
        val mask = 0xFF ushr (extra + 1)
        var v = (b0 and mask).toLong()
        repeat(extra) { v = (v shl 8) or ((read() ?: return null).toLong()) }
        val allOnes = (1L shl ((extra + 1) * 7)) - 1
        return if (v == allOnes) Long.MAX_VALUE else v
    }

    /** Read block track number (VINT — strip width marker). */
    private fun readVint(): Long {
        val b0 = read() ?: return 0
        val extra = Integer.numberOfLeadingZeros(b0) - 24
        val mask = 0xFF ushr (extra + 1)
        var v = (b0 and mask).toLong()
        repeat(extra) { v = (v shl 8) or ((read() ?: 0).toLong()) }
        return v
    }

    private fun readUint(sz: Long): Long {
        var v = 0L
        repeat(sz.toInt()) { v = (v shl 8) or ((read() ?: 0).toLong()) }
        return v
    }

    private fun readInt16(): Long {
        val hi = (read() ?: 0).toLong()
        val lo = (read() ?: 0).toLong() and 0xFF
        val raw = (hi shl 8) or lo
        return if (raw > 0x7FFF) raw - 0x10000L else raw
    }

    private fun readStr(sz: Long): String {
        val cap = minOf(sz, 65_536L).toInt()
        val buf = ByteArray(cap)
        var off = 0
        while (off < cap) {
            val n = input.read(buf, off, cap - off)
            if (n < 0) break
            off += n
        }
        pos += off
        if (sz > cap) skip(sz - cap)
        return String(buf, 0, off, Charsets.UTF_8).trimEnd(' ').trim()
    }

    private fun skip(sz: Long) {
        if (sz <= 0L || sz == Long.MAX_VALUE) return
        var rem = sz
        while (rem > 0) {
            val n = input.skip(rem)
            if (n <= 0) break
            rem -= n
        }
        pos += sz - rem
    }

    private fun read(): Int? {
        val b = input.read()
        if (b < 0) return null
        pos++
        return b
    }

    private fun isTextCodec(codec: String) =
        codec == "S_TEXT/UTF8" || codec == "S_TEXT/ASS" || codec == "S_TEXT/SSA"
}
