"""런타임 설정 — 환경변수 우선, 기본값 fallback."""
from __future__ import annotations

import os
from pathlib import Path

HOST = os.getenv("AD_SERVER_HOST", "0.0.0.0")
PORT = int(os.getenv("AD_SERVER_PORT", "8000"))

JOBS_DIR = Path(os.getenv("AD_JOBS_DIR", "/tmp/ad-server-jobs"))
JOBS_DIR.mkdir(parents=True, exist_ok=True)

AD_REPO_ROOT = Path(os.getenv("AD_REPO_ROOT", "/home/ingon/AR_book/AnimatedDrawings"))

ASSETS_DIR = Path(__file__).resolve().parent.parent / "assets"
DUMMY_GIF = ASSETS_DIR / "dummy.gif"
