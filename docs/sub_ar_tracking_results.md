# Sub-AR-Tracking 결과 — Homography paper tracking

날짜: 2026-05-18
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.8.0 (debug)

## MT.1 — OpenCV dependency

`org.opencv:opencv:4.10.0` Maven Central dependency 추가. APK 308 → **438 MB** (+130 MB OpenCV native libs).

## MT.2 — HomographyMath + PaperTracker

| 항목 | 결과 |
|---|---|
| HomographyMath 4 unit tests | ✅ identity / translation / scale / perspective |
| 전체 tests | 71 + 4 = **75 PASS** |

PaperTracker: ORB 500 features + Lowe ratio 0.75 + RANSAC threshold 3.0 + min inliers 15.

## MT.3 — ImageAnalysis 통합

CameraXPreview 의 `analyzer: ImageAnalysis.Analyzer?` 옵션 추가. 640×480 해상도 (tracking 용). YUV Y plane → grayscale Mat helper. OpenCVLoader.initLocal() 으로 native lib 로드.

## MT.4 — Sticker 4 vertex transform

ArStickerOverlay 의 trapezoid 4 vertex 가 homography 로 transform. Shadow ellipse 도 transformed bottom edge 따라 위치/크기 조정.

## MT.5 — 시연 + 디버깅 사이클

### 1차 시연 결과

| 항목 | 결과 |
|---|---|
| Reference 자동 캡처 | ✅ |
| Sticker 가 종이 따라 움직임 | ✅ (회전/이동 시 같이) |
| **단 sticker jitter 너무 큼** | ❌ |
| **터치 안 하면 sticker 안 보임** | ❌ |

### Fix #1 (commit `456e41e`) — anchor 자동 + EMA smoothing

- **anchor**: pointerInput 안 if-init → Modifier.onSizeChanged 로 이동 (터치 안 해도 즉시 init)
- **EMA**: `h_smoothed = 0.3 * h_new + 0.7 * h_prev` 으로 noise 감소
- lost 시 → state 안 update (마지막 위치 유지)

### Fix #2 (commit `b78ba98`) — anchor 위치 = drawing centroid

사용자 의도: 화면 중앙 보다 그림 위에 자동 stand.

- PaperTracker 가 reference keypoint 평균 (normalized 0~1) 노출
- ArViewScreen 의 LaunchedEffect 가 centroid + screenSize 둘 다 도착 후 anchor 변환
- 진입 직후 = 화면 중앙 (fallback), 1-2초 후 = 그림 가운데로 자동 이동

### Fix #3 (commit `9cf13d5`) — Alpha 0.3 → 0.1

사용자 보고: "anchor 너무 움직여". EMA factor 0.3 → 0.1 (응답 1/3, noise 1/3).

### 최종 시연 PASS

| 항목 | 결과 |
|---|---|
| 진입 후 자동 sticker 표시 | ✅ |
| Sticker 가 그림 위 (= keypoint centroid) 에 자동 stand | ✅ |
| 카메라 회전/이동 시 sticker 가 종이 따라 부드럽게 | ✅ |
| Jitter 거의 없음 (smoothing alpha=0.1) | ✅ |
| 터치 시 anchor 이동 (기존 기능 유지) | ✅ |

사용자 평가: "굿~ 좋다!!"

## APK 크기

| asset | 크기 |
|---|---|
| OpenCV native libs (모든 ABI) | +130 MB |
| 기타 (ONNX, models, motions) | 동일 |
| **APK 총** | **438 MB** (이전 308 MB → +130 MB) |

## 알려진 이슈 / Follow-up

- ⚠️ APK +130MB (모든 ABI 포함) — `abiFilters` 로 arm64-v8a 만 = ~50MB 절감 가능
- ⚠️ 큰 각도 (>60°) 또는 종이 부분 가림 시 lost
- ⚠️ Sticker 가 종이 영역 안 에서만 의미 — 종이 frame 밖 가면 의도 미정 (현재: 마지막 위치 유지)

## Sub-AR-Tracking commits

```text
5c9dd60  docs(ar-tracking): design
3189d03  docs(ar-tracking): implementation plan
52c4bdb  build(ar-tracking): OpenCV 4.10.0 maven dependency
a37c71e  feat(ar-tracking): HomographyMath + PaperTracker (OpenCV ORB+RANSAC)
c2a6bcf  feat(ar-tracking): wire PaperTracker into ArViewScreen via ImageAnalysis
7f633a5  feat(ar-tracking): apply homography to sticker 4 vertex + shadow
456e41e  fix(ar-tracking): auto anchor on size change + EMA homography smoothing
b78ba98  feat(ar-tracking): anchor sticker on drawing centroid (keypoint mean)
9cf13d5  tune(ar-tracking): EMA alpha 0.3 → 0.1 (less jitter)
```

## 다음 후보

| 우선순위 | Sub | 내용 |
|---|---|---|
| ⭐⭐ | 사용자 motion 녹화 | PC 에서 사용자 포즈 → BVH → 갤탭으로 |
| ⭐⭐ | Sub-1 mask 정확도 | boundary 짤림 해소 |
| ⭐ | Sub-3 Delaunay mesh | grid artifact 감소 |
| ⭐ | APK ABI 필터 | OpenCV native lib 의 arm64-v8a 만 |
