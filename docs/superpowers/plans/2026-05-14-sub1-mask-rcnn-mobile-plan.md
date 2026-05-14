# Sub-1 Implementation Plan — AD Mask R-CNN 모바일 포팅

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PC 의 AnimatedDrawings Mask R-CNN detector 를 ONNX 변환 후 갤럭시 태블릿 ONNX Runtime Mobile 로 실행. bbox + mask 출력 → Sub-5 의 `StubRigger` 를 `DetectionOnlyRigger` 로 교체.

**Architecture:** 두 단계 — PC 측 변환 (MMDeploy 로 .pth → .onnx, 검증) + 갤탭 측 통합 (ONNX Runtime Mobile Kotlin wrapper, CharacterRigger 구현체 교체). 위험은 M1.1 (RoI Align 변환), 막히면 옵션 [B] (YOLO retrain) 로 fallback.

**Tech Stack:**
- PC: Python 3.10, conda env `mmdeploy_export`, mmdetection 3.x, mmdeploy 1.x, onnxruntime, PyTorch 2.x
- 갤탭: Kotlin 2.0, ONNX Runtime Mobile 1.17.1, 기존 Jetpack Compose
- 모델: `drawn_humanoid_detector.mar` (MMDetection Mask R-CNN, ResNet-50 backbone)

**기본 결정 (Open Q default):**
- Quantization: FP32 먼저, FP16 은 latency 문제 발견 시
- Sample input: PC 의 `stickerbook/assets/captures/*/source.png` (이미 3 sample 있음)
- NNAPI: try-catch (실패 시 CPU fallback)
- 모델 배포: APK assets/ 번들
- 디버그 시각화 화면: 추가 안 함 (M1.4 통합 결과로 시각 확인)
- Conda env: 새로 `mmdeploy_export` 분리

---

## File Structure

### PC 측 — 새 작업 디렉토리

```
/home/ingon/AR_book/sub1_workdir/    (신규, git ignore — 작업물만)
├── detector_src/                    .mar 추출 결과
│   ├── latest.pth                   가중치 (~350MB)
│   ├── config.py                    mmdet model config
│   └── mmdet_handler.py             (참고용, 변환에 미사용)
├── out/                              MMDeploy export 출력
│   ├── end2end.onnx                 ★ 최종 산출
│   └── deploy.json                  메타정보
├── sample_input.jpg                 검증용 sample (capture 3개 중 1개)
├── deploy_config.py                 MMDeploy deploy config (복사)
├── export_onnx.sh                   M1.1 자동화 script
└── verify_onnx.py                   M1.2 검증 script
```

### 갤탭 측 — 기존 `stickerbook_android_porting/` 안에 추가

```
app/app/
├── build.gradle.kts                 수정 (onnxruntime-android 의존성)
└── src/main/
    ├── assets/models/                신규
    │   └── drawn_humanoid_detector.onnx     (PC 에서 복사, gitignore)
    └── java/com/k3i/stickerbook/rig/
        ├── MaskRcnnDetector.kt       신규 (ONNX inference wrapper)
        ├── DetectionOnlyRigger.kt    신규 (Sub-2 진입 전 임시 Rigger)
        ├── ImagePreprocess.kt        신규 (Bitmap → FloatBuffer)
        └── MaskPostprocess.kt        신규 (ONNX output → Detection 리스트)

app/app/src/test/java/com/k3i/stickerbook/rig/
├── ImagePreprocessTest.kt           신규 (Robolectric)
├── MaskPostprocessTest.kt           신규 (pure JVM)
└── DetectionOnlyRiggerTest.kt       신규 (Robolectric, MaskRcnnDetector mock)

app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt   수정 (Stub→Detection)
```

수정:
- `app/app/build.gradle.kts`: onnxruntime-android dep
- `.gitignore`: `app/app/src/main/assets/models/`, `app/app/build/` (이미)
- `ui/nav/AppNavHost.kt`: StubRigger → DetectionOnlyRigger
- `docs/sub1_results.md`: 신규 (최종 task)

작업 위치 안내 (모든 subagent 에게):
- PC 작업: `/home/ingon/AR_book/sub1_workdir/` (WSL Linux)
- 갤탭 작업: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/` (Windows-side, 절대 `/home/ingon/AR_book/stickerbook_android_porting/` 가지 말 것)
- Gradle wrapper: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/run-gradle.sh`
- Windows ADB: `/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe`

---

## Task 1: PC 작업 환경 셋업 (conda env + MMDeploy)

**Files:**
- Create: `/home/ingon/AR_book/sub1_workdir/` directory
- Modify: none (환경만)

- [ ] **Step 1: 작업 디렉토리 + sample input 준비**

```bash
mkdir -p /home/ingon/AR_book/sub1_workdir
cd /home/ingon/AR_book/sub1_workdir
cp /home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook/assets/captures/2026-05-11_10-22-29_motion_4/input.png sample_input.jpg \
   2>/dev/null || \
   cp /home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook/stickerbook_assets/stickers/s004/source.png sample_input.jpg \
   2>/dev/null || \
   echo "WARN: no sample input found; manually drop one as sample_input.jpg"
ls -la sample_input.jpg
```

Expected: `sample_input.jpg` 파일이 폴더에 있음.

- [ ] **Step 2: conda env 생성 + activate**

```bash
conda create -n mmdeploy_export python=3.10 -y
conda activate mmdeploy_export
python --version
```

