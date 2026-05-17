# Sub-AR-MVP Camera Overlay Sticker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 갤러리의 기존 sticker 를 갤탭 camera preview 위에 frame cycle 재생 + trapezoid tilt + ellipse shadow 로 "벌떡 서있는" overlay 시각. 사용자 터치로 anchor 위치 조정. 종이 추적 X (MVP).

**Architecture:** CameraXPreview (Sub-5 패턴 재사용) + Compose Canvas overlay. `ArStickerOverlay` 가 AnimationPlayer 패턴을 base 로 `drawBitmapMesh` (trapezoid) + ellipse shadow 추가. `ArViewScreen` 이 두 layer 합치고 터치 → anchor state. 새 nav route `arview/{stickerId}` + 상세 화면에 "AR 로 보기" 버튼.

**Tech Stack:** Kotlin + Jetpack Compose + CameraX + Android Canvas + JUnit4 + Robolectric

**Spec:** `docs/superpowers/specs/2026-05-15-sub-ar-mvp-design.md`

---

## File Structure

| 파일 | 종류 | 책임 |
|---|---|---|
| `app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerOverlay.kt` | 신규 | frame cycle + drawBitmapMesh trapezoid + ellipse shadow |
| `app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt` | 신규 | CameraXPreview + ArStickerOverlay + 터치 anchor + TopAppBar |
| `app/app/src/main/java/com/k3i/stickerbook/ui/StickerDetailScreen.kt` | 수정 | "AR 로 보기" 버튼 + onAR 콜백 |
| `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt` | 수정 | `arview/{stickerId}` 라우트 + onAR navigate |
| `app/app/src/test/java/com/k3i/stickerbook/ui/components/ArStickerOverlayTest.kt` | 신규 | tilt vertex 계산 + frame cycle wrap-around |
| `docs/sub_ar_mvp_results.md` | 신규 | M 결과 |

---

## Task 1: ArStickerOverlay 의 pure 계산 helper + 단위 테스트

**Goal:** Tilt vertex 계산 (4 점 trapezoid) 와 frame cycle index 계산을 pure 함수로 분리 + TDD.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerMath.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/ui/components/ArStickerMathTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.ui.components

import org.junit.Test
import kotlin.test.assertEquals

class ArStickerMathTest {

    @Test
    fun `trapezoid vertices anchor at bottom center with top wider than bottom`() {
        // anchor (= bottom-center of sticker) at (100, 200)
        // sticker width 60, height 80, bottom narrow factor 0.85
        val verts = ArStickerMath.trapezoidVertices(
            anchorX = 100f, anchorY = 200f,
            width = 60f, height = 80f,
            bottomNarrowFactor = 0.85f,
        )
        // 4 vertices in mesh order: TL, TR, BL, BR
        assertEquals(8, verts.size)  // [TLx, TLy, TRx, TRy, BLx, BLy, BRx, BRy]
        // bottom center at (100, 200), bottom width 60 * 0.85 = 51, half = 25.5
        // top center at (100, 200 - 80), top width 60, half = 30
        assertEquals(100f - 30f, verts[0], 0.01f)         // TL.x = 70
        assertEquals(200f - 80f, verts[1], 0.01f)         // TL.y = 120
        assertEquals(100f + 30f, verts[2], 0.01f)         // TR.x = 130
        assertEquals(200f - 80f, verts[3], 0.01f)         // TR.y = 120
        assertEquals(100f - 25.5f, verts[4], 0.01f)       // BL.x = 74.5
        assertEquals(200f, verts[5], 0.01f)               // BL.y = 200
        assertEquals(100f + 25.5f, verts[6], 0.01f)       // BR.x = 125.5
        assertEquals(200f, verts[7], 0.01f)               // BR.y = 200
    }

