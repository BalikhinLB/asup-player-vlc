package com.lb.asupplayer.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleTextTest {
    @Test
    fun preservesAssItalicAndRemovesOtherOverrideTags() {
        assertEquals(
            "<i>Top line\nSecond line</i>",
            SubtitleText.normalize("{\\an8}{\\i1}Top line\\NSecond line{\\i0}"),
        )
    }

    @Test
    fun preservesCombinedBoldAndItalicStyles() {
        assertEquals(
            "<i><b>Both</b></i><b> Bold</b> Plain",
            SubtitleText.normalize("{\\i1\\b1}Both{\\i0} Bold{\\b0} Plain"),
        )
    }

    @Test
    fun treatsNegativeAssStyleValuesAsEnabled() {
        assertEquals(
            "<i><b>Styled</b></i>",
            SubtitleText.normalize("{\\i-1\\b700}Styled"),
        )
    }

    @Test
    fun preservesExistingHtmlStyles() {
        assertEquals(
            "<i>Italic</i> and <b>bold</b>",
            SubtitleText.normalize("<i>Italic</i> and <b>bold</b>"),
        )
    }

    @Test
    fun keepsOrdinaryBraces() {
        assertEquals("Use {braces}", SubtitleText.normalize("Use {braces}"))
    }

    @Test
    fun convertsAssHardSpaces() {
        assertEquals("No\u00a0break", SubtitleText.normalize("No\\hbreak"))
    }
}
