"""Unit tests for rigging.py (parts separation + rig I/O)."""
import unittest
import numpy as np

import json
import os
import tempfile

from rigging import partition_mask_by_seeds, compute_pivot, save_rig, load_rig, RIG_VERSION


class PartitionTest(unittest.TestCase):
    def test_split_rectangle_horizontally(self):
        # 10x20 흰 마스크, 왼쪽/오른쪽 중앙에 seed
        mask = np.full((10, 20), 255, dtype=np.uint8)
        seeds = [(4, 5), (15, 5)]  # (x, y)
        labels = partition_mask_by_seeds(mask, seeds)

        self.assertEqual(labels.shape, (10, 20))
        # 왼쪽 절반 = label 0, 오른쪽 절반 = label 1
        self.assertEqual(labels[5, 2], 0)
        self.assertEqual(labels[5, 17], 1)
        # 마스크 외부가 아니므로 -1 은 없어야 함
        self.assertTrue((labels >= 0).all())

    def test_u_shape_geodesic(self):
        # U자 마스크. 두 팔의 끝에 seed. 유클리드로는 섞이지만 geodesic 은 각 팔을 덮어야 함.
        mask = np.zeros((20, 30), dtype=np.uint8)
        # 왼팔: 열 0-4, 행 0-19
        mask[0:20, 0:5] = 255
        # 오른팔: 열 25-29, 행 0-19
        mask[0:20, 25:30] = 255
        # 아래 bridge: 행 15-19, 열 0-29
        mask[15:20, 0:30] = 255
        seeds = [(2, 0), (27, 0)]  # 두 팔 상단
        labels = partition_mask_by_seeds(mask, seeds)

        # 왼팔 상단 = 0, 오른팔 상단 = 1
        self.assertEqual(labels[0, 2], 0)
        self.assertEqual(labels[0, 27], 1)
        # 외부는 -1
        self.assertEqual(labels[5, 15], -1)

    def test_outside_mask_is_minus_one(self):
        mask = np.zeros((5, 5), dtype=np.uint8)
        mask[1:4, 1:4] = 255
        labels = partition_mask_by_seeds(mask, [(2, 2)])
        # 내부는 0
        self.assertEqual(labels[2, 2], 0)
        # 외부는 -1
        self.assertEqual(labels[0, 0], -1)

    def test_empty_seeds_returns_all_minus_one(self):
        mask = np.full((5, 5), 255, dtype=np.uint8)
        labels = partition_mask_by_seeds(mask, [])
        self.assertTrue((labels == -1).all())

    def test_seed_outside_mask_is_ignored(self):
        # 마스크는 내부 사각형, seed 는 외부
        mask = np.zeros((10, 10), dtype=np.uint8)
        mask[3:7, 3:7] = 255
        labels = partition_mask_by_seeds(mask, [(0, 0)])  # 외부 seed
        # 외부 seed 는 무시되어 결과가 비어야 함 (모든 내부가 unreachable)
        self.assertEqual(labels[5, 5], -1)

    def test_duplicate_seed_handled(self):
        # 중복 seed 는 두 번째가 덮어씀 — 두 label 의 영역 합은 모든 mask 픽셀
        mask = np.full((5, 5), 255, dtype=np.uint8)
        labels = partition_mask_by_seeds(mask, [(2, 2), (2, 2)])
        # 한 label (0 또는 1) 이 중앙 픽셀을 차지, 나머지는 그 주변으로 퍼짐
        self.assertIn(labels[2, 2], (0, 1))
        self.assertTrue((labels >= 0).all())  # 모든 mask 픽셀 할당됨


