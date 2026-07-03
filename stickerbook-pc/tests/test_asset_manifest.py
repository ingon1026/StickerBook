import json
from pathlib import Path

from export.asset_manifest import Manifest, StickerEntry


def test_manifest_roundtrip(tmp_path: Path) -> None:
    entry = StickerEntry(
        id="s001",
        name="사자",
        motion="dance_1",
        duration_ms=2000,
        fps=30,
        frame_count=60,
        width=512,
        height=512,
        frames_dir="stickers/s001/frames",
        gif_path="stickers/s001/animation.gif",
        texture_path="stickers/s001/texture.png",
        source_path="stickers/s001/source.png",
    )
    m = Manifest(format_version=1, generated_at="2026-05-14T10:00:00", stickers=[entry])

    path = tmp_path / "manifest.json"
    m.save(path)
    loaded = Manifest.load(path)

    assert loaded.format_version == 1
    assert len(loaded.stickers) == 1
    assert loaded.stickers[0].name == "사자"
    assert loaded.stickers[0].frame_count == 60


def test_manifest_load_rejects_unknown_version(tmp_path: Path) -> None:
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps({"format_version": 99, "generated_at": "x", "stickers": []}))

    try:
        Manifest.load(path)
    except ValueError as e:
        assert "format_version" in str(e)
        return
    raise AssertionError("expected ValueError for unknown format_version")


def test_manifest_upsert_replaces_by_id(tmp_path: Path) -> None:
    m = Manifest(format_version=1, generated_at="t0", stickers=[])
    e1 = StickerEntry(id="s001", name="A", motion="m", duration_ms=1000, fps=30,
                     frame_count=30, width=512, height=512,
                     frames_dir="stickers/s001/frames", gif_path="", texture_path="",
                     source_path="")
    e2 = StickerEntry(id="s001", name="B", motion="m", duration_ms=1000, fps=30,
                     frame_count=30, width=512, height=512,
                     frames_dir="stickers/s001/frames", gif_path="", texture_path="",
                     source_path="")
    m.upsert(e1)
    m.upsert(e2)
    assert len(m.stickers) == 1
    assert m.stickers[0].name == "B"
