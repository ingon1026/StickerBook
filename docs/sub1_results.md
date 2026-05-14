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

## M1.3 — 갤탭 inference (Task 11, 수동 검증 필요)

| 항목 | 측정값 | 목표 | 비고 |
|---|---|---|---|
| 모델 load 성공 | (사용자 시연 후 채움) | crash 없음 | logcat `MaskRcnnDetector: loaded ONNX` 확인 |
| Cold start inference latency | __s | < 5 | 첫 SPACE → 결과 표시까지 |
| Warm latency | __s | < 2 | 2번째 이후 |
| 메모리 peak | __MB | < 500 | Android Studio Profiler |
| NNAPI 활성화 | (예/아니오) | — | logcat 의 NNAPI 메시지 확인 |
| APK 크기 | 299 MB | ⚠️ 목표 250MB 초과 — quantization follow-up |

## M1.4 — 통합 결과 (Task 11, 수동 검증 필요)

- ☐ 캡처 → DetectionOnlyRigger.real → mask 적용된 캐릭터 sticker 생성
- ☐ 그리드에 새 카드 `det_<timestamp>` 추가됨
- ☐ 새 카드 탭 → 상세 화면에 누끼된 캐릭터 (배경 투명) 표시

## 자동화 한계 (Task 11 보고)

Subagent 가 `adb input tap` 으로 자동 시연 시도했으나 Samsung Galaxy Tab S9 FE+ 의 bottom-edge gesture overlay (Bixby/Samsung Search/Launcher) 가 화면 하단 버튼 tap 을 가로챔. 따라서 inference 단계 (Processing → 결과 표시) 는 자동 검증 못 함. **사용자가 갤탭에서 직접 만져서 끝까지 따라가야** M1.3/M1.4 확인.

확인 방법:
1. 앱 → + FAB → 카메라 → 종이 그림 비추기 → 캡처
2. 다음 ▶ → 모션 선택 → 만들기 ▶
3. "스티커 만드는 중..." spinner — 시간 측정 (cold start ~?)
4. 그리드에 새 카드 (det_xxx) 보이면 success
5. 새 카드 탭 → 상세 화면 → 누끼된 캐릭터 보이면 M1.4 PASS

logcat 동시 확인:
```
adb logcat -s "MaskRcnnDetector" "AndroidRuntime:E"
```
- `loaded ONNX` 로그 = 모델 load OK
- `ClassCastException` 등 = `flattenDets/flattenMasks` 의 cast 조정 필요

## 알려진 이슈 / Follow-up

- ⚠️ APK 299MB — quantization (FP16 또는 INT8) 으로 100MB 이하 가능. 별도 task
- ⚠️ Masks 가 28×28 RoI — 큰 캐릭터에서 mask edge 가 픽셀화 보일 수 있음. 28×28 → bbox 크기 bilinear upsample (Bitmap.createScaledBitmap) 으로 부드럽게. visual quality 측정 필요
- mmdet 2.x → 3.x config patch 가 export 시 수동 (run_export.py). 향후 retrain 시 mmdet 3.x style config 처음부터 사용 권장
- ONNX Runtime Mobile 의 array cast 가 첫 inference 에서 검증 안 됨 — 사용자 시연 시 ClassCastException 발생하면 hot-fix 필요

## Sub-2 진입 조건 체크

- ☐ M1.3 PASS (사용자 manual verify)
- ☐ M1.4 PASS
- ☐ Inference latency 받아들일 수준
- ☐ AlphaPose 모바일 변환 방향 결정 (Sub-2 brainstorm 시점)

## Sub-1 commits

```text
706ef5e feat(sub-1): wire DetectionOnlyRigger.real (ONNX inference) in AppNavHost
f401465 feat(sub-1): DetectionOnlyRigger applies bbox+mask to capture; test factory for DI
6a55289 feat(sub-1): MaskRcnnDetector ONNX Runtime Mobile wrapper (NNAPI try + CPU fallback)
45db473 feat(sub-1): MaskPostprocess decodes ONNX (dets, masks) into Detection list
45843a1 feat(sub-1): ImagePreprocess Bitmap→NCHW BGR mean-subtract (matches AD ONNX export)
2105059 build(android): add onnxruntime-android 1.17.1 + ignore assets/models/
18f9737 docs(sub-1): implementation plan — 12 tasks (PC ONNX export + Android integration)
1fc7f33 docs(sub-1): AD Mask R-CNN ONNX mobile porting design
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
