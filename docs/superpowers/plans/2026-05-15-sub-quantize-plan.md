# Sub-Quantize ONNX INT8 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sub-1 (176MB) + Sub-2b (136MB) ONNX 를 INT8 dynamic quantize 하여 APK 520MB → ~250MB, latency 1-2분 → < 30s 로 단축. 정확도 손실 (Sub-1 bbox IoU > 0.7, Sub-2b keypoint diff < 5px) 안에서.

**Architecture:** PC: `onnxruntime.quantization.quantize_dynamic` 으로 두 모델 INT8 변환 + verify 스크립트로 FP32 와 비교. Android: assets 의 ONNX 파일 swap + `MaskRcnnDetector` / `AdPoseEstimator` 의 `ensureModelOnDisk()` 에 size-mismatch cache invalidation 추가. 갤탭 시연으로 latency + 시각 검증.

**Tech Stack:** Python + onnxruntime.quantization (PC) / Kotlin + ONNX Runtime Mobile (Android, 변경 없음)

**Spec:** `docs/superpowers/specs/2026-05-15-sub-quantize-design.md`

---

## File Structure

### PC 측 (`/home/ingon/AR_book/sub_quantize_workdir/`, git 추적 X)

| 파일 | 종류 |
|---|---|
| `quantize.py` | 신규 |
| `verify_quantize.py` | 신규 |
| `out/drawn_humanoid_detector_int8.onnx` | 산출 (~44MB) |
| `out/ad_pose_int8.onnx` | 산출 (~34MB) |

### Android 측 (branch `android-port`)

| 파일 | 종류 |
|---|---|
| `app/app/src/main/assets/models/drawn_humanoid_detector.onnx` | replace (176MB → 44MB) |
| `app/app/src/main/assets/models/ad_pose.onnx` | replace (136MB → 34MB) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/MaskRcnnDetector.kt` | 수정 (cache invalidation) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/AdPoseEstimator.kt` | 수정 (동일) |
| `docs/sub_quantize_results.md` | 신규 |

---

## Task 1: PC — quantize.py + 두 모델 INT8 변환

**Goal:** Sub-1 + Sub-2b ONNX 를 INT8 dynamic quantize. 산출 ~44MB + ~34MB.

**Files:**
- Create: `/home/ingon/AR_book/sub_quantize_workdir/quantize.py`
- Output: `/home/ingon/AR_book/sub_quantize_workdir/out/{drawn_humanoid_detector_int8.onnx, ad_pose_int8.onnx}`

- [ ] **Step 1: env + 작업 디렉토리 생성**

```bash
mkdir -p /home/ingon/AR_book/sub_quantize_workdir/out
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate mmdeploy_export
python -c "from onnxruntime.quantization import quantize_dynamic, QuantType; print('OK')"
```

Expected: `OK`.

- [ ] **Step 2: quantize.py 작성**

`/home/ingon/AR_book/sub_quantize_workdir/quantize.py`:

```python
"""
Quantize Sub-1 detector + Sub-2b pose ONNX to INT8 dynamic.
- weight: INT8
- activation: INT8 (auto)
- no calibration dataset needed
"""
import os
from onnxruntime.quantization import quantize_dynamic, QuantType

OUT_DIR = '/home/ingon/AR_book/sub_quantize_workdir/out'
os.makedirs(OUT_DIR, exist_ok=True)

SUB1_FP32 = '/home/ingon/AR_book/sub1_workdir/out/end2end_fixed.onnx'
SUB1_INT8 = f'{OUT_DIR}/drawn_humanoid_detector_int8.onnx'

SUB2B_FP32 = '/home/ingon/AR_book/sub2b_workdir/out/end2end.onnx'
SUB2B_INT8 = f'{OUT_DIR}/ad_pose_int8.onnx'

print(f"Quantizing Sub-1 detector ({os.path.getsize(SUB1_FP32) // (1024*1024)} MB)...")
quantize_dynamic(
    model_input=SUB1_FP32,
    model_output=SUB1_INT8,
    weight_type=QuantType.QUInt8,
)
print(f"  -> {os.path.getsize(SUB1_INT8) // (1024*1024)} MB")

print(f"Quantizing Sub-2b pose ({os.path.getsize(SUB2B_FP32) // (1024*1024)} MB)...")
quantize_dynamic(
    model_input=SUB2B_FP32,
    model_output=SUB2B_INT8,
    weight_type=QuantType.QUInt8,
)
print(f"  -> {os.path.getsize(SUB2B_INT8) // (1024*1024)} MB")

print("Done.")
```

