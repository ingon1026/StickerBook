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


def test_process_returns_gif_for_valid_input():
    """정상 입력 — 현재 stub 이라 dummy.gif 와 동일 바이트."""
    fake = io.BytesIO(b"\xff\xd8\xff\xd9")
    response = client.post(
        "/process",
        files={"image": ("x.jpg", fake, "image/jpeg")},
        data={"motion": "dab"},
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("image/gif")
    assert len(response.content) > 0
