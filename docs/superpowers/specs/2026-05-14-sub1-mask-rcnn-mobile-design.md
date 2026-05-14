# Sub-1 Design — AD Mask R-CNN 모바일 포팅 (그림 detection + mask)

> 작성일: 2026-05-14
> Phase 2 의 두 번째 sub-project (Sub-5 완료 후)
> 마스터 spec: `2026-05-14-phase2-master-design.md`
> 사용자 결정: 옵션 [A] = AD 의 Mask R-CNN 통째 ONNX 변환 후 ONNX Runtime Mobile 통합

---

## 1. 목표

PC 의 AnimatedDrawings detector (Mask R-CNN, MMDetection framework, ResNet-50 backbone, ~350MB .pth) 를 갤럭시 태블릿 on-device 에서 실행. 한 번 inference 로 **bbox + segmentation mask** 둘 다 출력.

### 이번 sub 의 책임

- 모바일 inference 가능한 ONNX 모델 산출
- 갤탭 측 Kotlin wrapper: `Bitmap` → `(List<BBox>, List<Mask>)`
- Sub-5 의 `CharacterRigger.rig()` 의 detection 부분 채움 (mask 활용은 Sub-3 ARAP 가 받음)
- 디버그 화면: 캡처 이미지 위에 bbox + mask overlay 표시 (선택)

### 비책임

- pose estimation (Sub-2)
- ARAP variation (Sub-3)
- BVH retarget (Sub-4)
- Annotated Drawings 데이터셋 retrain — 일단 그대로 변환만. M1 의 정확도 측정 후 별도 결정
- 라이브 카메라 streaming inference — 캡처 시 1회만 inference

---

## 2. 컨텍스트 — AD detector 의 실체

이전 brainstorm 시 "Detectron2" 라고 적었지만 실제는 **MMDetection (mmdet)**:

```yaml
model:
  type: MaskRCNN
  backbone:
    type: ResNet
    depth: 50
    frozen_stages: 1
    style: caffe
    init: open-mmlab://detectron2/resnet50_caffe   # 가중치만 detectron2 의 ResNet50 사용
  neck:
    type: FPN
  rpn_head: RPNHead
  roi_head:
    type: StandardRoIHead
    bbox_head: Shared2FCBBoxHead (num_classes=1, humanoid 만)
    mask_head: FCNMaskHead (output 14x14)
  ops: RoIAlign (bbox + mask)
```

원본: `/home/ingon/AR_book/AnimatedDrawings/torchserve/model-store/drawn_humanoid_detector.mar`
포맷: TorchServe model archive (zip). 내부: `latest.pth` (가중치) + `config.py` (모델 정의) + `mmdet_handler.py` (TorchServe handler).

핵심 도전: **RoI Align op 의 모바일 호환성**. ONNX Runtime 은 `RoIAlign` op 공식 지원 (opset 16+) — 변환 가능성 높음. MMDeploy 의 사용자 정의 export 도 보조.

---

## 3. 변환 path

**선택: PyTorch (.pth) → ONNX → ONNX Runtime Mobile (Android)**

- PyTorch → ONNX: MMDeploy 의 `tools/torch2onnx.py` (또는 mmdet 의 `tools/deployment/pytorch2onnx.py`)
- ONNX inference verification: Python `onnxruntime` (CPU)
- 모바일: ONNX Runtime Mobile (`onnxruntime-android` Gradle 의존성, NNAPI execution provider 옵션)

**TFLite path 거부 사유**: ONNX → TFLite 변환 (onnx-tf 또는 onnx2tflite) 의 RoI Align 호환성 검증 추가 위험. ONNX 가 더 직접적.

---

## 4. 단계적 PoC (위험 검증 우선)

### M1.1 — PC 측: .mar 추출 → MMDeploy ONNX export

**환경**: AD 의 conda env (`animated_drawings`, Python 3.8) 또는 새 env 에 `mmdeploy[onnxruntime]` 설치.

```bash
# 1. .mar 추출 (zip 풀기)
unzip drawn_humanoid_detector.mar -d ./detector_src
# → latest.pth + config.py + mmdet_handler.py

# 2. MMDeploy 변환 (Mask R-CNN deploy config 사용)
python tools/deploy.py \
    configs/mmdet/instance-seg/instance-seg_onnxruntime_dynamic.py \
    config.py \
    latest.pth \
    sample_input.jpg \
    --work-dir ./out --device cpu
# → out/end2end.onnx
```

**위험 응대**:
- MMDeploy 설치 실패 (Python 3.8 호환성) → mmdet 의 직접 export 시도 (`tools/deployment/pytorch2onnx.py`)
- RoI Align ONNX op 미지원 → MMDeploy 의 `--opset-version 16` (또는 17) 사용. RoIAlign 은 opset 16 정식 op
- 변환 자체 실패 → fallback [B] (YOLOv8 retrain) — brainstorm 옵션 변경 + 새 spec

