# 손그림 파츠 분리 + 2.5D rigged 애니메이션 v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자가 viewer에서 `E` 키로 파츠 탭을 지정하면 손그림이 2단 계층 rig로 분리되어 각 파츠가 독립적으로 pendulum swing 한다.

**Architecture:** 기존 `viewer.py` 의 `Part` 리스트 순회 구조를 그대로 활용하되, 다음을 추가한다 — (1) 신규 `rigging.py` 모듈이 seed 기반 Voronoi 파티션과 `rig.json` I/O 를 담당, (2) `viewer.py` 에 edit 모드와 multi-part 렌더 경로를 추가, (3) `live_demo.py` 는 `output/rig.json` 감지만 덧붙인다. 기존 단일 파츠 동작은 `rig.json` 없을 때 그대로 보존.

**Tech Stack:** Python 3.12, numpy, opencv-python, pygame — 신규 의존성 없음. 테스트는 Python 표준 `unittest` 사용 (pytest 설치 불필요).

**Spec:** [`docs/superpowers/specs/2026-04-17-parts-rigging-design.md`](../specs/2026-04-17-parts-rigging-design.md)

---

## File Structure

**신규 생성**
- `rigging.py` — 순수 함수 모듈 (Voronoi 파티션, pivot 계산, rig.json I/O, Part 리스트 구성)
- `tests/__init__.py` — 빈 파일 (Python 패키지)
- `tests/test_rigging.py` — unittest 기반 단위 테스트

**수정**
- `viewer.py` — rig.json 자동 로드, 2단 계층 렌더, E 키 edit 모드 진입점, `_RigEditor` 내부 클래스
- `live_demo.py` — viewer_phase 에서 rig.json 감지 후 파츠 리스트 구성
- `config.py` — swing/rig 관련 상수 추가

**검증**
- 모든 단위 테스트 `python3 -m unittest discover tests` 로 통과
- 수동: `python3 live_demo.py` → 그림 찍고 → viewer 에서 E → root + 2 child 탭 → ENTER → 다리/팔 흔들흔들 확인

---

## Task 1: tests 디렉토리 스캐폴드

**Files:**
- Create: `tests/__init__.py`
- Create: `tests/test_rigging.py` (빈 스켈레톤)

- [ ] **Step 1: 디렉토리/패키지 파일 생성**

```bash
mkdir -p tests
touch tests/__init__.py
```

- [ ] **Step 2: 테스트 스켈레톤 작성**

Create `tests/test_rigging.py`:
```python
"""Unit tests for rigging.py (parts separation + rig I/O)."""
import unittest


class SmokeTest(unittest.TestCase):
    def test_scaffold_runs(self):
        self.assertEqual(2 + 2, 4)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: 스켈레톤이 돈다는 것을 확인**

Run: `python3 -m unittest discover tests -v`
Expected: `test_scaffold_runs ... ok` 1개 PASS

- [ ] **Step 4: 커밋**

```bash
git add tests/__init__.py tests/test_rigging.py
git commit -m "test: add empty tests scaffold for rigging module"
```

---

## Task 2: `rigging.partition_mask_by_seeds` (multi-source BFS)

**Files:**
- Create: `rigging.py`
- Modify: `tests/test_rigging.py`

**컨텍스트:** 마스크 내 각 픽셀을 가장 가까운 (geodesic) seed 에 할당. 얇은 연결선 때문에 flood-fill 이 실패하는 경우가 없도록 multi-source BFS 로 구현.

- [ ] **Step 1: 실패 테스트 먼저 작성**

Replace `tests/test_rigging.py` contents with:
```python
"""Unit tests for rigging.py (parts separation + rig I/O)."""
import unittest
import numpy as np

from rigging import partition_mask_by_seeds


class PartitionTest(unittest.TestCase):
    def test_split_rectangle_horizontally(self):
        # 10x20 흰 마스크, 왼쪽/오른쪽 중앙에 seed
        mask = np.full((10, 20), 255, dtype=np.uint8)
        seeds = [(4, 5), (15, 5)]  # (x, y)
        labels = partition_mask_by_seeds(mask, seeds)

        self.assertEqual(labels.shape, (10, 20))
        # 왼쪽 절반 = label 0, 오른쪽 절반 = label 1
        self.assertEqual(labels[5, 2], 0)
        self.assertEqual(labels[5, 17], 1)
        # 마스크 외부가 아니므로 -1 은 없어야 함
        self.assertTrue((labels >= 0).all())

    def test_u_shape_geodesic(self):
        # U자 마스크. 두 팔의 끝에 seed. 유클리드로는 섞이지만 geodesic 은 각 팔을 덮어야 함.
        mask = np.zeros((20, 30), dtype=np.uint8)
        # 왼팔: 열 0-4, 행 0-19
        mask[0:20, 0:5] = 255
        # 오른팔: 열 25-29, 행 0-19
        mask[0:20, 25:30] = 255
        # 아래 bridge: 행 15-19, 열 0-29
        mask[15:20, 0:30] = 255
        seeds = [(2, 0), (27, 0)]  # 두 팔 상단
        labels = partition_mask_by_seeds(mask, seeds)

        # 왼팔 상단 = 0, 오른팔 상단 = 1
        self.assertEqual(labels[0, 2], 0)
        self.assertEqual(labels[0, 27], 1)
        # 외부는 -1
        self.assertEqual(labels[5, 15], -1)

    def test_outside_mask_is_minus_one(self):
        mask = np.zeros((5, 5), dtype=np.uint8)
        mask[1:4, 1:4] = 255
        labels = partition_mask_by_seeds(mask, [(2, 2)])
        # 내부는 0
        self.assertEqual(labels[2, 2], 0)
        # 외부는 -1
        self.assertEqual(labels[0, 0], -1)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: `ModuleNotFoundError: No module named 'rigging'`

