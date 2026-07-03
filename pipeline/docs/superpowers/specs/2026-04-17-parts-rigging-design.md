# 손그림 파츠 분리 + 2.5D rigged 애니메이션 (v1)

**작성일**: 2026-04-17
**대상 브랜치**: android-app (PC viewer 기준)
**상태**: design approved, pending implementation plan

## 1. 목적과 범위

### 목적
현재 `viewer.py` 는 손그림 누끼(단일 스프라이트)를 bounce/wobble/extrusion 효과와 함께 2.5D 로 애니메이션한다. 그러나 그림은 하나의 덩어리로 렌더되어 **팔/다리/머리가 독립적으로 움직이지 않음** — "스티커가 출렁이는" 느낌에 그침.

이 스펙은 손그림을 **사용자 지정 파츠로 분리**하고, 각 파츠가 간단한 pendulum swing 로 흔들리게 하는 최소 MVP 를 정의한다. 완성 시 정적인 그림이 "캐릭터처럼 움직이는" 체감으로 업그레이드된다.

### In-scope (v1)
- 사용자 탭 기반 파츠 분리 (viewer 내 `E` 키 토글로 진입하는 edit 모드)
- Voronoi/geodesic 기반 자동 마스크 파티션
- 2단 계층 (root + direct children), pivot 자동 계산
- 모든 자식 파츠에 균일 pendulum swing 애니메이션 (index phase offset)
- `rig.json` 사이드카로 영속화 및 재사용
- Rig 없을 때 기존 단일 스프라이트 동작 보존 (하위 호환)

### Out-of-scope (v2 이후)
- 제스처 / 트리거 기반 pose 전환 (wave, jump 등)
- 3단 이상 계층 (손가락, 발가락, 꼬리 끝 등)
- 파츠별 수동 애니메이션 튜닝 UI (slider 등)
- 파츠 rename UI — v1 은 자동 `child_1, child_2, ...` 부여
- 파이프라인 (`pipeline.py` / `live_demo.py`) 자체에 분리 단계 통합 — v1 은 viewer 내부에서만 edit

## 2. 사용자 결정 사항 요약

| 주제 | 결정 |
|------|------|
| 원하는 움직임 | 혼합: idle breathing + 사지 swing (MVP), 제스처는 v2 |
| 캐릭터 범위 | free-form (어떤 그림이든 동작해야 함) |
| 분리 UX | 부위별 탭 1회씩, 첫 탭 = root |
| 분리 타이밍 | viewer 내 `E` 키 토글 (기존 플로우 보존) |
| 계층 구조 | 2단 (루트 + 직속 자식들만) |
| 애니메이션 | 모든 자식에 균일 pendulum swing + golden-angle phase offset |
| 영속성 | `output/rig.json` 사이드카 |

## 3. 아키텍처

### 3.1 기존 구조 (보존)
- `pipeline.py` / `live_demo.py` — 카메라 → contour segmentation → mask 후처리 → depth/normal 추정 → export
- `viewer.py` — pygame 기반 2.5D 뷰어, `Part` 리스트를 순회하는 구조로 이미 일반화되어 있음 (단, 현재는 리스트 길이 = 1)

### 3.2 추가/변경
```
┌─────────────────────────────────────────────┐
│ live_demo.py (카메라 → 세그 → export)        │  변경 최소
└─────────────────┬───────────────────────────┘
                  ▼
         output/object.png, depth.png, normal.png
         (+ 선택적 rig.json)
                  ▼
┌─────────────────────────────────────────────┐
│ viewer.py — play_viewer(parts, ...)         │
│   rig.json 있으면 parts = [root, children…] │
│   없으면      parts = [single]              │
│                                             │
│   E 키 → edit_mode ON                       │
│      → rigging.py (신규) 호출               │
│      → 사용자 탭 → seeds 수집               │
│      → partition_mask_by_seeds              │
│      → compute_pivot (각 자식)              │
│      → rig.json 저장                        │
│      → parts 리로드                         │
└─────────────────────────────────────────────┘
```

### 3.3 모듈 책임

**`viewer.py` (변경)**
- 여러 `Part` 렌더: root 변환 → 각 자식이 root 변환 위에서 자기 pivot 기준 rotate
- `E` 키 이벤트 → edit 모드 진입/이탈
- `output/rig.json` 자동 로드/적용
- 자식 pendulum swing 계산 및 transform 합성

