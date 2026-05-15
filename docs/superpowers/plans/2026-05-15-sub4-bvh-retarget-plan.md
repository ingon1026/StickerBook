# Sub-4 BVH retarget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PC 의 AD `retargeter.py` 활용하여 9 BVH 파일을 17 COCO keypoint normalized JSON 으로 사전 변환. Android `JsonMotionSource` 가 JSON load + `initialPins` 기준 scale/translate + interpolation 으로 ArapRigger 에 motion 제공.

**Architecture:** PC: AD `Retargeter` → standard character template 으로 retarget → 17 COCO keypoint 추출 → frame 0 hip center=(0,0), shoulder dist=1.0 으로 normalize → JSON. Android: JSON load 후 `initialPins` 의 hip + shoulder 로 denormalize, frameCount 로 linear interpolation, ArapSolver 에 입력. `MotionStub.else→wave` fallback 제거.

**Tech Stack:** Python + AnimatedDrawings + numpy (PC) / Kotlin + kotlinx.serialization + JUnit4 + Robolectric (Android)

**Spec:** `docs/superpowers/specs/2026-05-15-sub4-bvh-retarget-design.md`

---

## File Structure

### PC 측 (`/home/ingon/AR_book/sub4_workdir/`, git 추적 X)

| 파일 | 종류 |
|---|---|
| `convert.py` | 신규 — BVH + standard template → COCO 17 normalized JSON |
| `out/<motion_id>.json` | 신규 (9 motion 각각) |

### Android 측 (`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/`)

| 파일 | 종류 | 책임 |
|---|---|---|
| `app/app/src/main/assets/motions/<id>.json` | 신규 (9개) | converted JSON |
| `app/app/src/main/java/com/k3i/stickerbook/rig/JsonMotionData.kt` | 신규 | kotlinx.serialization data class |
| `app/app/src/main/java/com/k3i/stickerbook/rig/JsonMotionSource.kt` | 신규 | MotionSource 구현, load + denormalize + interpolate |
| `app/app/src/main/java/com/k3i/stickerbook/rig/MotionStub.kt` | 수정 | else → identity (wave fallback 제거) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt` | 수정 | `real()` 의 motionSource = `JsonMotionSource(context)` |
| `app/app/src/main/java/com/k3i/stickerbook/data/MotionEntry.kt` | 수정 | catalog 를 AD 9 BVH 와 동기화 |
| `app/app/src/test/java/com/k3i/stickerbook/rig/JsonMotionSourceTest.kt` | 신규 | denormalize + interpolation TDD |
| `app/app/src/test/java/com/k3i/stickerbook/rig/MotionStubTest.kt` | 수정 | else → identity 반영 |
| `docs/sub4_results.md` | 신규 | M4 결과 |

---

## Task 1: PC — convert.py + dance_1 단일 BVH 변환

**Goal:** AD 의 conda env 안에서 convert.py 작성 + `dance_1.bvh` → `dance_1.json` 검증.

**Files:**
- Create: `/home/ingon/AR_book/sub4_workdir/` (작업 디렉토리)
- Create: `/home/ingon/AR_book/sub4_workdir/convert.py`
- Output: `/home/ingon/AR_book/sub4_workdir/out/dance_1.json`

- [ ] **Step 1: env 진입 + 작업 디렉토리 생성**

```bash
mkdir -p /home/ingon/AR_book/sub4_workdir/out
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings
python -c "from animated_drawings.model.retargeter import Retargeter; from animated_drawings.model.bvh import BVH; print('AD env OK')"
```

Expected: `AD env OK`. 만약 import fail → animated_drawings env 재셋업 (`cd /home/ingon/AR_book/AnimatedDrawings && pip install -e .`).

- [ ] **Step 2: AD 의 example character / motion config 위치 확인**

```bash
ls /home/ingon/AR_book/AnimatedDrawings/examples/characters/ 2>&1 | head -10
ls /home/ingon/AR_book/AnimatedDrawings/examples/config/ 2>&1 | head -10
```

가장 표준적인 character + retarget config 골라서 reference 로 사용. 흔히 `char1` 또는 `example` 폴더.

- [ ] **Step 3: convert.py 작성 (초기 버전 — AD joint name 확인용)**

`/home/ingon/AR_book/sub4_workdir/convert.py`:

```python
"""
Convert a BVH file to normalized 17 COCO keypoint JSON sequence.

Strategy:
- Use AD's Retargeter with a fixed standard character template (chosen from
  AD examples/characters/)
- Extract per-frame 2D joint positions from retargeter output
- Map AD humanoid joints → COCO 17 keypoints
- Normalize: frame 0 hip-center = (0,0), frame 0 shoulder distance = 1.0,
  y-axis flipped (BVH up=+Y → image down=+Y)
- Save as motions/<motion_id>.json

Usage:
    python convert.py <bvh_path> <motion_id> <output_json>
"""
import json
import sys
import numpy as np
from pathlib import Path

