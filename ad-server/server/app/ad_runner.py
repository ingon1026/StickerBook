"""AnimatedDrawings 호출 wrapper — 외부 의존성을 이 파일 안에 격리.

호출자(routes)는 `run(image_path, motion_id, work_dir) -> gif_path` 만 본다.

🔥 Real call 모드 (Sub-AD-Render-Fix 후):
    - image_to_annotations: torchserve 호출 → mask + skeleton 생성
    - 우리가 mvc_cfg yaml 직접 생성 + view.USE_MESA=true (헤드리스 핵심)
    - animated_drawings.render.start(mvc_cfg.yaml) → MesaView → gif

전제 조건 (run() 호출 시점에 필요):
    - torchserve 가 :8080 에 떠 있음 (drawn_humanoid_detector + pose_estimator)
    - libosmesa6 + libosmesa6-dev 설치됨
    - PyOpenGL osmesa 모듈 import 가능
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

from app import settings
from app.motion_registry import get_motion_yaml, UnknownMotionError  # noqa: F401

# AD 의 examples/ 폴더를 import path 에 추가.
# examples/ 는 패키지가 아니어서 자동 import 안 됨.
_AD_EXAMPLES = settings.AD_REPO_ROOT / "examples"
if str(_AD_EXAMPLES) not in sys.path:
    sys.path.insert(0, str(_AD_EXAMPLES))

# 기본 retarget config — 캐릭터 skeleton ↔ motion skeleton 매핑.
_RETARGET_CFG = _AD_EXAMPLES / "config" / "retarget" / "fair1_ppf.yaml"


class AdError(RuntimeError):
    """AD 처리 실패."""


def run(image_path: Path, motion_id: str, work_dir: Path) -> Path:
    """이미지 → motion 적용 → gif 경로 반환.

    Args:
        image_path: 입력 이미지 (jpg/png)
        motion_id: motion_registry 등록 id
        work_dir: 중간 산출물 저장 디렉토리 (호출자가 만들어서 줌)

    Returns:
        생성된 gif 파일의 절대 경로
    """
    # 1) 입력 검증
    if not image_path.exists():
        raise FileNotFoundError(f"image not found: {image_path}")

    if not _looks_like_image(image_path):
        raise AdError(f"input is not a valid image: {image_path}")

    motion_yaml = get_motion_yaml(motion_id)  # UnknownMotionError 가능

    if not _RETARGET_CFG.exists():
        raise AdError(f"retarget config missing: {_RETARGET_CFG}")

    work_dir.mkdir(parents=True, exist_ok=True)
    char_anno_dir = work_dir / "annotations"
    char_anno_dir.mkdir(exist_ok=True)

    # 2) annotation 생성 — torchserve 호출
    try:
        from image_to_annotations import image_to_annotations
    except ImportError as e:
        raise AdError(f"cannot import AD examples.image_to_annotations: {e}") from e

    try:
        image_to_annotations(str(image_path), str(char_anno_dir))
    except Exception as e:
        raise AdError(f"image_to_annotations failed: {e}") from e

    # 3) mvc_cfg dict 만들기 — view.USE_MESA=true 가 핵심
    output_gif = char_anno_dir / "video.gif"
    mvc_cfg = {
        "scene": {
            "ANIMATED_CHARACTERS": [{
                "character_cfg": str((char_anno_dir / "char_cfg.yaml").resolve()),
                "motion_cfg": str(motion_yaml.resolve()),
                "retarget_cfg": str(_RETARGET_CFG.resolve()),
            }],
        },
        "controller": {
            "MODE": "video_render",
            "OUTPUT_VIDEO_PATH": str(output_gif.resolve()),
        },
        "view": {
            "USE_MESA": True,  # ← 헤드리스 OpenGL 핵심
        },
    }

    # 4) mvc_cfg yaml 저장
    mvc_cfg_path = char_anno_dir / "mvc_cfg.yaml"
    with open(mvc_cfg_path, "w") as f:
        yaml.dump(mvc_cfg, f)

    # 5) render 실행 — MesaView 가 osmesa context 만들어 그림
    try:
        from animated_drawings import render
    except ImportError as e:
        raise AdError(f"cannot import animated_drawings.render: {e}") from e

    try:
        render.start(str(mvc_cfg_path))
    except Exception as e:
        raise AdError(f"AD render failed: {e}") from e

    # 6) 결과 gif 경로 반환
    if not output_gif.exists():
        raise AdError(f"AD produced no output at {output_gif}")
    if output_gif.stat().st_size < 100:
        raise AdError(f"AD output suspiciously small: {output_gif.stat().st_size}B")
    return output_gif


def _looks_like_image(p: Path) -> bool:
    """파일의 첫 바이트로 jpeg/png 매직 확인."""
    head = p.read_bytes()[:4]
    return head.startswith(b"\xff\xd8") or head.startswith(b"\x89PNG")