- [ ] **Step 3: 실행**

```bash
cd /home/ingon/AR_book/sub_quantize_workdir
python quantize.py 2>&1 | tee quantize.log
```

Expected:
```
Quantizing Sub-1 detector (167 MB)...
  -> 42 MB (대략)
Quantizing Sub-2b pose (129 MB)...
  -> 33 MB (대략)
Done.
```

- [ ] **Step 4: 일부 op INT8 unsupported warning 확인 (OK)**

quantize_dynamic 가 unsupported op 만나면 warning 출력. RoIAlign / NMS / Resize 등 일부 op 가 INT8 안 됨 — 자동 skip (FP32 로 남음). 그래도 weight 의 대부분이 quantized 라 효과 큼.

```bash
grep -E "WARNING|skipped|Unable" quantize.log | head -10
```

이런 메시지 나와도 OK. 단 quantize 자체가 fail 면 stop + BLOCKED report.

- [ ] **Step 5: 산출물 확인**

```bash
ls -la out/
```

Expected:
```
drwn_humanoid_detector_int8.onnx   ~42-50 MB
ad_pose_int8.onnx                  ~32-40 MB
```

- [ ] **Step 6: commit (Android repo 변경 없음 — PC workdir 만)**

이 task 는 Android repo 에 commit X. 다음 Task 3 에서 swap 시 commit.

---

## Task 2: PC — verify_quantize.py 정확도 검증

**Goal:** FP32 와 INT8 의 동일 입력 결과 비교. Sub-1 bbox IoU > 0.7, Sub-2b keypoint diff < 5px.

**Files:**
- Create: `/home/ingon/AR_book/sub_quantize_workdir/verify_quantize.py`

- [ ] **Step 1: verify_quantize.py 작성**

