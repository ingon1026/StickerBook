package com.k3i.stickerbook.rig.arap

import org.junit.Assert.assertEquals
import org.junit.Test

class CholeskyDecompositionTest {

    @Test
    fun `solve 2x2 SPD matrix`() {
        // A = [[4, 1], [1, 3]], b = [1, 2]
        // expected: x = [1/11, 7/11]
        val A = doubleArrayOf(4.0, 1.0, 1.0, 3.0)
        val chol = CholeskyDecomposition(A, n = 2)
        val x = chol.solve(doubleArrayOf(1.0, 2.0))
        assertEquals(1.0 / 11.0, x[0], 1e-9)
        assertEquals(7.0 / 11.0, x[1], 1e-9)
    }

    @Test
    fun `solve identity matrix returns input`() {
        val A = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val chol = CholeskyDecomposition(A, n = 3)
        val x = chol.solve(doubleArrayOf(3.0, 5.0, 7.0))
        assertEquals(3.0, x[0], 1e-9)
        assertEquals(5.0, x[1], 1e-9)
        assertEquals(7.0, x[2], 1e-9)
    }

    @Test
    fun `solve diagonal SPD matrix`() {
        val A = DoubleArray(9).also {
            it[0] = 2.0; it[4] = 4.0; it[8] = 8.0
        }
        val chol = CholeskyDecomposition(A, n = 3)
        val x = chol.solve(doubleArrayOf(4.0, 12.0, 24.0))
        assertEquals(2.0, x[0], 1e-9)
        assertEquals(3.0, x[1], 1e-9)
        assertEquals(3.0, x[2], 1e-9)
    }

    @Test
    fun `non-SPD matrix throws SingularException`() {
        val A = doubleArrayOf(1.0, 0.0, 0.0, -1.0)
        try {
            CholeskyDecomposition(A, n = 2)
            throw AssertionError("Expected SingularException")
        } catch (e: SingularException) {
            // expected
        }
    }

    @Test
    fun `zero matrix throws SingularException`() {
        val A = DoubleArray(4)
        try {
            CholeskyDecomposition(A, n = 2)
            throw AssertionError("Expected SingularException")
        } catch (e: SingularException) {
            // expected
        }
    }
}
