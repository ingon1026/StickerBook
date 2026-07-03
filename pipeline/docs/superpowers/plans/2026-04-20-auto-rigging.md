# Auto-Rigging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically rig hand-drawn figures into root + limb parts so viewer shows idle pendulum swing with zero user interaction, while leaving simple blobs (pig head, flower) as single sprites.

**Architecture:** Add `auto_rig.py` that skeletonizes the mask, counts branch endpoints, generates seeds for the existing `rigging.partition_mask_by_seeds` pipeline, and writes `rig.json`. `live_demo.py` calls it at the end of `run_pipeline`. Viewer and rigging modules are unchanged — they already support multi-part via `rig.json` detection.

**Tech Stack:** scikit-image (skeletonize), OpenCV, NumPy, existing `rigging.py`

**Reference spec:** `docs/superpowers/specs/2026-04-20-auto-rigging-design.md`

---

## File Structure

```
drawing-2p5d/
├── auto_rig.py                      (NEW) Skeleton analysis + seed generation + rig.json write
├── live_demo.py                     (MODIFY) Call try_auto_rig at end of run_pipeline
├── config.py                        (MODIFY) Add 3 tuning constants
├── requirements.txt                 (MODIFY) Add scikit-image
├── tests/
│   └── test_auto_rig.py             (NEW) Unit tests for skeleton/seed logic
├── rigging.py                       (UNCHANGED) Reused via partition_mask_by_seeds
└── viewer.py                        (UNCHANGED) Reused via rig.json detection
```

Each file has one responsibility. `auto_rig.py` is self-contained (pure functions + one I/O wrapper) so it can be unit-tested without touching the viewer or camera pipeline.

---

## Task 1: Add scikit-image dependency

**Files:**
- Modify: `requirements.txt`

- [ ] **Step 1: Check current requirements**

Run: `cat requirements.txt`
Expected output includes: `mediapipe>=0.10.9`, `opencv-python>=4.8.0`, `numpy>=1.24.0`, `pygame>=2.5.0`

- [ ] **Step 2: Append scikit-image**

Append one line to `requirements.txt`:

```
scikit-image>=0.22.0
```

- [ ] **Step 3: Install the dependency**

Run: `pip install scikit-image>=0.22.0`
Expected: installs successfully, no downgrade warnings for numpy/opencv.

- [ ] **Step 4: Verify import works**

Run: `python3 -c "from skimage.morphology import skeletonize; import numpy as np; print(skeletonize(np.zeros((5,5), dtype=bool)).shape)"`
Expected output: `(5, 5)`

- [ ] **Step 5: Commit**

```bash
git add requirements.txt
git commit -m "deps: add scikit-image for auto-rigging skeletonize"
```

---

## Task 2: Add config constants

**Files:**
- Modify: `config.py`

- [ ] **Step 1: Append constants to config.py**

Append to `config.py` after the existing `# === Rigging ===` block (around line 67):

```python
# === Auto-Rigging (skeleton-based) ===
# skeleton 가지 중 길이(픽셀)가 이 값 미만이면 노이즈로 제거
AUTO_RIG_MIN_BRANCH_PX = 15
# endpoint 개수가 이 값 미만이면 rig 하지 않고 단일 파츠로 진행
AUTO_RIG_MIN_ENDPOINTS = 3
# endpoint 개수 상한 — 초과하면 가지 길이 상위 N 개만 사용
AUTO_RIG_MAX_ENDPOINTS = 8
```

- [ ] **Step 2: Verify constants load**

Run: `python3 -c "import config; print(config.AUTO_RIG_MIN_BRANCH_PX, config.AUTO_RIG_MIN_ENDPOINTS, config.AUTO_RIG_MAX_ENDPOINTS)"`
Expected output: `15 3 8`

- [ ] **Step 3: Commit**

```bash
git add config.py
git commit -m "config: add auto-rigging tuning constants"
```

---

## Task 3: Create auto_rig module with skeletonize wrapper