```python
"""
Verify INT8 quantized ONNX against FP32 baseline.
- Sub-1: top bbox IoU > 0.7
- Sub-2b: per-keypoint L2 < 5px (heatmap-space)
"""
import numpy as np
import cv2
import onnxruntime as ort

SUB1_FP32 = '/home/ingon/AR_book/sub1_workdir/out/end2end_fixed.onnx'
SUB1_INT8 = '/home/ingon/AR_book/sub_quantize_workdir/out/drawn_humanoid_detector_int8.onnx'
SUB1_SAMPLE = '/home/ingon/AR_book/sub1_workdir/sample_input.jpg'

SUB2B_FP32 = '/home/ingon/AR_book/sub2b_workdir/out/end2end.onnx'
SUB2B_INT8 = '/home/ingon/AR_book/sub_quantize_workdir/out/ad_pose_int8.onnx'
SUB2B_SAMPLE = '/home/ingon/AR_book/sub2b_workdir/sample_input.jpg'


def iou(box1, box2):
    """box = [x1, y1, x2, y2]"""
    xA = max(box1[0], box2[0]); yA = max(box1[1], box2[1])
    xB = min(box1[2], box2[2]); yB = min(box1[3], box2[3])
    inter = max(0, xB - xA) * max(0, yB - yA)
    a1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
    a2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
    union = a1 + a2 - inter
    return inter / union if union > 0 else 0


def verify_sub1():
    img = cv2.imread(SUB1_SAMPLE)
    # mmdet preprocess: resize + normalize. Use same as Android side.
    img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32)
    # Simple: pass raw NCHW for testing — both FP32 and INT8 get same input
    h, w = img_rgb.shape[:2]
    nchw = img_rgb.transpose(2, 0, 1)[None, ...]  # [1, 3, H, W]

    fp32 = ort.InferenceSession(SUB1_FP32, providers=['CPUExecutionProvider'])
    int8 = ort.InferenceSession(SUB1_INT8, providers=['CPUExecutionProvider'])
    in_name = fp32.get_inputs()[0].name
    fp32_out = fp32.run(None, {in_name: nchw})
    int8_out = int8.run(None, {in_name: nchw})

    # Sub-1 outputs: [dets, labels, masks]. dets shape: [1, N, 5] (x1,y1,x2,y2,score)
    fp32_dets = fp32_out[0][0]  # [N, 5]
    int8_dets = int8_out[0][0]
    print(f"Sub-1: FP32 dets={len(fp32_dets)}, INT8 dets={len(int8_dets)}")
    if len(fp32_dets) == 0 or len(int8_dets) == 0:
        raise RuntimeError("No detections — sample may be bad or model broken")
    # Top score box
    fp32_top = fp32_dets[np.argmax(fp32_dets[:, 4])]
    int8_top = int8_dets[np.argmax(int8_dets[:, 4])]
    box_iou = iou(fp32_top[:4], int8_top[:4])
    print(f"  FP32 top: bbox={fp32_top[:4]} score={fp32_top[4]:.3f}")
    print(f"  INT8 top: bbox={int8_top[:4]} score={int8_top[4]:.3f}")
    print(f"  bbox IoU: {box_iou:.3f}")
    assert box_iou > 0.7, f"Sub-1 IoU too low: {box_iou:.3f}"
    print("Sub-1 PASS")


def verify_sub2b():
    # mmpose pose: 192x256 input, normalized
    img = cv2.imread(SUB2B_SAMPLE)
    resized = cv2.resize(img, (192, 256))
    rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32)
    mean = np.array([123.675, 116.28, 103.53], dtype=np.float32)
    std = np.array([58.395, 57.12, 57.375], dtype=np.float32)
    nchw = ((rgb - mean) / std).transpose(2, 0, 1)[None, ...]

    fp32 = ort.InferenceSession(SUB2B_FP32, providers=['CPUExecutionProvider'])
    int8 = ort.InferenceSession(SUB2B_INT8, providers=['CPUExecutionProvider'])
    in_name = fp32.get_inputs()[0].name
    fp32_hm = fp32.run(None, {in_name: nchw})[0][0]  # [17, 64, 48]
    int8_hm = int8.run(None, {in_name: nchw})[0][0]

    print(f"Sub-2b heatmap shape: {fp32_hm.shape}")
    max_diff_px = 0
    for k in range(17):
        yx_fp32 = np.unravel_index(fp32_hm[k].argmax(), fp32_hm[k].shape)
        yx_int8 = np.unravel_index(int8_hm[k].argmax(), int8_hm[k].shape)
        dist = ((yx_fp32[0] - yx_int8[0]) ** 2 + (yx_fp32[1] - yx_int8[1]) ** 2) ** 0.5
        max_diff_px = max(max_diff_px, dist)
    print(f"  max keypoint diff: {max_diff_px:.2f} px (heatmap-space)")
    assert max_diff_px < 5.0, f"Sub-2b keypoint diff too large: {max_diff_px:.2f}"
    print("Sub-2b PASS")


if __name__ == '__main__':
    verify_sub1()
    verify_sub2b()
    print("\nAll PASS")
```

- [ ] **Step 2: 실행**

```bash
cd /home/ingon/AR_book/sub_quantize_workdir
python verify_quantize.py 2>&1 | tee verify.log
```

Expected output:
```
Sub-1: FP32 dets=N, INT8 dets=N
  FP32 top: bbox=[...] score=0.X
  INT8 top: bbox=[...] score=0.X
  bbox IoU: 0.85
Sub-1 PASS
Sub-2b heatmap shape: (17, 64, 48)
  max keypoint diff: 1.50 px (heatmap-space)
Sub-2b PASS

All PASS
```

