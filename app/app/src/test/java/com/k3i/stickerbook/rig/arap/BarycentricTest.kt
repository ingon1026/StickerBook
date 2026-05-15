package com.k3i.stickerbook.rig.arap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BarycentricTest {

    @Test
    fun `point inside triangle returns three valid weights`() {
        val tri = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)
        val result = Barycentric.compute(px = 1f, py = 1f,
            ax = tri[0], ay = tri[1], bx = tri[2], by = tri[3], cx = tri[4], cy = tri[5])
        val (u, v, w) = result!!
        assertEquals(1.0f, u + v + w, 0.001f)
        assertTrue(u > 0 && v > 0 && w > 0)
    }

    @Test
    fun `point at vertex A returns u=1 others=0`() {
        val result = Barycentric.compute(px = 0f, py = 0f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f)
        assertEquals(1.0f, result!!.first, 0.001f)
        assertEquals(0.0f, result.second, 0.001f)
        assertEquals(0.0f, result.third, 0.001f)
    }

    @Test
    fun `point outside triangle returns negative weight`() {
        val result = Barycentric.compute(px = -1f, py = -1f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f)
        val (u, v, w) = result!!
        assertTrue(u < 0 || v < 0 || w < 0)
    }

    @Test
    fun `pointInTriangle returns false for clearly outside point`() {
        assertFalse(Barycentric.pointInTriangle(
            px = -5f, py = -5f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f,
        ))
    }

    @Test
    fun `pointInTriangle returns true for clearly inside point`() {
        assertTrue(Barycentric.pointInTriangle(
            px = 2f, py = 2f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f,
        ))
    }
}