    @Test
    fun `frame index wraps around frameCount`() {
        assertEquals(0, ArStickerMath.frameAt(tickIndex = 0, frameCount = 30))
        assertEquals(15, ArStickerMath.frameAt(tickIndex = 15, frameCount = 30))
        assertEquals(0, ArStickerMath.frameAt(tickIndex = 30, frameCount = 30))
        assertEquals(1, ArStickerMath.frameAt(tickIndex = 31, frameCount = 30))
        assertEquals(29, ArStickerMath.frameAt(tickIndex = 59, frameCount = 30))
    }

    @Test
    fun `shadow ellipse below anchor with given width and height`() {
        val rect = ArStickerMath.shadowRect(
            anchorX = 100f, anchorY = 200f,
            stickerWidth = 60f,
            shadowWidthRatio = 0.7f,
            shadowHeight = 6f,
        )
        // shadow width = 60 * 0.7 = 42, half = 21
        // shadow centered at anchor, height extends below anchor
        assertEquals(100f - 21f, rect.left, 0.01f)        // 79
        assertEquals(200f, rect.top, 0.01f)               // 200 (anchor line)
        assertEquals(100f + 21f, rect.right, 0.01f)       // 121
        assertEquals(200f + 6f, rect.bottom, 0.01f)       // 206
    }
}
```

- [ ] **Step 2: test 실행 → fail**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:testDebugUnitTest --tests "*.ArStickerMathTest" 2>&1 | tail -10
```

- [ ] **Step 3: ArStickerMath.kt 작성**

```kotlin
package com.k3i.stickerbook.ui.components

/**
 * Pure math helpers for ArStickerOverlay.
 * - trapezoidVertices: 4 vertex of perspective trapezoid (top wider, bottom narrower)
 *   anchored at bottom-center.
 * - frameAt: cyclic frame index from tick.
 * - shadowRect: ellipse bounding rect below anchor.
 */
object ArStickerMath {

    /** [TLx, TLy, TRx, TRy, BLx, BLy, BRx, BRy] in pixel coords. */
    fun trapezoidVertices(
        anchorX: Float, anchorY: Float,
        width: Float, height: Float,
        bottomNarrowFactor: Float,
    ): FloatArray {
        val halfTop = width / 2f
        val halfBottom = width * bottomNarrowFactor / 2f
        val topY = anchorY - height
        return floatArrayOf(
            anchorX - halfTop,    topY,        // TL
            anchorX + halfTop,    topY,        // TR
            anchorX - halfBottom, anchorY,     // BL
            anchorX + halfBottom, anchorY,     // BR
        )
    }

    fun frameAt(tickIndex: Int, frameCount: Int): Int =
        if (frameCount <= 0) 0 else ((tickIndex % frameCount) + frameCount) % frameCount

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun shadowRect(
        anchorX: Float, anchorY: Float,
        stickerWidth: Float,
        shadowWidthRatio: Float,
        shadowHeight: Float,
    ): Rect {
        val half = stickerWidth * shadowWidthRatio / 2f
        return Rect(
            left = anchorX - half,
            top = anchorY,
            right = anchorX + half,
            bottom = anchorY + shadowHeight,
        )
    }
}
```

- [ ] **Step 4: test 재실행 → 3 PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.ArStickerMathTest" 2>&1 | tail -10
```

- [ ] **Step 5: 전체 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 68 + 3 = 71 tests PASS.

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerMath.kt \
        app/app/src/test/java/com/k3i/stickerbook/ui/components/ArStickerMathTest.kt
git commit -m "feat(ar): ArStickerMath — trapezoid vertices + frame cycle + shadow rect (TDD)"
```

---

## Task 2: ArStickerOverlay Composable

**Goal:** AnimationPlayer 패턴 + ArStickerMath 활용 + drawBitmapMesh (trapezoid) + ellipse shadow.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerOverlay.kt`

- [ ] **Step 1: ArStickerOverlay.kt 작성**

```kotlin
package com.k3i.stickerbook.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.k3i.stickerbook.data.AssetHandle
import com.k3i.stickerbook.data.AssetRepository
import kotlinx.coroutines.delay