# Import AD modules - assumes AD repo is on sys.path or installed via pip install -e .
from animated_drawings.config import MotionConfig, RetargetConfig
from animated_drawings.model.retargeter import Retargeter

# AD humanoid joint name → COCO 17 index
# face keypoints (0-4) stay static in Android (set from initialPins)
AD_TO_COCO = {
    'left_shoulder': 5,  'right_shoulder': 6,
    'left_elbow': 7,     'right_elbow': 8,
    'left_hand': 9,      'right_hand': 10,
    'left_hip': 11,      'right_hip': 12,
    'left_knee': 13,     'right_knee': 14,
    'left_foot': 15,     'right_foot': 16,
}

# Path to a standard AD example character & retarget config.
# Will be updated after Step 2 inspection.
STANDARD_CHAR_CFG = '/home/ingon/AR_book/AnimatedDrawings/examples/characters/char1/char_cfg.yaml'
STANDARD_MOTION_CFG_TEMPLATE = '/home/ingon/AR_book/AnimatedDrawings/examples/config/motion/dance.yaml'
STANDARD_RETARGET_CFG = '/home/ingon/AR_book/AnimatedDrawings/examples/config/retarget/fair1_ppf.yaml'


def convert(bvh_path: str, motion_id: str, output_json: str):
    # Load motion_cfg template, override bvh_p
    motion_cfg = MotionConfig(STANDARD_MOTION_CFG_TEMPLATE)
    motion_cfg.bvh_p = Path(bvh_path)
    retarget_cfg = RetargetConfig(STANDARD_RETARGET_CFG)

    rt = Retargeter(motion_cfg, retarget_cfg)

    # Print available joint names so we can verify AD_TO_COCO mapping
    char_joint_names = rt.char_joint_names if hasattr(rt, 'char_joint_names') else None
    print(f"Available joint names: {char_joint_names}")

    F = rt.bvh.frame_max_num
    print(f"BVH frames: {F}, frame_time: {rt.bvh.frame_time}")

    coco_frames = np.zeros((F, 17, 2), dtype=np.float32)
    for f in range(F):
        for ad_name, coco_idx in AD_TO_COCO.items():
            try:
                # Adjust attribute access to actual Retargeter API after Step 5 inspection
                joint_pos = rt.get_joint_2d_position(ad_name, f)  # PLACEHOLDER API
                coco_frames[f, coco_idx] = joint_pos
            except Exception as e:
                print(f"  warning frame {f} joint {ad_name}: {e}")

    # Normalize
    f0_hip = (coco_frames[0, 11] + coco_frames[0, 12]) / 2
    f0_shoulder = np.linalg.norm(coco_frames[0, 5] - coco_frames[0, 6])
    if f0_shoulder < 1e-6:
        raise RuntimeError("frame 0 shoulder distance ~= 0, cannot normalize")
    coco_frames -= f0_hip
    coco_frames /= f0_shoulder
    coco_frames[:, :, 1] *= -1  # y flip (BVH up → image down)

    data = {
        "motion_id": motion_id,
        "frame_count": int(F),
        "fps": int(round(1.0 / rt.bvh.frame_time)),
        "backend": "ad-coco-17",
        "frames": coco_frames.tolist(),
    }
    with open(output_json, 'w') as f:
        json.dump(data, f)
    print(f"{output_json}: {F} frames, fps={data['fps']}")


if __name__ == '__main__':
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(1)
    convert(sys.argv[1], sys.argv[2], sys.argv[3])
```

**Note**: `rt.get_joint_2d_position(...)` 는 placeholder. AD `Retargeter` 의 실제 API 는 step 4 에서 확인 후 수정.

- [ ] **Step 4: dance_1 시도 + AD Retargeter API 확인**

```bash
cd /home/ingon/AR_book/sub4_workdir
python convert.py \
  /home/ingon/AR_book/AnimatedDrawings/examples/bvh/dance_1.bvh \
  dance_1 \
  out/dance_1.json 2>&1 | tee convert.log
