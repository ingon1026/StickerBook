# Sub-AR-Tracking Homography Paper Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenCV ORB+RANSAC 으로 갤탭 카메라가 종이 위 그림 추적. Sticker 의 4 vertex 가 homography 따라 변환 → 카메라 움직여도 sticker 가 종이 위 고정. Sub-AR-MVP 의 화면 위 anchor 한계 해소.

**Architecture:** Maven `org.opencv:opencv` dependency 추가. `PaperTracker` 가 reference frame 의 ORB descriptor cache + 매 frame BFMatcher + Lowe ratio + RANSAC findHomography. `ArViewScreen` 의 ImageAnalysis 가 매 frame `PaperTracker.update()` 호출 → homography state. `ArStickerOverlay` 의 trapezoid 4 vertex 를 homography 로 transform 후 `drawBitmapMesh`.

**Tech Stack:** OpenCV Android (Maven `org.opencv:opencv:4.10.0`) + CameraX ImageAnalysis + Compose Canvas + JUnit4

**Spec:** `docs/superpowers/specs/2026-05-18-sub-ar-tracking-design.md`

---

## File Structure

| 파일 | 종류 | 책임 |
|---|---|---|
| `app/app/build.gradle.kts` | 수정 | OpenCV maven dependency 추가 |
| `app/app/src/main/java/com/k3i/stickerbook/ar/HomographyMath.kt` | 신규 | 4 vertex transform pure helper |
| `app/app/src/main/java/com/k3i/stickerbook/ar/PaperTracker.kt` | 신규 | OpenCV ORB + RANSAC wrapper |
| `app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt` | 수정 | ImageAnalysis + PaperTracker 통합 + homography state |
| `app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerOverlay.kt` | 수정 | homography 입력 + 4 vertex transform |
| `app/app/src/test/java/com/k3i/stickerbook/ar/HomographyMathTest.kt` | 신규 | identity/translation/rotation 변환 TDD |
| `docs/sub_ar_tracking_results.md` | 신규 | MT 결과 |

---

## Task 1: OpenCV Android SDK 추가

**Goal:** Maven OpenCV dependency 추가 + build SUCCESS.

**Files:**
- Modify: `app/app/build.gradle.kts`

- [ ] **Step 1: 현재 build.gradle.kts 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
cat app/app/build.gradle.kts | grep -E "implementation|dependencies" | head -20
```

dependencies 블록 위치 확인.

- [ ] **Step 2: OpenCV dependency 추가**

`app/app/build.gradle.kts` 의 `dependencies { ... }` 블록 안에 추가:

```kotlin
implementation("org.opencv:opencv:4.10.0")
```

(Maven Central 의 공식 OpenCV Android. AAR 자동 download.)

만약 maven central 에 4.10.0 없으면 → 4.9.0 또는 다른 version 시도.

- [ ] **Step 3: gradle sync + build 확인**

```bash
./run-gradle.sh app:dependencies 2>&1 | grep opencv | head
./run-gradle.sh assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. APK 가 ~50MB 정도 증가 (OpenCV native libs).

만약 fail (artifact not found):
- 대안 1: `org.opencv:opencv-android:4.10.0`
- 대안 2: `com.quickbirdstudios:opencv:4.5.3.0`
- 대안 3: OpenCV.org 의 AAR 직접 download 후 libs/ 에 배치

- [ ] **Step 4: smoke test — OpenCV import 가능한지**

`app/app/src/main/java/com/k3i/stickerbook/ar/` 디렉토리 만들고 임시 file:

```kotlin
// app/app/src/main/java/com/k3i/stickerbook/ar/OpenCvSmoke.kt
package com.k3i.stickerbook.ar

import org.opencv.core.Mat

internal fun opencvSmokeTest(): String = "Mat class: ${Mat::class.java.name}"
```

```bash
./run-gradle.sh app:compileDebugKotlin 2>&1 | tail -5
```

Expected: SUCCESS. import 됐다면 OpenCV 사용 가능.

OK 면 위 smoke file 삭제 (production code 아님).

```bash
rm app/app/src/main/java/com/k3i/stickerbook/ar/OpenCvSmoke.kt
```

