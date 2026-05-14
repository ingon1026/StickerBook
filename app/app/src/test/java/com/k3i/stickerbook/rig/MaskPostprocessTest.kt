package com.k3i.stickerbook.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskPostprocessTest {

    @Test
    fun filters_low_score_detections() {
        // 3 detections: scores 0.9, 0.4, 0.2 — threshold 0.5
        val dets = floatArrayOf(
            10f, 10f, 50f, 50f, 0.9f,
            20f, 20f, 60f, 60f, 0.4f,
            30f, 30f, 70f, 70f, 0.2f,
        )
        val masks = FloatArray(3 * 4 * 4) { 1.0f }
        val result = MaskPostprocess.decode(
            dets = dets,
            masks = masks,
            maskHeight = 4,
            maskWidth = 4,
            scoreThreshold = 0.5f,
        )
        assertEquals(1, result.size)
        assertEquals(0.9f, result[0].score, 0.0001f)
    }

    @Test
    fun preserves_bbox_coordinates() {
        val dets = floatArrayOf(15f, 25f, 105f, 205f, 0.95f)
        val masks = FloatArray(1 * 4 * 4) { 1.0f }
        val result = MaskPostprocess.decode(
            dets = dets, masks = masks,
            maskHeight = 4, maskWidth = 4,
            scoreThreshold = 0.5f,
        )
        val box = result[0].bbox
        assertEquals(15f, box.left, 0.0001f)
        assertEquals(25f, box.top, 0.0001f)
        assertEquals(105f, box.right, 0.0001f)
        assertEquals(205f, box.bottom, 0.0001f)
    }

    @Test
    fun returns_empty_when_no_detections() {
        val result = MaskPostprocess.decode(
            dets = floatArrayOf(),
            masks = floatArrayOf(),
            maskHeight = 0, maskWidth = 0,
            scoreThreshold = 0.5f,
        )
        assertTrue(result.isEmpty())
    }
}
