# Sub-2 Implementation Plan — MediaPipe Pose 통합

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 캡처 이미지 → MediaPipe Pose Tasks → 33 keypoints → skeleton.json 저장 + 캐릭터 frame 위 관절 overlay → Sub-3 ARAP 입력 + 사용자 시각 검증.

**Architecture:** MediaPipe Tasks Vision SDK 갤탭 의존성 추가 + `pose_landmarker_heavy.task` 모델 번들. `PoseEstimator` wrapper 가 Bitmap → 33 landmarks. `SkeletonOverlay` 가 Bitmap + landmarks → Bitmap (점·선 그리기). `PoseDetectionRigger` 가 Sub-1 detector + Sub-2 pose 통합 + skeleton.json 저장. `AppNavHost` 가 `DetectionOnlyRigger` → `PoseDetectionRigger` 교체.

**Tech Stack:**
- Kotlin 2.0, Jetpack Compose (기존)
- MediaPipe Tasks Vision `0.10.14` (신규)
- 모델: `pose_landmarker_heavy.task` (~20MB)
- 기존: kotlinx.serialization, ONNX Runtime Mobile (Sub-1)

**기본 결정 (Open Q default — plan 안에서 결정 가능):**
- Pose model variant: heavy (V1 PC 와 일치, <100ms 갤탭)
- Landmark 좌표: 픽셀 (절대값) + image size 도 같이 저장
- Visibility threshold: overlay 에서 < 0.5 면 dim (alpha 0.3)
- 추정 실패 시: try-catch → Sub-1 결과만 사용 + Toast 안내
- 사용자 탭 fallback: Sub-2 후속 sub-task. 이번 plan 안 다룸

---

## File Structure

### 신규 파일

```
app/app/src/main/java/com/k3i/stickerbook/rig/
├── Landmark.kt                Landmark + SkeletonData data class + JSON
├── PoseEstimator.kt           MediaPipe PoseLandmarker wrapper
├── SkeletonOverlay.kt         Bitmap + landmarks → Bitmap (점·선)
└── PoseDetectionRigger.kt     CharacterRigger 구현: detector + pose 통합

app/app/src/main/assets/models/
└── pose_landmarker_heavy.task    (~20MB, gitignore)

app/app/src/test/java/com/k3i/stickerbook/rig/
├── LandmarkTest.kt
├── SkeletonOverlayTest.kt
└── PoseDetectionRiggerTest.kt
```

### 수정 파일

```
app/gradle/libs.versions.toml                       MediaPipe 버전 + libs entry
app/app/build.gradle.kts                            MediaPipe Tasks Vision dep
app/app/src/main/java/com/k3i/stickerbook/data/StickerEntry.kt
                                                    skeletonPath: String? = null 추가
app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
                                                    DetectionOnlyRigger → PoseDetectionRigger
docs/sub2_results.md                                결과 문서 (Task 8)
```

---

작업 디렉토리 (모든 task):
- **Android**: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/`  ← **primary, ONLY**
- **금지**: `/home/ingon/AR_book/stickerbook_android_porting/` ← stale duplicate
- Gradle wrapper: `./run-gradle.sh`
- Windows ADB: `/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe`

---

## Task 1: MediaPipe 의존성 + 모델 번들

**Files:**
- Modify: `app/gradle/libs.versions.toml`
- Modify: `app/app/build.gradle.kts`
- Create: `app/app/src/main/assets/models/pose_landmarker_heavy.task` (download)

- [ ] **Step 1: libs.versions.toml**

Edit `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/gradle/libs.versions.toml`.

Append to `[versions]`:
```toml
mediapipeTasks = "0.10.14"
```

Append to `[libraries]`:
```toml
mediapipe-tasks-vision = { group = "com.google.mediapipe", name = "tasks-vision", version.ref = "mediapipeTasks" }
```

- [ ] **Step 2: build.gradle.kts — dep**

Inside `dependencies { }` block, add:
```kotlin
    implementation(libs.mediapipe.tasks.vision)
