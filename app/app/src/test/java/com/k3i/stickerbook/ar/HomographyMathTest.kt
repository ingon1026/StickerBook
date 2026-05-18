package com.k3i.stickerbook.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class HomographyMathTest {

    @Test
    fun `identity homography returns vertices unchanged`() {
        val identity = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val verts = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f)
        val out = HomographyMath.transformVerts(verts, identity)
        for (i in verts.indices) {
            assertEquals(verts[i], out[i], 0.001f)
        }
    }

    @Test
    fun `translation homography shifts all vertices by tx and ty`() {
        val tx = 5.0; val ty = 7.0
        val h = doubleArrayOf(
            1.0, 0.0, tx,
            0.0, 1.0, ty,
            0.0, 0.0, 1.0,
        )
        val verts = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f)
        val out = HomographyMath.transformVerts(verts, h)
        for (i in 0 until 4) {
            assertEquals(verts[i * 2] + tx.toFloat(), out[i * 2], 0.001f)
            assertEquals(verts[i * 2 + 1] + ty.toFloat(), out[i * 2 + 1], 0.001f)
        }
    }

    @Test
    fun `scale homography multiplies coordinates`() {
        val h = doubleArrayOf(
            2.0, 0.0, 0.0,
            0.0, 3.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val verts = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f)
        val out = HomographyMath.transformVerts(verts, h)
        for (i in 0 until 4) {
            assertEquals(verts[i * 2] * 2f, out[i * 2], 0.001f)
            assertEquals(verts[i * 2 + 1] * 3f, out[i * 2 + 1], 0.001f)
        }
    }

    @Test
    fun `perspective division applied when w not 1`() {
        val h = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 2.0,
        )
        val verts = floatArrayOf(10f, 20f, 0f, 0f, 0f, 0f, 0f, 0f)
        val out = HomographyMath.transformVerts(verts, h)
        assertEquals(5f, out[0], 0.001f)
        assertEquals(10f, out[1], 0.001f)
    }
}
