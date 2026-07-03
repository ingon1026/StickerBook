#!/usr/bin/env python3
"""Walk `assets/captures/` and build a unified `stickerbook_assets/` folder."""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import zipfile
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from export.android_assets import build_sticker_entry
from export.asset_manifest import FORMAT_VERSION, Manifest

_CAPTURE_PATTERN = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_(?P<motion>.+)$")


def _slugify(name: str) -> str:
    s = name.lower()
    s = re.sub(r"[^a-z0-9_]+", "_", s)
    s = re.sub(r"_+", "_", s).strip("_")
    return s or "sticker"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument(
        "--captures-dir",
        type=Path,
        default=Path("assets/captures"),
        help="directory containing <ts>_<motion>/ subdirs (V1 captures)",
    )
    p.add_argument(
        "--output",
        type=Path,
        default=Path("stickerbook_assets"),
        help="output asset root",
    )
    p.add_argument("--zip", action="store_true", help="also emit <output>.zip")
    p.add_argument(
        "--no-chromakey",
        action="store_true",
        help="skip white→alpha (use when GIF already has alpha)",
    )
    p.add_argument("--force", action="store_true",
                   help="overwrite existing output dir (required if --output already exists)")
    args = p.parse_args()

    captures = sorted(d for d in args.captures_dir.iterdir() if d.is_dir())
    if not captures:
        print(f"no capture dirs under {args.captures_dir}")
        return 1

    if args.output.exists():
        if not args.force:
            print(f"[err] {args.output} already exists; pass --force to overwrite")
            return 1
        shutil.rmtree(args.output)
    args.output.mkdir(parents=True)

    manifest = Manifest(
        format_version=FORMAT_VERSION,
        generated_at=datetime.now().isoformat(timespec="seconds"),
        stickers=[],
    )

    for i, cap in enumerate(captures, start=1):
        m = _CAPTURE_PATTERN.match(cap.name)
        motion = m.group("motion") if m else "unknown"
        sticker_id = f"s{i:03d}"
        name = _slugify(cap.name)
        try:
            entry = build_sticker_entry(
                capture_dir=cap,
                out_root=args.output,
                sticker_id=sticker_id,
                name=name,
                motion=motion,
                chromakey_white=not args.no_chromakey,
            )
        except (OSError, ValueError, FileNotFoundError) as e:
            print(f"[skip] {cap}: {e}")
            continue
        manifest.upsert(entry)
        print(f"[ok] {cap.name} -> {sticker_id} ({entry.frame_count} frames)")

    manifest.save(args.output / "manifest.json")
    print(f"\nmanifest written: {args.output / 'manifest.json'}")
    print(f"stickers: {len(manifest.stickers)}")

    if args.zip:
        zip_path = args.output.with_suffix(".zip")
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for f in args.output.rglob("*"):
                zf.write(f, f.relative_to(args.output.parent))
        print(f"zip: {zip_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