**Files:**
- Create: `auto_rig.py`
- Create: `tests/test_auto_rig.py`

- [ ] **Step 1: Write failing test for skeletonize wrapper**

Create `tests/test_auto_rig.py`:

```python
"""Unit tests for auto_rig.py (skeleton-based automatic parts rigging)."""
import unittest
import numpy as np

from auto_rig import skeletonize_mask


class SkeletonizeTest(unittest.TestCase):
    def test_empty_mask_returns_empty_skeleton(self):
        mask = np.zeros((20, 20), dtype=np.uint8)
        skel = skeletonize_mask(mask)
        self.assertEqual(skel.shape, mask.shape)
        self.assertEqual(skel.dtype, bool)
        self.assertFalse(skel.any())

    def test_horizontal_bar_reduces_to_line(self):
        # 20x40 mask with horizontal bar 5 rows tall spanning columns 5..34
        mask = np.zeros((20, 40), dtype=np.uint8)
        mask[8:13, 5:35] = 255
        skel = skeletonize_mask(mask)
        # Skeleton must be non-empty and strictly narrower than original
        self.assertTrue(skel.any())
        self.assertLess(skel.sum(), (mask > 0).sum())

    def test_accepts_uint8_255_mask(self):
        mask = np.zeros((10, 10), dtype=np.uint8)
        mask[2:8, 4:6] = 255
        skel = skeletonize_mask(mask)
        self.assertEqual(skel.dtype, bool)
        self.assertTrue(skel.any())


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'auto_rig'`

- [ ] **Step 3: Create auto_rig.py with skeletonize wrapper**

Create `auto_rig.py`:

```python
"""Auto-rigging for 2.5D hand-drawn puppets.

Analyzes mask skeleton (medial axis) to detect limb endpoints and auto-generate
seeds for the existing rigging pipeline. Writes output/rig.json on success, or
removes it (forcing single-part fallback) on failure.

See docs/superpowers/specs/2026-04-20-auto-rigging-design.md
"""
import os
from typing import Optional

import numpy as np


def skeletonize_mask(mask: np.ndarray) -> np.ndarray:
    """Reduce a binary mask to its 1-pixel-wide medial axis.

    Args:
        mask: (H, W) uint8 binary mask, non-zero = foreground.

    Returns:
        (H, W) bool skeleton. Empty (all False) if input has no foreground
        or skimage is unavailable.
    """
    if mask is None or mask.size == 0:
        return np.zeros((0, 0), dtype=bool)
    if not (mask > 0).any():
        return np.zeros(mask.shape, dtype=bool)

    try:
        from skimage.morphology import skeletonize
    except ImportError:
        # Graceful fallback: no skeleton available, caller will skip rig
        return np.zeros(mask.shape, dtype=bool)

    binary = mask > 0
    return skeletonize(binary).astype(bool)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig -v`
Expected: `Ran 3 tests ... OK`

- [ ] **Step 5: Commit**

```bash
git add auto_rig.py tests/test_auto_rig.py
git commit -m "feat(auto-rig): add skeletonize_mask wrapper with ImportError fallback"
```

---

## Task 4: Detect endpoints and branching points in skeleton

**Files:**
- Modify: `auto_rig.py`
- Modify: `tests/test_auto_rig.py`

- [ ] **Step 1: Write failing tests for neighbor classification**

Append to `tests/test_auto_rig.py` (before `if __name__ == "__main__"`):

