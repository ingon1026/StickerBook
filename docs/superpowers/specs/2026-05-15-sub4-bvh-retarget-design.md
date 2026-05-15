# Sub-4 Design — BVH retarget (PC 사전 변환 + Android JSON 재생)

> 작성일: 2026-05-15
> Phase 2 의 다섯 번째 sub-project (Sub-5/1/2/2b/3 완료 후)
> 마스터 spec: `2026-05-14-phase2-master-design.md`
> 선행: `2026-05-15-sub3-arap-mesh-design.md`
> 사용자 결정: PC convert.py + JSON 배포 (Option A) + AD BVH catalog 동기화 + Kotlin 측 JsonMotionSource

---

## 1. 목표 / 책임 / 비책임

### 책임

- **PC convert.py**: AD `retargeter.py` 활용. BVH 파일 + standard character template → 17 COCO keypoint normalized JSON
- AD `examples/bvh/` 의 9 BVH (dance_1/2/3, motion_5, phone_1/2/z1, tabtab, zombie) 변환 + `app/app/src/main/assets/motions/` 배치
- **`MotionEntry` catalog** 를 AD BVH 이름과 동기화 (dab/motion_1/motion_2 제거 또는 rename)
- **Android `JsonMotionSource`** (`MotionSource` interface 구현): JSON load + `initialPins` 기준 scale/translate + frameCount interpolation
- `MotionStub.else → wave fallback` **제거** (실제 motion 으로 대체)
- 갤탭에서 motion 별 다른 움직임 시각 검증 (dance_1 vs phone_1)

### 비책임

- Android 측 BVH parser (PC 가 변환, JSON 만 load)
- AD retargeter 알고리즘 개선 (PC 사이드 그대로 사용)
- Audio sync (BVH 에 audio 정보 없음)
- 새 BVH 추가 자동화 (매뉴얼 process: 사용자가 PC 에서 convert.py 돌림)
- 동적 retarget 파라미터 조정 (PC 변환 시점 fixed)
- 사용자 직접 motion 녹화 (Phase 2 master plan 의 별도 sub-task)

### 최소 PASS 기준

1. PC: `python convert.py dance_1.bvh dance_1 dance_1.json` 실행 → JSON 산출
2. PC: 최소 3 motion (dance_1/2/3) 변환 + assets 배치
3. Android: `JsonMotionSource` 가 dance_1 load → ArapRigger 가 dance_1 frame sequence 로 character 변형
4. 갤탭에서 dance_1 vs dance_2 가 시각적으로 **다른 움직임**
5. `MotionStub.else → wave fallback` 제거 (catalog 의 모든 ID 가 실제 BVH 매핑)

---

## 2. AD BVH + retargeter 컨텍스트 + character 추상화

### BVH 파일 형식

```
HIERARCHY
ROOT Hips
{
    OFFSET 0 0 0
    CHANNELS 6 Xposition Yposition Zposition Zrotation Xrotation Yrotation
    JOINT Chest
    {
        OFFSET 0 14 0
        CHANNELS 3 Zrotation Xrotation Yrotation
        JOINT LeftShoulder { ... }
        ...
    }
}
MOTION
Frames: 120
Frame Time: 0.0333333
0 0 0 0 0 0 ...
```

- Joint tree (humanoid 보통 ~30-60 joints)
- 매 frame: root xyz + 각 joint euler angle

### AD retargeter 의 핵심 입력

```python
Retargeter(motion_cfg, retarget_cfg)
    motion_cfg.bvh_p           # BVH 파일
    motion_cfg.up              # "+y" or "+z"
    motion_cfg.forward_perp_joint_vectors  # forward direction
    motion_cfg.scale           # BVH skeleton scale
    motion_cfg.groundplane_joint

    retarget_cfg.char_start_loc
    retarget_cfg.bvh_projection_bodypart_groups
    retarget_cfg.char_joint_groups
```

retarget output: 각 frame 의 character joint 의 2D 위치.

### character 정보 추상화 — 가장 까다로운 부분

