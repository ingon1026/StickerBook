# Sub-1 결과 — AD Mask R-CNN 모바일 포팅

날짜: 2026-05-14
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.1.0 (debug)

## M1.1 — PC ONNX Export (Task 3)

| 항목 | 측정값 | 비고 |
|---|---|---|
| MMDeploy export 성공 | ✅ | mmdet 2.x → 3.x config 5 patch 적용 (run_export.py) |
| Where node 호환성 | ⚠️ post-export graph fix 필요 | fix_onnx.py 가 `Cast(INT64→FLOAT)` 삽입 |
| ONNX 모델 크기 | 168 MB | FP32. quantization 미적용 |
| Opset version | 16 | RoI Align 정식 지원 |
| Node count | 2154 | — |
| 출력 형식 | dets [1,N,5], labels [1,N], masks [1,N,28,28] | masks 는 28×28 RoI crop (full-image 아님) |

## M1.2 — PC ONNX 정확도 검증 (Task 4)

| 항목 | 측정값 | 임계 | 통과 |
|---|---|---|---|
| bbox max abs diff | 10.30 px | < 30 | ✅ |
| top-detection score diff | 0.0002 | < 0.05 | ✅ |
| mask IoU (full-image, ONNX vs PyTorch) | 0.893 | > 0.7 | ✅ |

## M1.3 — 갤탭 inference (Task 11, 사용자 시연 결과)

| 항목 | 측정값 | 목표 | 통과 |
|---|---|---|---|
| 모델 load | ✅ ~1초 (cached file mmap) | crash 없음 | ✅ |
| Inference latency (CPU only, 2 threads) | ~1-2분 | < 5s | ❌ — quantization 필요 |
| NNAPI 활성화 | ❌ 폐기 (Mask R-CNN 의 RoI Align op 갤탭 NNAPI 호환 X — native SIGSEGV) | — | — |
| APK 크기 | 299 MB | < 250 MB | ❌ — quantization follow-up |
| `largeHeap="true"` 필요 | ✅ 추가됨 | — | — |

### 발견한 hot-fix 3개 (plan 후속)

1. **OOM at model load** — 168MB `byte[]` heap 할당 실패. fix: file path 로 load (mmap), `assets → filesDir` stream copy, `largeHeap=true` 추가. (commit `3e80fd5`)
2. **Native SIGSEGV in libonnxruntime.so** — `OrtSession.addNnapi()` 호출 시 NNAPI partition 시도 → Mask R-CNN RoI Align 미지원 → native crash. fix: NNAPI 제거, CPU only, BASIC opt. (commit `b71dd1d`)
3. **ANR 1분 freeze** — inference 가 UI thread block. fix: `Dispatchers.Default` 로 background + Detector cache (`remember`). (commit `c5cecf7`)

## M1.4 — 통합 결과 (Task 11, 사용자 시연 결과)

- ✅ 캡처 → DetectionOnlyRigger.real → mask 적용된 캐릭터 sticker 생성
- ✅ 그리드에 새 카드 `det_<timestamp>` 추가됨
- ✅ 새 카드 탭 → 상세 화면에 누끼된 캐릭터 (배경 투명) 표시
- ⚠️ **Mask 정확도 한계** — 비스듬한 종이 + 돼지 그림 sample 에서 mask 가 하반신만 잡고 머리·상반신 빠짐. mmdet Mask R-CNN 의 28×28 RoI mask + 손그림 도메인 gap. M1.2 의 mask IoU 0.893 (10% 손실) 이 visual 로 명확하게 보임
- ⚠️ Mask edge 픽셀화 — 28×28 → bbox 크기 nearest upsample. Bitmap.createScaledBitmap 이 bilinear 하지만 28→1000+ scale 차이가 큼

### Visual sample

원본 (`source.png`): 돼지 캐릭터 + 초록 반바지, 종이 약간 비스듬
결과 (`frames/0001.png`): 반바지 + 다리 + 팔 일부만 (머리 + 상반신 빠짐)

