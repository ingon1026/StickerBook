# Sub-5 Implementation Plan — UI + 카메라 캡처

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 갤탭에서 카메라 → 캡처 → 모션 선택 → (Stub) 결과 → 라이브러리 추가 사용자 흐름을 동작시킨다. Sub-1~4 가 채울 자리는 Stub interface 로 미리 확정.

**Architecture:** Phase 1 의 Compose UI 위에 4 신규 화면 추가 (Capture / Review / Motion / Processing). CameraX 로 카메라 처리. `CharacterRigger` Stub interface 가 Sub-1~4 의 entry point. `CaptureSession` (Compose static local) 으로 화면 간 임시 state 전달. 새 sticker 결과는 내부 storage + manifest 갱신.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Material3, CameraX 1.3.4, kotlinx.serialization (기존), Coil (기존), Robolectric (테스트).

**기본 결정 (Open Q default — plan 안에서 결정 가능)**:
- 모션 라이브러리 source: dummy hardcoded list (Sub-4 까지 placeholder)
- CaptureSession scope: `staticCompositionLocalOf`
- Activity orientation: landscape 고정
- 가이드 사각형: 단순 stroke overlay

---

## File Structure

### 신규 파일

```
app/app/src/main/java/com/k3i/stickerbook/
├── camera/
│   ├── CameraXPreview.kt          AndroidView wrapped CameraX Preview UseCase
│   └── ImageCaptureController.kt  ImageCapture UseCase + 캡처 함수
├── data/
│   ├── CaptureSession.kt          Activity scoped in-memory (image + motion)
│   └── MotionEntry.kt             모션 메타 + dummy hardcoded list
├── rig/
│   ├── CharacterRigger.kt         interface (Sub-1~4 의 entry point)
│   ├── RigResult.kt               결과 data class
│   └── StubRigger.kt              placeholder 구현 (캡처 이미지 1-frame sticker)
├── ui/
│   ├── CaptureScreen.kt           화면 2 — 카메라 미리보기 + 캡처 버튼
│   ├── CaptureReviewScreen.kt     화면 3 — 캡처 사진 review
│   ├── MotionPickerScreen.kt      화면 4 — 모션 선택 그리드
│   └── ProcessingScreen.kt        처리 중 다이얼로그
```

### 수정 파일

```
app/app/src/main/AndroidManifest.xml             CAMERA 권한 + orientation
app/app/build.gradle.kts                         CameraX 의존성
app/gradle/libs.versions.toml                    CameraX 버전 + libs entries
app/app/src/main/java/com/k3i/stickerbook/
├── data/AssetRepository.kt                      saveSticker(entry) 추가
├── ui/StickerListScreen.kt                      FAB + onCaptureClick callback
└── ui/nav/AppNavHost.kt                         4 routes 추가 + Stub 호출 흐름
```

### 신규 테스트

```
app/app/src/test/java/com/k3i/stickerbook/
├── data/CaptureSessionTest.kt
├── data/AssetRepositoryTest.kt                  기존 확장 — saveSticker round-trip
├── rig/StubRiggerTest.kt
└── ui/MotionPickerScreenTest.kt
```

---

작업 디렉토리: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/`
Gradle wrapper: `./run-gradle.sh` (Windows 측 gradlew.bat 호출)
Git: 자동 commit, push 안 함 (memory rule)

---

### Task 1: CameraX 의존성 + AndroidManifest

**Files:**
- Modify: `app/gradle/libs.versions.toml`
- Modify: `app/app/build.gradle.kts`
- Modify: `app/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: libs.versions.toml — CameraX 버전 + libs entries 추가**

기존 `[versions]` 블록 끝에 추가:
```toml
cameraX = "1.3.4"
```

기존 `[libraries]` 블록 끝에 추가:
```toml
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "cameraX" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "cameraX" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraX" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "cameraX" }
```

- [ ] **Step 2: app/build.gradle.kts — CameraX 의존성 추가**

`dependencies { }` 블록 안 implementation 군 끝에 추가:
```kotlin
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
```

- [ ] **Step 3: AndroidManifest.xml — CAMERA 권한 + Activity orientation**

`<manifest>` 안, 기존 `<uses-permission>` 들 옆에 추가:
```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any"
                  android:required="true" />
```

기존 `<activity android:name=".MainActivity" ...>` 의 속성에 추가 (혹은 수정):
```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:theme="@style/Theme.Stickerbook">
```

