# Sub-AR-Pipeline Implementation Plan — A 버튼 one-shot 흐름

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 그리드 화면 의 두 번째 FAB ("AR") 누르면 캡처 → 모션 선택 → processing → AR view 자동 진입 (review skip). 기존 카메라 FAB 흐름 그대로 유지.

**Architecture:** Compose Navigation 의 route 들 (capture/motion/processing) 에 optional `mode` query arg 추가. NORMAL/AR_AUTO enum 값 forward. CaptureScreen 의 onCaptured + processing 의 끝 nav 가 mode 따라 분기.

**Tech Stack:** Kotlin, Compose, Compose Navigation 2.x, Material3.

---

### Task 1: NavMode enum

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/NavMode.kt`

- [ ] **Step 1: Write NavMode**

```kotlin
package com.k3i.stickerbook.ui.nav

enum class NavMode { NORMAL, AR_AUTO;

    companion object {
        fun parse(raw: String?): NavMode = when (raw) {
            "AR_AUTO" -> AR_AUTO
            else -> NORMAL
        }
    }
}
```

- [ ] **Step 2: Verify compiles**

Run: `./run-gradle.sh :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/NavMode.kt
git commit -m "feat(nav): add NavMode enum for NORMAL/AR_AUTO routes"
```

---

### Task 2: StickerListScreen 두 번째 FAB

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/StickerListScreen.kt`

- [ ] **Step 1: Add onArCaptureClick parameter + second FAB**

Edit StickerListScreen.kt:

Replace the parameter list:
```kotlin
fun StickerListScreen(
    manifest: Manifest,
    onStickerClick: (StickerEntry) -> Unit,
    onCaptureClick: () -> Unit,
    onArCaptureClick: () -> Unit,
)
```

Replace the floatingActionButton with a Column of two FABs:
```kotlin
floatingActionButton = {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        FloatingActionButton(onClick = onArCaptureClick) {
            Icon(Icons.Default.Star, contentDescription = "AR 자동 흐름")
        }
        FloatingActionButton(onClick = onCaptureClick) {
            Icon(Icons.Default.Add, contentDescription = "새 스티커")
        }
    }
},
```

Add import: `import androidx.compose.material.icons.filled.Star`

- [ ] **Step 2: Verify compiles**

Run: `./run-gradle.sh :app:compileDebugKotlin`
Expected: FAIL (AppNavHost still passes 3 args — fixed in Task 3)

- [ ] **Step 3: (skip commit until Task 3 wires up)**

---