- [ ] **Step 3: 만약 fail (IoU < 0.7 또는 keypoint diff > 5px)**

옵션:
- Static INT8 (calibration dataset) follow-up 으로
- 또는 FP16 fallback (별도 quantize_dynamic 호출, QuantType.QFloat16 — 단 onnxruntime 의 fp16 는 별도 도구 `onnxconverter_common.float16`)
- 보고 BLOCKED status 로 + controller 결정

만약 PASS 면 Android task 진행.

- [ ] **Step 4: commit 없음 (PC workdir)**

---

## Task 3: Android — assets ONNX swap + cache invalidation

**Goal:** assets 의 ONNX 두 파일 교체 + `MaskRcnnDetector` / `AdPoseEstimator` 의 `ensureModelOnDisk()` 에 size-mismatch 체크 추가.

**Files:**
- Modify: `app/app/src/main/assets/models/drawn_humanoid_detector.onnx` (replace)
- Modify: `app/app/src/main/assets/models/ad_pose.onnx` (replace)
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/MaskRcnnDetector.kt`
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/AdPoseEstimator.kt`

- [ ] **Step 1: 새 ONNX 파일들 복사**

```bash
cp /home/ingon/AR_book/sub_quantize_workdir/out/drawn_humanoid_detector_int8.onnx \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models/drawn_humanoid_detector.onnx
cp /home/ingon/AR_book/sub_quantize_workdir/out/ad_pose_int8.onnx \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models/ad_pose.onnx
ls -la /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models/
```

Expected:
```
drwn_humanoid_detector.onnx    ~42-50 MB
ad_pose.onnx                   ~32-40 MB
pose_landmarker_heavy.task     30 MB (unchanged)
```

(파일 이름은 그대로 유지 — wrapper 코드 변경 없음.)

- [ ] **Step 2: MaskRcnnDetector.kt 의 ensureModelOnDisk() 수정**

기존:
```kotlin
private fun ensureModelOnDisk(): String {
    val target = File(context.filesDir, "models/$MODEL_NAME")
    if (target.isFile && target.length() > 0) {
        Log.i(TAG, "model already cached at ${target.absolutePath} (${target.length()} bytes)")
        return target.absolutePath
    }
    target.parentFile?.mkdirs()
    context.assets.open("models/$MODEL_NAME").use { input ->
        target.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
    }
    Log.i(TAG, "copied model to ${target.absolutePath} (${target.length()} bytes)")
    return target.absolutePath
}
```

수정:
```kotlin
private fun ensureModelOnDisk(): String {
    val target = File(context.filesDir, "models/$MODEL_NAME")
    val assetSize = context.assets.openFd("models/$MODEL_NAME").use { it.length }
    if (target.isFile && target.length() == assetSize) {
        Log.i(TAG, "model already cached at ${target.absolutePath} (${target.length()} bytes)")
        return target.absolutePath
    }
    if (target.isFile) {
        Log.i(TAG, "model cache size mismatch (${target.length()} vs $assetSize), re-copying")
    }
    target.parentFile?.mkdirs()
    context.assets.open("models/$MODEL_NAME").use { input ->
        target.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
    }
    Log.i(TAG, "copied model to ${target.absolutePath} (${target.length()} bytes)")
    return target.absolutePath
}
```

- [ ] **Step 3: AdPoseEstimator.kt 의 ensureModelOnDisk() 동일 수정**

위 step 2 와 같은 패턴.

기존:
```kotlin
private fun ensureModelOnDisk(): String {
    val target = File(context.filesDir, "models/$MODEL_NAME")
    if (target.isFile && target.length() > 0) {
        Log.i(TAG, "model already cached at ${target.absolutePath} (${target.length()} bytes)")
        return target.absolutePath
    }
    target.parentFile?.mkdirs()
    context.assets.open("models/$MODEL_NAME").use { input ->
        target.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
    }
    Log.i(TAG, "copied model to ${target.absolutePath} (${target.length()} bytes)")
    return target.absolutePath
}
```