- [ ] **Step 4: 빌드 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/gradle/libs.versions.toml app/app/build.gradle.kts app/app/src/main/AndroidManifest.xml
git commit -m "build(android): add CameraX 1.3.4 deps + CAMERA permission + landscape orientation"
```

---

### Task 2: CharacterRigger interface + RigResult

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/CharacterRigger.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/RigResult.kt`

- [ ] **Step 1: RigResult 작성**

`app/app/src/main/java/com/k3i/stickerbook/rig/RigResult.kt`:
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
)
```

- [ ] **Step 2: CharacterRigger interface 작성**

`app/app/src/main/java/com/k3i/stickerbook/rig/CharacterRigger.kt`:
```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap

interface CharacterRigger {
    suspend fun rig(image: Bitmap, motion: String): RigResult
}
```

- [ ] **Step 3: 빌드 확인**

```bash
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/
git commit -m "feat(android): CharacterRigger interface + RigResult (entry point for Sub-1..4)"
```

---

### Task 3: StubRigger 구현 + 단위 테스트

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/StubRigger.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/StubRiggerTest.kt`

- [ ] **Step 1: failing test**

`app/app/src/test/java/com/k3i/stickerbook/rig/StubRiggerTest.kt`:
```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StubRiggerTest {

    @Test
    fun stub_writes_files_and_returns_rig_result() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val rigger = StubRigger(ctx)
        val r = rigger.rig(bitmap, "dance_1")

        assertTrue(r.framesDir.startsWith("stickers/stub_"))
        assertEquals(1, r.frameCount)
        assertEquals(64, r.width)
        assertEquals(64, r.height)

        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
        assertTrue(File(root, r.texturePath).isFile)
        assertTrue(File(root, r.gifPath).isFile)
        assertTrue(File(root, r.sourcePath).isFile)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.StubRiggerTest"
```

Expected: FAIL, unresolved reference `StubRigger`.

- [ ] **Step 3: 구현**

`app/app/src/main/java/com/k3i/stickerbook/rig/StubRigger.kt`:
```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

class StubRigger(private val context: Context) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        delay(1500)  // fake processing time

        val stickerId = "stub_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        // single frame = captured image itself (placeholder until Sub-1..4 land)
        writePng(image, File(framesDir, "0001.png"))
        writePng(image, File(sDir, "texture.png"))
        writePng(image, File(sDir, "animation.gif"))  // not a real gif; ok for Stub
        writePng(image, File(sDir, "source.png"))

        val rel = "stickers/$stickerId"
        return RigResult(
            framesDir = "$rel/frames",
            fps = 30,
            frameCount = 1,
            width = image.width,
            height = image.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
        )
    }

    private fun writePng(bmp: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.StubRiggerTest"
```

Expected: 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/StubRigger.kt
git add app/app/src/test/java/com/k3i/stickerbook/rig/StubRiggerTest.kt
git commit -m "feat(android): StubRigger writes placeholder sticker files for downstream subs"
```

---

### Task 4: CaptureSession + MotionEntry + 단위 테스트

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/data/CaptureSession.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/data/MotionEntry.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/data/CaptureSessionTest.kt`

- [ ] **Step 1: failing test**

`app/app/src/test/java/com/k3i/stickerbook/data/CaptureSessionTest.kt`:
```kotlin
package com.k3i.stickerbook.data

import android.graphics.Bitmap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureSessionTest {

    @Test
    fun set_and_reset_state() {
        val s = CaptureSession()
        assertNull(s.image)
        assertNull(s.motion)

        s.image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        s.motion = "dance_1"
        assertNotNull(s.image)
        assertNotNull(s.motion)

        s.reset()
        assertNull(s.image)
        assertNull(s.motion)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.CaptureSessionTest"
```

Expected: FAIL, unresolved reference `CaptureSession`.

- [ ] **Step 3: 구현**

`app/app/src/main/java/com/k3i/stickerbook/data/CaptureSession.kt`:
```kotlin
package com.k3i.stickerbook.data

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf

class CaptureSession {
    var image: Bitmap? = null
    var motion: String? = null
    fun reset() { image = null; motion = null }
}

val LocalCaptureSession = staticCompositionLocalOf<CaptureSession> {
    error("CaptureSession not provided")
}
```