```python
from auto_rig import find_skeleton_nodes


class SkeletonNodesTest(unittest.TestCase):
    def test_single_line_has_two_endpoints_no_branches(self):
        # Horizontal skeleton line: row 5, cols 2..8
        skel = np.zeros((10, 10), dtype=bool)
        skel[5, 2:9] = True
        endpoints, branches = find_skeleton_nodes(skel)
        self.assertEqual(sorted(endpoints), [(2, 5), (8, 5)])
        self.assertEqual(branches, [])

    def test_plus_shape_has_four_endpoints_one_branch(self):
        # + shape: horizontal row 5 cols 2..8, vertical col 5 rows 2..8
        skel = np.zeros((10, 10), dtype=bool)
        skel[5, 2:9] = True
        skel[2:9, 5] = True
        endpoints, branches = find_skeleton_nodes(skel)
        # 4 tips: (2,5), (8,5), (5,2), (5,8)
        self.assertEqual(sorted(endpoints), [(2, 5), (5, 2), (5, 8), (8, 5)])
        # 1 branching point at the cross center
        self.assertEqual(branches, [(5, 5)])

    def test_empty_skeleton_returns_empty_lists(self):
        skel = np.zeros((5, 5), dtype=bool)
        endpoints, branches = find_skeleton_nodes(skel)
        self.assertEqual(endpoints, [])
        self.assertEqual(branches, [])
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.SkeletonNodesTest -v`
Expected: FAIL with `ImportError: cannot import name 'find_skeleton_nodes'`

- [ ] **Step 3: Implement find_skeleton_nodes**

Append to `auto_rig.py`:

```python
def find_skeleton_nodes(skel: np.ndarray) -> tuple[list[tuple[int, int]], list[tuple[int, int]]]:
    """Classify skeleton pixels by 8-neighbor count.

    A skeleton pixel with exactly 1 skeleton neighbor is an endpoint (limb tip).
    A skeleton pixel with 3+ skeleton neighbors is a branching point (joint).
    2-neighbor pixels are interior (ignored here).

    Args:
        skel: (H, W) bool skeleton from skeletonize_mask.

    Returns:
        (endpoints, branches) — each a list of (x, y) tuples.
    """
    if skel is None or skel.size == 0 or not skel.any():
        return [], []

    # Count 8-connected skeleton neighbors for each skeleton pixel.
    # 3x3 sum minus self gives neighbor count.
    s = skel.astype(np.uint8)
    # Manual 3x3 sum via shifts (avoids scipy dependency)
    h, w = s.shape
    padded = np.zeros((h + 2, w + 2), dtype=np.uint8)
    padded[1:-1, 1:-1] = s
    neighbor_count = (
        padded[0:-2, 0:-2] + padded[0:-2, 1:-1] + padded[0:-2, 2:] +
        padded[1:-1, 0:-2] +                       padded[1:-1, 2:] +
        padded[2:,   0:-2] + padded[2:,   1:-1] + padded[2:,   2:]
    )

    endpoints = []
    branches = []
    ys, xs = np.where(skel)
    for y, x in zip(ys, xs):
        nc = int(neighbor_count[y, x])
        if nc == 1:
            endpoints.append((int(x), int(y)))
        elif nc >= 3:
            branches.append((int(x), int(y)))

    return endpoints, branches
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.SkeletonNodesTest -v`
Expected: `Ran 3 tests ... OK`

- [ ] **Step 5: Commit**

```bash
git add auto_rig.py tests/test_auto_rig.py
git commit -m "feat(auto-rig): classify skeleton endpoints and branching points"
```

---

## Task 5: Prune short noise branches

**Files:**
- Modify: `auto_rig.py`
- Modify: `tests/test_auto_rig.py`

- [ ] **Step 1: Write failing test for branch-length measurement**

Append to `tests/test_auto_rig.py`:

```python
from auto_rig import measure_branch_length


class BranchLengthTest(unittest.TestCase):
    def test_straight_branch_length_matches_pixel_count(self):
        # Branch: endpoint (0, 5) to branching point (6, 5), horizontal row 5
        skel = np.zeros((10, 10), dtype=bool)
        skel[5, 0:7] = True
        skel[2:9, 6] = True  # vertical through (6,5) makes it a branching pt
        # endpoint at (0,5), branch pt at (6,5) — 7 pixels along (incl both ends)
        length = measure_branch_length(skel, endpoint=(0, 5))
        self.assertEqual(length, 7)

    def test_isolated_endpoint_in_tiny_skel(self):
        # Single-pixel skeleton → endpoint=0 neighbors actually, but guard
        skel = np.zeros((5, 5), dtype=bool)
        skel[2, 2] = True
        # A lone pixel has 0 neighbors so find_skeleton_nodes returns no endpoint
        # but if called directly, length is 1 (self).
        length = measure_branch_length(skel, endpoint=(2, 2))
        self.assertEqual(length, 1)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.BranchLengthTest -v`
