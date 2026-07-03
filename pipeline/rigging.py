"""Parts rigging — seed-based partition, pivot calc, rig.json I/O.

v1 scope: 2-level hierarchy (root + direct children). See
docs/superpowers/specs/2026-04-17-parts-rigging-design.md
"""
import json
import os
from collections import deque

import cv2
import numpy as np


RIG_VERSION = 1


def partition_mask_by_seeds(mask: np.ndarray, seeds: list[tuple[int, int]]) -> np.ndarray:
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


def compute_pivot(child_mask: np.ndarray, parent_mask: np.ndarray) -> tuple:
    """Child 픽셀 중 parent 경계에 가장 가까운 (x, y) 를 pivot 으로 반환.

    Disjoint (서로 닿지 않음) 인 경우 child centroid 로 fallback.

    Args:
        child_mask: uint8 (H, W), child part mask.
        parent_mask: uint8 (H, W), parent part mask.

    Returns:
        tuple (x, y) as int: pivot coordinate within child_mask.
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
    min_dist = np.min(dist_in_child)

    # 만약 child 와 parent 가 떨어져 있으면 (최소거리 > 1.5) centroid 로 fallback
    # 1.5 선택 이유: 대각선 인접(√2≈1.41) 넘어서야 disjoint 로 판정
    if min_dist > 1.5:
        ys, xs = np.where(child_bool)
        return (int(xs.mean()), int(ys.mean()))

    idx = int(np.argmin(dist_in_child))
    y, x = np.unravel_index(idx, dist.shape)

    return (int(x), int(y))


def save_rig(rig: dict, path: str) -> None:
    """Save rig dict as JSON (pretty-printed)."""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rig, f, indent=2, ensure_ascii=False)


def load_rig(path: str) -> dict:
    """Load rig dict from JSON. Caller responsible for version check."""
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def build_parts_from_rig(object_rgba: np.ndarray,
                         mask: np.ndarray,
                         rig: dict) -> list:
    """Rig dict + shared assets → list of viewer.Part.

    The root renders the FULL mask (never fragmented) so the body always
    stays intact visually. Each child renders only its Voronoi region — it
    acts as an overlay that rotates on top of the intact root. When children
    are near their rest angle the overlays sit exactly on top of the root's
    limb area (no ghost), and small swing angles keep the rotated-limb
    "ghost" subtle enough to read as a natural sway.
    """
    from viewer import Part  # local import to avoid pygame/opencv cycles

    seeds = [tuple(p["seed_xy"]) for p in rig["parts"]]
    labels = partition_mask_by_seeds(mask, seeds)

    parts = []
    for i, pdata in enumerate(rig["parts"]):
        if pdata.get("parent", -1) < 0:
            # Root: show the entire mask so the body stays whole.
            region = (mask > 0)
        else:
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