- [ ] **Step 5: test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -5
```

Expected: 71 PASS (변경 없음, dependency 만 추가).

- [ ] **Step 6: commit**

```bash
git add app/app/build.gradle.kts
git commit -m "build(ar-tracking): add OpenCV Android maven dependency (4.10.0)"
```

---

## Task 2: HomographyMath pure TDD + PaperTracker

**Goal:** 4 vertex transform pure helper TDD + OpenCV ORB/RANSAC wrapper.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ar/HomographyMath.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/ar/PaperTracker.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/ar/HomographyMathTest.kt`

- [ ] **Step 1: failing test — HomographyMath**

```kotlin
package com.k3i.stickerbook.ar

import org.junit.Test
import kotlin.test.assertEquals

class HomographyMathTest {

    @Test
    fun `identity homography returns vertices unchanged`() {
        val identity = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val verts = floatArrayOf(
            10f, 20f,    // v0
            30f, 40f,    // v1
            50f, 60f,    // v2
            70f, 80f,    // v3
        )
        val out = HomographyMath.transformVerts(verts, identity)
        for (i in verts.indices) {
            assertEquals(verts[i], out[i], 0.001f)
        }
    }

    @Test
    fun `translation homography shifts all vertices by tx and ty`() {
        // [1 0 5]
        // [0 1 7]
        // [0 0 1]
        val tx = 5.0
        val ty = 7.0
        val h = doubleArrayOf(
            1.0, 0.0, tx,
            0.0, 1.0, ty,
            0.0, 0.0, 1.0,
        )
        val verts = floatArrayOf(
            10f, 20f,
            30f, 40f,
            50f, 60f,
            70f, 80f,
        )
        val out = HomographyMath.transformVerts(verts, h)
        for (i in 0 until 4) {
            assertEquals(verts[i * 2] + tx.toFloat(), out[i * 2], 0.001f)
            assertEquals(verts[i * 2 + 1] + ty.toFloat(), out[i * 2 + 1], 0.001f)
        }
    }

    @Test
    fun `scale homography multiplies coordinates`() {
        // [2 0 0]
        // [0 3 0]
        // [0 0 1]
        val h = doubleArrayOf(
            2.0, 0.0, 0.0,
            0.0, 3.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val verts = floatArrayOf(
            10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f,
        )
        val out = HomographyMath.transformVerts(verts, h)
        for (i in 0 until 4) {
            assertEquals(verts[i * 2] * 2f, out[i * 2], 0.001f)
            assertEquals(verts[i * 2 + 1] * 3f, out[i * 2 + 1], 0.001f)
        }
    }

    @Test
    fun `perspective division applied when w not 1`() {
        // h[2,2] = 2 makes all w = 2, so coordinates should halve
        val h = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 2.0,
        )
        val verts = floatArrayOf(10f, 20f, 0f, 0f, 0f, 0f, 0f, 0f)
        val out = HomographyMath.transformVerts(verts, h)
        // (1*10 + 0*20 + 0) / (0*10 + 0*20 + 2) = 10/2 = 5
        assertEquals(5f, out[0], 0.001f)
        assertEquals(10f, out[1], 0.001f)
    }
}
```

- [ ] **Step 2: test 실행 → fail**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.HomographyMathTest" 2>&1 | tail -10
```

- [ ] **Step 3: HomographyMath.kt 작성 (pure Kotlin, OpenCV 의존 X)**

```kotlin
package com.k3i.stickerbook.ar

/**
 * Apply a 3x3 row-major homography matrix to a flat float array of 2D vertices.
 * Vertices are [v0x, v0y, v1x, v1y, ...]. The same layout is returned, with
 * perspective division (w = h20*x + h21*y + h22) applied.
 */
object HomographyMath {