Expected: FAIL with `ImportError: cannot import name 'measure_branch_length'`

- [ ] **Step 3: Implement measure_branch_length**

Append to `auto_rig.py`:

```python
def measure_branch_length(skel: np.ndarray, endpoint: tuple[int, int]) -> int:
    """Walk from an endpoint along the skeleton until a branching point or
    another endpoint is hit. Return the number of skeleton pixels visited
    (including the starting endpoint itself).

    Args:
        skel: (H, W) bool skeleton.
        endpoint: (x, y) start point — assumed to lie on skeleton.

    Returns:
        Branch length in pixels (>= 1).
    """
    h, w = skel.shape
    x0, y0 = endpoint
    if not (0 <= x0 < w and 0 <= y0 < h) or not skel[y0, x0]:
        return 0

    visited = np.zeros_like(skel, dtype=bool)
    visited[y0, x0] = True
    length = 1
    cx, cy = x0, y0

    # Walk until we hit a branching point (>=3 skeleton neighbors) or no
    # unvisited neighbor (another endpoint / dead end).
    while True:
        # count skeleton neighbors at (cx, cy)
        n_skel = 0
        next_px = None
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dx == 0 and dy == 0:
                    continue
                nx, ny = cx + dx, cy + dy
                if 0 <= nx < w and 0 <= ny < h and skel[ny, nx]:
                    n_skel += 1
                    if not visited[ny, nx] and next_px is None:
                        next_px = (nx, ny)

        # Stop at branching point (>=3 neighbors, except the very first step
        # from an endpoint which has 1 neighbor)
        if length > 1 and n_skel >= 3:
            break
        if next_px is None:
            break
        cx, cy = next_px
        visited[cy, cx] = True
        length += 1

    return length
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.BranchLengthTest -v`
Expected: `Ran 2 tests ... OK`

- [ ] **Step 5: Commit**

```bash
git add auto_rig.py tests/test_auto_rig.py
git commit -m "feat(auto-rig): measure skeleton branch length for noise pruning"
```

---

## Task 6: Pick root and top-N endpoints

**Files:**
- Modify: `auto_rig.py`
- Modify: `tests/test_auto_rig.py`

- [ ] **Step 1: Write failing test for analyze_skeleton integrator**

Append to `tests/test_auto_rig.py`:

