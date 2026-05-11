"""Convert MediaPipe Pose landmark sequence to BVH text.

Strategy: write per-joint position channels (Xposition/Yposition/Zposition)
plus per-frame local rotations computed from rest pose → current pose
parent→child segment direction changes. AnimatedDrawings retargeting reads
the rotation channels to drive character joint orientations.
Joint name set matches `examples/config/retarget/my_dance.yaml`.

Skeleton:
    Root → Hips → (Spine1→Spine2→Spine3→Spine4 → Neck→Head, LeftArm chain,
                   RightArm chain), LeftThigh chain, RightThigh chain.
"""
from __future__ import annotations

from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np
from scipy.spatial.transform import Rotation as R

from motion.pose_estimator import PoseLandmarks


# MediaPipe Pose landmark indices (subset we use)
MP_NOSE = 0
MP_LEFT_SHOULDER, MP_RIGHT_SHOULDER = 11, 12
MP_LEFT_ELBOW, MP_RIGHT_ELBOW = 13, 14
MP_LEFT_WRIST, MP_RIGHT_WRIST = 15, 16
MP_LEFT_HIP, MP_RIGHT_HIP = 23, 24
MP_LEFT_KNEE, MP_RIGHT_KNEE = 25, 26
MP_LEFT_ANKLE, MP_RIGHT_ANKLE = 27, 28
MP_LEFT_FOOT_INDEX, MP_RIGHT_FOOT_INDEX = 31, 32


# Skeleton hierarchy in DFS order. Each entry: (joint_name, parent_index_in_list)
# parent_index = -1 means root (no parent)
SKELETON: List[Tuple[str, int]] = [
    ("Root", -1),         # 0
    ("Hips", 0),          # 1
    ("Spine1", 1),        # 2
    ("Spine2", 2),        # 3
    ("Spine3", 3),        # 4
    ("Spine4", 4),        # 5
    ("Neck", 5),          # 6
    ("Head", 6),          # 7
    ("LeftShoulder", 5),  # 8
    ("LeftArm", 8),       # 9
    ("LeftForeArm", 9),   # 10
    ("LeftHand", 10),     # 11
    ("RightShoulder", 5), # 12
    ("RightArm", 12),     # 13
    ("RightForeArm", 13), # 14
    ("RightHand", 14),    # 15
    ("LeftThigh", 1),     # 16
    ("LeftShin", 16),     # 17
    ("LeftFoot", 17),     # 18
    ("LeftToe", 18),      # 19
    ("RightThigh", 1),    # 20
    ("RightShin", 20),    # 21
    ("RightFoot", 21),    # 22
    ("RightToe", 22),     # 23
]


