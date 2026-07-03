"""Unit tests for bvh_writer.write_bvh."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from motion.bvh_writer import write_bvh
from motion.pose_estimator import PoseLandmarks


def make_t_pose_landmarks() -> PoseLandmarks:
    """A simple T-pose-ish 33-landmark fixture (only the ones we use)."""
    pts = np.zeros((33, 3), dtype=np.float32)
    # MediaPipe indices: 11=L_SHOULDER, 12=R_SHOULDER, 13=L_ELBOW, 14=R_ELBOW,
    # 15=L_WRIST, 16=R_WRIST, 23=L_HIP, 24=R_HIP, 25=L_KNEE, 26=R_KNEE,
    # 27=L_ANKLE, 28=R_ANKLE, 31=L_FOOT_INDEX, 32=R_FOOT_INDEX, 0=NOSE
    pts[11] = [-0.2, 1.5, 0]   # L shoulder
    pts[12] = [0.2, 1.5, 0]    # R shoulder
    pts[13] = [-0.5, 1.5, 0]   # L elbow (out)
    pts[14] = [0.5, 1.5, 0]    # R elbow (out)
    pts[15] = [-0.7, 1.5, 0]   # L wrist
    pts[16] = [0.7, 1.5, 0]    # R wrist
    pts[23] = [-0.15, 1.0, 0]  # L hip
    pts[24] = [0.15, 1.0, 0]   # R hip
    pts[25] = [-0.15, 0.5, 0]  # L knee
    pts[26] = [0.15, 0.5, 0]   # R knee
    pts[27] = [-0.15, 0.0, 0]  # L ankle
    pts[28] = [0.15, 0.0, 0]   # R ankle
    pts[31] = [-0.15, 0.0, 0.1]  # L foot index
    pts[32] = [0.15, 0.0, 0.1]   # R foot index
    pts[0] = [0, 1.7, 0]       # nose
    return PoseLandmarks(points=pts)


def test_write_bvh_creates_file(tmp_path: Path):
    out = tmp_path / "test.bvh"
    write_bvh([make_t_pose_landmarks()] * 5, fps=30.0, output_path=out)
    assert out.is_file()


def test_bvh_starts_with_hierarchy(tmp_path: Path):
    out = tmp_path / "test.bvh"
    write_bvh([make_t_pose_landmarks()], fps=30.0, output_path=out)
    text = out.read_text()
    assert text.startswith("HIERARCHY")
    assert "ROOT Root" in text
    assert "JOINT Hips" in text
    assert "JOINT Spine1" in text
    assert "JOINT LeftThigh" in text
    assert "JOINT RightThigh" in text
    assert "JOINT LeftShin" in text
    assert "JOINT LeftToe" in text


def test_bvh_motion_section_has_correct_frame_count(tmp_path: Path):
    out = tmp_path / "test.bvh"
    write_bvh([make_t_pose_landmarks()] * 7, fps=30.0, output_path=out)
    text = out.read_text()
    assert "MOTION" in text
    assert "Frames: 7" in text
    assert "Frame Time: 0.0333" in text  # 1/30


def test_bvh_motion_lines_count_matches_frames(tmp_path: Path):
    out = tmp_path / "test.bvh"
    write_bvh([make_t_pose_landmarks()] * 4, fps=30.0, output_path=out)
    text = out.read_text()
    motion_section = text.split("MOTION")[1]
    motion_lines = [
        ln for ln in motion_section.splitlines()
        if ln.strip() and not ln.startswith("Frames") and not ln.startswith("Frame Time")
    ]
    assert len(motion_lines) == 4


def test_none_landmarks_skipped(tmp_path: Path):
    """If a frame is None (recognition failure), interpolate or skip."""
    out = tmp_path / "test.bvh"
    seq = [make_t_pose_landmarks(), None, make_t_pose_landmarks()]
    write_bvh(seq, fps=30.0, output_path=out)
    text = out.read_text()
    # 2 valid frames written (None dropped) — strict design choice
    assert "Frames: 2" in text


def test_motion_text_has_nonzero_rotation_when_pose_changes(tmp_path: Path):
    """Different landmarks across frames → BVH rotation channels must vary."""
    rest = make_t_pose_landmarks()
    # Move left elbow up sharply on frame 2
    moved = make_t_pose_landmarks()
    moved.points[13] = np.array([-0.5, 1.8, 0], dtype=np.float32)  # L elbow lifted

    out = tmp_path / "test.bvh"
    write_bvh([rest, moved, rest], fps=30.0, output_path=out)
    text = out.read_text()

    # Extract motion lines (after Frame Time:)
    motion_section = text.split("Frame Time:")[1].splitlines()[1:]
    motion_lines = [ln for ln in motion_section if ln.strip()]
    assert len(motion_lines) == 3

    # frame 1 (the moved pose) — rotation channels should NOT all be zero
    floats_frame1 = [float(x) for x in motion_lines[1].split()]
    # 24 joints x 6 channels: rotation = chans 4,5,6 of each (1-indexed) = indices 3,4,5
    rot_values = []
    for j in range(24):
        rot_values.extend(floats_frame1[j * 6 + 3 : j * 6 + 6])
    assert any(abs(v) > 0.01 for v in rot_values), (
        "Expected at least one non-zero rotation in moved frame"
    )


def test_t_pose_through_t_pose_yields_near_zero_rotation(tmp_path: Path):
    """Identity input (rest = current) → rotations should be ~0."""
    rest = make_t_pose_landmarks()
    out = tmp_path / "test.bvh"
    write_bvh([rest, rest], fps=30.0, output_path=out)
    text = out.read_text()
    motion_section = text.split("Frame Time:")[1].splitlines()[1:]
    motion_lines = [ln for ln in motion_section if ln.strip()]
    floats_frame0 = [float(x) for x in motion_lines[0].split()]
    rot_values = []
    for j in range(24):
        rot_values.extend(floats_frame0[j * 6 + 3 : j * 6 + 6])
    # All rotations should be small (< 1°) since pose is unchanged
    max_rot = max(abs(v) for v in rot_values)
    assert max_rot < 1.0, f"Identity pose unexpectedly produced rotation up to {max_rot}°"
