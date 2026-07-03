# CLAUDE.md — stickerbook

## Project context
V1 Python PC PoC for the "2.5D 증강현실 (AR Stickerbook)" sub-project of AR_book.

Goal: a user points a webcam at a child's drawing on a sketchbook/notebook page, presses SPACE, and the whole frame is sent to AnimatedDrawings (subprocess) which auto-extracts the character skeleton and animates it using a BVH motion. The resulting GIF is chroma-keyed and homography-anchored onto the live camera as an AR sticker. M-key records the user's own motion via MediaPipe Pose → BVH and registers it into the motion library.

V2 (galaxy tablet) is a thin client + PC server architecture and lives elsewhere — not this folder.

See `docs/DESIGN.md` for the design spec.

## References
- Main rigging/animation base: https://github.com/facebookresearch/AnimatedDrawings (MIT)
- 2.5D sticker AR inspiration: https://github.com/tatsuya-ogawa/RakugakiAR (Swift/ARKit)
- Sibling PoC code (model reuse): `../LivingDrawing/`

## Module boundaries
Each module under `capture/`, `track/`, `render/`, `animate/`, `motion/`, `export/` has a single narrow responsibility and a documented interface. Cross-module communication goes through the dataclasses defined in `render/sticker.py` and `track/world_anchor.py`.

Do not merge modules without updating `docs/DESIGN.md` first.

## Implementation rules for stickerbook
- V1 PC only — keep this folder as PC Python PoC. Galaxy/ARCore code lives in a sibling folder if/when added, never here.
- AD subprocess inference must run on a background thread (`ThreadPoolExecutor`); the main OpenCV loop never blocks on the SPACE-triggered AD job.
- `WorldAnchor` is a Protocol. Only one concrete implementation (`HomographyAnchor`). Additional implementations must land behind the same interface.
- Exported assets must round-trip: loading `assets/captures/<timestamp>_<motion>/` artifacts in the AnimatedDrawings local demo must not error.
- Any new dependency goes into `requirements.txt`. No ad-hoc `pip install`.

## Milestones
M1 → M10 in `docs/DESIGN.md` (M10 = detection/SAM polishing → frame-whole AD path). See README for current per-milestone status.

## Avoid
- Mocking the webcam or SAM inside tests that are supposed to verify live behavior
- Abstract base classes for single-use interfaces (use `typing.Protocol`)
- Long docstrings; one-line comments only when the "why" is non-obvious
- Feature creep beyond the Stage 1 scope in `docs/DESIGN.md`
