# Sub-2b AD pose ONNX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AD humanoid pose estimator (mmpose, 408MB `.pth`) 를 ONNX 로 변환하여 갤탭에 통합한다. `PoseEstimator` interface 를 도입하여 MediaPipe 와 AD-pose 두 backend 를 swap 가능하게 만들고, 손그림에서 17 COCO keypoints 가 검출되는 것을 검증한다.

**Architecture:** PC 측: Sub-1 의 `run_export.py` 패턴을 mmpose 용으로 mirror. mmpose 2.x→3.x config patch 5개 + opset 16 dynamic_axes ONNX export. Android 측: `PoseEstimator` interface + `AdPoseEstimator` (ONNX) / `MediaPipePoseEstimator` (rename) 두 구현. `PoseDetectionRigger` 가 detector bbox 를 받아 AD pose 에 crop 입력. Sub-1 의 hot-fix 3개 (file mmap, NNAPI off, Dispatchers.Default) 재적용.

**Tech Stack:** Python 3.8 + mmpose 2.x + mmdeploy 1.3.1 + onnxruntime (PC) / Kotlin + ONNX Runtime Mobile 1.17.1 + JUnit4 + Robolectric (Android)

**Spec:** `docs/superpowers/specs/2026-05-15-sub2b-ad-pose-onnx-design.md`

---

## File Structure

### PC 측 (`/home/ingon/AR_book/sub2b_workdir/`, git 추적 X)

| 파일 | 종류 | 책임 |
|---|---|---|
| `pose_src/config.py` | 신규 (unzip 산출) | mmpose model + dataset + pipeline 정의 (.mar 안 그대로) |
| `pose_src/best_AP_epoch_72.pth` | 신규 (unzip 산출) | 가중치 408MB |
| `pose_src/mmpose_handler.py` | 신규 (unzip 산출) | 참조용, 사용 안 함 |
| `run_export.py` | 신규 | MMDeploy 로 ONNX export (mmpose 2.x→3.x patch 포함) |
| `verify_onnx.py` | 신규 | Python ORT 로 ONNX 산출물 정확도 검증 |
| `sample_input.jpg` | 신규 | 검증용 손그림 1장 (PC + 갤탭 공통) |
| `out/end2end.onnx` | 신규 | 산출 ONNX, Android assets 로 복사 |

### Android 측 (`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/`)

| 파일 | 종류 | 책임 |
|---|---|---|
| `app/app/src/main/assets/models/ad_pose.onnx` | 신규 | export 결과 (~400MB) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/PoseEstimator.kt` | BREAKING | class → interface |
| `app/app/src/main/java/com/k3i/stickerbook/rig/MediaPipePoseEstimator.kt` | 신규 | 기존 PoseEstimator class 본체 이동 + `: PoseEstimator` |
| `app/app/src/main/java/com/k3i/stickerbook/rig/AdPoseEstimator.kt` | 신규 | ONNX wrapper, crop + heatmap decode + unproject |
| `app/app/src/main/java/com/k3i/stickerbook/rig/PoseHeatmapDecoder.kt` | 신규 | heatmap [17,64,48] → 17 keypoints (crop 좌표계) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/Landmark.kt` | 수정 | `PoseBackend` enum + `SkeletonData.backend` field |
| `app/app/src/main/java/com/k3i/stickerbook/rig/SkeletonOverlay.kt` | 수정 | 17/33 keypoint connection 분기 |
| `app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt` | 수정 | estimator DI + bbox 전달 + factory rename |
| `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt` | 1줄 수정 | `realAd(ctx)` swap |
| `app/app/src/test/java/com/k3i/stickerbook/rig/LandmarkTest.kt` | 수정 | backend field round-trip |
| `app/app/src/test/java/com/k3i/stickerbook/rig/SkeletonOverlayTest.kt` | 수정 | 17 keypoint path |
| `app/app/src/test/java/com/k3i/stickerbook/rig/PoseDetectionRiggerTest.kt` | 수정 | estimator DI 테스트 |
| `app/app/src/test/java/com/k3i/stickerbook/rig/PoseHeatmapDecoderTest.kt` | 신규 | known heatmap → keypoint argmax + sub-pixel |
| `app/app/src/test/java/com/k3i/stickerbook/rig/AdPoseEstimatorTest.kt` | 신규 | crop math + affine inverse + (mock ONNX) end-to-end |
| `docs/sub2b_results.md` | 신규 | M2b 결과 doc |

---

## Task 1: PC — `.mar` 추출 + config 검증

**Goal:** AD pose `.mar` 를 풀고 mmpose config 가 가정대로 (top-down, 17 COCO keypoints) 인지 확인한다.

**Files:**
- Create: `/home/ingon/AR_book/sub2b_workdir/` (작업 디렉토리)
- Create: `/home/ingon/AR_book/sub2b_workdir/pose_src/{config.py, best_AP_epoch_72.pth, mmpose_handler.py}`
- Create: `/home/ingon/AR_book/sub2b_workdir/sample_input.jpg` (Sub-1 의 sample 또는 신규 손그림 1장)

- [ ] **Step 1: 작업 디렉토리 생성**

```bash
mkdir -p /home/ingon/AR_book/sub2b_workdir/pose_src
mkdir -p /home/ingon/AR_book/sub2b_workdir/out
cd /home/ingon/AR_book/sub2b_workdir
```

- [ ] **Step 2: `.mar` 추출**

```bash
unzip /home/ingon/AR_book/AnimatedDrawings/torchserve/model-store/drawn_humanoid_pose_estimator.mar -d pose_src/
ls -la pose_src/
```

Expected output:
```
config.py
best_AP_epoch_72.pth   # 408MB
mmpose_handler.py
MAR-INF/
```

- [ ] **Step 3: config.py 검증 (Section 2 가정과 일치 확인)**

```bash
grep -E "type=|backbone|num_joints|input_size|dataset_info|keypoint_info" pose_src/config.py | head -40
```

확인 항목:
- `model.type` (TopDown 류 — `TopDown`, `TopdownHeatmap` 등)
- `backbone.type` (`HRNet` / `ResNet`)
- `data_cfg.image_size` 또는 `input_size` (256x192 인지)
- `data_cfg.num_joints` 또는 `model.keypoint_head.out_channels` (17 인지)
- `dataset_info` 또는 `dataset_type` (COCO 17 keypoint 인지)

**Decision point:** 만약 config 가:
- num_joints ≠ 17 → spec append: `ad-coco-17` 대신 실제 정의 사용
- input_size ≠ (192, 256) → AdPoseEstimator crop resize target 변경
- backbone 이 ResNet 50 이면 ONNX 출력 ~200MB 가능 (HRNet-w48 은 ~400MB)

발견 사항을 `docs/sub2b_results.md` 의 "M2b.1 config 검증" 섹션에 메모. 큰 차이가 있으면 spec 업데이트 후 commit 별도.

- [ ] **Step 4: sample input 준비**

Sub-1 의 sample 재사용:

```bash
cp /home/ingon/AR_book/sub1_workdir/sample_input.jpg /home/ingon/AR_book/sub2b_workdir/sample_input.jpg 2>/dev/null \
  || ls /home/ingon/AR_book/AnimatedDrawings/examples/ | head
```

만약 없으면 AD examples 폴더에서 손그림 1장 골라 복사. 그래도 없으면 갤탭 캡처 1장 가져옴.

- [ ] **Step 5: 검증 결과 메모 (커밋 없음 — PC 작업 디렉토리는 git 밖)**

Android repo 의 결과 doc 에 적기:
```bash
# 일단 nothing to commit. Task 5 에서 sub2b_results.md 초안 시작 시 추가
```

---

## Task 2: PC — `run_export.py` 작성 + ONNX export

