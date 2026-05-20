"""Motion id ↔ AD yaml 경로 매핑.

새 motion 추가 시 _REGISTRY 만 수정. 호출자는 yaml 경로 구조를 모름.
"""
from __future__ import annotations

from pathlib import Path
from typing import TypedDict

from app import settings


class UnknownMotionError(KeyError):
    """알 수 없는 motion id."""


class MotionInfo(TypedDict):
    id: str
    label: str
    yaml: Path


_MOTION_CFG_DIR = settings.AD_REPO_ROOT / "examples" / "config" / "motion"

_REGISTRY: dict[str, MotionInfo] = {
    "dab": {
        "id": "dab",
        "label": "Dab",
        "yaml": _MOTION_CFG_DIR / "dab.yaml",
    },
    "wave_hello": {
        "id": "wave_hello",
        "label": "Wave Hello",
        "yaml": _MOTION_CFG_DIR / "wave_hello.yaml",
    },
    "jumping": {
        "id": "jumping",
        "label": "Jumping",
        "yaml": _MOTION_CFG_DIR / "jumping.yaml",
    },
}


def get_motion_yaml(motion_id: str) -> Path:
    info = _REGISTRY.get(motion_id)
    if info is None:
        raise UnknownMotionError(f"unknown motion: {motion_id}")
    yaml = info["yaml"]
    if not yaml.exists():
        raise FileNotFoundError(f"motion yaml missing on disk: {yaml}")
    return yaml


def list_motions() -> list[MotionInfo]:
    return list(_REGISTRY.values())