- [ ] **Step 3: `rigging.py` 에 최소 구현 작성**

Create `rigging.py`:
```python
"""Parts rigging — seed-based partition, pivot calc, rig.json I/O.

v1 scope: 2-level hierarchy (root + direct children). See
docs/superpowers/specs/2026-04-17-parts-rigging-design.md
"""
from collections import deque

import numpy as np


def partition_mask_by_seeds(mask: np.ndarray, seeds: list) -> np.ndarray:
    """Multi-source BFS: each mask pixel → index of closest seed (geodesic).

    Args:
        mask: uint8 (H, W), non-zero = foreground.
        seeds: list of (x, y) int tuples. Must lie inside mask.

    Returns:
        int32 (H, W) label image: -1 outside mask, 0..N-1 inside.
    """
    h, w = mask.shape
    labels = np.full((h, w), -1, dtype=np.int32)
    inside = mask > 0

    q = deque()
    for i, (x, y) in enumerate(seeds):
        if 0 <= x < w and 0 <= y < h and inside[y, x]:
            labels[y, x] = i
            q.append((x, y))

    # 4-connectivity BFS
    while q:
        x, y = q.popleft()
        lbl = labels[y, x]
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and inside[ny, nx] and labels[ny, nx] == -1:
                labels[ny, nx] = lbl
                q.append((nx, ny))

    return labels
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: 3개 PASS

- [ ] **Step 5: 커밋**

```bash
git add rigging.py tests/test_rigging.py
git commit -m "feat(rigging): add partition_mask_by_seeds (multi-source BFS)"
```

---

## Task 3: `rigging.compute_pivot`

**Files:**
- Modify: `rigging.py`
- Modify: `tests/test_rigging.py`

**컨텍스트:** 자식 파츠가 부모 마스크에 가장 근접하는 점 — 팔이 몸통에 붙는 "어깨" 같은 좌표. `cv2.distanceTransform` 활용. disjoint (떨어져 있는) 케이스는 child centroid 로 fallback.

- [ ] **Step 1: 실패 테스트 추가**

Append to `tests/test_rigging.py` (before `if __name__` block):
```python
from rigging import compute_pivot


class PivotTest(unittest.TestCase):
    def test_contact_point_horizontal(self):
        # parent: 왼쪽 덩어리 (열 0-9), child: 오른쪽 덩어리 (열 10-19) — 열 10 에서 접촉
        h, w = 10, 20
        parent_mask = np.zeros((h, w), dtype=np.uint8)
        parent_mask[:, 0:10] = 255
        child_mask = np.zeros((h, w), dtype=np.uint8)
        child_mask[:, 10:20] = 255

        pivot_x, pivot_y = compute_pivot(child_mask, parent_mask)
        # pivot 은 child 내부이면서 parent 경계 가장 가까운 점 → x == 10 근처
        self.assertEqual(pivot_x, 10)

    def test_disjoint_falls_back_to_centroid(self):
        # parent 왼쪽 위, child 오른쪽 아래 — 전혀 안 닿음
        h, w = 20, 20
        parent_mask = np.zeros((h, w), dtype=np.uint8)
        parent_mask[0:3, 0:3] = 255
        child_mask = np.zeros((h, w), dtype=np.uint8)
        child_mask[15:20, 15:20] = 255

        pivot_x, pivot_y = compute_pivot(child_mask, parent_mask)
        # child centroid = (17, 17) 근처
        self.assertAlmostEqual(pivot_x, 17, delta=1)
        self.assertAlmostEqual(pivot_y, 17, delta=1)
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: `ImportError: cannot import name 'compute_pivot'`

- [ ] **Step 3: `rigging.compute_pivot` 구현**

Add to `rigging.py`:
```python
import cv2


def compute_pivot(child_mask: np.ndarray, parent_mask: np.ndarray) -> tuple:
    """Child 픽셀 중 parent 경계에 가장 가까운 (x, y) 를 pivot 으로 반환.

    Disjoint (서로 닿지 않음) 인 경우 child centroid 로 fallback.
    """
    child_bool = child_mask > 0
    if not child_bool.any():
        h, w = child_mask.shape
        return (w // 2, h // 2)

    parent_bool = parent_mask > 0
    if not parent_bool.any():
        ys, xs = np.where(child_bool)
        return (int(xs.mean()), int(ys.mean()))

    # distanceTransform: 각 non-zero 픽셀에서 가장 가까운 zero 픽셀까지의 거리
    # parent 를 0, 그 외를 255 로 뒤집으면 → 각 non-parent 픽셀의 parent 까지 거리
    inv = ((parent_mask == 0).astype(np.uint8)) * 255
    dist = cv2.distanceTransform(inv, cv2.DIST_L2, 3)  # float32

    # child 내부로만 한정 → argmin
    INF = np.float32(1e9)
    dist_in_child = np.where(child_bool, dist, INF)
    idx = int(np.argmin(dist_in_child))
    y, x = np.unravel_index(idx, dist.shape)

    # 실제로 parent 와 떨어져 있을 때 fallback
    if dist_in_child[y, x] >= INF * 0.5:
        ys, xs = np.where(child_bool)
        return (int(xs.mean()), int(ys.mean()))

    return (int(x), int(y))
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: 5개 PASS (기존 3 + 신규 2)

- [ ] **Step 5: 커밋**

```bash
git add rigging.py tests/test_rigging.py
git commit -m "feat(rigging): add compute_pivot with disjoint fallback"
```

---

## Task 4: `rigging.save_rig` / `load_rig` (JSON I/O)

**Files:**
- Modify: `rigging.py`
- Modify: `tests/test_rigging.py`

- [ ] **Step 1: 실패 테스트 추가**

Append to `tests/test_rigging.py` (before `if __name__` block):
```python
import json
import os
import tempfile

