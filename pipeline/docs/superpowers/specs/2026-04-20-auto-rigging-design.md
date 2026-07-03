# 손그림 Auto-Rigging (Skeleton 기반)

**작성일**: 2026-04-20
**대상 브랜치**: android-app (PC viewer 기준)
**상태**: design approved, pending implementation plan
**관련 선행 스펙**: [2026-04-17-parts-rigging-design.md](2026-04-17-parts-rigging-design.md)

## 1. 배경과 문제

2026-04-17 스펙으로 "사용자 탭 기반 파츠 분리 + pendulum swing" 이 구현되었다 (`rigging.py`, `viewer.py` E 키). 그러나 실사용 시 두 가지 문제:

1. **UX 부담**: 새 그림이 들어올 때마다 사용자가 viewer 에서 E 키를 눌러 탭으로 seed 를 찍어야 함. 아이가 여러 그림을 연속으로 그리면 비현실적.
2. **과분할 리스크**: 사용자가 원리를 모르고 seed 를 10+ 개 찍으면 마스크가 Voronoi 로 잘게 쪼개져 "피카소 좀비" 가 됨. 정상 동작이지만 UX 실패.

또한 모든 그림이 rigging 대상은 아니다. "돼지 머리만" 같이 팔다리 없는 덩어리는 단일 스프라이트가 자연스럽다.

## 2. 목적과 범위

### 목적
손그림이 2.5D 파이프라인을 통과할 때 **사용자 조작 없이** 마스크 모양만 보고:
1. Rigging 이 필요한지 자동 판단
2. 필요하면 팔/다리/머리에 해당하는 seed 를 자동 생성
3. 기존 rigging 파이프라인 (`partition_mask_by_seeds`, `compute_pivot`) 에 주입

결과: 아이가 그림을 그릴 때마다 viewer 진입과 동시에 이미 rigged 된 인형으로 재생됨. 파츠가 불필요한 그림은 단일 스프라이트로 자연스럽게 처리.

### In-scope (MVP)
- Skeleton (medial axis) 기반 auto seed 생성
- Endpoint 개수 기준 rig 여부 자동 판단
- 기존 `rigging.partition_mask_by_seeds` / `compute_pivot` / `rig.json` 스키마 재사용
- 기존 idle pendulum swing 애니메이션 그대로 적용
- 수동 E 키 모드 유지 — 자동 결과가 맘에 안 들면 사용자가 재편집

### Out-of-scope (v2 이후)
- 제스처 트리거 (손 흔들기, 인사 등) — 아키텍처 훅만 열어둠
- 3단 이상 계층 (손가락/발가락/꼬리 끝)
- 다중 캐릭터 한 번에 rig
- Skeleton 실패 시 fallback 휴리스틱 (convex hull defect, 통계 기반 등)
- 방사형 대칭 (꽃잎) 판별 — MVP 에서는 꽃이 rig 될 수 있음, 수용

## 3. 사용자 결정 사항 요약

| 주제 | 결정 |
|------|------|
| 파츠 분리 용도 | idle 관절 움직임 (MVP) + 제스처 (v2 훅) |
| Rigging 여부 판단 | 자동 휴리스틱 (사용자 질문 없음) |
| 입력 가정 | 임의 손그림, class 정보 없음. 일반 형태 80% 커버 목표. |
| 휴리스틱 신호 | Skeleton 분석 (branch/endpoint 개수) |
| 구현 접근 | 자동 seed 생성 → 기존 `partition_mask_by_seeds` 재사용 |

## 4. 아키텍처

### 4.1 기존 구조 (보존)
- `live_demo.py` — 카메라 → segmentation → mask/depth/normal export → viewer
- `rigging.py` — seed 기반 partition, pivot 계산, rig.json I/O
- `viewer.py` — rig.json 있으면 multi-part 로 pendulum swing, 없으면 single part

### 4.2 추가