```python
from auto_rig import analyze_skeleton


class AnalyzeSkeletonTest(unittest.TestCase):
    def test_stick_figure_returns_root_and_limb_endpoints(self):
        # Minimal stick figure:
        #   head dot at (15,5), neck to (15,15), arms (10,15)-(15,15)-(20,15),
        #   body (15,15)-(15,25), legs (15,25)-(10,32) and (15,25)-(20,32)
        skel = np.zeros((40, 30), dtype=bool)
        # head down the middle
        skel[5:15, 15] = True
        # arms horizontal
        skel[15, 10:21] = True
        # torso
        skel[15:26, 15] = True
        # legs diagonal (approximate by two vertical)
        skel[25:33, 10] = True
        skel[25:33, 20] = True
        # bridging so legs connect at hip (15,25)
        skel[25, 10:21] = True

        root_xy, endpoints = analyze_skeleton(skel, min_branch_px=2, max_endpoints=8)
        self.assertIsNotNone(root_xy)
        # At least 4 endpoints (head, arm-left, arm-right, leg-left, leg-right)
        self.assertGreaterEqual(len(endpoints), 4)
        self.assertLessEqual(len(endpoints), 8)

    def test_round_blob_skeleton_has_too_few_endpoints(self):
        # Skeleton of a 20x20 filled square collapses to a small cross — few endpoints
        skel = np.zeros((30, 30), dtype=bool)
        # Simulate a small 3-pixel dot (post-skeletonize shape of a round blob)
        skel[15, 15] = True
        root_xy, endpoints = analyze_skeleton(skel, min_branch_px=2, max_endpoints=8)
        # Not enough endpoints for limbs
        self.assertLess(len(endpoints), 3)

    def test_truncates_to_max_endpoints(self):
        # Star shape with 10 arms
        skel = np.zeros((41, 41), dtype=bool)
        cx, cy = 20, 20
        skel[cy, cx] = True
        for i, (dx, dy) in enumerate([
            (1, 0), (-1, 0), (0, 1), (0, -1),
            (1, 1), (-1, -1), (1, -1), (-1, 1),
            (2, 1), (-2, -1),
        ]):
            for step in range(1, 15):
                x = cx + dx * step
                y = cy + dy * step
                if 0 <= x < 41 and 0 <= y < 41:
                    skel[y, x] = True
        root_xy, endpoints = analyze_skeleton(skel, min_branch_px=2, max_endpoints=5)
        self.assertEqual(len(endpoints), 5)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.AnalyzeSkeletonTest -v`
Expected: FAIL with `ImportError: cannot import name 'analyze_skeleton'`

- [ ] **Step 3: Implement analyze_skeleton**

Append to `auto_rig.py`:

```python
def analyze_skeleton(skel: np.ndarray,
                     min_branch_px: int = 15,
                     max_endpoints: int = 8) -> tuple[Optional[tuple[int, int]], list[tuple[int, int]]]:
    """From a skeleton, return (root_xy, endpoints) suitable for rig seeds.

    Args:
        skel: (H, W) bool skeleton.
        min_branch_px: branches shorter than this are treated as noise and
            their endpoints are dropped.
        max_endpoints: keep at most this many endpoints (longest branches first).

    Returns:
        (root_xy, endpoints): root is the branching point closest to the
            skeleton centroid, or None if the skeleton has no structure.
            endpoints is a list of (x, y) tuples sorted by branch length desc.
    """
    if skel is None or skel.size == 0 or not skel.any():
        return None, []

    endpoints_raw, branches = find_skeleton_nodes(skel)

    # Filter endpoints by branch length
    scored = []
    for ep in endpoints_raw:
        length = measure_branch_length(skel, ep)
        if length >= min_branch_px:
            scored.append((length, ep))

    # Sort by length desc, truncate
    scored.sort(key=lambda t: t[0], reverse=True)
    endpoints = [ep for (_, ep) in scored[:max_endpoints]]

    # Choose root: branching point closest to skeleton centroid;
    # if no branches, use skeleton centroid snapped to nearest skeleton pixel
    ys, xs = np.where(skel)
    cx_c = float(xs.mean())
    cy_c = float(ys.mean())

    if branches:
        root_xy = min(branches, key=lambda p: (p[0] - cx_c) ** 2 + (p[1] - cy_c) ** 2)
    else:
        # No branching point — find nearest skeleton pixel to centroid
        dists2 = (xs - cx_c) ** 2 + (ys - cy_c) ** 2
        idx = int(np.argmin(dists2))
        root_xy = (int(xs[idx]), int(ys[idx]))

    return root_xy, endpoints
```

Also update the module's top-of-file import block — the existing import of `Optional` is already there. Confirm by reading `auto_rig.py`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.AnalyzeSkeletonTest -v`
Expected: `Ran 3 tests ... OK`

- [ ] **Step 5: Commit**

```bash
git add auto_rig.py tests/test_auto_rig.py
git commit -m "feat(auto-rig): analyze_skeleton returns root + top-N limb endpoints"
```

