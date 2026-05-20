"""ad_runner 의 인터페이스 + 통합 검증."""
from pathlib import Path

import pytest

from app.ad_runner import run as ad_run, AdError

AD_REPO = Path("/home/ingon/AR_book/AnimatedDrawings")
SAMPLE_DRAWING = AD_REPO / "examples" / "drawings" / "garlic.png"


def test_run_is_callable():
    assert callable(ad_run)


def test_run_raises_ad_error_for_invalid_image(tmp_path: Path):
    bogus = tmp_path / "not_an_image.txt"
    bogus.write_text("hello")
    try:
        ad_run(bogus, "dab", tmp_path / "out")
        raise AssertionError("expected AdError")
    except AdError:
        pass


def test_run_raises_file_not_found_for_missing_image(tmp_path: Path):
    missing = tmp_path / "ghost.jpg"
    try:
        ad_run(missing, "dab", tmp_path / "out")
        raise AssertionError("expected FileNotFoundError")
    except FileNotFoundError:
        pass


def test_run_returns_gif_path(tmp_path: Path):
    """stub 동작 검증 — 정상 입력엔 gif 경로 반환."""
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    gif = ad_run(SAMPLE_DRAWING, "dab", tmp_path / "work")
    assert gif.exists()
    assert gif.suffix == ".gif"
    assert gif.stat().st_size > 0


def test_run_raises_unknown_motion(tmp_path: Path):
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    from app.motion_registry import UnknownMotionError
    try:
        ad_run(SAMPLE_DRAWING, "no_such_motion_xyz", tmp_path / "work")
        raise AssertionError("expected UnknownMotionError")
    except UnknownMotionError:
        pass