class PivotTest(unittest.TestCase):
    def test_contact_point_horizontal(self):
        # parent: 왼쪽 덩어리 (열 0-9), child: 오른쪽 덩어리 (열 10-19) — 열 10 에서 접촉
        h, w = 10, 20
        parent_mask = np.zeros((h, w), dtype=np.uint8)
        parent_mask[:, 0:10] = 255
        child_mask = np.zeros((h, w), dtype=np.uint8)
        child_mask[:, 10:20] = 255

        pivot_x, pivot_y = compute_pivot(child_mask, parent_mask)
        # pivot 은 child 내부이면서 parent 경계 가장 가까운 점 → x == 10 근처
        self.assertEqual(pivot_x, 10)

    def test_disjoint_falls_back_to_centroid(self):
        # parent 왼쪽 위, child 오른쪽 아래 — 전혀 안 닿음
        h, w = 20, 20
        parent_mask = np.zeros((h, w), dtype=np.uint8)
        parent_mask[0:3, 0:3] = 255
        child_mask = np.zeros((h, w), dtype=np.uint8)
        child_mask[15:20, 15:20] = 255

        pivot_x, pivot_y = compute_pivot(child_mask, parent_mask)
        # child centroid = (17, 17) 근처
        self.assertAlmostEqual(pivot_x, 17, delta=1)
        self.assertAlmostEqual(pivot_y, 17, delta=1)


class RigIoTest(unittest.TestCase):
    def test_roundtrip_preserves_fields(self):
        rig = {
            "version": RIG_VERSION,
            "source_object": "object.png",
            "image_size": [640, 480],
            "parts": [
                {"id": 0, "name": "root", "parent": -1,
                 "seed_xy": [320, 240], "pivot_xy": [320, 240]},
                {"id": 1, "name": "child_1", "parent": 0,
                 "seed_xy": [200, 180], "pivot_xy": [220, 200]},
            ],
        }
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "rig.json")
            save_rig(rig, path)
            loaded = load_rig(path)

        self.assertEqual(loaded, rig)


from rigging import build_parts_from_rig


class BuildPartsTest(unittest.TestCase):
    def test_yields_one_part_per_rig_entry(self):
        # 10x20 단순 흰 마스크
        mask = np.full((10, 20), 255, dtype=np.uint8)
        # RGBA: 모든 픽셀 255 red
        rgba = np.zeros((10, 20, 4), dtype=np.uint8)
        rgba[..., 0] = 255
        rgba[..., 3] = 255

        rig = {
            "version": RIG_VERSION,
            "source_object": "object.png",
            "image_size": [20, 10],
            "parts": [
                {"id": 0, "name": "root", "parent": -1,
                 "seed_xy": [5, 5], "pivot_xy": [5, 5]},
                {"id": 1, "name": "child_1", "parent": 0,
                 "seed_xy": [15, 5], "pivot_xy": [10, 5]},
            ],
        }

        parts = build_parts_from_rig(rgba, mask, rig)
        self.assertEqual(len(parts), 2)

        # Root covers the full mask (no body fragmentation); child covers only
        # its own Voronoi region. They overlap by design so children can rotate
        # without exposing a gap at the pivot.
        root_alpha = parts[0].object_rgba[:, :, 3]
        child_alpha = parts[1].object_rgba[:, :, 3]
        np.testing.assert_array_equal(root_alpha > 0, mask > 0)
        # Child is a strict subset of root (fully overlapped).
        self.assertTrue(((child_alpha > 0) & (root_alpha > 0) == (child_alpha > 0)).all())
        # Child region is non-empty.
        self.assertTrue((child_alpha > 0).any())

    def test_parent_and_pivot_propagated(self):
        mask = np.full((10, 20), 255, dtype=np.uint8)
        rgba = np.zeros((10, 20, 4), dtype=np.uint8)
        rgba[..., 3] = 255
        rig = {
            "version": RIG_VERSION,
            "source_object": "object.png",
            "image_size": [20, 10],
            "parts": [
                {"id": 0, "name": "root", "parent": -1,
                 "seed_xy": [5, 5], "pivot_xy": [5, 5]},
                {"id": 1, "name": "child_1", "parent": 0,
                 "seed_xy": [15, 5], "pivot_xy": [10, 5]},
            ],
        }
        parts = build_parts_from_rig(rgba, mask, rig)
        self.assertEqual(parts[0].parent, -1)
        self.assertEqual(parts[1].parent, 0)
        self.assertEqual(parts[1].pivot, (10, 5))


if __name__ == "__main__":
    unittest.main()