AD retargeter 는 character skeleton 정의 (size/proportion) 가 입력. **PC 사전 변환 시점에는 사용자 손그림 비율 모름**.

#### 해결: 표준 COCO skeleton template 사용

- PC 변환 시 AD example 의 가장 표준적인 character (예: `examples/characters/char1/`) 의 retarget_cfg 사용
- JSON 에는 이 standard unit 안의 17 keypoint xy 좌표 + frame 0 hip center = (0, 0), frame 0 shoulder distance = 1.0 으로 **normalize**
- Android 에서: `initialPins` (Sub-2b 의 17 COCO keypoint) 의 bone 길이로 scale + hip center 로 translate

#### Normalize 절차 (PC convert.py)

1. AD retargeter 돌려 standard character template 으로 retarget → 각 frame 의 17 COCO keypoint 의 2D 좌표
2. AD humanoid joint name → COCO 17 mapping 적용
3. Frame 0 의 hip center (kp 11 + kp 12)/2 를 (0, 0) 으로 평행이동
4. Frame 0 의 shoulder distance (kp 5 - kp 6) 를 unit (1.0) 으로 scale
5. y 축 flip (BVH up=+Y → image down=+Y)
6. 같은 transform 을 모든 frame 의 모든 keypoint 에 적용 → JSON

#### Denormalize (Android JsonMotionSource)

1. JSON load → `frames[N][17][xy]` normalized
2. `initialPins` 의 hip center + shoulder distance 계산
3. 매 frame 의 `xy_char_space = initialPins_hip + xy_normalized × initialPins_shoulder_distance`
4. ArapSolver 에 전달

### Edge cases

- BVH `frame_max_num` ≠ 30 → linear interpolation (Sub-3 의 `FRAME_COUNT = 30` 유지)
- shoulder/hip 이 mask 밖이라 `pinMask` false → ArapSolver 가 알아서 처리 (Sub-3 의 pin-mask 패턴)
- face keypoints (kp 0-4) 는 BVH motion 동안 의미 있는 변화 없음 → **`initialPins` 그대로 static 유지** (JsonMotionSource 가 `out = initialPins.copyOf()` 후 kp 5-16 만 덮어씀)

---

## 3. JSON schema + 모듈 분해 + 좌표 처리

### JSON schema

`app/app/src/main/assets/motions/<motion_id>.json`:

```json
{
  "motion_id": "dance_1",
  "frame_count": 120,
  "fps": 30,
  "backend": "ad-coco-17",
  "frames": [
    [[1.0, 0.0], [0.95, -0.05], [0.55, 0.5], ... (17 pairs)],
    [[0.99, 0.02], ... ],
    ...
  ]
}
```

- `frames[f][k] = [x_norm, y_norm]` for keypoint k at frame f
- Normalize 기준: frame 0 hip center = (0, 0), frame 0 shoulder distance = 1.0 unit
- y 는 image convention (down +)

### 모듈 분해

```
rig/
├── (existing, 수정) MotionStub.kt — else→wave fallback 제거
├── JsonMotionSource.kt              (신규)
└── JsonMotionData.kt                (신규)
```

### 데이터 계약

```kotlin
// rig/JsonMotionData.kt
@Serializable
data class JsonMotionData(
    @SerialName("motion_id") val motionId: String,
    @SerialName("frame_count") val frameCount: Int,
    val fps: Int,
    val backend: PoseBackend,
    val frames: List<List<List<Float>>>,  // [F, 17, 2]
)

// rig/JsonMotionSource.kt
class JsonMotionSource(private val context: Context) : MotionSource {
    override fun frames(motionId: String, initialPins: FloatArray, frameCount: Int): List<FloatArray>
}
```

### JsonMotionSource 핵심 흐름

