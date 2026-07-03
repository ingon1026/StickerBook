from __future__ import annotations

import json
import re
import shutil
from datetime import datetime
from pathlib import Path

import numpy as np
from PIL import Image, ImageSequence

from export.asset_manifest import StickerEntry

_ID_PATTERN = re.compile(r"^[a-z0-9_]+$")
_DEFAULT_FPS = 30


def _validate_id(sticker_id: str) -> None:
    if not _ID_PATTERN.match(sticker_id):
        raise ValueError(
            f"invalid sticker_id={sticker_id!r}: lowercase letters/digits/underscore only"
        )


def _chromakey_white_to_alpha(rgb: Image.Image, threshold: int = 250) -> Image.Image:
    """Pixels brighter than threshold on all channels become fully transparent."""
    arr = np.asarray(rgb.convert("RGB"), dtype=np.uint8)
    mask = (arr >= threshold).all(axis=-1)
    rgba = np.dstack([arr, np.where(mask, 0, 255).astype(np.uint8)])
    return Image.fromarray(rgba, mode="RGBA")


def _extract_frames(
    gif_path: Path, frames_dir: Path, chromakey_white: bool
) -> tuple[int, int, int, int]:
    """Returns (frame_count, fps, width, height)."""
    frames_dir.mkdir(parents=True, exist_ok=True)
    gif = Image.open(gif_path)

    durations = []
    count = 0
    width, height = gif.size
    for i, frame in enumerate(ImageSequence.Iterator(gif)):
        rgb = frame.convert("RGB")
        out = _chromakey_white_to_alpha(rgb) if chromakey_white else rgb.convert("RGBA")
        out.save(frames_dir / f"{i + 1:04d}.png", format="PNG")
        durations.append(int(frame.info.get("duration", 1000 // _DEFAULT_FPS)))
        count += 1

    if not durations:
        return 0, _DEFAULT_FPS, width, height

    avg_ms = max(1, sum(durations) // len(durations))
    fps = max(1, round(1000 / avg_ms))
    return count, fps, width, height


def build_sticker_entry(
    capture_dir: Path,
    out_root: Path,
    sticker_id: str,
    name: str,
    motion: str,
    chromakey_white: bool = True,
) -> StickerEntry:
    _validate_id(sticker_id)

    capture_dir = Path(capture_dir)
    out_root = Path(out_root)
    sdir = out_root / "stickers" / sticker_id
    sdir.mkdir(parents=True, exist_ok=True)

    gif_src = capture_dir / "video.gif"
    if not gif_src.is_file():
        raise FileNotFoundError(f"missing video.gif in {capture_dir}")

    frames_dir = sdir / "frames"
    frame_count, fps, width, height = _extract_frames(gif_src, frames_dir, chromakey_white)
    duration_ms = int(1000 * frame_count / fps) if fps > 0 else 0

    shutil.copyfile(gif_src, sdir / "animation.gif")
    for fname in ("texture.png", "input.png"):
        src = capture_dir / fname
        if src.is_file():
            dst_name = "source.png" if fname == "input.png" else fname
            shutil.copyfile(src, sdir / dst_name)

    rel_root = Path("stickers") / sticker_id
    entry = StickerEntry(
        id=sticker_id,
        name=name,
        motion=motion,
        duration_ms=duration_ms,
        fps=fps,
        frame_count=frame_count,
        width=width,
        height=height,
        frames_dir=str(rel_root / "frames"),
        gif_path=str(rel_root / "animation.gif"),
        texture_path=str(rel_root / "texture.png"),
        source_path=str(rel_root / "source.png"),
    )

    meta_path = sdir / "meta.json"
    _write_meta(meta_path, entry)
    return entry


def _write_meta(path: Path, entry: StickerEntry) -> None:
    payload = {
        "id": entry.id,
        "name": entry.name,
        "motion": entry.motion,
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "fps": entry.fps,
        "frame_count": entry.frame_count,
        "width": entry.width,
        "height": entry.height,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
