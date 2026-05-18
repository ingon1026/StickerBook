package com.k3i.stickerbook.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ArStickerMathTest {

    @Test
    fun `trapezoid vertices anchor at bottom center with top wider than bottom`() {
        val verts = ArStickerMath.trapezoidVertices(
            anchorX = 100f, anchorY = 200f,
            width = 60f, height = 80f,
            bottomNarrowFactor = 0.85f,
        )
        assertEquals(8, verts.size)
        // top width 60 → half=30. top y = 200-80 = 120
        assertEquals(100f - 30f, verts[0], 0.01f)
        assertEquals(200f - 80f, verts[1], 0.01f)
        assertEquals(100f + 30f, verts[2], 0.01f)
        assertEquals(200f - 80f, verts[3], 0.01f)
        // bottom width 60*0.85=51 → half=25.5
        assertEquals(100f - 25.5f, verts[4], 0.01f)
        assertEquals(200f, verts[5], 0.01f)
        assertEquals(100f + 25.5f, verts[6], 0.01f)
        assertEquals(200f, verts[7], 0.01f)
    }

    @Test
    fun `frame index wraps around frameCount`() {
        assertEquals(0, ArStickerMath.frameAt(tickIndex = 0, frameCount = 30))
        assertEquals(15, ArStickerMath.frameAt(tickIndex = 15, frameCount = 30))
        assertEquals(0, ArStickerMath.frameAt(tickIndex = 30, frameCount = 30))
        assertEquals(1, ArStickerMath.frameAt(tickIndex = 31, frameCount = 30))
        assertEquals(29, ArStickerMath.frameAt(tickIndex = 59, frameCount = 30))
    }

    @Test
    fun `shadow ellipse below anchor with given width and height`() {
        val rect = ArStickerMath.shadowRect(
            anchorX = 100f, anchorY = 200f,
            stickerWidth = 60f,
            shadowWidthRatio = 0.7f,
            shadowHeight = 6f,
        )
        // shadow width = 60 * 0.7 = 42, half = 21
        assertEquals(100f - 21f, rect.left, 0.01f)
        assertEquals(200f, rect.top, 0.01f)
        assertEquals(100f + 21f, rect.right, 0.01f)
        assertEquals(200f + 6f, rect.bottom, 0.01f)
    }
}
