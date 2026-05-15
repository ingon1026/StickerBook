# Sub-3 결과 — ARAP mesh 변형

날짜: 2026-05-15
대상: Galaxy Tab S9 FE+ (SM-X610), device id R54X4008CET
앱 버전: 0.3.0 (debug)

## M3.1~M3.4 — 단위 PASS

| Test class | 개수 | 비고 |
|---|---|---|
| BarycentricTest | 5 | inside/edge/outside, vertex round-trip |
| GridMeshBuilderTest | 5 | vertex count, triangle count, row-major ordering |
| CholeskyDecompositionTest | 5 | 2x2/3x3 SPD round-trip, singular catch |
| ArapSolverTest | 4 | identity (2 pins), translation, pin constraint, pin-mask |
| MotionStubTest | 4 | identity/wave 분기, frame 0 = initial, midframe 변형 |
| ArapRiggerTest | 2 | 30 PNG 생성, ad-coco-17 skeleton.json 저장 |
| **합계 (Sub-3 신규)** | **25** | |

기존 34 + 신규 25 = **59 tests PASS, 0 fail, 1 skipped**.

## M3.5 — Robolectric 통합

`ArapRigger.withStubs(...).rig(image, "wave")` → 30 PNG 생성, skeleton.json 저장, `backend: "ad-coco-17"` 명시. RigResult.frameCount = 30, fps = 30.

## M3.6 — 갤탭 시연 (시각 검증 PASS)

### 결과 (sticker `arap_1778820512338`)

| 항목 | 측정값 | 목표 | PASS |
|---|---|---|---|
| 30 frame PNG 생성 | ✅ 0001~0030.png 다 존재 | 30개 | ✅ |
| 17 keypoint 검출 | ✅ max score 0.93 | ≥ 1개 vis > 0.3 | ✅ |
| End-to-end latency | ~1-2분 (Sub-1 dominant) | < 30s ARAP 부분 | (Sub-1 quantization 후속) |
| frame 0 vs frame 15 변형 | ✅ md5 다름 + 시각 확인 — 양손 위 (V 포즈) → 어깨 위치 | sinusoidal | ✅ |
| 그림 self-overlap | ✅ 없음 — ARAP rigid 보존 | 없음 | ✅ |
| 스켈레톤 overlay | ❌ 없음 (의도된 동작) | 의도 (단순 motion 시각 보존) | ✅ (설명 참조) |

### 시각 검증 (캐릭터 막대 사람, "V 포즈" 손그림)

| Frame | 시각 |
|---|---|
| 0001 | 그림 그대로, 양손 V 포즈로 위 |
| 0015 | 양손 어깨 위치까지 내려옴 (wave 중간 단계) |
| 0030 | 다시 0001 위치로 복귀 (sinusoidal 끝) |

ARAP 가 손목/팔 영역의 mesh 만 변형, 캐릭터의 얼굴/몸통/다리는 보존. As-Rigid-As-Possible (rotation 자유, scale 패널티) 알고리즘 의도대로 작동.

## 스켈레톤 overlay 없는 이유

`ArapRigger` 는 일부러 `SkeletonOverlay` 호출 안 함:
- 30 frame animation 의 시각 본질이 **캐릭터 deformation 자체**
- 매 frame 에 점/선 같이 그리면 시각 dominate
- `PoseDetectionRigger` (1-frame 정적) 는 정적 스켈레톤 검증용이라 점/선 그렸지만, Sub-3 는 motion 자체가 검증 단위

디버그용 keypoint overlay 가 필요하면 별도 follow-up.

## 발견된 버그 + 수정 (commit `de30bcb`)

**문제**: 첫 시연 시 frame 0001~0030 가 md5 동일 (변형 0).
**원인**: `MotionEntry` catalog 의 ID ("dab", "dance_1", "motion_1" 등) 가 `MotionStub` 의 "wave" 와 mismatch. `MotionStub.frames(else)` 가 identity 반환 → 30 frame 다 같음.
**Fix**: `MotionStub.else` 가 wave 로 fallback. Sub-3 stub 단계의 목적은 visual 검증이므로 어떤 motion 선택해도 wave 적용.
**Sub-4 영향**: `BvhMotionSource` 가 실제 motion catalog 마다 다른 BVH 시퀀스 반환할 예정 → fallback 제거됨.

## 코드 변경 요약 (commits)

| commit | 내용 |
|---|---|
| f3a648e | Mesh2D data class + Barycentric helpers |
| 0007ac2 | GridMeshBuilder — bitmap → row-major grid mesh |
| ab71dd0 | CholeskyDecomposition — dense SPD factor + O(n²) solve |
| 31d55fb | ArapSolver — Igarashi 2009 ARAP, Kotlin port of AD arap.py |
| 85594fa | MotionSource interface + MotionStub wave |
| 68e8541 | MeshRenderer — Canvas.drawBitmapMesh wrapper |
| a7d92a3 | ArapRigger — orchestrate detect+pose+ARAP, emit 30 frame PNG |
| cff6e93 | swap to ArapRigger for production rigger |
| de30bcb | MotionStub falls back to wave for any catalog motion id |

## APK 크기

| 모델 | 크기 |
|---|---|
| drawn_humanoid_detector.onnx | 176 MB |
| pose_landmarker_heavy.task | 31 MB |
| ad_pose.onnx | 136 MB |
| **APK 총** | **~496 MB** (Sub-3 신규 model 없음, Sub-2b 와 동일) |

## 알려진 이슈 / Follow-up

- ⚠️ GIF encoding 미구현 — `animation.gif` 가 첫 frame PNG 복사. 별도 sub-task
- ⚠️ Grid mesh artifact — 큰 motion 에서 grid 경계 stair-stepping 가능. Delaunay triangulation 으로 follow-up
- ⚠️ Sub-1+2b quantization 여전히 후속 (latency 1-2분)
- ⚠️ MotionStub.else → wave fallback 은 Sub-4 가 와서 진짜 catalog mapping 제공할 때 제거
- ARAP solve 의 갤탭 실제 latency — log 에서 명시 측정 없음. logcat 보강 follow-up

## Sub-4 (BVH retarget) 진입 조건

- ✅ Sub-3 시각 검증 통과
- ✅ MotionSource interface 안정 (BvhMotionSource 가 swap 가능)
- ✅ 17 COCO keypoints schema 안정 (skeleton.json `ad-coco-17`)
- ☐ BVH parser 결정 (Sub-4 brainstorm)
- ☐ retarget skeleton 정의 (AD humanoid skeleton ↔ COCO 17 keypoint 매핑)
- ☐ MotionEntry catalog 의 ID 와 BVH 파일 매핑

## Sub-3 알고리즘 lesson (다음 ARAP/mesh 작업 시 재사용)

- **Dense Cholesky 면 충분** (V≤600 mesh 의 tA1xA1 5MB). Sparse 의 CSR/symbolic fact 면제
- AD arap.py 의 G_k 가 (rows, 2) — 잘못 (rows, 4) 가정 안 되도록 spec 작성 시 검증
- ARAP solve "identity test" 는 **2 pins 이상** 필요 (1 pin 은 null space 4 DOF 중 2 DOF 만 fix → underdetermined)
- mesh space 와 pin space 좌표계 정합 핵심 — character bitmap (mask crop) 좌표계로 통일
- Stub 단계 visual 검증에선 catalog motion ID 와의 mapping 도 확인 필요 (identity fallback 의 함정)