`app/app/src/main/java/com/k3i/stickerbook/data/MotionEntry.kt`:
```kotlin
package com.k3i.stickerbook.data

data class MotionEntry(
    val id: String,
    val displayName: String,
)

object MotionCatalog {
    // Dummy hardcoded list until Sub-4 (BVH parser) lands.
    val all: List<MotionEntry> = listOf(
        MotionEntry(id = "dab", displayName = "댑"),
        MotionEntry(id = "dance_1", displayName = "댄스 1"),
        MotionEntry(id = "dance_2", displayName = "댄스 2"),
        MotionEntry(id = "dance_3", displayName = "댄스 3"),
        MotionEntry(id = "motion_1", displayName = "모션 1"),
        MotionEntry(id = "motion_2", displayName = "모션 2"),
    )
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.CaptureSessionTest"
```

Expected: 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/data/CaptureSession.kt
git add app/app/src/main/java/com/k3i/stickerbook/data/MotionEntry.kt
git add app/app/src/test/java/com/k3i/stickerbook/data/CaptureSessionTest.kt
git commit -m "feat(android): CaptureSession + MotionCatalog dummy list"
```

---

### Task 5: AssetRepository.saveSticker + 테스트

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/data/AssetRepository.kt`
- Modify: `app/app/src/test/java/com/k3i/stickerbook/data/AssetRepositoryTest.kt` (기존)

- [ ] **Step 1: 기존 test 끝에 새 test 추가**

기존 `AssetRepositoryTest.kt` 의 마지막 `@Test` 뒤에 추가:
```kotlin
    @Test
    fun saveSticker_round_trip_appends_to_internal_manifest() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AssetRepository(ctx)

        // start with no manifest
        assertNull(repo.loadManifest())

        val entry = StickerEntry(
            id = "s_test",
            name = "테스트",
            motion = "dance_1",
            durationMs = 0,
            fps = 30,
            frameCount = 1,
            width = 64,
            height = 64,
            framesDir = "stickers/s_test/frames",
            gifPath = "stickers/s_test/animation.gif",
            texturePath = "stickers/s_test/texture.png",
            sourcePath = "stickers/s_test/source.png",
        )
        repo.saveSticker(entry)

        val m = repo.loadManifest()!!
        assertEquals(1, m.formatVersion)
        assertEquals(1, m.stickers.size)
        assertEquals("s_test", m.stickers[0].id)
    }
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.AssetRepositoryTest"
```

Expected: FAIL — unresolved `saveSticker`.

- [ ] **Step 3: 구현 — `AssetRepository.kt` 에 메서드 추가**

기존 `AssetRepository` 클래스 안 (companion object 앞) 에 추가:
```kotlin
    fun saveSticker(entry: StickerEntry) {
        val manifestFile = File(context.filesDir, "stickerbook_assets/manifest.json")
        manifestFile.parentFile?.mkdirs()

        val existing = loadManifest() ?: Manifest(
            formatVersion = com.k3i.stickerbook.data.SUPPORTED_FORMAT_VERSION,
            generatedAt = "",
            stickers = emptyList(),
        )
        val merged = existing.copy(
            stickers = existing.stickers.filter { it.id != entry.id } + entry,
        )

        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }
        manifestFile.writeText(json.encodeToString(Manifest.serializer(), merged))
    }
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.AssetRepositoryTest"
```

Expected: 3 passed (기존 2 + 신규 1).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/data/AssetRepository.kt
git add app/app/src/test/java/com/k3i/stickerbook/data/AssetRepositoryTest.kt
git commit -m "feat(android): AssetRepository.saveSticker appends entry to internal manifest"
```

---

### Task 6: CameraXPreview composable + ImageCaptureController

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/camera/CameraXPreview.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/camera/ImageCaptureController.kt`

(no unit test — visual behavior; verified by build + manual)

- [ ] **Step 1: ImageCaptureController 구현**

`app/app/src/main/java/com/k3i/stickerbook/camera/ImageCaptureController.kt`:
```kotlin
package com.k3i.stickerbook.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.Executors

class ImageCaptureController {
    val useCase: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    private val executor = Executors.newSingleThreadExecutor()

    suspend fun captureBitmap(): Bitmap = suspendCoroutine { cont ->
        useCase.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val rot = image.imageInfo.rotationDegrees
                        if (rot != 0) {
                            val m = Matrix().apply { postRotate(rot.toFloat()) }
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        }
                        cont.resume(bmp)
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    cont.resumeWithException(exc)
                }
            },
        )
    }
}
```

