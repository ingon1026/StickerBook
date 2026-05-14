# Sub-2 결과 — MediaPipe Pose 통합

날짜: 2026-05-14
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.1.0 (debug)

## M2.1 — MediaPipe SDK + 모델 번들 (Task 1)

| 항목 | 측정값 | 비고 |
|---|---|---|
| MediaPipe Tasks Vision | 0.10.14 | google() Maven 정상 resolve |
| pose_landmarker_heavy.task | 30 MB | float16 |
| APK 크기 | 375 MB | Sub-1 의 299MB + MediaPipe SDK + 모델. quantization follow-up |

## M2.2 — PoseEstimator wrapper (Task 4)

| 항목 | 측정값 | 비고 |
|---|---|---|
| init 성공 | ✅ | logcat `PoseEstimator: MediaPipe PoseLandmarker initialized` |
| API 호환성 | OK | MediaPipe 0.10.14 의 PoseLandmarker / BitmapImageBuilder / Optional.orElse |
| NumPoses | 1 | single-person 가정 |
| Min confidence | 0.5 | detection/presence/tracking 모두 |

## M2.3 — SkeletonOverlay (Task 5)

- ✅ 33 BlazePose keypoints + connections (face/arms/torso/legs)
- ✅ visibility < 0.5 인 landmark dim (alpha 76 / 255)
- ✅ 입력 bitmap 보존, 새 ARGB_8888 bitmap 반환

## M2.4 — 통합 + 사용자 시연 (Task 7, 수동 검증)

| 항목 | 측정값 | 목표 | 통과 |
|---|---|---|---|
| 모델 두 개 동시 load | ✅ | crash 없음 | ✅ |
| Sub-1 (ONNX) latency | (사용자 시연 후) | 1-2분 | — |
| Sub-2 (MediaPipe Pose) latency | (사용자 시연 후) | < 100ms | — |
| 33 landmarks 감지됨 | (logcat 확인) | `pose detected: 33 landmarks` | — |
| skeleton.json 저장 | (사용자 시연 후) | `stickers/pose_<ts>/skeleton.json` 존재 | — |
| skeleton overlay 시각 표시 | (사용자 시연 후) | 누끼된 캐릭터 + 점·선 | — |
| 손그림 정확도 (visibility) | (사용자 판단) | 시각으로 평가 | — |

## 유닛 테스트 회귀 (전체 passing)

```
BUILD SUCCESSFUL in 20s
27 actionable tasks

테스트 합계:
- AssetRepositoryTest: 3 tests (1 skipped)
- CaptureSessionTest: 1 test
- ManifestParserTest: 2 tests
- DetectionOnlyRiggerTest: 1 test
- ImagePreprocessTest: 3 tests
- LandmarkTest: 2 tests (Sub-2 신규)
- MaskPostprocessTest: 3 tests
- PoseDetectionRiggerTest: 2 tests (Sub-2 신규)
- SkeletonOverlayTest: 2 tests (Sub-2 신규)
- StubRiggerTest: 1 test
- MotionPickerScreenTest: 1 test

합계: 21 passed + 1 skipped, 0 failures, 0 errors
```

### 시연 절차

1. 앱 → + FAB → 카메라 → 종이 그림 비추기 → 캡처
2. 다음 ▶ → 모션 선택 → 만들기 ▶
3. 1-2분 spinner 대기
4. 그리드 복귀 + 새 `pose_<ts>` 카드
5. 카드 탭 → 상세 화면에 누끼 + 33 관절 마킹 표시 확인
6. logcat:
   ```bash
   adb logcat -s "PoseEstimator" "PoseDetectionRigger" "MaskRcnnDetector"
   ```
   `PoseEstimator: pose detected: 33 landmarks` 보이면 PASS

## 알려진 이슈 / Follow-up

- ⚠️ APK 375MB — Sub-1 의 168MB + MediaPipe 의 native libs 합쳐 큼. 단일 sub-task 로 quantization (Sub-1 의 ONNX FP16) 검토 가치
- ⚠️ Inference 1-2분 (Sub-1 dominant) — Sub-2 자체는 빠르지만 chain 의 약점이 Sub-1
- ⚠️ MediaPipe Pose 가 손그림 도메인에서 정확도 떨어질 수 있음 — 시각 검증 후 결정
- 33 landmarks 의 z (depth) 는 추정치 정확도 낮음. visibility/presence 만 신뢰

## Sub-3 (ARAP) 진입 조건

- ☐ Sub-2 visual demo 통과 (사용자 시연)
- ☐ skeleton.json 포맷 (33 landmarks 픽셀 좌표) 안정
- ☐ ARAP mesh 변형 알고리즘 결정 (Sub-3 brainstorm 시점)
- ☐ 33 → AD-style skeleton (head, torso, limbs) 매핑 결정 (Sub-3 / Sub-4 사이 결정)

## Sub-2 commits (시간순)

```text
2381090 docs(sub-2): MediaPipe Pose integration design — character keypoint estimation
33f0af7 docs(sub-2): implementation plan — 8 tasks (MediaPipe dep, Landmark/Overlay TDD, integration)
66a646c build(android): add MediaPipe Tasks Vision 0.10.14 + pose_landmarker_heavy model
3573b30 feat(sub-2): Landmark + SkeletonData with JSON round-trip
3a6c119 feat(sub-2): StickerEntry adds optional skeleton_path field (backward-compatible)
8b5cc6c feat(sub-2): PoseEstimator MediaPipe wrapper (Bitmap → 33 landmarks)
7a6a573 feat(sub-2): SkeletonOverlay draws 33-keypoint dots + connections on Bitmap
756efbc feat(sub-2): PoseDetectionRigger integrates detector + pose, writes skeleton.json
ecea11f feat(sub-2): swap DetectionOnlyRigger for PoseDetectionRigger in AppNavHost
```
