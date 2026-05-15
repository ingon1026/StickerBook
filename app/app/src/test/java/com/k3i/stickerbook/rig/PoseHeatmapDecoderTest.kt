package com.k3i.stickerbook.rig

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseHeatmapDecoderTest {

    @Test
    fun `argmax at known position returns that coordinate`() {
        // heatmap [1, 64, 48] (1 keypoint for simplicity)
        val hm = FloatArray(64 * 48) { 0f }
        // peak at (x=10, y=20)
        hm[20 * 48 + 10] = 1.0f
        val result = PoseHeatmapDecoder.decode(hm, numKeypoints = 1, height = 64, width = 48)
        assertEquals(1, result.size)
        assertEquals(10f, result[0].x, 0.01f)
        assertEquals(20f, result[0].y, 0.01f)
        assertEquals(1.0f, result[0].score, 0.01f)
    }

    @Test
    fun `17 keypoint heatmap returns 17 results`() {
        // [17, 64, 48] flat = 17 * 64 * 48
        val totalSize = 17 * 64 * 48
        val hm = FloatArray(totalSize) { 0f }
        // keypoint 5 peak at (5, 5)
        hm[5 * (64 * 48) + 5 * 48 + 5] = 0.8f
        val result = PoseHeatmapDecoder.decode(hm, numKeypoints = 17, height = 64, width = 48)
        assertEquals(17, result.size)
        assertEquals(5f, result[5].x, 0.01f)
        assertEquals(5f, result[5].y, 0.01f)
        assertEquals(0.8f, result[5].score, 0.01f)
    }

    @Test
    fun `sub-pixel offset adjusts peak by quarter step toward second-highest neighbor`() {
        // Standard mmpose post-process: peak + 0.25 * sign(neighbor - opposite)
        val hm = FloatArray(64 * 48) { 0f }
        hm[20 * 48 + 10] = 1.0f
        hm[20 * 48 + 11] = 0.9f   // right neighbor higher → x shifts +0.25
        hm[20 * 48 + 9] = 0.5f
        hm[19 * 48 + 10] = 0.3f
        hm[21 * 48 + 10] = 0.7f   // down neighbor higher → y shifts +0.25
        val result = PoseHeatmapDecoder.decode(hm, numKeypoints = 1, height = 64, width = 48)
        assertEquals(10.25f, result[0].x, 0.01f)
        assertEquals(20.25f, result[0].y, 0.01f)
    }
}