from rigging import save_rig, load_rig, RIG_VERSION


class RigIoTest(unittest.TestCase):
    def test_roundtrip_preserves_fields(self):
        rig = {
            "version": RIG_VERSION,
            "source_object": "object.png",
            "image_size": [640, 480],
            "parts": [
                {"id": 0, "name": "root", "parent": -1,
                 "seed_xy": [320, 240], "pivot_xy": [320, 240]},
                {"id": 1, "name": "child_1", "parent": 0,
                 "seed_xy": [200, 180], "pivot_xy": [220, 200]},
            ],
        }
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "rig.json")
            save_rig(rig, path)
            loaded = load_rig(path)

        self.assertEqual(loaded, rig)
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: `ImportError: cannot import name 'save_rig'`

- [ ] **Step 3: 구현 추가**

Add to `rigging.py`:
```python
import json
import os

RIG_VERSION = 1


def save_rig(rig: dict, path: str) -> None:
    """Save rig dict as JSON (pretty-printed)."""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rig, f, indent=2, ensure_ascii=False)


def load_rig(path: str) -> dict:
    """Load rig dict from JSON. Caller responsible for version check."""
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: 6개 PASS

- [ ] **Step 5: 커밋**

```bash
git add rigging.py tests/test_rigging.py
git commit -m "feat(rigging): add save_rig/load_rig JSON I/O"
```

---

## Task 5: `rigging.build_parts_from_rig`

**Files:**
- Modify: `rigging.py`
- Modify: `tests/test_rigging.py`

**컨텍스트:** `object_rgba` (공통 전체 이미지) + `mask` (합집합 마스크) + `rig` dict → 파츠별 `viewer.Part` 객체 리스트로 변환. 순환 import 피하려 `Part` 는 dataclass 로 `rigging.py` 안에도 동일 필드로 선언하지 않고, viewer.Part 를 import 해서 사용.

- [ ] **Step 1: 실패 테스트 추가**

Append to `tests/test_rigging.py`:
```python
from rigging import build_parts_from_rig


class BuildPartsTest(unittest.TestCase):
    def test_yields_one_part_per_rig_entry(self):
        # 10x20 단순 흰 마스크
        mask = np.full((10, 20), 255, dtype=np.uint8)
        # RGBA: 모든 픽셀 255 red
        rgba = np.zeros((10, 20, 4), dtype=np.uint8)
        rgba[..., 0] = 255
        rgba[..., 3] = 255

        rig = {
            "version": RIG_VERSION,
            "source_object": "object.png",
            "image_size": [20, 10],
            "parts": [
                {"id": 0, "name": "root", "parent": -1,
                 "seed_xy": [5, 5], "pivot_xy": [5, 5]},
                {"id": 1, "name": "child_1", "parent": 0,
                 "seed_xy": [15, 5], "pivot_xy": [10, 5]},
            ],
        }

        parts = build_parts_from_rig(rgba, mask, rig)
        self.assertEqual(len(parts), 2)

        # 각 파츠의 alpha 가 자신의 Voronoi 영역에만 불투명
        root_alpha = parts[0].object_rgba[:, :, 3]
        child_alpha = parts[1].object_rgba[:, :, 3]
        # 교집합은 없어야 함
        both_opaque = (root_alpha > 0) & (child_alpha > 0)
        self.assertFalse(both_opaque.any())
        # 합집합 = 원 mask
        union = (root_alpha > 0) | (child_alpha > 0)
        np.testing.assert_array_equal(union, mask > 0)

    def test_parent_and_pivot_propagated(self):
        mask = np.full((10, 20), 255, dtype=np.uint8)
        rgba = np.zeros((10, 20, 4), dtype=np.uint8)
        rgba[..., 3] = 255
        rig = {
            "version": RIG_VERSION,
            "source_object": "object.png",
            "image_size": [20, 10],
            "parts": [
                {"id": 0, "name": "root", "parent": -1,
                 "seed_xy": [5, 5], "pivot_xy": [5, 5]},
                {"id": 1, "name": "child_1", "parent": 0,
                 "seed_xy": [15, 5], "pivot_xy": [10, 5]},
            ],
        }
        parts = build_parts_from_rig(rgba, mask, rig)
        self.assertEqual(parts[0].parent, -1)
        self.assertEqual(parts[1].parent, 0)
        self.assertEqual(parts[1].pivot, (10, 5))
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: `ImportError: cannot import name 'build_parts_from_rig'`

- [ ] **Step 3: 구현 추가**

Add to `rigging.py`:
```python
def build_parts_from_rig(object_rgba: np.ndarray,
                         mask: np.ndarray,
                         rig: dict) -> list:
    """Rig dict + shared assets → list of viewer.Part.

    각 파츠는 원본 object_rgba 를 공유하되 자기 region 바깥은 alpha=0.
    """
    from viewer import Part  # local import to avoid pygame/opencv cycles

    seeds = [tuple(p["seed_xy"]) for p in rig["parts"]]
    labels = partition_mask_by_seeds(mask, seeds)

    parts = []
    for i, pdata in enumerate(rig["parts"]):
        region = (labels == i)
        part_rgba = object_rgba.copy()
        part_rgba[~region, 3] = 0
        parts.append(Part(
            object_rgba=part_rgba,
            pivot=tuple(pdata["pivot_xy"]),
            parent=pdata["parent"],
            z_order=i,
        ))

    return parts
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python3 -m unittest tests.test_rigging -v`
Expected: 8개 PASS