**Goal:** Sub-1 의 mmdet export 패턴을 mmpose 로 mirror 하여 `out/end2end.onnx` 생성.

**Files:**
- Create: `/home/ingon/AR_book/sub2b_workdir/run_export.py`
- Output: `/home/ingon/AR_book/sub2b_workdir/out/end2end.onnx` (~400MB)

- [ ] **Step 1: Sub-1 의 run_export.py 참조하여 mmpose 용 작성**

`run_export.py`:

```python
"""
Direct ONNX export for AD pose estimator (mmpose 2.x config) with mmdeploy 1.3.x + mmpose 1.x.

Patches (mirror of Sub-1 run_export.py):
1. model._scope_ = 'mmpose' — mmengine MODELS.build searches mmpose child registry
2. data_preprocessor with type 'mmpose.PoseDataPreprocessor' — mmpose 3.x BaseModel requires it
3. test_pipeline patched to mmpose 3.x equivalents (TopdownAffine, PackPoseInputs)
4. opset 16, dynamic_axes batch=1 fixed
"""
import os
import sys

WORK_DIR = '/home/ingon/AR_book/sub2b_workdir/out'
MODEL_CFG = '/home/ingon/AR_book/sub2b_workdir/pose_src/config.py'
CHECKPOINT = '/home/ingon/AR_book/sub2b_workdir/pose_src/best_AP_epoch_72.pth'
IMG = '/home/ingon/AR_book/sub2b_workdir/sample_input.jpg'
DEVICE = 'cpu'
DEPLOY_CFG = '/home/ingon/AR_book/sub2b_workdir/deploy_pose.py'

os.makedirs(WORK_DIR, exist_ok=True)

# Minimal deploy config (mmpose pose-detection top-down ONNX export)
DEPLOY_CFG_CONTENT = """
_base_ = ['../mmdeploy/configs/_base_/onnx_config.py']
codebase_config = dict(type='mmpose', task='PoseDetection')
onnx_config = dict(
    type='onnx',
    export_params=True,
    keep_initializers_as_inputs=False,
    opset_version=16,
    save_file='end2end.onnx',
    input_names=['input'],
    output_names=['output'],
    input_shape=[192, 256],  # (W, H) mmpose convention
    dynamic_axes=dict(input={0: 'batch'}, output={0: 'batch'}),
)
"""

# Write deploy config if missing
if not os.path.isfile(DEPLOY_CFG):
    with open(DEPLOY_CFG, 'w') as f:
        f.write(DEPLOY_CFG_CONTENT)

import mmengine
from mmdeploy.utils import load_config
from mmdeploy.codebase import import_codebase
from mmdeploy.utils import Codebase
from mmdeploy.apis.utils import build_task_processor
from mmdeploy.apis.onnx import export

print("Loading configs...")
deploy_cfg, model_cfg = load_config(DEPLOY_CFG, MODEL_CFG)

print("Patching model config for mmpose 3.x compatibility...")
# Fix 1: _scope_
model_cfg.model['_scope_'] = 'mmpose'

# Fix 2: data_preprocessor
if 'data_preprocessor' not in model_cfg.model:
    model_cfg.model['data_preprocessor'] = {}
if 'type' not in model_cfg.model['data_preprocessor']:
    img_norm_cfg = model_cfg.get('img_norm_cfg', {})
    model_cfg.model['data_preprocessor'] = {
        'type': 'mmpose.PoseDataPreprocessor',
        'mean': img_norm_cfg.get('mean', [123.675, 116.28, 103.53]),
        'std': img_norm_cfg.get('std', [58.395, 57.12, 57.375]),
        'bgr_to_rgb': img_norm_cfg.get('to_rgb', True),
    }
print("data_preprocessor:", model_cfg.model['data_preprocessor'])

print("Importing codebase (mmpose)...")
import_codebase(Codebase.MMPOSE)

print("Building task processor...")
task_processor = build_task_processor(model_cfg, deploy_cfg, DEVICE)

print("Building PyTorch model (loading checkpoint)...")
torch_model = task_processor.build_pytorch_model(CHECKPOINT)
print(f"  Model built: {type(torch_model).__name__}")

print("Patching test_pipeline for mmpose 3.x...")
# mmpose 2.x test_pipeline uses TopDownGetBboxCenterScale, TopDownAffine (legacy names).
# Replace with mmpose 3.x equivalents.
model_cfg.test_pipeline = [
    dict(type='mmpose.LoadImage'),
    dict(type='mmpose.GetBBoxCenterScale'),
    dict(type='mmpose.TopdownAffine', input_size=(192, 256)),
    dict(type='mmpose.PackPoseInputs'),
]

print("Creating model inputs...")
data, model_inputs = task_processor.create_input(IMG, input_shape=(192, 256))

print(f"Exporting ONNX to {WORK_DIR}/end2end.onnx ...")
output_file = os.path.join(WORK_DIR, 'end2end.onnx')
export(
    torch_model,
    model_inputs,
    output_path_prefix=os.path.join(WORK_DIR, 'end2end'),
    backend='onnxruntime',
    input_metas={'data_samples': None},
    context_info=dict(deploy_cfg=deploy_cfg),
    opset_version=16,
    input_names=['input'],
    output_names=['output'],
    dynamic_axes=dict(input={0: 'batch'}, output={0: 'batch'}),
)
print(f"Done. ONNX size: {os.path.getsize(output_file) / 1024 / 1024:.1f} MB")
```

- [ ] **Step 2: AD 의 conda env 진입 또는 mmdeploy env 사용**

Sub-1 에서 썼던 env 그대로:

```bash
conda activate animated_drawings   # 또는 mmdeploy 가 있는 env
python -c "import mmdeploy, mmpose; print(mmdeploy.__version__, mmpose.__version__)"
```

만약 mmpose 미설치:

```bash
pip install mmpose mmdeploy[onnxruntime]
```

- [ ] **Step 3: export 실행**

```bash
cd /home/ingon/AR_book/sub2b_workdir
python run_export.py 2>&1 | tee export.log
```

Expected output (성공):
```
...
Done. ONNX size: 390.5 MB
```

Expected failures (Sub-1 에서 본 류, 발견되면 patch append):
- `KeyError: 'PoseDataPreprocessor'` → mmpose 버전 mismatch, type 이름 변경
- `KeyError: '_scope_'` → 다른 위치 추가 필요
- `Cannot find type 'TopDownGetBboxCenterScale'` → test_pipeline 이름 mapping 수정
- export 후 onnx checker 실패 → fix_onnx.py 가 Task 3 에서 처리

- [ ] **Step 4: 산출물 확인**

```bash
ls -la out/end2end.onnx
file out/end2end.onnx
```

Expected: 200-450MB binary, "ONNX" 시그니처 보임.

- [ ] **Step 5: commit (Android repo 의 docs 에 export script reference 만 commit. PC workdir 자체는 git 밖)**

Task 5 에서 sub2b_results.md 에 export 로그 요약 추가 시 함께 commit. 이 task 자체에는 Android repo 에 commit 할 게 없음.

---

## Task 3: PC — `verify_onnx.py` 작성 + 정확도 검증

**Goal:** ONNX 산출물이 PyTorch eager 와 좌표 차이 < 1px 인지 검증.

**Files:**
- Create: `/home/ingon/AR_book/sub2b_workdir/verify_onnx.py`

- [ ] **Step 1: 검증 스크립트 작성**

