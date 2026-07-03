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


def find_skeleton_nodes(skel: np.ndarray) -> tuple[list[tuple[int, int]], list[tuple[int, int]]]:
    """Classify skeleton pixels into endpoints and branching points.

    Uses the Rutovitz 0→1 transition count around the 8-neighborhood (clockwise)
    instead of a raw neighbor count. Transitions correctly distinguish a branch
    pixel (a true T/+ junction with 3+ outgoing arms) from an on-arm pixel that
    merely has many skeleton neighbors due to diagonal connectivity.

    - transitions == 1 → endpoint (limb tip)
    - transitions >= 3 → branching point (joint)
    - transitions == 2 → interior (ignored)
    - transitions == 0 → isolated pixel (ignored)

    Args:
        skel: (H, W) bool skeleton from skeletonize_mask.

    Returns:
        (endpoints, branches) — each a list of (x, y) tuples.
    """
    if skel is None or skel.size == 0 or not skel.any():
        return [], []

    h, w = skel.shape
    s = skel.astype(np.uint8)
    padded = np.zeros((h + 2, w + 2), dtype=np.uint8)
    padded[1:-1, 1:-1] = s

    # Clockwise neighbors from North: N, NE, E, SE, S, SW, W, NW, back to N.
    # Transitions counted as 0→1 between consecutive neighbors on this ring.
    n = [
        padded[0:-2, 1:-1],  # N
        padded[0:-2, 2:  ],  # NE
        padded[1:-1, 2:  ],  # E
        padded[2:,   2:  ],  # SE
        padded[2:,   1:-1],  # S
        padded[2:,   0:-2],  # SW
        padded[1:-1, 0:-2],  # W
        padded[0:-2, 0:-2],  # NW
    ]
    transitions = np.zeros((h, w), dtype=np.int32)
    for i in range(8):
        a = n[i]
        b = n[(i + 1) % 8]
        transitions += ((a == 0) & (b == 1)).astype(np.int32)

    endpoints = []
    branches = []
    ys, xs = np.where(skel)
    for y, x in zip(ys, xs):
        t = int(transitions[y, x])
        if t == 1:
            endpoints.append((int(x), int(y)))
        elif t >= 3:
            branches.append((int(x), int(y)))

    return endpoints, branches


def measure_branch_length(skel: np.ndarray, endpoint: tuple[int, int]) -> int:
    """Walk from an endpoint along the skeleton until a branching point or
    another endpoint is hit. Return the number of skeleton pixels visited
    (including the starting endpoint itself).

    Args:
        skel: (H, W) bool skeleton.
        endpoint: (x, y) start point — assumed to lie on skeleton.

    Returns:
        Branch length in pixels (>= 0 if start is not on skeleton, else >= 1).
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

        # Stop at branching point (>=3 neighbors). Don't apply on the first
        # step — a pixel with 1 neighbor (true endpoint) shouldn't stop here.
        if length > 1 and n_skel >= 3:
            break
        if next_px is None:
            break
        cx, cy = next_px
        visited[cy, cx] = True
        length += 1

    return length


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
            skeleton centroid, or the nearest skeleton pixel to centroid if
            no branching point exists. None if the skeleton has no structure.
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
    # if no branches, use skeleton pixel nearest to centroid
    ys, xs = np.where(skel)
    cx_c = float(xs.mean())
    cy_c = float(ys.mean())

    if branches:
        root_xy = min(branches, key=lambda p: (p[0] - cx_c) ** 2 + (p[1] - cy_c) ** 2)
    else:
        dists2 = (xs - cx_c) ** 2 + (ys - cy_c) ** 2
        idx = int(np.argmin(dists2))
        root_xy = (int(xs[idx]), int(ys[idx]))

    return root_xy, endpoints


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

    # Scale the branch-length threshold by the mask's bounding box so the
    # heuristic adapts to drawing size: a 15-px spur is a real limb on a
    # tiny sketch but just contour noise on a 400-px-tall figure.
    mys, mxs = np.where(mask > 0)
    bbox_h = int(mys.max() - mys.min() + 1)
    bbox_w = int(mxs.max() - mxs.min() + 1)
    min_bbox_dim = max(1, min(bbox_h, bbox_w))
    scale_floor = int(min_bbox_dim * 0.25)
    effective_min_branch = max(config.AUTO_RIG_MIN_BRANCH_PX, scale_floor)

    root_xy, endpoints = analyze_skeleton(
        skel,
        min_branch_px=effective_min_branch,
        max_endpoints=config.AUTO_RIG_MAX_ENDPOINTS,
    )
    if root_xy is None:
        _cleanup()
        return None

    # Drop endpoints that cluster near the root — real limbs extend outward.
    # Bumps on a compact blob fail this: their tips sit a few px from the body
    # center so they should be dropped even if their skeleton branch passed
    # the length filter.
    def _reach(ep):
        return ((ep[0] - root_xy[0]) ** 2 + (ep[1] - root_xy[1]) ** 2) ** 0.5

    endpoints = [ep for ep in endpoints if _reach(ep) >= min_bbox_dim * 0.25]

    # Require at least one endpoint to reach substantially outward
    # (≥65% of the shorter bbox dim). Lumpy blob figures — where every "limb"
    # is just a small perimeter bump — fail this and get routed to single
    # part, while true stick figures (arms/legs reach ~80-90%) pass cleanly.
    if endpoints:
        max_reach = max(_reach(ep) for ep in endpoints)
        if max_reach < min_bbox_dim * 0.65:
            endpoints = []

    if len(endpoints) < config.AUTO_RIG_MIN_ENDPOINTS:
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