```
┌─────────────────────────────────────────────┐
│ live_demo.py                                │
│   run_pipeline(image_bgr, norm_x, norm_y):  │
│     ... mask 생성, export ...               │
│                                             │
│     [신규] try_auto_rig(mask)               │
│       → 성공: rig.json 저장                 │
│       → 실패(단일 파츠): rig.json 삭제      │
│                                             │
│   viewer_phase(...)                         │
│     기존 rig 로딩 로직이 자동 분기          │
└─────────────────────────────────────────────┘
              ▲
              │
┌─────────────────────────────────────────────┐
│ auto_rig.py (신규)                          │
│   try_auto_rig(mask) → Optional[dict]       │
│     1. skeletonize(mask)                    │
│     2. analyze_skeleton(skel) →             │
│          (root_xy, [endpoint_xy, ...])      │
│     3. if len(endpoints) < MIN:             │
│          return None  # 단일 파츠           │
│        else:                                │
│          seeds = [root] + endpoints[:MAX]   │
│          labels = partition_mask_by_seeds() │
│          rig = build_rig(seeds, mask)       │
│          return rig                         │
└─────────────────────────────────────────────┘
```

### 4.3 모듈 책임

**`auto_rig.py` (신규)**
- `skeletonize_mask(mask: ndarray) -> skel: ndarray` — skimage wrapper, 실패 시 빈 skeleton
- `analyze_skeleton(skel) -> (root_xy, endpoints)` — branching/endpoint 추출, 짧은 가지 제거
- `try_auto_rig(mask, output_dir) -> Optional[dict]` — 상기 단계 통합, rig.json 저장까지. 실패 시 `None` 반환 + 기존 rig.json 있으면 삭제

**`live_demo.py` (수정)**
- `run_pipeline` 끝부분에 `auto_rig.try_auto_rig(mask, config.OUTPUT_DIR)` 추가
- 이전 수정에서 넣은 "stale rig.json 삭제 로직" 은 auto_rig 내부로 이동 (None 반환 시 삭제)

**`rigging.py` (변경 없음)**
- 기존 API 가 seed 리스트를 받도록 이미 설계되어 있어 auto_rig 가 그대로 호출

**`viewer.py` (변경 없음)**
- `load_parts_from_output` 이 rig.json 있으면 multi, 없으면 single 로 분기 — 기존 로직 유지
- E 키 수동 편집도 그대로, 자동 결과 위에 덮어쓰기 가능

**`config.py` (상수 추가)**
- `AUTO_RIG_MIN_BRANCH_PX = 15` — 이보다 짧은 skeleton 가지는 노이즈로 제거
- `AUTO_RIG_MIN_ENDPOINTS = 3` — 이 미만이면 단일 파츠
- `AUTO_RIG_MAX_ENDPOINTS = 8` — 상한 (가장 긴 8개만)

## 5. 핵심 알고리즘

### 5.1 Skeleton 분석

```python
def analyze_skeleton(skel: np.ndarray) -> tuple[tuple | None, list[tuple]]:
    """
    skel: (H, W) binary, skimage.morphology.skeletonize 결과
    returns: (root_xy | None, [endpoint_xy, ...])

    1. 각 skeleton 픽셀의 8-이웃 내 skeleton 이웃 수 계산
       - neighbors == 1 → endpoint
       - neighbors >= 3 → branching point
       - neighbors == 2 → 중간 점 (무시)

    2. endpoint 로부터 BFS 로 각 가지 (branch) 길이 측정
       - 길이 < AUTO_RIG_MIN_BRANCH_PX 인 가지는 노이즈로 제거
         (endpoint 에서 다음 branching point 또는 endpoint 까지의 경로 픽셀 수)

    3. root 선정:
       - branching point 가 ≥ 1 개: skeleton centroid 에 가장 가까운 branching point
       - 없으면 (일자형 skeleton): skeleton centroid 자체를 root 로 (가까운 skeleton 픽셀로 snap)

    4. endpoint 정리:
       - 상위 AUTO_RIG_MAX_ENDPOINTS 개 (가지 길이 내림차순)
    """
```

### 5.2 Rig 여부 판단

```python
if len(endpoints) < AUTO_RIG_MIN_ENDPOINTS:
    return None  # 단일 파츠. 예: 돼지 머리(endpoint 0~1), 꽃 중심부 둥근(1~2)

# endpoints >= 3: rig 대상
seeds = [root_xy] + endpoints  # 첫 seed = root, 나머지 = children
```