**산출**: `models/drawn_humanoid_detector.onnx` (PC, ~100-200 MB)

### M1.2 — PC 측: ONNX inference 정확도 검증

```python
# Python script
import onnxruntime as ort, numpy as np, torch

# 1. PyTorch reference
ref_model = load_mmdet_model('config.py', 'latest.pth')
ref_out = ref_model(sample_input)

# 2. ONNX inference
sess = ort.InferenceSession('end2end.onnx')
onnx_out = sess.run(None, {'input': preprocess(sample_input)})

# 3. Compare (bbox, score, mask)
assert np.allclose(ref_bbox, onnx_bbox, atol=2.0)  # 픽셀 단위
assert np.allclose(ref_score, onnx_score, atol=0.01)
assert iou(ref_mask, onnx_mask) > 0.95
```

**측정 metric**:
- bbox 좌표 차이 (픽셀)
- score 차이
- mask IoU
- inference time (PC CPU)

### M1.3 — 갤탭 측: ONNX Runtime Mobile 통합

**Gradle 의존성**:
```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
```

**Kotlin wrapper** (`rig/MaskRcnnDetector.kt`):
```kotlin
class MaskRcnnDetector(context: Context) {
    private val session: OrtSession

    init {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            // try NNAPI first; fall back to CPU
            try { addNnapi() } catch (_: Throwable) {}
        }
        session = env.createSession(loadModelBytes(context), opts)
    }

    data class Detection(val bbox: RectF, val mask: Bitmap, val score: Float)

    fun detect(image: Bitmap): List<Detection> {
        val input = preprocess(image)  // resize, normalize, ToTensor
        val results = session.run(mapOf("input" to input))
        return postprocess(results)  // NMS, threshold, decode masks
    }
}
```

**갤탭에서 검증**:
- 캡처된 sample 이미지 → `detect()` → bbox + mask 결과
- Logcat 으로 inference latency 측정
- Android Studio Profiler 로 메모리/CPU
- 디버그 화면 (선택): bbox overlay + mask 반투명 표시

### M1.4 — Sub-5 의 CharacterRigger 부분 통합

Sub-2 (pose) 가 완료될 때 둘이 합쳐 `AdRigger : CharacterRigger` 가 됨. Sub-1 만으로는 통합 불가.

**중간 단계 (Sub-1 만)**:
- `StubRigger` 의 placeholder 1-frame sticker 대신, **bbox + mask 가 적용된 캡처 이미지** 를 frames 로 사용 (정적, 1 frame, 누끼된 character).
- 사용자가 Sub-1 결과를 갤탭에서 시각 확인 가능 (mask 가 적용된 모양).

`StubRigger` → `DetectionOnlyRigger` (임시 클래스, Sub-2 진입 전):
```kotlin
class DetectionOnlyRigger(context: Context) : CharacterRigger {
    private val detector = MaskRcnnDetector(context)

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detector.detect(image)
        val top = detections.maxByOrNull { it.score } ?: error("no humanoid detected")
        val character = applyMask(image, top.mask)  // cropped + mask 적용
        // ... write to internal storage (StubRigger 와 동일 패턴)
        return RigResult(...)
    }
}
```

`AppNavHost` 의 `StubRigger(ctx)` → `DetectionOnlyRigger(ctx)` 로 교체.

---

## 5. 성공 조건

| 단계 | 기준 |
|---|---|
| M1.1 | end2end.onnx 파일 생성, MMDeploy export 무에러 |
| M1.2 | bbox 좌표 차이 < 2px, score 차이 < 0.01, mask IoU > 0.95 (PyTorch 와 비교) |
| M1.3 | 갤탭 inference 동작, latency < 5s (cold), < 2s (warm), 메모리 peak < 500MB |
| M1.4 | DetectionOnlyRigger 가 갤탭에서 캡처된 그림으로 mask 적용 1-frame sticker 생성. UI 검증 통과 |
| 추가 | 모델 + ONNX RT 추가 deps APK 증가 < 250MB |

---

## 6. 위험 + Fallback