Expected: Python 3.10.x.

- [ ] **Step 3: PyTorch + MMCV + MMDetection + MMDeploy 설치**

MMDeploy 의존성은 PyTorch + mmcv + mmdet 순서. PyTorch CPU 버전이면 작음.

```bash
conda activate mmdeploy_export
pip install --upgrade pip
pip install torch==2.1.0 torchvision==0.16.0 --index-url https://download.pytorch.org/whl/cpu

# OpenMMLab installer (간단)
pip install -U openmim
mim install "mmengine>=0.10.0"
mim install "mmcv==2.1.0"
mim install "mmdet==3.2.0"

# MMDeploy + ONNX Runtime
pip install "mmdeploy==1.3.1" "mmdeploy-runtime==1.3.1"
pip install onnxruntime==1.17.1
```

설치 시간: 약 5-15분 (네트워크 + 디스크).

확인:
```bash
python -c "import torch, mmcv, mmdet, mmdeploy, onnxruntime; print('all imports OK')"
```

Expected: `all imports OK`.

- [ ] **Step 4: BLOCKED 처리 (옵션)**

위 설치 중 실패 (mmcv 빌드 fail, torch 호환성 등) — STOP, report BLOCKED. 사용자가 수동 셋업 결정 또는 fallback 모델 결정.

다음 task 진입은 Step 3 의 `all imports OK` 확인 후.

(no commit — 환경 셋업 only)

---

## Task 2: .mar 추출 + 가중치 확인

**Files:**
- Create: `/home/ingon/AR_book/sub1_workdir/detector_src/`

- [ ] **Step 1: .mar (zip) 풀기**

```bash
cd /home/ingon/AR_book/sub1_workdir
mkdir -p detector_src && cd detector_src
unzip -o /home/ingon/AR_book/AnimatedDrawings/torchserve/model-store/drawn_humanoid_detector.mar
ls -la
```

Expected: `latest.pth`, `config.py`, `mmdet_handler.py`, `MAR-INF/MANIFEST.json` 보임.

- [ ] **Step 2: config + 가중치 sanity check**

```bash
cd /home/ingon/AR_book/sub1_workdir/detector_src
python << 'EOF'
import torch
ckpt = torch.load('latest.pth', map_location='cpu')
keys = list(ckpt.keys()) if isinstance(ckpt, dict) else []
print(f'top-level keys: {keys[:5]}')
state = ckpt.get('state_dict', ckpt)
print(f'param tensors: {len(state)}')
print(f'first 3 names: {list(state.keys())[:3]}')
EOF
```

Expected: `state_dict` 또는 비슷, ResNet backbone + FPN + RPN + RoI head 의 tensor 들.

- [ ] **Step 3: mmdet 로 모델 로드 + dummy inference**

```bash
cd /home/ingon/AR_book/sub1_workdir/detector_src
python << 'EOF'
from mmdet.apis import init_detector, inference_detector
model = init_detector('config.py', 'latest.pth', device='cpu')
print('model loaded:', type(model).__name__)
EOF
```

Expected: `model loaded: MaskRCNN`.

- [ ] **Step 4: BLOCKED 처리**

만약 mmdet 가 config.py 의 형식을 인식 못하면 (mmdet 2.x vs 3.x format 차이), config 마이그레이션 필요. 그 case 발견 시 STOP + report (사용자가 수동 수정 또는 mmdet 2.x env 별도 셋업).

(no commit)

---

## Task 3: MMDeploy ONNX Export (M1.1, 가장 위험)

**Files:**
- Create: `/home/ingon/AR_book/sub1_workdir/deploy_config.py`
- Create: `/home/ingon/AR_book/sub1_workdir/export_onnx.sh`
- Output: `/home/ingon/AR_book/sub1_workdir/out/end2end.onnx`

- [ ] **Step 1: MMDeploy 의 instance-seg ONNX deploy config 복사**

```bash
cd /home/ingon/AR_book/sub1_workdir
python -c "import mmdeploy, os; print(os.path.dirname(mmdeploy.__file__))" > /tmp/_mmdeploy_path
MMDEPLOY=$(cat /tmp/_mmdeploy_path)
cp $MMDEPLOY/../configs/mmdet/instance-seg/instance-seg_onnxruntime_dynamic.py deploy_config.py 2>/dev/null \
  || find / -name "instance-seg_onnxruntime_dynamic.py" 2>/dev/null | head -1 | xargs -I {} cp {} deploy_config.py
ls -la deploy_config.py
head -30 deploy_config.py
```

Expected: `deploy_config.py` 가 존재. 안 보이면 그 위치 search 가 실패 — 수동 다운로드:
```bash
curl -L -o deploy_config.py https://raw.githubusercontent.com/open-mmlab/mmdeploy/main/configs/mmdet/instance-seg/instance-seg_onnxruntime_dynamic.py
```

- [ ] **Step 2: export_onnx.sh 작성**

