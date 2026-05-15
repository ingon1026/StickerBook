# Sub-3 Design — ARAP mesh 변형 (캐릭터 모션 적용)

> 작성일: 2026-05-15
> Phase 2 의 네 번째 sub-project (Sub-5/1/2/2b 완료 후)
> 마스터 spec: `2026-05-14-phase2-master-design.md`
> 선행: `2026-05-14-sub2-mediapipe-pose-design.md`, `2026-05-15-sub2b-ad-pose-onnx-design.md`
> 사용자 결정: Sub-3 단독 검증 (stub motion) + Kotlin pure ARAP solver + grid mesh + ArapRigger 신규 클래스

---

## 1. 목표 / 책임 / 비책임

### 책임

- Sub-1 detector 의 mask Bitmap → 2D triangle mesh (grid mesh, 의존성 0)
- Sub-2b 의 17 COCO keypoints → mesh control pins
- Kotlin pure ARAP solver (Igarashi 2009) — dense Cholesky factor 한 번 + 매 frame 빠른 substitution
- Stub motion: hardcoded `Map<String, List<List<Landmark>>>` (motion ID → 17 keypoint 의 N frame sequence). Sub-4 BVH 로 swap 가능한 interface 보존
- Per-frame rendering: 변형된 vertex → `Canvas.drawBitmapMesh()` → PNG. N=30 frames @ 30fps
- `ArapRigger : CharacterRigger` 신규 클래스, AppNavHost `realAd` 자리에 swap
- 갤탭에서 motion 적용된 N-frame 시퀀스가 그림 보존 + 관절 따라 움직임 검증

### 비책임

- BVH parser / retarget (Sub-4)
- GIF encoding (Sub-3 follow-up. 일단 PNG 시퀀스만)
- 라이브 streaming (캡처 시 1회만)
- ARAP 알고리즘 자체 개선 (Igarashi 2009 그대로)
- mesh quality 개선 (grid mesh 그대로. 부족 시 Sub-3 별도 follow-up 으로 Delaunay)
- frame slideshow UI 처리 (현재 상세 화면이 frameCount>1 처리하는지 M3.5 직전 확인)

### 최소 PASS 기준

1. ArapRigger 가 30 frames PNG + frameCount=30 의 RigResult 생성
2. 캐릭터의 머리/팔/다리가 motion 따라 합리적으로 움직임 (rigid translation 이 아니라 mesh deformation 일관)
3. 그림 self-overlap 없는 정도의 deformation
4. 갤탭에서 30 frames 생성 < 30초 (Sub-1 dominant 1-2분 의 timing 안에)

---

## 2. 컨텍스트 — AD ARAP 의 실체

### 원본

`/home/ingon/AR_book/AnimatedDrawings/animated_drawings/model/arap.py` (329 lines)

### 알고리즘

Takeo Igarashi & Yuki Igarashi, "Implementing As-Rigid-As-Possible Shape Manipulation and Surface Flattening" (2009). 2D mesh deformation. control point (pins) 의 새 위치 입력 → mesh 의 모든 vertex 의 새 위치 출력. translation+rotation 자유, scaling 만 패널티.

### `__init__` 핵심 수식

- 입력: `pins_xy [P,2]`, `triangles [T,3]`, `vertices [V,2]`, `weight w=1000`
- precompute:
  - edge 의 vertex 쌍 (sorted+dedupe) `e_v_idxs`
  - edge vector `edge_vectors[k] = v_j - v_i`
  - 각 pin 의 barycentric coordinate (어느 triangle, 그 3 vertex 의 weight)
  - 행렬 `A1` `[2(E+P), 2V]` — edge rotation 자유 + pin constraint
  - 행렬 `A2` `[E+P, V]` — edge scale 패널티 + pin constraint
  - cache: `tA1xA1 = A1ᵀA1`, `tA2xA2 = A2ᵀA2`, `G` (edge rotation 추출용)
  - singular 방지: `det == 0` 이면 `+= 1e-8 · I`

### `solve(pins_xy_new)` 흐름

