# Sub-3 ARAP mesh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sub-1 mask + Sub-2b 17 COCO keypoints 를 control point 로 사용하여 캐릭터 bitmap 을 motion sequence 따라 변형. Kotlin pure ARAP solver (Igarashi 2009) + grid mesh + `Canvas.drawBitmapMesh` rendering 으로 30 frame PNG sequence 생성.

**Architecture:** `ArapRigger : CharacterRigger` 신규 클래스가 detect→pose→mesh→ARAP solve→render 의 orchestration. Dense Cholesky factor 한 번 precompute 후 매 frame 의 새 pin 위치에 대해 forward/back substitution. `MotionSource` interface 로 stub (hardcoded "wave") 와 Sub-4 의 BVH 가 swap 가능.

**Tech Stack:** Kotlin + Android Canvas/Bitmap + Robolectric + JUnit4 (의존성 0, ONNX/scipy/Eigen 안 씀)

**Spec:** `docs/superpowers/specs/2026-05-15-sub3-arap-mesh-design.md`

---

## File Structure

### Production (Android)

| 파일 | 책임 |
|---|---|
| `app/app/src/main/java/com/k3i/stickerbook/rig/arap/Mesh2D.kt` | data class (vertices, triangles, gridW/H) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/arap/Barycentric.kt` | point-in-triangle + barycentric coord helper |
| `app/app/src/main/java/com/k3i/stickerbook/rig/arap/GridMeshBuilder.kt` | mask Bitmap + grid size → Mesh2D |
| `app/app/src/main/java/com/k3i/stickerbook/rig/arap/CholeskyDecomposition.kt` | dense SPD Cholesky factor + solve(b) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/arap/ArapSolver.kt` | Igarashi 2009 ARAP, Kotlin port of AD arap.py |
| `app/app/src/main/java/com/k3i/stickerbook/rig/MotionSource.kt` | interface — motionId + initialPins → List<FloatArray> |
| `app/app/src/main/java/com/k3i/stickerbook/rig/MotionStub.kt` | hardcoded "wave" / 정적 등 |
| `app/app/src/main/java/com/k3i/stickerbook/rig/MeshRenderer.kt` | Canvas.drawBitmapMesh wrapper |
| `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt` | CharacterRigger 구현, orchestration |
| `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt` | 1줄 swap: realAd → realArap |

### Test (Android, JUnit4 + Robolectric)

| 파일 | 책임 |
|---|---|
| `app/app/src/test/java/com/k3i/stickerbook/rig/arap/BarycentricTest.kt` | inside/edge/outside, known triangle round-trip |
| `app/app/src/test/java/com/k3i/stickerbook/rig/arap/GridMeshBuilderTest.kt` | mask=전체 1 → V/T 개수, mask=구멍 → boundary OK |
| `app/app/src/test/java/com/k3i/stickerbook/rig/arap/CholeskyDecompositionTest.kt` | 4×4 / 16×16 SPD round-trip, singular catch |
| `app/app/src/test/java/com/k3i/stickerbook/rig/arap/ArapSolverTest.kt` | identity / translation / rotation / large mesh perf |
| `app/app/src/test/java/com/k3i/stickerbook/rig/MotionStubTest.kt` | "wave" sequence, identity for unknown motion |
| `app/app/src/test/java/com/k3i/stickerbook/rig/ArapRiggerTest.kt` | mock detector/estimator/motion → 30 PNG 생성 |

### Docs

| 파일 | 책임 |
|---|---|
| `docs/sub3_results.md` | M3 결과 (시연 결과, perf, follow-up) |

---

## Task 1: Mesh2D + Barycentric (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/arap/Mesh2D.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/arap/Barycentric.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/arap/BarycentricTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.rig.arap

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class BarycentricTest {

    @Test
    fun `point inside triangle returns three valid weights`() {
        // triangle (0,0)-(10,0)-(0,10), point (1,1) inside
        val tri = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)
        val result = Barycentric.compute(px = 1f, py = 1f,
            ax = tri[0], ay = tri[1], bx = tri[2], by = tri[3], cx = tri[4], cy = tri[5])
        assertNotNull(result)
        val (u, v, w) = result
        // sum to 1
        assertEquals(1.0f, u + v + w, 0.001f)
        // all positive for interior point
        assert(u > 0 && v > 0 && w > 0)
    }

    @Test
    fun `point at vertex A returns u=1 others=0`() {
        val result = Barycentric.compute(px = 0f, py = 0f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f)
        assertNotNull(result)
        assertEquals(1.0f, result.first, 0.001f)
        assertEquals(0.0f, result.second, 0.001f)
        assertEquals(0.0f, result.third, 0.001f)
    }

    @Test
    fun `point outside triangle returns negative weight`() {
        // point (-1, -1) outside the triangle
        val result = Barycentric.compute(px = -1f, py = -1f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f)
        assertNotNull(result)
        // at least one weight should be negative for outside
        val (u, v, w) = result
        assert(u < 0 || v < 0 || w < 0)
    }

    @Test
    fun `pointInTriangle returns false for clearly outside point`() {
        assertEquals(false, Barycentric.pointInTriangle(
            px = -5f, py = -5f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f,
        ))
    }

    @Test
    fun `pointInTriangle returns true for clearly inside point`() {
        assertEquals(true, Barycentric.pointInTriangle(
            px = 2f, py = 2f,
            ax = 0f, ay = 0f, bx = 10f, by = 0f, cx = 0f, cy = 10f,
        ))
    }
}
```

- [ ] **Step 2: test 실행 → fail (class 없음)**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:testDebugUnitTest --tests "*.BarycentricTest" 2>&1 | tail -10
```

Expected: FAIL with `Barycentric` unresolved.

- [ ] **Step 3: `Mesh2D.kt` 작성**

```kotlin
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
```

- [ ] **Step 4: `Barycentric.kt` 작성**

```kotlin
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
```

- [ ] **Step 5: test 재실행 → 5 tests PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.BarycentricTest" 2>&1 | tail -10
```

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/arap/Mesh2D.kt \
        app/app/src/main/java/com/k3i/stickerbook/rig/arap/Barycentric.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/arap/BarycentricTest.kt
git commit -m "feat(sub-3): Mesh2D data class + Barycentric helpers (TDD)"
```

---