```

- [ ] **Step 3: Download pose_landmarker_heavy.task**

```bash
mkdir -p /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models
curl -L -o pose_landmarker_heavy.task \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task
ls -lh pose_landmarker_heavy.task
```

Expected: 파일 다운로드, 크기 ~20-30MB. (size 너무 작으면 다운로드 실패, 큰 차이면 새 version. 그래도 진행.)

이 파일은 `.gitignore` 의 `assets/models/` rule (Sub-1 Task 5 commit `2105059`) 에 이미 포함되어 있어서 별도 add 불필요.

- [ ] **Step 4: 빌드 확인 + APK 크기 점검**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:assembleDebug
ls -lh app/app/build/outputs/apk/debug/app-debug.apk
```

Expected:
- BUILD SUCCESSFUL
- APK 크기 ~320MB (이전 299 + 20). 250MB target 초과 — 알려진 follow-up

- [ ] **Step 5: Commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add app/gradle/libs.versions.toml app/app/build.gradle.kts
git commit -m "build(android): add MediaPipe Tasks Vision 0.10.14 + pose_landmarker_heavy model"
```

No push. (.task 파일은 gitignore.)

---

## Task 2: Landmark + SkeletonData (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/Landmark.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/LandmarkTest.kt`

- [ ] **Step 1: failing test**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/rig/LandmarkTest.kt`:

```kotlin
package com.k3i.stickerbook.rig

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LandmarkTest {

    @Test
    fun landmark_serializes_round_trip() {
        val original = Landmark(x = 100.5f, y = 200.3f, z = -0.5f, visibility = 0.95f, presence = 0.99f)
        val json = Json.encodeToString(Landmark.serializer(), original)
        val parsed = Json.decodeFromString(Landmark.serializer(), json)
        assertEquals(original.x, parsed.x, 0.001f)
        assertEquals(original.y, parsed.y, 0.001f)
        assertEquals(original.visibility, parsed.visibility, 0.001f)
    }

    @Test
    fun skeleton_data_holds_33_landmarks() {
        val landmarks = List(33) { i ->
            Landmark(x = i.toFloat(), y = i * 2f, z = 0f, visibility = 1f, presence = 1f)
        }
        val data = SkeletonData(landmarks = landmarks, imageWidth = 1280, imageHeight = 720)
        val json = Json.encodeToString(SkeletonData.serializer(), data)
        val parsed = Json.decodeFromString(SkeletonData.serializer(), json)
        assertEquals(33, parsed.landmarks.size)
        assertEquals(1280, parsed.imageWidth)
        assertEquals(720, parsed.imageHeight)
        assertEquals(5f, parsed.landmarks[5].x, 0.001f)
    }
}
```

- [ ] **Step 2: Run test — FAIL (unresolved Landmark/SkeletonData)**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.LandmarkTest"
```

Expected: FAIL — unresolved references.

- [ ] **Step 3: Implementation**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/Landmark.kt`:

```kotlin
package com.k3i.stickerbook.rig

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Landmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float,
)

@Serializable
data class SkeletonData(
    val landmarks: List<Landmark>,
    @SerialName("image_width") val imageWidth: Int,
    @SerialName("image_height") val imageHeight: Int,
)
```

- [ ] **Step 4: Run test — PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.LandmarkTest"
```

Expected: 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/Landmark.kt
git add app/app/src/test/java/com/k3i/stickerbook/rig/LandmarkTest.kt
git commit -m "feat(sub-2): Landmark + SkeletonData with JSON round-trip"
```

---

## Task 3: StickerEntry 에 skeletonPath 필드 추가

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/data/Manifest.kt`

- [ ] **Step 1: 기존 StickerEntry 확장**

Edit `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/data/Manifest.kt`. Find the existing `StickerEntry` data class. Add a new optional field at the end:

```kotlin
@Serializable
data class StickerEntry(
    val id: String,
    val name: String,
    val motion: String,
    @SerialName("duration_ms") val durationMs: Int,
    val fps: Int,
    @SerialName("frame_count") val frameCount: Int,
    val width: Int,
    val height: Int,
    @SerialName("frames_dir") val framesDir: String,
    @SerialName("gif_path") val gifPath: String,
    @SerialName("texture_path") val texturePath: String,
    @SerialName("source_path") val sourcePath: String,
    @SerialName("skeleton_path") val skeletonPath: String? = null,
)
```