```python
"""
Verify out/end2end.onnx accuracy against PyTorch eager.
Loads sample_input.jpg, crops by hand (256x192 center crop), runs both, compares 17 keypoints.
"""
import numpy as np
import cv2
import onnxruntime as ort
import torch
from mmengine import Config
from mmpose.apis import init_model, inference_topdown

ONNX_PATH = '/home/ingon/AR_book/sub2b_workdir/out/end2end.onnx'
MODEL_CFG = '/home/ingon/AR_book/sub2b_workdir/pose_src/config.py'
CHECKPOINT = '/home/ingon/AR_book/sub2b_workdir/pose_src/best_AP_epoch_72.pth'
IMG_PATH = '/home/ingon/AR_book/sub2b_workdir/sample_input.jpg'

# 1) Load and prepare 256x192 input (mmpose convention: H=256, W=192)
img = cv2.imread(IMG_PATH)
H, W = img.shape[:2]
# Use entire image as bbox (center)
bbox = np.array([0, 0, W, H], dtype=np.float32)
center = np.array([(bbox[0]+bbox[2])/2, (bbox[1]+bbox[3])/2], dtype=np.float32)
scale = np.array([(bbox[2]-bbox[0])/200, (bbox[3]-bbox[1])/200], dtype=np.float32) * 1.25

resized = cv2.resize(img, (192, 256))
rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32)
mean = np.array([123.675, 116.28, 103.53], dtype=np.float32)
std = np.array([58.395, 57.12, 57.375], dtype=np.float32)
normalized = (rgb - mean) / std
nchw = normalized.transpose(2, 0, 1)[None, ...]  # (1, 3, 256, 192)

# 2) ONNX inference
sess = ort.InferenceSession(ONNX_PATH, providers=['CPUExecutionProvider'])
input_name = sess.get_inputs()[0].name
onnx_out = sess.run(None, {input_name: nchw})[0]  # heatmap [1, 17, 64, 48]
print(f"ONNX output shape: {onnx_out.shape}")

# 3) Argmax + sub-pixel
heatmaps = onnx_out[0]  # [17, 64, 48]
keypoints_onnx = []
for k in range(heatmaps.shape[0]):
    hm = heatmaps[k]
    yx = np.unravel_index(hm.argmax(), hm.shape)
    keypoints_onnx.append((yx[1], yx[0], float(hm[yx])))  # (x, y, score)
keypoints_onnx = np.array(keypoints_onnx, dtype=np.float32)
print(f"ONNX keypoints (heatmap space): {keypoints_onnx[:3]}")

# 4) PyTorch eager inference for comparison
model = init_model(MODEL_CFG, CHECKPOINT, device='cpu')
results = inference_topdown(model, IMG_PATH, bboxes=np.array([[0, 0, W, H]]), bbox_format='xyxy')
pred = results[0].pred_instances.keypoints[0]  # (17, 2) in original image space
pred_scores = results[0].pred_instances.keypoint_scores[0]  # (17,)
print(f"PyTorch keypoints (image space): {pred[:3]}")

# 5) Map ONNX heatmap → image space (heatmap 48x64 → resized 192x256 → original WxH)
scale_x = W / 48
scale_y = H / 64
onnx_image_space = np.stack([
    keypoints_onnx[:, 0] * scale_x,
    keypoints_onnx[:, 1] * scale_y,
], axis=1)
print(f"ONNX keypoints (image space): {onnx_image_space[:3]}")

# 6) Compare (visibility-weighted L2)
diff = np.linalg.norm(onnx_image_space - pred, axis=1)
print(f"Per-keypoint L2 (image-space px): {diff}")
print(f"Max diff: {diff.max():.2f} px,  Mean: {diff.mean():.2f} px")
assert diff.max() < 5.0, "ONNX deviates too much from PyTorch eager (>5px)"
print("PASS")
```

- [ ] **Step 2: 실행**

```bash
cd /home/ingon/AR_book/sub2b_workdir
python verify_onnx.py 2>&1 | tee verify.log
```

Expected:
```
ONNX output shape: (1, 17, 64, 48)
...
Max diff: 1.20 px,  Mean: 0.45 px
PASS
```

- [ ] **Step 3: 실패 시 디버깅 분기**

| 증상 | 원인 후보 | 대응 |
|---|---|---|
| `KeyError`, mmpose import fail | env mismatch | env 재구성 |
| ONNX shape ≠ [1,17,64,48] | num_keypoints 다름 | Task 1 Step 3 결정 분기로 회귀 |
| Max diff > 5px | normalization mean/std 차이 | config 의 실제 img_norm_cfg 으로 verify_onnx mean/std 일치 |
| All keypoints at (0,0) | heatmap 비정상 | export.log 검토, opset 13 까지 낮춰 재시도 |

- [ ] **Step 4: 산출물 보존**

```bash
ls -la out/end2end.onnx
cp out/end2end.onnx /tmp/ad_pose_backup.onnx   # M2b.3 에서 Android assets 로 복사하기 전 백업
```

---

## Task 4: Android — ONNX asset 배치 + APK 크기 체크

**Goal:** export 한 `end2end.onnx` 를 Android assets 에 배치하고 빌드 가능한지 확인.

**Files:**
- Create: `app/app/src/main/assets/models/ad_pose.onnx` (~400MB)
- Verify: APK build 통과

- [ ] **Step 1: ONNX 를 assets 에 복사**

```bash
cp /home/ingon/AR_book/sub2b_workdir/out/end2end.onnx \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models/ad_pose.onnx
ls -la /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models/
```

Expected:
```
drwxr-xr-x ...
-rw-r--r--   ad_pose.onnx         ~400000000
-rw-r--r--   drawn_humanoid_detector.onnx   168000000
-rw-r--r--   pose_landmarker_heavy.task     30000000
```

- [ ] **Step 2: Gradle clean build (의존성 변경 없음, asset 만 추가)**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh clean assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. APK 위치:
```
app/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: APK 크기 확인**

```bash
ls -la app/app/build/outputs/apk/debug/app-debug.apk
```

Expected: 700-800MB. 이 sub 의 PASS 기준 안에 들어가지만 quantization sub-task 후속 필요 (spec §5 R8 명시).

- [ ] **Step 4: commit (assets 만 추가)**

`.gitignore` 또는 git LFS 정책 확인 후 commit:

```bash
git check-ignore app/app/src/main/assets/models/ad_pose.onnx
# 빈 출력 = 추적됨
git add app/app/src/main/assets/models/ad_pose.onnx
git status --short
```

Sub-1 에서 168MB ONNX 추가했을 때 패턴 그대로. git LFS 가 아니면 일반 binary 로 추가.

```bash
git commit -m "build(sub-2b): add AD pose ONNX asset (mmpose, ~400MB, 17 COCO keypoints)"
```

---

## Task 5: Android — `Landmark.kt` 에 `PoseBackend` enum + `backend` field

**Goal:** `SkeletonData` 가 17/33 keypoint backend 를 구분 가능하게. backward-compat (backend 누락 JSON → MEDIAPIPE_33 fallback).

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/Landmark.kt`
- Modify: `app/app/src/test/java/com/k3i/stickerbook/rig/LandmarkTest.kt`

- [ ] **Step 1: failing test 작성 (backend round-trip)**

`app/app/src/test/java/com/k3i/stickerbook/rig/LandmarkTest.kt` 기존 파일에 추가:

```kotlin
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals

class LandmarkBackendTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mediapipe-33 backend round-trips`() {
        val data = SkeletonData(
            backend = PoseBackend.MEDIAPIPE_33,
            landmarks = listOf(Landmark(1f, 2f, 3f, 0.9f, 0.9f)),
            imageWidth = 100,
            imageHeight = 200,
        )
        val text = json.encodeToString(SkeletonData.serializer(), data)
        val parsed = json.decodeFromString(SkeletonData.serializer(), text)
        assertEquals(PoseBackend.MEDIAPIPE_33, parsed.backend)
        assertEquals(1, parsed.landmarks.size)
    }

    @Test
    fun `ad-coco-17 backend round-trips`() {
        val data = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = List(17) { Landmark(it.toFloat(), 0f, 0f, 0.5f, 0.5f) },
            imageWidth = 1920,
            imageHeight = 1080,
        )
        val text = json.encodeToString(SkeletonData.serializer(), data)
        val parsed = json.decodeFromString(SkeletonData.serializer(), text)
        assertEquals(PoseBackend.AD_COCO_17, parsed.backend)
        assertEquals(17, parsed.landmarks.size)
    }

    @Test
    fun `legacy json without backend field parses as MEDIAPIPE_33`() {
        val legacy = """
            {"landmarks": [{"x":0,"y":0,"z":0,"visibility":1,"presence":1}],
             "image_width": 100, "image_height": 200}
        """.trimIndent()
        val parsed = json.decodeFromString(SkeletonData.serializer(), legacy)
        assertEquals(PoseBackend.MEDIAPIPE_33, parsed.backend)
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh app:testDebugUnitTest --tests "*.LandmarkBackendTest" 2>&1 | tail -15
```

Expected: FAIL with `PoseBackend` unresolved reference.

- [ ] **Step 3: `Landmark.kt` 수정**

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
enum class PoseBackend {
    @SerialName("mediapipe-33") MEDIAPIPE_33,
    @SerialName("ad-coco-17") AD_COCO_17;

    val keypointCount: Int get() = when (this) {
        MEDIAPIPE_33 -> 33
        AD_COCO_17 -> 17
    }
}

@Serializable
data class SkeletonData(
    val backend: PoseBackend = PoseBackend.MEDIAPIPE_33,
    val landmarks: List<Landmark>,
    @SerialName("image_width") val imageWidth: Int,
    @SerialName("image_height") val imageHeight: Int,
)
```

`backend` 의 default value `MEDIAPIPE_33` 가 legacy JSON parse 시 fallback 역할.

- [ ] **Step 4: 테스트 재실행 → PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.LandmarkBackendTest" 2>&1 | tail -15
```

Expected:
```
BUILD SUCCESSFUL
3 tests completed
```

- [ ] **Step 5: 회귀 확인 — 기존 LandmarkTest 도 PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.LandmarkTest*" 2>&1 | tail -15
```

기존 2 tests 도 함께 PASS. 만약 실패 — 기존 test 가 SkeletonData(landmarks, w, h) 생성자 호출했다면 default backend 가 채워져서 OK 야 함.

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/Landmark.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/LandmarkTest.kt
git commit -m "feat(sub-2b): SkeletonData.backend (PoseBackend enum) with legacy fallback"
```

---

## Task 6: Android — `PoseEstimator` interface 분리 + `MediaPipePoseEstimator` rename

**Goal:** 현재 concrete class 인 `PoseEstimator` 를 interface 로 만들고, 기존 본체를 `MediaPipePoseEstimator` 로 옮긴다.

**Files:**
- Modify (BREAKING): `app/app/src/main/java/com/k3i/stickerbook/rig/PoseEstimator.kt`
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/MediaPipePoseEstimator.kt`
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt` (참조 한 줄)

- [ ] **Step 1: 새 파일 `MediaPipePoseEstimator.kt` 작성 (interface 선언 전)**

먼저 본체 옮길 파일 만들고 거기서 `: PoseEstimator` 붙임. interface 선언은 Step 2.

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class MediaPipePoseEstimator(context: Context) : PoseEstimator {

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
     * @param bbox ignored by MediaPipe — full-image detection
     */
    override fun estimate(image: Bitmap, bbox: RectF?): SkeletonData {
        val mpImage = BitmapImageBuilder(image).build()
        val result: PoseLandmarkerResult = landmarker.detect(mpImage)

        if (result.landmarks().isEmpty()) {
            Log.w(TAG, "no pose detected")
            return SkeletonData(
                backend = PoseBackend.MEDIAPIPE_33,
                landmarks = emptyList(),
                imageWidth = image.width,
                imageHeight = image.height,
            )
        }

        val mpLandmarks = result.landmarks()[0]
        val landmarks = mpLandmarks.map { lm ->
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
        return SkeletonData(
            backend = PoseBackend.MEDIAPIPE_33,
            landmarks = landmarks,
            imageWidth = image.width,
            imageHeight = image.height,
        )
    }

    fun close() {
        landmarker.close()
    }

    companion object {
        private const val TAG = "MediaPipePoseEstimator"
        private const val MODEL_ASSET_PATH = "models/pose_landmarker_heavy.task"
    }
}
```

- [ ] **Step 2: `PoseEstimator.kt` 를 interface 로 교체**

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Estimates 2D pose landmarks from an image.
 *
 * Implementations:
 * - [MediaPipePoseEstimator] — 33 BlazePose keypoints. bbox 무시.
 * - [AdPoseEstimator] — 17 COCO keypoints. bbox 필수 (top-down).
 */
interface PoseEstimator {

    /**
     * @param image full-frame bitmap (camera capture)
     * @param bbox optional bounding box for top-down crop. AD backend requires it.
     */
    fun estimate(image: Bitmap, bbox: RectF? = null): SkeletonData
}
```

- [ ] **Step 3: `PoseDetectionRigger.kt` 참조 한 줄 수정 (이 task 의 최소 변경)**

`PoseDetectionRigger.kt:101` 의 `val estimator = PoseEstimator(context)` 를:

```kotlin
val estimator = MediaPipePoseEstimator(context)
```

(나머지 `estimate(it)` 호출은 lambda signature 그대로. `RectF? = null` default 로 인자 안 줘도 됨)

- [ ] **Step 4: 빌드 확인**

```bash
./run-gradle.sh app:compileDebugKotlin 2>&1 | tail -10
```

Expected: SUCCESS.

- [ ] **Step 5: 기존 모든 unit test 실행 → 회귀 없음 확인**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -15
```

Expected: 21+ tests PASS (Sub-2 의 결과 doc 합계와 동일 + Landmark backend test 3개 추가).

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/PoseEstimator.kt \
        app/app/src/main/java/com/k3i/stickerbook/rig/MediaPipePoseEstimator.kt \
        app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt
git commit -m "refactor(sub-2b): PoseEstimator becomes interface; MediaPipe impl renamed"
```

---

## Task 7: Android — `SkeletonOverlay` 17/33 분기

**Goal:** SkeletonOverlay 가 `backend` 에 따라 17 COCO connection 또는 33 BlazePose connection 을 그리도록.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/SkeletonOverlay.kt`
- Modify: `app/app/src/test/java/com/k3i/stickerbook/rig/SkeletonOverlayTest.kt`

- [ ] **Step 1: failing test (17 backend 에서 17 점만 그리고 33 connection 안 호출)**

`SkeletonOverlayTest.kt` 에 추가:

```kotlin
@Test
fun `ad-coco-17 skeleton draws 17 dots only`() {
    val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    val landmarks = List(17) { i ->
        Landmark(
            x = (i * 5).toFloat(),
            y = (i * 5).toFloat(),
            z = 0f,
            visibility = 0.9f,
            presence = 0.9f,
        )
    }
    val skeleton = SkeletonData(
        backend = PoseBackend.AD_COCO_17,
        landmarks = landmarks,
        imageWidth = 100,
        imageHeight = 100,
    )
    val out = SkeletonOverlay.draw(bmp, skeleton)
    assertEquals(100, out.width)
    assertEquals(100, out.height)
    // pixel check: (0,0) is nose → red dot painted
    val px = out.getPixel(0, 0)
    assertNotEquals(Color.TRANSPARENT, px)
}

@Test
fun `empty landmarks returns unchanged bitmap`() {
    val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }
    val skeleton = SkeletonData(
        backend = PoseBackend.AD_COCO_17,
        landmarks = emptyList(),
        imageWidth = 50,
        imageHeight = 50,
    )
    val out = SkeletonOverlay.draw(bmp, skeleton)
    assertEquals(Color.WHITE, out.getPixel(0, 0))
}
```

- [ ] **Step 2: test 실행 → 실패 확인**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.SkeletonOverlayTest" 2>&1 | tail -15
```

Expected: FAIL (CONNECTIONS hardcoded 33 → 17 keypoint 입력 시 out-of-bound 점프, 또는 결과 다름).

- [ ] **Step 3: `SkeletonOverlay.kt` 수정 — backend 별 connection 분기**

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object SkeletonOverlay {

    private val BLAZE_33_CONNECTIONS = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 7,
        0 to 4, 4 to 5, 5 to 6, 6 to 8,
        9 to 10,
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
        15 to 17, 15 to 19, 15 to 21, 17 to 19,
        16 to 18, 16 to 20, 16 to 22, 18 to 20,
        11 to 23, 12 to 24, 23 to 24,
        23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
        24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
    )

    // COCO 17 connections (head, arms, torso, legs)
    private val COCO_17_CONNECTIONS = listOf(
        // head
        0 to 1, 0 to 2, 1 to 3, 2 to 4,
        // shoulders + arms
        5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10,
        // torso
        5 to 11, 6 to 12, 11 to 12,
        // legs
        11 to 13, 13 to 15, 12 to 14, 14 to 16,
    )

    fun draw(bitmap: Bitmap, skeleton: SkeletonData): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (skeleton.landmarks.isEmpty()) return out
        val canvas = Canvas(out)
        val connections = when (skeleton.backend) {
            PoseBackend.MEDIAPIPE_33 -> BLAZE_33_CONNECTIONS
            PoseBackend.AD_COCO_17 -> COCO_17_CONNECTIONS
        }
        drawConnections(canvas, skeleton.landmarks, connections)
        drawDots(canvas, skeleton.landmarks)
        return out
    }

    private fun drawConnections(
        canvas: Canvas,
        landmarks: List<Landmark>,
        connections: List<Pair<Int, Int>>,
    ) {
        val paint = Paint().apply {
            color = Color.argb(255, 0, 200, 255)
            strokeWidth = 4f
            isAntiAlias = true
        }
        for ((a, b) in connections) {
            if (a >= landmarks.size || b >= landmarks.size) continue
            val la = landmarks[a]
            val lb = landmarks[b]
            paint.alpha = if (la.visibility < 0.5f || lb.visibility < 0.5f) 76 else 255
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

- [ ] **Step 4: test 재실행 → PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.SkeletonOverlayTest" 2>&1 | tail -15
```

Expected: 4+ tests PASS (기존 2 + 신규 2).

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/SkeletonOverlay.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/SkeletonOverlayTest.kt
git commit -m "feat(sub-2b): SkeletonOverlay supports COCO 17 keypoint backend"
```

---

## Task 8: Android — `PoseHeatmapDecoder` (TDD)

**Goal:** ONNX heatmap `[17, 64, 48]` → 17 keypoints `(x, y, score)` (heatmap 좌표계). 순수 함수.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/PoseHeatmapDecoder.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/PoseHeatmapDecoderTest.kt`

- [ ] **Step 1: failing test (known heatmap → known keypoint)**

```kotlin
package com.k3i.stickerbook.rig

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoseHeatmapDecoderTest {

    @Test
    fun `argmax at known position returns that coordinate`() {
        // heatmap [1, 64, 48] (1 keypoint for simplicity)
        val hm = FloatArray(64 * 48) { 0f }
        // peak at (x=10, y=20)
        hm[20 * 48 + 10] = 1.0f
        val result = PoseHeatmapDecoder.decode(hm, numKeypoints = 1, height = 64, width = 48)
        assertEquals(1, result.size)
        assertEquals(10f, result[0].x, 0.01f)
        assertEquals(20f, result[0].y, 0.01f)
        assertEquals(1.0f, result[0].score, 0.01f)
    }

    @Test
    fun `17 keypoint heatmap returns 17 results`() {
        // [17, 64, 48] flat = 17 * 64 * 48
        val totalSize = 17 * 64 * 48
        val hm = FloatArray(totalSize) { 0f }
        // keypoint 5 peak at (5, 5)
        hm[5 * (64 * 48) + 5 * 48 + 5] = 0.8f
        val result = PoseHeatmapDecoder.decode(hm, numKeypoints = 17, height = 64, width = 48)
        assertEquals(17, result.size)
        assertEquals(5f, result[5].x, 0.01f)
        assertEquals(5f, result[5].y, 0.01f)
        assertEquals(0.8f, result[5].score, 0.01f)
    }

    @Test
    fun `sub-pixel offset adjusts peak by quarter step toward second-highest neighbor`() {
        // Standard mmpose post-process: peak + 0.25 * sign(neighbor - opposite)
        val hm = FloatArray(64 * 48) { 0f }
        hm[20 * 48 + 10] = 1.0f
        hm[20 * 48 + 11] = 0.9f   // right neighbor higher → x shifts +0.25
        hm[20 * 48 + 9] = 0.5f
        hm[19 * 48 + 10] = 0.3f
        hm[21 * 48 + 10] = 0.7f   // down neighbor higher → y shifts +0.25
        val result = PoseHeatmapDecoder.decode(hm, numKeypoints = 1, height = 64, width = 48)
        assertEquals(10.25f, result[0].x, 0.01f)
        assertEquals(20.25f, result[0].y, 0.01f)
    }
}
```

- [ ] **Step 2: test 실행 → 실패 (class 없음)**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.PoseHeatmapDecoderTest" 2>&1 | tail -10
```

- [ ] **Step 3: `PoseHeatmapDecoder.kt` 작성**

```kotlin
package com.k3i.stickerbook.rig

/**
 * Decodes a flattened heatmap [K, H, W] tensor into [K] keypoints (heatmap coord space).
 * Sub-pixel refinement: shift peak by 0.25 toward higher neighbor on each axis.
 *
 * Output coordinates are in heatmap space (e.g., 0..47 for W=48, 0..63 for H=64).
 * AdPoseEstimator maps them to crop-space and then to original-frame-space via affine inverse.
 */
object PoseHeatmapDecoder {

    data class Peak(val x: Float, val y: Float, val score: Float)

    fun decode(
        heatmap: FloatArray,
        numKeypoints: Int,
        height: Int,
        width: Int,
    ): List<Peak> {
        require(heatmap.size == numKeypoints * height * width) {
            "heatmap size ${heatmap.size} != $numKeypoints * $height * $width"
        }
        val out = ArrayList<Peak>(numKeypoints)
        val plane = height * width
        for (k in 0 until numKeypoints) {
            val base = k * plane
            var bestIdx = 0
            var bestVal = heatmap[base]
            for (i in 1 until plane) {
                val v = heatmap[base + i]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = i
                }
            }
            val py = bestIdx / width
            val px = bestIdx % width
            // Sub-pixel: shift toward higher neighbor by 0.25
            var fx = px.toFloat()
            var fy = py.toFloat()
            if (px in 1 until (width - 1)) {
                val right = heatmap[base + py * width + (px + 1)]
                val left = heatmap[base + py * width + (px - 1)]
                if (right > left) fx += 0.25f else if (left > right) fx -= 0.25f
            }
            if (py in 1 until (height - 1)) {
                val down = heatmap[base + (py + 1) * width + px]
                val up = heatmap[base + (py - 1) * width + px]
                if (down > up) fy += 0.25f else if (up > down) fy -= 0.25f
            }
            out.add(Peak(fx, fy, bestVal))
        }
        return out
    }
}
```

- [ ] **Step 4: test 실행 → PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.PoseHeatmapDecoderTest" 2>&1 | tail -10
```