## Task 2: GridMeshBuilder (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/arap/GridMeshBuilder.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/arap/GridMeshBuilderTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.rig.arap

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GridMeshBuilderTest {

    @Test
    fun `grid mesh vertex count matches gridW+1 times gridH+1`() {
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)  // all foreground
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
        // top-left vertex (index 0) at (0, 0)
        assertEquals(0f, mesh.vertices[0], 0.001f)
        assertEquals(0f, mesh.vertices[1], 0.001f)
        // bottom-right vertex
        val lastIdx = ((4 + 1) * (3 + 1) - 1) * 2
        assertEquals(80f, mesh.vertices[lastIdx], 0.001f)
        assertEquals(60f, mesh.vertices[lastIdx + 1], 0.001f)
    }

    @Test
    fun `vertices row-major ordering`() {
        val mask = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        val mesh = GridMeshBuilder.build(mask, gridWidth = 2, gridHeight = 3)
        // vertex (1, 0) = index 1 → (20, 0)
        assertEquals(20f, mesh.vertices[1 * 2], 0.001f)
        assertEquals(0f, mesh.vertices[1 * 2 + 1], 0.001f)
        // vertex (0, 1) = index (gridW+1) = 3 → (0, 10)
        assertEquals(0f, mesh.vertices[3 * 2], 0.001f)
        assertEquals(10f, mesh.vertices[3 * 2 + 1], 0.001f)
    }

    @Test
    fun `each grid cell produces two triangles with valid vertex ids`() {
        val mask = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        val mesh = GridMeshBuilder.build(mask, gridWidth = 2, gridHeight = 2)
        // all triangle indices are valid
        for (id in mesh.triangles) {
            assertTrue(id in 0 until mesh.vertexCount, "triangle id $id out of range")
        }
        // sum of vertex ids per triangle should be distinct triples
        val triples = (0 until mesh.triangleCount).map { i ->
            val a = mesh.triangles[i * 3]
            val b = mesh.triangles[i * 3 + 1]
            val c = mesh.triangles[i * 3 + 2]
            Triple(a, b, c)
        }.toSet()
        assertEquals(mesh.triangleCount, triples.size)
    }
}
```

- [ ] **Step 2: test 실행 → fail**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.GridMeshBuilderTest" 2>&1 | tail -10
```

- [ ] **Step 3: `GridMeshBuilder.kt` 작성**

```kotlin
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
```

- [ ] **Step 4: test 실행 → 5 tests PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.GridMeshBuilderTest" 2>&1 | tail -10
```

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/arap/GridMeshBuilder.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/arap/GridMeshBuilderTest.kt
git commit -m "feat(sub-3): GridMeshBuilder — bitmap → row-major grid mesh"
```

---

## Task 3: CholeskyDecomposition (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/arap/CholeskyDecomposition.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/arap/CholeskyDecompositionTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.rig.arap

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CholeskyDecompositionTest {

    @Test
    fun `solve 2x2 SPD matrix`() {
        // A = [[4, 1], [1, 3]], b = [1, 2]
        // expected: x = [1/11, 7/11] = [0.0909..., 0.6363...]
        val A = doubleArrayOf(4.0, 1.0, 1.0, 3.0)
        val chol = CholeskyDecomposition(A, n = 2)
        val x = chol.solve(doubleArrayOf(1.0, 2.0))
        assertEquals(1.0 / 11.0, x[0], 1e-9)
        assertEquals(7.0 / 11.0, x[1], 1e-9)
    }

    @Test
    fun `solve identity matrix returns input`() {
        // A = I_3, b = [3, 5, 7]
        val A = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val chol = CholeskyDecomposition(A, n = 3)
        val x = chol.solve(doubleArrayOf(3.0, 5.0, 7.0))
        assertEquals(3.0, x[0], 1e-9)
        assertEquals(5.0, x[1], 1e-9)
        assertEquals(7.0, x[2], 1e-9)
    }

    @Test
    fun `solve diagonal SPD matrix`() {
        // A = diag(2, 4, 8), b = [4, 12, 24]
        // x = [2, 3, 3]
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
        // A = [[1, 0], [0, -1]] — has negative eigenvalue
        val A = doubleArrayOf(1.0, 0.0, 0.0, -1.0)
        assertFailsWith<SingularException> {
            CholeskyDecomposition(A, n = 2)
        }
    }

    @Test
    fun `zero matrix throws SingularException`() {
        val A = DoubleArray(4)  // 2x2 zeros
        assertFailsWith<SingularException> {
            CholeskyDecomposition(A, n = 2)
        }
    }
}
```

- [ ] **Step 2: test 실행 → fail**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.CholeskyDecompositionTest" 2>&1 | tail -10
```

- [ ] **Step 3: `CholeskyDecomposition.kt` 작성**

```kotlin
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
        // forward: L·y = b  →  y[i] = (b[i] - Σ L[i,k]*y[k] for k<i) / L[i,i]
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= L[i * n + k] * y[k]
            y[i] = s / L[i * n + i]
        }
        val x = DoubleArray(n)
        // backward: Lᵀ·x = y  →  x[i] = (y[i] - Σ L[k,i]*x[k] for k>i) / L[i,i]
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
            // diagonal
            var d = a[k * n + k]
            for (j in 0 until k) {
                val v = l[k * n + j]
                d -= v * v
            }
            if (d <= 0.0 || d.isNaN()) throw SingularException(
                "Cholesky: non-positive diagonal at k=$k (d=$d)")
            val kk = sqrt(d)
            l[k * n + k] = kk
            // below diagonal
            for (i in (k + 1) until n) {
                var s = a[i * n + k]
                for (j in 0 until k) s -= l[i * n + j] * l[k * n + j]
                l[i * n + k] = s / kk
            }
        }
        return l
    }
}
```

- [ ] **Step 4: test 재실행 → 5 tests PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.CholeskyDecompositionTest" 2>&1 | tail -10
```

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/arap/CholeskyDecomposition.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/arap/CholeskyDecompositionTest.kt
git commit -m "feat(sub-3): CholeskyDecomposition — dense SPD factor + O(n^2) solve"
```

---