    fun transformVerts(verts: FloatArray, h: DoubleArray): FloatArray {
        require(verts.size % 2 == 0) { "verts must have even length" }
        require(h.size == 9) { "homography must be 9 doubles" }
        val out = FloatArray(verts.size)
        var i = 0
        while (i < verts.size) {
            val x = verts[i].toDouble()
            val y = verts[i + 1].toDouble()
            val u = h[0] * x + h[1] * y + h[2]
            val v = h[3] * x + h[4] * y + h[5]
            val w = h[6] * x + h[7] * y + h[8]
            out[i] = (u / w).toFloat()
            out[i + 1] = (v / w).toFloat()
            i += 2
        }
        return out
    }
}
```

- [ ] **Step 4: test 재실행 → 4 PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.HomographyMathTest" 2>&1 | tail -10
```

- [ ] **Step 5: PaperTracker.kt 작성**

```kotlin
package com.k3i.stickerbook.ar

import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB

/**
 * Tracks a paper/drawing using ORB keypoints + RANSAC homography.
 *
 * Workflow:
 *   1. setReference(frame): extract ORB on the first frame; cache descriptors.
 *   2. update(frame): extract ORB on the new frame, match against reference,
 *      Lowe ratio test, RANSAC findHomography. Returns 3x3 matrix as DoubleArray
 *      (row-major), or null if too few inliers.
 *
 * Threading: not thread-safe; call from a single executor (e.g., ImageAnalysis
 * background thread).
 */
class PaperTracker(
    private val nFeatures: Int = 500,
    private val loweRatio: Double = 0.75,
    private val ransacThreshold: Double = 3.0,
    private val minInliers: Int = 15,
) {
    private val orb: ORB = ORB.create(nFeatures)
    private val matcher: BFMatcher = BFMatcher.create(org.opencv.core.Core.NORM_HAMMING, false)

    private val refKeypoints = MatOfKeyPoint()
    private val refDescriptors = Mat()
    var isReferenceSet: Boolean = false
        private set

    fun setReference(frameGray: Mat) {
        refKeypoints.release()
        refDescriptors.release()
        orb.detectAndCompute(frameGray, Mat(), refKeypoints, refDescriptors)
        isReferenceSet = refKeypoints.toArray().isNotEmpty()
        Log.i(TAG, "reference set: ${refKeypoints.toArray().size} keypoints")
    }

    fun update(frameGray: Mat): DoubleArray? {
        if (!isReferenceSet) return null

        val curKeypoints = MatOfKeyPoint()
        val curDescriptors = Mat()
        orb.detectAndCompute(frameGray, Mat(), curKeypoints, curDescriptors)
        if (curDescriptors.empty()) return null

        val knnMatches = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(refDescriptors, curDescriptors, knnMatches, 2)

        val goodMatches = mutableListOf<DMatch>()
        for (mm in knnMatches) {
            val arr = mm.toArray()
            if (arr.size >= 2 && arr[0].distance < loweRatio * arr[1].distance) {
                goodMatches.add(arr[0])
            }
        }

        if (goodMatches.size < minInliers) {
            Log.w(TAG, "lost: only ${goodMatches.size} good matches")
            return null
        }

        val refPts = refKeypoints.toArray()
        val curPts = curKeypoints.toArray()
        val srcPoints = MatOfPoint2f(*goodMatches.map { Point(refPts[it.queryIdx].pt.x, refPts[it.queryIdx].pt.y) }.toTypedArray())
        val dstPoints = MatOfPoint2f(*goodMatches.map { Point(curPts[it.trainIdx].pt.x, curPts[it.trainIdx].pt.y) }.toTypedArray())

        val homography = Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC, ransacThreshold)
        if (homography.empty()) {
            Log.w(TAG, "findHomography returned empty")
            return null
        }
        val out = DoubleArray(9)
        homography.get(0, 0, out)
        return out
    }

    fun reset() {
        refKeypoints.release()
        refDescriptors.release()
        isReferenceSet = false
    }

    companion object {
        private const val TAG = "PaperTracker"
    }
}
```

- [ ] **Step 6: compile + test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 71 + 4 = 75 tests PASS.

- [ ] **Step 7: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ar/HomographyMath.kt \
        app/app/src/main/java/com/k3i/stickerbook/ar/PaperTracker.kt \
        app/app/src/test/java/com/k3i/stickerbook/ar/HomographyMathTest.kt