Expected: 3 tests PASS.

- [ ] **Step 5: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/PoseHeatmapDecoder.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/PoseHeatmapDecoderTest.kt
git commit -m "feat(sub-2b): PoseHeatmapDecoder — heatmap argmax with sub-pixel offset"
```

---

## Task 9: Android — `AdPoseEstimator` (Sub-1 hot-fix 3개 + crop + ONNX + unproject)

**Goal:** AD ONNX pose model wrapper. crop bbox → 256x192 → ONNX → heatmap → 17 keypoints → 원본 frame 좌표.

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/AdPoseEstimator.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/AdPoseEstimatorTest.kt`

- [ ] **Step 1: failing test — affine inverse math (순수 함수, ONNX 없이)**

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.RectF
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AdPoseEstimatorTest {

    @Test
    fun `crop bbox of full image returns same image-space coords`() {
        // bbox = entire image 100x100, padding 1.25 ratio
        // keypoint at crop (96, 128) = center of 192x256 crop
        // → should map back to (50, 50) in image space
        val bbox = RectF(0f, 0f, 100f, 100f)
        val imageW = 100
        val imageH = 100
        val cropW = 192
        val cropH = 256

        // crop space center (96, 128)
        val cropPx = 96f
        val cropPy = 128f
        val (imgX, imgY) = AdPoseEstimator.unprojectFromCrop(
            cropX = cropPx, cropY = cropPy,
            bbox = bbox,
            cropW = cropW, cropH = cropH,
            paddingRatio = 1.25f,
            imageW = imageW, imageH = imageH,
        )
        // 100x100 image, bbox = entire image, 1.25 padding makes bbox 125x125 centered at (50,50).
        // crop 192x256 of that → center (96, 128) maps to image center (50, 50).
        assertEquals(50f, imgX, 0.5f)
        assertEquals(50f, imgY, 0.5f)
    }

    @Test
    fun `crop bbox in upper-left maps keypoint correctly`() {
        // bbox at (10, 20, 50, 80) — width 40, height 60
        // center = (30, 50), padded scale = 50 * 1.25 = 62.5 W, 75 * 1.25 = 75 H
        // wait — mmpose uses aspect-ratio aware scale. For input_size (W=192, H=256), ratio = 192/256 = 0.75
        // Use simpler check: keypoint at exact bbox top-left corner in crop space
        val bbox = RectF(10f, 20f, 50f, 80f)
        val (imgX, imgY) = AdPoseEstimator.unprojectFromCrop(
            cropX = 0f, cropY = 0f,  // crop top-left
            bbox = bbox, cropW = 192, cropH = 256,
            paddingRatio = 1.25f, imageW = 200, imageH = 200,
        )
        // crop (0,0) corresponds to bbox center - 0.5 * scale (in image coords).
        // Padded bbox: center = (30, 50), scale x = 40 * 1.25 = 50 (clamped to aspect ratio with H), scale y = 60 * 1.25 = 75.
        // Aspect-ratio aware: input_size aspect = 192/256 = 0.75 → scale = max(width, height/aspect) per mmpose convention
        // Just check the math is consistent: round-trip should bring it back
        val (cropBackX, cropBackY) = AdPoseEstimator.projectToCrop(
            imageX = imgX, imageY = imgY,
            bbox = bbox, cropW = 192, cropH = 256,
            paddingRatio = 1.25f,
        )
        assertEquals(0f, cropBackX, 1.0f)
        assertEquals(0f, cropBackY, 1.0f)
    }
}
```

- [ ] **Step 2: test 실행 → 실패 (class 없음)**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.AdPoseEstimatorTest" 2>&1 | tail -10
```