수정 (위 MaskRcnnDetector 와 같은 패턴):
```kotlin
private fun ensureModelOnDisk(): String {
    val target = File(context.filesDir, "models/$MODEL_NAME")
    val assetSize = context.assets.openFd("models/$MODEL_NAME").use { it.length }
    if (target.isFile && target.length() == assetSize) {
        Log.i(TAG, "model already cached at ${target.absolutePath} (${target.length()} bytes)")
        return target.absolutePath
    }
    if (target.isFile) {
        Log.i(TAG, "model cache size mismatch (${target.length()} vs $assetSize), re-copying")
    }
    target.parentFile?.mkdirs()
    context.assets.open("models/$MODEL_NAME").use { input ->
        target.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
    }
    Log.i(TAG, "copied model to ${target.absolutePath} (${target.length()} bytes)")
    return target.absolutePath
}
```

- [ ] **Step 4: compile + test 회귀**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 64 tests PASS (변경 없음). 단 `MaskRcnnDetectorTest` / `AdPoseEstimatorTest` 가 Robolectric 으로 assets 접근하면 새 ONNX file size 가 unit test 와 무관 (assets.openFd 가 Robolectric 에서 어떻게 처리될지 확인 필요).

만약 test fail (Robolectric `openFd` 미지원):
- 대안: `assets.open("...").available()` 사용 — InputStream 의 byte count
- 또는 `context.resources.assets.list("models").let { listOf("...") }` 등으로 size 직접 측정 우회

가장 안전: `assets.open(...).use { it.available() }` 로 size 확인 (대부분 정확). 변경:
```kotlin
val assetSize = context.assets.open("models/$MODEL_NAME").use { it.available().toLong() }
```

- [ ] **Step 5: commit**

`.gitignore` 가 `assets/models/` 폴더 ignore 라 ONNX 파일 자체는 git 안 들어감. wrapper 코드만 commit:

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/MaskRcnnDetector.kt \
        app/app/src/main/java/com/k3i/stickerbook/rig/AdPoseEstimator.kt
git status --short
git commit -m "feat(quantize): cache invalidation on size mismatch (for INT8 ONNX swap)"
```

---

## Task 4: APK build + 갤탭 install + 시연

**Goal:** APK 크기 < 250MB 확인 + 갤탭 install + latency 측정 + 시각 검증.

- [ ] **Step 1: APK build**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh clean assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: APK 크기 확인**

```bash
ls -la app/app/build/outputs/apk/debug/app-debug.apk
```

Expected: 200-260 MB (이전 520MB 대비 절반 이상 감소).

- [ ] **Step 3: 갤탭 install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  'C:\Users\leesa\AR_book\stickerbook_android_porting\app\app\build\outputs\apk\debug\app-debug.apk'
```

Expected: Success.

- [ ] **Step 4: logcat 모니터 + 시연 (사용자 직접)**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -c
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -s \
  "MaskRcnnDetector" "AdPoseEstimator" "ArapRigger" "JsonMotionSource"
```

logcat 확인 포인트:
- `MaskRcnnDetector: model cache size mismatch (... vs ...), re-copying` (새 크기 로 cache 교체)
- `AdPoseEstimator: model cache size mismatch (...)`
- `MaskRcnnDetector: loaded ONNX, inputs=...` (정상 load)

시연 절차:
1. 앱 launch → + FAB → 카메라 → 손그림 캡처
2. ▶ → 모션 선택 → 만들기 ▶
3. **start time** 부터 stopwatch
4. spinner 사라짐 시간 = inference latency

Expected: < 30s (이전 1-2분 대비).

- [ ] **Step 5: 시각 결과 비교 (사용자 평가)**

이전 Sub-4 시연 결과 sticker (arap_1778831667138) 와 새 sticker 의 시각 비교:
- Sub-1 mask 영역 비슷한가 (다리 잘림 등은 모델 한계, 무관)
- 17 keypoint 위치 비슷한가 (motion 효과)
- 전체 frame slideshow 자연스러운가

큰 차이 없으면 PASS.

- [ ] **Step 6: 시연 결과 보고 (사용자)**

사용자 평가 받고 Task 5 진행.

---

## Task 5: 결과 doc + memory 업데이트

**Files:**
- Create: `docs/sub_quantize_results.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`

- [ ] **Step 1: docs/sub_quantize_results.md 작성**

```markdown
# Sub-Quantize 결과 — ONNX INT8 dynamic quantization