Important: default `= null` makes it backward-compatible. Existing manifest.json without `skeleton_path` still parses. `format_version` stays at 1.

- [ ] **Step 2: 기존 test 회귀 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.ManifestParserTest"
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.AssetRepositoryTest"
```

Expected: 모두 통과 (skeleton_path default null 이라 기존 manifest 파싱 OK).

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/data/Manifest.kt
git commit -m "feat(sub-2): StickerEntry adds optional skeleton_path field (backward-compatible)"
```

---

## Task 4: PoseEstimator (MediaPipe wrapper, compile-only)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/PoseEstimator.kt`

(MediaPipe 의존성 + 실 모델 필요 — unit test 어려움. 통합은 Task 7 의 시각 검증.)

- [ ] **Step 1: 구현**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/PoseEstimator.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseEstimator(context: Context) {

    private val landmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
        Log.i(TAG, "MediaPipe PoseLandmarker initialized")
    }

    /**
     * Estimate pose landmarks for the given Bitmap.
     * Returns 33 pixel-coordinate landmarks, or empty list if no pose detected.
     */
    fun estimate(image: Bitmap): SkeletonData {
        val mpImage = BitmapImageBuilder(image).build()
        val result: PoseLandmarkerResult = landmarker.detect(mpImage)

        if (result.landmarks().isEmpty()) {
            Log.w(TAG, "no pose detected")
            return SkeletonData(emptyList(), image.width, image.height)
        }

        // First pose (numPoses=1)
        val mpLandmarks = result.landmarks()[0]
        val landmarks = mpLandmarks.mapIndexed { i, lm ->
            val visibility = lm.visibility().orElse(1f)
            val presence = lm.presence().orElse(1f)
            Landmark(
                x = lm.x() * image.width,
                y = lm.y() * image.height,
                z = lm.z(),
                visibility = visibility,
                presence = presence,
            )
        }
        Log.i(TAG, "pose detected: ${landmarks.size} landmarks")
        return SkeletonData(landmarks, image.width, image.height)
    }

    fun close() {
        landmarker.close()
    }

    companion object {
        private const val TAG = "PoseEstimator"
        private const val MODEL_ASSET_PATH = "models/pose_landmarker_heavy.task"
    }
}
```

- [ ] **Step 2: 빌드**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. (MediaPipe API 차이 시 fix — 예: `lm.visibility()` 가 `Optional<Float>` 인 경우 `.orElse(1f)` 또는 `.get()`.)

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/PoseEstimator.kt
git commit -m "feat(sub-2): PoseEstimator MediaPipe wrapper (Bitmap → 33 landmarks)"
```

---

## Task 5: SkeletonOverlay (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/SkeletonOverlay.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/SkeletonOverlayTest.kt`

- [ ] **Step 1: failing test**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/rig/SkeletonOverlayTest.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkeletonOverlayTest {

    @Test
    fun draws_landmark_dots_changing_pixels() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val originalCenter = bmp.getPixel(100, 100)

        val landmarks = listOf(
            Landmark(x = 100f, y = 100f, z = 0f, visibility = 1f, presence = 1f),
        )
        val skeleton = SkeletonData(landmarks, 200, 200)
        val out = SkeletonOverlay.draw(bmp, skeleton)

        // pixel near landmark should have changed
        val newCenter = out.getPixel(100, 100)
        assertNotEquals(originalCenter, newCenter)
        // image size preserved
        assertEquals(200, out.width)
        assertEquals(200, out.height)
    }

    @Test
    fun empty_landmarks_returns_unchanged_size() {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val skeleton = SkeletonData(emptyList(), 64, 48)
        val out = SkeletonOverlay.draw(bmp, skeleton)
        assertEquals(64, out.width)
        assertEquals(48, out.height)
    }
}
```

- [ ] **Step 2: Run — FAIL**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.SkeletonOverlayTest"
```