git commit -m "feat(ar-tracking): HomographyMath + PaperTracker (OpenCV ORB+RANSAC)"
```

---

## Task 3: ArViewScreen 의 ImageAnalysis 통합 + reference 자동 캡처

**Goal:** ArViewScreen 에 CameraX ImageAnalysis pipeline 추가. 첫 frame 을 PaperTracker.setReference, 매 frame update → homography StateFlow.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt`
- (선택) `app/app/src/main/java/com/k3i/stickerbook/camera/CameraXPreview.kt` — ImageAnalysis 옵션 추가

### Step 1: CameraXPreview 의 ImageAnalysis 지원 확인

```bash
grep -n "ImageAnalysis\|analyzer" app/app/src/main/java/com/k3i/stickerbook/camera/CameraXPreview.kt
```

만약 ImageAnalysis 가 이미 있으면 그대로 사용. 없으면 추가:

```kotlin
// camera/CameraXPreview.kt 의 signature 에 analyzer 옵션 추가
@Composable
fun CameraXPreview(
    controller: ImageCaptureController,
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer? = null,
)
```

내부에서 CameraProvider 의 use case 에 ImageAnalysis 추가:
```kotlin
val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(android.util.Size(640, 480))
    .build()
analyzer?.let { analysis.setAnalyzer(executor, it) }
cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture, analysis)
```

- [ ] **Step 2: ArViewScreen 에 PaperTracker + ImageAnalyzer 추가**

`app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt` 의 ArViewScreen Composable 안에:

```kotlin
// state
val tracker = remember { com.k3i.stickerbook.ar.PaperTracker() }
val homographyState = remember { mutableStateOf<DoubleArray?>(null) }
val executor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

// analyzer
val analyzer = remember {
    androidx.camera.core.ImageAnalysis.Analyzer { imageProxy ->
        try {
            val mat = yPlaneToGrayMat(imageProxy)
            if (!tracker.isReferenceSet) {
                tracker.setReference(mat)
            } else {
                val h = tracker.update(mat)
                if (h != null) homographyState.value = h
            }
        } finally {
            imageProxy.close()
        }
    }
}

DisposableEffect(Unit) {
    onDispose {
        executor.shutdown()
        tracker.reset()
    }
}

// pass analyzer to CameraXPreview
CameraXPreview(controller = controller, analyzer = analyzer, modifier = Modifier.fillMaxSize())
```

- [ ] **Step 3: yPlaneToGrayMat helper 작성**

`app/app/src/main/java/com/k3i/stickerbook/ar/ImageProxyToMat.kt`:

```kotlin
package com.k3i.stickerbook.ar

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Convert ImageProxy (YUV_420_888) Y plane to single-channel OpenCV Mat.
 * ORB only needs grayscale; we skip U/V planes for speed.
 */
fun yPlaneToGrayMat(image: ImageProxy): Mat {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = image.width
    val height = image.height
    val bytes = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        buffer.get(bytes)
    } else {
        var off = 0
        val rowBuf = ByteArray(rowStride)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(rowBuf, 0, rowStride)
            for (col in 0 until width) bytes[off++] = rowBuf[col * pixelStride]
        }
    }
    val mat = Mat(height, width, CvType.CV_8UC1)
    mat.put(0, 0, bytes)
    return mat
}
```

- [ ] **Step 4: compile + test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL + 75 tests PASS.

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ar/ImageProxyToMat.kt \
        app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt \
        app/app/src/main/java/com/k3i/stickerbook/camera/CameraXPreview.kt