판단 예시:
- 막대인형 (머리-몸-팔2-다리2): endpoint 5개 → rig (머리+팔2+다리2 = child 5, 몸통 = root)
- 돼지 머리 (귀 살짝 뾰족): endpoint 0~2개 → 단일 파츠
- 꽃 (꽃잎 5장): endpoint 5개 → rig 됨 (MVP 한계, 수용)

### 5.3 기존 파이프라인 연결

```python
def try_auto_rig(mask, output_dir):
    skel = skeletonize_mask(mask)
    if skel is None or skel.sum() == 0:
        _cleanup_rig(output_dir)
        return None

    root_xy, endpoints = analyze_skeleton(skel)
    if root_xy is None or len(endpoints) < AUTO_RIG_MIN_ENDPOINTS:
        _cleanup_rig(output_dir)
        return None

    seeds = [root_xy] + endpoints[:AUTO_RIG_MAX_ENDPOINTS]
    labels = rigging.partition_mask_by_seeds(mask, seeds)
    h, w = mask.shape
    parts_data = [{"id": 0, "name": "root", "parent": -1,
                   "seed_xy": list(seeds[0]), "pivot_xy": list(seeds[0])}]
    root_mask = (labels == 0).astype(np.uint8) * 255
    for i in range(1, len(seeds)):
        child_mask = (labels == i).astype(np.uint8) * 255
        if child_mask.sum() < config.MIN_PART_AREA_PX * 255:
            continue  # 너무 작으면 파츠 skip, root 에 흡수
        pivot = rigging.compute_pivot(child_mask, root_mask)
        parts_data.append({"id": i, "name": f"child_{i}", "parent": 0,
                           "seed_xy": list(seeds[i]), "pivot_xy": list(pivot)})

    rig = {"version": rigging.RIG_VERSION, "source_object": config.OBJECT_FILENAME,
           "image_size": [w, h], "parts": parts_data}
    rigging.save_rig(rig, os.path.join(output_dir, config.RIG_FILENAME))
    return rig
```

## 6. 런타임 동작

### 6.1 자동 rig 성공 (막대인형)
```
live_demo.py → 캡처 → segmentation → mask, object.png, depth.png 저장
  → try_auto_rig(mask): skeleton endpoint=5 → rig.json 저장
  → viewer 진입
  → load_parts_from_output: rig.json 감지 → 5 파츠 (root + child 4) 로딩
  → 바로 rigged 애니메이션 재생 (팔다리 흔들림)
  → 사용자가 맘에 안 들면 E 키 → 수동 재편집 가능
```

### 6.2 자동 rig 스킵 (돼지 머리)
```
캡처 → segmentation → export
  → try_auto_rig(mask): endpoint=1 → None 반환, rig.json 삭제
  → viewer 진입
  → load_parts_from_output: rig.json 없음 → 단일 파츠
  → 기존 bounce/wobble 애니메이션만 재생
  → 사용자가 원하면 E 키 → 수동 seed 찍어 rig 가능
```

### 6.3 Edit 모드 (기존 유지)
- E 키 → edit 모드 → 사용자 seed 찍어 ENTER → rig.json 덮어쓰기 → viewer 리로드
- 자동 결과를 덮어쓰는 경로로 활용

## 7. 데이터 모델

`rig.json` 스키마는 기존과 동일 (auto 여부를 나타내는 필드 없음 — 결과물은 수동/자동 구분 없이 동일 형식).

v2 훅으로 optional 필드 미리 정의만 해둠 (MVP에서는 작성/읽기 모두 안 함):
```json
{
  "version": 1,
  "source_object": "object.png",
  "image_size": [640, 480],
  "auto_generated": true,           // v2 hook: 자동/수동 구분
  "animation_profile": "idle",      // v2 hook: "idle" | "gesture_wave" | ...
  "parts": [...]
}
```

## 8. 실패 모드와 대응