```bash
cat > /home/ingon/AR_book/sub1_workdir/export_onnx.sh <<'EOF'
#!/bin/bash
set -e
cd /home/ingon/AR_book/sub1_workdir

# MMDeploy deploy tool path
MMDEPLOY_DIR=$(python -c "import mmdeploy, os; print(os.path.dirname(mmdeploy.__file__))")
DEPLOY_TOOL="$MMDEPLOY_DIR/../tools/deploy.py"

if [ ! -f "$DEPLOY_TOOL" ]; then
    # fallback to pip-installed location
    DEPLOY_TOOL=$(python -c "import mmdeploy, os; print(os.path.join(os.path.dirname(mmdeploy.__file__), '..', 'tools', 'deploy.py'))")
fi
echo "Using deploy tool: $DEPLOY_TOOL"

python "$DEPLOY_TOOL" \
    deploy_config.py \
    detector_src/config.py \
    detector_src/latest.pth \
    sample_input.jpg \
    --work-dir ./out \
    --device cpu \
    --dump-info
EOF
chmod +x /home/ingon/AR_book/sub1_workdir/export_onnx.sh
```

- [ ] **Step 3: Export 실행 (long-running, expect 5-15min)**

```bash
cd /home/ingon/AR_book/sub1_workdir
./export_onnx.sh 2>&1 | tee export.log
```

Expected: 끝에 `Successfully exported ONNX model: ./out/end2end.onnx` 또는 비슷.

가능한 실패:
- `Unsupported op: RoIAlign` → STOP + report. RoI Align ONNX opset 호환성 문제. Mitigation: deploy_config.py 에 `onnx_config = dict(opset_version=16)` 추가하고 재시도.
- `CUDA not available` 경고 → 무시 (CPU mode 의도)
- 다른 op 미지원 → STOP + report. 정확한 op 이름 + 위치 알려줌.

- [ ] **Step 4: 산출물 확인**

```bash
ls -la /home/ingon/AR_book/sub1_workdir/out/
du -sh /home/ingon/AR_book/sub1_workdir/out/end2end.onnx
```

Expected:
- `end2end.onnx` 파일 존재
- 크기 약 100-300 MB (ResNet50 + FPN + RPN + RoI heads, FP32)

- [ ] **Step 5: STOP 게이트 — 사용자에게 보고**

M1.1 의 가장 큰 위험 통과 확인. 만약 실패 → 옵션 [B] (YOLO retrain) 로 spec 변경 필요 — Sub-1 plan 폐기 + 새 brainstorm.

성공 시: 보고 후 자동 진행.

(no commit on PC side; 모델 파일은 갤탭으로 옮길 때만 git tracking)

---

## Task 4: ONNX inference 검증 (M1.2)

**Files:**
- Create: `/home/ingon/AR_book/sub1_workdir/verify_onnx.py`

- [ ] **Step 1: 검증 script 작성**

`/home/ingon/AR_book/sub1_workdir/verify_onnx.py`:

```python
"""Compare ONNX inference vs original PyTorch (mmdet) output on the same sample."""
import sys
from pathlib import Path
import numpy as np
import cv2
import onnxruntime as ort
import torch

WORKDIR = Path('/home/ingon/AR_book/sub1_workdir')
ONNX_PATH = WORKDIR / 'out' / 'end2end.onnx'
SAMPLE_PATH = WORKDIR / 'sample_input.jpg'
CONFIG = WORKDIR / 'detector_src' / 'config.py'
CHECKPOINT = WORKDIR / 'detector_src' / 'latest.pth'

# --- 1) Load sample
img = cv2.imread(str(SAMPLE_PATH))
if img is None:
    print(f'FAIL: sample not loaded from {SAMPLE_PATH}')
    sys.exit(1)
print(f'sample shape: {img.shape}')

# --- 2) PyTorch (mmdet) reference inference
from mmdet.apis import init_detector, inference_detector
pt_model = init_detector(str(CONFIG), str(CHECKPOINT), device='cpu')
pt_result = inference_detector(pt_model, str(SAMPLE_PATH))
pt_bboxes = pt_result.pred_instances.bboxes.cpu().numpy()   # [N, 4]
pt_scores = pt_result.pred_instances.scores.cpu().numpy()   # [N]
pt_masks = pt_result.pred_instances.masks.cpu().numpy()     # [N, H, W] bool
print(f'PyTorch: {len(pt_bboxes)} detections, top score = {pt_scores[0] if len(pt_scores) else "n/a"}')

# --- 3) ONNX inference
sess = ort.InferenceSession(str(ONNX_PATH), providers=['CPUExecutionProvider'])
input_name = sess.get_inputs()[0].name
input_shape = sess.get_inputs()[0].shape
print(f'ONNX input: name={input_name}, shape={input_shape}')

# Preprocess (mmdet style: BGR → keep BGR, mean/std normalize, NHWC → NCHW)
# mmdet 의 transform 을 그대로 따라하는 게 정확. 단순히 cv2 read 후 to_tensor.
# 실제 mmdet 의 normalize: mean=[123.675, 116.28, 103.53], std=[58.395, 57.12, 57.375], BGR→RGB
img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
mean = np.array([123.675, 116.28, 103.53], dtype=np.float32).reshape(1, 1, 3)
std = np.array([58.395, 57.12, 57.375], dtype=np.float32).reshape(1, 1, 3)
img_norm = ((img_rgb.astype(np.float32) - mean) / std).transpose(2, 0, 1)  # CHW
input_tensor = img_norm[np.newaxis, ...]  # [1, 3, H, W]

# MMDeploy 의 ONNX 는 추가로 image_meta 같은 입력 받을 수도. 모델 input 수 확인.
n_inputs = len(sess.get_inputs())
print(f'ONNX has {n_inputs} input(s)')

if n_inputs == 1:
    onnx_out = sess.run(None, {input_name: input_tensor})
else:
    # mmdeploy 의 일부 deploy_config 는 단일 input 만. 만약 여러 input 이면
    # deploy_config 에 dynamic_axes + single input 설정해야.
    print('WARN: ONNX has multiple inputs — adjust deploy_config or this script')
    for i, inp in enumerate(sess.get_inputs()):
        print(f'  input {i}: name={inp.name}, shape={inp.shape}')
    sys.exit(2)

# Output 구조: MMDeploy 의 instance-seg 는 (dets, labels, masks) 또는 비슷
print(f'ONNX outputs: {len(onnx_out)}')
for i, out in enumerate(onnx_out):
    print(f'  output {i}: shape={out.shape}, dtype={out.dtype}')

# 비교 (top-1 detection)
onnx_dets = onnx_out[0]  # 일반적으로 [1, N, 5] (x1,y1,x2,y2,score)
onnx_masks = onnx_out[2] if len(onnx_out) > 2 else None

if onnx_dets.ndim == 3:
    onnx_dets = onnx_dets[0]  # [N, 5]

if len(onnx_dets) == 0:
    print('FAIL: ONNX returned no detections')
    sys.exit(1)

top_pt_score = pt_scores[0] if len(pt_scores) else 0.0
top_onnx_score = onnx_dets[0][4]
bbox_pt = pt_bboxes[0]
bbox_onnx = onnx_dets[0][:4]

print(f'\nTop detection compare:')
print(f'  PyTorch  bbox = {bbox_pt}, score = {top_pt_score:.4f}')
print(f'  ONNX     bbox = {bbox_onnx}, score = {top_onnx_score:.4f}')

bbox_diff = np.abs(bbox_pt - bbox_onnx).max()
score_diff = abs(top_pt_score - top_onnx_score)
print(f'  bbox max diff: {bbox_diff:.2f} px')
print(f'  score diff: {score_diff:.4f}')

ok = bbox_diff < 5.0 and score_diff < 0.05
print(f'\nVerdict: {"PASS" if ok else "FAIL"}')
sys.exit(0 if ok else 1)
```

