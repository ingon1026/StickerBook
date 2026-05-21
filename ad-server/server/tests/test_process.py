"""POST /process 의 실제 동작 검증 — routes ↔ ad_runner 통합."""
import io
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

AD_REPO = Path("/home/ingon/AR_book/AnimatedDrawings")
SAMPLE_DRAWING = AD_REPO / "examples" / "drawings" / "garlic.png"


def test_process_rejects_missing_image():
    response = client.post("/process", data={"motion": "dab"})
    assert response.status_code in (400, 422)


def test_process_rejects_missing_motion():
    fake = io.BytesIO(b"\xff\xd8\xff\xd9")
    response = client.post(
        "/process",
        files={"image": ("x.jpg", fake, "image/jpeg")},
    )
    assert response.status_code in (400, 422)


def test_process_rejects_unknown_motion():
    """알 수 없는 motion id → 400."""
    fake = io.BytesIO(b"\xff\xd8\xff\xd9")
    response = client.post(
        "/process",
        files={"image": ("x.jpg", fake, "image/jpeg")},
        data={"motion": "no_such_motion_xyz"},
    )
    assert response.status_code == 400


def test_process_rejects_non_image_input():
    """텍스트 파일 보내면 AdError → 422."""
    response = client.post(
        "/process",
        files={"image": ("x.txt", io.BytesIO(b"hello"), "text/plain")},
        data={"motion": "dab"},
    )
    assert response.status_code == 422


def test_process_cleans_job_dir_on_failure():
    """실패(422)해도 job 폴더가 JOBS_DIR 에 안 쌓인다."""
    from app import settings

    before = (
        set(settings.JOBS_DIR.iterdir()) if settings.JOBS_DIR.exists() else set()
    )
    response = client.post(
        "/process",
        files={"image": ("x.txt", io.BytesIO(b"hello"), "text/plain")},
        data={"motion": "dab"},
    )
    assert response.status_code == 422
    after = (
        set(settings.JOBS_DIR.iterdir()) if settings.JOBS_DIR.exists() else set()
    )
    assert after == before, "실패한 요청의 job 폴더가 정리되지 않음"


import pytest


@pytest.mark.slow
def test_process_cleans_job_dir_on_success():
    """정상 처리 후에도 job 폴더가 안 쌓인다 (BackgroundTask 청소)."""
    from app import settings

    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    before = (
        set(settings.JOBS_DIR.iterdir()) if settings.JOBS_DIR.exists() else set()
    )
    with SAMPLE_DRAWING.open("rb") as f:
        response = client.post(
            "/process",
            files={"image": ("garlic.png", f, "image/png")},
            data={"motion": "dab"},
        )
    assert response.status_code == 200, response.text
    after = (
        set(settings.JOBS_DIR.iterdir()) if settings.JOBS_DIR.exists() else set()
    )
    assert after == before, "성공한 요청의 job 폴더가 정리되지 않음"


@pytest.mark.slow
def test_process_returns_gif_for_valid_input():
    """진짜 AD 호출 — torchserve + osmesa 필요. 1-2분."""
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    with SAMPLE_DRAWING.open("rb") as f:
        response = client.post(
            "/process",
            files={"image": ("garlic.png", f, "image/png")},
            data={"motion": "dab"},
        )
    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("image/gif")
    assert len(response.content) > 1000