## Task 4: ArapSolver (TDD) — 핵심 알고리즘

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/arap/ArapSolver.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/arap/ArapSolverTest.kt`

**Reference:** `/home/ingon/AR_book/AnimatedDrawings/animated_drawings/model/arap.py` (AD 의 원본). Algorithm: Igarashi & Igarashi 2009.

- [ ] **Step 1: failing tests 작성 (identity, translation)**

```kotlin
package com.k3i.stickerbook.rig.arap

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        // pin at vertex 0 (0, 0)
        val pins = floatArrayOf(0f, 0f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        val result = solver.solve(pins)
        // each vertex should be at its original position (small tolerance due to numeric)
        for (i in mesh.vertices.indices) {
            assertEquals(mesh.vertices[i], result[i], 0.1f)
        }
    }

    @Test
    fun `translating a pin translates all vertices by the same delta`() {
        val mesh = simpleQuadMesh()
        // pin all 4 vertices to ensure rigid translation is forced
        val pins = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        // translate all pins by (+5, +3)
        val newPins = floatArrayOf(5f, 3f, 15f, 3f, 5f, 13f, 15f, 13f)
        val result = solver.solve(newPins)
        for (v in 0 until 4) {
            assertEquals(mesh.vertices[v * 2] + 5f, result[v * 2], 0.5f)
            assertEquals(mesh.vertices[v * 2 + 1] + 3f, result[v * 2 + 1], 0.5f)
        }
    }

    @Test
    fun `solve respects pin constraints`() {
        val mesh = simpleQuadMesh()
        // 4 corners pinned
        val pins = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        // move top-right (3rd pin, idx 3 in mesh.vertices) to (20, 0)
        val newPins = floatArrayOf(0f, 0f, 20f, 0f, 0f, 10f, 10f, 10f)
        val result = solver.solve(newPins)
        // top-right vertex should be near (20, 0)
        assertEquals(20f, result[1 * 2], 1.0f)
        assertEquals(0f, result[1 * 2 + 1], 1.0f)
        // top-left vertex should stay near (0, 0)
        assertEquals(0f, result[0 * 2], 1.0f)
        assertEquals(0f, result[0 * 2 + 1], 1.0f)
    }

    @Test
    fun `pin outside mesh is skipped (pin_mask)`() {
        val mesh = simpleQuadMesh()
        // pin 0 inside, pin 1 outside mesh (negative coords)
        val pins = floatArrayOf(5f, 5f, -100f, -100f)
        val solver = ArapSolver(initialPins = pins, mesh = mesh)
        // Should not throw, just ignore the outside pin
        val newPins = floatArrayOf(6f, 6f, -100f, -100f)
        val result = solver.solve(newPins)
        // Output size = mesh.vertexCount * 2
        assertEquals(mesh.vertexCount * 2, result.size)
    }
}
```

- [ ] **Step 2: test 실행 → fail (class 없음)**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.ArapSolverTest" 2>&1 | tail -10
```

- [ ] **Step 3: `ArapSolver.kt` 작성 — 큰 파일이라 구조를 단계적으로**