1. `b1 = [0,…,0, w·pins.flatten()]`
2. **1차 solve**: `v1 = spsolve(tA1xA1, tA1·b1)` — rotation+scale 자유
3. `T1 = G · v1` — 각 edge 의 회전 행렬 추출
4. 각 edge 의 원본 vector 를 그 회전만큼 돌려 `b2_top[k] = [c·ex+s·ey, -s·ex+c·ey]`
5. `b2 = [b2_top; w·pins]`
6. **2차 solve** (x, y 분리): `v2x = spsolve(tA2xA2, tA2·b2x)`, `v2y` 같음 — scale 패널티
7. return `(v2x, v2y)` stack `[V,2]`

### scipy → Kotlin 매핑

| scipy 호출 | Kotlin pure 대체 |
|---|---|
| `sp.csr_matrix` + `spsolve` | **Dense Cholesky** (tAxA 는 SPD) — V=567 mesh 의 `tA1xA1` 가 1134×1134, 5MB. 큰 부담 X |
| `np.linalg.det` 체크 | Cholesky factor 시 `sqrt(negative)` catch → perturbation |
| `np.linalg.inv` (G_k 의 작은 행렬) | 작은 행렬 (6×4 등) dense Gauss-Jordan |
| numpy element-wise | Kotlin `DoubleArray` row-major flat |

### 메모리 / 성능 추정 (gridW=20, gridH=26, V=567)

- `tA1xA1` 1134×1134 double = 10MB
- `tA2xA2` 567×567 double = 2.5MB
- Cholesky factor O(n³/3) ≈ 0.5GFLOP one-time, **~수백 ms 갤탭**
- Forward/back substitution O(n²) per solve ≈ 1.3MFLOP, **<5ms per frame**
- 30 frame × 2 solve × 5ms = **300ms inference** (전체 캐릭터 모션 변형). 충분히 real-time

---

## 3. 모듈 분해 + 데이터 계약

### 모듈 구조

```
rig/
├── ArapRigger.kt              (신규) - CharacterRigger 구현, orchestration
├── MotionSource.kt            (신규) - interface
├── MotionStub.kt              (신규) - hardcoded motion sequences ("wave", "dance", ...)
├── MeshRenderer.kt            (신규) - Canvas.drawBitmapMesh wrapper
│
└── (existing) PoseDetectionRigger, MaskRcnnDetector, AdPoseEstimator, ...

rig/arap/                       (신규 sub-package)
├── Mesh2D.kt                  (신규) - data class
├── GridMeshBuilder.kt         (신규) - mask Bitmap → Mesh2D
├── Barycentric.kt             (신규) - point-in-triangle + barycentric coords
├── CholeskyDecomposition.kt   (신규) - dense SPD Cholesky + cache
└── ArapSolver.kt              (신규) - Igarashi 2009 main class
```

### 데이터 계약

```kotlin
// rig/arap/Mesh2D.kt
data class Mesh2D(
    val vertices: FloatArray,    // [V*2] xy pairs, row-major: y*(gridW+1)+x
    val triangles: IntArray,     // [T*3] vertex id triples, T = 2*gridW*gridH
    val gridWidth: Int,          // for drawBitmapMesh
    val gridHeight: Int,
)

// rig/MotionSource.kt
interface MotionSource {
    /** Absolute pin positions per frame in character-bitmap coord space.
     *  Returns List of size frameCount, each FloatArray same length as initialPins. */
    fun frames(motionId: String, initialPins: FloatArray, frameCount: Int): List<FloatArray>
}

// rig/arap/ArapSolver.kt
class ArapSolver(
    initialPins: FloatArray,     // [P*2]
    val mesh: Mesh2D,
    val weight: Float = 1000f,
) {
    /** Returns deformed vertex positions [V*2]. */
    fun solve(newPins: FloatArray): FloatArray
}
```

### Flow (`ArapRigger.rig`)

```
1. detection = detector.detect(image)            // Sub-1
2. top = detection.first
3. pose = estimator.estimate(image, top.bbox)    // Sub-2b, 17 image-space landmarks
4. character = applyMask(image, top.mask, top.bbox)
5. // Sub-2b fix: keypoints 를 character-bitmap 좌표계로 평행이동
   pinsChar = pose.landmarks - (top.bbox.left, top.bbox.top)
6. mesh = GridMeshBuilder.build(top.mask, gridW=20, gridH=26)
7. arap = ArapSolver(initialPins=pinsChar, mesh)  // precompute Cholesky
8. frameSeqs = motionSource.frames(motion, pinsChar, frameCount=30)
9. for i in 0..29:
     deformedVerts = arap.solve(frameSeqs[i])    // sparse back-sub
     frameBmp = MeshRenderer.draw(character, mesh.gridWidth, mesh.gridHeight, deformedVerts)
     writePng(frameBmp, framesDir/"${(i+1).pad(4)}.png")
10. return RigResult(frameCount=30, fps=30, ...)
```