**`rigging.py` (신규)**
- `partition_mask_by_seeds(mask: ndarray, seeds: list[(x,y)]) -> label_image` — Voronoi/geodesic 파티션
- `compute_pivot(child_mask, parent_mask) -> (x, y)` — 자식 내 부모 경계 접점
- `save_rig(rig, path)`, `load_rig(path) -> rig_dict` — JSON I/O
- `build_parts_from_rig(object_rgba, mask, rig) -> list[Part]` — rig.json 과 원본 asset 으로 `Part` 리스트 구성

**`rig_editor` (viewer.py 내부 클래스 또는 별도 모듈)**
- edit 모드 pygame 상태 관리 (seeds, 오버레이 렌더)
- 클릭/Enter/Esc/C 키 처리

**`live_demo.py` (미세 수정)**
- viewer 호출 직전에 `output/rig.json` 존재 여부 확인 후 `load_rig` → `build_parts_from_rig` 로 여러 파츠 전달하거나, 없으면 기존 단일 Part 경로

## 4. 데이터 모델

### 4.1 `rig.json` 스키마

`output/rig.json` 에 저장된다. 경로 필드는 모두 `output/` 기준 상대 경로.

```json
{
  "version": 1,
  "source_object": "object.png",
  "image_size": [640, 480],
  "parts": [
    {
      "id": 0,
      "name": "root",
      "parent": -1,
      "seed_xy": [320, 240],
      "pivot_xy": [320, 240]
    },
    {
      "id": 1,
      "name": "child_1",
      "parent": 0,
      "seed_xy": [200, 180],
      "pivot_xy": [220, 200]
    }
  ]
}
```

**설계 근거**
- `seed_xy` 만 저장 — 마스크 자체는 저장하지 않고 매번 `partition_mask_by_seeds` 로 재생성. deterministic 하므로 재현성 보장, 파일 크기 최소.
- `pivot_xy` 는 저장된 값 우선. 없거나 재생성 필요 시 `compute_pivot` 으로 보충.
- `parent` 는 id 참조. v1 에서는 root(-1) 또는 0(루트의 직속 자식) 만 유효.
- `image_size` — 마스크/object_rgba 와 좌표계 일치 확인용.

### 4.2 런타임 `Part` 구조 (viewer.py 기존)
```python
@dataclass
class Part:
    object_rgba: ndarray      # 공통 RGBA (부모와 동일 소스 공유 OK, mask 로 가려 렌더)
    mask: ndarray             # 이 파츠 전용 binary mask (H, W)
    pivot: tuple[int, int]    # local pivot (마스크 좌표계)
    parent: int = -1
    z_order: int = 0
    # 기타 기존 필드 유지 (_padded_rgba, _padded_side_rgba, depth, normal 등)
```

v1 에서 각 파츠의 `object_rgba` 는 공유하되 `mask` 를 파츠별로 다르게 적용한 **sub-sprite** 가 `_padded_rgba` 로 들어간다.

## 5. 런타임 동작

### 5.1 첫 실행 (rig.json 없음)
```
live_demo.py → 카메라 → 그림 선택 → export
  → viewer.play_viewer([single_part])    # 기존 동작 그대로
  → 사용자가 E 키
  → edit 모드 진입
      → "첫 탭은 몸통(루트)입니다" 오버레이 표시
      → 사용자 탭: seed 추가, 번호/색 표시
      → ENTER: partition → pivot 계산 → rig.json 저장 → viewer 리로드 (여러 파츠)
      → ESC: 취소 (아무 변경 없음)
      → C: 모든 seeds clear
  → 리로드 후 각 파츠가 pendulum swing 시작
```

### 5.2 재실행 (rig.json 있음)
```
live_demo.py → ... (카메라 생략도 가능, 같은 object.png 재사용) → viewer 진입
  → rig.json 감지 → load → build_parts_from_rig → parts 리스트
  → 바로 rigged 애니메이션 재생
  → E 키로 재편집 가능 (기존 seeds 이 표시됨, 추가/삭제/확정)
```

### 5.3 Edit 모드 UI