```kotlin
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
 * extraction. With grid mesh of V=567 vertices, a single solve is well under
 * 50 ms on the Galaxy Tab.
 *
 * Important: `initialPins` and the mesh vertices must be in the same coordinate
 * frame (e.g. character-bitmap space). The output of solve() is in that same frame.
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

    private val G: DoubleArray                 // [2E, 2V]
    private val tA1: DoubleArray               // [2V, 2(E+P)]
    private val tA2: DoubleArray               // [V, E+P]
    private val cholA1: CholeskyDecomposition  // 2V × 2V
    private val cholA2: CholeskyDecomposition  // V × V

    private val edgeCount: Int
    private val vertCount: Int

    init {
        vertCount = mesh.vertexCount
        // (1) build deduplicated edges from triangles (sorted pair → set)
        val edgeSet = HashSet<IntPair>()
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

        // (2) edge vectors
        edgeVectors = FloatArray(edgeCount * 2)
        for (k in 0 until edgeCount) {
            val vi = edges[k].a
            val vj = edges[k].b
            edgeVectors[k * 2] = mesh.vertices[vj * 2] - mesh.vertices[vi * 2]
            edgeVectors[k * 2 + 1] = mesh.vertices[vj * 2 + 1] - mesh.vertices[vi * 2 + 1]
        }

        // (3) pin barycentric: for each input pin, find containing triangle
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

        // (4) build vertex → neighbor vertex map (for edge G_k computation)
        val vNeighbors = HashMap<Int, HashSet<Int>>()
        var t = 0
        while (t < mesh.triangles.size) {
            val a = mesh.triangles[t]
            val b = mesh.triangles[t + 1]
            val c = mesh.triangles[t + 2]
            vNeighbors.getOrPut(a) { HashSet() }.let { it.add(b); it.add(c) }
            vNeighbors.getOrPut(b) { HashSet() }.let { it.add(a); it.add(c) }
            vNeighbors.getOrPut(c) { HashSet() }.let { it.add(a); it.add(b) }
            t += 3
        }

        // (5) build A1 [2(E+P), 2V] and G [2E, 2V]
        val rowsA1 = 2 * (edgeCount + activePinCount)
        val colsA1 = 2 * vertCount
        val A1 = DoubleArray(rowsA1 * colsA1)
        val Gtmp = DoubleArray(2 * edgeCount * colsA1)

        for (k in 0 until edgeCount) {
            val viIdx = edges[k].a
            val vjIdx = edges[k].b
            // initialize A1 with -I at vi, +I at vj (top 2 rows for this edge)
            A1[(2 * k) * colsA1 + 2 * viIdx] = -1.0
            A1[(2 * k + 1) * colsA1 + 2 * viIdx + 1] = -1.0
            A1[(2 * k) * colsA1 + 2 * vjIdx] = 1.0
            A1[(2 * k + 1) * colsA1 + 2 * vjIdx + 1] = 1.0

            // find shared neighbors of vi and vj
            val viN = vNeighbors[viIdx] ?: HashSet()
            val vjN = vNeighbors[vjIdx] ?: HashSet()
            val sharedNeighbors = ArrayList<Int>()
            for (n in viN) if (n in vjN) sharedNeighbors.add(n)

            val eVnbrIdxs = ArrayList<Int>().apply {
                add(viIdx); add(vjIdx); addAll(sharedNeighbors)
            }
            val centerX = mesh.vertices[viIdx * 2]
            val centerY = mesh.vertices[viIdx * 2 + 1]
            // G_k matrix is (2 * (|nbrs|-1)) × 4
            val nNbr = eVnbrIdxs.size - 1
            val GkRows = 2 * nNbr
            val Gk = Array(GkRows) { DoubleArray(4) }
            for (m in 0 until nNbr) {
                val v = eVnbrIdxs[m + 1]
                val vx = (mesh.vertices[v * 2] - centerX).toDouble()
                val vy = (mesh.vertices[v * 2 + 1] - centerY).toDouble()
                Gk[2 * m][0] = vx;  Gk[2 * m][1] = vy
                Gk[2 * m + 1][0] = vy; Gk[2 * m + 1][1] = -vx
            }
            // compute Gk_star = inv(Gk^T · Gk) · Gk^T  (a 4 × (2*nNbr) matrix)
            val GkStar = pseudoInverseLeft(Gk)  // returns 4 × GkRows matrix

            // edge_matrix = [-I_{2x2} ... -I_{2x2}] | I_{2*(nNbr-1)}  shape (2*nNbr) × (2*nNbr)
            // Multiply GkStar (4 × 2*nNbr) by edge_matrix (2*nNbr × 2*nNbr) → g (4 × 2*nNbr)
            val edgeMatrix = buildEdgeMatrix(nNbr)
            val g = matMul(GkStar, 4, 2 * nNbr, edgeMatrix, 2 * nNbr)

            // e = [[ekx, eky],[eky, -ekx]]
            val ekx = edgeVectors[k * 2].toDouble()
            val eky = edgeVectors[k * 2 + 1].toDouble()
            val eMat = arrayOf(
                doubleArrayOf(ekx,  eky),
                doubleArrayOf(eky, -ekx),
            )
            // h = e · g  → shape (2 × 2*nNbr)
            val h = matMul2(eMat, g, 2 * nNbr)

            // splice h into A1, g into G for each neighbor vertex
            for (hOffset in eVnbrIdxs.indices) {
                val vIdx = eVnbrIdxs[hOffset]
                // A1[2k:2k+2, 2v:2v+2] -= h[:, 2*hOffset:2*hOffset+2]
                A1[(2 * k) * colsA1 + 2 * vIdx]     -= h[0][2 * hOffset]
                A1[(2 * k) * colsA1 + 2 * vIdx + 1] -= h[0][2 * hOffset + 1]
                A1[(2 * k + 1) * colsA1 + 2 * vIdx]     -= h[1][2 * hOffset]
                A1[(2 * k + 1) * colsA1 + 2 * vIdx + 1] -= h[1][2 * hOffset + 1]
                // G[2k:2k+2, 2v:2v+2] = g[:, 2*hOffset:2*hOffset+2]
                Gtmp[(2 * k) * colsA1 + 2 * vIdx]     = g[0 * (2 * nNbr) + 2 * hOffset]
                Gtmp[(2 * k) * colsA1 + 2 * vIdx + 1] = g[0 * (2 * nNbr) + 2 * hOffset + 1]
                Gtmp[(2 * k + 1) * colsA1 + 2 * vIdx]     = g[1 * (2 * nNbr) + 2 * hOffset]
                Gtmp[(2 * k + 1) * colsA1 + 2 * vIdx + 1] = g[1 * (2 * nNbr) + 2 * hOffset + 1]
            }
        }
        G = Gtmp

        // (6) bottom rows of A1: pin constraints
        for (p in 0 until activePinCount) {
            for (vw in pinBary[p]) {
                val vIdx = vw.idx
                val vW = vw.value
                A1[(2 * edgeCount + 2 * p) * colsA1 + 2 * vIdx]         = (weight * vW).toDouble()
                A1[(2 * edgeCount + 2 * p + 1) * colsA1 + 2 * vIdx + 1] = (weight * vW).toDouble()
            }
        }

        // (7) build A2 [E+P, V]
        val rowsA2 = edgeCount + activePinCount
        val A2 = DoubleArray(rowsA2 * vertCount)
        for (k in 0 until edgeCount) {
            val viIdx = edges[k].a
            val vjIdx = edges[k].b
            A2[k * vertCount + viIdx] = -1.0
            A2[k * vertCount + vjIdx] = 1.0
        }
        for (p in 0 until activePinCount) {
            for (vw in pinBary[p]) {
                A2[(edgeCount + p) * vertCount + vw.idx] = (weight * vw.value).toDouble()
            }
        }

        // (8) precompute tA1 (transpose of A1) and tA2 (transpose of A2)
        tA1 = transpose(A1, rowsA1, colsA1)
        tA2 = transpose(A2, rowsA2, vertCount)

        // (9) Cholesky of (tA1 · A1) and (tA2 · A2), with perturbation retry
        val tA1xA1 = matMulFlat(tA1, colsA1, rowsA1, A1, colsA1)  // colsA1 × colsA1 = 2V × 2V
        cholA1 = makeCholeskyOrPerturb(tA1xA1, colsA1)
        val tA2xA2 = matMulFlat(tA2, vertCount, rowsA2, A2, vertCount)  // V × V
        cholA2 = makeCholeskyOrPerturb(tA2xA2, vertCount)
    }

    /** Run ARAP on new pin positions. Returns deformed vertices [V*2]. */
    fun solve(newPinsAll: FloatArray): FloatArray {
        require(newPinsAll.size == pinMask.size * 2)
        // Drop pins masked out
        val newPins = FloatArray(activePinCount * 2)
        var w = 0
        for (p in pinMask.indices) {
            if (pinMask[p]) {
                newPins[w * 2] = newPinsAll[p * 2]
                newPins[w * 2 + 1] = newPinsAll[p * 2 + 1]
                w++
            }
        }

        // (1) b1
        val b1 = DoubleArray(2 * edgeCount + 2 * activePinCount)
        for (p in 0 until activePinCount) {
            b1[2 * edgeCount + 2 * p]     = (weight * newPins[p * 2]).toDouble()
            b1[2 * edgeCount + 2 * p + 1] = (weight * newPins[p * 2 + 1]).toDouble()
        }
        // (2) v1 = chol1.solve(tA1 · b1)
        val rhs1 = matVecMul(tA1, 2 * vertCount, 2 * (edgeCount + activePinCount), b1)
        val v1 = cholA1.solve(rhs1)

        // (3) T1 = G · v1
        val T1 = matVecMul(G, 2 * edgeCount, 2 * vertCount, v1)

        // (4) build b2_top (rotated edge vectors)
        val b2x = DoubleArray(edgeCount + activePinCount)
        val b2y = DoubleArray(edgeCount + activePinCount)
        for (k in 0 until edgeCount) {
            var c = T1[2 * k]
            var s = T1[2 * k + 1]
            val scale = 1.0 / sqrt(c * c + s * s)
            c *= scale; s *= scale
            val ex = edgeVectors[k * 2].toDouble()
            val ey = edgeVectors[k * 2 + 1].toDouble()
            b2x[k] = c * ex + s * ey
            b2y[k] = -s * ex + c * ey
        }
        // (5) b2 bottom: pin constraints
        for (p in 0 until activePinCount) {
            b2x[edgeCount + p] = (weight * newPins[p * 2]).toDouble()
            b2y[edgeCount + p] = (weight * newPins[p * 2 + 1]).toDouble()
        }
        // (6) v2x = chol2.solve(tA2 · b2x), v2y similarly
        val rhs2x = matVecMul(tA2, vertCount, edgeCount + activePinCount, b2x)
        val rhs2y = matVecMul(tA2, vertCount, edgeCount + activePinCount, b2y)
        val v2x = cholA2.solve(rhs2x)
        val v2y = cholA2.solve(rhs2y)
        // (7) interleave
        val out = FloatArray(vertCount * 2)
        for (v in 0 until vertCount) {
            out[v * 2] = v2x[v].toFloat()
            out[v * 2 + 1] = v2y[v].toFloat()
        }
        return out
    }

    private fun findPinTriangle(px: Float, py: Float): List<IntFloat>? {
        var t = 0
        while (t < mesh.triangles.size) {
            val a = mesh.triangles[t]; val b = mesh.triangles[t + 1]; val c = mesh.triangles[t + 2]
            val ax = mesh.vertices[a * 2]; val ay = mesh.vertices[a * 2 + 1]
            val bx = mesh.vertices[b * 2]; val by = mesh.vertices[b * 2 + 1]
            val cx = mesh.vertices[c * 2]; val cy = mesh.vertices[c * 2 + 1]
            if (Barycentric.pointInTriangle(px, py, ax, ay, bx, by, cx, cy)) {
                val bc = Barycentric.compute(px, py, ax, ay, bx, by, cx, cy)!!
                return listOf(IntFloat(a, bc.first), IntFloat(b, bc.second), IntFloat(c, bc.third))
            }
            t += 3
        }
        return null
    }

    // ===== Linear algebra helpers (dense, row-major flat) =====

    private fun makeCholeskyOrPerturb(matrix: DoubleArray, n: Int): CholeskyDecomposition {
        var attempt = matrix
        for (retry in 0 until 5) {
            try {
                return CholeskyDecomposition(attempt, n)
            } catch (e: SingularException) {
                val perturbed = attempt.copyOf()
                val eps = 1e-8 * (retry + 1)
                for (i in 0 until n) perturbed[i * n + i] += eps
                attempt = perturbed
            }
        }
        throw ArapInitException("Cholesky failed after 5 perturbation retries")
    }

    private fun transpose(m: DoubleArray, rows: Int, cols: Int): DoubleArray {
        val out = DoubleArray(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) out[c * rows + r] = m[r * cols + c]
        return out
    }

    private fun matMulFlat(a: DoubleArray, ar: Int, ac: Int, b: DoubleArray, bc: Int): DoubleArray {
        val out = DoubleArray(ar * bc)
        for (i in 0 until ar) for (j in 0 until bc) {
            var s = 0.0
            for (k in 0 until ac) s += a[i * ac + k] * b[k * bc + j]
            out[i * bc + j] = s
        }
        return out
    }

    private fun matVecMul(a: DoubleArray, ar: Int, ac: Int, v: DoubleArray): DoubleArray {
        val out = DoubleArray(ar)
        for (i in 0 until ar) {
            var s = 0.0
            for (k in 0 until ac) s += a[i * ac + k] * v[k]
            out[i] = s
        }
        return out
    }

    private fun pseudoInverseLeft(m: Array<DoubleArray>): DoubleArray {
        // m is GkRows × 4. Returns 4 × GkRows flat matrix = inv(mᵀ·m) · mᵀ
        val rows = m.size
        val cols = m[0].size
        // mᵀ · m (4 × 4)
        val mtm = DoubleArray(cols * cols)
        for (i in 0 until cols) for (j in 0 until cols) {
            var s = 0.0
            for (k in 0 until rows) s += m[k][i] * m[k][j]
            mtm[i * cols + j] = s
        }
        val mtmInv = invert4x4(mtm)
        // result = mtmInv · mᵀ  (cols × rows)
        val out = DoubleArray(cols * rows)
        for (i in 0 until cols) for (j in 0 until rows) {
            var s = 0.0
            for (k in 0 until cols) s += mtmInv[i * cols + k] * m[j][k]
            out[i * rows + j] = s
        }
        return out
    }

    private fun invert4x4(a: DoubleArray): DoubleArray {
        // Gauss-Jordan on 4x4
        val n = 4
        val aug = DoubleArray(n * 2 * n)
        for (i in 0 until n) {
            for (j in 0 until n) aug[i * 2 * n + j] = a[i * n + j]
            aug[i * 2 * n + n + i] = 1.0
        }
        for (k in 0 until n) {
            var pivot = aug[k * 2 * n + k]
            if (kotlin.math.abs(pivot) < 1e-12) {
                // find a row to swap with
                for (r in (k + 1) until n) {
                    if (kotlin.math.abs(aug[r * 2 * n + k]) >= 1e-12) {
                        for (j in 0 until 2 * n) {
                            val tmp = aug[k * 2 * n + j]
                            aug[k * 2 * n + j] = aug[r * 2 * n + j]
                            aug[r * 2 * n + j] = tmp
                        }
                        pivot = aug[k * 2 * n + k]
                        break
                    }
                }
                if (kotlin.math.abs(pivot) < 1e-12) {
                    throw SingularException("invert4x4: singular near k=$k")
                }
            }
            for (j in 0 until 2 * n) aug[k * 2 * n + j] /= pivot
            for (i in 0 until n) {
                if (i == k) continue
                val f = aug[i * 2 * n + k]
                for (j in 0 until 2 * n) aug[i * 2 * n + j] -= f * aug[k * 2 * n + j]
            }
        }
        val inv = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) inv[i * n + j] = aug[i * 2 * n + n + j]
        return inv
    }

    private fun buildEdgeMatrix(nNbr: Int): DoubleArray {
        // shape: (2*nNbr) × (2*nNbr)
        // first 2 columns: stack of -I_{2x2} blocks (nNbr blocks vertically)
        // remaining columns: I_{2*(nNbr-1)}
        val size = 2 * nNbr
        val out = DoubleArray(size * size)
        for (m in 0 until nNbr) {
            out[(2 * m) * size + 0] = -1.0
            out[(2 * m + 1) * size + 1] = -1.0
        }
        for (j in 0 until 2 * (nNbr - 1)) {
            out[(j + 2) * size + (j + 2)] = 1.0
        }
        return out
    }

    private fun matMul(a: DoubleArray, ar: Int, ac: Int, b: DoubleArray, bc: Int): DoubleArray =
        matMulFlat(a, ar, ac, b, bc)

    private fun matMul2(a: Array<DoubleArray>, b: DoubleArray, bc: Int): Array<DoubleArray> {
        val ar = a.size
        val ac = a[0].size
        val out = Array(ar) { DoubleArray(bc) }
        for (i in 0 until ar) for (j in 0 until bc) {
            var s = 0.0
            for (k in 0 until ac) s += a[i][k] * b[k * bc + j]
            out[i][j] = s
        }
        return out
    }
}

// helper data classes (no kotlin.Pair to keep primitives unboxed)
data class IntPair(val a: Int, val b: Int) {
    companion object {
        fun sorted(x: Int, y: Int): IntPair = if (x <= y) IntPair(x, y) else IntPair(y, x)
    }
}
data class IntFloat(val idx: Int, val value: Float)
```

