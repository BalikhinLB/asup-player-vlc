package com.lb.asupplayer.subtitle

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MatroskaParserTest {
    @Test
    fun usesBlockDurationForSubtitleEndTime() {
        val trackEntry = element(
            0xAE,
            element(0xD7, byteArrayOf(1)) +
                element(0x83, byteArrayOf(0x11)) +
                element(0x86, "S_TEXT/UTF8".toByteArray()),
        )
        val tracks = element(0x1654AE6B, trackEntry)
        val info = element(0x1549A966, element(0x2AD7B1, uint(2_000_000)))

        val block = element(
            0xA1,
            byteArrayOf(0x81.toByte(), 0, 0, 0) + "Line".toByteArray(),
        )
        val blockGroup = element(
            0xA0,
            block + element(0x9B, uint(2_000)),
        )
        val cluster = element(0x1F43B675, element(0xE7, uint(1_000)) + blockGroup)
        val segment = element(0x18538067, info + tracks + cluster)
        val file = element(0x1A45DFA3, byteArrayOf()) + segment

        val entry = MatroskaParser(ByteArrayInputStream(file), debugLog = {}).parse()
            .single().entries.single()

        assertEquals(2_000L, entry.startMs)
        assertEquals(6_000L, entry.endMs)
        assertEquals("Line", entry.text)
    }

    private fun element(id: Long, content: ByteArray): ByteArray =
        idBytes(id) + byteArrayOf((0x80 or content.size).toByte()) + content

    private fun idBytes(id: Long): ByteArray {
        val width = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(width) { index ->
            (id shr ((width - index - 1) * 8)).toByte()
        }
    }

    private fun uint(value: Int): ByteArray {
        val width = when {
            value > 0xFFFFFF -> 4
            value > 0xFFFF -> 3
            value > 0xFF -> 2
            else -> 1
        }
        return ByteArray(width) { index ->
            (value shr ((width - index - 1) * 8)).toByte()
        }
    }
}
