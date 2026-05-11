from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
import pytest

from animate.animated_drawings_runner import AnimationResult
from app import AnchoredSticker, App, AppAction, _PerfTracker
from track.homography_anchor import HomographyAnchor


def test_handle_key_quit_on_lowercase_q() -> None:
    app = App(camera_source=0)
    assert app._handle_key(ord("q")) == AppAction.QUIT


def test_handle_key_quit_on_uppercase_q() -> None:
    app = App(camera_source=0)
    assert app._handle_key(ord("Q")) == AppAction.QUIT


def test_handle_key_quit_on_escape() -> None:
    app = App(camera_source=0)
    assert app._handle_key(27) == AppAction.QUIT


def test_handle_key_returns_none_when_no_key_pressed() -> None:
    app = App(camera_source=0)
    assert app._handle_key(-1) is None


def test_handle_key_reset_on_lowercase_r() -> None:
    app = App(camera_source=0)
    assert app._handle_key(ord("r")) == AppAction.RESET


def test_handle_key_reset_on_uppercase_r() -> None:
    app = App(camera_source=0)
    assert app._handle_key(ord("R")) == AppAction.RESET


def test_handle_key_save_on_lowercase_s() -> None:
    app = App(camera_source=0)
    assert app._handle_key(ord("s")) == AppAction.SAVE


def test_handle_key_save_on_uppercase_s() -> None:
    app = App(camera_source=0)
    assert app._handle_key(ord("S")) == AppAction.SAVE


def test_handle_key_returns_none_for_other_keys() -> None:
    app = App(camera_source=0)
    assert app._handle_key(ord("a")) is None
    assert app._handle_key(ord("x")) is None


def test_reset_stickers_cleans_each_work_dir(tmp_path: Path) -> None:
    app = App()
    work_a = tmp_path / "a"; work_a.mkdir()
    work_b = tmp_path / "b"; work_b.mkdir()

    def _stub_sticker_with_workdir(wd: Path) -> AnchoredSticker:
        asset = MagicMock()
        asset.source_region = (0, 0, 10, 10)
        asset.texture_bgra = np.zeros((10, 10, 4), dtype=np.uint8)
        return AnchoredSticker(
            sticker=asset, anchor=HomographyAnchor(),
            animation_work_dir=wd,
        )

    app._anchored = [_stub_sticker_with_workdir(work_a), _stub_sticker_with_workdir(work_b)]
    app._reset_stickers()

    assert not work_a.exists()
    assert not work_b.exists()
    assert app._anchored == []


def test_choose_popup_lift_ratio_full_when_sy_at_least_sh() -> None:
    from app import _choose_popup_lift_ratio
    # sy=300, sh=200 -> max_ratio=1.5, capped to 1.0
    assert _choose_popup_lift_ratio((100, 300, 200, 200)) == 1.0


def test_choose_popup_lift_ratio_proportional_when_sy_less_than_sh() -> None:
    from app import _choose_popup_lift_ratio
    # sy=50, sh=200 -> ratio=0.25
    assert _choose_popup_lift_ratio((100, 50, 200, 200)) == pytest.approx(0.25)


def test_choose_popup_lift_ratio_zero_when_at_top_edge() -> None:
    from app import _choose_popup_lift_ratio
    # sy=0 -> ratio=0 (billboard sits flat at source)
    assert _choose_popup_lift_ratio((100, 0, 200, 200)) == 0.0


def _dummy_anim_result() -> AnimationResult:
    return AnimationResult(
        success=True, video_path=None, char_cfg_path=None,
        duration_sec=0.0, error=None,
    )


def test_promote_to_live_caps_popup_for_high_source_region() -> None:
    app = App()
    asset = MagicMock()
    asset.source_region = (100, 30, 200, 250)  # sy=30, sh=250 -> ratio=0.12
    asset.texture_bgra = np.zeros((250, 200, 4), dtype=np.uint8)
    anchor = HomographyAnchor()

    item = app._promote_to_live(asset, anchor, _dummy_anim_result())

    assert item.popup_lift_ratio == pytest.approx(30.0 / 250.0)


def test_promote_to_live_keeps_full_popup_for_centered_source_region() -> None:
    app = App()
    asset = MagicMock()
    asset.source_region = (100, 400, 200, 200)  # sy=400, sh=200 -> ratio=1.0
    asset.texture_bgra = np.zeros((200, 200, 4), dtype=np.uint8)
    anchor = HomographyAnchor()

    item = app._promote_to_live(asset, anchor, _dummy_anim_result())

    assert item.popup_lift_ratio == 1.0


# --- Slot resolver (multi-sticker layout) -------------------------------------

from app import _resolve_slot  # noqa: E402
from render.sticker import StickerAsset  # noqa: E402


def _fake_anchored(
    source_region, offset_norm=(0.0, 0.0), slot_index=0
) -> AnchoredSticker:
    """Build a minimal AnchoredSticker for the resolver to inspect."""
    return AnchoredSticker(
        sticker=StickerAsset(
            texture_bgra=np.zeros((10, 10, 4), dtype=np.uint8),
            mask_u8=np.zeros((10, 10), dtype=np.uint8),
            source_region=source_region,
        ),
        anchor=MagicMock(),
        lateral_offset_norm=offset_norm,
        slot_index=slot_index,
    )


def test_first_sticker_gets_slot_0_center() -> None:
    slot, offset, scale = _resolve_slot((100, 100, 200, 300), [])
    assert slot == 0
    assert offset == (0.0, 0.0)
    assert scale == 1.0


def test_second_sticker_picks_a_side_slot() -> None:
    """With one sticker centered, second should land at a side (slot >= 1)."""
    first = _fake_anchored((100, 100, 200, 300))
    slot, offset, scale = _resolve_slot((100, 100, 200, 300), [first])
    assert slot >= 1
    assert offset != (0.0, 0.0)
    assert scale < 1.0


def test_resolver_picks_far_slot_to_avoid_existing() -> None:
    """Existing sticker on the right → second should go left/diagonal-left."""
    first = _fake_anchored((400, 100, 200, 300))  # existing center ≈ (500, 250)
    # New sticker default center (200, 250). Slots that push left maximize
    # distance from the existing right-side sticker.
    slot, offset, scale = _resolve_slot((100, 100, 200, 300), [first])
    assert offset[0] <= 0.0


def test_resolver_excludes_slot_0_for_non_first() -> None:
    """Slot 0 is reserved for the first sticker; resolver never returns it."""
    first = _fake_anchored((100, 100, 200, 300))
    slot, _, _ = _resolve_slot((100, 100, 200, 300), [first])
    assert slot != 0
