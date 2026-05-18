# Sub-Mask-Fix Mask Boundary 후처리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mask R-CNN 의 mask (boundary 짤림) + 원본 image grayscale 의 검은 선 합집합으로 refined mask 생성. ArapRigger 의 applyMask 호출 전 적용.

**Architecture:** `MaskRefiner` 신규 — OpenCV (이미 dependency) 의 grayscale threshold + dilate + bitwise OR 로 후처리. 그림 의 검은 선 (캐릭터 outline) 이 mask 영역 dilation 안에 있으면 mask 에 추가. ArapRigger 의 `applyMask` 호출 전 `MaskRefiner.refine()` 1줄 추가.

**Tech Stack:** Kotlin + OpenCV 4.10.0 + Android Bitmap API + JUnit4 + Robolectric

**Spec:** `docs/superpowers/specs/2026-05-18-sub-mask-fix-design.md`

---

## File Structure

| 파일 | 종류 | 책임 |
|---|---|---|
| `app/app/src/main/java/com/k3i/stickerbook/rig/MaskRefiner.kt` | 신규 | OpenCV grayscale + mask dilation + union refine |
| `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt` | 수정 | applyMask 직전 MaskRefiner.refine() 호출 (1 line) |
| `app/app/src/test/java/com/k3i/stickerbook/rig/MaskRefinerTest.kt` | 신규 | 3 TDD tests (검은 사각형 / mask 멀리 / 빈 mask) |
| `docs/sub_mask_fix_results.md` | 신규 | MR 결과 |

---

## Task 1: MaskRefiner.kt + TDD (Robolectric)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/MaskRefiner.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/MaskRefinerTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MaskRefinerTest {

    init {
        OpenCVLoader.initLocal()
    }

    /** Helper: count of non-zero alpha pixels in a Bitmap. */
    private fun nonZeroPixels(bm: Bitmap): Int {
        var n = 0
        for (y in 0 until bm.height) for (x in 0 until bm.width) {
            if (Color.alpha(bm.getPixel(x, y)) > 127) n++
        }
        return n
    }

    @Test
    fun `refined mask includes black drawing lines that the original mask missed`() {
        // 100x100 white image with a black 30x60 rectangle centered (drawing)
        val w = 100; val h = 100
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val isDrawing = x in 35..64 && y in 20..79
            image.setPixel(x, y, if (isDrawing) Color.BLACK else Color.WHITE)
        }
        // Sub-1 mask is small (e.g., 28x28) and only covers TOP half of the drawing
        val rawMask = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
        for (y in 0 until 28) for (x in 0 until 28) {
            // top half mask
            rawMask.setPixel(x, y, if (y < 14) Color.WHITE else Color.TRANSPARENT)
        }
        val bbox = RectF(35f, 20f, 65f, 80f)

        val refined = MaskRefiner.refine(image, bbox, rawMask)

        // Refined mask should be roughly bbox size and cover more of the drawing
        // than the original top-half mask alone
        assertTrue(refined.width >= 30 && refined.width <= 60)
        assertTrue(refined.height >= 60 && refined.height <= 120)
        // Mask should now cover the bottom half too (the drawing's black pixels)
        val refinedNonZero = nonZeroPixels(refined)
        // Bottom half black pixels (~30 * 30 = 900) should be picked up too
        assertTrue(refinedNonZero > 500, "refined mask too small: $refinedNonZero")
    }

    @Test
    fun `black pixels outside bbox region are not included`() {
        val w = 100; val h = 100
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // black noise pixels OUTSIDE bbox (top-left corner)
        for (y in 0 until 10) for (x in 0 until 10) {
            image.setPixel(x, y, Color.BLACK)
        }
        // empty white inside bbox area
        for (y in 50 until 80) for (x in 50 until 80) {
            image.setPixel(x, y, Color.WHITE)
        }
        // 28x28 mask, only top half
        val rawMask = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
        for (y in 0 until 28) for (x in 0 until 28) {
            rawMask.setPixel(x, y, if (y < 14) Color.WHITE else Color.TRANSPARENT)
        }
        val bbox = RectF(50f, 50f, 80f, 80f)

        val refined = MaskRefiner.refine(image, bbox, rawMask)

        // Refined mask is bbox-sized — outside-bbox black pixels not represented
        assertTrue(refined.width <= bbox.width().toInt() + 1)
        assertTrue(refined.height <= bbox.height().toInt() + 1)
    }

    @Test
    fun `empty mask returns empty refined mask`() {
        val w = 100; val h = 100
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // some black pixels
        for (y in 30 until 70) for (x in 30 until 70) {
            image.setPixel(x, y, Color.BLACK)
        }
        // FULLY EMPTY mask (no foreground at all)
        val rawMask = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
        rawMask.eraseColor(Color.TRANSPARENT)
        val bbox = RectF(20f, 20f, 80f, 80f)

        val refined = MaskRefiner.refine(image, bbox, rawMask)
        // With no mask seed, dilation produces nothing, so no black pixels included
        assertTrue(nonZeroPixels(refined) == 0, "expected empty refined but got pixels")
    }
}
```

- [ ] **Step 2: test 실행 → fail (class 없음)**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:testDebugUnitTest --tests "*.MaskRefinerTest" 2>&1 | tail -10
```