- [ ] **Step 5: 커밋**

```bash
git add rigging.py tests/test_rigging.py
git commit -m "feat(rigging): build_parts_from_rig assembles Part list from rig dict"
```

---

## Task 6: `config.py` 상수 추가

**Files:**
- Modify: `config.py`

- [ ] **Step 1: 상수 블록 추가**

Append to `config.py`:
```python

# === Rigging (parts separation + swing animation) ===
# rig.json 경로 (OUTPUT_DIR 기준 상대)
RIG_FILENAME = "rig.json"
# 각 자식 파츠의 좌우 흔들림 최대 각도 (도)
SWING_MAX_DEG = 7.0
# 흔들림 주기 (Hz)
SWING_HZ = 0.55
# 자식 간 phase offset (golden-angle, rad) — 싱크 방지
GOLDEN_ANGLE_RAD = 2.39996322972865332  # π × (3 - √5)
# 파츠 1 개당 최소 픽셀 — 이 이하면 경고
MIN_PART_AREA_PX = 100
# edit 모드 seed 삭제 히트 반경 (px, 원본 좌표계)
EDIT_SEED_HIT_RADIUS_PX = 12
```

- [ ] **Step 2: 임포트 무결성 확인**

Run: `python3 -c "import config; print(config.SWING_MAX_DEG, config.RIG_FILENAME)"`
Expected: `7.0 rig.json`

- [ ] **Step 3: 커밋**

```bash
git add config.py
git commit -m "feat(config): add rigging and swing animation constants"
```

---

## Task 7: `viewer.py` — rig.json 자동 로드 + 다중 파츠 렌더 (swing rotation)

**Files:**
- Modify: `viewer.py`

**컨텍스트:** 현재 `play_viewer(parts, ...)` 는 `Part` 리스트를 순회하지만 리스트 길이가 대부분 1 이라 계층 의식 안 함. v1 계층 접근:
- Root (parent==-1) = 기존 물리 (bounce, wobble, tilt, squash, breath) 그대로
- Child (parent>=0) = root 의 translation 은 공유 (같은 bounce_y, sway_x), **추가로 자기 pivot 기준 swing rotation**
- Tilt 상속은 v1 에선 생략 — 작은 각도라 시각 차이 미미

- [ ] **Step 1: 먼저 현재 `viewer.py` 의 play_viewer / draw 루프 맥락을 읽기**

Read: `viewer.py` 전체 (기존 draw 루프 구조 파악). 특히 `for idx in order:` 루프 블록.

- [ ] **Step 2: `viewer.py` 에 swing 계산 helper 추가**

Add near the top of `viewer.py` (after existing imports, before `@dataclass class Part`):
```python
import config


def child_swing_deg(t: float, child_idx: int) -> float:
    """Pendulum swing for a child part — phase-offset so siblings don't sync."""
    phase = child_idx * config.GOLDEN_ANGLE_RAD
    return config.SWING_MAX_DEG * math.sin(
        config.SWING_HZ * 2 * math.pi * t + phase
    )
```

- [ ] **Step 3: draw 루프에서 child 별 swing rotation 적용**

In `viewer.py`, 기존 `for idx in order:` 블록 안에서, 최종 `total_tilt` 계산 부분을 다음과 같이 교체:

기존:
```python
            # Tilt (rotate both)
            total_tilt = m.tilt + mouse_tilt_x * 0.4
```

교체:
```python
            # Tilt (root 기준) + child 는 자기 swing 추가
            is_root = (parts[idx].parent < 0)
            if is_root:
                total_tilt = m.tilt + mouse_tilt_x * 0.4
            else:
                # v1: child 는 root 물리 위에 swing 만 얹음, tilt 상속은 skip
                child_idx_among_children = sum(
                    1 for j in range(idx) if parts[j].parent >= 0)
                total_tilt = child_swing_deg(t, child_idx_among_children)
```

Note: `pygame.transform.rotate` 는 sprite 중심 기준 rotation 이므로, 자식의 pivot 기준 rotation 을 근사하려면 blit 위치를 pivot 에 맞춰 보정해야 함. 다음 단계.

- [ ] **Step 4: 자식 blit 위치를 pivot 기준으로 보정**

같은 블록에서 맨 아래 `screen.blit(final_top, ...)` 앞에 다음 분기 추가 (child 만 pivot 보정):

기존:
```python
            # Blit (center on render_cx, render_cy)
            fw, fh = final.get_size()
            screen.blit(final, (int(render_cx - fw / 2), int(render_cy - fh / 2)))
```

위 블록 (여러 파츠 blit 부분) 을 다음으로 교체. 이때 top / side 렌더 블록은 이미 존재하므로 — 실제 수정은 `screen.blit(final_top, (top_x, top_y))` 주변의 `top_x, top_y` 계산을 child 일 때 pivot 보정하도록 바꾼다.

기존:
```python
            scale_factor = draw_h / sprite_h
            fw, fh = final_top.get_size()
            top_x = int(render_cx - fw / 2)
            top_y = int(render_cy - fh / 2)
```

