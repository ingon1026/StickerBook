# Sub-GIF Animated GIF Encoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ArapRigger 가 생성하는 30 PNG frame 을 진짜 animated GIF 로 합성. Public domain `AnimatedGifEncoder` (Java) 를 프로젝트 내부에 포함 + ArapRigger 의 placeholder copyTo 를 실 encoder 호출로 교체. 갤러리 export 자동 작동.

**Architecture:** Three-file encoder (AnimatedGifEncoder + NeuQuant + LzwEncoder, ~600 lines) 를 `gif/` sub-package 에 추가 (의존성 0, Bitmap API 만 사용). ArapRigger 의 frame 저장 loop 후 encoder 로 합성. Unit test 가 GIF89a magic + frame count + Netscape extension 검증.

**Tech Stack:** Kotlin + Android Bitmap API + JUnit4 + Robolectric

**Spec:** `docs/superpowers/specs/2026-05-15-sub-gif-design.md`

---

## File Structure

### Production

| 파일 | 책임 |
|---|---|
| `app/app/src/main/java/com/k3i/stickerbook/gif/AnimatedGifEncoder.kt` | top-level API (start/setRepeat/setFrameRate/addFrame/finish) + GIF89a header/extensions/trailer |
| `app/app/src/main/java/com/k3i/stickerbook/gif/NeuQuant.kt` | NeuQuant color quantization (RGB 24bit → 256 color palette per frame) |
| `app/app/src/main/java/com/k3i/stickerbook/gif/LzwEncoder.kt` | LZW compression (per-frame indexed pixels → GIF image data) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt` | 수정 — animation.gif 합성 부분만 |

### Test

| 파일 | 책임 |
|---|---|
| `app/app/src/test/java/com/k3i/stickerbook/gif/AnimatedGifEncoderTest.kt` | 5 fake bitmap → GIF89a header + frame count + loop count 검증 |

### Docs

| 파일 | 책임 |
|---|---|
| `docs/sub_gif_results.md` | MG 결과 |

---

## Task 1: AnimatedGifEncoder + helper classes 추가

**Goal:** Public domain Java code (https://github.com/nbadal/android-gif-encoder/blob/master/AnimatedGifEncoder.java 류) 를 Kotlin port 또는 .java 그대로 포함. 의존성 0, Bitmap API 만 사용.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/gif/AnimatedGifEncoder.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/gif/NeuQuant.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/gif/LzwEncoder.kt`

- [ ] **Step 1: Public domain source 확인 + 작성**

원본: Kevin Weiner 의 java AnimatedGifEncoder + NeuQuant + LzwEncoder (1998, Public Domain).
- Reference: https://github.com/nbadal/android-gif-encoder (Public Domain port)
- 또는 search "AnimatedGifEncoder Java public domain Kevin Weiner"

3 클래스 (~600 line) 를 Kotlin 으로 port. 핵심 API + 알고리즘은 그대로.

`AnimatedGifEncoder.kt` 의 public API (시그니처 만 — 내부 구현은 reference source 따라):