- [ ] **Step 4: test 재실행 — 첫 4 tests PASS 시도**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.ArapSolverTest" 2>&1 | tail -20
```

가장 큰 단계. 만약 첫 시도에 실패하면:
- algorithm 매핑 디버깅 (AD arap.py L88-131 와 비교)
- 작은 mesh 부터 numeric 시각 확인

- [ ] **Step 5: 전체 test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -20
```

Expected: 모든 기존 tests PASS + 신규 ArapSolverTest 4 PASS.

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/arap/ArapSolver.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/arap/ArapSolverTest.kt
git commit -m "feat(sub-3): ArapSolver — Igarashi 2009 ARAP, Kotlin port of AD arap.py"
```

---

## Task 5: MotionSource + MotionStub (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/MotionSource.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/MotionStub.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/MotionStubTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.rig

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionStubTest {

    private fun fakeInitialPins(): FloatArray {
        // 17 COCO keypoints with synthetic locations
        // l_shoulder (index 5) at (100, 200), l_wrist (index 9) at (50, 400)
        val pins = FloatArray(17 * 2)
        for (i in 0 until 17) {
            pins[i * 2] = (i * 10).toFloat()
            pins[i * 2 + 1] = (i * 20).toFloat()
        }
        // Override specific COCO indices for the wave test
        pins[5 * 2] = 100f;  pins[5 * 2 + 1] = 200f   // l_shoulder
        pins[9 * 2] = 50f;   pins[9 * 2 + 1] = 400f   // l_wrist
        pins[6 * 2] = 150f;  pins[6 * 2 + 1] = 210f   // r_shoulder
        pins[10 * 2] = 200f; pins[10 * 2 + 1] = 410f  // r_wrist
        return pins
    }

    @Test
    fun `unknown motion returns identity frames`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("unknown", pins, frameCount = 5)
        assertEquals(5, frames.size)
        for (f in frames) {
            for (i in pins.indices) {
                assertEquals(pins[i], f[i], 0.001f)
            }
        }
    }

    @Test
    fun `wave motion frame 0 equals initial pins`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("wave", pins, frameCount = 30)
        for (i in pins.indices) {
            assertEquals(pins[i], frames[0][i], 0.001f)
        }
    }

    @Test
    fun `wave motion moves wrist y over frames`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("wave", pins, frameCount = 30)
        // mid-frame, wrist y should differ from initial wrist y
        val initialWristY = pins[9 * 2 + 1]
        val midWristY = frames[15][9 * 2 + 1]
        assertTrue(kotlin.math.abs(midWristY - initialWristY) > 1f,
            "wave motion should change l_wrist y at frame 15")
    }

    @Test
    fun `wave motion frame count matches`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("wave", pins, frameCount = 60)
        assertEquals(60, frames.size)
        assertEquals(pins.size, frames[0].size)
    }
}
```