- **배경**: 일반 애니메이션 일시 정지, 반투명 검은 오버레이 (alpha ~120)
- **가이드 표시**: mask 가장자리 노란 선 (0.5px), 좌상단 지시 문구
- **seeds 표시**: 이전 seed 에 번호와 파츠 색상 원 (루트=빨강, 1=초록, 2=파랑, 3=주황, 4=보라, 5=청록, 6+ 회색)
- **키 바인딩**:
  - 마우스 좌클릭 (기존 seed 근처 >12px): 새 seed 추가 (mask 내부인 경우만)
  - 마우스 좌클릭 (기존 seed 근처 ≤12px): 그 seed 삭제
  - `ENTER`: partition 실행 + 저장 + edit 모드 종료
  - `ESC`: 변경 버리고 종료
  - `C`: 모든 seeds 제거
- Seed 이동(드래그 편집)은 v1 미지원 — 삭제 후 재추가로 대체.

### 5.4 2단 계층 변환 파이프라인

프레임당 각 Part 드로우:

```
# Root
root_M = T(bounce + sway + wobble_x) * R(tilt) * S(1 + breath)
draw(root, root_M)

# Each child (parent = 0)
child_pivot_world = root_M.apply(child.pivot)
child_M = T(child_pivot_world)
         * R(swing_angle(t, child.id))
         * T(-child.pivot)
         * root_M   # 부모 변환 상속
draw(child, child_M)
```

- root_M 은 기존 bounce/wobble/tilt/breath 로직 그대로 계산 (변경 없음)
- 자식은 pivot 을 부모 변환에 태운 뒤 자기 swing rotation 적용 → 팔이 몸통 따라 움직이며 동시에 어깨 기준 흔들림
- 2단이라 곱셈 1회로 충분, matrix 라이브러리 불필요 (pygame.Surface.blit + transform.rotate 조합)

## 6. 핵심 알고리즘

### 6.1 Voronoi/geodesic 파티션
```python
def partition_mask_by_seeds(mask: np.ndarray, seeds: list[tuple[int, int]]) -> np.ndarray:
    """
    mask: binary (0 or 255), shape (H, W)
    seeds: [(x, y), ...] — 탭 좌표. 모두 mask > 0 영역 내부여야 함.
    returns: label_image (H, W) int32, label[y,x] ∈ {0..N-1} for mask>0, -1 otherwise.
    """
    # 구현: seed 마다 마스크 내부에서 BFS/geodesic distance 전파 → 각 픽셀은 최단 distance 의 seed 에 할당.
    # 실전 가능 구현: cv2.distanceTransformWithLabels 를 seed 마다 실행 후 argmin, 또는 multi-source BFS.
    # 선택 구현: multi-source BFS (가장 간단, O(H*W) 1 pass)
```

**장점**: 모든 mask 픽셀이 정확히 하나의 seed 에 할당됨. 실패 모드 없음. flood-fill 처럼 얇은 선 때문에 leak 되는 일 없음.
**주의**: Euclidean Voronoi 가 아닌 **geodesic** (mask 내부로만) 이어야 함. 예: U자 모양 mask 에서 가장 가까운 seed 가 바깥으로 일직선이어도 geodesic 은 mask 따라 돌아가야 함.

### 6.2 Pivot 자동 계산
```python
def compute_pivot(child_mask: np.ndarray, parent_mask: np.ndarray) -> tuple[int, int]:
    """
    child_mask 내부에서 parent_mask 경계에 가장 가까운 점.
    = child_mask 내부의 argmin( distance_to_parent_boundary )
    """
    # cv2.distanceTransform 을 (parent_mask > 0) 의 여집합에 적용 → child_mask 내부 argmin 위치
    # 에지 케이스: child 가 parent 와 disjoint 인 경우 → child 중심점 (centroid of child_mask) 을 pivot 으로 사용 (v1 타협안)
```

**의미**: pivot 은 "자식이 부모와 붙어있는 점" — 팔이 몸통에 붙는 어깨 위치, 머리가 몸통 위로 붙는 목 위치 등. swing rotation 이 이 점 기준으로 일어나 자연스러움.

