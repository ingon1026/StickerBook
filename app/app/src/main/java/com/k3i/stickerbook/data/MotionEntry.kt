package com.k3i.stickerbook.data

data class MotionEntry(
    val id: String,
    val displayName: String,
)

object MotionCatalog {
    // Dummy hardcoded list until Sub-4 (BVH parser) lands.
    val all: List<MotionEntry> = listOf(
        MotionEntry(id = "dab", displayName = "댑"),
        MotionEntry(id = "dance_1", displayName = "댄스 1"),
        MotionEntry(id = "dance_2", displayName = "댄스 2"),
        MotionEntry(id = "dance_3", displayName = "댄스 3"),
        MotionEntry(id = "motion_1", displayName = "모션 1"),
        MotionEntry(id = "motion_2", displayName = "모션 2"),
    )
}
