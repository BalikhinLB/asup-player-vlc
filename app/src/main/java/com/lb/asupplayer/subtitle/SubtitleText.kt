package com.lb.asupplayer.subtitle

object SubtitleText {
    private val ASS_OVERRIDE_RE = Regex("""\{\\[^}]*\}""")
    private val ASS_STYLE_RE = Regex("""\\([ib])(-?\d+)""", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String {
        var italic = false
        var bold = false

        val styled = buildString {
            var textStart = 0
            for (override in ASS_OVERRIDE_RE.findAll(text)) {
                append(text, textStart, override.range.first)

                var nextItalic = italic
                var nextBold = bold
                for (style in ASS_STYLE_RE.findAll(override.value)) {
                    val enabled = (style.groupValues[2].toIntOrNull() ?: 0) != 0
                    when (style.groupValues[1].lowercase()) {
                        "i" -> nextItalic = enabled
                        "b" -> nextBold = enabled
                    }
                }

                if (nextItalic != italic || nextBold != bold) {
                    appendStyleEnd(italic, bold)
                    appendStyleStart(nextItalic, nextBold)
                    italic = nextItalic
                    bold = nextBold
                }
                textStart = override.range.last + 1
            }
            append(text, textStart, text.length)
            appendStyleEnd(italic, bold)
        }

        return styled
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", "\u00a0")
            .trim()
    }

    private fun StringBuilder.appendStyleStart(italic: Boolean, bold: Boolean) {
        if (italic) append("<i>")
        if (bold) append("<b>")
    }

    private fun StringBuilder.appendStyleEnd(italic: Boolean, bold: Boolean) {
        if (bold) append("</b>")
        if (italic) append("</i>")
    }
}