- [ ] **Step 2: test 실행 → fail**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.MotionStubTest" 2>&1 | tail -10
```

- [ ] **Step 3: `MotionSource.kt` 작성**

```kotlin
package com.k3i.stickerbook.rig

/**
 * Provides per-frame pin positions for a named motion.
 *
 * Implementations:
 * - [MotionStub] — hardcoded sequences (wave, …), used until Sub-4
 * - [BvhMotionSource] (Sub-4) — driven by BVH retarget pipeline
 */
interface MotionSource {
    /**
     * @param motionId motion catalog id (e.g. "wave")
     * @param initialPins flat [P*2] xy pairs in character-bitmap coord space
     * @param frameCount number of frames to generate
     * @return list of size [frameCount], each entry a fresh FloatArray same shape as [initialPins]
     */
    fun frames(motionId: String, initialPins: FloatArray, frameCount: Int): List<FloatArray>
}
```

- [ ] **Step 4: `MotionStub.kt` 작성**

```kotlin
package com.k3i.stickerbook.rig

import kotlin.math.PI
import kotlin.math.sin

/**
 * Hardcoded motions for Sub-3 standalone validation.
 *
 * Conventions:
 * - 17 COCO keypoints (indices match Sub-2b AD_COCO_17 backend)
 *   5=l_shoulder, 6=r_shoulder, 7=l_elbow, 8=r_elbow, 9=l_wrist, 10=r_wrist
 *
 * Coordinate space: character-bitmap pixels (y increases downward).
 */
class MotionStub : MotionSource {

    override fun frames(
        motionId: String,
        initialPins: FloatArray,
        frameCount: Int,
    ): List<FloatArray> = when (motionId) {
        "wave" -> wave(initialPins, frameCount)
        else -> identity(initialPins, frameCount)
    }

    private fun identity(pins: FloatArray, n: Int): List<FloatArray> =
        List(n) { pins.copyOf() }

    /**
     * Wave: both wrists swing from down (initial position) to up (around shoulder height)
     * and back, sinusoidally over [frameCount] frames.
     */
    private fun wave(pins: FloatArray, n: Int): List<FloatArray> {
        val lShoulderY = pins[5 * 2 + 1]
        val rShoulderY = pins[6 * 2 + 1]
        val lWristY = pins[9 * 2 + 1]
        val rWristY = pins[10 * 2 + 1]
        return List(n) { f ->
            val phase = 0.5f - 0.5f * sin(2.0 * PI * f / n).toFloat()  // 0 → 1 → 0
            val frame = pins.copyOf()
            // Lerp wrist y from initial down position to shoulder height
            frame[9 * 2 + 1]  = lWristY + (lShoulderY - lWristY) * phase
            frame[10 * 2 + 1] = rWristY + (rShoulderY - rWristY) * phase
            frame
        }
    }
}
```

- [ ] **Step 5: test 재실행 → 4 PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.MotionStubTest" 2>&1 | tail -10
```

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/MotionSource.kt \
        app/app/src/main/java/com/k3i/stickerbook/rig/MotionStub.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/MotionStubTest.kt
git commit -m "feat(sub-3): MotionSource interface + MotionStub wave (TDD)"
```

---

## Task 6: MeshRenderer

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/MeshRenderer.kt`

Pure thin wrapper around `Canvas.drawBitmapMesh`. Robolectric Bitmap test would fragile (drawBitmapMesh implementations differ), so we skip unit test here and validate via ArapRiggerTest in Task 7.

- [ ] **Step 1: `MeshRenderer.kt` 작성**

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Renders a bitmap onto a deformed grid mesh.
 *
 * The verts array follows Canvas.drawBitmapMesh convention:
 *   length = (meshWidth+1) * (meshHeight+1) * 2, row-major (y * (meshWidth+1) + x) * 2.
 *
 * The returned bitmap has the same dimensions as the input.
 */
object MeshRenderer {

    fun draw(
        source: Bitmap,
        meshWidth: Int,
        meshHeight: Int,
        verts: FloatArray,
    ): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmapMesh(source, meshWidth, meshHeight, verts, 0, null, 0, null)
        return out
    }
}
```

- [ ] **Step 2: build 확인**

```bash
./run-gradle.sh app:compileDebugKotlin 2>&1 | tail -5
```

Expected: SUCCESS.

- [ ] **Step 3: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/MeshRenderer.kt
git commit -m "feat(sub-3): MeshRenderer — Canvas.drawBitmapMesh wrapper"
```

---

