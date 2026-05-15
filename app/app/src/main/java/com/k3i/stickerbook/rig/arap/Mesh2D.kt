package com.k3i.stickerbook.rig.arap

/**
 * 2D triangle mesh.
 *
 * vertices: row-major xy pairs, size = 2 * (gridWidth+1) * (gridHeight+1).
 *   index of vertex at grid (x, y) = y * (gridWidth+1) + x
 *
 * triangles: vertex-id triples, size = 3 * 2 * gridWidth * gridHeight.
 *   Each grid cell becomes two triangles.
 */
data class Mesh2D(
    val vertices: FloatArray,
    val triangles: IntArray,
    val gridWidth: Int,
    val gridHeight: Int,
) {
    val vertexCount: Int get() = vertices.size / 2
    val triangleCount: Int get() = triangles.size / 3
}