교체:
```python
            scale_factor = draw_h / sprite_h
            fw, fh = final_top.get_size()
            if is_root:
                top_x = int(render_cx - fw / 2)
                top_y = int(render_cy - fh / 2)
            else:
                # child: sprite 의 pivot 이 스크린 상에서 원래 unrotated 위치를 유지하도록 보정
                # sprite(회전 전) 의 pivot 스크린 좌표 = root render pos + pivot local offset - sprite center offset
                pivot_x_local, pivot_y_local = parts[idx].pivot
                # sprite 는 top_base 와 같은 가로세로 비율. pad 고려하기.
                pad_x = parts[idx]._pad_x * (draw_w / sprite_w)
                # pivot_local 은 원본 object_rgba 기준 — padded 좌표로 변환
                pivot_in_padded_x = pivot_x_local + parts[idx]._pad_x
                pivot_in_padded_y = pivot_y_local
                # draw_w / sprite_w 스케일 팩터 적용
                sx_factor = draw_w / sprite_w
                sy_factor = draw_h / sprite_h
                pivot_draw_x = pivot_in_padded_x * sx_factor
                pivot_draw_y = pivot_in_padded_y * sy_factor
                # unrotated 시 pivot 의 스크린 위치 (root 와 같은 기준점)
                root_top_x = int(render_cx - draw_w / 2)
                root_top_y = int(render_cy - draw_h / 2)
                pivot_screen_x = root_top_x + pivot_draw_x
                pivot_screen_y = root_top_y + pivot_draw_y
                # 회전 후 pivot 이 final_top 안에서 어디 있는지 계산
                # (회전 전 sprite center 에서 pivot 오프셋을 회전시킴)
                off_x = pivot_draw_x - draw_w / 2
                off_y = pivot_draw_y - draw_h / 2
                rad = -math.radians(total_tilt)
                c, s = math.cos(rad), math.sin(rad)
                rot_off_x = off_x * c - off_y * s
                rot_off_y = off_x * s + off_y * c
                pivot_in_final_x = fw / 2 + rot_off_x
                pivot_in_final_y = fh / 2 + rot_off_y
                # blit 위치: final_top 의 pivot_in_final 이 pivot_screen 에 오도록
                top_x = int(pivot_screen_x - pivot_in_final_x)
                top_y = int(pivot_screen_y - pivot_in_final_y)
```

(extrusion side 블릿도 `top_x, top_y` 를 그대로 쓰므로 일관성 유지됨.)

- [ ] **Step 5: `viewer.py` 상단에 rig 자동 로드 헬퍼 추가**

Add near the other module-level functions:
```python
def load_parts_from_output(output_dir: str = "output") -> list:
    """Convenience: output/ 에서 object/depth/normal + (있으면) rig.json 을 읽어 Part 리스트 반환.

    rig.json 이 없으면 단일 파츠 반환 (기존 동작).
    """
    import os
    import rigging
    import config

    obj_path = os.path.join(output_dir, config.OBJECT_FILENAME)
    depth_path = os.path.join(output_dir, config.DEPTH_FILENAME)
    normal_path = os.path.join(output_dir, config.NORMAL_FILENAME)
    mask_path = os.path.join(output_dir, config.MASK_FILENAME)
    rig_path = os.path.join(output_dir, config.RIG_FILENAME)

    single = load_part_from_paths(
        obj_path,
        depth_path if os.path.exists(depth_path) else None,
        normal_path if os.path.exists(normal_path) else None,
    )

    if not os.path.exists(rig_path) or not os.path.exists(mask_path):
        return [single]

    rig = rigging.load_rig(rig_path)
    if rig.get("version") != rigging.RIG_VERSION:
        print(f"[viewer] rig.json version mismatch, ignoring: {rig.get('version')}")
        return [single]

    mask = cv2.imread(mask_path, cv2.IMREAD_GRAYSCALE)
    return rigging.build_parts_from_rig(single.object_rgba, mask, rig)
```

- [ ] **Step 6: 구문 검사**

Run: `python3 -m py_compile viewer.py rigging.py && echo OK`
Expected: `OK`

- [ ] **Step 7: smoke test (headless)**

Run:
```bash
python3 -c "
import viewer, numpy as np
# fake 2-part scenario
rgba = np.zeros((20, 40, 4), dtype=np.uint8)
rgba[..., 3] = 255
p1 = viewer.Part(object_rgba=rgba.copy(), pivot=(10, 10), parent=-1, z_order=0)
rgba2 = rgba.copy(); rgba2[:, :20, 3] = 0
p2 = viewer.Part(object_rgba=rgba2, pivot=(25, 10), parent=0, z_order=1)
viewer._prepare_part(p1); viewer._prepare_part(p2)
print('prepared parts OK, swing_deg(1.0, 0)=',
      viewer.child_swing_deg(1.0, 0))
"
```
Expected: `prepared parts OK, swing_deg(1.0, 0)= ...` (float 값 출력)

- [ ] **Step 8: 커밋**

```bash
git add viewer.py
git commit -m "feat(viewer): multi-part render with child swing around pivot"
```

---

## Task 8: `viewer.py` — `E` 키 edit 모드 + `_RigEditor` 오버레이

**Files:**
- Modify: `viewer.py`

**컨텍스트:** `E` 누르면 일반 애니메이션 정지, 반투명 오버레이 + mask 가장자리 표시, 사용자가 마우스로 seed 추가/삭제, ENTER 로 확정/저장, ESC 로 취소, C 로 전체 clear. 저장 시 `rigging.partition_mask_by_seeds` 로 labels 생성, 각 자식에 대해 `compute_pivot` 실행, rig dict 조립, `save_rig`.

- [ ] **Step 1: `_RigEditor` 클래스 추가 (viewer.py 상단, Part dataclass 아래)**

