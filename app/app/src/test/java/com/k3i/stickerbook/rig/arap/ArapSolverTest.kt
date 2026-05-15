package com.k3i.stickerbook.rig.arap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArapSolverTest {

    private fun simpleQuadMesh(): Mesh2D {
        // 2×2 grid (gridW=1, gridH=1) → 4 vertices, 2 triangles
        // vertices: (0,0), (10,0), (0,10), (10,10)
        val vertices = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)
        val triangles = intArrayOf(0, 1, 2, 1, 3, 2)
        return Mesh2D(vertices, triangles, gridWidth = 1, gridHeight = 1)
    }

    @Test
    fun `solve with identity pins returns original vertices within tolerance`() {
        val mesh = simpleQuadMesh()
        // Two pins are required: a single pin leaves rotation+scale DOFs free on a 4-vertex
        // mesh (verified against scipy reference — 1-pin identity diverges significantly).
        val pins = floatArrayOf(0.5f, 0.5f, 9.5f, 9.5f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        val result = solver.solve(pins)
        for (i in mesh.vertices.indices) {
            assertEquals(mesh.vertices[i], result[i], 0.1f)
        }
    }

    @Test
    fun `translating a pin translates all vertices by the same delta`() {
        val mesh = simpleQuadMesh()
        // 4 corner pins
        val pins = floatArrayOf(0.5f, 0.5f, 9.5f, 0.5f, 0.5f, 9.5f, 9.5f, 9.5f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        // translate all pins by (+5, +3)
        val newPins = floatArrayOf(5.5f, 3.5f, 14.5f, 3.5f, 5.5f, 12.5f, 14.5f, 12.5f)
        val result = solver.solve(newPins)
        for (v in 0 until 4) {
            assertEquals(mesh.vertices[v * 2] + 5f, result[v * 2], 0.5f)
            assertEquals(mesh.vertices[v * 2 + 1] + 3f, result[v * 2 + 1], 0.5f)
        }
    }

    @Test
    fun `solve respects pin constraints`() {
        val mesh = simpleQuadMesh()
        // 4 corners pinned near vertices (slightly inside)
        val pins = floatArrayOf(0.5f, 0.5f, 9.5f, 0.5f, 0.5f, 9.5f, 9.5f, 9.5f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        // move top-right pin (index 1) from (9.5, 0.5) to (19.5, 0.5)
        val newPins = floatArrayOf(0.5f, 0.5f, 19.5f, 0.5f, 0.5f, 9.5f, 9.5f, 9.5f)
        val result = solver.solve(newPins)
        // top-right vertex (index 1) should be near (20, 0) — since pin near (19.5, 0.5)
        assertTrue("top-right x should move right, got ${result[1*2]}", result[1 * 2] > 15f)
        // top-left (0, 0) should stay near origin
        assertEquals(0f, result[0 * 2], 1.5f)
        assertEquals(0f, result[0 * 2 + 1], 1.5f)
    }

    @Test
    fun `pin outside mesh is masked out`() {
        val mesh = simpleQuadMesh()
        // pin 0 inside at (5, 5), pin 1 way outside at (-100, -100)
        val pins = floatArrayOf(5f, 5f, -100f, -100f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        // pinMask[0] should be true, pinMask[1] should be false
        assertEquals(true, solver.pinMask[0])
        assertEquals(false, solver.pinMask[1])
        // solve should not crash even with the masked-out pin
        val newPins = floatArrayOf(6f, 6f, -100f, -100f)
        val result = solver.solve(newPins)
        assertEquals(mesh.vertexCount * 2, result.size)
    }
}
