# Sub-2b 결과 — AD pose ONNX 통합

날짜: 2026-05-15
대상: Galaxy Tab S9 FE+ (SM-X610), device id R54X4008CET
앱 버전: 0.2.0 (debug)

## M2b.1 — PC: .mar 추출 + config 검증

| 항목 | 측정값 | 비고 |
|---|---|---|
| backbone | **ResNet-50** | plan §2 추정 HRNet-w48 와 다름 (spec discovery) |
| num_keypoints | 17 | COCO 표준 |
| image_size | (192, 256) | mmpose top-down convention |
| heatmap_size | (48, 64) | stride 4 |
| dataset | COCO | keypoint_info 17개 정의 + skeleton_info 19개 link |
| loss | JointsMSELoss | use_target_weight |

## M2b.2 — ONNX export

| 항목 | 측정값 | 비고 |
|---|---|---|
| 변환 path | mmpose 1.x reference config + AD checkpoint swap | plan 의 mmpose 2.x→3.x config patch 우회 (mmpose 1.x stock ResNet-50 COCO 256x192 reference 가 AD 와 architecturally identical) |
| 변환 시간 | ~10 초 (CPU) | |
| ONNX 크기 | **129.6 MB** | plan 예상 ~400MB 대비 매우 가벼움 (ResNet-50 + heatmap head only) |
| opset | 16 | |
| dynamic batch | input/output {0: 'batch'} | |

## M2b.3 — verify_onnx PASS

| keypoint | PyTorch (x,y,score) | ONNX (x,y,score) | Δpx | Δscore |
|---|---|---|---|---|
| nose, l_eye, ... 16 others | 다 일치 | 다 일치 | **0.000** | **0.0000** |

ONNX ↔ PyTorch eager 완전 동일 출력. 모델 변환 무손실.

## M2b.4 — Android 통합

### 코드 변경 요약 (Task 5~11)

| commit | 내용 |
|---|---|
| d7cea5f | `Landmark.kt` 에 `PoseBackend` enum + `SkeletonData.backend` (legacy fallback default=MEDIAPIPE_33) |
| e1a0aab | `PoseEstimator` class → interface, `MediaPipePoseEstimator.kt` 신규 (기존 본체 이동) |
| 910fe52 | `SkeletonOverlay` 17/33 keypoint 분기 (COCO_17_CONNECTIONS 추가) |
| c39ceb9 | `PoseHeatmapDecoder` — heatmap argmax + sub-pixel offset (pure Kotlin) |
| 4399679 | `AdPoseEstimator` — ONNX wrapper, crop affine + heatmap decode + unproject |
| 51d4a36 | `PoseDetectionRigger` DI + bbox 전달, realAd/realMediaPipe factories |
| 20dd339 | `AppNavHost.realAd(ctx)` swap + dead `real()` cleanup |
| d9f79a2 | Skeleton 좌표계 fix — image-space landmarks 를 character bitmap (mask crop) space 로 평행이동 후 overlay |

### Sub-1 hot-fix 3개 재적용 (`AdPoseEstimator`)

- ✅ File path mmap: `ensureModelOnDisk()` + `createSession(filePath)` (byte[] 통째 로드 X)
- ✅ NNAPI off: `BASIC_OPT` + CPU only (mmpose ops 도 NNAPI 호환성 검증 안 됨, Sub-1 lesson)
- ✅ Dispatchers.Default: AppNavHost 가 기존 패턴 그대로 (rigger 자체는 `suspend`, inference 는 background)

### APK 크기

| 모델 | 크기 |
|---|---|
| `drawn_humanoid_detector.onnx` | 176 MB |
| `pose_landmarker_heavy.task` | 31 MB |
| `ad_pose.onnx` | **136 MB** |
| 모델 합계 | 343 MB |
| **APK 총** | **520 MB** (plan 예상 780MB 대비 ~260MB 절감) |

## M2b.5 — 갤탭 시연 (시각 검증 PASS)

### 시연 절차

1. + FAB → 카메라 → 손그림 (막대 사람) 비추기 → 캡처
2. ▶ → 모션 선택 → 만들기 ▶
3. 1-2분 대기 (Sub-1 detector dominant)
4. 그리드 → `pose_<ts>` 카드 → 상세 화면

### 결과 (sticker `pose_1778811685717`)

| 항목 | 측정값 | 목표 | PASS |
|---|---|---|---|
| 모델 두 개 load | ✅ AD detector 176MB + AD pose 136MB | crash 없음 | ✅ |
| End-to-end latency | ~1-2분 (Sub-1 dominant) | < 5s | ❌ Sub-1 quantization 후속 |
| 17 keypoints 검출 | ✅ 전 17개 + max score **0.92** | ≥ 1개 vis > 0.3 | ✅ |
| skeleton.json 저장 | ✅ `pose_1778811685717/skeleton.json` (image-space 좌표) | 존재 | ✅ |
| skeleton overlay 시각 일치 | ✅ 머리/팔/다리 위치 그림과 합리적 일치 | 머리/팔/다리 일치 | ✅ |