- [ ] **Step 2: 실행**

```bash
cd /home/ingon/AR_book/sub1_workdir
conda activate mmdeploy_export
python verify_onnx.py 2>&1 | tee verify.log
```

Expected: 끝에 `Verdict: PASS`. bbox diff < 5px, score diff < 0.05.

가능한 실패:
- bbox/score 큰 차이 → ONNX 변환이 numerically 정확 안 함. STOP + report. opset 변경 또는 preprocessing 수정 시도.
- ONNX output structure 예상과 다름 → script 안 print 로 확인 후 사용자에게 알리고 수정.
- mmdet inference_detector 가 새 API 라 호환 안 됨 → mmdet 버전 (3.x) 확인 + API 적용.

- [ ] **Step 3: 결과 기록 + 보고**

verify.log 의 마지막 10 줄 + 모델 크기 사용자에게 보고. M1.2 PASS = 자동 진행, FAIL = STOP.

(no commit)

---

## Task 5: 갤탭 측 ONNX Runtime Mobile 의존성

**Files:**
- Modify: `app/gradle/libs.versions.toml`
- Modify: `app/app/build.gradle.kts`
- Modify: `.gitignore` (assets/models)

- [ ] **Step 1: libs.versions.toml — onnxruntime-android 추가**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/gradle/libs.versions.toml`:

`[versions]` 끝에 추가:
```toml
onnxruntime = "1.17.1"
```

`[libraries]` 끝에 추가:
```toml
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
```

- [ ] **Step 2: build.gradle.kts — implementation 추가**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/build.gradle.kts` 의 `dependencies { }` 안에:
```kotlin
    implementation(libs.onnxruntime.android)
```

- [ ] **Step 3: .gitignore — 모델 파일 ignore**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/.gitignore` 끝에 추가 (없으면):
```
app/app/src/main/assets/models/
```

- [ ] **Step 4: 빌드 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. (onnxruntime AAR 다운로드 처음이라 시간 걸림.)

- [ ] **Step 5: Commit**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add app/gradle/libs.versions.toml app/app/build.gradle.kts .gitignore
git commit -m "build(android): add onnxruntime-android 1.17.1 + ignore assets/models/"
```

No push.

---

## Task 6: PC ONNX 모델 → 갤탭 assets/models/ 복사

**Files:**
- Copy: `out/end2end.onnx` (PC) → `app/app/src/main/assets/models/drawn_humanoid_detector.onnx` (갤탭)

- [ ] **Step 1: 폴더 생성 + 파일 복사**

```bash
mkdir -p /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models
cp /home/ingon/AR_book/sub1_workdir/out/end2end.onnx \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models/drawn_humanoid_detector.onnx
du -sh /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/assets/models/drawn_humanoid_detector.onnx
```

Expected: 파일 존재, 크기 100-300MB.

- [ ] **Step 2: APK 크기 확인 (정보용)**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:assembleDebug
ls -lh app/app/build/outputs/apk/debug/app-debug.apk
```

Expected: APK 크기 ~150-350 MB. 그 이상이면 **STOP + report** — quantization 필요 (FP16 또는 INT8).

(no commit — 모델은 gitignore. APK 빌드만 확인.)

---

## Task 7: ImagePreprocess + 단위 테스트 (TDD)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/ImagePreprocess.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/ImagePreprocessTest.kt`

