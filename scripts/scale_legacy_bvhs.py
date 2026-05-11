"""One-shot: scale legacy m-unit BVHs (our writer pre-fix) to cm convention.

bvh_writer used to emit MediaPipe world m units; AD's my_dance.yaml retarget
applies `scale: 0.025` assuming Rokoko cm — so legacy BVHs retarget at ~100x
too small, making the character look static. This script multiplies OFFSETs
in HIERARCHY and Xposition/Yposition/Zposition channels in MOTION by 100,
in-place, for both library/ and AD examples/bvh/. Idempotent: skips BVHs
whose max abs OFFSET >= 1.0 (already cm or never m-unit).

Usage:
    cd stickerbook
    python scripts/scale_legacy_bvhs.py
"""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

from config import AD_REPO_PATH, ROOT  # noqa: E402


SCALE = 100.0
LIBRARY_DIR = ROOT / "assets" / "motions" / "library"
AD_BVH_DIR = AD_REPO_PATH / "examples" / "bvh"

OFFSET_RE = re.compile(r"(OFFSET\s+)(-?\d+\.?\d*)\s+(-?\d+\.?\d*)\s+(-?\d+\.?\d*)")


def _max_abs_offset(text: str) -> float:
    h = text[: text.index("MOTION")]
    vals = []
    for m in OFFSET_RE.finditer(h):
        vals.extend(abs(float(m.group(i))) for i in (2, 3, 4))
    return max(vals) if vals else 0.0


def _channel_layout(text: str) -> list[tuple[int, int]]:
    """Return list of (start_chan_idx, num_pos_chans) per joint, in DFS order.

    Reads HIERARCHY's CHANNELS lines. Returns position-channel slices to scale
    in MOTION rows. We assume Xposition/Yposition/Zposition are listed in that
    order at the start of the channel set (standard); count them per joint.
    """
    layout: list[tuple[int, int]] = []
    cursor = 0
    h = text[: text.index("MOTION")]
    for line in h.splitlines():
        s = line.strip()
        if not s.startswith("CHANNELS"):
            continue
        toks = s.split()
        n_chan = int(toks[1])
        names = toks[2 : 2 + n_chan]
        n_pos = sum(1 for n in names if n.endswith("position"))
        layout.append((cursor, n_pos))
        cursor += n_chan
    return layout


def _scale_motion_rows(text: str, layout: list[tuple[int, int]]) -> str:
    motion_idx = text.index("MOTION")
    head = text[:motion_idx]
    motion_block = text[motion_idx:]
    out_lines = []
    body_started = False
    for line in motion_block.splitlines():
        if not body_started:
            out_lines.append(line)
            if line.strip().startswith("Frame Time"):
                body_started = True
            continue
        if not line.strip():
            out_lines.append(line)
            continue
        vals = line.split()
        nums = [float(v) for v in vals]
        for start, n_pos in layout:
            for k in range(n_pos):
                nums[start + k] *= SCALE
        out_lines.append(" ".join(f"{v:.6f}" for v in nums))
    return head + "\n".join(out_lines)


def _scale_offsets(text: str) -> str:
    motion_idx = text.index("MOTION")
    head = text[:motion_idx]
    tail = text[motion_idx:]

    def repl(m: re.Match) -> str:
        x = float(m.group(2)) * SCALE
        y = float(m.group(3)) * SCALE
        z = float(m.group(4)) * SCALE
        return f"{m.group(1)}{x:.6f} {y:.6f} {z:.6f}"

    return OFFSET_RE.sub(repl, head) + tail


def convert_one(bvh_path: Path) -> bool:
    """Return True if scaled, False if skipped."""
    text = bvh_path.read_text()
    max_off = _max_abs_offset(text)
    if max_off >= 1.0:
        print(f"[skip] {bvh_path.name:30s} max_offset={max_off:.4f} (already cm-ish)")
        return False
    layout = _channel_layout(text)
    text = _scale_offsets(text)
    text = _scale_motion_rows(text, layout)
    bvh_path.write_text(text)
    print(f"[scaled] {bvh_path.name:28s} max_offset {max_off:.4f} → {max_off*SCALE:.2f}")
    return True


def main() -> int:
    if not LIBRARY_DIR.is_dir():
        print(f"[err] library dir not found: {LIBRARY_DIR}", file=sys.stderr)
        return 1
    n_scaled = 0
    n_skip = 0
    for bvh in sorted(LIBRARY_DIR.glob("*.bvh")):
        if convert_one(bvh):
            n_scaled += 1
            ad_copy = AD_BVH_DIR / bvh.name
            if ad_copy.is_file():
                shutil.copyfile(bvh, ad_copy)
                print(f"          → mirrored to {ad_copy}")
            else:
                print(f"          (no AD mirror at {ad_copy}, skipping copy)")
        else:
            n_skip += 1
    print(f"\n[done] scaled={n_scaled} skipped={n_skip}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