---

## Task 7: try_auto_rig end-to-end (write rig.json or cleanup)

**Files:**
- Modify: `auto_rig.py`
- Modify: `tests/test_auto_rig.py`

- [ ] **Step 1: Write failing test for try_auto_rig**

Append to `tests/test_auto_rig.py`:

```python
import os
import tempfile

from auto_rig import try_auto_rig


class TryAutoRigTest(unittest.TestCase):
    def _stick_figure_mask(self):
        """Create a stick-figure mask with a clear torso + 4 limbs."""
        mask = np.zeros((200, 150), dtype=np.uint8)
        # Torso (thick vertical band)
        mask[60:140, 70:85] = 255
        # Head (circle-ish on top)
        import cv2
        cv2.circle(mask, (77, 45), 20, 255, -1)
        # Arms: horizontal bars left & right from torso top
        mask[65:75, 30:70] = 255
        mask[65:75, 85:125] = 255
        # Legs: diagonals down-left and down-right
        for i in range(40):
            mask[140 + i, max(0, 70 - i)] = 255
            mask[140 + i, min(149, 85 + i)] = 255
            mask[140 + i, max(0, 70 - i - 1):max(0, 70 - i)] = 255
            mask[140 + i, min(149, 85 + i):min(150, 85 + i + 2)] = 255
        return mask

    def test_stick_figure_writes_rig_json(self):
        mask = self._stick_figure_mask()
        with tempfile.TemporaryDirectory() as d:
            rig = try_auto_rig(mask, output_dir=d)
            self.assertIsNotNone(rig)
            self.assertEqual(rig["version"], 1)
            self.assertGreaterEqual(len(rig["parts"]), 4)  # root + 3+ limbs
            self.assertTrue(os.path.exists(os.path.join(d, "rig.json")))

    def test_round_blob_returns_none_and_removes_rig(self):
        # Circular mask — skeleton too simple for rig
        import cv2
        mask = np.zeros((100, 100), dtype=np.uint8)
        cv2.circle(mask, (50, 50), 30, 255, -1)
        with tempfile.TemporaryDirectory() as d:
            # Pre-seed a stale rig.json
            stale_path = os.path.join(d, "rig.json")
            with open(stale_path, "w") as f:
                f.write('{"stale": true}')
            rig = try_auto_rig(mask, output_dir=d)
            self.assertIsNone(rig)
            self.assertFalse(os.path.exists(stale_path))

    def test_empty_mask_returns_none(self):
        mask = np.zeros((50, 50), dtype=np.uint8)
        with tempfile.TemporaryDirectory() as d:
            rig = try_auto_rig(mask, output_dir=d)
            self.assertIsNone(rig)
            self.assertFalse(os.path.exists(os.path.join(d, "rig.json")))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.TryAutoRigTest -v`
Expected: FAIL with `ImportError: cannot import name 'try_auto_rig'`

- [ ] **Step 3: Implement try_auto_rig**

Append to `auto_rig.py`:

```python
def try_auto_rig(mask: np.ndarray, output_dir: str) -> Optional[dict]:
    """Attempt to auto-generate rig.json from a mask.

    On success, writes <output_dir>/rig.json and returns the rig dict.
    On failure (too few endpoints, empty mask, skimage missing, etc.), removes
    any stale rig.json in output_dir and returns None so the viewer falls back
    to single-part rendering.

    Uses config.AUTO_RIG_* constants for tuning and config.RIG_FILENAME /
    config.OBJECT_FILENAME / config.MIN_PART_AREA_PX from the existing pipeline.
    """
    import config
    import rigging

    rig_path = os.path.join(output_dir, config.RIG_FILENAME)

    def _cleanup():
        if os.path.exists(rig_path):
            try:
                os.remove(rig_path)
            except OSError:
                pass

    if mask is None or mask.size == 0 or not (mask > 0).any():
        _cleanup()
        return None

    skel = skeletonize_mask(mask)
    if not skel.any():
        _cleanup()
        return None

    root_xy, endpoints = analyze_skeleton(
        skel,
        min_branch_px=config.AUTO_RIG_MIN_BRANCH_PX,
        max_endpoints=config.AUTO_RIG_MAX_ENDPOINTS,
    )
    if root_xy is None or len(endpoints) < config.AUTO_RIG_MIN_ENDPOINTS:
        _cleanup()
        return None

    seeds = [root_xy] + endpoints
    labels = rigging.partition_mask_by_seeds(mask, seeds)

    h, w = mask.shape
    root_mask = (labels == 0).astype(np.uint8) * 255
    parts_data = [{
        "id": 0, "name": "root", "parent": -1,
        "seed_xy": list(seeds[0]),
        "pivot_xy": list(seeds[0]),
    }]

    # Add children; skip those below the min part area threshold
    min_part_px = getattr(config, "MIN_PART_AREA_PX", 100)
    for i in range(1, len(seeds)):
        child_mask = (labels == i).astype(np.uint8) * 255
        if int((child_mask > 0).sum()) < min_part_px:
            continue
        pivot = rigging.compute_pivot(child_mask, root_mask)
        parts_data.append({
            "id": i, "name": f"child_{i}", "parent": 0,
            "seed_xy": list(seeds[i]),
            "pivot_xy": list(pivot),
        })

    # If all children got filtered out, no rig to save
    if len(parts_data) < 2:
        _cleanup()
        return None

    rig = {
        "version": rigging.RIG_VERSION,
        "source_object": config.OBJECT_FILENAME,
        "image_size": [w, h],
        "parts": parts_data,
    }
    rigging.save_rig(rig, rig_path)
    return rig
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig.TryAutoRigTest -v`
Expected: `Ran 3 tests ... OK`

- [ ] **Step 5: Run all auto_rig tests**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -m unittest tests.test_auto_rig -v`
Expected: all tests pass

- [ ] **Step 6: Commit**

```bash
git add auto_rig.py tests/test_auto_rig.py
git commit -m "feat(auto-rig): try_auto_rig writes rig.json or cleans up"
```

---

## Task 8: Integrate into live_demo.py

**Files:**
- Modify: `live_demo.py`

- [ ] **Step 1: Review current run_pipeline and rig cleanup code**

Run: `cd /home/ingon/AR_book/drawing-2p5d && grep -n "rig.json\|auto_rig\|stale_rig\|RIG_FILENAME" live_demo.py`

Expected: there is a block added earlier that deletes `stale_rig` at the start of `run_pipeline`. This block will be replaced by the `try_auto_rig` call at the END of `run_pipeline` (which handles cleanup internally).

- [ ] **Step 2: Add auto_rig import at top of live_demo.py**

Find the `import viewer` line and add `import auto_rig` next to it:

```python
import auto_rig
import viewer
```

- [ ] **Step 3: Remove the early stale-rig cleanup**

Find this block at the start of `run_pipeline` (added in a previous session):

```python
    # Clear any stale rig.json from a previous capture — a multi-part rig whose
    # seed points fall outside the current mask renders as empty parts in the
    # viewer. Fresh captures start in single-part mode; user can E=edit-rig.
    stale_rig = os.path.join(config.OUTPUT_DIR, config.RIG_FILENAME)
    if os.path.exists(stale_rig):
        os.remove(stale_rig)
```

Delete it — `auto_rig.try_auto_rig` now owns stale-rig handling.

- [ ] **Step 4: Call try_auto_rig at the end of run_pipeline**

Find the end of `run_pipeline`, where `paths` dict is built and returned:

```python
    elapsed = time.time() - t0
    print(f"Done in {elapsed:.1f}s\n")
    for k, v in paths.items():
        print(f"  {k}: {v}")

    return paths