```kotlin
override fun frames(motionId, initialPins, frameCount): List<FloatArray> {
    val data = load(motionId) ?: return List(frameCount) { initialPins.copyOf() }
    // 1. anchor + scale from initialPins
    val hipCx = (initialPins[11*2] + initialPins[12*2]) / 2f
    val hipCy = (initialPins[11*2+1] + initialPins[12*2+1]) / 2f
    val dx = initialPins[5*2] - initialPins[6*2]
    val dy = initialPins[5*2+1] - initialPins[6*2+1]
    val scale = sqrt(dx*dx + dy*dy)  // shoulder distance pixels

    // 2. denormalize each source frame, face keypoints stay static
    val source = data.frames.map { frame ->
        val out = initialPins.copyOf()
        for (k in 5..16) {
            out[k*2]   = hipCx + frame[k][0] * scale
            out[k*2+1] = hipCy + frame[k][1] * scale
        }
        out
    }

    // 3. linear interpolation source N → target frameCount
    return interpolate(source, frameCount)
}
```

### MotionStub 변경

```kotlin
// 기존 else → wave fallback 제거
override fun frames(motionId, initialPins, frameCount) = when (motionId) {
    "wave" -> wave(initialPins, frameCount)
    "identity", "static" -> identity(initialPins, frameCount)
    else -> identity(initialPins, frameCount)  // unknown → 정적 (이전엔 wave fallback)
}
```

MotionStub 은 이제 unit test stub 만. Production = `JsonMotionSource`.

### 파일 변경 요약

| 파일 | 종류 |
|---|---|
| `rig/JsonMotionData.kt` | 신규 |
| `rig/JsonMotionSource.kt` | 신규 |
| `rig/MotionStub.kt` | 수정 (else→wave 제거) |
| `rig/ArapRigger.kt` | 수정 (`real()` 의 motionSource 를 `JsonMotionSource(context)`) |
| `data/MotionEntry.kt` | 수정 (catalog 를 AD BVH 9개와 동기화) |
| `app/app/src/main/assets/motions/<id>.json` | 신규 (9개) |
| `/home/ingon/AR_book/sub4_workdir/convert.py` | 신규 PC script |
| `test/.../JsonMotionSourceTest.kt` | 신규 (denormalize + interpolation TDD) |

---

## 4. PC convert.py + 단계적 PoC + 리스크

### PC convert.py 의 구성

작업 디렉토리: `/home/ingon/AR_book/sub4_workdir/`
Env: AD 의 `animated_drawings` conda env

```python
"""
Convert a BVH file to normalized 17 COCO keypoint JSON sequence.
"""
import json, sys, numpy as np
from animated_drawings.config import MotionConfig, RetargetConfig
from animated_drawings.model.retargeter import Retargeter

AD_TO_COCO = {
    'left_shoulder': 5,  'right_shoulder': 6,
    'left_elbow': 7,     'right_elbow': 8,
    'left_hand': 9,      'right_hand': 10,
    'left_hip': 11,      'right_hip': 12,
    'left_knee': 13,     'right_knee': 14,
    'left_foot': 15,     'right_foot': 16,
}

def convert(bvh_path, motion_id, output_json):
    motion_cfg = MotionConfig(bvh_p=bvh_path, ...)
    retarget_cfg = RetargetConfig(...)  # standard COCO template
    rt = Retargeter(motion_cfg, retarget_cfg)

    F = rt.bvh.frame_max_num
    coco_frames = np.zeros((F, 17, 2), dtype=np.float32)
    for f in range(F):
        for ad_name, coco_idx in AD_TO_COCO.items():
            joint_idx = rt.char_joint_names.index(ad_name)
            coco_frames[f, coco_idx] = rt.joint_positions[f, joint_idx]

    # Normalize
    f0_hip = (coco_frames[0, 11] + coco_frames[0, 12]) / 2
    f0_shoulder = np.linalg.norm(coco_frames[0, 5] - coco_frames[0, 6])
    coco_frames -= f0_hip
    coco_frames /= f0_shoulder
    coco_frames[:, :, 1] *= -1  # y flip

    data = {
        "motion_id": motion_id,
        "frame_count": F,
        "fps": int(round(1.0 / rt.bvh.frame_time)),
        "backend": "ad-coco-17",
        "frames": coco_frames.tolist(),
    }
    with open(output_json, 'w') as f:
        json.dump(data, f)
    print(f"{output_json}: {F} frames")

if __name__ == '__main__':
    convert(*sys.argv[1:4])
```