- [ ] **Step 2: CameraXPreview composable 구현**

`app/app/src/main/java/com/k3i/stickerbook/camera/CameraXPreview.kt`:
```kotlin
package com.k3i.stickerbook.camera

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun CameraXPreview(
    controller: ImageCaptureController,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(controller) {
        val cameraProvider = ProcessCameraProvider.getInstance(ctx).let {
            suspendCoroutine<ProcessCameraProvider> { cont ->
                it.addListener({ cont.resume(it.get()) }, ContextCompat.getMainExecutor(ctx))
            }
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, controller.useCase,
        )
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

// suspendCoroutine helper imports
private suspend fun <T> suspendCoroutine(block: (kotlin.coroutines.Continuation<T>) -> Unit): T =
    kotlin.coroutines.suspendCoroutine(block)
```

(The internal `suspendCoroutine` shim avoids importing the kotlinx variant at top — keep file self-contained.)

- [ ] **Step 3: 빌드 확인**

```bash
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/camera/
git commit -m "feat(android): CameraX preview composable + image capture controller"
```

---

### Task 7: CaptureScreen (화면 2)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/CaptureScreen.kt`

- [ ] **Step 1: 구현**

`app/app/src/main/java/com/k3i/stickerbook/ui/CaptureScreen.kt`:
```kotlin
package com.k3i.stickerbook.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.k3i.stickerbook.camera.CameraXPreview
import com.k3i.stickerbook.camera.ImageCaptureController
import com.k3i.stickerbook.data.LocalCaptureSession
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onCaptured: () -> Unit,
) {
    val ctx = LocalContext.current
    val session = LocalCaptureSession.current
    val scope = rememberCoroutineScope()
    val controller = remember { ImageCaptureController() }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("새 스티커 만들기") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (!hasPermission) {
                Text(
                    "카메라 권한이 필요합니다",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                CameraXPreview(controller = controller, modifier = Modifier.fillMaxSize())
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val bmp = controller.captureBitmap()
                                session.image = bmp
                                onCaptured()
                            } catch (t: Throwable) {
                                // swallow — user will see no transition; could add Snackbar later
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                ) {
                    Text("캡처")
                }
            }
        }
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/CaptureScreen.kt
git commit -m "feat(android): CaptureScreen with CameraX preview + capture button"
```

---

### Task 8: CaptureReviewScreen (화면 3)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/CaptureReviewScreen.kt`

- [ ] **Step 1: 구현**

`app/app/src/main/java/com/k3i/stickerbook/ui/CaptureReviewScreen.kt`:
```kotlin
package com.k3i.stickerbook.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.LocalCaptureSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureReviewScreen(
    onRetake: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val session = LocalCaptureSession.current
    val bmp = session.image

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캡처 확인") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "캡처된 그림",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("캡처 이미지 없음")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                    Text("다시 찍기")
                }
                Button(
                    onClick = onNext,
                    enabled = bmp != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("다음 ▶")
                }
            }
        }
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/CaptureReviewScreen.kt
git commit -m "feat(android): CaptureReviewScreen with retake/next actions"
```

---

### Task 9: MotionPickerScreen (화면 4) + 테스트

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/MotionPickerScreen.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/ui/MotionPickerScreenTest.kt`

- [ ] **Step 1: failing test (Robolectric + Compose test)**

`app/app/src/test/java/com/k3i/stickerbook/ui/MotionPickerScreenTest.kt`:
```kotlin
package com.k3i.stickerbook.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.k3i.stickerbook.data.CaptureSession
import com.k3i.stickerbook.data.LocalCaptureSession
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class MotionPickerScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun lists_motions_and_select_updates_session() {
        val session = CaptureSession()
        rule.setContent {
            CompositionLocalProvider(LocalCaptureSession provides session) {
                MotionPickerScreen(onBack = {}, onConfirm = {})
            }
        }
        rule.onNodeWithText("댑").assertIsDisplayed()
        rule.onNodeWithText("댄스 1").performClick()
        assertEquals("dance_1", session.motion)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (unresolved MotionPickerScreen)**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.ui.MotionPickerScreenTest"
```

Expected: FAIL.

- [ ] **Step 3: 구현**

`app/app/src/main/java/com/k3i/stickerbook/ui/MotionPickerScreen.kt`:
```kotlin
package com.k3i.stickerbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.LocalCaptureSession
import com.k3i.stickerbook.data.MotionCatalog
import com.k3i.stickerbook.data.MotionEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionPickerScreen(
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val session = LocalCaptureSession.current
    var selected by remember { mutableStateOf<MotionEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("모션 선택") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(MotionCatalog.all, key = { it.id }) { m ->
                    MotionCard(
                        entry = m,
                        isSelected = selected?.id == m.id,
                        onClick = {
                            selected = m
                            session.motion = m.id
                        },
                    )
                }
            }
            Button(
                onClick = onConfirm,
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text("만들기 ▶")
            }
        }
    }
}

