# Sub-AR-Tracking Design — Homography paper tracking

> 작성일: 2026-05-18
> Sub-AR-MVP 의 한계 (화면 위 anchor) 해소. Master plan §1 시나리오 완성
> 선행: `2026-05-15-sub-ar-mvp-design.md`
> 사용자 결정: OpenCV Android SDK + AR view 진입 직후 첫 frame 자동 reference

---

## 1. 목표 / 책임 / 비책임

### 책임

- OpenCV Android SDK 추가 (build.gradle dependency, AAR)
- `PaperTracker` 신규 — reference frame ↔ 현재 frame 의 ORB keypoint matching + RANSAC homography
- `ArViewScreen` 확장 — 진입 직후 첫 frame 자동 reference + ImageAnalysis 매 frame homography update
- `ArStickerOverlay` 수정 — anchor + 4 vertex 를 homography 로 변환
- Lost detection (inlier < 15) → sticker 페이드/숨김

### 비책임

- ARCore plane detection (OpenCV 자체)
- ArUco marker (그림 자체가 anchor)
- 3D depth (2D homography 만, 종이 평면 가정)
- Multi-paper tracking
- 사용자 anchor drag — reference 시점 의 고정 anchor 만

### 최소 PASS 기준

1. AR view 진입 → 첫 frame ORB extract + reference 저장
2. 매 frame 매핑된 sticker 가 종이 위 위치에 고정 (카메라 좌우/위아래/회전 시 sticker 가 종이 따라 움직임)
3. Sticker perspective (homography 의 4 vertex) 가 종이 기울기 반영
4. 종이가 frame 밖 → sticker fade/hide
5. 갤탭 15fps 이상 (homography 매 frame)

---

## 2. 데이터 흐름 + 모듈 분해

```
[AR view 진입]
   ↓ CameraXPreview + ImageAnalysis (640×480 tracking 해상도)
   ↓ 첫 frame: PaperTracker.setReference(frame)
   ↓     → ORB extract reference keypoints + descriptors

[매 frame ImageAnalysis callback]
   ↓ PaperTracker.update(frame) → Mat3x3 homography or null (lost)
   ↓ ArViewScreen 의 homography state 갱신

[ArStickerOverlay (re-draw)]
   ↓ Reference anchor + trapezoid 4 vertex (reference frame 좌표계)
   ↓ Each vertex 를 homography 로 변환 → current frame 좌표
   ↓ Canvas.drawBitmapMesh(transformed verts)
```

### 모듈 분해

```
ar/                                  (신규 sub-package)
├── PaperTracker.kt                  (신규) - OpenCV ORB + RANSAC
└── HomographyMath.kt                (신규) - 4 vertex 변환 helper (pure)

ui/
├── ArViewScreen.kt                  (수정) - ImageAnalysis + PaperTracker 통합
└── components/
    └── ArStickerOverlay.kt          (수정) - homography 받아 4 vertex 변환
```

---

## 3. PaperTracker API

```kotlin
// ar/PaperTracker.kt
class PaperTracker {
    fun setReference(frameBgr: Mat)
    fun update(currentFrameBgr: Mat): Mat?
    val isReferenceSet: Boolean
    fun reset()
}
```

내부:
- ORB detector: `Orb.create(nfeatures=500)`
- BFMatcher (Hamming distance for ORB binary descriptors)
- Lowe ratio test: 0.75
- `Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 3.0)`
- inlier 개수 < 15 → null (lost)

---

## 4. ImageAnalysis pipeline

```kotlin
val analyzer = ImageAnalysis.Builder()
    .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(Size(640, 480))
    .build()

analyzer.setAnalyzer(executor) { imageProxy ->
    val mat = yuv420ToMat(imageProxy)
    if (!tracker.isReferenceSet) tracker.setReference(mat)
    else {
        val h = tracker.update(mat)
        homographyState.value = h
    }
    imageProxy.close()
}
```

해상도 640×480 — tracking 용. Preview 는 high-res 그대로.

---

## 5. Sticker 4 vertex 변환

### Reference 좌표계

진입 시 첫 frame 의 화면 중앙 (또는 사용자 터치 위치) = `referenceAnchor: Offset`.

기존 trapezoid (Sub-AR-MVP) 의 4 vertex 를 `referenceAnchor` 기준 으로 계산:
```kotlin
val refVerts = ArStickerMath.trapezoidVertices(refAnchor.x, refAnchor.y, ...)
```

### 매 frame 변환