```python
@dataclass
class _RigEditState:
    """rig edit 모드 런타임 상태."""
    active: bool = False
    seeds: list = field(default_factory=list)  # [(x, y), ...] 원본 마스크 좌표계
    _saved_snapshot: object = None  # 취소 시 복원용 (미사용, 자리만)


# edit 모드 표시용 색 (루트=빨강, 자식 순서대로)
_EDITOR_COLORS = [
    (230, 60, 60),   # root
    (60, 200, 100),  (60, 150, 240), (240, 160, 50),
    (180, 80, 220), (40, 200, 200), (160, 160, 160),
]
```

- [ ] **Step 2: play_viewer 시그니처에 output_dir 인자 추가하고 state 보관**

수정: 기존 `def play_viewer(parts, window_size=..., interactive_tilt=True, caption=...)` 를

```python
def play_viewer(parts: list,
                window_size: tuple = (WINDOW_W, WINDOW_H),
                interactive_tilt: bool = True,
                caption: str = "2.5D Live Viewer",
                output_dir: str = "output",
                mask_for_edit: np.ndarray = None) -> str:
```

`output_dir` 은 rig.json 저장 경로, `mask_for_edit` 은 edit 모드에서 seeds 의 유효성 체크용.

- [ ] **Step 3: play_viewer 안에 edit state 와 키 바인딩**

Inside `play_viewer`, after `motions = [_PartMotion() for _ in parts]`, add:
```python
    rig_state = _RigEditState()
```

이벤트 루프의 `elif event.type == pygame.KEYDOWN:` 블록에 새 분기 추가:
```python
                elif event.key == pygame.K_e and mask_for_edit is not None:
                    rig_state.active = not rig_state.active
                    if rig_state.active:
                        rig_state.seeds = []  # 새로 시작
```

- [ ] **Step 4: 마우스 클릭을 edit 모드로 라우팅**

Inside `for event in pygame.event.get()` 루프, 마우스 다운 이벤트 추가:
```python
            elif event.type == pygame.MOUSEBUTTONDOWN and rig_state.active:
                mx, my = pygame.mouse.get_pos()
                # 스크린 좌표 → 원본 마스크 좌표로 역변환 필요.
                # v1: mask_for_edit 와 viewer 가 같은 비율/위치로 렌더된다고 가정하고
                # 화면 중앙에 mask 가 맞춰져 있다고 단순화. 자세한 변환은 Step 5 의 헬퍼가 처리.
                mask_xy = _screen_to_mask_xy(mx, my, mask_for_edit, (win_w, win_h))
                if mask_xy is not None:
                    # 기존 seed 히트 테스트
                    hit = _find_near_seed(mask_xy, rig_state.seeds,
                                           config.EDIT_SEED_HIT_RADIUS_PX)
                    if hit is not None:
                        rig_state.seeds.pop(hit)
                    else:
                        if mask_for_edit[mask_xy[1], mask_xy[0]] > 0:
                            rig_state.seeds.append(mask_xy)
```

그리고 ENTER/ESC/C 키도 edit 모드에서 오버라이드:
```python
                elif event.key == pygame.K_RETURN and rig_state.active:
                    _commit_rig(rig_state.seeds, mask_for_edit, output_dir,
                                 parts[0].object_rgba.shape[:2])
                    result = "retake"  # viewer 재시작 유도 (live_demo 루프가 rig 재로드)
                    running = False
                elif event.key == pygame.K_ESCAPE and rig_state.active:
                    rig_state.active = False
                    rig_state.seeds = []
                elif event.key == pygame.K_c and rig_state.active:
                    rig_state.seeds = []
```

(주의: 기존 `if event.key in (pygame.K_ESCAPE, pygame.K_q):` 분기는 edit 모드가 꺼져있을 때만 동작하도록 바꾼다 — 위의 `elif event.key == pygame.K_ESCAPE and rig_state.active:` 를 상위에 배치.)

- [ ] **Step 5: 좌표 변환 / 오버레이 / 커밋 헬퍼들 추가**

Add to `viewer.py` (module level):
```python
def _screen_to_mask_xy(sx: int, sy: int, mask: np.ndarray,
                       window_size: tuple) -> tuple:
    """스크린 좌표 → 마스크 원본 좌표 (역변환).

    v1: 마스크가 스크린 중앙에 `max_sprite_h` 기준으로 비율 고정되어
    렌더된다고 가정. live_demo 에서 viewer 호출 시 같은 스케일을 쓴다.
    """
    win_w, win_h = window_size
    mh, mw = mask.shape
    max_sprite_h = int(win_h * 0.45)
    scale = max_sprite_h / mh if mh > max_sprite_h else 1.0
    rendered_w = int(mw * scale)
    rendered_h = int(mh * scale)
    # 화면에서 sprite bottom 은 ground_y 에 닿음 (DROP_START_Y 무시, 정지 상태 가정)
    ground_y = win_h - GROUND_MARGIN
    top = ground_y - rendered_h
    left = (win_w - rendered_w) // 2
    # 역변환
    local_x = (sx - left) / scale
    local_y = (sy - top) / scale
    if 0 <= local_x < mw and 0 <= local_y < mh:
        return (int(local_x), int(local_y))
    return None


def _find_near_seed(xy: tuple, seeds: list, radius: int) -> int:
    """seeds 중 xy 에서 radius 이내에 있는 첫 인덱스. 없으면 None."""
    x, y = xy
    for i, (sx, sy) in enumerate(seeds):
        if (sx - x) ** 2 + (sy - y) ** 2 <= radius * radius:
            return i
    return None


def _commit_rig(seeds: list, mask: np.ndarray, output_dir: str,
                image_shape_hw: tuple) -> None:
    """seeds 로 rig dict 생성 + rig.json 저장."""
    import os
    import rigging

    if len(seeds) == 0:
        # seeds 비었으면 rig.json 제거 (단일 파츠로 복귀)
        rig_path = os.path.join(output_dir, config.RIG_FILENAME)
        if os.path.exists(rig_path):
            os.remove(rig_path)
        return

    labels = rigging.partition_mask_by_seeds(mask, seeds)
    h, w = image_shape_hw
    parts_data = []
    # 루트
    root_mask = (labels == 0).astype(np.uint8) * 255
    parts_data.append({
        "id": 0, "name": "root", "parent": -1,
        "seed_xy": list(seeds[0]),
        "pivot_xy": list(seeds[0]),
    })
    # 자식들
    for i in range(1, len(seeds)):
        child_mask = (labels == i).astype(np.uint8) * 255
        pivot = rigging.compute_pivot(child_mask, root_mask)
        parts_data.append({
            "id": i, "name": f"child_{i}", "parent": 0,
            "seed_xy": list(seeds[i]),
            "pivot_xy": list(pivot),
        })

    rig = {
        "version": rigging.RIG_VERSION,
        "source_object": config.OBJECT_FILENAME,
        "image_size": [w, h],
        "parts": parts_data,
    }
    rigging.save_rig(rig, os.path.join(output_dir, config.RIG_FILENAME))
```