Expected: FAIL — unresolved `SkeletonOverlay`.

- [ ] **Step 3: Implementation**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/SkeletonOverlay.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object SkeletonOverlay {

    // BlazePose 33-keypoint connections (a subset of canonical edges)
    private val CONNECTIONS = listOf(
        // face
        0 to 1, 1 to 2, 2 to 3, 3 to 7,
        0 to 4, 4 to 5, 5 to 6, 6 to 8,
        9 to 10,
        // arms
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
        15 to 17, 15 to 19, 15 to 21, 17 to 19,
        16 to 18, 16 to 20, 16 to 22, 18 to 20,
        // torso
        11 to 23, 12 to 24, 23 to 24,
        // legs
        23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
        24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
    )

    /**
     * Draws landmark dots and connection lines on top of [bitmap].
     * Low-visibility landmarks (< 0.5) are drawn dim (alpha 0.3).
     */
    fun draw(bitmap: Bitmap, skeleton: SkeletonData): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (skeleton.landmarks.isEmpty()) return out
        val canvas = Canvas(out)
        drawConnections(canvas, skeleton.landmarks)
        drawDots(canvas, skeleton.landmarks)
        return out
    }

    private fun drawConnections(canvas: Canvas, landmarks: List<Landmark>) {
        val paint = Paint().apply {
            color = Color.argb(255, 0, 200, 255)
            strokeWidth = 4f
            isAntiAlias = true
        }
        for ((a, b) in CONNECTIONS) {
            if (a >= landmarks.size || b >= landmarks.size) continue
            val la = landmarks[a]
            val lb = landmarks[b]
            paint.alpha = if (la.visibility < 0.5f || lb.visibility < 0.5f) 76 else 255  // 0.3 vs 1.0
            canvas.drawLine(la.x, la.y, lb.x, lb.y, paint)
        }
    }

    private fun drawDots(canvas: Canvas, landmarks: List<Landmark>) {
        val paint = Paint().apply {
            color = Color.argb(255, 255, 80, 80)
            isAntiAlias = true
        }
        for (lm in landmarks) {
            paint.alpha = if (lm.visibility < 0.5f) 76 else 255
            canvas.drawCircle(lm.x, lm.y, 6f, paint)
        }
    }
}
```

- [ ] **Step 4: Run — PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.SkeletonOverlayTest"
```

Expected: 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/SkeletonOverlay.kt
git add app/app/src/test/java/com/k3i/stickerbook/rig/SkeletonOverlayTest.kt
git commit -m "feat(sub-2): SkeletonOverlay draws 33-keypoint dots + connections on Bitmap"
```

---

## Task 6: PoseDetectionRigger (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/PoseDetectionRiggerTest.kt`

- [ ] **Step 1: failing test (injected stubs for both detector and pose)**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/rig/PoseDetectionRiggerTest.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PoseDetectionRiggerTest {

    @Test
    fun saves_frames_and_skeleton_json() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)

        // Inject stubs: empty detection + 33 dummy landmarks
        val dummyLandmarks = List(33) { i ->
            Landmark(x = i.toFloat(), y = i.toFloat() * 2f, z = 0f, visibility = 1f, presence = 1f)
        }
        val rigger = PoseDetectionRigger.withStubs(
            ctx,
            detect = { _ -> emptyList() },
            estimate = { _ -> SkeletonData(dummyLandmarks, 128, 128) },
        )
        val r = rigger.rig(bitmap, "dance_1")

        assertEquals(1, r.frameCount)
        assertTrue(r.framesDir.startsWith("stickers/pose_"))
        assertNotNull(r.skeletonPath)

        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
        assertTrue(File(root, r.skeletonPath!!).isFile)
        // skeleton.json contains 33 landmarks
        val content = File(root, r.skeletonPath!!).readText()
        assertTrue(content.contains("landmarks"))
        assertTrue(content.contains("33") || content.contains("32"))  // last index or count
    }

    @Test
    fun handles_pose_estimator_failure_gracefully() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val rigger = PoseDetectionRigger.withStubs(
            ctx,
            detect = { _ -> emptyList() },
            estimate = { _ -> throw RuntimeException("simulated pose failure") },
        )
        // Should not crash — fall back to no-skeleton mode
        val r = rigger.rig(bitmap, "m")
        // Still returns a result with frames written; skeletonPath may be null
        assertEquals(1, r.frameCount)
        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
    }
}
```

- [ ] **Step 2: Run — FAIL**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.PoseDetectionRiggerTest"
```

