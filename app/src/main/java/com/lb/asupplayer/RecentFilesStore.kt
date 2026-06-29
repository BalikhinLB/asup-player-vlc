package com.lb.asupplayer

import android.content.Context
import android.net.Uri
import com.lb.asupplayer.subtitle.SubtitleEntry
import com.lb.asupplayer.subtitle.SubtitleTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecentFile(
    val uri: Uri,
    val displayName: String,
    val lastPositionMs: Long,
    val activeSubtitleTrackId: Int = -1,      // -1 = subtitles off
    val audioTrackId: Int = AUDIO_NOT_SET,     // AUDIO_NOT_SET = don't restore
    val subtitleSizePercent: Int = DEFAULT_SUB_SIZE,
    val subtitlePositionPercent: Int = DEFAULT_SUB_POS,
    val externalSubtitleUri: Uri? = null,
) {
    companion object {
        const val AUDIO_NOT_SET = Int.MIN_VALUE
        const val DEFAULT_SUB_SIZE = 100
        const val DEFAULT_SUB_POS = 0  // 0 = bottom
    }
}

class RecentFilesStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val subtitlesDir = File(context.filesDir, "subtitle_cache").also { it.mkdirs() }

    fun getAll(): List<RecentFile> = uriList().mapNotNull { uriStr ->
        val hash = hash(uriStr)
        val name = prefs.getString(keyName(hash), null) ?: return@mapNotNull null
        RecentFile(
            uri = Uri.parse(uriStr),
            displayName = name,
            lastPositionMs = prefs.getLong(keyPos(hash), 0L),
            activeSubtitleTrackId = prefs.getInt(keySubId(hash), -1),
            audioTrackId = prefs.getInt(keyAudId(hash), RecentFile.AUDIO_NOT_SET),
            subtitleSizePercent = prefs.getInt(keySubSz(hash), RecentFile.DEFAULT_SUB_SIZE),
            subtitlePositionPercent = prefs.getInt(keySubPos(hash), RecentFile.DEFAULT_SUB_POS),
            externalSubtitleUri = prefs.getString(keyExtSub(hash), null)?.let { Uri.parse(it) },
        )
    }

    fun save(
        uri: Uri,
        name: String,
        positionMs: Long,
        tracks: List<SubtitleTrack>,
        activeSubtitleTrackId: Int = -1,
        audioTrackId: Int = RecentFile.AUDIO_NOT_SET,
        subtitleSizePercent: Int = RecentFile.DEFAULT_SUB_SIZE,
        subtitlePositionPercent: Int = RecentFile.DEFAULT_SUB_POS,
        externalSubtitleUri: Uri? = null,
    ) {
        val uriStr = uri.toString()
        val hash = hash(uriStr)
        val list = uriList().toMutableList().also { it.remove(uriStr); it.add(0, uriStr) }
        if (list.size > MAX) pruneOldest(list.drop(MAX))
        prefs.edit()
            .putString(KEY_URIS, JSONArray(list.take(MAX)).toString())
            .putString(keyName(hash), name)
            .putLong(keyPos(hash), positionMs)
            .putInt(keySubId(hash), activeSubtitleTrackId)
            .putInt(keyAudId(hash), audioTrackId)
            .putInt(keySubSz(hash), subtitleSizePercent)
            .putInt(keySubPos(hash), subtitlePositionPercent)
            .apply { if (externalSubtitleUri != null) putString(keyExtSub(hash), externalSubtitleUri.toString()) else remove(keyExtSub(hash)) }
            .apply()
        saveSubtitles(hash, tracks)
    }

    fun updatePlaybackState(
        uri: Uri,
        positionMs: Long,
        activeSubtitleTrackId: Int,
        audioTrackId: Int,
        subtitleSizePercent: Int,
        subtitlePositionPercent: Int,
        externalSubtitleUri: Uri? = null,
    ) {
        val hash = hash(uri.toString())
        if (!prefs.contains(keyName(hash))) return
        prefs.edit()
            .putLong(keyPos(hash), positionMs)
            .putInt(keySubId(hash), activeSubtitleTrackId)
            .putInt(keyAudId(hash), audioTrackId)
            .putInt(keySubSz(hash), subtitleSizePercent)
            .putInt(keySubPos(hash), subtitlePositionPercent)
            .apply { if (externalSubtitleUri != null) putString(keyExtSub(hash), externalSubtitleUri.toString()) else remove(keyExtSub(hash)) }
            .apply()
    }

    fun moveToFront(uri: Uri) {
        val uriStr = uri.toString()
        val list = uriList().toMutableList()
        if (!list.contains(uriStr)) return
        list.remove(uriStr)
        list.add(0, uriStr)
        prefs.edit().putString(KEY_URIS, JSONArray(list).toString()).apply()
    }

    /**
     * Returns null if this URI has never been extracted (cache file absent).
     * Returns an empty list if it was extracted but had no subtitle tracks.
     */
    fun loadSubtitles(uri: Uri): List<SubtitleTrack>? {
        val file = subtitleFile(hash(uri.toString()))
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("tracks")
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val ea = t.getJSONArray("entries")
                val entries = (0 until ea.length()).map { j ->
                    val e = ea.getJSONArray(j)
                    SubtitleEntry(e.getLong(0), e.getLong(1), e.getString(2))
                }
                SubtitleTrack(t.getInt("id"), t.getString("name"), entries)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun uriList(): List<String> {
        val json = prefs.getString(KEY_URIS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSubtitles(hash: String, tracks: List<SubtitleTrack>) {
        try {
            val arr = JSONArray()
            for (track in tracks) {
                val ea = JSONArray()
                for (e in track.entries) {
                    ea.put(JSONArray().apply { put(e.startMs); put(e.endMs); put(e.text) })
                }
                arr.put(JSONObject().apply {
                    put("id", track.id)
                    put("name", track.name)
                    put("entries", ea)
                })
            }
            subtitleFile(hash).writeText(JSONObject().apply { put("tracks", arr) }.toString())
        } catch (_: Exception) {
        }
    }

    private fun pruneOldest(removed: List<String>) {
        val editor = prefs.edit()
        for (uriStr in removed) {
            val hash = hash(uriStr)
            editor.remove(keyName(hash))
                .remove(keyPos(hash))
                .remove(keySubId(hash))
                .remove(keyAudId(hash))
                .remove(keySubSz(hash))
                .remove(keySubPos(hash))
                .remove(keyExtSub(hash))
            subtitleFile(hash).delete()
            externalSubtitleFile(hash).delete()
        }
        editor.apply()
    }

    fun saveExternalSubtitle(videoUri: Uri, track: SubtitleTrack) {
        val hash = hash(videoUri.toString())
        if (!prefs.contains(keyName(hash))) return
        try {
            val ea = JSONArray()
            for (e in track.entries) {
                ea.put(JSONArray().apply { put(e.startMs); put(e.endMs); put(e.text) })
            }
            externalSubtitleFile(hash).writeText(
                JSONObject().apply {
                    put("id", track.id)
                    put("name", track.name)
                    put("entries", ea)
                }.toString()
            )
        } catch (_: Exception) {}
    }

    fun loadExternalSubtitle(videoUri: Uri): SubtitleTrack? {
        val file = externalSubtitleFile(hash(videoUri.toString()))
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val ea = obj.getJSONArray("entries")
            val entries = (0 until ea.length()).map { j ->
                val e = ea.getJSONArray(j)
                SubtitleEntry(e.getLong(0), e.getLong(1), e.getString(2))
            }
            SubtitleTrack(obj.getInt("id"), obj.getString("name"), entries)
        } catch (_: Exception) { null }
    }

    private fun subtitleFile(hash: String) = File(subtitlesDir, "$hash.json")
    private fun externalSubtitleFile(hash: String) = File(subtitlesDir, "ext_$hash.json")
    private fun hash(uriStr: String) = uriStr.hashCode().toString()
    private fun keyName(hash: String) = "name_$hash"
    private fun keyPos(hash: String) = "pos_$hash"
    private fun keySubId(hash: String) = "sub_id_$hash"
    private fun keyAudId(hash: String) = "aud_id_$hash"
    private fun keySubSz(hash: String) = "sub_sz_$hash"
    private fun keySubPos(hash: String) = "sub_pos_$hash"
    private fun keyExtSub(hash: String) = "ext_sub_$hash"

    companion object {
        private const val PREFS = "recent_files"
        private const val KEY_URIS = "uris"
        private const val MAX = 5
    }
}