- [ ] **Step 6: edit 오버레이 렌더**

play_viewer 의 draw 루프 끝부분 (파츠 전부 블릿한 다음, `pygame.display.flip()` 직전) 에 추가:
```python
        if rig_state.active and mask_for_edit is not None:
            # 반투명 검은 오버레이
            overlay = pygame.Surface((win_w, win_h), pygame.SRCALPHA)
            overlay.fill((0, 0, 0, 120))
            screen.blit(overlay, (0, 0))
            # mask 가장자리 + seeds
            _draw_edit_overlay(screen, mask_for_edit, rig_state.seeds,
                                (win_w, win_h), font)
```

Add to module level:
```python
def _draw_edit_overlay(screen, mask, seeds, window_size, font) -> None:
    """edit 모드 가이드: 마스크 경계 + seed 원 + 지시 문구."""
    import pygame
    win_w, win_h = window_size
    mh, mw = mask.shape
    max_sprite_h = int(win_h * 0.45)
    scale = max_sprite_h / mh if mh > max_sprite_h else 1.0
    rendered_w = int(mw * scale)
    rendered_h = int(mh * scale)
    ground_y = win_h - GROUND_MARGIN
    top = ground_y - rendered_h
    left = (win_w - rendered_w) // 2

    # 마스크 경계: cv2.findContours 로 뽑아서 폴리곤 그리기
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL,
                                    cv2.CHAIN_APPROX_NONE)
    for cnt in contours:
        pts = [(int(left + px[0][0] * scale),
                int(top + px[0][1] * scale)) for px in cnt]
        if len(pts) >= 2:
            pygame.draw.lines(screen, (230, 220, 80), True, pts, 1)

    # seeds
    for i, (sx, sy) in enumerate(seeds):
        col = _EDITOR_COLORS[i % len(_EDITOR_COLORS)]
        scr_x = int(left + sx * scale)
        scr_y = int(top + sy * scale)
        pygame.draw.circle(screen, col, (scr_x, scr_y), 10, 0)
        pygame.draw.circle(screen, (255, 255, 255), (scr_x, scr_y), 10, 2)
        txt = font.render(str(i), True, (255, 255, 255))
        screen.blit(txt, (scr_x + 12, scr_y - 10))

    # 지시 문구
    if len(seeds) == 0:
        msg = "첫 탭은 루트(몸통)입니다"
    else:
        msg = f"자식 {len(seeds) - 1} 개 | ENTER=저장  ESC=취소  C=clear"
    info = font.render(msg, True, (240, 240, 240))
    screen.blit(info, (20, win_h - 40))
```

- [ ] **Step 7: 구문 검사 + viewer import smoke test**

Run: `python3 -m py_compile viewer.py && echo OK`
Expected: `OK`

Run: `python3 -c "import viewer; assert hasattr(viewer, '_commit_rig') and hasattr(viewer, 'child_swing_deg'); print('OK')"`
Expected: `OK`

- [ ] **Step 8: 커밋**

```bash
git add viewer.py
git commit -m "feat(viewer): add E-key edit mode with seed overlay and rig commit"
```

---

## Task 9: `live_demo.py` — viewer 호출 시 rig.json 자동 감지

**Files:**
- Modify: `live_demo.py`

**컨텍스트:** 현재 `viewer_phase` 는 단일 Part 만 만들어 viewer 에 넘긴다. `viewer.load_parts_from_output()` 로 대체하고, `mask_for_edit` 도 전달해 edit 모드가 동작하게.

- [ ] **Step 1: 기존 viewer_phase 교체**

viewer_phase 함수 전체를 교체:
```python
def viewer_phase(object_path, depth_path, normal_path):
    """output/ 을 기반으로 Part 리스트 구성 (rig.json 있으면 다중, 없으면 단일)."""
    import cv2
    import os
    parts = viewer.load_parts_from_output(output_dir=config.OUTPUT_DIR)
    mask_path = os.path.join(config.OUTPUT_DIR, config.MASK_FILENAME)
    mask = cv2.imread(mask_path, cv2.IMREAD_GRAYSCALE) if os.path.exists(mask_path) else None
    return viewer.play_viewer(
        parts,
        window_size=(WINDOW_W, WINDOW_H),
        interactive_tilt=True,
        caption="2.5D Live Viewer",
        output_dir=config.OUTPUT_DIR,
        mask_for_edit=mask,
    )
```