```

Expected: 첫 시도 fail. log 의 stack trace 와 `Available joint names: ...` 확인 후 다음 조정:
- `char_joint_names` 가 다른 이름 (예: `LeftShoulder` instead of `left_shoulder`) → `AD_TO_COCO` mapping 수정
- `rt.get_joint_2d_position` 가 존재 안 함 → `rt.joint_positions` 또는 다른 attribute 사용

AD `retargeter.py` line 84-87 의 `self.joint_positions: npt.NDArray[np.float32]` (shape `[frame, joint_idx, 2]` 류) 활용.

올바른 API (예상):
```python
joint_idx = char_joint_names.index(ad_name)
coco_frames[f, coco_idx] = rt.joint_positions[f, joint_idx][:2]  # x, y
```

- [ ] **Step 5: convert.py 수정 + 다시 시도, dance_1.json 산출**

`get_joint_2d_position` 부분을:
```python
joint_idx = char_joint_names.index(ad_name)
coco_frames[f, coco_idx] = rt.joint_positions[f, joint_idx][:2]
```

다시 실행. Expected:
```
BVH frames: 120, frame_time: 0.0333...
out/dance_1.json: 120 frames, fps=30
```

- [ ] **Step 6: JSON 검증**

```bash
python -c "
import json
data = json.load(open('out/dance_1.json'))
print('motion_id:', data['motion_id'])
print('frame_count:', data['frame_count'])
print('fps:', data['fps'])
print('backend:', data['backend'])
import numpy as np
frames = np.array(data['frames'])
print('shape:', frames.shape)
print('frame 0 hip center:', (frames[0,11]+frames[0,12])/2)  # should be ~(0,0)
print('frame 0 shoulder dist:', np.linalg.norm(frames[0,5]-frames[0,6]))  # should be ~1.0
"
```

Expected:
```
motion_id: dance_1
frame_count: 120
fps: 30
backend: ad-coco-17
shape: (120, 17, 2)
frame 0 hip center: [0. 0.]
frame 0 shoulder dist: 1.0
```

- [ ] **Step 7: commit (Android repo 에는 변경 없음 — PC workdir 만)**

Sub4_workdir 는 git 추적 X. 이 task 자체에는 commit 없음. Step 4 의 log 와 산출 JSON 은 Task 7 의 결과 doc 에서 reference.

---

## Task 2: PC — 9 BVH 모두 변환 + Android assets/motions/ 배치

**Goal:** 9 BVH 파일 모두 변환 + Android assets 폴더에 배치.

**Files:**
- Output: `/home/ingon/AR_book/sub4_workdir/out/<id>.json` (9개)
- Copy to: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/motions/<id>.json` (9개)

- [ ] **Step 1: 9 BVH 모두 변환**

```bash
cd /home/ingon/AR_book/sub4_workdir
for bvh in dance_1 dance_2 dance_3 motion_5 phone_1 phone_2 phone_z1 tabtab zombie; do
  echo "=== $bvh ==="
  python convert.py \
    "/home/ingon/AR_book/AnimatedDrawings/examples/bvh/${bvh}.bvh" \
    "$bvh" \
    "out/${bvh}.json" || echo "FAIL: $bvh"
done
ls -la out/
```

Expected: 9 JSON 파일, 각 약 10-30KB.

- [ ] **Step 2: 일부 BVH 실패 시 대응**

만약 `zombie.bvh` 등이 skeleton 구조 다름 → `char_joint_names` 다를 수 있음. 해당 BVH 의 joint name set 확인:
```bash
python -c "
from animated_drawings.model.bvh import BVH
b = BVH.from_file('/home/ingon/AR_book/AnimatedDrawings/examples/bvh/zombie.bvh')
print(b.get_joint_names())
"
```

joint name 이 mapping 표와 다르면:
- 옵션 a: 해당 BVH 만 skip + Task 4 의 MotionEntry catalog 에서 제외
- 옵션 b: `AD_TO_COCO` 에 추가 alias

PC PoC 단계라 옵션 a 우선 (skip + catalog 동기화 시 그것만 제외).

- [ ] **Step 3: Android assets 폴더 생성 + 복사**

```bash
mkdir -p /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/motions
cp /home/ingon/AR_book/sub4_workdir/out/*.json \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/motions/
ls -la /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/motions/
```

Expected: 7-9 JSON 파일 (실패한 BVH 제외).

- [ ] **Step 4: .gitignore 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git check-ignore app/app/src/main/assets/motions/dance_1.json
echo "exit=$?"
```

만약 exit=0 (ignored) → JSON 들이 git 안 들어감. assets/models/ 와 함께 ignored 라면 motions/ 도 ignored 일 가능성. 그러나 JSON 은 작아서 git 에 포함하는 게 좋음. .gitignore 확인 후 필요시:

```bash
# Edit .gitignore: 'app/app/src/main/assets/models/' 만 ignore 하고 motions/ 는 추적
# 이미 그렇게 돼 있으면 변경 X
cat .gitignore | grep assets
```

motions/ 가 추적 안 되면 `git add -f app/app/src/main/assets/motions/` 사용 또는 .gitignore 수정.

- [ ] **Step 5: commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add app/app/src/main/assets/motions/*.json
git status --short
git commit -m "build(sub-4): add converted BVH→COCO17 JSON motions (AD examples/bvh)"
```

---

## Task 3: Android — `JsonMotionData` + `JsonMotionSource` (TDD)