| 상황 | 대응 |
|------|------|
| skimage 미설치 | ImportError 잡아서 `try_auto_rig` 가 None 반환, 로그 경고. requirements.txt 에 추가 |
| 마스크가 너무 작음 (< 100 px) | `skel.sum() == 0` 체크 → None 반환, 단일 파츠 |
| skeleton 이 일자형 (branching 없음) | endpoint 2개 → MIN_ENDPOINTS 미달 → 단일 파츠 |
| endpoint 과다 (낙서, > MAX) | 가지 길이 상위 MAX 개만 사용 |
| 꽃 그림처럼 방사형 대칭 | MVP 에서 rig 됨 (허용). v2 에서 대칭성 감지 추가 검토 |
| compute_pivot 실패 (파츠가 root 와 disjoint) | 기존 로직대로 centroid fallback |
| 작은 파츠 (< MIN_PART_AREA_PX) | skip 후 root 영역으로 흡수 |

## 9. 파일 변경 목록

| 파일 | 변경 종류 | 개요 |
|------|----------|------|
| `auto_rig.py` | 신규 | skeleton 분석, seed 자동 생성, rig.json 작성 |
| `live_demo.py` | 소폭 수정 | `run_pipeline` 끝에 `try_auto_rig(mask, output_dir)` 호출. 기존 stale rig 삭제 로직은 auto_rig 내부로 이동 |
| `config.py` | 상수 추가 | `AUTO_RIG_MIN_BRANCH_PX`, `AUTO_RIG_MIN_ENDPOINTS`, `AUTO_RIG_MAX_ENDPOINTS` |
| `requirements.txt` | 의존성 추가 | `scikit-image` (skeletonize 용) |
| `rigging.py` | 변경 없음 | 기존 API 재사용 |
| `viewer.py` | 변경 없음 | 기존 rig.json 감지 로직이 auto/manual 무관하게 작동 |

## 10. 테스트 전략

### 10.1 유닛 테스트 (`tests/test_auto_rig.py`)
- 막대인형 형태 synthetic mask (머리원 + 몸통사각 + 팔다리 4선) → endpoint 5, rig 생성 확인
- 원 (둥근 마스크) → endpoint 0~1, None 반환 확인
- 꽃 (5 꽃잎 방사형) → endpoint 5, rig 생성됨 (현 단계 한계 문서화)
- 빈 마스크 → None 반환
- 너무 작은 마스크 (50px) → None 반환

### 10.2 수동 통합 테스트
1. 막대인형 아이 그림 캡처 → viewer 진입 즉시 팔다리 흔들림 확인
2. 돼지 머리 캡처 → viewer 단일 파츠 bounce 만 확인
3. 꽃 그림 캡처 → 꽃잎이 흔들리는 결과 확인 (알려진 한계, 문서화)
4. 자동 rig 후 E 수동 편집 → rig 덮어쓰기 확인
5. scikit-image 없는 환경 → 에러 없이 단일 파츠 fallback 확인

### 10.3 성능
- skeletonize 640×480 마스크: ~20ms 예상 (skimage)
- analyze_skeleton: ~5ms
- 전체 auto_rig: ~30~50ms — 기존 pipeline (수백 ms) 대비 무시 가능

## 11. 마이그레이션 / 하위 호환

- 기존 rig.json 스키마와 호환 (auto 여부 플래그는 미사용 — v2 훅)
- 기존 수동 E 키 경로 100% 유지
- scikit-image 미설치 환경에서도 기존 기능 (단일 파츠 + 수동 rig) 정상 작동
- `live_demo.py` 이전에 추가한 stale rig 삭제 로직은 `auto_rig.try_auto_rig` 의 None 분기로 대체됨

## 12. v2 훅

- `rig.json` 의 `auto_generated`, `animation_profile` 필드 — 현재는 작성 안 함, v2 에서 제스처 구분 시 활용
- `analyze_skeleton` 은 tuple 반환 → v2 에서 파츠 타입 (머리/팔/다리) 라벨링 추가 가능
- 방사형 대칭 감지 (꽃 판별) — skeleton 각 endpoint 가 centroid 에 대해 균일 각도 분포면 rig 스킵 후보
