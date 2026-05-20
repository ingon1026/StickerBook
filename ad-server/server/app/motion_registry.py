"""Motion id ↔ AD yaml 경로 매핑.

새 motion 추가 시 _REGISTRY 만 수정. 호출자는 yaml 경로 구조를 모름.

목록 = AD 공식 fair1 standard + my_dance 시리즈.
참고: stickerbook 의 scripts/seed_library.py 의 SEED 와 호환.

retarget 매핑 규칙:
  - 기본: motion 과 *동명 retarget* 자동 (예: dance_1 → retarget/dance_1.yaml)
  - 동명 retarget 없으면 _RETARGET_OVERRIDE 로 명시 (예: jumping_jacks → fair1_ppf)
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
_RETARGET_CFG_DIR = settings.AD_REPO_ROOT / "examples" / "config" / "retarget"

# 동명 retarget 없는 motion 의 fallback. AD 공식 generic retarget.
_RETARGET_FALLBACK = _RETARGET_CFG_DIR / "fair1_ppf.yaml"

# 동명 retarget 없거나 *다른 retarget* 을 강제할 때 명시.
# motion 의 BVH 가 어떤 skeleton 인지에 따라 매칭되는 retarget 선택.
_RETARGET_OVERRIDE: dict[str, str] = {
    # jumping_jacks BVH 는 CMU1 skeleton (examples/bvh/cmu1/) → cmu1_pfp retarget
    "jumping_jacks": "cmu1_pfp",
}


def _entry(id_: str, label: str) -> MotionInfo:
    return {"id": id_, "label": label, "yaml": _MOTION_CFG_DIR / f"{id_}.yaml"}


# fair1 표준 (AD 공식) + my_dance 시리즈 (Rokoko 변환).
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


def get_retarget_yaml(motion_id: str) -> Path:
    """motion 에 짝지어진 retarget config 경로 반환.

    우선순위:
      1) _RETARGET_OVERRIDE[motion_id] 가 명시되어 있으면 그 yaml
      2) retarget/<motion_id>.yaml 이 존재하면 그것 (AD 공식 동명 짝)
      3) fallback: retarget/fair1_ppf.yaml
    """
    if motion_id not in _REGISTRY:
        raise UnknownMotionError(f"unknown motion: {motion_id}")

    override = _RETARGET_OVERRIDE.get(motion_id)
    if override:
        path = _RETARGET_CFG_DIR / f"{override}.yaml"
        if not path.exists():
            raise FileNotFoundError(f"override retarget missing: {path}")
        return path

    same_name = _RETARGET_CFG_DIR / f"{motion_id}.yaml"
    if same_name.exists():
        return same_name

    if not _RETARGET_FALLBACK.exists():
        raise FileNotFoundError(f"fallback retarget missing: {_RETARGET_FALLBACK}")
    return _RETARGET_FALLBACK


def list_motions() -> list[MotionInfo]:
    return list(_REGISTRY.values())