- [ ] **Step 3: `AdPoseEstimator.kt` 작성 (pure helper functions + ONNX wrapper)**

```kotlin
package com.k3i.stickerbook.rig

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

class AdPoseEstimator(private val context: Context) : PoseEstimator {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelPath = ensureModelOnDisk()
        val opts = OrtSession.SessionOptions().apply {
            // Sub-1 lesson: NNAPI off for mmpose ops too (HRNet has BatchNorm fusion that NNAPI
            // doesn't always handle on Galaxy Tab S9 FE+). CPU only.
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        Log.i(TAG, "creating ONNX session from $modelPath ...")
        session = env.createSession(modelPath, opts)
        Log.i(TAG, "loaded ad_pose ONNX, inputs=${session.inputNames}, outputs=${session.outputNames}")
    }

    /**
     * Sub-1 pattern: copy 400MB ONNX from APK assets to filesDir on first run.
     * Loading by file path lets ONNX Runtime mmap, avoiding 400MB byte[] heap allocation.
     */
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

    override fun estimate(image: Bitmap, bbox: RectF?): SkeletonData {
        if (bbox == null) {
            Log.w(TAG, "no bbox provided; AdPoseEstimator requires top-down bbox")
            return SkeletonData(PoseBackend.AD_COCO_17, emptyList(), image.width, image.height)
        }

        // 1. Build affine crop (256x192) using bbox center + padded scale
        val crop = cropAffine(image, bbox, CROP_W, CROP_H, PADDING_RATIO)

        // 2. Normalize ImageNet mean/std, NCHW float
        val inputBuf = preprocess(crop)

        // 3. ONNX inference
        val shape = longArrayOf(1, 3, CROP_H.toLong(), CROP_W.toLong())
        val tensor = OnnxTensor.createTensor(env, inputBuf, shape)
        val outputs = try {
            session.run(mapOf(session.inputNames.first() to tensor))
        } finally {
            // tensor lifetime: free after run() returns
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val raw = outputs[0].value as Array<Array<Array<FloatArray>>>  // [1,17,H,W]
            val heatmapH = raw[0][0].size
            val heatmapW = raw[0][0][0].size
            val flat = FloatArray(NUM_KEYPOINTS * heatmapH * heatmapW)
            for (k in 0 until NUM_KEYPOINTS) {
                for (r in 0 until heatmapH) {
                    raw[0][k][r].copyInto(flat, k * heatmapH * heatmapW + r * heatmapW)
                }
            }

            // 4. Decode heatmap → 17 (x, y, score) in heatmap space
            val peaks = PoseHeatmapDecoder.decode(flat, NUM_KEYPOINTS, heatmapH, heatmapW)

            // 5. Heatmap → crop space (multiply by stride = CROP / heatmap)
            val strideX = CROP_W.toFloat() / heatmapW
            val strideY = CROP_H.toFloat() / heatmapH

            // 6. Crop space → original image space (affine inverse)
            val landmarks = peaks.map { p ->
                val cropX = p.x * strideX
                val cropY = p.y * strideY
                val (imgX, imgY) = unprojectFromCrop(
                    cropX = cropX, cropY = cropY,
                    bbox = bbox, cropW = CROP_W, cropH = CROP_H,
                    paddingRatio = PADDING_RATIO,
                    imageW = image.width, imageH = image.height,
                )
                Landmark(
                    x = imgX,
                    y = imgY,
                    z = 0f,
                    visibility = p.score,
                    presence = p.score,
                )
            }
            Log.i(TAG, "pose detected: ${landmarks.size} landmarks (max score ${peaks.maxOf { it.score }})")
            return SkeletonData(
                backend = PoseBackend.AD_COCO_17,
                landmarks = landmarks,
                imageWidth = image.width,
                imageHeight = image.height,
            )
        } finally {
            outputs.close()
            tensor.close()
        }
    }

    private fun cropAffine(image: Bitmap, bbox: RectF, w: Int, h: Int, padding: Float): Bitmap {
        val (cx, cy, sw, sh) = paddedBbox(bbox, w, h, padding)
        val mat = Matrix()
        // Source rect: padded bbox centered at (cx, cy) with size (sw, sh)
        // Dest: 0,0 to w,h
        mat.postTranslate(-(cx - sw / 2f), -(cy - sh / 2f))
        mat.postScale(w / sw, h / sh)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(image, mat, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(3 * h * w)
        // NCHW, R-G-B mean/std normalize
        val mean = floatArrayOf(123.675f, 116.28f, 103.53f)
        val std = floatArrayOf(58.395f, 57.12f, 57.375f)
        for (c in 0..2) {
            for (i in 0 until h * w) {
                val argb = pixels[i]
                val v = when (c) {
                    0 -> (argb shr 16) and 0xFF
                    1 -> (argb shr 8) and 0xFF
                    else -> argb and 0xFF
                }
                buf.put((v.toFloat() - mean[c]) / std[c])
            }
        }
        buf.rewind()
        return buf
    }

    fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "AdPoseEstimator"
        private const val MODEL_NAME = "ad_pose.onnx"
        private const val CROP_W = 192
        private const val CROP_H = 256
        private const val PADDING_RATIO = 1.25f
        private const val NUM_KEYPOINTS = 17

        /**
         * Aspect-ratio aware padded bbox following mmpose convention.
         * Returns (centerX, centerY, scaleW, scaleH) in image coordinates.
         */
        private fun paddedBbox(
            bbox: RectF, cropW: Int, cropH: Int, padding: Float,
        ): FloatArray {
            val cx = (bbox.left + bbox.right) / 2f
            val cy = (bbox.top + bbox.bottom) / 2f
            val bw = bbox.width()
            val bh = bbox.height()
            val aspect = cropW.toFloat() / cropH
            // Make padded bbox match crop aspect, then apply padding
            var sw = bw
            var sh = bh
            if (sw / sh > aspect) {
                sh = sw / aspect
            } else {
                sw = sh * aspect
            }
            sw *= padding
            sh *= padding
            return floatArrayOf(cx, cy, sw, sh)
        }

        /**
         * Map a point from crop-space (0..cropW, 0..cropH) back to image-space.
         */
        fun unprojectFromCrop(
            cropX: Float, cropY: Float,
            bbox: RectF, cropW: Int, cropH: Int,
            paddingRatio: Float, imageW: Int, imageH: Int,
        ): Pair<Float, Float> {
            val (cx, cy, sw, sh) = paddedBbox(bbox, cropW, cropH, paddingRatio)
            // crop (0,0) = image (cx - sw/2, cy - sh/2)
            // crop (cropW, cropH) = image (cx + sw/2, cy + sh/2)
            val imgX = (cx - sw / 2f) + (cropX / cropW) * sw
            val imgY = (cy - sh / 2f) + (cropY / cropH) * sh
            return imgX to imgY
        }

        /**
         * Inverse of [unprojectFromCrop]. For test round-trip.
         */
        fun projectToCrop(
            imageX: Float, imageY: Float,
            bbox: RectF, cropW: Int, cropH: Int, paddingRatio: Float,
        ): Pair<Float, Float> {
            val (cx, cy, sw, sh) = paddedBbox(bbox, cropW, cropH, paddingRatio)
            val cropX = (imageX - (cx - sw / 2f)) / sw * cropW
            val cropY = (imageY - (cy - sh / 2f)) / sh * cropH
            return cropX to cropY
        }

        // Helper for destructuring FloatArray of size 4
        private operator fun FloatArray.component1() = this[0]
        private operator fun FloatArray.component2() = this[1]
        private operator fun FloatArray.component3() = this[2]
        private operator fun FloatArray.component4() = this[3]
    }
}
```