| Risk | 영향 | Mitigation |
|---|---|---|
| RoI Align ONNX 변환 실패 | M1.1 좌초 | opset 16+ 명시 / MMDeploy custom op / **fallback: YOLO retrain (Sub-1 spec 재작성)** |
| ONNX 모델 너무 큼 (>300MB) | APK 거부 | quantization (FP32 → FP16 또는 INT8 dynamic). MMDeploy 가 quantization 지원 |
| 갤탭 latency > 5s | UX 저하 | (1) 입력 해상도 축소 (1024→640) (2) NNAPI 가속 (3) 모델 quantization (4) GPU delegate |
| NNAPI 미지원 op | crash / fallback to CPU | 기본 CPU + try NNAPI then catch. CPU 만 으로도 동작해야 |
| ONNX Runtime Mobile APK 크기 ~30MB 증가 | 무시 가능 | 그대로 진행 |
| 모델 파일 APK 번들 | 빌드 시간 증가 | 처음엔 assets/ 번들. 200MB 넘으면 DynamicFeature 또는 on-demand download |
| 정확도가 손그림에 부족 | 갤탭 시연 실패 | retrain 별도 결정 (Annotated Drawings 데이터셋 으로) |

---

## 7. 파일 구조

### PC 측 (작업 디렉토리)

```
AnimatedDrawings/                  # AD 원본 repo (이미 있음)
/home/ingon/AR_book/sub1_workdir/  # 새로 만들 작업 폴더 (gitignore)
├── detector_src/                  # .mar 추출 결과
│   ├── latest.pth
│   ├── config.py
│   └── mmdet_handler.py
├── out/                            # MMDeploy 출력
│   └── end2end.onnx
├── verify_onnx.py                  # M1.2 검증 스크립트
└── sample_input.jpg                # 테스트 이미지
```

PC 측은 별도 작업 디렉토리. drawing-to-2.5d-repo 와 분리.

### 갤탭 측 (stickerbook_android_porting/)

```
app/app/src/main/
├── assets/models/
│   └── drawn_humanoid_detector.onnx   # PC 에서 생성한 ONNX (gitignore)
└── java/com/k3i/stickerbook/
    ├── rig/
    │   ├── MaskRcnnDetector.kt          # 신규 — ONNX inference wrapper
    │   ├── DetectionOnlyRigger.kt       # 신규 — Sub-2 진입 전 임시 CharacterRigger 구현
    │   └── (CharacterRigger.kt, RigResult.kt, StubRigger.kt 는 Sub-5 의 그대로)
    └── ui/nav/AppNavHost.kt              # 수정 — StubRigger → DetectionOnlyRigger
```

신규 테스트:
```
app/app/src/test/java/com/k3i/stickerbook/rig/
└── MaskRcnnDetectorTest.kt            # Robolectric (작은 input 으로 inference smoke)
```

---

## 8. CharacterRigger interface 사용

Sub-5 의 interface (commit 6933296) 그대로 사용. Sub-1 의 `DetectionOnlyRigger` 가 이를 구현.

```kotlin
// 변경 없음
interface CharacterRigger {
    suspend fun rig(image: Bitmap, motion: String): RigResult
}

// 변경 없음
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

DetectionOnlyRigger 는 RigResult 의 frames/ 안에 **mask 가 적용된 정적 1-frame** 만 저장. 모션 적용 X (Sub-3, Sub-4 가 추가).

---

## 9. Open Questions

1. **모델 quantization 시점** — M1.1 에서 FP32 그대로 vs FP16 부터 시작?
   - 추천: FP32 먼저 정확도 검증. 그 후 FP16 quantize → latency 비교

2. **Sample input 이미지** — M1.2 검증용 어디서?
   - PC 측 `stickerbook/assets/captures/` 의 source.png 들 사용 (이미 3 sample 있음)

3. **NNAPI vs CPU only** — M1.3 에서 NNAPI 시도?
   - 추천: try-catch 패턴. NNAPI 안 되면 CPU fallback. 둘 다 측정

4. **모델 파일 배포** — APK assets 번들 vs runtime download?
   - 추천: 일단 APK 번들 (개발 빠름). 200MB 넘으면 검토. 사용자 deploy 환경 (USB push) 라 APK 크기 우려 적음

5. **디버그 시각화 화면** — Sub-1 만의 결과 보는 별도 화면 추가?
   - 추천: 안 함. M1.4 통합 결과만 시각 확인. 시간 절약

6. **모델 변환 환경 (conda)** — AD 의 animated_drawings env (Py 3.8) 에서 MMDeploy 설치 vs 새 env?
   - 추천: 새 env (`mmdeploy_export`, Py 3.10). 학습 환경 안 건드림

---

## 10. Next Action

이 spec → `writing-plans` 스킬로 단계별 implementation plan 작성 → 사용자 review → subagent-driven-development 로 실행.

추정 task 수: 8-12 (M1.1 ~ M1.4 각각 + 빌드/통합/테스트).

예상 작업 시간: **5-10 일** (대부분 M1.1 의 ONNX export + 검증 + 갤탭 통합 디버깅. 변환이 첫 시도에 잘 되면 빠름).