/**
 * Camera overlay sticker. Plays frame sequence at fps + draws as a perspective
 * trapezoid anchored at [anchor] (bottom-center of the sticker) with ellipse
 * shadow under it for fake-AR "벌떡 서있는" depth feel.
 */
@Composable
fun ArStickerOverlay(
    framesDir: String,
    frameCount: Int,
    fps: Int,
    anchor: Offset,
    stickerWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val repo = remember { AssetRepository(ctx) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val frameIntervalMs = remember(fps) { (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L) }

    LaunchedEffect(framesDir, frameCount, fps) {
        if (frameCount <= 0) return@LaunchedEffect
        var tick = 0
        while (true) {
            val i = ArStickerMath.frameAt(tick, frameCount)
            val name = "%04d.png".format(i + 1)
            val handle = repo.resolve("$framesDir/$name")
            val newBmp = decodeFrameForAr(ctx, handle)
            // recycle previous before swap to avoid leak
            bitmap?.recycle()
            bitmap = newBmp
            delay(frameIntervalMs)
            tick++
        }
    }

    Canvas(modifier = modifier) {
        val bmp = bitmap ?: return@Canvas

        // Aspect-preserving height for given width
        val aspectH = stickerWidthPx * bmp.height / bmp.width.coerceAtLeast(1)

        // 1) shadow first (under sticker)
        val shadowH = aspectH * 0.05f
        val shadow = ArStickerMath.shadowRect(
            anchorX = anchor.x, anchorY = anchor.y,
            stickerWidth = stickerWidthPx,
            shadowWidthRatio = 0.7f,
            shadowHeight = shadowH,
        )
        val shadowPaint = Paint().apply {
            color = Color.argb(80, 0, 0, 0)
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawOval(
            RectF(shadow.left, shadow.top, shadow.right, shadow.bottom),
            shadowPaint,
        )

        // 2) trapezoid sticker via drawBitmapMesh (1x1 grid → 4 vertices)
        val verts = ArStickerMath.trapezoidVertices(
            anchorX = anchor.x, anchorY = anchor.y,
            width = stickerWidthPx, height = aspectH,
            bottomNarrowFactor = 0.85f,
        )
        drawContext.canvas.nativeCanvas.drawBitmapMesh(
            bmp,
            /* meshWidth = */ 1, /* meshHeight = */ 1,
            verts, /* vertOffset = */ 0,
            /* colors = */ null, /* colorOffset = */ 0,
            /* paint = */ null,
        )
    }
}

private fun decodeFrameForAr(ctx: android.content.Context, handle: AssetHandle): Bitmap? = when (handle) {
    is AssetHandle.Bundled -> ctx.assets.open(handle.assetPath).use {
        android.graphics.BitmapFactory.decodeStream(it)
    }
    is AssetHandle.InternalFile -> android.graphics.BitmapFactory.decodeFile(handle.file.absolutePath)
}
```

- [ ] **Step 2: compile 확인**

```bash
./run-gradle.sh app:compileDebugKotlin 2>&1 | tail -10
```

Expected: SUCCESS.

- [ ] **Step 3: 전체 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -5
```

Expected: 71 PASS (변경 없음).

- [ ] **Step 4: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/components/ArStickerOverlay.kt
git commit -m "feat(ar): ArStickerOverlay Composable — frame cycle + trapezoid + shadow"
```

---

## Task 3: ArViewScreen

**Goal:** CameraXPreview + ArStickerOverlay + 터치 anchor + TopAppBar.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt`

- [ ] **Step 1: ArViewScreen.kt 작성**

```kotlin
package com.k3i.stickerbook.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import com.k3i.stickerbook.camera.CameraXPreview
import com.k3i.stickerbook.camera.ImageCaptureController
import com.k3i.stickerbook.data.Manifest as StickerManifest
import com.k3i.stickerbook.ui.components.ArStickerOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArViewScreen(
    manifest: StickerManifest,
    stickerId: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val entry = remember(manifest, stickerId) {
        manifest.stickers.firstOrNull { it.id == stickerId }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    var anchor by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val controller = remember { ImageCaptureController() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.name ?: "AR") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (hasPermission) {
                CameraXPreview(controller = controller, modifier = Modifier.fillMaxSize())
            } else {
                Text("카메라 권한이 필요합니다")
            }
            // Initial anchor at center on first composition where size known via pointerInput
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(stickerId) {
                        // Set initial anchor to center if null
                        if (anchor == null) {
                            anchor = Offset(size.width / 2f, size.height * 2f / 3f)
                        }
                        detectTapGestures { offset ->
                            anchor = offset
                        }
                    },
            ) {
                val a = anchor
                if (entry != null && a != null) {
                    val widthPx = with(density) { 200.dp.toPx() }
                    ArStickerOverlay(
                        framesDir = entry.framesDir,
                        frameCount = entry.frameCount,
                        fps = entry.fps,
                        anchor = a,
                        stickerWidthPx = widthPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
```

import 끝에 `androidx.compose.ui.unit.dp` 추가 필요할 수도. compile 시 확인.

- [ ] **Step 2: compile**

```bash
./run-gradle.sh app:compileDebugKotlin 2>&1 | tail -10
```

만약 missing import (e.g., `dp`): 추가.

- [ ] **Step 3: 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -5
```

Expected: 71 PASS.

- [ ] **Step 4: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/ArViewScreen.kt
git commit -m "feat(ar): ArViewScreen — CameraX preview + touch anchor + sticker overlay"
```

---

## Task 4: StickerDetailScreen + AppNavHost 통합

**Goal:** 상세 화면에 "AR 로 보기" 버튼 + AppNavHost 라우트 추가.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/StickerDetailScreen.kt`
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`

- [ ] **Step 1: StickerDetailScreen.kt 의 signature + onAR 콜백 추가**

기존 (line 34 근처):
```kotlin
onSave: () -> Unit,
```

위 라인 다음에 추가:
```kotlin
onAR: () -> Unit,
```

기존 (line 65 근처) 의 Save 버튼 직후:
```kotlin
Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
    Text("갤러리에 저장")
}
```

다음에 추가:
```kotlin
Button(onClick = onAR, modifier = Modifier.fillMaxWidth()) {
    Text("AR 로 보기")
}
```

(`Modifier.fillMaxWidth()` 사이에 `Spacer(modifier = Modifier.height(8.dp))` 추가 가능 — 기존 패턴 확인 후 일관성 유지)

- [ ] **Step 2: AppNavHost.kt 의 detail 라우트 의 StickerDetailScreen 호출 에 onAR 추가**

기존:
```kotlin
StickerDetailScreen(
    entry = entry,
    onBack = { nav.popBackStack() },
    onPrev = { ... },
    onNext = { ... },
    onSave = { ... },
)
```

수정 — onSave 다음 줄 에 추가:
```kotlin
onAR = { nav.navigate("arview/${entry.id}") },
```

- [ ] **Step 3: AppNavHost.kt 에 arview 라우트 추가**

기존 composable list 의 끝 (또는 적절 위치) 에 추가:
```kotlin
composable(
    route = "arview/{id}",
    arguments = listOf(navArgument("id") { type = NavType.StringType }),
) { backStack ->
    val id = backStack.arguments?.getString("id") ?: return@composable
    ArViewScreen(
        manifest = m,
        stickerId = id,
        onBack = { nav.popBackStack() },
    )
}
```

import 추가:
```kotlin
import com.k3i.stickerbook.ui.ArViewScreen
```

- [ ] **Step 4: compile + test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL + 71 tests PASS.

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/StickerDetailScreen.kt \
        app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(ar): wire AR view from detail screen + nav route arview/{id}"
```

---

## Task 5: APK install + 갤탭 시연 + 결과 doc

**Goal:** 갤탭에 install, 사용자 시연, 결과 doc.

- [ ] **Step 1: APK install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  'C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk'
```

- [ ] **Step 2: 사용자 시연 (사용자 직접)**

1. 앱 launch → 그리드 → 기존 sticker (e.g. arap_1778834983208_phone_1) 탭
2. 상세 화면 → **"AR 로 보기"** 버튼 클릭
3. 카메라 권한 허가
4. 카메라 preview 보이는지
5. 화면 중앙 (initial) 에 sticker 30 frame cycle 재생
6. 화면 다른 위치 터치 → sticker 이동
7. 뒤로 → 상세 복귀

체크리스트:
- [ ] 카메라 preview 정상
- [ ] Sticker frame cycle 재생 (30 frame, jitter 없음)
- [ ] Trapezoid tilt + ellipse shadow → "벌떡 서있는" 느낌
- [ ] 터치 anchor 즉시 반응
- [ ] 뒤로 복귀 OK

- [ ] **Step 3: 결과 doc 작성**

`docs/sub_ar_mvp_results.md`:

```markdown
# Sub-AR-MVP 결과 — Camera overlay sticker

날짜: 2026-05-1X
대상: Galaxy Tab S9 FE+ (SM-X610)

## MA.1 — ArStickerMath 단위 (TDD)

3 tests PASS (trapezoid vertices, frame cycle, shadow rect).

## MA.2~MA.3 — Composable + Screen 통합

- `ArStickerOverlay`: AnimationPlayer 패턴 + drawBitmapMesh trapezoid + ellipse shadow
- `ArViewScreen`: CameraXPreview + 터치 anchor + TopAppBar
- 71 tests PASS (회귀 없음)

## MA.4 — 상세 화면 진입점 + nav 라우트

"AR 로 보기" 버튼 + `arview/{stickerId}` route.

## MA.5 — 갤탭 시연

| 항목 | 결과 |
|---|---|
| 카메라 preview | ✅ |
| Sticker frame cycle | (실제) |
| Trapezoid tilt + shadow | (시각 평가) |
| 터치 anchor 반응 | ✅ |
| 뒤로 복귀 | ✅ |

## 알려진 이슈 / Follow-up

- ⚠️ 종이 추적 X — 카메라 움직이면 sticker 화면 위치 그대로
  - Sub-AR-Tracking (별도 sub) 에서 homography 추가
- ⚠️ Sticker fixed size (200dp) — pinch-to-zoom 미지원
- ⚠️ Sticker 의 frame decode 30 fps 시 갤탭 CPU 사용량 — 측정 follow-up

## 다음 sub 후보

- Sub-AR-Tracking: ORB/AKAZE 종이 추적
- 사용자 motion 녹화 (PC)
- Sub-1 mask 정확도
```

- [ ] **Step 4: memory 업데이트**

`project_phase2_progress.md`:
- description 줄: "+ Sub-AR-MVP" 추가
- 후속 list 에서 "Sub-AR" 제거, Sub-AR-Tracking 항목 추가

`MEMORY.md`: Phase 2 줄 업데이트.

- [ ] **Step 5: commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add docs/sub_ar_mvp_results.md
git commit -m "docs(ar-mvp): MA results — camera overlay sticker, touch anchor PASS"
```

---

## 진행 순서 요약

1. **Task 1 (~30분)**: ArStickerMath pure 함수 + TDD (3 tests)
2. **Task 2 (~1시간)**: ArStickerOverlay Composable
3. **Task 3 (~1시간)**: ArViewScreen (CameraX + 터치)
4. **Task 4 (~30분)**: 상세 화면 버튼 + nav 라우트
5. **Task 5 (~30분)**: 갤탭 시연 + 결과 doc

총 ~3-4시간.

## 후속 sub 후보

- Sub-AR-Tracking: ORB/AKAZE 종이 추적
- Sub-AR-Multi: 여러 sticker 동시
- Sub-AR-Polish: shadow blur, fade-in, pinch-zoom
- 사용자 motion 녹화