- [ ] **Step 1: failing test**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/rig/ImagePreprocessTest.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImagePreprocessTest {

    @Test
    fun emits_nchw_float_buffer_with_expected_shape() {
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val (buffer, h, w) = ImagePreprocess.toNchwTensor(bmp)
        assertEquals(24, h)
        assertEquals(32, w)
        // [1, 3, H, W] = 1 * 3 * 24 * 32 = 2304 floats
        assertEquals(1 * 3 * 24 * 32, buffer.remaining())
    }

    @Test
    fun normalizes_mean_and_std_imagenet() {
        // single-color bitmap: RGB(127, 127, 127) → 정규화 후 0 근처
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(127, 127, 127))
        val (buffer, _, _) = ImagePreprocess.toNchwTensor(bmp)
        val arr = FloatArray(buffer.remaining()).also { buffer.get(it) }
        // mean ~123.675 / std ~58.4 → (127 - 123.675) / 58.4 ≈ 0.057
        // each value should be ~0.057 for R channel
        assertEquals(0.057f, arr[0], 0.1f)
    }
}
```

- [ ] **Step 2: Run test — FAIL**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.ImagePreprocessTest"
```

Expected: FAIL — unresolved `ImagePreprocess`.

- [ ] **Step 3: Implementation**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/ImagePreprocess.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object ImagePreprocess {

    // mmdet ImageNet normalization (RGB)
    private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)

    /**
     * Converts a Bitmap to NCHW FloatBuffer in mmdet RGB normalization.
     * Returns (buffer, height, width).
     */
    fun toNchwTensor(bitmap: Bitmap): Triple<FloatBuffer, Int, Int> {
        val h = bitmap.height
        val w = bitmap.width
        val pixels = IntArray(h * w)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val buf = ByteBuffer.allocateDirect(1 * 3 * h * w * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Plane order: R, G, B (CHW)
        for (c in 0..2) {
            val mean = MEAN[c]
            val std = STD[c]
            for (i in 0 until h * w) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> (p shr 16) and 0xFF  // R
                    1 -> (p shr 8) and 0xFF   // G
                    else -> p and 0xFF        // B
                }
                buf.put((v.toFloat() - mean) / std)
            }
        }
        buf.rewind()
        return Triple(buf, h, w)
    }
}
```

- [ ] **Step 4: Run test — PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.ImagePreprocessTest"
```

Expected: 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/ImagePreprocess.kt
git add app/app/src/test/java/com/k3i/stickerbook/rig/ImagePreprocessTest.kt
git commit -m "feat(sub-1): ImagePreprocess Bitmap→NCHW FloatBuffer with mmdet RGB normalize"
```

---

## Task 8: MaskPostprocess + 단위 테스트 (TDD, pure JVM)

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/MaskPostprocess.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/MaskPostprocessTest.kt`

(MMDeploy 의 ONNX output 정확 shape 은 M1.2 시점에 확인됨. 일반적으로 `(dets, labels, masks)` 형식. dets = `[N, 5]` (x1, y1, x2, y2, score). masks = `[N, h, w]` (binary or float)).

- [ ] **Step 1: failing test**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/rig/MaskPostprocessTest.kt`:

```kotlin
package com.k3i.stickerbook.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskPostprocessTest {

    @Test
    fun filters_low_score_detections() {
        // 3 detections: scores 0.9, 0.4, 0.2 — threshold 0.5
        val dets = floatArrayOf(
            10f, 10f, 50f, 50f, 0.9f,
            20f, 20f, 60f, 60f, 0.4f,
            30f, 30f, 70f, 70f, 0.2f,
        )
        val masks = FloatArray(3 * 4 * 4) { 1.0f }
        val result = MaskPostprocess.decode(
            dets = dets,
            masks = masks,
            maskHeight = 4,
            maskWidth = 4,
            scoreThreshold = 0.5f,
        )
        assertEquals(1, result.size)
        assertEquals(0.9f, result[0].score, 0.0001f)
    }

    @Test
    fun preserves_bbox_coordinates() {
        val dets = floatArrayOf(15f, 25f, 105f, 205f, 0.95f)
        val masks = FloatArray(1 * 4 * 4) { 1.0f }
        val result = MaskPostprocess.decode(
            dets = dets, masks = masks,
            maskHeight = 4, maskWidth = 4,
            scoreThreshold = 0.5f,
        )
        val box = result[0].bbox
        assertEquals(15f, box.left, 0.0001f)
        assertEquals(25f, box.top, 0.0001f)
        assertEquals(105f, box.right, 0.0001f)
        assertEquals(205f, box.bottom, 0.0001f)
    }

    @Test
    fun returns_empty_when_no_detections() {
        val result = MaskPostprocess.decode(
            dets = floatArrayOf(),
            masks = floatArrayOf(),
            maskHeight = 0, maskWidth = 0,
            scoreThreshold = 0.5f,
        )
        assertTrue(result.isEmpty())
    }
}
```

- [ ] **Step 2: Run test — FAIL**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.MaskPostprocessTest"
```

Expected: FAIL — unresolved `MaskPostprocess`.

- [ ] **Step 3: Implementation**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/MaskPostprocess.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

data class Detection(
    val bbox: RectF,
    val mask: Bitmap,
    val score: Float,
)

object MaskPostprocess {