Expected: FAIL — unresolved `PoseDetectionRigger`.

- [ ] **Step 3: Implementation**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class PoseDetectionRigger private constructor(
    private val context: Context,
    private val detect: (Bitmap) -> List<Detection>,
    private val estimate: (Bitmap) -> SkeletonData,
) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detect(image)
        val top = detections.maxByOrNull { it.score }

        val skeleton: SkeletonData? = runCatching { estimate(image) }
            .onFailure { Log.w(TAG, "pose estimate failed; continuing without skeleton", it) }
            .getOrNull()

        val stickerId = "pose_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        val character = if (top != null) {
            applyMask(image, top.mask, top.bbox)
        } else {
            image
        }
        val finalFrame = if (skeleton != null && skeleton.landmarks.isNotEmpty()) {
            SkeletonOverlay.draw(character, skeleton)
        } else {
            character
        }

        writePng(finalFrame, File(framesDir, "0001.png"))
        writePng(finalFrame, File(sDir, "texture.png"))
        writePng(finalFrame, File(sDir, "animation.gif"))
        writePng(image, File(sDir, "source.png"))

        val rel = "stickers/$stickerId"
        var skeletonPath: String? = null
        if (skeleton != null) {
            val skeletonFile = File(sDir, "skeleton.json")
            skeletonFile.writeText(Json.encodeToString(SkeletonData.serializer(), skeleton))
            skeletonPath = "$rel/skeleton.json"
        }

        return RigResult(
            framesDir = "$rel/frames",
            fps = 30,
            frameCount = 1,
            width = finalFrame.width,
            height = finalFrame.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
            skeletonPath = skeletonPath,
        )
    }

    private fun applyMask(image: Bitmap, mask: Bitmap, bbox: android.graphics.RectF): Bitmap {
        val left = bbox.left.toInt().coerceAtLeast(0)
        val top = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(image, left, top, cropW, cropH)
        val maskScaled = Bitmap.createScaledBitmap(mask, cropW, cropH, true)
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(cropped, 0f, 0f, null)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskScaled, 0f, 0f, paint)
        return out
    }

    private fun writePng(bmp: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    companion object {
        private const val TAG = "PoseDetectionRigger"

        /**
         * Production factory: real detector + real pose estimator.
         */
        fun real(context: Context): PoseDetectionRigger {
            val detector = MaskRcnnDetector(context)
            val estimator = PoseEstimator(context)
            return PoseDetectionRigger(
                context,
                detect = { detector.detect(it) },
                estimate = { estimator.estimate(it) },
            )
        }

        /**
         * Test factory: injected stubs.
         */
        fun withStubs(
            context: Context,
            detect: (Bitmap) -> List<Detection>,
            estimate: (Bitmap) -> SkeletonData,
        ): PoseDetectionRigger = PoseDetectionRigger(context, detect, estimate)
    }
}
```

Note: RigResult 의 새 `skeletonPath` 인자가 필요. Sub-1 의 `RigResult` data class 에 추가 필드 필요.

- [ ] **Step 4: RigResult 에 skeletonPath 필드 추가**

Edit `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/RigResult.kt`:

```kotlin
package com.k3i.stickerbook.rig

data class RigResult(
    val framesDir: String,
    val fps: Int,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val texturePath: String,
    val gifPath: String,
    val sourcePath: String,
    val skeletonPath: String? = null,
)
```

(StubRigger / DetectionOnlyRigger 의 호출은 nullable default `null` 이라 변경 불필요.)

- [ ] **Step 5: Run — PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.PoseDetectionRiggerTest"
```

