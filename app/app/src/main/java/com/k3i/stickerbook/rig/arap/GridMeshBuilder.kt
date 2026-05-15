package com.k3i.stickerbook.rig.arap

import android.graphics.Bitmap

/**
 * Builds a regular grid mesh covering an entire bitmap. The mask is only used
 * to size the mesh (the bitmap's dimensions); cells outside the foreground are
 * still part of the mesh, but the character bitmap is already mask-applied, so
 * those cells render as transparent.
 *
 * vertex layout: row-major (gridWidth+1) * (gridHeight+1) vertices.
 *   vertex index at (x, y) = y * (gridWidth+1) + x
 *
 * triangle layout: each cell (x, y) emits two triangles:
 *   - upper:   (x, y), (x+1, y), (x, y+1)
 *   - lower:   (x+1, y), (x+1, y+1), (x, y+1)
 */
object GridMeshBuilder {

    fun build(bitmap: Bitmap, gridWidth: Int, gridHeight: Int): Mesh2D {
        require(gridWidth > 0 && gridHeight > 0) { "grid size must be positive" }
        val cols = gridWidth + 1
        val rows = gridHeight + 1
        val cellW = bitmap.width.toFloat() / gridWidth
        val cellH = bitmap.height.toFloat() / gridHeight

        val vertices = FloatArray(cols * rows * 2)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = (y * cols + x) * 2
                vertices[idx] = x * cellW
                vertices[idx + 1] = y * cellH
            }
        }

        val triangles = IntArray(gridWidth * gridHeight * 6)
        var t = 0
        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                val v00 = y * cols + x
                val v10 = y * cols + (x + 1)
                val v01 = (y + 1) * cols + x
                val v11 = (y + 1) * cols + (x + 1)
                triangles[t++] = v00; triangles[t++] = v10; triangles[t++] = v01
                triangles[t++] = v10; triangles[t++] = v11; triangles[t++] = v01
            }
        }
        return Mesh2D(vertices, triangles, gridWidth, gridHeight)
    }
}