def _compute_joint_positions(
    lm: PoseLandmarks,
) -> np.ndarray:
    """Map 33 MediaPipe landmarks → 24 BVH joint world positions.

    Returns shape (24, 3) ordered by SKELETON.
    """
    p = lm.points  # (33, 3)
    hip_mid = (p[MP_LEFT_HIP] + p[MP_RIGHT_HIP]) / 2.0
    sho_mid = (p[MP_LEFT_SHOULDER] + p[MP_RIGHT_SHOULDER]) / 2.0
    spine_dir = sho_mid - hip_mid

    pos = np.zeros((24, 3), dtype=np.float32)
    pos[0] = (0, 0, 0)                      # Root
    pos[1] = hip_mid                         # Hips
    pos[2] = hip_mid + 0.25 * spine_dir      # Spine1
    pos[3] = hip_mid + 0.50 * spine_dir      # Spine2
    pos[4] = hip_mid + 0.75 * spine_dir      # Spine3
    pos[5] = sho_mid                         # Spine4
    pos[6] = sho_mid + np.array([0, 0.05, 0], dtype=np.float32)  # Neck
    pos[7] = p[MP_NOSE]                      # Head
    pos[8] = p[MP_LEFT_SHOULDER]             # LeftShoulder
    pos[9] = p[MP_LEFT_SHOULDER]             # LeftArm (same start)
    pos[10] = p[MP_LEFT_ELBOW]               # LeftForeArm
    pos[11] = p[MP_LEFT_WRIST]               # LeftHand
    pos[12] = p[MP_RIGHT_SHOULDER]           # RightShoulder
    pos[13] = p[MP_RIGHT_SHOULDER]           # RightArm
    pos[14] = p[MP_RIGHT_ELBOW]              # RightForeArm
    pos[15] = p[MP_RIGHT_WRIST]              # RightHand
    pos[16] = p[MP_LEFT_HIP]                 # LeftThigh
    pos[17] = p[MP_LEFT_KNEE]                # LeftShin
    pos[18] = p[MP_LEFT_ANKLE]               # LeftFoot
    pos[19] = p[MP_LEFT_FOOT_INDEX]          # LeftToe
    pos[20] = p[MP_RIGHT_HIP]                # RightThigh
    pos[21] = p[MP_RIGHT_KNEE]               # RightShin
    pos[22] = p[MP_RIGHT_ANKLE]              # RightFoot
    pos[23] = p[MP_RIGHT_FOOT_INDEX]         # RightToe
    # MediaPipe Pose World Landmarks: +y is down. AD assumes +y up. Flip
    # y so head ends up above hips (otherwise the sticker renders upside-down).
    pos[:, 1] *= -1.0
    # MediaPipe single-camera world landmarks have noisy z (depth). The retarget
    # yaml's PCA fits a 2D plane through joint groups; if z noise rivals real
    # motion amplitude, PCA's principal axis tilts toward the noise and
    # projection collapses the visible motion. Force frontal plane (XY) so
    # only clean 2D motion drives the character.
    pos[:, 2] = 0.0
    # m → cm: AD's `my_dance.yaml` retarget uses `scale: 0.025` (Rokoko cm
    # convention). Without this, joint position variance is ~100× too small
    # and retargeted character motion appears static.
    pos *= 100.0
    return pos


def _build_hierarchy_text(rest_positions: np.ndarray) -> str:
    """Build HIERARCHY section. Offsets = rest_positions[child] - rest_positions[parent]."""
    lines: List[str] = ["HIERARCHY"]

    def indent(level: int) -> str:
        return "\t" * level

    children: dict[int, list[int]] = {i: [] for i in range(len(SKELETON))}
    for i, (_, parent_idx) in enumerate(SKELETON):
        if parent_idx >= 0:
            children[parent_idx].append(i)

    def emit(idx: int, level: int) -> None:
        name, parent_idx = SKELETON[idx]
        offset = (
            rest_positions[idx]
            if parent_idx < 0
            else rest_positions[idx] - rest_positions[parent_idx]
        )
        keyword = "ROOT" if parent_idx < 0 else "JOINT"
        lines.append(f"{indent(level)}{keyword} {name}")
        lines.append(f"{indent(level)}{{")
        lines.append(
            f"{indent(level+1)}OFFSET {offset[0]:.6f} {offset[1]:.6f} {offset[2]:.6f}"
        )
        lines.append(
            f"{indent(level+1)}CHANNELS 6 Xposition Yposition Zposition Xrotation Yrotation Zrotation"
        )
        for child in children[idx]:
            emit(child, level + 1)
        if not children[idx]:
            # End site for leaf
            lines.append(f"{indent(level+1)}End Site")
            lines.append(f"{indent(level+1)}{{")
            lines.append(f"{indent(level+2)}OFFSET 0.000000 0.000000 0.000000")
            lines.append(f"{indent(level+1)}}}")
        lines.append(f"{indent(level)}}}")

    emit(0, 0)
    return "\n".join(lines)