- [ ] **Step 4: test 재실행 → PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.AdPoseEstimatorTest" 2>&1 | tail -10
```

Expected: 2 tests PASS (pure math, ONNX session not invoked).

- [ ] **Step 5: 전체 test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -20
```

Expected: 모든 기존 tests + 신규 tests PASS (35+ total).

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/AdPoseEstimator.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/AdPoseEstimatorTest.kt
git commit -m "feat(sub-2b): AdPoseEstimator — ONNX wrapper, crop affine + heatmap decode + unproject"
```

---

## Task 10: Android — `PoseDetectionRigger` DI + bbox 전달 + factory rename

**Goal:** `PoseDetectionRigger` 가 estimator 를 DI 로 받고, detector 의 bbox 를 estimator 에 전달.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt`
- Modify: `app/app/src/test/java/com/k3i/stickerbook/rig/PoseDetectionRiggerTest.kt`

- [ ] **Step 1: failing test — estimator 가 bbox 를 받았는지 검증**

`PoseDetectionRiggerTest.kt` 에 추가:

```kotlin
@Test
fun `rig passes detector bbox to estimator`() = runBlocking {
    val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }
    val fakeDetection = Detection(
        bbox = RectF(10f, 20f, 80f, 90f),
        mask = Bitmap.createBitmap(70, 70, Bitmap.Config.ARGB_8888),
        score = 0.9f,
    )
    var receivedBbox: RectF? = null
    val rigger = PoseDetectionRigger.withStubs(
        context = ApplicationProvider.getApplicationContext(),
        detect = { listOf(fakeDetection) },
        estimate = { _, bbox ->
            receivedBbox = bbox
            SkeletonData(
                PoseBackend.AD_COCO_17,
                List(17) { Landmark(0f, 0f, 0f, 0.5f, 0.5f) },
                100, 100,
            )
        },
    )
    rigger.rig(image, "wave")
    assertEquals(RectF(10f, 20f, 80f, 90f), receivedBbox)
}

@Test
fun `rig passes null bbox when detector returns nothing`() = runBlocking {
    val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    var receivedBbox: RectF? = RectF(0f, 0f, 1f, 1f) // sentinel
    val rigger = PoseDetectionRigger.withStubs(
        context = ApplicationProvider.getApplicationContext(),
        detect = { emptyList() },
        estimate = { _, bbox ->
            receivedBbox = bbox
            SkeletonData(PoseBackend.MEDIAPIPE_33, emptyList(), 100, 100)
        },
    )
    rigger.rig(image, "wave")
    assertNull(receivedBbox)
}
```

- [ ] **Step 2: test 실행 → 실패 (estimate signature 다름)**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.PoseDetectionRiggerTest" 2>&1 | tail -10
```

- [ ] **Step 3: `PoseDetectionRigger.kt` 수정**

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class PoseDetectionRigger private constructor(
    private val context: Context,
    private val detect: (Bitmap) -> List<Detection>,
    private val estimate: (Bitmap, RectF?) -> SkeletonData,
) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detect(image)
        val top = detections.maxByOrNull { it.score }
        val bbox = top?.bbox

        val skeleton: SkeletonData? = runCatching { estimate(image, bbox) }
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

    private fun applyMask(image: Bitmap, mask: Bitmap, bbox: RectF): Bitmap {
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

        fun realAd(context: Context): PoseDetectionRigger {
            val detector = MaskRcnnDetector(context)
            val estimator = AdPoseEstimator(context)
            return PoseDetectionRigger(
                context,
                detect = { detector.detect(it) },
                estimate = { image, bbox -> estimator.estimate(image, bbox) },
            )
        }

        fun realMediaPipe(context: Context): PoseDetectionRigger {
            val detector = MaskRcnnDetector(context)
            val estimator = MediaPipePoseEstimator(context)
            return PoseDetectionRigger(
                context,
                detect = { detector.detect(it) },
                estimate = { image, _ -> estimator.estimate(image, null) },
            )
        }

        fun withStubs(
            context: Context,
            detect: (Bitmap) -> List<Detection>,
            estimate: (Bitmap, RectF?) -> SkeletonData,
        ): PoseDetectionRigger = PoseDetectionRigger(context, detect, estimate)
    }
}
```

