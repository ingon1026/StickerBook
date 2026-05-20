"""AnimatedDrawings 호출 wrapper — 외부 의존성을 이 파일 안에 격리.

호출자(routes)는 `run(image_path, motion_id, work_dir) -> gif_path` 만 본다.

🚧 현재 상태 (M3 일시 보류, sub-task "AD-Render-Fix" 로 분리):
    - 입력 검증 (파일 존재 / 이미지 매직바이트 / motion 등록) 까지만 활성
    - 실제 AD 호출은 dummy.gif 반환으로 stub
    - 진짜 AD 호출 코드는 git 이전 commit (이 파일의 직전 버전) 에 보존됨
    - 막힌 원인: WSL2 의 OpenGL 컨텍스트 생성 실패
      (torchserve OK + detection/pose OK + 마지막 video render 단계만 실패)

이 stub 의도: M4 Android 데모까지 통신 파이프라인 완성 후, 별도 sub-task 로
OpenGL render 해결.
"""
from __future__ import annotations

import shutil
from pathlib import Path

from app import settings
from app.motion_registry import get_motion_yaml, UnknownMotionError  # noqa: F401


class AdError(RuntimeError):
    """AD 처리 실패."""


def run(image_path: Path, motion_id: str, work_dir: Path) -> Path:
    """이미지 → motion 적용 → gif 경로 반환.

    🚧 현재 stub: 입력 검증 후 dummy.gif 복사본 반환.

    Raises:
        FileNotFoundError: image_path 없음
        AdError: 이미지 형식 아님
        UnknownMotionError: motion_id 미등록
    """
    if not image_path.exists():
        raise FileNotFoundError(f"image not found: {image_path}")

    if not _looks_like_image(image_path):
        raise AdError(f"input is not a valid image: {image_path}")

    # motion_id 검증 (등록 안 됐으면 UnknownMotionError)
    _ = get_motion_yaml(motion_id)

    work_dir.mkdir(parents=True, exist_ok=True)
    out = work_dir / "result.gif"
    shutil.copy(settings.DUMMY_GIF, out)
    return out


def _looks_like_image(p: Path) -> bool:
    """파일의 첫 바이트로 jpeg/png 매직 확인."""
    head = p.read_bytes()[:4]
    return head.startswith(b"\xff\xd8") or head.startswith(b"\x89PNG")
