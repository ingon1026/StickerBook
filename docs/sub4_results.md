# Sub-4 결과 — BVH retarget (PC 사전 변환 + Android JSON 재생)

날짜: 2026-05-15
대상: Galaxy Tab S9 FE+ (SM-X610), device id R54X4008CET
앱 버전: 0.4.0 (debug)

## M4.1 — PC: dance_1 단일 변환

| 항목 | 측정값 |
|---|---|
| BVH source | examples/bvh/dance_1.bvh |
| AD env | animated_drawings (mmpose 미설치 X, AD 자체 retargeter) |
| Retargeter API 발견 | `rt.joint_positions` shape = `[F, 3*N]` flat (reshape 필요) / `rt.bvh_joint_names` (CamelCase) |
| 3D axes 매핑 | X=depth, Y=up, Z=lateral → screen_x = Z, screen_y = -Y |
| 변환 시간 | ~수 초 |

## M4.2 — PC: 8 BVH 변환 (zombie 1개 skip)

| BVH | frames | fps | 비고 |
|---|---|---|---|
| dance_1 | 249 | 30 | OK |
| dance_2 | 249 | 30 | OK |
| dance_3 | 249 | 30 | OK |
| motion_5 | 260 | 24 | OK |
| phone_1 | 319 | 24 | OK |
| phone_2 | 339 | 24 | OK |
| phone_z1 | 339 | 24 | OK |
| tabtab | 704 | 30 | OK |
| **zombie** | — | — | **SKIP** (LeftThigh/RightThigh missing, skeleton 다름) |

assets/motions/ 에 8 JSON 파일 (~2 MB 합계).

## M4.3 — Android: JsonMotionSource 단위 PASS

| Test | 결과 |
|---|---|
| unknown motion returns identity | ✅ |
| frame 0 of normalized motion equals initialPins | ✅ (fix #3 후) |
| interpolation evenly maps source N → target M | ✅ |
| target frameCount=1 returns first source frame | ✅ |

64 tests PASS (Sub-3 60 + Sub-4 신규 4), 0 fail.

## M4.4~M4.5 — MotionStub fallback 제거 + ArapRigger swap

- MotionStub: `else → wave` fallback (Sub-3 fix) → `else → identity` (Sub-4 cleanup). MotionStub 은 unit test 용도만
- MotionEntry catalog: dab/motion_1/motion_2 제거, motion_5/phone_1/2/z1/tabtab 추가 (8 motion)
- MotionPickerScreenTest 도 같이 fix (catalog 에서 "댑" 제거 → broken test)
- ArapRigger.real() 의 motionSource = `JsonMotionSource(context)`

## M4.6 — 갤탭 시연 (시각 검증 PASS)

### 디버깅 사이클 (3 fix 반복)

| Fix | Commit | 문제 | 해결 |
|---|---|---|---|
| 1 | `c72c933` | shoulder-dist normalize → motion 좌표 4-7 unit (character 영역 밖) | motion centroid + max abs = 1 |
| 2 | `fa60d08` | torso × 1.5 scale = 1384px > bitmap 1199px → motion 또 밖 | scale = char keypoint bbox max × 0.5 |
| 3 | `912a354` | motion frame 0 자세 ≠ character V-pose → ARAP 가 frame 0 부터 변형 → 머리만 크고 다리 압축 | **delta-only**: frame n = initialPins + (motion[n] - motion[0]) × scale |

### 최종 시연 (sticker arap_1778831667138)

| 항목 | 결과 | 평가 |
|---|---|---|
| Frame 0001 | V-pose 그대로 (머리/팔/몸/다리 형태 보존) | ✅ |
| Frame 0015 | 양 손목이 motion 따라 변형 (왼손 위로, 오른팔 펴짐) | ✅ |
| md5 다른지 (frame 0 vs 15) | ✅ 다름 | ✅ |
| 머리/몸 왜곡 | 없음 — character pose 보존 | ✅ |
| 다리 잘림 | 약간 (boundary 에서) | Sub-1 mask 한계, scope 외 |

## 발견된 알고리즘 lesson

1. **Sub-3 ARAP 의 control point 첫 frame 매칭 중요** — character 자세 ≠ motion 자세 면 frame 0 부터 mesh 통째 변형
2. **Motion delta 적용**이 핵심 — motion 의 절대 위치 X, frame n 와 frame 0 의 변화량만 character 에 적용
3. **Scale 단위 선택**: shoulder distance (너무 작음 1×) → torso × 1.5 (너무 큼 ~1.5 bitmap) → keypoint bbox × 0.5 (적절)
4. PC normalize 기준점: motion centroid X → frame 0 hip center O (anchor 명확)

## APK 크기

| asset | 크기 |
|---|---|
| drawn_humanoid_detector.onnx | 176 MB |
| pose_landmarker_heavy.task | 31 MB |
| ad_pose.onnx | 136 MB |
| motions/*.json (8개) | ~2 MB |
| **APK 총** | **~520 MB** (Sub-3 와 거의 동일) |

## 알려진 이슈 / Follow-up

- ⚠️ AD retargeter 의 정확도 한계 — zombie BVH 의 skeleton 형식 다름, skip
- ⚠️ Sub-1 mask 가 character 의 boundary 부분 (다리 끝, 머리) 종종 짤림 — 모델 한계, ARAP 가 보정 못 함 (별도 sub-task)
- ⚠️ shoulder dist normalize 의 정확도 (BVH skeleton 회전 시) — PCA 기반 정규화는 follow-up
- ⚠️ Sub-1+2b quantization 여전히 후속 (latency 1-2분)
- ⚠️ Android 측 BVH parser 직접 사용 검토 — JavaBVHParser 후보지만 retargeter 까지 옮기면 새 sub-task 규모

## Sub-4 commits (시간순)

```text
b68ac5d  docs(sub-4): BVH retarget design — PC convert.py + JSON + Android JsonMotionSource
d1ffacc  docs(sub-4): implementation plan — 7 tasks (PC convert + JsonMotionSource TDD)
c6c10ab  build(sub-4): add converted BVH→COCO17 JSON motions (AD examples/bvh)
b87284c  feat(sub-4): JsonMotionSource — load BVH-converted JSON, denormalize + interpolate (TDD)
c9ac6f6  feat(sub-4): drop MotionStub wave fallback, sync MotionEntry with AD BVH catalog
a6f7430  feat(sub-4): ArapRigger.real() uses JsonMotionSource for production
c72c933  fix(sub-4): motion bbox normalize + character torso scale  [디버깅 fix #1]
fa60d08  fix(sub-4): anchor motion at frame-0 hip + scale by char keypoint bbox / 2  [디버깅 fix #2]
912a354  fix(sub-4): apply motion as delta from frame 0 (preserve character pose)  [디버깅 fix #3, 최종]
```

## Phase 2 본체 완료

Sub-5/1/2/2b/3/4 모두 완료. V2 정식 (갤탭 단독 on-device AD-style animation) 의 본체 구현.

남은 follow-up:
- Sub-1+2b quantization (latency, APK 크기)
- Sub-3 GIF encoding
- Sub-3 Delaunay mesh (artifact 감소)
- Sub-1 mask 정확도 (모델 재학습 또는 후처리)
- 사용자 직접 motion 녹화 (m 키 등) — Phase 2 master plan 의 별도 sub
