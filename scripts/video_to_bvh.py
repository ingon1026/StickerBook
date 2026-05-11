"""Convert an MP4 video → BVH via MediaPipe Pose → register in MotionLibrary.

Usage:
    cd stickerbook
    python scripts/video_to_bvh.py /path/to/input.mp4 --name dance_test
    python scripts/video_to_bvh.py /path/to/input.mp4   # auto motion_NNN

Result: new entry in stickerbook/assets/motions/library/<name>.bvh and
AD examples/{bvh,config/motion,config/retarget} populated. Then run
stickerbook (main.py) and select <name> via L key + 1-9 keys.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

from config import AD_REPO_PATH, ROOT  # noqa: E402
from motion.bvh_writer import write_bvh  # noqa: E402
from motion.library import MotionLibrary  # noqa: E402
from motion.pose_estimator import PoseEstimator  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mp4", type=Path, help="input video file")
    parser.add_argument("--name", default=None, help="motion name (default: motion_NNN)")
    args = parser.parse_args()

    if not args.mp4.is_file():
        print(f"[err] input not found: {args.mp4}")
        return 1

    print(f"[v2bvh] reading {args.mp4} …")
    cap = cv2.VideoCapture(str(args.mp4))
    if not cap.isOpened():
        print(f"[err] cv2 cannot open: {args.mp4}")
        return 1
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames = []
    while True:
        ok, frame = cap.read()
        if not ok or frame is None:
            break
        frames.append(frame)
    cap.release()
    print(f"[v2bvh] frames: {len(frames)}, fps: {fps:.1f}")

    if len(frames) < 30:
        print(f"[err] too few frames ({len(frames)}); need >= 30")
        return 1

    print("[v2bvh] running MediaPipe Pose …")
    estimator = PoseEstimator()
    try:
        landmarks = estimator.estimate_batch(frames)
    finally:
        estimator.close()
    n_fail = sum(1 for lm in landmarks if lm is None)
    fail_rate = n_fail / len(landmarks)
    print(f"[v2bvh] pose recognition: {len(landmarks) - n_fail}/{len(landmarks)} ok ({fail_rate:.0%} fail)")
    if fail_rate > 0.5:
        print(f"[err] too many failures (> 50%) — try clearer footage")
        return 1

    tmp_bvh = Path("/tmp") / f"video_to_bvh_{args.mp4.stem}.bvh"
    write_bvh(landmarks, fps=fps, output_path=tmp_bvh)
    print(f"[v2bvh] wrote bvh: {tmp_bvh}")

    library = MotionLibrary(
        library_dir=ROOT / "assets" / "motions" / "library",
        ad_repo_path=AD_REPO_PATH,
    )
    name = library.add(tmp_bvh, name=args.name, preset="rokoko")
    print(f"[v2bvh] registered as: {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
