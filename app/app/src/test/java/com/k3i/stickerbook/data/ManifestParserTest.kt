package com.k3i.stickerbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestParserTest {

    @Test
    fun parses_minimal_manifest() {
        val json = """
            {
              "format_version": 1,
              "generated_at": "2026-05-14T10:00:00",
              "stickers": [
                {
                  "id": "s001",
                  "name": "사자",
                  "motion": "dance_1",
                  "duration_ms": 2000,
                  "fps": 30,
                  "frame_count": 60,
                  "width": 512,
                  "height": 512,
                  "frames_dir": "stickers/s001/frames",
                  "gif_path": "stickers/s001/animation.gif",
                  "texture_path": "stickers/s001/texture.png",
                  "source_path": "stickers/s001/source.png"
                }
              ]
            }
        """.trimIndent()

        val m = ManifestParser.parse(json)

        assertEquals(1, m.formatVersion)
        assertEquals(1, m.stickers.size)
        assertEquals("사자", m.stickers[0].name)
        assertEquals(60, m.stickers[0].frameCount)
    }

    @Test
    fun rejects_unknown_format_version() {
        val json = """{"format_version": 99, "generated_at": "x", "stickers": []}"""
        assertThrows(IllegalStateException::class.java) { ManifestParser.parse(json) }
    }
}
