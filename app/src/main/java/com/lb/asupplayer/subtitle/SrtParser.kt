package com.lb.asupplayer.subtitle

import java.io.InputStream

object SrtParser {

    private val TIMING_RE = Regex(
        """(\d+):(\d+):(\d+)[,.](\d{1,3})\s*-->\s*(\d+):(\d+):(\d+)[,.](\d{1,3})""",
    )
    private val TAG_RE = Regex("""<[^>]+>|\{[^}]+\}""")

    fun parse(stream: InputStream): List<SubtitleEntry> =
        parse(stream.bufferedReader(Charsets.UTF_8).readText())

    fun parse(raw: String): List<SubtitleEntry> {
        val text = raw
            .trimStart('﻿')
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val entries = mutableListOf<SubtitleEntry>()

        for (block in text.split(Regex("""\n{2,}"""))) {
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.size < 2) continue

            val timingIdx = if (lines[0].trim().toIntOrNull() != null) 1 else 0
            if (timingIdx >= lines.size) continue

            val match = TIMING_RE.find(lines[timingIdx]) ?: continue
            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured

            val startMs = toMs(h1, m1, s1, ms1)
            val endMs = toMs(h2, m2, s2, ms2)
            if (startMs >= endMs) continue

            val body = lines.drop(timingIdx + 1).joinToString("\n")
            val text = TAG_RE.replace(body, "").trim()
            if (text.isEmpty()) continue

            entries.add(SubtitleEntry(startMs, endMs, text))
        }

        return entries.sortedBy { it.startMs }
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        val msNorm = ms.take(3).padEnd(3, '0').toLong()
        return h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + msNorm
    }
}
