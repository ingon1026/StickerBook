"""HTTP 라우트 정의."""
from __future__ import annotations

import shutil
import uuid
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

from app import ad_runner, settings
from app.motion_registry import UnknownMotionError

router = APIRouter()


def _cleanup(job_dir: Path) -> None:
    """job 디렉토리 통째 삭제 (이미 없어도 무방)."""
    shutil.rmtree(job_dir, ignore_errors=True)


@router.get("/health")
def health():
    return {"status": "ok"}


@router.post("/process")
async def process(
    image: UploadFile = File(...),
    motion: str = Form(...),
):
    """이미지 + motion 받아 GIF 반환.

    job 디렉토리는 처리 후 정리한다 — 성공 시 응답 전송 완료 후
    (BackgroundTask), 실패 시 즉시. 디스크에 job 이 쌓이지 않게.
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
        _cleanup(job_dir)
        raise HTTPException(status_code=400, detail=f"unknown motion: {motion}") from e
    except FileNotFoundError as e:
        _cleanup(job_dir)
        raise HTTPException(status_code=400, detail=str(e)) from e
    except ad_runner.AdError as e:
        _cleanup(job_dir)
        raise HTTPException(status_code=422, detail=f"AD failed: {e}") from e

    # 성공 — 응답(gif) 전송이 끝난 뒤 job_dir 삭제.
    return FileResponse(
        path=gif_path,
        media_type="image/gif",
        filename="result.gif",
        background=BackgroundTask(_cleanup, job_dir),
    )