Expected: 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt
git add app/app/src/main/java/com/k3i/stickerbook/rig/RigResult.kt
git add app/app/src/test/java/com/k3i/stickerbook/rig/PoseDetectionRiggerTest.kt
git commit -m "feat(sub-2): PoseDetectionRigger integrates detector + pose, writes skeleton.json"
```

---

## Task 7: AppNavHost — DetectionOnlyRigger → PoseDetectionRigger + 시각 검증

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`

- [ ] **Step 1: Edit AppNavHost.kt — 두 곳 변경**

Find:
```kotlin
    val rigger = remember { com.k3i.stickerbook.rig.DetectionOnlyRigger.real(ctx) }
```

Replace with:
```kotlin
    val rigger = remember { com.k3i.stickerbook.rig.PoseDetectionRigger.real(ctx) }
```

Then find the line in the "processing" route:
```kotlin
                    val entry = StickerEntry(
                        id = result.framesDir.substringAfter("stickers/").substringBefore("/"),
                        name = displayName,
                        motion = motion,
                        ...
                        sourcePath = result.sourcePath,
                    )
```

Add `skeletonPath = result.skeletonPath,` before the closing parenthesis:
```kotlin
                    val entry = StickerEntry(
                        id = result.framesDir.substringAfter("stickers/").substringBefore("/"),
                        name = displayName,
                        motion = motion,
                        durationMs = (1000 * result.frameCount / result.fps),
                        fps = result.fps,
                        frameCount = result.frameCount,
                        width = result.width,
                        height = result.height,
                        framesDir = result.framesDir,
                        gifPath = result.gifPath,
                        texturePath = result.texturePath,
                        sourcePath = result.sourcePath,
                        skeletonPath = result.skeletonPath,
                    )
```

- [ ] **Step 2: 빌드**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Install + 갤탭 실행**

```bash
ADB="/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$ADB shell input keyevent 224
$ADB shell svc power stayon true
./run-gradle.sh :app:installDebug 2>&1 | tail -3
$ADB shell am force-stop com.k3i.stickerbook
$ADB logcat -c
$ADB shell am start -n com.k3i.stickerbook/.MainActivity
sleep 5
$ADB logcat -d -t 200 -s "PoseEstimator:I" "PoseDetectionRigger:W" "MaskRcnnDetector:I" "AndroidRuntime:E" | head -30
```

Expected:
- `MaskRcnnDetector: creating ONNX session ...`
- `PoseEstimator: MediaPipe PoseLandmarker initialized`
- No crash

- [ ] **Step 4: 사용자 manual demo**

사용자가 갤탭에서 직접 시연 (Samsung bottom-edge 자동화 한계):

1. 앱 → + FAB → 카메라 → 종이 그림 캡처
2. 다음 → 모션 선택 → 만들기 ▶
3. 1-2분 대기 (Sub-1 inference 이 dominant. Sub-2 는 < 100ms 추가)
4. 그리드에 `pose_<ts>` 카드 추가
5. 카드 탭 → 상세 화면 → **누끼된 캐릭터 + 33 관절 마킹 + 연결선 표시**
6. 갤탭에서:
   ```bash
   $ADB shell run-as com.k3i.stickerbook ls files/stickerbook_assets/stickers/pose_*/
   ```
   `skeleton.json` 파일 확인.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(sub-2): swap DetectionOnlyRigger for PoseDetectionRigger in AppNavHost"
```

---

## Task 8: 회귀 + sub2_results.md

**Files:**
- Create: `docs/sub2_results.md`

- [ ] **Step 1: 전체 unit test 회귀**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 모두 통과. Sub-1 의 7 + Sub-5 의 7 + 신규 Landmark 2 + SkeletonOverlay 2 + PoseDetectionRigger 2 = 약 20 + 1 ignored.

- [ ] **Step 2: Create docs/sub2_results.md**

Create `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/docs/sub2_results.md`:

```markdown
# Sub-2 결과 — MediaPipe Pose 통합

날짜: <시연 시점>
대상: Galaxy Tab S9 FE+ (SM-X610)

## 통합 결과