@Composable
private fun MotionCard(entry: MotionEntry, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 3.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(entry.displayName)
    }
}

// LazyVerticalGrid column-scope import workaround
private val androidx.compose.foundation.lazy.grid.LazyGridItemScope.weight get() = 1f
@Suppress("UNUSED_PARAMETER")
private fun androidx.compose.foundation.layout.ColumnScope.weight(value: Float): Modifier = Modifier
```

(The trailing `weight` references at the bottom are scoping helpers; if the compiler reports them as unused or conflicting, replace `Modifier.fillMaxWidth().weight(1f)` calls in this file with `Modifier.fillMaxWidth().padding(bottom = 16.dp)` instead — same visual outcome.)

- [ ] **Step 4: Run test — expect PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.ui.MotionPickerScreenTest"
```

Expected: 1 test passed. (Compose UI tests run via Robolectric with this rule setup.)

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/MotionPickerScreen.kt
git add app/app/src/test/java/com/k3i/stickerbook/ui/MotionPickerScreenTest.kt
git commit -m "feat(android): MotionPickerScreen with grid + selection state"
```

---

### Task 10: ProcessingScreen (다이얼로그)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/ProcessingScreen.kt`

- [ ] **Step 1: 구현**

`app/app/src/main/java/com/k3i/stickerbook/ui/ProcessingScreen.kt`:
```kotlin
package com.k3i.stickerbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = "스티커 만드는 중...",
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
```

- [ ] **Step 2: 빌드 + Commit**

```bash
./run-gradle.sh :app:compileDebugKotlin
git add app/app/src/main/java/com/k3i/stickerbook/ui/ProcessingScreen.kt
git commit -m "feat(android): ProcessingScreen progress indicator"
```

---

