"""FastAPI 앱 진입점.

실행:
    uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import shutil
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import settings
from app.routes import router


@asynccontextmanager
async def lifespan(app: FastAPI):
    # startup: 이전 실행에서 남은 stale job 디렉토리 정리.
    shutil.rmtree(settings.JOBS_DIR, ignore_errors=True)
    settings.JOBS_DIR.mkdir(parents=True, exist_ok=True)
    yield


app = FastAPI(title="ad-server", version="0.1.0", lifespan=lifespan)
app.include_router(router)
