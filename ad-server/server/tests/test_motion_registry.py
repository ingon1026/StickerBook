import pytest

from app.motion_registry import get_motion_yaml, list_motions, UnknownMotionError


def test_known_motion_returns_yaml_path():
    p = get_motion_yaml("dab")
    assert str(p).endswith("dab.yaml")
    assert p.exists()


def test_unknown_motion_raises():
    with pytest.raises(UnknownMotionError):
        get_motion_yaml("nonexistent_motion_xyz")


def test_list_motions_returns_ids():
    ids = [m["id"] for m in list_motions()]
    assert "dab" in ids
    assert "wave_hello" in ids
    assert len(ids) >= 1