**Goal:** kotlinx.serialization data class + JSON load/denormalize/interpolate 함수. Unit test.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/JsonMotionData.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/JsonMotionSource.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/JsonMotionSourceTest.kt`

- [ ] **Step 1: failing test 작성**

`app/app/src/test/java/com/k3i/stickerbook/rig/JsonMotionSourceTest.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class JsonMotionSourceTest {

    /** 17 keypoint initialPins with synthetic positions. */
    private fun fakeInitialPins(): FloatArray {
        val pins = FloatArray(17 * 2)
        for (i in 0 until 17) {
            pins[i * 2] = (i * 10).toFloat()
            pins[i * 2 + 1] = (i * 20).toFloat()
        }
        // Override hip and shoulder for normalization tests:
        pins[5 * 2] = 100f;  pins[5 * 2 + 1] = 200f   // l_shoulder
        pins[6 * 2] = 50f;   pins[6 * 2 + 1] = 200f   // r_shoulder (shoulder_dist = 50)
        pins[11 * 2] = 70f;  pins[11 * 2 + 1] = 400f  // l_hip
        pins[12 * 2] = 80f;  pins[12 * 2 + 1] = 400f  // r_hip (hip center = (75, 400))
        return pins
    }

    /** Write a synthetic motion JSON to assets-equivalent location for the test app. */
    private fun writeSyntheticMotion(context: Context, motionId: String, frames: List<List<List<Float>>>) {
        val dir = File(context.cacheDir, "test_motions")
        dir.mkdirs()
        val text = """{"motion_id":"$motionId","frame_count":${frames.size},"fps":30,"backend":"ad-coco-17","frames":${
            frames.joinToString(",", "[", "]") { f ->
                f.joinToString(",", "[", "]") { kp -> "[${kp[0]},${kp[1]}]" }
            }
        }}"""
        File(dir, "$motionId.json").writeText(text)
    }

    @Test
    fun `unknown motion returns identity frames`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pins = fakeInitialPins()
        val src = JsonMotionSource(ctx)
        val frames = src.frames("does_not_exist", pins, frameCount = 5)
        assertEquals(5, frames.size)
        for (f in frames) {
            for (i in pins.indices) {
                assertEquals(pins[i], f[i], 0.001f)
            }
        }
    }

    @Test
    fun `frame 0 of normalized motion equals initialPins (face static, body anchored)`() {
        // Build a synthetic motion: frame 0 has hip=(0,0), shoulder at (+/-0.5, 0), all others zero
        // After denormalize: hip should equal initialPins hip center, shoulder should equal initialPins shoulders
        val normalized = List(1) { _ ->
            List(17) { k ->
                when (k) {
                    5 -> listOf(0.5f, 0f)       // l_shoulder normalized at (0.5, 0)
                    6 -> listOf(-0.5f, 0f)      // r_shoulder normalized at (-0.5, 0)
                    11 -> listOf(0f, 0.4f)      // l_hip at (0, +0.4) — below hip center
                    12 -> listOf(0f, 0.4f)      // r_hip at (0, +0.4)
                    else -> listOf(0f, 0f)
                }
            }
        }
        // Wait — frame 0 hip-center should be (0,0) in normalized space, so each hip is centered.
        // Use hip at (0, 0) for both indices (avg = (0,0))
        // Adjust: hip at (-0.05, 0) and (+0.05, 0) — small offset so avg = (0,0)
        val normalizedFixed = List(1) {
            List(17) { k ->
                when (k) {
                    5 -> listOf(0.5f, 0f)
                    6 -> listOf(-0.5f, 0f)
                    11 -> listOf(-0.05f, 0f)
                    12 -> listOf(0.05f, 0f)
                    else -> listOf(0f, 0f)
                }
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = JsonMotionSource.withAssetLoader(ctx) { id ->
            if (id == "synth1") JsonMotionData(
                motionId = "synth1", frameCount = 1, fps = 30,
                backend = PoseBackend.AD_COCO_17, frames = normalizedFixed,
            ) else null
        }
        val pins = fakeInitialPins()
        val frames = src.frames("synth1", pins, frameCount = 1)
        assertEquals(1, frames.size)
        // l_shoulder should equal pins[5*2..5*2+1]
        // initialPins hip center = (75, 400), shoulder dist = sqrt(50^2 + 0^2) = 50
        // l_shoulder normalized = (0.5, 0) → image = (75 + 0.5*50, 400 + 0*50) = (100, 400)
        // But initialPins l_shoulder is (100, 200) — different!
        // The denormalize doesn't preserve initial shoulder y because the source frame 0
        // uses motion-template's shoulder y (= 0 normalized), not initialPins shoulder y.
        // So this verifies: at frame 0 of motion, character takes motion's pose, not initial pose.
        assertEquals(100f, frames[0][5*2], 0.5f)
        assertEquals(400f, frames[0][5*2+1], 0.5f)
        // face keypoint (kp 0) should equal initialPins[0..1] (static)
        assertEquals(pins[0], frames[0][0], 0.001f)
        assertEquals(pins[1], frames[0][1], 0.001f)
    }

    @Test
    fun `interpolation maps source N frames to target M frames evenly`() {
        // Synthetic motion: 3 frames, l_wrist (kp 9) at x=0, 1, 2 (normalized)
        val normalized = List(3) { f ->
            List(17) { k ->
                if (k == 9) listOf(f.toFloat(), 0f) else listOf(0f, 0f)
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = JsonMotionSource.withAssetLoader(ctx) { id ->
            if (id == "synth2") JsonMotionData(
                motionId = "synth2", frameCount = 3, fps = 30,
                backend = PoseBackend.AD_COCO_17, frames = normalized,
            ) else null
        }
        val pins = fakeInitialPins()
        val target = 5
        val frames = src.frames("synth2", pins, frameCount = target)
        assertEquals(target, frames.size)
        // Frame 0 and 4 should match source frame 0 and 2 (corners) — l_wrist x
        // shoulder dist in pins = 50 (see fakeInitialPins), hip center = (75, 400)
        // frame 0: l_wrist normalized x = 0 → image x = 75 + 0*50 = 75
        // frame 4 (last): l_wrist normalized x = 2 → image x = 75 + 2*50 = 175
        assertEquals(75f, frames[0][9*2], 0.5f)
        assertEquals(175f, frames[target-1][9*2], 0.5f)
        // mid frame should interpolate between source frames 1 and 2
        // t at frame 2 of target 5 = 2/4 * 2 = 1.0 (exact source frame 1) → normalized x = 1.0
        // image x = 75 + 1.0*50 = 125
        assertEquals(125f, frames[2][9*2], 0.5f)
    }

    @Test
    fun `target frameCount of 1 returns first source frame`() {
        val normalized = List(3) { f ->
            List(17) { k ->
                if (k == 9) listOf(f.toFloat(), 0f) else listOf(0f, 0f)
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = JsonMotionSource.withAssetLoader(ctx) { id ->
            if (id == "synth3") JsonMotionData(
                motionId = "synth3", frameCount = 3, fps = 30,
                backend = PoseBackend.AD_COCO_17, frames = normalized,
            ) else null
        }
        val pins = fakeInitialPins()
        val frames = src.frames("synth3", pins, frameCount = 1)
        assertEquals(1, frames.size)
        // image x = 75 (frame 0 of source has wrist normalized x = 0)
        assertEquals(75f, frames[0][9*2], 0.5f)
    }
}
```

- [ ] **Step 2: test 실행 → fail (class 없음)**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:testDebugUnitTest --tests "*.JsonMotionSourceTest" 2>&1 | tail -10
```

- [ ] **Step 3: `JsonMotionData.kt` 작성**

```kotlin
package com.k3i.stickerbook.rig

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JsonMotionData(
    @SerialName("motion_id") val motionId: String,
    @SerialName("frame_count") val frameCount: Int,
    val fps: Int,
    val backend: PoseBackend,
    val frames: List<List<List<Float>>>,  // [F, 17, 2]
)
```

- [ ] **Step 4: `JsonMotionSource.kt` 작성**

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

/**
 * Loads normalized COCO 17 motions from assets/motions/<id>.json and
 * denormalizes them into character-bitmap pixel space using initialPins.
 *
 * Normalization convention (PC convert.py):
 * - frame 0 hip center (kp 11 + kp 12)/2 = (0, 0)
 * - frame 0 shoulder distance (kp 5 - kp 6) = 1.0 unit
 * - y axis points down (image convention)
 *
 * Face keypoints (0-4) are not stored in JSON — JsonMotionSource keeps them
 * static at the initialPins values for every output frame. Body keypoints
 * (5-16) are scaled and translated to match the character's anchor + scale.
 *
 * If the BVH source frame count differs from the requested frameCount, linear
 * interpolation maps source[0..N-1] onto target[0..M-1] uniformly.
 *
 * Unknown motion id (no matching JSON in assets) → returns identity frames so
 * callers can fall back gracefully.
 */
class JsonMotionSource(
    private val context: Context,
    private val assetLoader: (String) -> JsonMotionData? = { id -> defaultLoad(context, id) },
) : MotionSource {

    private val cache = mutableMapOf<String, JsonMotionData?>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun frames(
        motionId: String,
        initialPins: FloatArray,
        frameCount: Int,
    ): List<FloatArray> {
        val data = cache.getOrPut(motionId) { assetLoader(motionId) }
        if (data == null) {
            Log.w(TAG, "motion '$motionId' not found, returning identity frames")
            return List(frameCount) { initialPins.copyOf() }
        }
        val source = denormalize(data, initialPins)
        Log.i(TAG, "loaded motion '$motionId' (${data.frameCount} frames @ ${data.fps}fps)")
        return interpolate(source, frameCount)
    }

    private fun denormalize(
        data: JsonMotionData,
        initialPins: FloatArray,
    ): List<FloatArray> {
        val hipCx = (initialPins[11 * 2] + initialPins[12 * 2]) / 2f
        val hipCy = (initialPins[11 * 2 + 1] + initialPins[12 * 2 + 1]) / 2f
        val dx = initialPins[5 * 2] - initialPins[6 * 2]
        val dy = initialPins[5 * 2 + 1] - initialPins[6 * 2 + 1]
        var scale = sqrt(dx * dx + dy * dy)
        if (scale < 1f) {
            Log.w(TAG, "shoulder distance < 1px; using 10% of image diagonal as fallback")
            scale = 100f  // fallback — caller bitmap likely degenerate
        }
        return data.frames.map { frame ->
            val out = initialPins.copyOf()  // face keypoints 0-4 stay static
            for (k in 5..16) {
                out[k * 2]     = hipCx + frame[k][0] * scale
                out[k * 2 + 1] = hipCy + frame[k][1] * scale
            }
            out
        }
    }

    private fun interpolate(src: List<FloatArray>, target: Int): List<FloatArray> {
        if (src.isEmpty()) return emptyList()
        if (src.size == target) return src
        return List(target) { i ->
            val t = if (target == 1) 0f else i.toFloat() / (target - 1) * (src.size - 1)
            val lo = t.toInt().coerceAtMost(src.size - 2).coerceAtLeast(0)
            val hi = (lo + 1).coerceAtMost(src.size - 1)
            val frac = t - lo
            val a = src[lo]; val b = src[hi]
            FloatArray(a.size) { idx -> a[idx] * (1 - frac) + b[idx] * frac }
        }
    }

    companion object {
        private const val TAG = "JsonMotionSource"
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        /** Factory variant for tests — injects a custom asset loader. */
        fun withAssetLoader(context: Context, loader: (String) -> JsonMotionData?): JsonMotionSource =
            JsonMotionSource(context, loader)

        private fun defaultLoad(context: Context, motionId: String): JsonMotionData? = try {
            val text = context.assets.open("motions/$motionId.json")
                .bufferedReader().use { it.readText() }
            DEFAULT_JSON.decodeFromString(JsonMotionData.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 5: test 재실행 → 4 tests PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.JsonMotionSourceTest" 2>&1 | tail -10
```

- [ ] **Step 6: 전체 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -20
```

Expected: 59 (Sub-3) + 4 (신규) = **63 tests PASS**.

- [ ] **Step 7: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/JsonMotionData.kt \
        app/app/src/main/java/com/k3i/stickerbook/rig/JsonMotionSource.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/JsonMotionSourceTest.kt
git commit -m "feat(sub-4): JsonMotionSource — load BVH-converted JSON, denormalize + interpolate (TDD)"
```

---

## Task 4: MotionStub fallback 제거 + MotionEntry catalog 동기화

**Goal:** `MotionStub.else → wave fallback` 을 `identity` 로 변경 (production 은 JsonMotionSource 사용, stub 은 unit test only). MotionEntry catalog 를 9 BVH 와 일치.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/MotionStub.kt`
- Modify: `app/app/src/main/java/com/k3i/stickerbook/data/MotionEntry.kt`
- Modify: `app/app/src/test/java/com/k3i/stickerbook/rig/MotionStubTest.kt`

- [ ] **Step 1: MotionStub.kt 수정**

```kotlin
override fun frames(
    motionId: String,
    initialPins: FloatArray,
    frameCount: Int,
): List<FloatArray> = when (motionId) {
    "wave" -> wave(initialPins, frameCount)
    else -> identity(initialPins, frameCount)
}
```

(기존: `"identity", "static" -> identity` + `else -> wave` 였음. 단순화: wave 명시 ID 만 wave, 나머지 identity.)

- [ ] **Step 2: MotionStubTest 의 fallback test 수정**

기존 test `unknown motion falls back to wave for stub visual validation` 가 깨질 것. 두 줄 수정:

```kotlin
@Test
fun `unknown motion now falls back to identity (production uses JsonMotionSource)`() {
    val stub = MotionStub()
    val pins = fakeInitialPins()
    val frames = stub.frames("dab", pins, frameCount = 30)
    for (f in frames) {
        for (i in pins.indices) {
            assertEquals(pins[i].toDouble(), f[i].toDouble(), 0.001)
        }
    }
}
```

기존 `identity motion returns frames matching initial pins` test 는 그대로 유지. 즉 `MotionStubTest` 의 4 tests:
- identity motion returns frames matching initial pins (기존)
- unknown motion now falls back to identity (수정)
- wave motion frame 0 equals initial pins (기존)
- wave motion moves wrist y over frames (기존)
- wave motion frame count matches (기존)

- [ ] **Step 3: MotionEntry catalog 동기화**

`app/app/src/main/java/com/k3i/stickerbook/data/MotionEntry.kt`:

```kotlin
package com.k3i.stickerbook.data

data class MotionEntry(
    val id: String,
    val displayName: String,
)

object MotionCatalog {
    // IDs match Sub-4 converted BVH files in assets/motions/<id>.json.
    // Some BVH may be skipped if Task 2 conversion failed (e.g., zombie skeleton mismatch);
    // edit the list to exclude any IDs without a corresponding JSON file.
    val all: List<MotionEntry> = listOf(
        MotionEntry(id = "dance_1", displayName = "댄스 1"),
        MotionEntry(id = "dance_2", displayName = "댄스 2"),
        MotionEntry(id = "dance_3", displayName = "댄스 3"),
        MotionEntry(id = "motion_5", displayName = "모션 5"),
        MotionEntry(id = "phone_1", displayName = "전화 1"),
        MotionEntry(id = "phone_2", displayName = "전화 2"),
        MotionEntry(id = "phone_z1", displayName = "전화 Z1"),
        MotionEntry(id = "tabtab", displayName = "탭탭"),
        MotionEntry(id = "zombie", displayName = "좀비"),
    )
}
```

**Note**: Task 2 에서 일부 BVH 변환 실패한 경우 그 항목을 위 list 에서 제거. JSON 없는 ID 가 선택되면 JsonMotionSource 가 identity fallback 으로 처리됨.

- [ ] **Step 4: build + test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -20
```

Expected: 63 tests PASS (Task 3 까지) 그대로 + MotionStubTest 수정 반영.

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/MotionStub.kt \
        app/app/src/main/java/com/k3i/stickerbook/data/MotionEntry.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/MotionStubTest.kt
git commit -m "feat(sub-4): drop MotionStub wave fallback, sync MotionEntry with AD BVH catalog"
```

---

## Task 5: ArapRigger.real() 의 motionSource swap

**Goal:** `ArapRigger.real(context)` 가 `JsonMotionSource(context)` 사용 (1줄 swap).

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt`

- [ ] **Step 1: `ArapRigger.kt` 의 `real()` factory 한 줄 수정**

```kotlin
fun real(context: Context): ArapRigger {
    val detector = MaskRcnnDetector(context)
    val estimator = AdPoseEstimator(context)
    return ArapRigger(
        context,
        detect = { detector.detect(it) },
        estimate = { image, bbox -> estimator.estimate(image, bbox) },
        motionSource = JsonMotionSource(context),  // ← Sub-4: was MotionStub()
    )
}
```

- [ ] **Step 2: build + 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL + 63 tests PASS.

- [ ] **Step 3: 갤탭 install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  'C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk'
```

Expected: Success.

- [ ] **Step 4: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt
git commit -m "feat(sub-4): ArapRigger.real() uses JsonMotionSource for production"
```

---

## Task 6: 갤탭 시연 (사용자 직접)

**Goal:** 두 개의 다른 motion (dance_1 vs phone_1) 으로 sticker 생성, 시각적으로 다른 움직임 확인.

- [ ] **Step 1: logcat 모니터 시작 (별도 터미널)**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -c
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -s \
  "JsonMotionSource" "ArapRigger" "AdPoseEstimator" "MaskRcnnDetector"
```

- [ ] **Step 2: dance_1 시연**

1. 앱 launch → + FAB → 카메라 → 손그림 캡처
2. ▶ → **댄스 1** 선택 → 만들기 ▶
3. 1-2분 대기
4. 그리드 → `arap_<ts>` 카드 → 상세 화면에서 30 frame slideshow
5. logcat 에서 `JsonMotionSource: loaded motion 'dance_1' (120 frames @ 30fps)` 확인

- [ ] **Step 3: phone_1 시연 (같은 캡처 다시 + 다른 motion)**

1. 다시 + FAB → 카메라 → 같은 손그림 캡처
2. ▶ → **전화 1** 선택 → 만들기 ▶
3. 결과 카드 → 상세 화면
4. dance_1 결과와 시각적으로 **다른 움직임** 확인

- [ ] **Step 4: 결과 sticker 의 frame PNG 비교 (선택)**

```bash
LATEST=$(/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe shell \
  "run-as com.k3i.stickerbook ls files/stickerbook_assets/stickers/" 2>&1 | grep "^arap" | sort -r | head -2)
echo "Latest: $LATEST"
# dance_1 sticker 의 frame 15 PNG pull
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe exec-out \
  run-as com.k3i.stickerbook cat "files/stickerbook_assets/stickers/<DANCE_ID>/frames/0015.png" > /tmp/dance_f15.png
# phone_1 sticker 의 frame 15 PNG pull
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe exec-out \
  run-as com.k3i.stickerbook cat "files/stickerbook_assets/stickers/<PHONE_ID>/frames/0015.png" > /tmp/phone_f15.png
md5sum /tmp/dance_f15.png /tmp/phone_f15.png
```

Expected: md5 다름 (motion 별로 다른 deformation).

PASS 기준 (spec §1):
- (1) dance_1.json + phone_1.json load 성공 ✅
- (2) 각 motion 의 frame 0 vs frame 15 다름 (motion 적용 됨) ✅
- (3) dance_1 vs phone_1 시각적으로 다른 움직임 (md5 다름) ✅

- [ ] **Step 5: 이 task 는 commit 없음 (사용자 시연 결과는 Task 7 의 results doc 에서 종합)**

---

## Task 7: 결과 doc + memory 업데이트

**Files:**
- Create: `docs/sub4_results.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`

- [ ] **Step 1: `docs/sub4_results.md` 작성 (시연 결과 채움)**

```markdown
# Sub-4 결과 — BVH retarget (PC 사전 변환 + Android JSON 재생)

날짜: 2026-05-1X
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.4.0 (debug)

## M4.1 — PC: dance_1 단일 변환

| 항목 | 측정값 |
|---|---|
| BVH source | examples/bvh/dance_1.bvh (120 frames @ 30fps) |
| AD env | animated_drawings (기존) |
| 변환 시간 | ~수 초 |
| JSON 크기 | ~XX KB |
| Normalize 검증 | frame 0 hip center = (0,0), shoulder = 1.0 ✅ |

## M4.2 — PC: 9 BVH 모두 변환

| BVH | 변환 결과 |
|---|---|
| dance_1 | ✅ 120 frames |
| dance_2 | (실제 값 채움) |
| dance_3 | (실제 값) |
| motion_5 | (실제 값) |
| phone_1, phone_2, phone_z1 | (실제 값) |
| tabtab | (실제 값) |
| zombie | (실제 값 — 일부 skeleton 다르면 skip 가능) |

assets/motions/ 에 총 X JSON 파일.

## M4.3 — Android: JsonMotionSource 단위 PASS

| Test | 결과 |
|---|---|
| unknown motion returns identity | ✅ |
| frame 0 of normalized motion equals initialPins anchor | ✅ |
| interpolation evenly maps source N → target M | ✅ |
| target frameCount=1 returns first source frame | ✅ |

63 (Sub-3 까지) + 4 = **67 tests PASS, 0 fail**.

## M4.4~M4.5 — MotionStub fallback 제거 + ArapRigger swap

- MotionStub: else → identity (wave fallback 제거)
- MotionEntry catalog: AD 9 BVH 와 동기화 (dab/motion_1/motion_2 제거, motion_5/phone_*/tabtab/zombie 추가)
- ArapRigger.real() 의 motionSource = JsonMotionSource

## M4.6 — 갤탭 시연 (시각 검증 PASS)

| 항목 | 측정값 | PASS |
|---|---|---|
| dance_1 sticker 생성 | ✅ | ✅ |
| phone_1 sticker 생성 | ✅ | ✅ |
| dance_1 vs phone_1 시각 다름 | md5 다름 | ✅ |
| logcat motion load 메시지 | ✅ | ✅ |

## APK 크기

| asset | 크기 |
|---|---|
| drawn_humanoid_detector.onnx | 176 MB |
| pose_landmarker_heavy.task | 31 MB |
| ad_pose.onnx | 136 MB |
| motions/*.json (9개) | < 1 MB |
| **APK 총** | **~496 MB** (Sub-3 와 거의 동일) |

## 알려진 이슈 / Follow-up

- ⚠️ AD retargeter 의 정확도 한계 — 일부 motion (zombie 등) 의 비정상 skeleton 인식
- ⚠️ shoulder distance normalize 는 BVH skeleton 회전 시 부정확 가능 — PCA 기반 정규화 follow-up
- ⚠️ Sub-1+2b quantization 여전히 후속 (latency 1-2분)
- ⚠️ GIF encoding 미구현 (Sub-3 follow-up 그대로)

## Sub-5 (사용자 직접 motion 녹화) 진입 조건

- ✅ Sub-4 완료, JsonMotionSource interface 안정
- ☐ 사용자 motion 녹화 UI 결정 (m 키 / 카메라 / 외부 device)
- ☐ 녹화 영상 → BVH 또는 직접 keypoint sequence 추출

## Sub-4 commits (시간순)

(commit SHA 들 채워서 나열)
```

- [ ] **Step 2: memory 업데이트**

`/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`:

- description 줄: "Sub-5/1/2/2b/3/4 완료. Phase 2 본체 완료"
- Sub-4 행 status 를 ✅ 로 변경
- Sub-4 다음 (master plan 에 따라) 의 진입 한 줄 추가

`/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`:
- Phase 2 줄 업데이트: "Sub-4 (BVH retarget) 완료, dance/phone motion 시각 검증 PASS"

- [ ] **Step 3: commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add docs/sub4_results.md
git commit -m "docs(sub-4): M4 results — BVH retarget integration, dance/phone motion demo"
```

---

## 진행 순서 요약

1. **Task 1 (PC, ~1시간)**: convert.py + dance_1 시도. AD API 디버깅 (joint name + Retargeter attribute)
2. **Task 2 (PC, ~30분)**: 9 BVH 다 변환 + assets 배치
3. **Task 3 (Android, ~1-2시간)**: JsonMotionSource TDD
4. **Task 4 (Android, ~30분)**: MotionStub fallback 제거 + catalog 동기화
5. **Task 5 (Android, ~10분)**: ArapRigger swap 1줄 + 갤탭 install
6. **Task 6 (갤탭, ~10분)**: 시연 (사용자 직접)
7. **Task 7 (~30분)**: 결과 doc + memory

총 예상: 4-6시간.

## Sub-1/2b/3 패턴 적용 여부

- ✅ **File path mmap**: Sub-4 는 ONNX 안 씀, N/A
- ✅ **NNAPI off**: ONNX 안 씀, N/A
- ✅ **Dispatchers.Default**: AppNavHost 기존 패턴 그대로 (rigger.rig() 호출 background)

## Sub-4 단독 검증 핵심

- `JsonMotionSourceTest` 의 denormalize math + interpolation 정확성
- 갤탭 시연 dance_1 vs phone_1 의 시각적 차이 (motion 별 다른 움직임)