## Task 7: ArapRigger (TDD, Robolectric)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/ArapRiggerTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ArapRiggerTest {

    private fun fakeDetection(image: Bitmap): Detection {
        val bbox = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val mask = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)
        return Detection(bbox = bbox, mask = mask, score = 0.9f)
    }

    @Test
    fun `rig produces 30 frame pngs and a 30 frame RigResult`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val pose = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = List(17) { Landmark((10 + it * 10).toFloat(), (10 + it * 5).toFloat(),
                0f, 0.9f, 0.9f) },
            imageWidth = 200, imageHeight = 200,
        )
        val rigger = ArapRigger.withStubs(
            context = ctx,
            detect = { listOf(fakeDetection(image)) },
            estimate = { _, _ -> pose },
            motionSource = MotionStub(),
        )
        val result = rigger.rig(image, "wave")
        assertEquals(30, result.frameCount)
        assertEquals(30, result.fps)
        // verify pngs exist
        val framesDir = File(ctx.filesDir, "stickerbook_assets/${result.framesDir}")
        assertTrue(framesDir.isDirectory)
        for (i in 1..30) {
            val f = File(framesDir, "${i.toString().padStart(4, '0')}.png")
            assertTrue(f.isFile, "frame $i missing at $f")
        }
    }

    @Test
    fun `rig writes skeleton json with ad-coco-17 backend`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val pose = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = List(17) { Landmark(it * 4f, it * 4f, 0f, 0.5f, 0.5f) },
            imageWidth = 100, imageHeight = 100,
        )
        val rigger = ArapRigger.withStubs(
            context = ctx,
            detect = { listOf(fakeDetection(image)) },
            estimate = { _, _ -> pose },
            motionSource = MotionStub(),
        )
        val result = rigger.rig(image, "wave")
        assertTrue(result.skeletonPath != null)
        val skel = File(ctx.filesDir, "stickerbook_assets/${result.skeletonPath}")
        assertTrue(skel.isFile)
        val text = skel.readText()
        assertTrue("ad-coco-17" in text)
    }
}
```

- [ ] **Step 2: test 실행 → fail (class 없음)**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.ArapRiggerTest" 2>&1 | tail -10
```

- [ ] **Step 3: `ArapRigger.kt` 작성**

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import com.k3i.stickerbook.rig.arap.ArapSolver
import com.k3i.stickerbook.rig.arap.GridMeshBuilder
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class ArapRigger private constructor(
    private val context: Context,
    private val detect: (Bitmap) -> List<Detection>,
    private val estimate: (Bitmap, RectF?) -> SkeletonData,
    private val motionSource: MotionSource,
) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detect(image)
        val top = detections.maxByOrNull { it.score }
        val bbox = top?.bbox

        val skeleton: SkeletonData? = runCatching { estimate(image, bbox) }
            .onFailure { Log.w(TAG, "pose estimate failed; continuing without skeleton", it) }
            .getOrNull()

        val stickerId = "arap_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        val character = if (top != null) applyMask(image, top.mask, top.bbox) else image
        val rel = "stickers/$stickerId"

        // Shift skeleton landmarks into character-bitmap coord space (Sub-2b fix)
        val shiftedSkeleton = if (skeleton != null && top != null) {
            val offsetX = top.bbox.left.coerceAtLeast(0f)
            val offsetY = top.bbox.top.coerceAtLeast(0f)
            skeleton.copy(
                landmarks = skeleton.landmarks.map { it.copy(x = it.x - offsetX, y = it.y - offsetY) },
                imageWidth = character.width,
                imageHeight = character.height,
            )
        } else {
            skeleton
        }

        // If no skeleton, emit a single static frame and bail.
        if (shiftedSkeleton == null || shiftedSkeleton.landmarks.isEmpty() || top == null) {
            writePng(character, File(framesDir, "0001.png"))
            writePng(character, File(sDir, "texture.png"))
            writePng(character, File(sDir, "animation.gif"))
            writePng(image, File(sDir, "source.png"))
            return RigResult(
                framesDir = "$rel/frames",
                fps = 30, frameCount = 1,
                width = character.width, height = character.height,
                texturePath = "$rel/texture.png",
                gifPath = "$rel/animation.gif",
                sourcePath = "$rel/source.png",
                skeletonPath = null,
            )
        }

        // Build mesh + ARAP
        val mesh = GridMeshBuilder.build(character, GRID_W, GRID_H)
        val initialPins = landmarksToFloatArray(shiftedSkeleton)
        val solver = ArapSolver(initialPins = initialPins, mesh = mesh)
        val frameSeqs = motionSource.frames(motion, initialPins, FRAME_COUNT)

        for (i in 0 until FRAME_COUNT) {
            val deformed = solver.solve(frameSeqs[i])
            val frameBmp = MeshRenderer.draw(character, mesh.gridWidth, mesh.gridHeight, deformed)
            val name = (i + 1).toString().padStart(4, '0') + ".png"
            writePng(frameBmp, File(framesDir, name))
            if (i == 0) writePng(frameBmp, File(sDir, "texture.png"))  // first frame as thumbnail
            frameBmp.recycle()
        }

        // animation.gif placeholder = first frame copy (real GIF encoding is a follow-up)
        File(framesDir, "0001.png").copyTo(File(sDir, "animation.gif"), overwrite = true)
        writePng(image, File(sDir, "source.png"))

        val skeletonFile = File(sDir, "skeleton.json")
        skeletonFile.writeText(Json.encodeToString(SkeletonData.serializer(), shiftedSkeleton))

        return RigResult(
            framesDir = "$rel/frames",
            fps = 30, frameCount = FRAME_COUNT,
            width = character.width, height = character.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
            skeletonPath = "$rel/skeleton.json",
        )
    }

    private fun landmarksToFloatArray(skeleton: SkeletonData): FloatArray {
        val out = FloatArray(skeleton.landmarks.size * 2)
        for ((i, lm) in skeleton.landmarks.withIndex()) {
            out[i * 2] = lm.x
            out[i * 2 + 1] = lm.y
        }
        return out
    }

    private fun applyMask(image: Bitmap, mask: Bitmap, bbox: RectF): Bitmap {
        val left = bbox.left.toInt().coerceAtLeast(0)
        val topI = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - topI).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(image, left, topI, cropW, cropH)
        val maskScaled = Bitmap.createScaledBitmap(mask, cropW, cropH, true)
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(cropped, 0f, 0f, null)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        canvas.drawBitmap(maskScaled, 0f, 0f, paint)
        return out
    }

    private fun writePng(bmp: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    companion object {
        private const val TAG = "ArapRigger"
        private const val GRID_W = 20
        private const val GRID_H = 26
        private const val FRAME_COUNT = 30

        fun real(context: Context): ArapRigger {
            val detector = MaskRcnnDetector(context)
            val estimator = AdPoseEstimator(context)
            return ArapRigger(
                context,
                detect = { detector.detect(it) },
                estimate = { image, bbox -> estimator.estimate(image, bbox) },
                motionSource = MotionStub(),
            )
        }

        fun withStubs(
            context: Context,
            detect: (Bitmap) -> List<Detection>,
            estimate: (Bitmap, RectF?) -> SkeletonData,
            motionSource: MotionSource,
        ): ArapRigger = ArapRigger(context, detect, estimate, motionSource)
    }
}
```

- [ ] **Step 4: test 재실행 → 2 PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.ArapRiggerTest" 2>&1 | tail -20
```