### 단계적 PoC (M4.1 ~ M4.7)

| M | 내용 | 검증 |
|---|---|---|
| **M4.1** | PC: sub4_workdir + convert.py + dance_1.bvh 1개 변환 | dance_1.json 산출, frame_count=120, frame 0 hip ≈ (0,0), shoulder ≈ 1.0 |
| **M4.2** | PC: 9 BVH 다 변환 + assets/motions/ 배치 | 9 JSON 파일 |
| **M4.3** | Android: JsonMotionData + JsonMotionSource (TDD) | denormalize math + interpolation 정확 |
| **M4.4** | MotionStub fallback 제거 + MotionEntry catalog 동기화 | catalog ID 가 9 BVH 와 일치 |
| **M4.5** | ArapRigger.real() 의 motionSource → JsonMotionSource | compile + 회귀 test PASS |
| **M4.6** | 갤탭 시연 | dance_1 vs phone_1 시각적으로 다른 움직임 |
| **M4.7** | 결과 doc + memory | docs/sub4_results.md |

### 리스크 매트릭스

| # | 리스크 | 영향 | 대응 |
|---|---|---|---|
| R1 | AD `animated_drawings` env 미설치 | M4.1 막힘 | `cd AnimatedDrawings && pip install -e .` 그리고 sklearn / pyyaml 같은 의존성 |
| R2 | AD standard character template 결정 | M4.1 retarget 결과 비율 영향 | AD example 중 가장 표준적인 character 의 retarget_cfg.yaml 복사 (`examples/characters/char1/` 또는 동등) |
| R3 | AD joint name 이 mapping 표와 다름 (예: `LeftShoulder` vs `left_shoulder`) | mapping 깨짐 | M4.1 첫 시도에서 `rt.char_joint_names` print 후 mapping table 수정 |
| R4 | shoulder distance normalize 가 BVH skeleton 회전 시 부정확 | scale 어긋남 | scope 외, 후속 PCA-based 정규화 |
| R5 | JSON 파일 크기 합쳐도 200KB 미만 | scope 외 | — |
| R6 | dance vs zombie 같은 변칙 motion 의 retarget 정확도 차이 | 일부 motion 어색 | scope 외, AD retargeter 한계 |
| R7 | BVH frame_count > ArapRigger FRAME_COUNT (30) 시 정보 손실 | 빠른 motion detail 손실 | linear interpolation acceptable |
| R8 | `initialPins` 의 shoulder distance 가 너무 작거나 0 (mask 잘림 등) | scale 0 → divide-by-zero | JsonMotionSource 에 fallback (예: shoulder dist < 1px 이면 image diagonal 의 10%) |

### 검증

**Unit (Robolectric + JUnit4)**
- `JsonMotionSourceTest`:
  - synthetic JSON (3 frames, normalized) → denormalize 결과 frame 0 = initialPins
  - interpolation: source 60 → target 30 frames evenly spaced
  - unknown motion id → static frames

**통합 (M4.5)**
- ArapRigger.real() 가 JsonMotionSource 사용. dance_1 → 30 frame 생성 + frame 0 vs frame 15 시각 다름

**갤탭 시연 (M4.6)**
- dance_1 캐릭터 → 30 frame slideshow (춤 동작)
- phone_1 캐릭터 → 다른 움직임 (전화 받기 류)
- logcat `JsonMotionSource: loaded motion 'dance_1' (120 frames)` 메시지

### Sub-4 진입 조건 (이미 충족)

- ✅ Sub-3 완료 — MotionSource interface 안정
- ✅ AD repo `examples/bvh/` 9 BVH 존재
- ✅ AD `animated_drawings` conda env 가능

### Sub-4 후속 / 결과 영향

- 새 BVH 추가 절차: PC 에서 `python convert.py <bvh> <id> <output_json>` → assets/motions/ 배치 → MotionEntry catalog 에 ID 추가
- 사용자 직접 motion 녹화 (m 키 류) 의 PC 작업은 별도 sub
- Sub-3 follow-up (GIF encoding, Delaunay mesh) 영향 없음
