import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ASSETS_DIR = ROOT / "assets"
CAPTURES_DIR = ASSETS_DIR / "captures"
SAMPLES_DIR = ASSETS_DIR / "samples"
DEBUG_DIR = ASSETS_DIR / "debug"


@dataclass(frozen=True)
class Config:
    camera_index: int = 0
    frame_width: int = 1280
    frame_height: int = 720

    homography_min_inliers: int = 10
    homography_min_inlier_ratio: float = 0.3
    homography_lost_frames_threshold: int = 15


DEFAULT = Config()


# M9: AnimatedDrawings integration uses a separate conda env; overridable via env vars.
TORCHSERVE_BIN = Path(os.environ.get(
    "STICKERBOOK_TORCHSERVE_BIN",
    "/home/ingon/miniconda3/envs/animated_drawings/bin/torchserve",
))
AD_PYTHON = Path(os.environ.get(
    "STICKERBOOK_AD_PYTHON",
    "/home/ingon/miniconda3/envs/animated_drawings/bin/python",
))
AD_REPO_PATH = Path(os.environ.get(
    "STICKERBOOK_AD_REPO",
    str(Path.home() / "AR_book" / "AnimatedDrawings"),
))
ANIMATION_WORK_DIR = Path(os.environ.get(
    "STICKERBOOK_AD_WORK_DIR",
    "/tmp/stickerbook_ad",
))
TORCHSERVE_CONFIG_PATH = Path(os.environ.get(
    "STICKERBOOK_TS_CONFIG",
    "/tmp/ts_config.properties",
))
TORCHSERVE_MODELS = [
    "drawn_humanoid_detector.mar",
    "drawn_humanoid_pose_estimator.mar",
]