```kotlin
// HomographyMath.kt
fun transformVerts(refVerts: FloatArray, h: Mat): FloatArray {
    val out = FloatArray(8)  // 4 vertex × 2
    for (i in 0 until 4) {
        val x = refVerts[i * 2]
        val y = refVerts[i * 2 + 1]
        val w = h.get(2, 0)[0] * x + h.get(2, 1)[0] * y + h.get(2, 2)[0]
        out[i * 2]     = ((h.get(0, 0)[0] * x + h.get(0, 1)[0] * y + h.get(0, 2)[0]) / w).toFloat()
        out[i * 2 + 1] = ((h.get(1, 0)[0] * x + h.get(1, 1)[0] * y + h.get(1, 2)[0]) / w).toFloat()
    }
    return out
}
```

ArStickerOverlay 가 이 transformed verts 를 받아 `drawBitmapMesh` 호출.

Shadow ellipse 도 비슷한 변환 (단 4 vertex polygon 으로 → ArStickerOverlay 에서 그리는 방식 일부 변경: ellipse → 변형된 polygon 또는 단순 무시).

### Lost 처리

`homography == null` → sticker 안 그림 (또는 alpha fade). 갤탭 UX: "종이를 다시 비춰주세요" 텍스트 toast.

---

## 6. 단계적 PoC

| M | 내용 | 검증 |
|---|---|---|
| **MT.1** | OpenCV Android SDK 추가 (build.gradle, AAR) | Gradle sync PASS, build SUCCESSFUL |
| **MT.2** | HomographyMath.kt 단위 (TDD, pure) + PaperTracker.kt 구현 | unit test PASS (math), integration via ArViewScreen |
| **MT.3** | ArViewScreen 의 ImageAnalysis 추가 + reference 자동 캡처 | logcat 에 "reference set, 250 keypoints" 류 |
| **MT.4** | ArStickerOverlay 의 homography 입력 + 4 vertex 변환 | sticker 가 종이 perspective 따라 변형 |
| **MT.5** | 갤탭 시연 — 카메라 회전/이동 시 sticker 가 종이 따라옴 | 시각 검증 PASS |
| **MT.6** | 결과 doc + memory | docs/sub_ar_tracking_results.md |

---

## 7. 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R1 | OpenCV native lib APK +50MB | scope 외, acceptable. quantize 한 APK 308 → ~358 MB |
| R2 | Reference frame 의 keypoint 부족 (흰 종이) | 그림 자체 의 선들이 keypoint 됨. 시연 시 그림 안 비추면 fail |
| R3 | inlier 임계값 (15) 너무 strict/loose | 실측 + parameter 조정 |
| R4 | 30fps 시 tracking latency | 640×480 + nfeatures=500 → ~10-20ms/frame 예상. backpressure STRATEGY_KEEP_ONLY_LATEST 로 drop |
| R5 | Compose state homography update — main thread frame drop | `StateFlow<Mat?>` + collectAsState |
| R6 | YUV→Mat 변환 cost | OpenCV `Imgproc.cvtColor` (Y plane only 사용 — grayscale 충분 for ORB) |

---

## 8. 검증

### Unit (Robolectric / JVM)

- `HomographyMathTest`:
  - identity homography → vertices 그대로
  - translation homography → vertices 평행이동
  - 90° rotation → vertices 90° 회전 (within tolerance)

PaperTracker 의 unit test 는 OpenCV native 의존이라 skip — integration test 로 검증.

### 갤탭 시연 (MT.5)

체크리스트:
- [ ] 그림 가운데 두고 진입 → sticker 가 그림 가운데 stand
- [ ] 카메라 ±30° rotation → sticker 가 종이 따라 rotation
- [ ] 카메라 멀리/가까이 → sticker scale 자동
- [ ] 종이 frame 밖으로 → sticker fade
- [ ] FPS 측정 (logcat 또는 시각 jitter)

PASS: 카메라 움직임 시 sticker 가 종이 위에 "붙어있는" 시각.

---

## 9. Sub-AR-Tracking 진입 조건 (이미 충족)

- ✅ Sub-AR-MVP 완료 — ArViewScreen + ArStickerOverlay + trapezoid tilt 작동
- ✅ CameraX 와 ImageAnalysis 통합 가능 (Sub-5 의 CameraXPreview 그대로)

## 10. 후속

- Sub-AR-Tracking 의 한계: 종이 정면 위에서만 잘 동작. 큰 각도 (>60°) 또는 종이 부분 가림 시 lost
- 후속 sub: 사용자 motion 녹화 (PC), mask 정확도, Delaunay mesh