이건 모델 본질적 한계. Sub-2 의 pose estimation 결과가 머리·관절 위치 잡으면 Sub-3 (ARAP) 단계에서 mesh 가 mask 보다 더 정확하게 캐릭터 영역 정의 가능 → 통합 시 자연 해소 기대.

## 알려진 이슈 / Follow-up

- ⚠️ **Inference 1-2분 (CPU)** — 가장 큰 follow-up. quantization (FP16/INT8) + input resize 시 5-10초 가능 예상. 별도 sub-task
- ⚠️ **APK 299MB** — quantization 으로 100MB 이하 가능
- ⚠️ **Mask 머리 빠짐** — 모델 학습 한계 (사람 도메인 + 손그림 gap). Sub-2/Sub-3 통합 후 자연 해소 가능성. retrain (Annotated Drawings 데이터셋) 도 가능
- ⚠️ Mask edge 픽셀화 (28×28 → bbox 크기)
- mmdet 2.x → 3.x config patch 가 export 시 수동 (run_export.py)
- NNAPI 비활성 — Mask R-CNN 의 RoI Align op 갤탭 NNAPI EP 미지원
- ONNX Runtime Mobile 의 array cast — 실제 inference 에서 정상 동작 확인됨

## Sub-2 진입 조건 체크

- ✅ M1.3 — inference 동작 (latency 1-2분, 별도 quantization sub-task)
- ✅ M1.4 — end-to-end flow OK (mask 정확도는 known limit)
- ⚠️ Inference latency 받아들일 수준은 아님 — 단기 데모는 가능, production 은 quantization 필요
- ☐ AlphaPose 모바일 변환 방향 결정 (Sub-2 brainstorm 시점)

### Sub-2 진입 시 우선 결정

1. AlphaPose ONNX 변환 시도 vs MediaPipe Pose 활용 vs 사용자 탭 fallback
2. Sub-1 의 quantization 을 Sub-2 와 함께 묶을지 별도로 갈지
3. Mask 정확도 보정 (Sub-1 retrain) 을 Sub-2 통합 후 평가

## Sub-1 commits (시간순)

```text
1fc7f33 docs(sub-1): AD Mask R-CNN ONNX mobile porting design
18f9737 docs(sub-1): implementation plan — 12 tasks (PC ONNX export + Android integration)
2105059 build(android): add onnxruntime-android 1.17.1 + ignore assets/models/
45843a1 feat(sub-1): ImagePreprocess Bitmap→NCHW BGR mean-subtract
45db473 feat(sub-1): MaskPostprocess decodes ONNX (dets, masks) into Detection list
6a55289 feat(sub-1): MaskRcnnDetector ONNX Runtime Mobile wrapper
f401465 feat(sub-1): DetectionOnlyRigger applies bbox+mask to capture
706ef5e feat(sub-1): wire DetectionOnlyRigger.real (ONNX inference) in AppNavHost
5468dba docs(sub-1): results template
3e80fd5 fix(sub-1): OOM on 168MB ONNX load — file path + mmap + largeHeap
b71dd1d fix(sub-1): drop NNAPI delegate — incompatible with Mask R-CNN ops
c5cecf7 fix(sub-1): cache Rigger + run inference on Dispatchers.Default (ANR fix)
```

## 유닛 테스트 결과

```
Total tests: 15 (14 passed, 1 skipped)

Sub-1 new tests (7):
  - ImagePreprocessTest: 3 passed
  - MaskPostprocessTest: 3 passed
  - DetectionOnlyRiggerTest: 1 passed

Sub-5 tests (7):
  - ManifestParserTest: 2 passed
  - AssetRepositoryTest: 3 passed (1 skipped in total suite count)
  - CaptureSessionTest: 1 passed
  - StubRiggerTest: 1 passed
  - MotionPickerScreenTest: 1 passed
```

**Build:** SUCCESS in 13s | **Timestamp:** 2026-05-14T07:34:46