    /**
     * Decodes MMDeploy ONNX output (dets, masks) into Detection list.
     * - dets: flat float array, [N, 5] (x1, y1, x2, y2, score)
     * - masks: flat float array, [N, maskHeight, maskWidth] (binary 0/1 or float 0-1)
     */
    fun decode(
        dets: FloatArray,
        masks: FloatArray,
        maskHeight: Int,
        maskWidth: Int,
        scoreThreshold: Float = 0.5f,
    ): List<Detection> {
        if (dets.isEmpty()) return emptyList()
        val n = dets.size / 5
        val results = mutableListOf<Detection>()
        for (i in 0 until n) {
            val off = i * 5
            val score = dets[off + 4]
            if (score < scoreThreshold) continue
            val bbox = RectF(dets[off], dets[off + 1], dets[off + 2], dets[off + 3])
            val mask = if (masks.isNotEmpty() && maskHeight > 0 && maskWidth > 0) {
                decodeMask(masks, i, maskHeight, maskWidth)
            } else {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
            }
            results.add(Detection(bbox, mask, score))
        }
        return results
    }

    private fun decodeMask(masks: FloatArray, index: Int, h: Int, w: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val start = index * h * w
        val pixels = IntArray(h * w)
        for (j in 0 until h * w) {
            val v = masks[start + j]
            val alpha = if (v > 0.5f) 255 else 0
            pixels[j] = Color.argb(alpha, 0, 0, 0)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}
```

- [ ] **Step 4: Run test — PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.MaskPostprocessTest"
```

Expected: 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/MaskPostprocess.kt
git add app/app/src/test/java/com/k3i/stickerbook/rig/MaskPostprocessTest.kt
git commit -m "feat(sub-1): MaskPostprocess decodes ONNX (dets, masks) into Detection list"
```

---

## Task 9: MaskRcnnDetector — ONNX Runtime Mobile wrapper

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/MaskRcnnDetector.kt`

(Detector 자체는 ONNX 의존 + 실 모델 필요 → unit test 어려움. 통합 test 로 Task 11/12 에서 검증.)

- [ ] **Step 1: 구현**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/MaskRcnnDetector.kt`:

```kotlin
package com.k3i.stickerbook.rig

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class MaskRcnnDetector(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions()
        // try NNAPI first; on failure, OrtSession.run still works on CPU
        runCatching { opts.addNnapi() }
            .onFailure { Log.w(TAG, "NNAPI not available, using CPU", it) }
        val bytes = context.assets.open("models/drawn_humanoid_detector.onnx")
            .use { it.readBytes() }
        session = env.createSession(bytes, opts)
        Log.i(TAG, "loaded ONNX, inputs=${session.inputNames}, outputs=${session.outputNames}")
    }

    fun detect(image: Bitmap, scoreThreshold: Float = 0.5f): List<Detection> {
        val (inputBuf, h, w) = ImagePreprocess.toNchwTensor(image)
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        val tensor = OnnxTensor.createTensor(env, inputBuf, shape)
        val inputName = session.inputNames.first()
        val outputs = session.run(mapOf(inputName to tensor))

        // MMDeploy instance-seg 출력 구조: 일반적으로 (dets[N,5], labels[N], masks[N,h,w])
        val dets = (outputs[0].value as Array<*>).let { arr ->
            // dets shape [1, N, 5] 인 경우 unflat
            val batch = arr[0] as Array<FloatArray>
            FloatArray(batch.size * 5).also { flat ->
                batch.forEachIndexed { i, row ->
                    row.copyInto(flat, i * 5)
                }
            }
        }
        val (masksFlat, mh, mw) = decodeMasksTensor(outputs, dets.size / 5)
        outputs.close()
        tensor.close()
        return MaskPostprocess.decode(dets, masksFlat, mh, mw, scoreThreshold)
    }

    private fun decodeMasksTensor(
        outputs: OrtSession.Result,
        nDets: Int,
    ): Triple<FloatArray, Int, Int> {
        // Output index for masks varies — typically index 2 for instance-seg
        if (outputs.size() < 3 || nDets == 0) return Triple(FloatArray(0), 0, 0)
        val raw = outputs[2].value as Array<*>
        val batch = raw[0] as Array<Array<FloatArray>>  // [N, h, w]
        if (batch.isEmpty()) return Triple(FloatArray(0), 0, 0)
        val h = batch[0].size
        val w = batch[0][0].size
        val flat = FloatArray(nDets * h * w)
        for (i in 0 until nDets.coerceAtMost(batch.size)) {
            for (r in 0 until h) {
                batch[i][r].copyInto(flat, i * h * w + r * w)
            }
        }
        return Triple(flat, h, w)
    }

    fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "MaskRcnnDetector"
    }
}
```

(주의: ONNX output 의 실제 shape 은 Task 4 의 verify_onnx.py log 에서 봤을 거. 만약 shape 가 위 추정과 다르면 `decodeMasksTensor` 의 cast 수정 필요. 다음 단계에서 갤탭 inference 시 logcat 으로 확인하고 조정.)

- [ ] **Step 2: 빌드 확인**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. (onnxruntime API 호환 안 되면 import 또는 사용 syntax 조정.)

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/MaskRcnnDetector.kt
git commit -m "feat(sub-1): MaskRcnnDetector ONNX Runtime Mobile wrapper (NNAPI try + CPU fallback)"
```

---

## Task 10: DetectionOnlyRigger — Sub-2 진입 전 임시 Rigger

**Files:**
- Create: `app/app/src/main/java/com/k3i/stickerbook/rig/DetectionOnlyRigger.kt`
- Create: `app/app/src/test/java/com/k3i/stickerbook/rig/DetectionOnlyRiggerTest.kt` (TDD)

