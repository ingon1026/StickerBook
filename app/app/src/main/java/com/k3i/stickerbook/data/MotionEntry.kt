package com.k3i.stickerbook.data

data class MotionEntry(
    val id: String,
    val displayName: String,
)

object MotionCatalog {
    // IDs match converted BVH files in assets/motions/<id>.json.
    // Mixamo-style skeleton (LeftUpLeg 등) BVH 도 convert.py 의 alias 매핑 으로 지원.
    val all: List<MotionEntry> = listOf(
        MotionEntry(id = "motion_1", displayName = "모션 1"),
        MotionEntry(id = "motion_2", displayName = "모션 2"),
        MotionEntry(id = "motion_3", displayName = "모션 3"),
        MotionEntry(id = "motion_4", displayName = "모션 4"),
        MotionEntry(id = "motion_5", displayName = "모션 5"),
        MotionEntry(id = "dab", displayName = "댑"),
        MotionEntry(id = "jumping", displayName = "점프"),
        MotionEntry(id = "wave_hello", displayName = "인사"),
        MotionEntry(id = "zombie", displayName = "좀비"),
    )
}