### keypoint 분포 (image 3264x2448 안, 손그림 막대 사람)

| keypoint | (x, y) | visibility |
|---|---|---|
| nose | (1571, 858) | 0.92 |
| l_eye, r_eye | (1652, 813), (1491, 836) | 0.91, 0.87 |
| l_ear, r_ear | (1732, 836), (1422, 882) | 0.89, 0.89 |
| l_shoulder, r_shoulder | (1720, 1248), (1479, 1317) | 0.91, 0.88 |
| l_elbow, r_elbow | (1847, 1111), (1353, 1237) | 0.90, 0.88 |
| l_wrist, r_wrist | (1938, 916), (1216, 1145) | 0.88, 0.89 |
| l_hip, r_hip | (1640, 1604), (1525, 1627) | 0.84, 0.86 |
| l_knee, r_knee | (1697, 1799), (1502, 1810) | 0.75, 0.74 |
| l_ankle, r_ankle | (1743, 1919), (1480, 1919) | **0.47, 0.34** |

발목만 visibility 낮음 — 막대 그림 의 발이 가늘어서 모델이 헷갈림. 전체적으로 사람 형태 일관.

### 발견된 버그 + 수정 (commit `d9f79a2`)

**문제**: 첫 시연 시 skeleton overlay 안 보임.
**원인**: `skeleton.landmarks.x/y` 는 원본 image 좌표계 (3264x2448) 인데, `character` bitmap 은 mask 적용 후 bbox crop 결과 (작은 영역). `SkeletonOverlay.draw(character, skeleton)` 호출 시 점들이 character 범위 밖에 그려져 안 보임.
**Fix**: `PoseDetectionRigger.rig()` 에서 SkeletonOverlay 호출 직전 landmarks 의 `(x, y)` 를 `(top.bbox.left, top.bbox.top)` 만큼 평행이동.

## 유닛 테스트 회귀

| Test class | 개수 | 변경 |
|---|---|---|
| `LandmarkTest` + `LandmarkBackendTest` | 5 (2 기존 + 3 신규) | backend round-trip + legacy fallback |
| `SkeletonOverlayTest` | 4 (2 기존 + 2 신규) | 17 keypoint COCO path |
| `PoseHeatmapDecoderTest` | 3 (신규) | argmax + sub-pixel |
| `AdPoseEstimatorTest` | 2 (신규) | unproject/project round-trip |
| `PoseDetectionRiggerTest` | 4 (2 기존 + 2 신규) | bbox propagation |
| 그 외 모든 기존 tests | 다 PASS | 회귀 X |
| **합계** | **34 PASS, 0 fail** | |

## 알려진 이슈 / Follow-up

- ⚠️ APK 520 MB — Sub-1 detector + AD pose 합쳐 큼. quantization sub-task 후속 (Sub-1 + Sub-2b 동시 FP16 또는 INT8)
- ⚠️ End-to-end latency 1-2분 — Sub-1 detector 가 dominant (1-2분 CPU), AD pose 는 빠름. quantization 동일 필요
- ⚠️ 발목 visibility 낮음 — 단순 막대 그림에서 모델 한계. ARAP rigging (Sub-3) 에서 visibility threshold 로 처리
- Sub-1 mask 의 도메인 한계 (그림에 따라 머리 빠지거나 하반신만 잡음) — 모델 학습 한계, ARAP (Sub-3) 에서 보완 또는 모델 재학습

## Sub-3 (ARAP) 진입 조건

- ✅ Sub-2b 시각 검증 통과
- ✅ skeleton.json 포맷 안정 (ad-coco-17 또는 mediapipe-33, backend field 명시)
- ☐ ARAP mesh 변형 알고리즘 결정
- ☐ AD-skeleton 매핑 (17 COCO ↔ AD 자체 skeleton 형식)
- ☐ BVH retarget 와 연결 지점 결정 (Sub-4)

## Sub-2b commits (시간순)

```text
d7cea5f feat(sub-2b): SkeletonData.backend (PoseBackend enum) with legacy fallback
e1a0aab refactor(sub-2b): PoseEstimator becomes interface; MediaPipe impl renamed
910fe52 feat(sub-2b): SkeletonOverlay supports COCO 17 keypoint backend
c39ceb9 feat(sub-2b): PoseHeatmapDecoder — heatmap argmax with sub-pixel offset
4399679 feat(sub-2b): AdPoseEstimator — ONNX wrapper, crop affine + heatmap decode + unproject
51d4a36 feat(sub-2b): PoseDetectionRigger DI + bbox propagation, realAd/realMediaPipe factories
20dd339 feat(sub-2b): swap to AD pose estimator (realAd) for production rigger
d9f79a2 fix(sub-2b): shift skeleton landmarks into masked-character coord space before overlay
```
