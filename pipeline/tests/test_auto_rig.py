"""Unit tests for auto_rig.py (skeleton-based automatic parts rigging)."""
import unittest
import numpy as np

from auto_rig import skeletonize_mask, find_skeleton_nodes, measure_branch_length, analyze_skeleton


class SkeletonizeTest(unittest.TestCase):
    def test_empty_mask_returns_empty_skeleton(self):
        mask = np.zeros((20, 20), dtype=np.uint8)
        skel = skeletonize_mask(mask)
        self.assertEqual(skel.shape, mask.shape)
        self.assertEqual(skel.dtype, bool)
        self.assertFalse(skel.any())

    def test_horizontal_bar_reduces_to_line(self):
        # 20x40 mask with horizontal bar 5 rows tall spanning columns 5..34
        mask = np.zeros((20, 40), dtype=np.uint8)
        mask[8:13, 5:35] = 255
        skel = skeletonize_mask(mask)
        # Skeleton must be non-empty and strictly narrower than original
        self.assertTrue(skel.any())
        self.assertLess(skel.sum(), (mask > 0).sum())

    def test_accepts_uint8_255_mask(self):
        mask = np.zeros((10, 10), dtype=np.uint8)
        mask[2:8, 4:6] = 255
        skel = skeletonize_mask(mask)
        self.assertEqual(skel.dtype, bool)
        self.assertTrue(skel.any())


class SkeletonNodesTest(unittest.TestCase):
    def test_single_line_has_two_endpoints_no_branches(self):
        # Horizontal skeleton line: row 5, cols 2..8
        skel = np.zeros((10, 10), dtype=bool)
        skel[5, 2:9] = True
        endpoints, branches = find_skeleton_nodes(skel)
        self.assertEqual(sorted(endpoints), [(2, 5), (8, 5)])
        self.assertEqual(branches, [])

    def test_plus_shape_has_four_endpoints_one_branch(self):
        # + shape: horizontal row 5 cols 2..8, vertical col 5 rows 2..8
        skel = np.zeros((10, 10), dtype=bool)
        skel[5, 2:9] = True
        skel[2:9, 5] = True
        endpoints, branches = find_skeleton_nodes(skel)
        # 4 tips: (2,5), (8,5), (5,2), (5,8)
        self.assertEqual(sorted(endpoints), [(2, 5), (5, 2), (5, 8), (8, 5)])
        # 1 branching point at the cross center
        self.assertEqual(branches, [(5, 5)])

    def test_empty_skeleton_returns_empty_lists(self):
        skel = np.zeros((5, 5), dtype=bool)
        endpoints, branches = find_skeleton_nodes(skel)
        self.assertEqual(endpoints, [])
        self.assertEqual(branches, [])


class BranchLengthTest(unittest.TestCase):
    def test_straight_branch_length_matches_pixel_count(self):
        # Branch: endpoint (0, 5) to branching point (6, 5), horizontal row 5
        skel = np.zeros((10, 10), dtype=bool)
        skel[5, 0:7] = True
        skel[2:9, 6] = True  # vertical through (6,5) makes it a branching pt
        # endpoint at (0,5), walk until reaching (5,5) which has 4 skel neighbors
        # (4,5), (6,5), (4,6), (6,6) — stops at branching criterion
        length = measure_branch_length(skel, endpoint=(0, 5))
        self.assertEqual(length, 6)

    def test_isolated_endpoint_in_tiny_skel(self):
        # Single-pixel skeleton → 0 neighbors, but guard against zero-length
        skel = np.zeros((5, 5), dtype=bool)
        skel[2, 2] = True
        length = measure_branch_length(skel, endpoint=(2, 2))
        self.assertEqual(length, 1)