git commit -m "feat(ar-tracking): wire PaperTracker into ArViewScreen via ImageAnalysis"
```

---

## Task 4: ArStickerOverlay 의 homography 입력 + 4 vertex transform

**Goal:** ArStickerOverlay 가 homography state 받아 trapezoid 4 vertex 를 transform 후 그리기.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerOverlay.kt`
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt`

- [ ] **Step 1: ArStickerOverlay 의 signature 에 homography 추가**

기존:
```kotlin
@Composable
fun ArStickerOverlay(
    framesDir: String,
    frameCount: Int,
    fps: Int,
    anchor: Offset,
    stickerWidthPx: Float,
    modifier: Modifier = Modifier,
)
```

수정:
```kotlin
@Composable
fun ArStickerOverlay(
    framesDir: String,
    frameCount: Int,
    fps: Int,
    anchor: Offset,
    stickerWidthPx: Float,
    homography: DoubleArray? = null,  // ← new: null = no tracking (MVP fallback)
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 2: drawBitmapMesh 호출 전 vertex transform**

기존:
```kotlin
val verts = ArStickerMath.trapezoidVertices(...)
drawContext.canvas.nativeCanvas.drawBitmapMesh(bmp, 1, 1, verts, 0, null, 0, null)
```

수정:
```kotlin
val refVerts = ArStickerMath.trapezoidVertices(
    anchorX = anchor.x, anchorY = anchor.y,
    width = stickerWidthPx, height = aspectH,
    bottomNarrowFactor = 0.85f,
)
val finalVerts = if (homography != null) {
    com.k3i.stickerbook.ar.HomographyMath.transformVerts(refVerts, homography)
} else {
    refVerts  // no tracking yet
}
drawContext.canvas.nativeCanvas.drawBitmapMesh(bmp, 1, 1, finalVerts, 0, null, 0, null)
```

Shadow 도 동일 transform 적용 (4 점 polygon 으로 → ellipse drawing 은 4 점 trapezoid 의 bottom edge 의 중심 + 폭 으로 근사):

```kotlin
// shadow: 4 corner 의 bottom-left + bottom-right 의 중심 + 폭
val bottomL = Offset(finalVerts[4], finalVerts[5])
val bottomR = Offset(finalVerts[6], finalVerts[7])
val shadowCenterX = (bottomL.x + bottomR.x) / 2f
val shadowCenterY = (bottomL.y + bottomR.y) / 2f
val shadowWidth = kotlin.math.sqrt(
    ((bottomR.x - bottomL.x) * (bottomR.x - bottomL.x) +
     (bottomR.y - bottomL.y) * (bottomR.y - bottomL.y)).toDouble()
).toFloat() * 0.7f
val shadowHeight = aspectH * 0.05f
val shadow = ArStickerMath.shadowRect(
    anchorX = shadowCenterX,
    anchorY = shadowCenterY,
    stickerWidth = shadowWidth / 0.7f,  // shadowRect divides by 0.7 internally
    shadowWidthRatio = 0.7f,
    shadowHeight = shadowHeight,
)
// (shadowPaint drawOval 같은 패턴 그대로)
```

- [ ] **Step 3: ArViewScreen 의 ArStickerOverlay 호출 에 homographyState 전달**

```kotlin
val h by homographyState
ArStickerOverlay(
    framesDir = entry.framesDir,
    frameCount = entry.frameCount,
    fps = entry.fps,
    anchor = a,
    stickerWidthPx = widthPx,
    homography = h,
    modifier = Modifier.fillMaxSize(),
)
```

- [ ] **Step 4: compile + test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL + 75 tests PASS.

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerOverlay.kt \
        app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt
git commit -m "feat(ar-tracking): apply homography to sticker 4 vertex + shadow"
```

---

## Task 5: 갤탭 시연

**Goal:** APK install + 사용자 시연 (카메라 움직임 시 sticker 가 종이 위 고정).

- [ ] **Step 1: 갤탭 install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  'C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk'
```

- [ ] **Step 2: logcat 모니터**

별도 터미널:
```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -c
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -s \
  "PaperTracker" "ArViewScreen"
```

기대 로그:
- `PaperTracker: reference set: 250 keypoints` (또는 비슷)
- (lost 시) `PaperTracker: lost: only 5 good matches`

- [ ] **Step 3: 시연 (사용자 직접)**

1. 종이 그림 책상에 펴기 (Sub-AR-MVP demo 시 사용한 동일 그림 또는 새 그림)
2. 앱 → 그리드 → sticker 선택 → "AR 로 보기"
3. 카메라 권한 허가
4. **종이를 카메라 가운데 비추기** — 첫 frame 의 reference 가 그것 캡처
5. 잠시 기다림 → sticker 가 그림 가운데 stand
6. **카메라 ±30° rotation, 또는 좌우 이동** → sticker 가 종이 따라 같이 움직임
7. 카메라 빨리/멀리 → sticker scale 자동
8. 종이 frame 밖으로 → sticker fade (또는 안 그려짐)

체크리스트:
- [ ] Reference 캡처 logcat 확인
- [ ] Sticker 가 종이 위 (화면 위 아님) 에 고정
- [ ] 회전 시 sticker 도 회전 (homography rotation 성분)
- [ ] FPS 자연스러움 (15+ fps)

만약 sticker 가 화면 위 anchor 만 (MVP 동작) → tracking 안 됨. logcat 확인:
- "reference set" 로그 없음 → ImageAnalysis pipeline 미연결
- "lost" 만 계속 → keypoint 매칭 임계값 조정 필요

- [ ] **Step 4: 시연 결과 보고**

사용자 평가 받음. PASS / 부분 PASS / FAIL.

- [ ] **Step 5: commit (시연 결과 코드 변경 없으면 skip)**

---

## Task 6: 결과 doc + memory

**Files:**
- Create: `docs/sub_ar_tracking_results.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`

- [ ] **Step 1: docs/sub_ar_tracking_results.md 작성**

```markdown
# Sub-AR-Tracking 결과 — Homography paper tracking

날짜: 2026-05-1X
대상: Galaxy Tab S9 FE+ (SM-X610)

## MT.1 — OpenCV dependency

`org.opencv:opencv:4.10.0` Maven 추가. APK +X MB.

## MT.2 — HomographyMath 단위 + PaperTracker

4 tests PASS (identity / translation / scale / perspective).

71 + 4 = 75 tests PASS.

PaperTracker: ORB 500 features + Lowe ratio 0.75 + RANSAC threshold 3.0 + min inliers 15.

## MT.3 — ImageAnalysis 통합

ArViewScreen 의 ImageAnalysis pipeline. yPlaneToGrayMat helper (Y plane only, ORB 는 gray scale 충분).

## MT.4 — Sticker 4 vertex transform

ArStickerOverlay 의 trapezoid + shadow 가 homography 로 transform.

## MT.5 — 갤탭 시연

| 항목 | 결과 |
|---|---|
| Reference 캡처 logcat | ✅ X keypoints |
| Sticker 종이 위 고정 | (실제) |
| 카메라 회전 → sticker 회전 | (시각 평가) |
| FPS | (실측) |

## 알려진 이슈

- ⚠️ APK +50MB (OpenCV native libs)
- ⚠️ keypoint 부족 시 (단색 종이) lost
- ⚠️ 큰 각도 (>60°) 또는 종이 가림 시 lost

## 다음 후보

- 사용자 motion 녹화 (PC)
- Sub-1 mask 정확도
- Sub-3 Delaunay mesh
```

- [ ] **Step 2: memory 업데이트**

`project_phase2_progress.md`:
- description 줄: "+ Sub-AR-Tracking" 추가
- 후속 list 에서 "Sub-AR-Tracking" 제거

`MEMORY.md`: Phase 2 줄 업데이트.

- [ ] **Step 3: commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add docs/sub_ar_tracking_results.md
git commit -m "docs(ar-tracking): MT results — homography paper tracking 시각 검증"
```

---

## 진행 순서 요약

1. **Task 1 (~30분)**: OpenCV dependency
2. **Task 2 (~1-2시간)**: HomographyMath TDD + PaperTracker
3. **Task 3 (~1시간)**: ImageAnalysis pipeline + reference 캡처
4. **Task 4 (~30분)**: ArStickerOverlay 의 homography transform
5. **Task 5 (~30분)**: 시연
6. **Task 6 (~20분)**: 결과 doc

총 ~4-5시간.

## 핵심 risk

- OpenCV Maven artifact 못 찾으면 → 다른 version 또는 AAR 직접 download
- ImageAnalysis ↔ CameraXPreview 통합 의 surface lifecycle issue — STRATEGY_KEEP_ONLY_LATEST 로 backpressure
- 종이 단색 → keypoint 부족 — 사용자 그림 자체 가 anchor 라 정상 동작
- Tracking lost 시 sticker 동작 (현재 plan: 마지막 homography 유지) — UX 조정 가능