### Task 3: AppNavHost route 들 의 mode arg + wiring

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`

- [ ] **Step 1: Add NavMode import + route helpers**

Add imports:
```kotlin
import com.k3i.stickerbook.ui.nav.NavMode
```

- [ ] **Step 2: Update list composable to pass two callbacks**

Replace:
```kotlin
composable("list") {
    StickerListScreen(
        manifest = m,
        onStickerClick = { entry -> nav.navigate("detail/${entry.id}") },
        onCaptureClick = { nav.navigate("capture") },
    )
}
```

With:
```kotlin
composable("list") {
    StickerListScreen(
        manifest = m,
        onStickerClick = { entry -> nav.navigate("detail/${entry.id}") },
        onCaptureClick = { nav.navigate("capture?mode=NORMAL") },
        onArCaptureClick = { nav.navigate("capture?mode=AR_AUTO") },
    )
}
```

- [ ] **Step 3: Update capture route to take mode arg**

Replace:
```kotlin
composable("capture") {
    CaptureScreen(
        onBack = { nav.popBackStack() },
        onCaptured = { nav.navigate("review") },
    )
}
```

With:
```kotlin
composable(
    route = "capture?mode={mode}",
    arguments = listOf(
        navArgument("mode") { type = NavType.StringType; defaultValue = "NORMAL" },
    ),
) { backStack ->
    val mode = NavMode.parse(backStack.arguments?.getString("mode"))
    CaptureScreen(
        onBack = { nav.popBackStack() },
        onCaptured = {
            when (mode) {
                NavMode.NORMAL -> nav.navigate("review?mode=NORMAL")
                NavMode.AR_AUTO -> nav.navigate("motion?mode=AR_AUTO")
            }
        },
    )
}
```

- [ ] **Step 4: Update review route to forward mode**

Replace:
```kotlin
composable("review") {
    CaptureReviewScreen(
        onBack = { nav.popBackStack() },
        onRetake = { nav.popBackStack() },
        onNext = { nav.navigate("motion") },
    )
}
```

With:
```kotlin
composable(
    route = "review?mode={mode}",
    arguments = listOf(
        navArgument("mode") { type = NavType.StringType; defaultValue = "NORMAL" },
    ),
) { backStack ->
    val mode = NavMode.parse(backStack.arguments?.getString("mode"))
    CaptureReviewScreen(
        onBack = { nav.popBackStack() },
        onRetake = { nav.popBackStack() },
        onNext = { nav.navigate("motion?mode=${mode.name}") },
    )
}
```

- [ ] **Step 5: Update motion route to forward mode**

Replace:
```kotlin
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
```

With:
```kotlin
composable(
    route = "motion?mode={mode}",
    arguments = listOf(
        navArgument("mode") { type = NavType.StringType; defaultValue = "NORMAL" },
    ),
) { backStack ->
    val mode = NavMode.parse(backStack.arguments?.getString("mode"))
    MotionPickerScreen(
        onBack = { nav.popBackStack() },
        onConfirm = {
            if (session.image != null && session.motion != null) {
                nav.navigate("processing?mode=${mode.name}")
            }
        },
    )
}
```

- [ ] **Step 6: Update processing route — mode arg + end-nav branching**

Replace:
```kotlin
composable("processing") {
    LaunchedEffect(Unit) {
        val image = session.image
        val motion = session.motion
        if (image == null || motion == null) {
            nav.popBackStack("list", inclusive = false)
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.Default) {
            rigger.rig(image, motion)
        }
        val prefix = result.framesDir.substringAfter("stickers/").substringBefore("_")
        val displayName = "${prefix}_${System.currentTimeMillis() % 100000}"
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
        AssetRepository(ctx).saveSticker(entry)
        session.reset()
        manifestVersion++
        nav.navigate("list") {
            popUpTo("list") { inclusive = true }
        }
        android.widget.Toast.makeText(
            ctx, "새 스티커 만들어짐", android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
    ProcessingScreen()
}
```

With:
```kotlin
composable(
    route = "processing?mode={mode}",
    arguments = listOf(
        navArgument("mode") { type = NavType.StringType; defaultValue = "NORMAL" },
    ),
) { backStack ->
    val mode = NavMode.parse(backStack.arguments?.getString("mode"))
    LaunchedEffect(Unit) {
        val image = session.image
        val motion = session.motion
        if (image == null || motion == null) {
            nav.popBackStack("list", inclusive = false)
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.Default) {
            rigger.rig(image, motion)
        }
        val prefix = result.framesDir.substringAfter("stickers/").substringBefore("_")
        val displayName = "${prefix}_${System.currentTimeMillis() % 100000}"
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
        AssetRepository(ctx).saveSticker(entry)
        session.reset()
        manifestVersion++
        when (mode) {
            NavMode.NORMAL -> nav.navigate("list") {
                popUpTo("list") { inclusive = true }
            }
            NavMode.AR_AUTO -> nav.navigate("arview/${entry.id}") {
                popUpTo("list")
            }
        }
        android.widget.Toast.makeText(
            ctx, "새 스티커 만들어짐", android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
    ProcessingScreen()
}
```

- [ ] **Step 7: Verify compiles**

Run: `./run-gradle.sh :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit Task 2 + Task 3 together**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/StickerListScreen.kt \
        app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(nav): add AR_AUTO mode + second FAB for one-shot AR flow"
```

---

### Task 4: 빌드 + install + 갤탭 시연

- [ ] **Step 1: Build debug APK**

Run: `./run-gradle.sh :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install to Galaxy Tab**

Run:
```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe -s R54X4008CET install -r \
  "C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk"
```
Expected: `Success`

- [ ] **Step 3: 시연 — NORMAL 회귀**

기존 카메라 FAB (Add 아이콘) → capture → review → motion → processing → grid (새 sticker 보임). 흐름 그대로.

- [ ] **Step 4: 시연 — AR_AUTO 신규**

AR FAB (Star 아이콘) → capture → motion (review skip!) → processing → **ArView 자동 진입**. Sticker 가 카메라 frame 위 종이 추적.

- [ ] **Step 5: 시연 — back 네비게이션**

ArView 에서 back → grid 로 복귀, 새 sticker 가 갤러리 에 보임.

---

### Task 5: 결과 doc + memory

**Files:**
- Create: `app/app/docs/sub_ar_pipeline_results.md`
- Update: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`

- [ ] **Step 1: Write results doc**

Content: AR FAB 위치 + AR_AUTO 흐름 단계 + NORMAL 회귀 결과 + 시연 노트.

- [ ] **Step 2: Update phase2_progress memory**

Sub-AR-Pipeline 완료 행 추가 (Sub-Mask-Fix 와 동일 형식).

- [ ] **Step 3: Commit**

```bash
git add app/app/docs/sub_ar_pipeline_results.md
git commit -m "docs(sub-ar-pipeline): record AR_AUTO one-shot pipeline results"
```

---

## Risks / Notes

- AR_AUTO 흐름 에서 review skip → capture 가 흐릿/실패 면 사용자 가 back 으로 재캡처
- Compose nav 의 query arg `?mode={mode}` 가 optional + defaultValue 로 작동 (기존 `nav.navigate("capture")` 형태 호출 도 NORMAL 로 떨어짐 — 다만 본 plan 에선 명시적 `?mode=NORMAL` 사용)
- AR view 에서 back 시 popUpTo("list") 로 review/motion/processing 다 정리 됨
