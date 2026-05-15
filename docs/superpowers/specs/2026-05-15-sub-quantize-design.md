# Sub-Quantize Design — ONNX INT8 dynamic quantization

> 작성일: 2026-05-15
> Phase 2 본체 완료 후 첫 follow-up sub-task
> 마스터 spec: `2026-05-14-phase2-master-design.md`
> 선행: `2026-05-14-sub1-mask-rcnn-mobile-design.md`, `2026-05-15-sub2b-ad-pose-onnx-design.md`
> 사용자 결정: **INT8 dynamic** quantization (calibration dataset 불필요)

---

## 1. 목표 / 책임 / 비책임

### 책임

- Sub-1 ONNX (176MB) + Sub-2b ONNX (136MB) 를 INT8 dynamic 으로 quantize
- 산출물:
  - `drawn_humanoid_detector_int8.onnx` (~44MB)
  - `ad_pose_int8.onnx` (~34MB)
- PC 정확도 검증: FP32 와 INT8 의 동일 입력 결과 비교
- Android assets/models/ 의 ONNX 파일 swap
- Android wrapper (MaskRcnnDetector, AdPoseEstimator) 의 cache invalidation — 새 model 과 size mismatch 시 재 copy
- 갤탭 시연: latency 측정 + 시각 결과 비교 (이전과 큰 차이 없는지)

### 비책임

- Static INT8 (calibration dataset) — dynamic 으로 부족하면 별도 follow-up
- MediaPipe Pose Tasks (이미 단독 30MB, 작음)
- 모델 architecture 변경 (단순 quantize 만)
- Quantize-aware training (학습 단계 재방문, scope 외)
- ARM Neon SIMD optimization (onnxruntime 가 알아서)

### 최소 PASS 기준

1. APK 520MB → < 250MB (목표 200MB)
2. End-to-end latency 1-2분 → < 30s
3. Sub-1 detector bbox IoU > 0.7 with FP32
4. Sub-2b pose keypoint diff < 5px with FP32
5. 갤탭 시연에서 motion 결과의 시각 차이 없음 (사용자 평가)

---

## 2. 변환 path

PC env: `mmdeploy_export` conda env (`onnxruntime.quantization` 가용 검증됨).

### quantize.py

```python
# /home/ingon/AR_book/sub_quantize_workdir/quantize.py
from onnxruntime.quantization import quantize_dynamic, QuantType

# Sub-1 detector
quantize_dynamic(
    model_input='/home/ingon/AR_book/sub1_workdir/out/end2end_fixed.onnx',
    model_output='out/drawn_humanoid_detector_int8.onnx',
    weight_type=QuantType.QUInt8,
)

# Sub-2b pose
quantize_dynamic(
    model_input='/home/ingon/AR_book/sub2b_workdir/out/end2end.onnx',
    model_output='out/ad_pose_int8.onnx',
    weight_type=QuantType.QUInt8,
)
```

`QuantType.QUInt8` = unsigned 8-bit (대부분의 backend 친화). Activation 도 자동 INT8.

### verify_quantize.py

동일 입력 → FP32 vs INT8 결과 비교:

```python
import onnxruntime as ort
import numpy as np

# Sub-1: bbox IoU
fp32_sess = ort.InferenceSession('sub1_workdir/out/end2end_fixed.onnx')
int8_sess = ort.InferenceSession('out/drawn_humanoid_detector_int8.onnx')

sample = preprocess('/home/ingon/AR_book/sub1_workdir/sample_input.jpg')
fp32_out = fp32_sess.run(None, {'input': sample})
int8_out = int8_sess.run(None, {'input': sample})

# Compare top bbox
fp32_bbox = fp32_out[0][0, 0, :4]  # [x1, y1, x2, y2] (예시)
int8_bbox = int8_out[0][0, 0, :4]
iou = compute_iou(fp32_bbox, int8_bbox)
assert iou > 0.7, f"IoU too low: {iou}"

# Sub-2b: per-keypoint L2 distance
fp32_sess2 = ort.InferenceSession('sub2b_workdir/out/end2end.onnx')
int8_sess2 = ort.InferenceSession('out/ad_pose_int8.onnx')

sample2 = preprocess_pose('/home/ingon/AR_book/sub2b_workdir/sample_input.jpg')
fp32_pose = decode_heatmap(fp32_sess2.run(None, {'input': sample2})[0])
int8_pose = decode_heatmap(int8_sess2.run(None, {'input': sample2})[0])

for k in range(17):
    diff = np.linalg.norm(fp32_pose[k] - int8_pose[k])
    assert diff < 5.0, f"keypoint {k} diff {diff:.2f}px"

print("Quantize PASS")
```

---

## 3. 모듈 분해 + 파일 변경

### PC 측 (`/home/ingon/AR_book/sub_quantize_workdir/`, git 추적 X)

| 파일 | 종류 |
|---|---|
| `quantize.py` | 신규 |
| `verify_quantize.py` | 신규 |
| `out/drawn_humanoid_detector_int8.onnx` | 산출 (~44MB) |
| `out/ad_pose_int8.onnx` | 산출 (~34MB) |

### Android 측

