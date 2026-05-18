package com.k3i.stickerbook.ar

/**
 * Apply a 3x3 row-major homography matrix to a flat float array of 2D vertices.
 * Vertices are [v0x, v0y, v1x, v1y, ...]. The same layout is returned, with
 * perspective division (w = h20*x + h21*y + h22) applied.
 */
object HomographyMath {

    fun transformVerts(verts: FloatArray, h: DoubleArray): FloatArray {
        require(verts.size % 2 == 0) { "verts must have even length" }
        require(h.size == 9) { "homography must be 9 doubles" }
        val out = FloatArray(verts.size)
        var i = 0
        while (i < verts.size) {
            val x = verts[i].toDouble()
            val y = verts[i + 1].toDouble()
            val u = h[0] * x + h[1] * y + h[2]
            val v = h[3] * x + h[4] * y + h[5]
            val w = h[6] * x + h[7] * y + h[8]
            out[i] = (u / w).toFloat()
            out[i + 1] = (v / w).toFloat()
            i += 2
        }
        return out
    }
}
