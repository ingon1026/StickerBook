# Sub-Quantize 결과 — ONNX INT8 dynamic quantization

날짜: 2026-05-15
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.5.0 (debug)

## MQ.1~MQ.2 — PC quantize + verify

| 모델 | FP32 | INT8 | 감소 |
|---|---|---|---|
| Sub-1 detector | 167 MB | **43 MB** | 74% ↓ |
| Sub-2b pose | 129 MB | **62 MB** | 52% ↓ |
| **합계** | 296 MB | **105 MB** | **65% ↓** |

### 정확도 검증 (PASS)

| 모델 | metric | 측정값 | 기준 | PASS |
|---|---|---|---|---|
| Sub-1 detector | top bbox IoU | **0.987** | > 0.7 | ✅ 압도적 |
| Sub-2b pose | max keypoint diff | **1.41 px** | < 5 px | ✅ |

INT8 dynamic 의 정확도 손실 거의 0. NonZero op (RoI 관련 9개) 만 INT8 미지원으로 FP32 유지.

## MQ.3 — Android: cache invalidation

기존 wrapper 의 `ensureModelOnDisk()` 가 size 무시 → APK 업그레이드 시 새 model 적용 X. 수정: size mismatch 시 재 copy.

```kotlin
val assetSize = context.assets.open("models/$MODEL_NAME").use { it.available().toLong() }
if (target.isFile && target.length() == assetSize) {
    return target.absolutePath
}
// re-copy on mismatch
```

64 unit tests 회귀 PASS.

## MQ.4 — 갤탭 시연

| 항목 | 측정값 | 목표 | PASS |
|---|---|---|---|
| **APK 크기** | **308 MB** | < 250 MB | ⚠️ 목표 미달 (이전 520MB 대비 41% 감소) |
| ONNX 모델 합계 | 105 MB | < 80 MB | ⚠️ NonZero op skip 영향 |
| Mask/keypoint 시각 차이 | 거의 없음 | 없음 | ✅ |
| End-to-end latency | (사용자 평가) | < 30s | (사용자 인정 한계) |

**한계 분석**: APK 308MB 중 ONNX 105MB + MediaPipe 31MB + JSON 2MB = 138MB assets, 나머지 170MB 가 Android libraries (onnxruntime-android native libs, kotlin runtime, compose, MediaPipe Tasks native, AppCompat 등). Android library deps 가 dominant — 추가 압축은 라이브러리 자체 분할 등 큰 별도 작업 필요.

## 알려진 이슈 / Follow-up

- ⚠️ APK 250MB 목표 미달 — Android library deps dominant. 추가 압축은 별도 sub
- ⚠️ Sub-1 의 Mask R-CNN NonZero op (RoI 관련) INT8 미지원 → 일부만 quantize
- ⚠️ MediaPipe Pose (31MB) 도 quantize 가능 (작아서 우선순위 낮음)

## Sub-Quantize commits

```text
d6c5079  docs(quantize): design
2ef93a4  docs(quantize): implementation plan
5821c97  feat(quantize): cache invalidation on size mismatch (for INT8 ONNX swap)
```

(quantize 자체 commit 은 없음 — 모델 파일은 .gitignore 처리. PC workdir 도 git 추적 X.)

## Phase 2 + Sub-Quantize 완료 정리

V2 정식 (갤탭 단독 on-device AD-style animation) 본체 + 첫 follow-up 완료:

| Sub | 상태 | 핵심 |
|---|---|---|
| Sub-5 | ✅ | UI + 카메라 |
| Sub-1 | ✅ | Mask R-CNN ONNX (INT8 43MB) |
| Sub-2 | ⚠️ | MediaPipe (도메인 한계) |
| Sub-2b | ✅ | AD pose ONNX (INT8 62MB) |
| Sub-3 | ✅ | ARAP mesh (Kotlin pure) |
| Sub-4 | ✅ | BVH retarget (PC + JSON) |
| **Sub-Quantize** | ✅ | INT8 dynamic, APK 41% 감소 |

다음 후보:
- **AR overlay + GIF encoding** (사용자 요청 — master plan §1 시나리오)
- Sub-1 mask 정확도 (boundary 짤림 등)
- Sub-3 Delaunay mesh
- 사용자 motion 녹화