```kotlin
package com.k3i.stickerbook.gif

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Public domain port of Kevin Weiner's AnimatedGifEncoder.
 * Build animated GIF89a from a sequence of Bitmap frames.
 *
 * Usage:
 *   val encoder = AnimatedGifEncoder()
 *   outputStream.use { os ->
 *       encoder.start(os)
 *       encoder.setRepeat(0)         // infinite loop
 *       encoder.setFrameRate(30f)    // 30 fps
 *       encoder.addFrame(bitmap1)
 *       encoder.addFrame(bitmap2)
 *       ...
 *       encoder.finish()
 *   }
 */
class AnimatedGifEncoder {
    private var width = 0
    private var height = 0
    private var started = false
    private var out: OutputStream? = null
    private var image: Bitmap? = null
    private var pixels: ByteArray? = null       // converted frame indexed to palette
    private var indexedPixels: ByteArray? = null
    private var colorDepth = 0
    private var colorTab: ByteArray? = null      // RGB palette
    private val usedEntry = BooleanArray(256)
    private var palSize = 7
    private var dispose = -1
    private var closeStream = false
    private var firstFrame = true
    private var sizeSet = false
    private var sample = 10  // NeuQuant sample factor
    private var repeat = -1
    private var delay = 0  // 1/100 sec per frame

    /** 0 = infinite. */
    fun setRepeat(iter: Int) {
        if (iter >= 0) repeat = iter
    }

    /** Frames per second → 1/100 sec delay between frames. */
    fun setFrameRate(fps: Float) {
        if (fps != 0f) delay = (100f / fps).toInt()
    }

    /** Start encoder. Returns true on success. */
    fun start(os: OutputStream): Boolean {
        out = os
        started = true
        return try {
            writeString("GIF89a")
            true
        } catch (e: Exception) { false }
    }

    /** Add a frame. First frame sets size. */
    fun addFrame(bm: Bitmap): Boolean {
        if (!started) return false
        return try {
            if (!sizeSet) {
                width = bm.width; height = bm.height; sizeSet = true
            }
            image = bm
            getImagePixels()
            analyzePixels()
            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) writeNetscapeExt()
            }
            writeGraphicCtrlExt()
            writeImageDesc()
            if (!firstFrame) writePalette()  // local palette per frame for accuracy
            writePixels()
            firstFrame = false
            true
        } catch (e: Exception) { false }
    }

    /** Finish encoder, write GIF trailer. */
    fun finish(): Boolean {
        if (!started) return false
        return try {
            out?.write(0x3b)  // GIF trailer
            out?.flush()
            started = false
            true
        } catch (e: Exception) { false }
    }

    // === Internal helpers — see Kevin Weiner's reference ===
    private fun getImagePixels() {
        val w = image!!.width
        val h = image!!.height
        val argb = IntArray(w * h)
        image!!.getPixels(argb, 0, w, 0, 0, w, h)
        pixels = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            val c = argb[i]
            pixels!![i * 3]     = ((c shr 16) and 0xFF).toByte()  // R
            pixels!![i * 3 + 1] = ((c shr 8) and 0xFF).toByte()   // G
            pixels!![i * 3 + 2] = (c and 0xFF).toByte()           // B
        }
    }

    private fun analyzePixels() {
        val len = pixels!!.size
        val nPix = len / 3
        indexedPixels = ByteArray(nPix)
        val nq = NeuQuant(pixels!!, len, sample)
        colorTab = nq.process()
        // Map pixels to palette indices
        for (i in 0 until nPix) {
            val r = pixels!![i * 3].toInt() and 0xFF
            val g = pixels!![i * 3 + 1].toInt() and 0xFF
            val b = pixels!![i * 3 + 2].toInt() and 0xFF
            val idx = nq.map(b, g, r)
            usedEntry[idx] = true
            indexedPixels!![i] = idx.toByte()
        }
        colorDepth = 8
        palSize = 7  // 8-bit
    }

    private fun writeString(s: String) {
        for (c in s) out?.write(c.code)
    }

    private fun writeShort(v: Int) {
        out?.write(v and 0xFF)
        out?.write((v shr 8) and 0xFF)
    }

    private fun writeLSD() {
        // Logical screen descriptor
        writeShort(width)
        writeShort(height)
        out?.write(0x80 or 0x70 or 0x00 or palSize)
        out?.write(0)  // bg color
        out?.write(0)  // aspect ratio
    }

    private fun writePalette() {
        out?.write(colorTab!!)
        val n = (3 * 256) - colorTab!!.size
        for (i in 0 until n) out?.write(0)
    }

    private fun writeGraphicCtrlExt() {
        out?.write(0x21)
        out?.write(0xf9)
        out?.write(4)
        val disp = if (dispose >= 0) dispose else 0
        out?.write(0 or (disp shl 2) or 0 or 0)
        writeShort(delay)
        out?.write(0)  // transparent index
        out?.write(0)
    }

    private fun writeImageDesc() {
        out?.write(0x2c)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        if (firstFrame) out?.write(0)
        else out?.write(0x80 or 0 or 0 or 0 or palSize)
    }

    private fun writeNetscapeExt() {
        out?.write(0x21); out?.write(0xff); out?.write(11)
        writeString("NETSCAPE2.0")
        out?.write(3); out?.write(1)
        writeShort(repeat)
        out?.write(0)
    }

    private fun writePixels() {
        val encoder = LzwEncoder(width, height, indexedPixels!!, colorDepth)
        encoder.encode(out!!)
    }
}
```

`NeuQuant.kt` 와 `LzwEncoder.kt` 은 reference source (Kevin Weiner 의 NeuQuant.java + LzwEncoder.java) 를 그대로 Kotlin port. 양 ~300 lines + ~150 lines. **알고리즘 변경 X — 단순 직역**.

NeuQuant 의 public API:
```kotlin
class NeuQuant(thepic: ByteArray, len: Int, sample: Int) {
    fun process(): ByteArray  // returns RGB palette of size 256 * 3
    fun map(b: Int, g: Int, r: Int): Int  // RGB → palette index
}
```