### 좌표계 정합

- **mesh space = character bitmap space** (cropW × cropH, mask 적용된 작은 bitmap)
- **pins space = mesh space** (keypoints 도 character space 로 평행이동 후 ARAP 입력)
- `Canvas.drawBitmapMesh(character, gridW, gridH, deformedVerts, ...)` — 정확히 grid mesh 만 받음

---

## 4. ARAP Solver Kotlin 구현 가이드

### 단순화: Sparse 안 씀 → Dense Cholesky

- Mesh V=567, tA1xA1 1134×1134 = 5MB ≤ heap 부담 작음
- Sparse 의 CSR + symbolic factorization + fill-in 복잡도 면제
- Cholesky O(n³/3) 한 번 ~수백 ms (갤탭), substitution O(n²) <5ms per frame

### CholeskyDecomposition

```kotlin
// rig/arap/CholeskyDecomposition.kt
class CholeskyDecomposition(matrix: DoubleArray, val n: Int) {
    private val L: DoubleArray  // lower triangular, row-major flat

    init {
        L = factor(matrix)  // throws SingularException if not SPD
    }

    /** Solve A·x = b via forward then backward substitution. */
    fun solve(b: DoubleArray): DoubleArray {
        // forward: L·y = b
        // backward: Lᵀ·x = y
    }

    private fun factor(a: DoubleArray): DoubleArray {
        // for k in 0..n-1:
        //   L[k,k] = sqrt(a[k,k] - sum(L[k,j]^2 for j<k))   // throws if negative
        //   L[i,k] = (a[i,k] - sum(L[i,j]*L[k,j] for j<k)) / L[k,k]
    }
}

class SingularException : RuntimeException()
```

### ArapSolver

```kotlin
// rig/arap/ArapSolver.kt
class ArapSolver(
    initialPins: FloatArray,   // [P*2] (char-space)
    val mesh: Mesh2D,
    val weight: Float = 1000f,
) {
    private val edges: List<Pair<Int, Int>>
    private val edgeVectors: FloatArray
    private val pinBary: List<List<Pair<Int, Float>>>  // each pin: 3 (vertexId, w)
    private val pinMask: BooleanArray
    private val G: DoubleArray                          // [2E, 2V] dense
    private val cholA1: CholeskyDecomposition           // factor of (A1^T·A1)
    private val cholA2: CholeskyDecomposition           // factor of (A2^T·A2)
    private val tA1: DoubleArray                        // [2V, 2(E+P)]
    private val tA2: DoubleArray                        // [V, E+P]

    init {
        // (1) Build edges (sorted+dedupe from triangles)
        // (2) Compute edge_vectors[k] = vertex[v_j] - vertex[v_i]
        // (3) For each pin: find containing triangle via Barycentric → pinBary[p]
        //     pin outside any triangle → pinMask[p]=false
        // (4) Build A1 (AD arap.py L88-131) — edge rotation 자유 + pin constraint
        // (5) Build G (AD arap.py L122-125) — edge rotation extractor
        // (6) Build A2 (AD arap.py L133-143) — edge scale 패널티 + pin constraint
        // (7) tA1·A1 → Cholesky (catch SingularException → perturb +1e-8·I, retry 5회)
        // (8) tA2·A2 → Cholesky (same)
    }

    fun solve(newPins: FloatArray): FloatArray {
        // (1) b1 = concat(zeros[2E], weight*newPins[mask])
        // (2) v1 = cholA1.solve(tA1·b1)
        // (3) T1 = G·v1
        // (4) Edge rotation: for each k, extract (c, s) from T1[2k:2k+2], normalize,
        //     rotate edge_vectors[k] → b2_top[k]
        // (5) b2 = concat(b2_top, weight*newPins[mask])
        // (6) v2x = cholA2.solve(tA2·b2.x)
        // (7) v2y = cholA2.solve(tA2·b2.y)
        // (8) return interleave(v2x, v2y) [V*2]
    }
}
```

