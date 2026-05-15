package com.k3i.stickerbook.data

data class MotionEntry(
    val id: String,
    val displayName: String,
)

object MotionCatalog {
    // IDs match Sub-4 converted BVH files in assets/motions/<id>.json.
    // zombie.bvh skipped (incompatible skeleton structure, missing LeftThigh/RightThigh).
    val all: List<MotionEntry> = listOf(
        MotionEntry(id = "dance_1", displayName = "댄스 1"),
        MotionEntry(id = "dance_2", displayName = "댄스 2"),
        MotionEntry(id = "dance_3", displayName = "댄스 3"),
        MotionEntry(id = "motion_5", displayName = "모션 5"),
        MotionEntry(id = "phone_1", displayName = "전화 1"),
        MotionEntry(id = "phone_2", displayName = "전화 2"),
        MotionEntry(id = "phone_z1", displayName = "전화 Z1"),
        MotionEntry(id = "tabtab", displayName = "탭탭"),
    )
}