LzwEncoder 의 public API:
```kotlin
class LzwEncoder(width: Int, height: Int, pixels: ByteArray, colorDepth: Int) {
    fun encode(os: OutputStream)
}
```

- [ ] **Step 2: 3 파일 작성**

위 `AnimatedGifEncoder.kt` 그대로 + NeuQuant.kt / LzwEncoder.kt 은 reference port. Implementer 가 web search 로 Kevin Weiner public domain Java source 가져와서 Kotlin port.

검색 query 예:
- `AnimatedGifEncoder.java public domain Kevin Weiner`
- `NeuQuant.java site:github.com Kevin Weiner`
- `LzwEncoder.java GIF89a Kevin Weiner`

- [ ] **Step 3: compile 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:compileDebugKotlin 2>&1 | tail -10
```

Expected: SUCCESS.

- [ ] **Step 4: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/gif/
git commit -m "feat(gif): add public domain AnimatedGifEncoder + NeuQuant + LzwEncoder (Kotlin port)"
```

---

## Task 2: Unit test — GIF89a header + frame count 검증 (TDD)

**Goal:** 5 frame fake bitmap → encode → output 의 GIF89a magic + frame descriptor count + Netscape extension 검증.

**Files:**
- Create: `app/app/src/test/java/com/k3i/stickerbook/gif/AnimatedGifEncoderTest.kt`

- [ ] **Step 1: failing test 작성**

```kotlin
package com.k3i.stickerbook.gif

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AnimatedGifEncoderTest {

    private fun makeBitmap(w: Int, h: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    @Test
    fun `encoded GIF starts with GIF89a magic`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        assertTrue(enc.start(out))
        enc.setRepeat(0)
        enc.setFrameRate(30f)
        assertTrue(enc.addFrame(makeBitmap(10, 10, Color.RED)))
        assertTrue(enc.finish())

        val bytes = out.toByteArray()
        assertEquals('G'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('8'.code.toByte(), bytes[3])
        assertEquals('9'.code.toByte(), bytes[4])
        assertEquals('a'.code.toByte(), bytes[5])
    }

    @Test
    fun `encoded GIF has 5 image descriptors for 5 frames`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        enc.start(out); enc.setRepeat(0); enc.setFrameRate(30f)
        for (i in 0 until 5) {
            val color = when (i) {
                0 -> Color.RED
                1 -> Color.GREEN
                2 -> Color.BLUE
                3 -> Color.YELLOW
                else -> Color.MAGENTA
            }
            enc.addFrame(makeBitmap(10, 10, color))
        }
        enc.finish()

        val bytes = out.toByteArray()
        // Each frame includes an Image Descriptor starting with 0x2c.
        // Trailer is 0x3b (end of GIF, only one).
        val imgDescriptors = bytes.count { it == 0x2c.toByte() }
        assertEquals(5, imgDescriptors, "Expected 5 image descriptors (one per frame)")
        val trailers = bytes.count { it == 0x3b.toByte() }
        assertEquals(1, trailers, "Expected exactly one GIF trailer")
    }

    @Test
    fun `encoded GIF has Netscape extension for infinite loop`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        enc.start(out); enc.setRepeat(0); enc.setFrameRate(30f)
        enc.addFrame(makeBitmap(10, 10, Color.WHITE))
        enc.addFrame(makeBitmap(10, 10, Color.BLACK))
        enc.finish()

        val bytes = out.toByteArray()
        // Netscape extension signature: "NETSCAPE2.0"
        val signature = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
        val found = (0..(bytes.size - signature.size)).any { i ->
            (signature.indices).all { j -> bytes[i + j] == signature[j] }
        }
        assertTrue(found, "Netscape extension 'NETSCAPE2.0' should be present for infinite loop")
    }

    @Test
    fun `frame rate sets per-frame delay`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        enc.start(out); enc.setRepeat(0); enc.setFrameRate(30f)  // delay = 100/30 = 3 (1/100 sec)
        enc.addFrame(makeBitmap(10, 10, Color.RED))
        enc.finish()

        val bytes = out.toByteArray()
        // Graphic Control Extension: 0x21 0xF9 0x04 <packed> <delayLo> <delayHi> <transparentIdx> 0x00
        // Delay = 3 = 0x0003 in little-endian
        // Find pattern 0x21 0xF9 0x04
        var i = 0
        var foundDelay: Int? = null
        while (i < bytes.size - 8) {
            if (bytes[i] == 0x21.toByte() && bytes[i+1] == 0xF9.toByte() && bytes[i+2] == 0x04.toByte()) {
                foundDelay = (bytes[i+4].toInt() and 0xFF) or ((bytes[i+5].toInt() and 0xFF) shl 8)
                break
            }
            i++
        }
        assertEquals(3, foundDelay, "delay should be 3 (1/100 sec, derived from 30 fps)")
    }
}
```