```

Insert the auto-rig call **before** the `elapsed = ...` line:

```python
    # Auto-rigging: analyze mask skeleton, write output/rig.json if rig-worthy,
    # or clear any stale rig so viewer falls back to single-part.
    rig = auto_rig.try_auto_rig(mask, config.OUTPUT_DIR)
    if rig is not None:
        n_children = len(rig["parts"]) - 1
        print(f"  Auto-rig: root + {n_children} children")
    else:
        print(f"  Auto-rig: single part (no rig needed)")
```

- [ ] **Step 5: Syntax check**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 -c "import ast; ast.parse(open('live_demo.py').read()); print('OK')"`
Expected: `OK`

- [ ] **Step 6: Commit**

```bash
git add live_demo.py
git commit -m "feat(live-demo): integrate auto_rig.try_auto_rig into pipeline"
```

---

## Task 9: Manual integration test

**Files:**
- (none — runtime verification)

- [ ] **Step 1: Verify RealSense camera is free**

Run: `ps aux | grep -iE "cheese|realsense" | grep -v grep`
Expected: no output (no process holding the camera). If output shows `cheese` or similar, `pkill -f cheese` first.

- [ ] **Step 2: Run live demo and capture a stick-figure drawing**

Run: `cd /home/ingon/AR_book/drawing-2p5d && python3 live_demo.py`

Point camera at a stick-figure drawing. Click on the character.

Expected console output includes:
```
[2/4] Stroke segmentation at (...) ...
  Foreground: 10~30%
[3/4] Postprocessing mask ...
[4/4] Estimating depth & normal + exporting ...
  Auto-rig: root + N children      ← N should be 3~6 for a stick figure
```

Viewer should show the character with limbs swinging independently (pendulum).

- [ ] **Step 3: Capture a round blob (e.g., a pig head or a circle drawing)**

Press R (retake) in viewer, aim camera at a drawing that's just a head/blob.

Expected console output:
```
  Auto-rig: single part (no rig needed)
```

Viewer shows single-part bounce/wobble (no limb swing).

- [ ] **Step 4: Verify manual E-key override still works**

Inside viewer (in a rigged capture), press **E** to enter edit mode. Tap a different set of seeds, press ENTER.

Expected: viewer reloads with the user's rig replacing the auto one.

- [ ] **Step 5: Verify scikit-image fallback**

Temporarily rename the skimage install to simulate missing dep:

Run: `python3 -c "import skimage; print(skimage.__file__)"` → note path
Run: `mv <that-path>/morphology /tmp/_morphology_backup`
Run: `python3 live_demo.py` and capture anything.

Expected console:
```
  Auto-rig: single part (no rig needed)
```
No crash.

Restore: `mv /tmp/_morphology_backup <that-path>/morphology`

- [ ] **Step 6: Commit the manual test log (optional)**

If you took notes on observed behavior worth keeping, append to `docs/superpowers/plans/2026-04-20-auto-rigging.md` under an "Integration test notes" section, and commit.

```bash
git add docs/superpowers/plans/2026-04-20-auto-rigging.md
git commit -m "docs(auto-rig): record manual integration test results"
```

---

## Self-Review Notes

**Spec coverage check:**
- Section 2 "In-scope" items → Tasks 3-8 ✓
- Section 4 file changes → Tasks 1-8 ✓
- Section 5 algorithms (skeleton, endpoint detection, pruning, root selection, rig build) → Tasks 3-7 ✓
- Section 8 failure modes (skimage missing, empty mask, short branches, too many endpoints, small parts) → covered in Tasks 3, 6, 7 (and tested in test classes)
- Section 10 test strategy → Tasks 3-7 unit tests + Task 9 manual integration
- Out-of-scope items (gesture, 3-level hierarchy, radial symmetry detection) intentionally NOT in plan

**Type consistency:** `analyze_skeleton` signature uses `Optional[tuple[int, int]]` consistently across tasks 6-7. `try_auto_rig` returns `Optional[dict]` as defined.

**No placeholders:** Each task contains full code, exact commands, expected outputs.