- [ ] **Step 1: failing test**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/rig/DetectionOnlyRiggerTest.kt`:

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
class DetectionOnlyRiggerTest {

    @Test
    fun returns_stub_when_no_detection() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        // Inject an empty-result detector (delegate)
        val fakeDetector = object {
            fun detect(image: Bitmap): List<Detection> = emptyList()
        }
        val rigger = DetectionOnlyRigger.withDetector(
            ctx,
            detect = { fakeDetector.detect(it) },
        )
        val r = rigger.rig(bitmap, "dance_1")

        // Falls back to a single static frame (raw bitmap) when no detection
        assertEquals(1, r.frameCount)
        assertTrue(r.framesDir.startsWith("stickers/det_"))
        val root = File(ctx.filesDir, "stickerbook_assets")
        assertTrue(File(root, r.framesDir + "/0001.png").isFile)
    }
}
```

- [ ] **Step 2: Run — FAIL**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.DetectionOnlyRiggerTest"
```

Expected: FAIL — unresolved `DetectionOnlyRigger`.

- [ ] **Step 3: Implementation**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/rig/DetectionOnlyRigger.kt`:

```kotlin
package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

class DetectionOnlyRigger private constructor(
    private val context: Context,
    private val detect: (Bitmap) -> List<Detection>,
) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detect(image)
        val top = detections.maxByOrNull { it.score }

        val stickerId = "det_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        val character = if (top != null) {
            applyMask(image, top.mask, top.bbox)
        } else {
            // no detection — keep the raw capture as-is
            image
        }

        writePng(character, File(framesDir, "0001.png"))
        writePng(character, File(sDir, "texture.png"))
        writePng(character, File(sDir, "animation.gif"))
        writePng(image, File(sDir, "source.png"))

        val rel = "stickers/$stickerId"
        return RigResult(
            framesDir = "$rel/frames",
            fps = 30,
            frameCount = 1,
            width = character.width,
            height = character.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
        )
    }

    private fun applyMask(image: Bitmap, mask: Bitmap, bbox: android.graphics.RectF): Bitmap {
        // 1) Crop the original image to bbox
        val left = bbox.left.toInt().coerceAtLeast(0)
        val top = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(image, left, top, cropW, cropH)

        // 2) Resize mask to cropped size
        val maskScaled = Bitmap.createScaledBitmap(mask, cropW, cropH, true)

        // 3) Apply mask as alpha channel
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
        /**
         * Production factory — uses real MaskRcnnDetector backed by ONNX model.
         */
        fun real(context: Context): DetectionOnlyRigger {
            val detector = MaskRcnnDetector(context)
            return DetectionOnlyRigger(context) { detector.detect(it) }
        }

        /**
         * Test factory — inject custom detection function.
         */
        fun withDetector(
            context: Context,
            detect: (Bitmap) -> List<Detection>,
        ): DetectionOnlyRigger = DetectionOnlyRigger(context, detect)
    }
}
```

- [ ] **Step 4: Run — PASS**

```bash
./run-gradle.sh :app:testDebugUnitTest --tests "com.k3i.stickerbook.rig.DetectionOnlyRiggerTest"
```

Expected: 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/rig/DetectionOnlyRigger.kt
git add app/app/src/test/java/com/k3i/stickerbook/rig/DetectionOnlyRiggerTest.kt
git commit -m "feat(sub-1): DetectionOnlyRigger applies bbox+mask to capture; testable via injected detect()"
```

---

## Task 11: AppNavHost — StubRigger → DetectionOnlyRigger

**Files:**
- Modify: `app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`

- [ ] **Step 1: 단일 라인 교체**

`AppNavHost.kt` 의 "processing" composable 안 `StubRigger(ctx).rig(...)` 호출을 `DetectionOnlyRigger.real(ctx).rig(...)` 로 교체.

찾기:
```kotlin
                    val result = StubRigger(ctx).rig(image, motion)
```

교체:
```kotlin
                    val result = DetectionOnlyRigger.real(ctx).rig(image, motion)
```

import 추가 (StubRigger import 옆 또는 같은 패키지라 자동 — 같은 `com.k3i.stickerbook.rig.*` 이라 사실 import 변경 X. 단 import 가 explicit `import com.k3i.stickerbook.rig.StubRigger` 면 옆에 `import com.k3i.stickerbook.rig.DetectionOnlyRigger` 추가).

- [ ] **Step 2: 빌드**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: install + force-stop + launch**

```bash
ADB="/mnt/c/Users/leesa/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$ADB shell input keyevent 224
$ADB shell svc power stayon true
./run-gradle.sh :app:installDebug
$ADB shell am force-stop com.k3i.stickerbook
$ADB shell am start -n com.k3i.stickerbook/.MainActivity
```

- [ ] **Step 4: 시각 검증 — 사용자가 종이 그림으로 흐름 따라가기**

수동 (subagent 가 screenshot 가능):
1. 앱 → 그리드 → FAB → 카메라
2. 종이 그림을 카메라로 비추기 (또는 갤탭 다른 화면 image)
3. 캡처 → review → 모션 선택 → 만들기
4. Processing (1.5~5s — ONNX inference 느림 가능) → 그리드 복귀
5. 새 sticker 카드 탭 → 상세 화면 → bbox+mask 적용된 캐릭터 (배경 제거) 표시

logcat 확인:
```bash
$ADB logcat -d -t 200 -s "MaskRcnnDetector" "AndroidRuntime:E" | head -50
```

Expected: `loaded ONNX, inputs=..., outputs=...` 로그 + 에러 없음.

가능한 실패:
- `OutOfMemoryError` — 모델 크기 + APK 메모리. 입력 해상도 축소 또는 quantization 필요
- `Unsupported op` — NNAPI 가 일부 op 미지원. CPU fallback 자동 작동해야. 그래도 crash 면 NNAPI 강제 비활성화 (opts.addNnapi() 줄 제거)
- `MaskRcnnDetector: input shape mismatch` — ImagePreprocess 의 출력과 모델 input 호환성 — 디코드 로직 조정
- ONNX output 구조 다름 → MaskRcnnDetector.detect() 의 cast 부분 조정

각 실패는 STOP + report (사용자가 다음 결정).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(sub-1): swap StubRigger for DetectionOnlyRigger (ONNX inference)"
```

