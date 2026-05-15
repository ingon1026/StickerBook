package com.k3i.stickerbook.rig.arap

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GridMeshBuilderTest {

    @Test
    fun `grid mesh vertex count matches gridW+1 times gridH+1`() {
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        val mesh = GridMeshBuilder.build(mask, gridWidth = 4, gridHeight = 5)
        assertEquals(4, mesh.gridWidth)
        assertEquals(5, mesh.gridHeight)
        assertEquals((4 + 1) * (5 + 1), mesh.vertexCount)
    }

    @Test
    fun `triangle count is 2 times gridW times gridH`() {
        val mask = Bitmap.createBitmap(60, 50, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        val mesh = GridMeshBuilder.build(mask, gridWidth = 4, gridHeight = 5)
        assertEquals(2 * 4 * 5, mesh.triangleCount)
    }

    @Test
    fun `vertices span bitmap area`() {
        val mask = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        val mesh = GridMeshBuilder.build(mask, gridWidth = 4, gridHeight = 3)
        assertEquals(0f, mesh.vertices[0])
        assertEquals(0f, mesh.vertices[1])
        val lastIdx = ((4 + 1) * (3 + 1) - 1) * 2
        assertEquals(80f, mesh.vertices[lastIdx])
        assertEquals(60f, mesh.vertices[lastIdx + 1])
    }

    @Test
    fun `vertices row-major ordering`() {
        val mask = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        val mesh = GridMeshBuilder.build(mask, gridWidth = 2, gridHeight = 3)
        assertEquals(20f, mesh.vertices[1 * 2])
        assertEquals(0f, mesh.vertices[1 * 2 + 1])
        assertEquals(0f, mesh.vertices[3 * 2])
        assertEquals(10f, mesh.vertices[3 * 2 + 1])
    }

    @Test
    fun `each grid cell produces two triangles with valid vertex ids`() {
        val mask = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        val mesh = GridMeshBuilder.build(mask, gridWidth = 2, gridHeight = 2)
        for (i in mesh.triangles.indices) {
            val id = mesh.triangles[i]
            assertTrue("triangle id $id out of range", id in 0 until mesh.vertexCount)
        }
        var tripleCount = 0
        for (i in 0 until mesh.triangleCount) {
            val a = mesh.triangles[i * 3]
            val b = mesh.triangles[i * 3 + 1]
            val c = mesh.triangles[i * 3 + 2]
            assertTrue("vertices should be different", a != b && b != c && a != c)
            tripleCount++
        }
        assertEquals(mesh.triangleCount.toLong(), tripleCount.toLong())
    }
}
