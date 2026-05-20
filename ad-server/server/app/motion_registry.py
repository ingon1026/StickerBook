"""Motion id ↔ AD yaml 경로 매핑.

새 motion 추가 시 _REGISTRY 만 수정. 호출자는 yaml 경로 구조를 모름.

목록 = AD 공식 fair1 standard + my_dance 시리즈.
참고: stickerbook 의 scripts/seed_library.py 의 SEED 와 호환.
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


def _entry(id_: str, label: str) -> MotionInfo:
    return {"id": id_, "label": label, "yaml": _MOTION_CFG_DIR / f"{id_}.yaml"}


# fair1 표준 (AD 공식 README 예제) + my_dance 시리즈 (Rokoko 변환본).
# 정렬 = 사용자에게 보일 자연스러운 순서 (단순 → 복잡).
_REGISTRY: dict[str, MotionInfo] = {
    info["id"]: info for info in [
        _entry("dab",            "Dab"),
        _entry("wave_hello",     "Wave Hello"),
        _entry("jumping",        "Jumping"),
        _entry("jumping_jacks",  "Jumping Jacks"),
        _entry("zombie",         "Zombie"),
        _entry("dance_1",        "Dance 1"),
        _entry("dance_2",        "Dance 2"),
        _entry("dance_3",        "Dance 3"),
        _entry("my_dance",       "My Dance"),
        _entry("my_dance_2",     "My Dance 2"),
        _entry("my_dance_3",     "My Dance 3"),
    ]
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