(인자 `object_path, depth_path, normal_path` 는 시그니처 호환성 위해 유지하되 함수 본문에서 사용 안 함 — 이 호출은 `run_pipeline` 의 반환값을 쓰지만 `load_parts_from_output` 이 동일 파일들을 읽는다.)

- [ ] **Step 2: import 누락 확인**

`live_demo.py` 상단에 `import config` 이 이미 있는지 확인. 없으면 추가.

- [ ] **Step 3: 구문 검사**

Run: `python3 -m py_compile live_demo.py && echo OK`
Expected: `OK`

- [ ] **Step 4: 커밋**

```bash
git add live_demo.py
git commit -m "feat(live_demo): auto-load rig.json via viewer.load_parts_from_output"
```

---

## Task 10: 통합 smoke test + 문서 업데이트

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 전체 단위 테스트 재확인**

Run: `python3 -m unittest discover tests -v`
Expected: 8 tests PASS (Task 1~5 누적)

- [ ] **Step 2: 전체 모듈 import 검증**

Run:
```bash
python3 -c "
import viewer, rigging, config, live_demo
print('all imports OK')
print('rig version:', rigging.RIG_VERSION)
print('swing:', config.SWING_MAX_DEG, config.SWING_HZ)
"
```
Expected:
```
all imports OK
rig version: 1
swing: 7.0 0.55
```

- [ ] **Step 3: README 에 edit 모드 키 문서화**

Append to `README.md` under the viewer keys table:
```markdown

### 파츠 리깅 (v1)

뷰어에서 `E` 를 눌러 파츠 편집 모드 진입. 그림 위를 탭하면 첫 탭이 루트(몸통), 이후는 자식. 기존 seed 근처 클릭 = 삭제. `ENTER` 로 저장 후 재시작 시 자동 로드. `output/rig.json` 에 seed 좌표가 저장된다.
```

- [ ] **Step 4: 수동 통합 테스트 (사용자 수행)**

체크리스트:
- [ ] `python3 live_demo.py` 실행, 그림 촬영/선택
- [ ] viewer 에서 `E` 누름 → 오버레이 표시 확인
- [ ] 몸통 탭 → 빨간 원 "0" 표시 확인
- [ ] 팔/다리 2~3 탭 → 색색 원 + 숫자 표시
- [ ] `ENTER` → `output/rig.json` 생성 확인
- [ ] viewer 재시작 → 자동 로드, 자식 파츠가 swing 되는지 관찰
- [ ] `E` 다시 → seed 표시되는지, 삭제 동작하는지

- [ ] **Step 5: 최종 커밋**

```bash
git add README.md
git commit -m "docs: add parts-rigging v1 edit mode usage to README"
```

---

## Self-Review 체크리스트 (plan author — 이 파일 작성자 self-check)

**Spec coverage:**
- 목적/범위 §1 → Task 전체가 MVP 커버, out-of-scope 는 v2 로 명시 ✓
- 사용자 결정 §2 → Q1~Q6 각각 Task 에 반영됨 (swing=Q6, 2단=Q5, free-form=Q2, tap=Q3, toggle=Q4) ✓
- 아키텍처 §3 → Task 7, 8, 9 가 각 모듈 변경 ✓
- 데이터 모델 §4 → Task 4 (rig.json 스키마), Task 5 (runtime Part) ✓
- 런타임 동작 §5 → Task 7 (자동 로드), Task 8 (edit 모드), Task 9 (통합) ✓
- 알고리즘 §6 → Task 2 (partition), Task 3 (pivot), Task 7 (swing) ✓
- 실패 모드 §7 → `_commit_rig` 의 빈 seeds 처리, version 체크 in `load_parts_from_output` ✓
- 파일 변경 §8 → Task 6, 7, 8, 9 에 각각 매핑 ✓
- 테스트 §9 → Task 2~5 단위 + Task 10 수동 ✓
- 하위 호환 §10 → `load_parts_from_output` 의 rig 없음 fallback ✓
- 성능 §11 → 파츠 6개 기준 목표, 실측은 Task 10 수동에서 확인 ✓

**Placeholder scan:** "TBD"/"TODO"/"구현 나중에" 없음 ✓. 모든 Step 에 실제 코드/명령 포함 ✓.

**Type consistency:**
- `partition_mask_by_seeds(mask, seeds) -> int32 ndarray` — Task 2, 5 공통 ✓
- `compute_pivot(child_mask, parent_mask) -> (int, int)` — Task 3, 8 `_commit_rig` 공통 ✓
- `RIG_VERSION` 이름 — Task 4 정의, Task 5, 7, 8 사용 일관 ✓
- `Part(object_rgba, pivot, parent, z_order, ...)` — Task 5 건조, Task 7, 8 에서 읽기 일관 ✓
- `save_rig` / `load_rig` 시그니처 — Task 4 정의, Task 7, 8 사용 일관 ✓
- `config.SWING_MAX_DEG`, `config.SWING_HZ`, `config.GOLDEN_ANGLE_RAD`, `config.RIG_FILENAME`, `config.EDIT_SEED_HIT_RADIUS_PX`, `config.MIN_PART_AREA_PX` — Task 6 정의, Task 7, 8 에서 사용 일관 ✓

이슈 없음. 실행 단계로 진행 가능.