def _align_rotation(rest_dir: np.ndarray, curr_dir: np.ndarray) -> R:
    """Quaternion that rotates rest_dir onto curr_dir (about their cross product)."""
    rest_n = float(np.linalg.norm(rest_dir))
    curr_n = float(np.linalg.norm(curr_dir))
    if rest_n < 1e-9 or curr_n < 1e-9:
        return R.identity()
    rest_u = rest_dir / rest_n
    curr_u = curr_dir / curr_n
    cos_a = float(np.dot(rest_u, curr_u))
    cos_a = max(-1.0, min(1.0, cos_a))
    if cos_a > 1.0 - 1e-9:
        return R.identity()
    if cos_a < -1.0 + 1e-9:
        # 180° flip — pick any axis perpendicular to rest_u
        axis = np.cross(rest_u, np.array([1.0, 0.0, 0.0]))
        if np.linalg.norm(axis) < 1e-6:
            axis = np.cross(rest_u, np.array([0.0, 1.0, 0.0]))
        axis /= np.linalg.norm(axis)
        return R.from_rotvec(axis * np.pi)
    axis = np.cross(rest_u, curr_u)
    axis_n = float(np.linalg.norm(axis))
    if axis_n < 1e-9:
        # cos_a check passed but cross is degenerate (float precision) — treat as no-op
        return R.identity()
    axis /= axis_n
    angle = np.arccos(cos_a)
    return R.from_rotvec(axis * angle)


def _compute_local_rotations(
    rest_positions: np.ndarray,
    frame_positions: np.ndarray,
) -> np.ndarray:
    """Per-joint local Euler XYZ (degrees) for the frame.

    Aligns rest segment (parent→child) to current frame segment, then converts
    global rotations to parent-space local rotations via R_local = R_parent^-1 * R_global.
    Returns shape (24, 3) Euler XYZ degrees.
    """
    n = len(SKELETON)
    global_rots: List[R] = [R.identity()] * n
    for i in range(1, n):  # skip Root
        _, parent_idx = SKELETON[i]
        rest_dir = rest_positions[i] - rest_positions[parent_idx]
        curr_dir = frame_positions[i] - frame_positions[parent_idx]
        global_rots[i] = _align_rotation(rest_dir, curr_dir)

    eulers = np.zeros((n, 3), dtype=np.float32)
    for i in range(n):
        _, parent_idx = SKELETON[i]
        if parent_idx < 0:
            local = global_rots[i]
        else:
            local = global_rots[parent_idx].inv() * global_rots[i]
        eulers[i] = local.as_euler("XYZ", degrees=True).astype(np.float32)
    return eulers


def _build_motion_text(
    rest_positions: np.ndarray,
    sequence: List[np.ndarray],
    fps: float,
) -> str:
    """Build MOTION section. Each frame: per-joint Xpos Ypos Zpos Xrot Yrot Zrot."""
    lines: List[str] = ["MOTION"]
    lines.append(f"Frames: {len(sequence)}")
    lines.append(f"Frame Time: {1.0 / fps:.6f}")
    for positions in sequence:
        eulers = _compute_local_rotations(rest_positions, positions)
        parts: List[str] = []
        for j in range(positions.shape[0]):
            parts.extend(
                [f"{positions[j, 0]:.6f}", f"{positions[j, 1]:.6f}", f"{positions[j, 2]:.6f}"]
            )
            parts.extend(
                [f"{eulers[j, 0]:.6f}", f"{eulers[j, 1]:.6f}", f"{eulers[j, 2]:.6f}"]
            )
        lines.append(" ".join(parts))
    return "\n".join(lines)


def write_bvh(
    landmark_sequence: List[Optional[PoseLandmarks]],
    fps: float,
    output_path: Path,
) -> None:
    """landmarks → BVH file. None frames are dropped."""
    valid_seq = [lm for lm in landmark_sequence if lm is not None]
    if not valid_seq:
        raise ValueError("write_bvh: empty valid landmark sequence")

    rest_positions = _compute_joint_positions(valid_seq[0])
    hierarchy_text = _build_hierarchy_text(rest_positions)

    frame_positions: List[np.ndarray] = [
        _compute_joint_positions(lm) for lm in valid_seq
    ]
    motion_text = _build_motion_text(rest_positions, frame_positions, fps)

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(hierarchy_text + "\n" + motion_text + "\n")