---

## Task 12: 최종 회귀 + sub1_results.md

**Files:**
- Create: `app/app/src/test/...` 의 모든 test 회귀
- Create: `docs/sub1_results.md`

- [ ] **Step 1: 전체 unit test 회귀**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
./run-gradle.sh :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: 모두 통과 (이전 Sub-5 7 passed + 1 ignored + 신규 6 = 약 13 passed + 1 ignored).

- [ ] **Step 2: sub1_results.md 작성**

`/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/docs/sub1_results.md`:

```markdown
# Sub-1 결과 — AD Mask R-CNN 모바일 포팅

날짜: <YYYY-MM-DD>
대상: Galaxy Tab S9 FE+ (SM-X610)

## PC 변환 (M1.1, M1.2)

- MMDeploy export: <성공/실패> (시간: __분)
- ONNX 모델 크기: __MB
- bbox 차이 (vs PyTorch): __ px
- score 차이: __
- mask IoU: __

## 갤탭 inference (M1.3)

| 항목 | 측정값 | 목표 | 통과 |
|---|---|---|---|
| Cold start latency | __s | < 5 | ☐ |
| Warm latency | __s | < 2 | ☐ |
| 메모리 peak | __MB | < 500 | ☐ |
| NNAPI 활성화 | 예/아니오 | — | — |
| APK 크기 증가 | __MB | < 250 | ☐ |

## 통합 결과 (M1.4)

- ☑/☐ 캡처 → DetectionOnlyRigger → mask 적용된 캐릭터 sticker 생성
- ☑/☐ 그리드에 새 카드 추가
- ☑/☐ 카드 탭 → 누끼된 캐릭터 표시

## 알려진 이슈

- (예시) ONNX postprocessing 의 output cast 가 모델 변형마다 조정 필요
- (예시) NNAPI delegate 에서 일부 op CPU fallback
- (실제 측정 후 채움)

## Sub-2 진입 조건

- ☐ Sub-1 의 detect() 가 안정 동작
- ☐ bbox + mask 정확도 충분 (수동 검증)
- ☐ AlphaPose mobile 변환 방향 결정 (Sub-2 brainstorm 시점)

## Commits

(git log --oneline 1fc7f33^..HEAD 결과 첨부)
```

- [ ] **Step 3: 실제 측정값 채우기 (수동)**

사용자가 시연 후 표 빈칸 채움.

- [ ] **Step 4: Commit**

```bash
git add docs/sub1_results.md
git commit -m "docs(sub-1): results notes + Sub-2 entry conditions"
```

---

## Self-Review

**1. Spec coverage** — Sub-1 spec sections:
- §1 목표 + 책임 → Task 9 (MaskRcnnDetector) + Task 10 (DetectionOnlyRigger)
- §2 컨텍스트 (MMDet) → Task 2 (.mar 추출)
- §3 변환 path (ONNX → ORT Mobile) → Task 3, 5 (의존성), 7-9
- §4 PoC M1.1~M1.4 → Task 3 (M1.1), Task 4 (M1.2), Task 11 (M1.3, M1.4)
- §5 성공 조건 → Task 4 (검증) + Task 11 (시각 검증) + Task 12 (결과 doc)
- §6 위험 + Fallback → Task 3 step 3, Task 6 step 2, Task 11 step 4 의 BLOCKED 처리
- §7 파일 구조 → File Structure section 그대로
- §8 CharacterRigger interface → Task 10 (DetectionOnlyRigger implements interface)
- §9 Open Q → header 의 기본 결정으로 명시
- §10 Next Action → 본 plan 자체

**2. Placeholder scan** — "TBD/implement later" 없음. Task 12 의 결과 doc 의 표 빈칸은 사용자가 측정 후 채우는 의도 (placeholder 아님).

**3. Type 일관성** — `Detection(bbox, mask, score)` 가 Task 8 정의, Task 9/10 사용. `RigResult` 시그니처 Sub-5 의 것 그대로. `CharacterRigger.rig(Bitmap, String): RigResult` 시그니처 Sub-5 의 것 그대로. `MaskRcnnDetector.detect(Bitmap): List<Detection>` 일관.

문제 없음.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-14-sub1-mask-rcnn-mobile-plan.md`.

두 실행 옵션:

1. **Subagent-Driven (recommended)** — task 마다 fresh subagent + spec/quality review. PC 측 환경 셋업이 위험하니 BLOCKED 발생 시 사용자 확인.
2. **Inline Execution** — 현재 세션에서 task 일괄 + 체크포인트.

어느 쪽?
