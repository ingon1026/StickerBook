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


def test_run_raises_unknown_motion(tmp_path: Path):
    """알 수 없는 motion id → UnknownMotionError. 입력 검증 단계라 slow X."""
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    from app.motion_registry import UnknownMotionError
    try:
        ad_run(SAMPLE_DRAWING, "no_such_motion_xyz", tmp_path / "work")
        raise AssertionError("expected UnknownMotionError")
    except UnknownMotionError:
        pass


@pytest.mark.slow
def test_run_real_call_produces_gif(tmp_path: Path):
    """torchserve + osmesa 가 떠 있어야 PASS. 1-2분 소요.

    검증: 진짜 AD 가 도는지 + 결과 GIF 가 정상 크기인지.
    """
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    gif = ad_run(SAMPLE_DRAWING, "dab", tmp_path / "work")
    assert gif.exists(), f"gif missing: {gif}"
    assert gif.suffix == ".gif"
    assert gif.stat().st_size > 1000, f"gif too small: {gif.stat().st_size}B"