날짜: 2026-05-1X
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.5.0 (debug)

## MQ.1~MQ.2 — PC quantize + verify

| 모델 | FP32 | INT8 | 감소 |
|---|---|---|---|
| Sub-1 detector | 176 MB | (실제 크기) | (실제) |
| Sub-2b pose | 136 MB | (실제 크기) | (실제) |
| **합계** | 312 MB | (실제) | (실제 %) |

### 정확도 검증

| 모델 | metric | 결과 | 기준 |
|---|---|---|---|
| Sub-1 detector | top bbox IoU | (실제) | > 0.7 |
| Sub-2b pose | max keypoint diff | (실제) px | < 5 px |

## MQ.3 — Android: cache invalidation

기존 wrapper 의 `ensureModelOnDisk()` 가 size 무시하고 cache 유지 → APK 업그레이드 후 새 model 적용 X. 수정: size mismatch 시 재 copy.

64 unit tests 회귀 PASS.

## MQ.4 — 갤탭 시연

| 항목 | 측정값 | 목표 | PASS |
|---|---|---|---|
| APK 크기 | (실제 MB) | < 250 MB | (TBD) |
| End-to-end latency | (실제 초) | < 30s | (TBD) |
| Sub-1 mask 시각 차이 | (사용자 평가) | 없음 | (TBD) |
| Sub-2b keypoint 시각 차이 | (사용자 평가) | 없음 | (TBD) |
| Motion 결과 시각 차이 | (사용자 평가) | 없음 | (TBD) |

## 알려진 이슈 / Follow-up

- ⚠️ 일부 op (RoIAlign, NMS) INT8 미지원 → 자동 skip. 효과 부분적
- ⚠️ 정확도 부족 발견 시 static INT8 (calibration) follow-up
- ⚠️ Sub-1 mask 의 모델 학습 한계는 여전히 존재 (quantize 와 무관)

## Sub-Quantize commits

(commit SHA 들 채워서 나열)
```

- [ ] **Step 2: memory 업데이트**

`/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`:
- description 줄에 "+ Sub-Quantize" 추가
- 후속 follow-up 섹션에서 quantization 항목 제거 (완료됨)

`/home/ingon/.claude/projects/-home-ingon-AR-book/memory/MEMORY.md`:
- Phase 2 줄에 "Sub-Quantize 완료 (APK X MB, latency X 초)" 추가

- [ ] **Step 3: commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add docs/sub_quantize_results.md
git commit -m "docs(quantize): MQ results — INT8 dynamic, APK X MB, latency X s"
```

---

## 진행 순서 요약

1. **Task 1 (PC, ~10분)**: quantize.py + 두 모델 변환
2. **Task 2 (PC, ~10분)**: verify_quantize.py + 정확도 검증
3. **Task 3 (Android, ~15분)**: assets swap + cache invalidation 로직 + test 회귀
4. **Task 4 (~30분)**: APK build + 갤탭 install + 시연 + latency 측정
5. **Task 5 (~20분)**: 결과 doc + memory

총 예상: 1-2시간. 만약 정확도 fail 시 추가 static INT8 작업 (별도 sub).

## Sub-Quantize 의 효과

성공 시:
- APK 520MB → ~200-250MB
- Latency 1-2분 → < 30s
- 갤탭 사용자 경험 큰 개선
- Play Store 제출 가능 크기 (보통 100-200MB 권장)

실패 시 fallback:
- IoU < 0.7: static INT8 (calibration ~50장 sample) 별도 sub
- Latency 변화 없음: 일부 op 미적용, RoIAlign 자체 가 latency 의 dominant 일 수도 → 별도 분석 필요