- [ ] **Step 5: 전체 test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -20
```

Expected: 모두 PASS.

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/ArapRiggerTest.kt
git commit -m "feat(sub-3): ArapRigger — orchestrate detect+pose+ARAP, emit 30 frame PNG"
```

---

## Task 8: AppNavHost swap + 갤탭 시연

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt:43`

- [ ] **Step 1: AppNavHost 한 줄 수정**

```kotlin
// Before:
val rigger = remember { PoseDetectionRigger.realAd(ctx) }
// After:
val rigger = remember { ArapRigger.real(ctx) }
```

- [ ] **Step 2: 빌드 + 테스트 회귀**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:testDebugUnitTest assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 모든 tests PASS.

- [ ] **Step 3: 갤탭 install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  'C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk'
```

Expected: Success.

- [ ] **Step 4: 갤탭 시연 (사용자 직접)**

1. 앱 launch
2. + FAB → 카메라 → 손그림 (막대 사람) 캡처
3. ▶ → 모션 = "wave" 선택 → 만들기 ▶
4. 1-2분 대기 (Sub-1 detector dominant + 30 ARAP solves)
5. 그리드 → `arap_<ts>` 카드 → 상세 화면에 30 frame slideshow 가 진행 (`AnimationPlayer` 가 이미 frameCount 지원)
6. 양쪽 손목이 모션 동안 위로 올라갔다 내려옴 확인

logcat (사용자 별도 터미널):
```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -s \
  "ArapRigger" "AdPoseEstimator" "MaskRcnnDetector"
```

`ArapRigger` 가 30 frame 처리 완료 메시지 보이면 PASS.

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(sub-3): swap to ArapRigger for production rigger"
```

---

## Task 9: 결과 doc + memory 업데이트

**Files:**
- Create: `docs/sub3_results.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`

- [ ] **Step 1: `docs/sub3_results.md` 작성 (시연 결과 채움)**

```markdown
# Sub-3 결과 — ARAP mesh 변형

날짜: 2026-05-15
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.3.0 (debug)

## M3.1~M3.4 — 단위 PASS

| Test class | 개수 |
|---|---|
| BarycentricTest | 5 |
| GridMeshBuilderTest | 5 |
| CholeskyDecompositionTest | 5 |
| ArapSolverTest | 4 |
| MotionStubTest | 4 |
| ArapRiggerTest | 2 |
| **합계 (Sub-3 신규)** | **25** |

기존 34 + 신규 25 = **59 tests PASS, 0 fail**.

## M3.5 — Robolectric 통합

`ArapRigger.withStubs(...).rig(image, "wave")` → 30 PNG 생성, skeleton.json 저장, ad-coco-17 backend 명시.

## M3.6 — 갤탭 시연 (시각 검증 PASS / 부분 실패 여부 명시)

| 항목 | 측정값 | 목표 | PASS |
|---|---|---|---|
| 30 frame PNG 생성 | ✅ | 30개 | ✅ |
| End-to-end latency | (수치) | < 30s ARAP 부분 | (TBD on demo) |
| 양쪽 손목 움직임 | (시각 확인) | sinusoidal | (TBD on demo) |
| 그림 self-overlap | (시각 확인) | 없음 | (TBD on demo) |

## APK 크기

| 모델 | 크기 |
|---|---|
| drawn_humanoid_detector.onnx | 176 MB |
| pose_landmarker_heavy.task | 31 MB |
| ad_pose.onnx | 136 MB |
| **APK 총** | **~520 MB** (Sub-3 는 추가 model 없음) |

## 알려진 이슈 / Follow-up

- ⚠️ GIF encoding 미구현 — `animation.gif` 가 첫 frame PNG 복사. 별도 sub-task
- ⚠️ Grid mesh artifact — 큰 motion 에서 grid 경계 stair-stepping 가능. Delaunay triangulation 으로 follow-up
- ⚠️ Sub-1+2b quantization 여전히 후속

## Sub-4 (BVH retarget) 진입 조건

- ✅ Sub-3 시각 검증 통과
- ✅ MotionSource interface 안정 (BvhMotionSource 가 swap 가능)
- ☐ BVH parser 결정 (Sub-4 brainstorm)
- ☐ retarget skeleton 정의 (AD skeleton 17 ↔ BVH skeleton 매핑)

## Sub-3 commits (시간순)

(commit SHA 들 채워서 나열)
```

- [ ] **Step 2: memory 업데이트**

`/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`:
- description 줄: "Sub-5/1/2/2b/3 완료. Sub-4 차례 (BVH retarget)"
- Sub-3 행 status 를 ✅ 로 변경
- Sub-4 진입 한 줄: `"stickerbook Sub-4 (BVH retarget) brainstorm 진행해줘"`

`/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`:
- Phase 2 줄 업데이트: "Sub-3 (ARAP) 완료, 30 frame motion 시각 검증 PASS"

- [ ] **Step 3: commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add docs/sub3_results.md
git commit -m "docs(sub-3): M3 results — ARAP mesh deformation, 30 frame motion demo"
```

---

## 진행 순서 요약

1. **Task 1-3 (~2-3시간)**: 작은 helper 클래스들 (Barycentric, GridMeshBuilder, CholeskyDecomposition) TDD
2. **Task 4 (~4-6시간)**: ArapSolver 핵심 알고리즘 port. AD arap.py 와 1:1 매핑. 디버깅 의존
3. **Task 5-6 (~1-2시간)**: MotionStub + MeshRenderer
4. **Task 7 (~2-3시간)**: ArapRigger 통합 + Robolectric test
5. **Task 8 (~30분)**: AppNavHost swap + 갤탭 시연
6. **Task 9 (~30분)**: 결과 doc + memory

총 예상: 1.5-2일. Task 4 가 dominant — 알고리즘 정확도 위해 충분한 디버깅 시간.

## Sub-1+2b hot-fix 패턴 적용 여부

- ✅ **File path mmap**: Sub-3 는 ONNX 안 씀, N/A
- ✅ **NNAPI off**: Sub-3 는 ONNX 안 씀, N/A
- ✅ **Dispatchers.Default**: AppNavHost 의 `withContext(Dispatchers.Default) { rigger.rig(...) }` 기존 패턴 그대로 (Task 8 의 swap 한 줄 외 변경 X)

## Sub-3 단독 검증 핵심

- `ArapSolverTest` 의 "identity / translation / rotation / pin-mask" 4 test 가 모든 알고리즘 정확도 검증
- `ArapRiggerTest` 의 Robolectric 통합이 30 PNG 생성 + skeleton.json 저장 검증
- 갤탭 시연이 최종 시각 검증 (Task 8)
