from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from export.android_assets import build_sticker_entry
from export.asset_manifest import StickerEntry


def _make_dummy_capture(tmp_path: Path) -> Path:
    """Fake V1 capture directory with minimal files."""
    cap = tmp_path / "capture"
    cap.mkdir()

    # video.gif: 3-frame 64x64 RGBA-ish (palette GIF)
    frames = [Image.new("RGB", (64, 64), color=(i * 80, 0, 0)) for i in range(3)]
    frames[0].save(
        cap / "video.gif",
        save_all=True,
        append_images=frames[1:],
        duration=100,  # ms per frame
        loop=0,
    )
    Image.new("RGBA", (64, 64), color=(0, 0, 0, 0)).save(cap / "texture.png")
    Image.new("L", (64, 64), color=0).save(cap / "mask.png")
    Image.new("RGB", (64, 64), color=(128, 128, 128)).save(cap / "input.png")
    return cap


def test_build_sticker_entry_creates_full_layout(tmp_path: Path) -> None:
    capture = _make_dummy_capture(tmp_path)
    out_root = tmp_path / "assets"

    entry: StickerEntry = build_sticker_entry(
        capture_dir=capture,
        out_root=out_root,
        sticker_id="s001",
        name="테스트",
        motion="dance_1",
    )

    assert entry.id == "s001"
    assert entry.name == "테스트"
    assert entry.motion == "dance_1"
    assert entry.frame_count >= 1
    assert entry.width == 64 and entry.height == 64

    sdir = out_root / "stickers" / "s001"
    assert (sdir / "meta.json").is_file()
    assert (sdir / "texture.png").is_file()
    assert (sdir / "animation.gif").is_file()
    assert (sdir / "source.png").is_file()
    frames = sorted((sdir / "frames").glob("*.png"))
    assert len(frames) == entry.frame_count
    # frame name pattern
    assert frames[0].name == "0001.png"


def test_build_sticker_entry_rejects_invalid_id(tmp_path: Path) -> None:
    capture = _make_dummy_capture(tmp_path)
    with pytest.raises(ValueError):
        build_sticker_entry(
            capture_dir=capture, out_root=tmp_path / "assets",
            sticker_id="Invalid ID!", name="x", motion="m",
        )


def test_build_sticker_entry_extracts_alpha_frames_when_chromakey(tmp_path: Path) -> None:
    """GIF has white background; output frames should preserve alpha (white→transparent)."""
    capture = _make_dummy_capture(tmp_path)
    # Force GIF to be near-white
    frames = [Image.new("RGB", (64, 64), color=(255, 255, 255)) for _ in range(3)]
    frames[0].save(
        capture / "video.gif", save_all=True, append_images=frames[1:],
        duration=100, loop=0,
    )

    entry = build_sticker_entry(
        capture_dir=capture, out_root=tmp_path / "assets",
        sticker_id="s002", name="x", motion="m", chromakey_white=True,
    )

    frame = Image.open(tmp_path / "assets" / "stickers" / "s002" / "frames" / "0001.png")
    assert frame.mode == "RGBA"
    arr = np.array(frame)
    # white pixels should be transparent
    assert (arr[..., 3] == 0).all()