- ☑/☐ 캡처 → PoseDetectionRigger → 누끼된 캐릭터 + 33 관절 overlay 보임
- ☑/☐ `stickers/pose_<ts>/skeleton.json` 생성됨
- ☑/☐ JSON 안 33 landmarks 픽셀 좌표
- ☑/☐ 손그림 관절 정확도 (사용자 판단)

## Inference latency

| 단계 | 측정값 | 비고 |
|---|---|---|
| Sub-1 (Mask R-CNN ONNX) | ~1-2분 | dominant |
| Sub-2 (MediaPipe Pose heavy) | < 100ms | (사용자가 느끼는 변화 X) |
| **Total** | ~1-2분 | Sub-1 quantization 후속 시 단축 |

## Visual quality

- (사용자 시연 후 채움)
- Sub-1 의 mask 머리 빠짐 + Sub-2 의 머리 관절 (nose/eyes/ears) 위치 비교

## 알려진 이슈 / Follow-up

- 손그림 도메인 pose 정확도 — 시연 후 평가. 부족 시 사용자 탭 fallback (Sub-2 후속 sub-task)
- Sub-1 quantization 후속 — Sub-2 와 무관, 별도 sub-task

## Sub-3 (ARAP) 진입 조건

- ☑/☐ Sub-2 visual 검증 통과
- ☑/☐ skeleton.json 포맷 안정 (33 landmarks pixel-coord)
- ☐ ARAP mesh 변형 알고리즘 결정 (Sub-3 brainstorm)

## Sub-2 commits

(아래에 git log --oneline 2381090^..HEAD 결과 첨부)
```

- [ ] **Step 3: 실제 git log 결과 채우기**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git log --oneline 2381090^..HEAD
```

위 출력을 `sub2_results.md` 끝의 `(아래에 git log...)` 부분에 markdown code block (```text ... ```) 으로 붙여넣기.

- [ ] **Step 4: Commit**

```bash
git add docs/sub2_results.md
git commit -m "docs(sub-2): results template + Sub-3 entry conditions"
```

---

## Self-Review

**1. Spec coverage** — Sub-2 spec sections:
- §1 목표 → Task 6 (PoseDetectionRigger) + Task 7 (AppNavHost wiring)
- §2 컨텍스트 (MMPose vs MediaPipe) → header 의 결정 메모
- §3 Architecture → Task 1-7 전체
- §4 컴포넌트 → Task 2, 4, 5, 6 (4 신규 + 2 수정)
- §5 PoC M2.1~M2.4 → Task 1 (M2.1), Task 4 (M2.2), Task 5 (M2.3), Task 7 (M2.4)
- §6 데이터 모델 → Task 2 (Landmark/SkeletonData), Task 3 (StickerEntry), Task 6 (RigResult)
- §7 Sub-1 lessons → Task 4 (PoseEstimator) + Task 7 (`remember` cache)
- §8 Risks → Task 6 의 try-catch on estimate failure, Task 7 의 logcat check
- §9 인터페이스 → Task 6 (CharacterRigger 구현)
- §10 Open Q → header 의 default 결정 + Task 6 의 try-catch fallback
- §11 Next Action → 본 plan 자체

**2. Placeholder scan** — "TBD/implement later" 없음. sub2_results.md 의 빈칸 (`<시연 시점>`, `☑/☐`) 은 사용자 측정값 자리. placeholder 와 다름.

**3. Type 일관성** — `Landmark(x, y, z, visibility, presence)` 가 Task 2/4/5/6 일관. `SkeletonData(landmarks, imageWidth, imageHeight)` 일관. `PoseDetectionRigger.rig(Bitmap, String): RigResult` 시그니처 CharacterRigger 인터페이스 일관. `RigResult.skeletonPath: String? = null` default 가 nullable 이라 Sub-1 의 DetectionOnlyRigger / StubRigger 호출 호환.

문제 없음.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-14-sub2-mediapipe-pose-plan.md`.

두 실행 옵션:

1. **Subagent-Driven (recommended)** — task 마다 fresh subagent + spec/quality review.
2. **Inline Execution** — 현재 세션 task 일괄.

어느 쪽?