class AnalyzeSkeletonTest(unittest.TestCase):
    def test_stick_figure_returns_root_and_limb_endpoints(self):
        # Minimal stick figure:
        #   head dot at (15,5), neck to (15,15), arms (10,15)-(15,15)-(20,15),
        #   body (15,15)-(15,25), legs (15,25)-(10,32) and (15,25)-(20,32)
        skel = np.zeros((40, 30), dtype=bool)
        # head down the middle
        skel[5:15, 15] = True
        # arms horizontal
        skel[15, 10:21] = True
        # torso
        skel[15:26, 15] = True
        # legs diagonal (approximate by two vertical)
        skel[25:33, 10] = True
        skel[25:33, 20] = True
        # bridging so legs connect at hip (15,25)
        skel[25, 10:21] = True

        root_xy, endpoints = analyze_skeleton(skel, min_branch_px=2, max_endpoints=8)
        self.assertIsNotNone(root_xy)
        # At least 4 endpoints (head, arm-left, arm-right, leg-left, leg-right)
        self.assertGreaterEqual(len(endpoints), 4)
        self.assertLessEqual(len(endpoints), 8)

    def test_round_blob_skeleton_has_too_few_endpoints(self):
        # A single pixel — no limb structure
        skel = np.zeros((30, 30), dtype=bool)
        skel[15, 15] = True
        root_xy, endpoints = analyze_skeleton(skel, min_branch_px=2, max_endpoints=8)
        self.assertLess(len(endpoints), 3)

    def test_truncates_to_max_endpoints(self):
        # Star shape with 10 arms
        skel = np.zeros((41, 41), dtype=bool)
        cx, cy = 20, 20
        skel[cy, cx] = True
        for dx, dy in [
            (1, 0), (-1, 0), (0, 1), (0, -1),
            (1, 1), (-1, -1), (1, -1), (-1, 1),
            (2, 1), (-2, -1),
        ]:
            for step in range(1, 15):
                x = cx + dx * step
                y = cy + dy * step
                if 0 <= x < 41 and 0 <= y < 41:
                    skel[y, x] = True
        root_xy, endpoints = analyze_skeleton(skel, min_branch_px=2, max_endpoints=5)
        self.assertEqual(len(endpoints), 5)


import os
import tempfile

from auto_rig import try_auto_rig


class TryAutoRigTest(unittest.TestCase):
    def _stick_figure_mask(self):
        """Create a stick-figure mask with a clear torso + 4 limbs."""
        import cv2
        mask = np.zeros((200, 150), dtype=np.uint8)
        # Torso (thick vertical band)
        mask[60:140, 70:85] = 255
        # Head (circle-ish on top)
        cv2.circle(mask, (77, 45), 20, 255, -1)
        # Arms: horizontal bars left & right from torso top
        mask[65:75, 30:70] = 255
        mask[65:75, 85:125] = 255
        # Legs: diagonals down-left and down-right
        for i in range(40):
            mask[140 + i, max(0, 70 - i)] = 255
            mask[140 + i, min(149, 85 + i)] = 255
            mask[140 + i, max(0, 70 - i - 1):max(0, 70 - i)] = 255
            mask[140 + i, min(149, 85 + i):min(150, 85 + i + 2)] = 255
        return mask

    def test_stick_figure_writes_rig_json(self):
        mask = self._stick_figure_mask()
        with tempfile.TemporaryDirectory() as d:
            rig = try_auto_rig(mask, output_dir=d)
            self.assertIsNotNone(rig)
            self.assertEqual(rig["version"], 1)
            self.assertGreaterEqual(len(rig["parts"]), 4)  # root + 3+ limbs
            self.assertTrue(os.path.exists(os.path.join(d, "rig.json")))

    def test_round_blob_returns_none_and_removes_rig(self):
        # Circular mask — skeleton too simple for rig
        import cv2
        mask = np.zeros((100, 100), dtype=np.uint8)
        cv2.circle(mask, (50, 50), 30, 255, -1)
        with tempfile.TemporaryDirectory() as d:
            # Pre-seed a stale rig.json
            stale_path = os.path.join(d, "rig.json")
            with open(stale_path, "w") as f:
                f.write('{"stale": true}')
            rig = try_auto_rig(mask, output_dir=d)
            self.assertIsNone(rig)
            self.assertFalse(os.path.exists(stale_path))

    def test_empty_mask_returns_none(self):
        mask = np.zeros((50, 50), dtype=np.uint8)
        with tempfile.TemporaryDirectory() as d:
            rig = try_auto_rig(mask, output_dir=d)
            self.assertIsNone(rig)
            self.assertFalse(os.path.exists(os.path.join(d, "rig.json")))


if __name__ == "__main__":
    unittest.main()
