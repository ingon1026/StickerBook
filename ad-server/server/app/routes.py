"""HTTP 라우트 정의."""
from __future__ import annotations

import uuid

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse

from app import ad_runner, settings
from app.motion_registry import UnknownMotionError

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}


@router.post("/process")
async def process(
    image: UploadFile = File(...),
    motion: str = Form(...),
):
    """이미지 + motion 받아 GIF 반환.

    현재 ad_runner 는 stub (입력 검증 후 dummy.gif).
    OpenGL render 해결되면 진짜 GIF 가 자동으로 흐름.
    """
    if not image.filename:
        raise HTTPException(status_code=400, detail="missing field: image")

    job_id = uuid.uuid4().hex[:8]
    job_dir = settings.JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    input_path = job_dir / image.filename
    input_path.write_bytes(await image.read())

    print(
        f"[/process] job={job_id} motion={motion} "
        f"image={image.filename} size={input_path.stat().st_size}B"
    )

    try:
        gif_path = ad_runner.run(input_path, motion, job_dir / "ad")
    except UnknownMotionError as e:
        raise HTTPException(status_code=400, detail=f"unknown motion: {motion}") from e
    except FileNotFoundError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except ad_runner.AdError as e:
        raise HTTPException(status_code=422, detail=f"AD failed: {e}") from e

    return FileResponse(
        path=gif_path,
        media_type="image/gif",
        filename="result.gif",
    )