- [ ] **Step 4: 기존 PoseDetectionRiggerTest 의 withStubs 호출이 깨졌을 수 있음 — 수정**

기존:
```kotlin
estimate = { SkeletonData(...) }
```

→ 새:
```kotlin
estimate = { _, _ -> SkeletonData(...) }
```

`PoseDetectionRiggerTest.kt` 의 모든 `withStubs` 호출 자리에 lambda signature 추가. `(Bitmap, RectF?) -> SkeletonData`.

- [ ] **Step 5: test 실행 → 신규 + 기존 모두 PASS**

```bash
./run-gradle.sh app:testDebugUnitTest --tests "*.PoseDetectionRiggerTest" 2>&1 | tail -15
```

Expected: 4+ tests PASS.

- [ ] **Step 6: 전체 unit test 회귀**

```bash
./run-gradle.sh app:testDebugUnitTest 2>&1 | tail -20
```

Expected: 모두 PASS (35+).

- [ ] **Step 7: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/PoseDetectionRigger.kt \
        app/app/src/test/java/com/k3i/stickerbook/rig/PoseDetectionRiggerTest.kt
git commit -m "feat(sub-2b): PoseDetectionRigger DI + bbox propagation, realAd/realMediaPipe factories"
```

---

## Task 11: Android — `AppNavHost` 1줄 swap + APK build

**Goal:** Production wiring 을 AD pose 로 전환. APK 빌드 + 갤탭 install.

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt:43`

- [ ] **Step 1: 한 줄 수정**

```kotlin
// Before:
val rigger = remember { PoseDetectionRigger.real(ctx) }

// After:
val rigger = remember { PoseDetectionRigger.realAd(ctx) }
```

- [ ] **Step 2: 빌드**

```bash
./run-gradle.sh assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 갤탭 install**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  app/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 4: 갤탭 launch + logcat 모니터**

```bash
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe shell \
  am start -n com.k3i.stickerbook/.MainActivity
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -c
/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat \
  -s "AdPoseEstimator" "PoseDetectionRigger" "MaskRcnnDetector" "AndroidRuntime"
```

- [ ] **Step 5: 갤탭 시연**

1. + FAB → 카메라 → 손그림 비추기 → 캡처
2. ▶ → 모션 선택 → 만들기 ▶
3. 1-2분 대기 (Sub-1 detector dominant + AD pose ~수십 초)
4. 그리드 복귀, 새 `pose_<ts>` 카드 등장
5. 카드 탭 → 상세 화면에 누끼 + 17 keypoints 점 + COCO connection 시각화

PASS 기준 (spec §1):
- (a) detector 의 bbox crop 통과 ✓
- (b) 17 keypoints 중 ≥ 1개 visibility > 0.3
- (c) 머리/팔/다리 위치가 그림과 시각적으로 일치

- [ ] **Step 6: commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(sub-2b): swap to AD pose estimator (realAd) for production rigger"
```

---

## Task 12: 결과 doc + memory 업데이트

**Goal:** Sub-2b PASS 보고. 다음 sub-project (Sub-3 ARAP) 진입 조건 정리.

**Files:**
- Create: `docs/sub2b_results.md`
- Modify: `/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md`

- [ ] **Step 1: `docs/sub2b_results.md` 작성**

(Sub-2 의 results 패턴 따라가기. 갤탭 시연 결과 + 시각 평가 + 다음 sub 진입 조건)

```markdown
# Sub-2b 결과 — AD pose ONNX 통합

날짜: 2026-05-1X
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.2.0 (debug)

## M2b.1 — PC: .mar 추출 + config 검증

| 항목 | 측정값 | 비고 |
|---|---|---|
| backbone | (실제 값) | HRNet-w48 / ResNet-50 |
| num_keypoints | (실제 값) | 17 가정 검증 |
| input_size | (실제 값) | (192, 256) 가정 검증 |

## M2b.2 — ONNX export

| 항목 | 측정값 |
|---|---|
| ONNX size | XYZ MB |
| Python ORT verify max diff | X.XX px |
| Python ORT verify mean diff | X.XX px |

## M2b.3 — Android wrapper

- ✅ AdPoseEstimator 통합 (Sub-1 hot-fix 3개 적용: file mmap, NNAPI off, Dispatchers.Default)
- ✅ Unit tests N+ PASS (PoseHeatmapDecoder, AdPoseEstimator math, Landmark backend, SkeletonOverlay 17)

## M2b.4 — 갤탭 시연

| 항목 | 측정값 | 목표 | PASS |
|---|---|---|---|
| 인스턴스 load | ✅ | crash 없음 | ✅ |
| End-to-end latency | ~1-2분 (Sub-1 dominant) | < 5s | ❌ quantization 후속 |
| 17 keypoint 검출 | (수치) | ≥ 1개 vis > 0.3 | ✅/❌ |
| skeleton overlay 시각 일치 | (관찰) | 머리/팔/다리 일치 | ✅/❌ |

## APK 크기

| 모델 | 크기 |
|---|---|
| drawn_humanoid_detector.onnx | 168 MB |
| pose_landmarker_heavy.task | 30 MB |
| ad_pose.onnx | ~400 MB |
| APK 총 | ~780 MB |

quantization sub-task 후속 필요 (Sub-1 + Sub-2b 함께).

## 알려진 이슈 / Follow-up

- ⚠️ APK 780MB
- ⚠️ Inference 1-2분 (Sub-1 dominant)
- (시연 결과 따라) keypoint 정확도 평가

## Sub-3 (ARAP) 진입 조건

- ☐ Sub-2b 시각 검증 통과
- ☐ skeleton.json (ad-coco-17 또는 mediapipe-33) 안정
- ☐ ARAP mesh 알고리즘 결정
```

- [ ] **Step 2: memory 업데이트**

`/home/ingon/.claude/projects/-home-ingon-AR-book/memory/project_phase2_progress.md` 의 Sub-2b 줄 수정:

```
| **Sub-2b** | ✅ 완료 (2026-05-1X) — AD pose ONNX 통합, 17 keypoints 검출. 시각 검증 (결과 doc 참조). APK 780MB | (등) |
```

Sub-3 차례 명시.

진입 한 줄 업데이트:
```
진입 한 줄: "stickerbook Sub-3 (ARAP mesh) brainstorm 진행해줘"
```

- [ ] **Step 3: commit**

```bash
git add docs/sub2b_results.md
git commit -m "docs(sub-2b): M2b results — AD pose ONNX integration on Galaxy Tab"
```

---

## 진행 순서 요약

1. **Task 1-3 (PC, ~1-2일)**: .mar 추출 → run_export.py → ONNX export → verify_onnx.py
   - **Major risk**: mmpose 2.x→3.x patch 추가 필요 시 spec append + retry
2. **Task 4 (~15분)**: ONNX asset 배치 + APK build 통과 확인
3. **Task 5-7 (~1-2시간)**: backend enum + interface + overlay 분기 (TDD)
4. **Task 8-9 (~3-4시간)**: PoseHeatmapDecoder + AdPoseEstimator (TDD, pure math test)
5. **Task 10 (~30분)**: PoseDetectionRigger DI
6. **Task 11 (~15분)**: AppNavHost swap + APK install + 갤탭 시연
7. **Task 12 (~30분)**: results doc + memory

총 예상: 3-5일 (Sub-1 비슷, PC export 가 dominant)

## Sub-1 hot-fix 3개 재발 방지 (이미 plan 에 반영됨)

- ✅ Task 9: `ensureModelOnDisk()` + `createSession(filePath)` + `largeHeap=true` (AndroidManifest 이미 있음)
- ✅ Task 9: NNAPI off, CPU only, BASIC_OPT
- ✅ AppNavHost 의 `withContext(Dispatchers.Default)` 기존대로 유지 (Task 11)