- [ ] **Step 2: test 실행 → fail or pass**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.AnimatedGifEncoderTest" 2>&1 | tail -10
```

만약 첫 시도 fail — encoder 의 일부 op 에러 (NeuQuant 의 process 시 비정상 값 등) → debug + fix.

- [ ] **Step 3: 4 tests PASS 확인**

각 test 가 PASS:
- GIF89a magic
- 5 image descriptors
- Netscape extension for infinite loop
- frame rate → delay (3 for 30 fps)

- [ ] **Step 4: 전체 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 64 + 4 = **68 tests PASS**.

- [ ] **Step 5: commit**

```bash
git add app/app/src/test/java/com/k3i/stickerbook/gif/AnimatedGifEncoderTest.kt
git commit -m "test(gif): AnimatedGifEncoder TDD — magic/descriptors/Netscape/delay"
```

---

## Task 3: ArapRigger 의 animation.gif 합성

**Goal:** ArapRigger 의 placeholder copyTo 를 실 encoder 호출로 교체.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt`

- [ ] **Step 1: ArapRigger.kt 의 animation.gif 합성 부분 수정**

기존 (ArapRigger.kt 의 rig() 메서드 내):
```kotlin
// animation.gif placeholder = first frame copy (real GIF encoding is a follow-up)
File(framesDir, "0001.png").copyTo(File(sDir, "animation.gif"), overwrite = true)
```

변경:
```kotlin
import android.graphics.BitmapFactory
import com.k3i.stickerbook.gif.AnimatedGifEncoder

// ... rig() 내부:
val gifFile = File(sDir, "animation.gif")
gifFile.outputStream().use { os ->
    val encoder = AnimatedGifEncoder()
    encoder.start(os)
    encoder.setRepeat(0)         // infinite loop
    encoder.setFrameRate(30f)    // 30 fps (1/100 sec delay = 3)
    for (i in 1..FRAME_COUNT) {
        val name = i.toString().padStart(4, '0') + ".png"
        val frame = BitmapFactory.decodeFile(File(framesDir, name).absolutePath)
        encoder.addFrame(frame)
        frame.recycle()
    }
    encoder.finish()
}
Log.i(TAG, "encoded animation.gif (${gifFile.length()} bytes)")
```

- [ ] **Step 2: compile 확인**

```bash
./run-gradle.sh app:compileDebugKotlin 2>&1 | tail -5
```

Expected: SUCCESS.

- [ ] **Step 3: 전체 test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 68 tests PASS (변경 없음).

Note: `ArapRiggerTest` 의 기존 test (`rig produces 30 frame pngs and a 30 frame RigResult`) 가 animation.gif 도 함께 생성됨. test 가 .gif 파일 존재만 확인 (현재 single-PNG-copy 든 진짜 GIF 든 file 자체 존재 같음). 회귀 PASS.

- [ ] **Step 4: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt
git commit -m "feat(gif): ArapRigger encodes real animated GIF (30 frame, 30fps, infinite loop)"
```

---

## Task 4: APK build + 갤탭 시연

**Goal:** 새 sticker 만들고 갤러리 저장 → image viewer 에서 30 frame 애니메이션 재생 확인.

- [ ] **Step 1: APK build**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh assembleDebug 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: 갤탭 install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  'C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk'
```

- [ ] **Step 3: 갤탭 시연 (사용자 직접)**

1. 앱 launch → + FAB → 카메라 → 손그림 캡처
2. ▶ → 모션 선택 → 만들기 ▶
3. 결과 sticker → 상세 화면
4. "갤러리에 저장" 버튼 클릭
5. 갤탭 의 **Photos 앱** (또는 Samsung Gallery) → Pictures/Stickerbook 폴더 찾기
6. GIF 파일 클릭 → 30 frame 애니메이션 재생 확인

- [ ] **Step 4: 결과 확인 (adb shell)**

