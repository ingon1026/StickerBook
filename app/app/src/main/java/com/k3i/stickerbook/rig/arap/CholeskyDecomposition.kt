package com.k3i.stickerbook.rig.arap

import kotlin.math.sqrt

class SingularException(message: String = "Cholesky: matrix not SPD") : RuntimeException(message)

/**
 * Dense Cholesky factorization A = L·Lᵀ for symmetric positive-definite A.
 *
 * Constructor performs the O(n³/3) factorization and caches L.
 * solve(b) then performs forward + backward substitution in O(n²).
 *
 * Matrices are stored row-major flat: A[i, j] = a[i * n + j].
 */
class CholeskyDecomposition(matrix: DoubleArray, val n: Int) {

    private val L: DoubleArray = factor(matrix)

    /** Solve A·x = b. Returns new array x of length n. */
    fun solve(b: DoubleArray): DoubleArray {
        require(b.size == n) { "b must have size $n, got ${b.size}" }
        val y = DoubleArray(n)
        // forward: L·y = b
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= L[i * n + k] * y[k]
            y[i] = s / L[i * n + i]
        }
        val x = DoubleArray(n)
        // backward: Lᵀ·x = y
        for (i in (n - 1) downTo 0) {
            var s = y[i]
            for (k in (i + 1) until n) s -= L[k * n + i] * x[k]
            x[i] = s / L[i * n + i]
        }
        return x
    }

    private fun factor(a: DoubleArray): DoubleArray {
        require(a.size == n * n) { "matrix must be $n*$n" }
        val l = DoubleArray(n * n)
        for (k in 0 until n) {
            var d = a[k * n + k]
            for (j in 0 until k) {
                val v = l[k * n + j]
                d -= v * v
            }
            if (d <= 0.0 || d.isNaN()) throw SingularException(
                "Cholesky: non-positive diagonal at k=$k (d=$d)")
            val kk = sqrt(d)
            l[k * n + k] = kk
            for (i in (k + 1) until n) {
                var s = a[i * n + k]
                for (j in 0 until k) s -= l[i * n + j] * l[k * n + j]
                l[i * n + k] = s / kk
            }
        }
        return l
    }
}