- [ ] **Step 3: MaskRefiner.kt 작성**

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Refines a Mask R-CNN output mask by uniting it with the black drawing lines
 * found inside the bbox region of the source image.
 *
 * Algorithm:
 *   1. Crop bbox region of the source image, convert to grayscale.
 *   2. Threshold (< 100 = black drawing line) → binMask.
 *   3. Scale the input mask to bbox size, dilate by 5px → expandedMask.
 *   4. combinedMask = binMask AND expandedMask  (only black pixels near the mask).
 *   5. finalMask = mask OR combinedMask, then morphology close (5x5).
 */
object MaskRefiner {

    private const val TAG = "MaskRefiner"
    private const val GRAYSCALE_THRESHOLD = 100.0
    private const val DILATE_KSIZE = 5.0
    private const val CLOSE_KSIZE = 5.0

    fun refine(image: Bitmap, bbox: RectF, mask: Bitmap): Bitmap {
        val left = bbox.left.toInt().coerceAtLeast(0)
        val top = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)

        // 1. Crop bbox region → grayscale Mat
        val roi = Bitmap.createBitmap(image, left, top, w, h)
        val roiMat = Mat()
        Utils.bitmapToMat(roi, roiMat)  // BGRA
        val gray = Mat()
        Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_BGRA2GRAY)

        // 2. Threshold (black drawing lines)
        val binMask = Mat()
        Imgproc.threshold(gray, binMask, GRAYSCALE_THRESHOLD, 255.0, Imgproc.THRESH_BINARY_INV)

        // 3. Scale + binarize input mask → expandedMask (dilate)
        val scaledMaskBmp = Bitmap.createScaledBitmap(mask, w, h, true)
        val scaledMaskMat = Mat()
        Utils.bitmapToMat(scaledMaskBmp, scaledMaskMat)  // BGRA, alpha channel = mask
        // Extract alpha as single-channel
        val maskChannels = mutableListOf<Mat>()
        Core.split(scaledMaskMat, maskChannels)
        val maskAlpha = if (maskChannels.size >= 4) maskChannels[3] else maskChannels[0]
        val seed = Mat()
        Imgproc.threshold(maskAlpha, seed, 127.0, 255.0, Imgproc.THRESH_BINARY)
        val expandedMask = Mat()
        val dilateKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(DILATE_KSIZE, DILATE_KSIZE),
        )
        Imgproc.dilate(seed, expandedMask, dilateKernel)

        // 4. combinedMask = binMask AND expandedMask (black pixels NEAR the mask)
        val combinedMask = Mat()
        Core.bitwise_and(binMask, expandedMask, combinedMask)

        // 5. finalMask = mask OR combinedMask, then close to fill holes
        val finalMask = Mat()
        Core.bitwise_or(seed, combinedMask, finalMask)
        val closed = Mat()
        val closeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(CLOSE_KSIZE, CLOSE_KSIZE),
        )
        Imgproc.morphologyEx(finalMask, closed, Imgproc.MORPH_CLOSE, closeKernel)

        // Convert back to ARGB_8888 bitmap (alpha = mask)
        val outBgra = Mat(h, w, CvType.CV_8UC4)
        // Set alpha channel from `closed`, fill RGB with white
        val white = Mat(h, w, CvType.CV_8UC1)
        white.setTo(org.opencv.core.Scalar(255.0))
        Core.merge(listOf(white, white, white, closed), outBgra)
        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outBgra, outBmp)

        // Release Mats
        roiMat.release()
        gray.release()
        binMask.release()
        scaledMaskMat.release()
        maskChannels.forEach { it.release() }
        seed.release()
        expandedMask.release()
        combinedMask.release()
        finalMask.release()
        closed.release()
        white.release()
        outBgra.release()

        Log.i(TAG, "refined ${w}x${h} mask")
        return outBmp
    }
}
```

- [ ] **Step 4: test 재실행 → 3 PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.MaskRefinerTest" 2>&1 | tail -15
```