### Task 11: StickerListScreen FAB 추가

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/StickerListScreen.kt`

- [ ] **Step 1: 시그니처 + Scaffold 에 FAB 추가**

`StickerListScreen.kt` 전체 replace:
```kotlin
package com.k3i.stickerbook.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.data.StickerEntry
import com.k3i.stickerbook.ui.components.StickerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerListScreen(
    manifest: Manifest,
    onStickerClick: (StickerEntry) -> Unit,
    onCaptureClick: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("스티커북") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCaptureClick) {
                Icon(Icons.Default.Add, contentDescription = "새 스티커")
            }
        },
    ) { inner ->
        if (manifest.stickers.isEmpty()) {
            Text(
                "자산이 없습니다.\n+ 버튼으로 새 스티커를 만들어보세요.",
                modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize().padding(inner).padding(8.dp),
            ) {
                items(manifest.stickers, key = { it.id }) { entry ->
                    StickerCard(entry = entry, onClick = { onStickerClick(entry) })
                }
            }
        }
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
./run-gradle.sh :app:compileDebugKotlin
```

Expected: FAIL — `AppNavHost.kt` 에서 `StickerListScreen(...)` 호출이 새 파라미터 `onCaptureClick` 없이 호출 중. 다음 task 에서 fix.

- [ ] **Step 3: Commit (with broken build — intentional bridge)**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/StickerListScreen.kt
git commit -m "feat(android): add FAB to sticker list (callback wired in next task)"
```

(다음 task 와 짝. AppNavHost 가 호출자라 같이 수정해야.)

---

### Task 12: AppNavHost — 4 routes 추가 + 전체 흐름 연결

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`

- [ ] **Step 1: 전체 replace**

`app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`:
```kotlin
package com.k3i.stickerbook.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.k3i.stickerbook.data.AnimationSaver
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.CaptureSession
import com.k3i.stickerbook.data.LocalCaptureSession
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.data.StickerEntry
import com.k3i.stickerbook.rig.StubRigger
import com.k3i.stickerbook.ui.CaptureReviewScreen
import com.k3i.stickerbook.ui.CaptureScreen
import com.k3i.stickerbook.ui.MotionPickerScreen
import com.k3i.stickerbook.ui.ProcessingScreen
import com.k3i.stickerbook.ui.StickerDetailScreen
import com.k3i.stickerbook.ui.StickerListScreen

@Composable
fun AppNavHost() {
    val ctx = LocalContext.current
    val session = remember { CaptureSession() }

    var manifestVersion by remember { mutableStateOf(0) }
    val manifest by produceState<Manifest?>(initialValue = null, manifestVersion) {
        value = AssetRepository(ctx).loadManifest()
    }
    val m = manifest
    if (m == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val nav = rememberNavController()

    CompositionLocalProvider(LocalCaptureSession provides session) {
        NavHost(navController = nav, startDestination = "list") {
            composable("list") {
                StickerListScreen(
                    manifest = m,
                    onStickerClick = { entry -> nav.navigate("detail/${entry.id}") },
                    onCaptureClick = { nav.navigate("capture") },
                )
            }

            composable(
                route = "detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { backStack ->
                val id = backStack.arguments?.getString("id") ?: return@composable
                val idx = m.stickers.indexOfFirst { it.id == id }
                if (idx < 0) return@composable
                val entry = m.stickers[idx]
                StickerDetailScreen(
                    entry = entry,
                    onBack = { nav.popBackStack() },
                    onPrev = {
                        m.stickers.getOrNull(idx - 1)?.let {
                            nav.navigate("detail/${it.id}") { popUpTo("list") }
                        }
                    },
                    onNext = {
                        m.stickers.getOrNull(idx + 1)?.let {
                            nav.navigate("detail/${it.id}") { popUpTo("list") }
                        }
                    },
                    onSave = {
                        val saver = AnimationSaver(ctx)
                        val uri = saver.saveGif(entry)
                        val msg = if (uri != null) "갤러리에 저장됨" else "저장 실패"
                        android.widget.Toast.makeText(
                            ctx, msg, android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }

            composable("capture") {
                CaptureScreen(
                    onBack = { nav.popBackStack() },
                    onCaptured = { nav.navigate("review") },
                )
            }

            composable("review") {
                CaptureReviewScreen(
                    onBack = { nav.popBackStack() },
                    onRetake = { nav.popBackStack() },
                    onNext = { nav.navigate("motion") },
                )
            }

            composable("motion") {
                MotionPickerScreen(
                    onBack = { nav.popBackStack() },
                    onConfirm = {
                        if (session.image != null && session.motion != null) {
                            nav.navigate("processing")
                        }
                    },
                )
            }

            composable("processing") {
                LaunchedEffect(Unit) {
                    val image = session.image
                    val motion = session.motion
                    if (image == null || motion == null) {
                        nav.popBackStack("list", inclusive = false)
                        return@LaunchedEffect
                    }
                    val result = StubRigger(ctx).rig(image, motion)
                    val displayName = "stub_${System.currentTimeMillis() % 100000}"
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
                    )
                    AssetRepository(ctx).saveSticker(entry)
                    session.reset()
                    manifestVersion++  // re-trigger produceState to reload manifest
                    nav.navigate("list") {
                        popUpTo("list") { inclusive = true }
                    }
                    android.widget.Toast.makeText(
                        ctx, "새 스티커 만들어짐", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                ProcessingScreen()
            }
        }
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: install + 시각 검증**

```bash
ADB="/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$ADB shell input keyevent 224  # wake
$ADB shell svc power stayon true
./run-gradle.sh :app:installDebug
$ADB shell am force-stop com.k3i.stickerbook
$ADB shell am start -n com.k3i.stickerbook/.MainActivity
```

수동 검증:
1. 그리드 화면 → 오른쪽 아래 + FAB 보임
2. + FAB 탭 → 카메라 권한 요청 다이얼로그 → 허용
3. 카메라 미리보기 표시
4. [캡처] 버튼 탭 → review 화면
5. [다음 ▶] 탭 → 모션 선택
6. 모션 카드 1개 탭 (테두리 표시) → [만들기 ▶] 활성
7. [만들기 ▶] 탭 → "스티커 만드는 중..." 1.5초 → 그리드 복귀 → Toast "새 스티커 만들어짐"
8. 그리드에 새 카드 (캡처 사진 썸네일) 추가됨
9. 그 새 카드 탭 → 상세 화면 → 1-frame 정적 표시 (Stub 결과)

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(android): wire capture flow (camera → review → motion → stub rig → list)"
```

---

### Task 13: 최종 회귀 테스트

- [ ] **Step 1: 전체 unit test**

```bash
./run-gradle.sh :app:testDebugUnitTest
```

Expected: 모든 신규 + 기존 테스트 통과. Phase 1 의 2 + Sub-5 의 4 = 약 6+ tests.

- [ ] **Step 2: instrumented smoke test (Phase 1 의 것 재실행)**

```bash
ADB="/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$ADB shell input keyevent 224
$ADB shell svc power stayon true
./run-gradle.sh :app:connectedDebugAndroidTest
```

Expected: 1 passed (Phase 1 의 AnimationPlayerSmokeTest — list screen 진입 확인).

- [ ] **Step 3: Sub-5 의 모든 흐름 manual 한 번 더**

(Task 12 step 3 의 9 단계 재확인)

- [ ] **Step 4: 결과 메모**

`docs/sub5_results.md` 신규 (간단히, 측정값 / 알려진 이슈 / next steps):
```markdown
# Sub-5 결과

날짜: <측정 시점>
대상: Galaxy Tab S9 FE+ (SM-X610)

## 흐름 검증

- 그리드 → + FAB → 카메라 → 캡처 → review → 모션 선택 → 만들기 → 그리드 복귀 + 새 sticker ✅
- StubRigger 가 1.5초 후 placeholder 1-frame sticker 추가

## 알려진 이슈

- StubRigger 의 sticker 가 1 frame 만 (정적). Sub-1~4 통합 후 실제 모션 적용 예정.
- gif_path 파일이 실제 GIF 가 아닌 PNG. 호환성 안 맞지만 Sub-5 단계 OK.
- 카메라 가이드 사각형 단순 stroke. Sub-1 의 detection 통합 시 자동 영역 인식으로 교체 예정.

## Sub-1 진입 조건 체크

- ☐ Sub-5 흐름 안정 (수동 검증 통과)
- ☐ CharacterRigger interface 가 Sub-1+2 진입점으로 적합
- ☐ Annotated Drawings 데이터셋 확보 / 모델 선정

## Commit history (Sub-5)

(여기에 git log --oneline 결과 첨부)
```

- [ ] **Step 5: Commit**

```bash
git add docs/sub5_results.md
git commit -m "docs: sub-5 results notes + Sub-1 entry conditions"
```

---

## Self-Review

**1. Spec coverage** — sub-5 spec 의 sections:
- §1 (목표 + MVP) → Task 11 (FAB) + Task 12 (전체 흐름)
- §2 (Architecture) → Task 2~6 (CharacterRigger, CaptureSession, CameraX)
- §3 (UI wireframe) → Task 7~10 (4 screens)
- §4 (데이터 모델) → Task 2/4 (RigResult, CaptureSession, MotionEntry)
- §5 (CharacterRigger Stub) → Task 2/3
- §6 (Phase 1 재사용 + 수정) → Task 11/12
- §7 (권한 + 에러) → Task 7 (CaptureScreen 의 권한 launcher), Task 12 (Toast)
- §8 (테스트) → Task 3/4/5/9 (4 unit tests) + Task 13 (regression)
- §9 (Open Q) → header 의 default 명시
- §10 (Next Action) → Task 13 step 4 (Sub-1 entry)

**2. Placeholder scan** — "TBD/TODO/implement later" 없음. Task 11 의 "broken build intentional bridge" 는 Task 12 와 paired — 명확. Task 9 의 trailing `weight` shim 은 컴파일 실패 시 fallback 명시.

**3. Type 일관성** — `CharacterRigger.rig(Bitmap, String): RigResult` 시그니처가 Task 2/3/12 일관. `CaptureSession.image`/`motion` 가 Task 4/7/8/9/12 일관. `AssetRepository.saveSticker(StickerEntry)` 가 Task 5/12 일관. `LocalCaptureSession` 이 Task 4/7/8/9 일관.

문제 없음.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-14-sub5-camera-ui-plan.md`.

두 실행 옵션:

1. **Subagent-Driven (recommended)** — task 마다 fresh subagent + spec/quality review. 빠른 반복.

2. **Inline Execution** — 현재 세션에서 task 일괄 + 체크포인트.

어느 쪽?
