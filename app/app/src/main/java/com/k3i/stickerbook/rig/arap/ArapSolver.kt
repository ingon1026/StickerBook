package com.k3i.stickerbook.rig.arap

import kotlin.math.sqrt

class ArapInitException(message: String) : RuntimeException(message)

/**
 * As-Rigid-As-Possible 2D shape manipulation (Igarashi & Igarashi 2009).
 *
 * Kotlin port of AnimatedDrawings/animated_drawings/model/arap.py.
 *
 * Constructor precomputes the two Cholesky factors (one per solve stage).
 * solve(newPins) then performs two O(n²) substitutions and an edge-rotation
 * extraction.
 *
 * `initialPins` and the mesh vertices must be in the same coordinate frame.
 * The output of solve() is in that same frame.
 */
class ArapSolver(
    initialPins: FloatArray,
    val mesh: Mesh2D,
    val weight: Float = 1000f,
) {
    private val edges: List<IntPair>
    private val edgeVectors: FloatArray       // [E * 2]
    private val pinBary: List<List<IntFloat>>  // active pins: each has 3 (vertexId, weight)
    val pinMask: BooleanArray                  // length = initial pin count
    private val activePinCount: Int

    private val G: DoubleArray                 // [2E * 2V], row-major, rows=2E cols=2V
    private val tA1: DoubleArray               // [2V * 2(E+P)], row-major
    private val tA2: DoubleArray               // [V * (E+P)], row-major
    private val cholA1: CholeskyDecomposition  // 2V × 2V
    private val cholA2: CholeskyDecomposition  // V × V

    private val edgeCount: Int
    private val vertCount: Int

    init {
        vertCount = mesh.vertexCount

        // (1) Build deduplicated edge list from triangles (sorted so a <= b)
        val edgeSet = LinkedHashSet<IntPair>()
        var i = 0
        while (i < mesh.triangles.size) {
            val a = mesh.triangles[i]
            val b = mesh.triangles[i + 1]
            val c = mesh.triangles[i + 2]
            edgeSet.add(IntPair.sorted(a, b))
            edgeSet.add(IntPair.sorted(b, c))
            edgeSet.add(IntPair.sorted(c, a))
            i += 3
        }
        edges = edgeSet.toList()
        edgeCount = edges.size

        // (2) Edge vectors: vj - vi
        edgeVectors = FloatArray(edgeCount * 2)
        for (k in 0 until edgeCount) {
            val vi = edges[k].a
            val vj = edges[k].b
            edgeVectors[k * 2]     = mesh.vertices[vj * 2]     - mesh.vertices[vi * 2]
            edgeVectors[k * 2 + 1] = mesh.vertices[vj * 2 + 1] - mesh.vertices[vi * 2 + 1]
        }

        // (3) Pin barycentric: find containing triangle for each initial pin
        val pinCount = initialPins.size / 2
        pinMask = BooleanArray(pinCount)
        val bary = ArrayList<List<IntFloat>>()
        for (p in 0 until pinCount) {
            val px = initialPins[p * 2]
            val py = initialPins[p * 2 + 1]
            val found = findPinTriangle(px, py)
            if (found != null) {
                bary.add(found)
                pinMask[p] = true
            } else {
                pinMask[p] = false
            }
        }
        pinBary = bary
        activePinCount = pinBary.size
        require(activePinCount > 0) { "all pins outside mesh" }

        // (4) Build vertex → neighbor vertex map
        val vNeighbors = HashMap<Int, HashSet<Int>>()
        var t = 0
        while (t < mesh.triangles.size) {
            val a = mesh.triangles[t]
            val b = mesh.triangles[t + 1]
            val c = mesh.triangles[t + 2]
            vNeighbors.getOrPut(a) { HashSet() }.also { it.add(b); it.add(c) }
            vNeighbors.getOrPut(b) { HashSet() }.also { it.add(a); it.add(c) }
            vNeighbors.getOrPut(c) { HashSet() }.also { it.add(a); it.add(b) }
            t += 3
        }

        // (5) Build A1 [2(E+P) x 2V] and G [2E x 2V]
        val rowsA1 = 2 * (edgeCount + activePinCount)
        val colsA1 = 2 * vertCount  // = 2V
        val A1 = DoubleArray(rowsA1 * colsA1)
        val Gtmp = DoubleArray(2 * edgeCount * colsA1)

        for (k in 0 until edgeCount) {
            val viIdx = edges[k].a
            val vjIdx = edges[k].b

            // A1[2k:2k+2, 2vi:2vi+2] = -I_2
            A1[(2 * k)     * colsA1 + 2 * viIdx]     = -1.0
            A1[(2 * k + 1) * colsA1 + 2 * viIdx + 1] = -1.0
            // A1[2k:2k+2, 2vj:2vj+2] = +I_2
            A1[(2 * k)     * colsA1 + 2 * vjIdx]     = 1.0
            A1[(2 * k + 1) * colsA1 + 2 * vjIdx + 1] = 1.0

            // e_vnbr_idxs = [vi, vj, shared neighbors of vi and vj]
            val viN = vNeighbors[viIdx] ?: HashSet()
            val vjN = vNeighbors[vjIdx] ?: HashSet()
            val eVnbrIdxs = ArrayList<Int>()
            eVnbrIdxs.add(viIdx)
            eVnbrIdxs.add(vjIdx)
            for (n in viN) if (n in vjN) eVnbrIdxs.add(n)

            val nNbrTotal = eVnbrIdxs.size       // = |{vi, vj, vr, vl, ...}|
            val nNbrExcl  = nNbrTotal - 1        // = nNbrTotal - 1 (excluding vi itself)
            val centerX   = mesh.vertices[viIdx * 2].toDouble()
            val centerY   = mesh.vertices[viIdx * 2 + 1].toDouble()

            // G_k: shape (2*nNbrExcl, 2)
            // For each neighbor v in e_vnbr_xys[1:]:
            //   rows [2m, 2m+1] = [(vx, vy), (vy, -vx)]  relative to vi
            val gkRows = 2 * nNbrExcl
            val Gk = Array(gkRows) { DoubleArray(2) }
            for (m in 0 until nNbrExcl) {
                val v = eVnbrIdxs[m + 1]
                val vx = mesh.vertices[v * 2].toDouble()     - centerX
                val vy = mesh.vertices[v * 2 + 1].toDouble() - centerY
                Gk[2 * m][0]     = vx;  Gk[2 * m][1]     = vy
                Gk[2 * m + 1][0] = vy;  Gk[2 * m + 1][1] = -vx
            }

            // G_k_star = inv(G_k^T · G_k) · G_k^T  shape: (2 × gkRows)
            val GkStar = pseudoInverseLeft2Col(Gk)  // 2 × gkRows, row-major flat

            // edge_matrix: shape (gkRows × 2*nNbrTotal)
            // = hstack(tile(-I_2, (nNbrExcl, 1)), I_(2*nNbrExcl))
            // First 2 cols: stacked -I_2 (nNbrExcl times), remaining 2*nNbrExcl: identity
            val edgeMat = buildEdgeMatrix(nNbrTotal, nNbrExcl)  // gkRows × 2*nNbrTotal

            // g = G_k_star (2 × gkRows) · edge_matrix (gkRows × 2*nNbrTotal) = (2 × 2*nNbrTotal)
            val g = matMul(GkStar, 2, gkRows, edgeMat, 2 * nNbrTotal)

            // e = [[ekx, eky], [eky, -ekx]]
            val ekx = edgeVectors[k * 2].toDouble()
            val eky = edgeVectors[k * 2 + 1].toDouble()
            val eMat = doubleArrayOf(ekx, eky, eky, -ekx)

            // h = e (2×2) · g (2 × 2*nNbrTotal) = (2 × 2*nNbrTotal)
            val h = matMul(eMat, 2, 2, g, 2 * nNbrTotal)

            // Splice h into A1 and g into G
            for (hOffset in 0 until nNbrTotal) {
                val vIdx = eVnbrIdxs[hOffset]
                // A1[2k:2k+2, 2vIdx:2vIdx+2] -= h[:, 2*hOffset:2*hOffset+2]
                A1[(2 * k)     * colsA1 + 2 * vIdx]     -= h[0 * (2 * nNbrTotal) + 2 * hOffset]
                A1[(2 * k)     * colsA1 + 2 * vIdx + 1] -= h[0 * (2 * nNbrTotal) + 2 * hOffset + 1]
                A1[(2 * k + 1) * colsA1 + 2 * vIdx]     -= h[1 * (2 * nNbrTotal) + 2 * hOffset]
                A1[(2 * k + 1) * colsA1 + 2 * vIdx + 1] -= h[1 * (2 * nNbrTotal) + 2 * hOffset + 1]

                // G[2k:2k+2, 2vIdx:2vIdx+2] = g[:, 2*hOffset:2*hOffset+2]
                Gtmp[(2 * k)     * colsA1 + 2 * vIdx]     = g[0 * (2 * nNbrTotal) + 2 * hOffset]
                Gtmp[(2 * k)     * colsA1 + 2 * vIdx + 1] = g[0 * (2 * nNbrTotal) + 2 * hOffset + 1]
                Gtmp[(2 * k + 1) * colsA1 + 2 * vIdx]     = g[1 * (2 * nNbrTotal) + 2 * hOffset]
                Gtmp[(2 * k + 1) * colsA1 + 2 * vIdx + 1] = g[1 * (2 * nNbrTotal) + 2 * hOffset + 1]
            }
        }
        G = Gtmp

        // (6) Bottom rows of A1: pin constraints (x and y separately)
        for (p in 0 until activePinCount) {
            for (vw in pinBary[p]) {
                val vIdx = vw.idx
                val vW   = vw.value.toDouble()
                A1[(2 * edgeCount + 2 * p)     * colsA1 + 2 * vIdx]     = weight * vW
                A1[(2 * edgeCount + 2 * p + 1) * colsA1 + 2 * vIdx + 1] = weight * vW
            }
        }

        // (7) Build A2 [(E+P) × V]
        val rowsA2 = edgeCount + activePinCount
        val A2 = DoubleArray(rowsA2 * vertCount)
        for (k in 0 until edgeCount) {
            A2[k * vertCount + edges[k].a] = -1.0
            A2[k * vertCount + edges[k].b] =  1.0
        }
        for (p in 0 until activePinCount) {
            for (vw in pinBary[p]) {
                A2[(edgeCount + p) * vertCount + vw.idx] = weight * vw.value.toDouble()
            }
        }

        // (8) Precompute tA1, tA2
        tA1 = transpose(A1, rowsA1, colsA1)
        tA2 = transpose(A2, rowsA2, vertCount)

        // (9) Cholesky of (tA1·A1) and (tA2·A2), with perturbation retry
        val tA1xA1 = matMul(tA1, colsA1, rowsA1, A1, colsA1)
        cholA1 = makeCholeskyOrPerturb(tA1xA1, colsA1)

        val tA2xA2 = matMul(tA2, vertCount, rowsA2, A2, vertCount)
        cholA2 = makeCholeskyOrPerturb(tA2xA2, vertCount)
    }

    /** Run ARAP on new pin positions. Returns deformed vertices [V*2]. */
    fun solve(newPinsAll: FloatArray): FloatArray {
        require(newPinsAll.size == pinMask.size * 2)

        // Collect only active (non-masked) pins
        val newPins = FloatArray(activePinCount * 2)
        var w = 0
        for (p in pinMask.indices) {
            if (pinMask[p]) {
                newPins[w * 2]     = newPinsAll[p * 2]
                newPins[w * 2 + 1] = newPinsAll[p * 2 + 1]
                w++
            }
        }

        val colsA1 = 2 * vertCount

        // Stage 1: find v1 that minimises ||A1·v - b1||
        // b1 = [0 ... 0 | w*px0 w*py0 w*px1 w*py1 ...]
        val b1 = DoubleArray(2 * edgeCount + 2 * activePinCount)
        for (p in 0 until activePinCount) {
            b1[2 * edgeCount + 2 * p]     = weight * newPins[p * 2].toDouble()
            b1[2 * edgeCount + 2 * p + 1] = weight * newPins[p * 2 + 1].toDouble()
        }
        val rhs1 = matVecMul(tA1, colsA1, 2 * (edgeCount + activePinCount), b1)
        val v1   = cholA1.solve(rhs1)

        // Extract per-edge rotation from T1 = G · v1
        val T1 = matVecMul(G, 2 * edgeCount, colsA1, v1)

        // Stage 2: rotate original edge vectors, build b2
        val b2x = DoubleArray(edgeCount + activePinCount)
        val b2y = DoubleArray(edgeCount + activePinCount)
        for (k in 0 until edgeCount) {
            var c = T1[2 * k]
            var s = T1[2 * k + 1]
            val scale = 1.0 / sqrt(c * c + s * s)
            c *= scale; s *= scale
            // T2 = [[c, s], [-s, c]]; e1 = T2 · e0
            val ex = edgeVectors[k * 2].toDouble()
            val ey = edgeVectors[k * 2 + 1].toDouble()
            b2x[k] =  c * ex + s * ey
            b2y[k] = -s * ex + c * ey
        }
        for (p in 0 until activePinCount) {
            b2x[edgeCount + p] = weight * newPins[p * 2].toDouble()
            b2y[edgeCount + p] = weight * newPins[p * 2 + 1].toDouble()
        }

        val rhs2x = matVecMul(tA2, vertCount, edgeCount + activePinCount, b2x)
        val rhs2y = matVecMul(tA2, vertCount, edgeCount + activePinCount, b2y)
        val v2x   = cholA2.solve(rhs2x)
        val v2y   = cholA2.solve(rhs2y)

        // Interleave x, y → flat output
        val out = FloatArray(vertCount * 2)
        for (v in 0 until vertCount) {
            out[v * 2]     = v2x[v].toFloat()
            out[v * 2 + 1] = v2y[v].toFloat()
        }
        return out
    }

    // ===== Private helpers =====

    private fun findPinTriangle(px: Float, py: Float): List<IntFloat>? {
        var t = 0
        while (t < mesh.triangles.size) {
            val a = mesh.triangles[t]
            val b = mesh.triangles[t + 1]
            val c = mesh.triangles[t + 2]
            val ax = mesh.vertices[a * 2];     val ay = mesh.vertices[a * 2 + 1]
            val bx = mesh.vertices[b * 2];     val by = mesh.vertices[b * 2 + 1]
            val cx = mesh.vertices[c * 2];     val cy = mesh.vertices[c * 2 + 1]
            if (Barycentric.pointInTriangle(px, py, ax, ay, bx, by, cx, cy)) {
                val bc = Barycentric.compute(px, py, ax, ay, bx, by, cx, cy)!!
                return listOf(IntFloat(a, bc.first), IntFloat(b, bc.second), IntFloat(c, bc.third))
            }
            t += 3
        }
        return null
    }

    /** Returns Cholesky of `matrix` (n×n, row-major flat), perturbing diagonal if singular. */
    private fun makeCholeskyOrPerturb(matrix: DoubleArray, n: Int): CholeskyDecomposition {
        var m = matrix
        repeat(5) { retry ->
            try {
                return CholeskyDecomposition(m, n)
            } catch (e: SingularException) {
                val eps = 1e-8 * (retry + 1)
                val perturbed = m.copyOf()
                for (ii in 0 until n) perturbed[ii * n + ii] += eps
                m = perturbed
            }
        }
        throw ArapInitException("Cholesky failed after 5 perturbation retries")
    }

    /** Transpose of rows×cols matrix (row-major flat). Returns cols×rows result. */
    private fun transpose(m: DoubleArray, rows: Int, cols: Int): DoubleArray {
        val out = DoubleArray(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) out[c * rows + r] = m[r * cols + c]
        return out
    }

    /** Dense matrix multiply: (ar × ac) · (ac × bc) → (ar × bc), all row-major flat. */
    private fun matMul(a: DoubleArray, ar: Int, ac: Int, b: DoubleArray, bc: Int): DoubleArray {
        val out = DoubleArray(ar * bc)
        for (ii in 0 until ar) for (j in 0 until bc) {
            var s = 0.0
            for (k in 0 until ac) s += a[ii * ac + k] * b[k * bc + j]
            out[ii * bc + j] = s
        }
        return out
    }

    /** Dense matrix-vector multiply: (ar × ac) · v → ar result. */
    private fun matVecMul(a: DoubleArray, ar: Int, ac: Int, v: DoubleArray): DoubleArray {
        val out = DoubleArray(ar)
        for (ii in 0 until ar) {
            var s = 0.0
            for (k in 0 until ac) s += a[ii * ac + k] * v[k]
            out[ii] = s
        }
        return out
    }

    /**
     * Left pseudo-inverse of a (rows × 2) matrix.
     * Returns 2 × rows (row-major flat) = inv(mᵀ·m) · mᵀ.
     */
    private fun pseudoInverseLeft2Col(m: Array<DoubleArray>): DoubleArray {
        val rows = m.size
        // mᵀ·m (2×2): a00, a01, a11
        var a00 = 0.0; var a01 = 0.0; var a11 = 0.0
        for (k in 0 until rows) {
            a00 += m[k][0] * m[k][0]
            a01 += m[k][0] * m[k][1]
            a11 += m[k][1] * m[k][1]
        }
        val det = a00 * a11 - a01 * a01
        // inv 2×2: [[a11, -a01], [-a01, a00]] / det
        val inv00: Double
        val inv01: Double
        val inv11: Double
        if (kotlin.math.abs(det) < 1e-12) {
            // Degenerate — return transpose (caller perturbation handles it)
            val out = DoubleArray(2 * rows)
            for (k in 0 until rows) { out[k] = m[k][0]; out[rows + k] = m[k][1] }
            return out
        } else {
            inv00 = a11 / det
            inv01 = -a01 / det
            inv11 = a00 / det
        }
        // result = inv (2×2) · mᵀ (2×rows) → 2×rows
        val out = DoubleArray(2 * rows)
        for (k in 0 until rows) {
            out[k]        = inv00 * m[k][0] + inv01 * m[k][1]
            out[rows + k] = inv01 * m[k][0] + inv11 * m[k][1]
        }
        return out
    }

    /**
     * Build edge_matrix: shape (2*nNbrExcl) × (2*nNbrTotal), row-major flat.
     *
     * = hstack(tile(-I_2, (nNbrExcl, 1)), I_(2*nNbrExcl))
     * First 2 cols = stacked -I_2 blocks (nNbrExcl times).
     * Remaining 2*nNbrExcl cols = identity.
     */
    private fun buildEdgeMatrix(nNbrTotal: Int, nNbrExcl: Int): DoubleArray {
        val rows = 2 * nNbrExcl
        val cols = 2 * nNbrTotal
        val out  = DoubleArray(rows * cols)
        // First 2 cols: stacked -I_2
        for (m in 0 until nNbrExcl) {
            out[(2 * m)     * cols + 0] = -1.0
            out[(2 * m + 1) * cols + 1] = -1.0
        }
        // Remaining 2*nNbrExcl cols: identity (offset by 2)
        for (k in 0 until 2 * nNbrExcl) {
            out[k * cols + (2 + k)] = 1.0
        }
        return out
    }
}

// ===== Helper data classes =====

data class IntPair(val a: Int, val b: Int) {
    companion object {
        fun sorted(x: Int, y: Int): IntPair = if (x <= y) IntPair(x, y) else IntPair(y, x)
    }
}

data class IntFloat(val idx: Int, val value: Float)