### 6.3 Pendulum swing
```python
def child_swing_angle_deg(t: float, child_idx: int) -> float:
    phase = child_idx * GOLDEN_ANGLE_RAD   # 2π × 0.381...
    return MAX_SWING_DEG * math.sin(SWING_HZ * 2 * math.pi * t + phase)
```

- `MAX_SWING_DEG` ~ 6–10°
- `SWING_HZ` ~ 0.5–0.8 Hz
- Golden-angle phase offset → 자식끼리 싱크되지 않고 자연스럽게 어긋남

## 7. 실패 모드와 대응

| 상황 | 대응 |
|------|------|
| rig.json 파일 없음 | 기존 단일 파츠 경로로 fallback |
| rig.json version mismatch | 경고 로그 + 무시, re-edit 유도 |
| seed 가 mask 바깥 클릭 | 무시, `(1회 깜빡임)` 피드백 |
| 루트 외 seeds 가 0 개 | 단일 파츠와 동일 (부모 자신만 존재) |
| 파츠가 너무 작음 (<100 px) | 경고 메시지 표시 but 허용 |
| seed 두 개가 같은 픽셀 | 두 번째 seed 제거 피드백 |
| image_size 와 현재 object.png 크기 불일치 | 경고 + rig 폐기 유도 |

## 8. 파일 변경 목록

| 파일 | 변경 종류 | 개요 |
|------|----------|------|
| `viewer.py` | 수정 | 여러 Part 렌더 (이미 순회 구조), E 키 edit 모드 entry, 2단 transform 합성, rig.json 자동 로드 |
| `rigging.py` | 신규 | `partition_mask_by_seeds`, `compute_pivot`, `save_rig`, `load_rig`, `build_parts_from_rig` |
| `live_demo.py` | 소폭 수정 | viewer 호출 전 rig.json 조회해 파츠 리스트 구성 (없으면 단일) |
| `pipeline.py` | 변경 없음 | |
| `postprocess.py` | 변경 없음 | |
| `config.py` | 소폭 수정 | `SWING_*`, `GOLDEN_ANGLE_RAD`, `MIN_PART_AREA_PX` 상수 추가 |

## 9. 테스트 전략

### 9.1 유닛 테스트 (`tests/test_rigging.py`)
- `partition_mask_by_seeds`: 간단한 사각형 mask + 2 seeds → 좌반/우반으로 나뉘는지
- `partition_mask_by_seeds`: U자 mask + seeds → geodesic 이 맞는 영역으로 가는지
- `compute_pivot`: child 와 parent 가 명백히 접하는 상황에서 접점 좌표 반환
- `save_rig` / `load_rig`: round-trip JSON 보존

### 9.2 수동 통합 테스트
- 기존 `output/object.png` 로 `python3 viewer.py` 실행 → E 키 → root + 2 children 탭 → ENTER → 애니메이션 확인
- 재실행 → rig.json 자동 로드 확인
- E → C → ENTER (모두 지움) → 단일 파츠로 복귀 확인

### 9.3 Edge cases
- 파츠 1개 (root 만): 기존과 동일 동작
- 파츠 7개: 성능 정상
- 루트가 경계에 걸친 경우: swing 시 자식이 화면 밖 튀어나가지 않는지

## 10. 마이그레이션 / 하위 호환

- 기존 `output/` 에 rig.json 없으면 **현재와 100% 동일**하게 동작해야 함
- 기존 `Part` dataclass 는 필드 추가만 (삭제/이름변경 없음)
- 기존 키 바인딩 보존: SPACE, R, Q, ESC. E 만 신규

## 11. 성능 예상

- partition 1회 계산 비용: 640×480 에서 ~20ms (multi-source BFS). edit 모드 확정 시 1회만 수행.
- 프레임당 추가 비용: 자식 파츠당 smoothscale + rotate + blit — 파츠 6개 기준 프레임당 ~2–3ms 추가. 60fps 여유.
- `rig.json` 크기: 파츠 10개 기준 ~1KB.

## 12. v2 로 이어지는 설계 훅

- `Part.parent` 필드 3단 이상도 허용 (현재 v1 에서는 validation 으로 0 또는 -1 만 강제)
- swing 공식 분리 가능 — 제스처 모드 도입 시 override 가능한 함수 테이블로
- `rig.json` 에 `animation_profile` 필드를 옵셔널로 열어두되 v1 에서는 무시