| 파일 | 종류 | 비고 |
|---|---|---|
| `app/app/src/main/assets/models/drawn_humanoid_detector.onnx` | replace | 176MB → 44MB (같은 이름 유지) |
| `app/app/src/main/assets/models/ad_pose.onnx` | replace | 136MB → 34MB (같은 이름 유지) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/MaskRcnnDetector.kt` | 수정 | `ensureModelOnDisk()` 에 size mismatch 체크 추가 |
| `app/app/src/main/java/com/k3i/stickerbook/rig/AdPoseEstimator.kt` | 수정 | 동일 |

### Cache invalidation logic

기존 wrapper 의 `ensureModelOnDisk()`:
```kotlin
private fun ensureModelOnDisk(): String {
    val target = File(context.filesDir, "models/$MODEL_NAME")
    if (target.isFile && target.length() > 0) {
        return target.absolutePath  // ← 기존 cache 그대로 사용
    }
    // copy from assets...
}
```

문제: APK 업그레이드 후 새 model (44MB) 와 기존 cache (176MB) mismatch → 새 model 적용 안 됨.

수정:
```kotlin
private fun ensureModelOnDisk(): String {
    val target = File(context.filesDir, "models/$MODEL_NAME")
    val assetSize = context.assets.openFd("models/$MODEL_NAME").use { it.length }
    if (target.isFile && target.length() == assetSize) {
        return target.absolutePath  // size 일치하면 cache 사용
    }
    // size mismatch 또는 미존재 → 재 copy
    target.parentFile?.mkdirs()
    context.assets.open("models/$MODEL_NAME").use { input ->
        target.outputStream().use { output -> input.copyTo(output, 1024*1024) }
    }
    return target.absolutePath
}
```

(또는 `BuildConfig.VERSION_CODE` 기반 cache invalidation 도 가능. Size 비교가 단순 + 효과적.)

---

## 4. 단계적 PoC (MQ.1 ~ MQ.5)

| M | 내용 | 검증 |
|---|---|---|
| **MQ.1** | PC: sub_quantize_workdir + quantize.py + 두 모델 변환 | INT8 ONNX 생성, 크기 ~1/4 |
| **MQ.2** | PC: verify_quantize.py 정확도 검증 | Sub-1 bbox IoU > 0.7, Sub-2b keypoint diff < 5px |
| **MQ.3** | Android: assets swap + cache invalidation 로직 추가 | unit test 회귀 PASS (64 그대로) |
| **MQ.4** | APK build + 갤탭 install + 시연 | APK < 250MB, latency < 30s, motion 결과 시각 차이 없음 |
| **MQ.5** | 결과 doc + memory | docs/sub_quantize_results.md |

---

## 5. 리스크 매트릭스

| # | 리스크 | 영향 | 대응 |
|---|---|---|---|
| R1 | Mask R-CNN 일부 op (RoIAlign 등) INT8 미지원 → 자동 skip | 효과 부분적 | `quantize_dynamic` 자동 처리. 효과 작아도 OK |
| R2 | 정확도 손실 너무 큼 (IoU < 0.5 등) | mask/keypoint 어긋남 | static INT8 (calibration) follow-up 또는 FP16 fallback |
| R3 | 갤탭 latency 변화 없음 | latency 목표 미달 | 일부 op 미적용 가능. logcat 확인. ARM Neon SIMD 가 자동 적용되는지 |
| R4 | 갤탭 의 기존 model cache 가 새 model 과 mismatch → load error | crash | ensureModelOnDisk size 비교 후 재 copy |
| R5 | ONNX 의 dynamic axis (batch=1) + INT8 호환성 문제 | model load fail | quantize 시 옵션 확인. fail 시 model export 단계 재방문 |
| R6 | int8 ONNX runtime 의 일부 backend 호환성 | model load 시 warning | NNAPI off 유지 (Sub-1 lesson). CPU only |

---

## 6. 검증

### Unit (Android)

기존 64 tests 회귀 PASS. 새 unit test 없음 (quantize 는 PC 작업 + ONNX swap).

### PC 검증 (MQ.2)

```bash
python verify_quantize.py 2>&1 | tail -5
# Expected:
# Sub-1 IoU: 0.85+
# Sub-2b max keypoint diff: 2.3 px
# Quantize PASS
```

### 갤탭 시연 (MQ.4)

- 같은 그림 캡처 → 같은 motion 선택 → 결과 sticker 와 이전 sticker 비교
- latency 측정: 캡처 → spinner 사라짐 시간
- logcat 에서 `MaskRcnnDetector: loaded ONNX, inputs=[input], outputs=[dets, labels, masks]` 같은 메시지 확인
- 시각 결과 변화 검증 (사용자 평가)

---

## 7. Sub-Quantize 진입 조건 (이미 충족)

- ✅ Sub-1 + Sub-2b ONNX 모델 존재
- ✅ `mmdeploy_export` conda env + onnxruntime.quantization 가용
- ✅ MaskRcnnDetector + AdPoseEstimator wrapper 존재

## 8. Sub-Quantize 후 영향

- **APK 520MB → ~250MB** — Play Store 제출 가능 (보통 200MB+)
- **Latency 1-2분 → ~30s** — 사용자 경험 큰 개선
- **남은 follow-up**:
  - 추가 손실 시 static INT8 (calibration) 또는 model retraining
  - MediaPipe 의 30MB 도 quantize 가능 (작아서 우선순위 낮음)
  - 모델 자체 재학습 (정확도 + 효율 동시 개선) — 별도 sub
