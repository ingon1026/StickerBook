"""FastAPI 앱 진입점.

실행:
    uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

from fastapi import FastAPI

from app.routes import router

app = FastAPI(title="ad-server", version="0.1.0")
app.include_router(router)