### Singular Handling

- `CholeskyDecomposition.factor()` 가 `sqrt(negative)` 또는 0 division 만나면 `SingularException`
- `ArapSolver.init()` 에서 catch → `tAxA += 1e-8·I` → 재시도 최대 5번
- 5번 실패 시 `ArapInitException` throw (실제 grid mesh 에서 SPD 거의 항상 만족)

### 단위 테스트

| 클래스 | 단위 테스트 |
|---|---|
| `Barycentric` | inside/edge/outside, known triangle |
| `CholeskyDecomposition` | 4x4 / 16x16 SPD, A·x=b 정해 vs solve 일치 (ε<1e-6), singular catch |
| `GridMeshBuilder` | mask=전체 1 → grid V/T 개수 정확, mask=일부 0 → boundary 처리 |
| `ArapSolver` | 1 quad mesh + 1 pin: identity (solve(initialPins)≈vertices), translation, 90° rotation |
| `MotionStub` | "wave" frame 0 = initialPins, frame 15 = wrist y 변화 |

---

## 5. 단계적 PoC + 리스크 + 마일스톤

### 마일스톤 체크리스트

- [ ] **M3.1** `Barycentric` + `Mesh2D` + `GridMeshBuilder` (TDD)
- [ ] **M3.2** `CholeskyDecomposition` (TDD)
- [ ] **M3.3** `ArapSolver` init + solve (TDD)
- [ ] **M3.4** `MotionSource` + `MotionStub` (TDD)
- [ ] **M3.5** `MeshRenderer` + `ArapRigger` 통합 (Robolectric)
- [ ] **M3.6** AppNavHost swap + 갤탭 시연 (시각 검증)
- [ ] **M3.7** 결과 doc + memory 업데이트

### 리스크 매트릭스

| # | 리스크 | 영향 | 대응 |
|---|---|---|---|
| R1 | Cholesky factor 갤탭에서 너무 느림 (>1초) | M3.5 막힘 | dense → sparse 또는 mesh 작게 (gridW=10) |
| R2 | ARAP solve 결과가 self-overlap / 비정상 deformation | M3.6 시각 검증 실패 | mesh density ↑, motion keypoint delta 작게, weight 조정 (1000→5000) |
| R3 | mask 작아서 pin 다수가 mask 밖 | activePinCount ↓ → ARAP 부정확 | Sub-1 의 mask 한계 자체. scope 외 |
| R4 | `Canvas.drawBitmapMesh` 가 grid 변형 시 artifact | rendering 깨짐 | 변형 frame 별 PNG 확인. 심하면 mesh dense |
| R5 | 30 frame × ARAP solve = 갤탭 latency 추가 | UX 저하 | `Dispatchers.Default` 로 background, spinner 유지 |
| R6 | OOM (30 frames × cropW × cropH × 4) | crash | 각 frame 즉시 PNG 저장 후 Bitmap.recycle(). frames List<Bitmap> 보관 X |
| R7 | mesh boundary vertex (mask 밖) 도 ARAP 가 변형 → 잡소리 | overlay 잡소리 | character bitmap 자체가 mask 적용된 PNG (mask 밖 = 투명) 라 보임상 OK |

### 검증

**Unit**: 위 §4 의 5 클래스 각각 unit test, 핵심 = ArapSolver "identity" test

**통합 (M3.5)**: Robolectric Bitmap 으로 mock detector/estimator/motion 주입 → 30 PNG 생성 확인

**갤탭 시연 (M3.6)**:
- adb pull 로 frames/0001.png ~ 0030.png 검사
- 또는 갤탭 상세 화면 slideshow (현재 UI 가 frameCount>1 처리하는지 M3.5 직전 확인)

### Sub-3 진입 조건 (이미 충족)

- ✅ Sub-2b 완료 — 17 COCO keypoints 일관
- ✅ Sub-1 mask 일관 (그림에 따라 한계 있음, scope 외)
- ✅ AD `arap.py` 원본 참조 가능

### Sub-3 후속

- **Sub-4 (BVH retarget)**: `MotionStub` 자리에 `BvhMotionSource` swap (interface 호환)
- **Sub-3 follow-up**: GIF encoding, frame count 60+, mesh density up, Delaunay triangulation
- **상세 화면 frame slideshow UI** (M3.5 직전 확인 필요)
