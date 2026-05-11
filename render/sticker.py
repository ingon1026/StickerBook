from __future__ import annotations

from dataclasses import dataclass
from typing import Tuple

import numpy as np


@dataclass(frozen=True)
class StickerAsset:
    """Raw character cutout. Channels are BGR+A (cv2-native); convert to RGB on PNG export."""

    texture_bgra: np.ndarray
    mask_u8: np.ndarray
    source_region: Tuple[int, int, int, int]  # (x, y, w, h)