만약 1 fail: 알고리즘 의 boundary 조건 (예: dilate kernel 의 결과 영역) 디버깅. nonZeroPixels assertion 의 임계값 조정 가능.

- [ ] **Step 5: 전체 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 75 + 3 = 78 tests PASS.

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/MaskRefiner.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/MaskRefinerTest.kt
git commit -m "feat(mask-fix): MaskRefiner — OpenCV grayscale threshold + mask dilation union (TDD)"
```

---

## Task 2: ArapRigger 의 applyMask 전 호출 1줄 추가

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt`

- [ ] **Step 1: 현재 applyMask 호출 위치 확인**

```bash
grep -n "applyMask\|top.mask" /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt
```

ArapRigger 의 `rig()` 안에서 `applyMask(image, top.mask, top.bbox)` 호출 위치 확인.

- [ ] **Step 2: ArapRigger.kt 수정**

기존 (rig() 안에서, line ~50 부근):
```kotlin
val character = if (top != null) {
    applyMask(image, top.mask, top.bbox)
} else {
    image
}
```

수정:
```kotlin
val character = if (top != null) {
    val refinedMask = MaskRefiner.refine(image, top.bbox, top.mask)
    applyMask(image, refinedMask, top.bbox)
} else {
    image
}
```

`MaskRefiner` import 추가 (이미 같은 패키지 `rig` 이라 import 불필요. 단 IDE 가 자동 추가 가능):
```kotlin
import com.k3i.stickerbook.rig.MaskRefiner  // 같은 package 면 생략 가능
```

(같은 package 이면 import 생략 가능. compile 후 확인.)

- [ ] **Step 3: compile + test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL + 78 tests PASS.

ArapRiggerTest 가 fake detection (mask = 단색 흰 bitmap) 을 사용하므로 회귀 OK 일 가능성. 만약 ArapRiggerTest fail (refined mask 가 빈 → applyMask 가 빈 character?) → fake mask 의 패턴 조정 또는 test 명시.

- [ ] **Step 4: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt
git commit -m "feat(mask-fix): ArapRigger calls MaskRefiner before applyMask"
```

---

## Task 3: APK install + 갤탭 시연 (사용자 직접)

- [ ] **Step 1: APK install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  'C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk'
```

- [ ] **Step 2: 시연 (사용자 직접)**

1. 이전 시연 의 다리 잘림 그림 (V-pose 막대 사람) 재캡처
2. 모션 선택 → 만들기
3. 결과 sticker 의 character bitmap 확인 — 다리/머리 까지 포함되는지

체크포인트:
- [ ] 다리 끝까지 포함
- [ ] 머리 끝까지 포함
- [ ] 종이 의 다른 메모/노이즈 안 포함

- [ ] **Step 3: 결과 보고 — 사용자 평가**

만약 여전히 일부 boundary 짤림: threshold 조정 또는 dilate kernel 더 큼.

---

## Task 4: 결과 doc + memory

- [ ] **Step 1: docs/sub_mask_fix_results.md 작성**

```markdown
# Sub-Mask-Fix 결과 — Mask boundary 후처리

날짜: 2026-05-18
대상: Galaxy Tab S9 FE+ (SM-X610)

## MR.1 — MaskRefiner TDD

3 unit tests PASS:
- refined mask includes black drawing lines that original mask missed
- black pixels outside bbox not included
- empty mask returns empty refined

전체 75 + 3 = **78 tests PASS**.

## MR.2 — ArapRigger 통합

1줄 변경: `applyMask` 직전 `MaskRefiner.refine()`.

## MR.3 — 갤탭 시연

| 항목 | 이전 | 현재 |
|---|---|---|
| 다리 끝 포함 | (잘림) | (실제 확인) |
| 머리 끝 포함 | (잘림) | (실제 확인) |
| 종이 노이즈 | — | (확인) |

## 알려진 follow-up

- Threshold 100 의 적정성 — 그림 종류 따라 조정 필요할 수도
- Dilation kernel 5×5 — 큰 character boundary 짤림 시 7×7 또는 9×9 로
```

- [ ] **Step 2: memory 업데이트**

`project_phase2_progress.md`: 다음 후보 list 에서 "Sub-1 mask 정확도" 제거 (이 task 가 그것).

- [ ] **Step 3: commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add docs/sub_mask_fix_results.md
git commit -m "docs(mask-fix): MR results — boundary 후처리 시연 PASS"
```

---

## 진행 순서 요약

1. Task 1 (~30분): MaskRefiner.kt + 3 TDD
2. Task 2 (~10분): ArapRigger 1줄 통합
3. Task 3 (~15분): APK install + 시연
4. Task 4 (~15분): 결과 doc

총 ~1시간.
