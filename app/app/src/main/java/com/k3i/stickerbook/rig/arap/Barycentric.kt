package com.k3i.stickerbook.rig.arap

/**
 * Triangle helpers in pure Kotlin (no Android deps so tests can run on JVM).
 *
 * compute() returns barycentric coords (u, v, w) of point P relative to triangle (A, B, C).
 * P = u*A + v*B + w*C, and u + v + w = 1.
 *
 * From Christer Ericson "Real-Time Collision Detection".
 */
object Barycentric {

    /** Returns Triple(u, v, w) or null if triangle is degenerate. */
    fun compute(
        px: Float, py: Float,
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
    ): Triple<Float, Float, Float>? {
        val v0x = bx - ax; val v0y = by - ay
        val v1x = cx - ax; val v1y = cy - ay
        val v2x = px - ax; val v2y = py - ay
        val d00 = v0x * v0x + v0y * v0y
        val d01 = v0x * v1x + v0y * v1y
        val d11 = v1x * v1x + v1y * v1y
        val d20 = v2x * v0x + v2y * v0y
        val d21 = v2x * v1x + v2y * v1y
        val denom = d00 * d11 - d01 * d01
        if (denom == 0f) return null
        val v = (d11 * d20 - d01 * d21) / denom
        val w = (d00 * d21 - d01 * d20) / denom
        val u = 1f - v - w
        return Triple(u, v, w)
    }

    /** True iff point P lies inside triangle ABC (including edges). */
    fun pointInTriangle(
        px: Float, py: Float,
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
    ): Boolean {
        val bc = compute(px, py, ax, ay, bx, by, cx, cy) ?: return false
        val (u, v, w) = bc
        return u >= 0f && v >= 0f && w >= 0f && (u + v + w) <= 1.0001f
    }
}