만들어진 GIF 파일의 byte size 확인:
```bash
ADB=/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe
LATEST=$($ADB shell "run-as com.k3i.stickerbook ls files/stickerbook_assets/stickers/" 2>&1 | grep "^arap" | sort -r | head -1 | tr -d '\r')
$ADB shell "run-as com.k3i.stickerbook ls -la files/stickerbook_assets/stickers/$LATEST/animation.gif"
```

Expected: byte size > 30 * (단일 PNG size). 예: 단일 PNG 200KB, 30 frame GIF 약 1-3MB (NeuQuant + LZW 압축).

만약 byte size 가 단일 PNG 와 비슷 (~200KB) → 여전히 placeholder. ArapRigger 의 변경 적용 안 됨 — debug.

- [ ] **Step 5: 별도 device 에서 GIF 공유 테스트 (옵션)**

KakaoTalk, Slack 등으로 갤러리의 GIF 공유 → 다른 device 에서 정상 재생 확인.

- [ ] **Step 6: commit (사용자 시연 결과)**

시연 결과는 Task 5 의 결과 doc 에서. 이 task 자체 commit X.

---

## Task 5: 결과 doc + memory 업데이트

**Files:**
- Create: `docs/sub_gif_results.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`

- [ ] **Step 1: docs/sub_gif_results.md 작성**

```markdown
# Sub-GIF 결과 — Animated GIF encoding

날짜: 2026-05-1X
대상: Galaxy Tab S9 FE+ (SM-X610)

## MG.1 — AnimatedGifEncoder + helpers 추가

3 파일 (AnimatedGifEncoder.kt, NeuQuant.kt, LzwEncoder.kt) public domain Kotlin port. 의존성 0, Bitmap API 만 사용.

## MG.2 — Unit test (4 PASS)

- GIF89a magic
- 5 image descriptors (one per frame)
- Netscape extension for infinite loop
- delay = 3 (1/100 sec, derived from 30 fps)

기존 64 + 4 = **68 tests PASS**.

## MG.3 — ArapRigger 의 GIF 합성

기존 placeholder (single PNG copyTo) → AnimatedGifEncoder 호출. 30 frame, 30 fps, infinite loop GIF.

## MG.4 — 갤탭 시연

| 항목 | 측정값 | PASS |
|---|---|---|
| GIF byte size | (실제) MB | ✅ (단일 PNG 대비 30배 가까이) |
| 갤탭 Photos 앱 에서 재생 | (시각 확인) | ✅ |
| KakaoTalk 공유 후 재생 | (옵션) | ✅ |

## 알려진 이슈 / Follow-up

- ⚠️ NeuQuant 의 256 color quantization 으로 손그림 색 손실 가능 (보통 안 보임)
- ⚠️ GIF 인코딩 시간 ~1-3초 (30 frame × NeuQuant) — 전체 latency 의 일부
- Sub-AR (다음 sub) 에서 camera overlay 시 GIF 활용 가능 (또는 frame PNG sequence 직접)
```

- [ ] **Step 2: memory 업데이트**

`project_phase2_progress.md` 의 후속 follow-up 섹션에서 "Sub-3 GIF encoding" 제거. 새 항목 추가:
```
- ✅ Sub-GIF 완료 (AnimatedGifEncoder, animation.gif 실 합성)
- 다음 우선: AR overlay (camera preview + sticker)
```

`MEMORY.md` 의 Phase 2 줄 update.

- [ ] **Step 3: commit**

```bash
git add docs/sub_gif_results.md
git commit -m "docs(gif): MG results — real animated GIF encoding, 30 frame share PASS"
```

---

## 진행 순서 요약

1. **Task 1 (~1-2시간)**: AnimatedGifEncoder + NeuQuant + LzwEncoder Kotlin port (~600 lines)
2. **Task 2 (~30분)**: Unit test 4 PASS
3. **Task 3 (~15분)**: ArapRigger 의 placeholder → encoder
4. **Task 4 (~15분)**: APK build + install + 갤탭 시연
5. **Task 5 (~20분)**: 결과 doc + memory

총 ~3시간 예상.

## 핵심 작업: Public domain source 의 Kotlin port

implementer 는 Kevin Weiner 의 1998 public domain Java code 를 reference 로:
- `AnimatedGifEncoder.java` (~250 lines)
- `NeuQuant.java` (~300 lines)
- `LzwEncoder.java` (~150 lines)

Kotlin 으로 직역 (알고리즘 변경 X). 자세한 source 는 nbadal/android-gif-encoder, ResearchGate, archive.org 등에서 확인 가능. 의존성 0 (`OutputStream`, `Bitmap` 만 사용).
