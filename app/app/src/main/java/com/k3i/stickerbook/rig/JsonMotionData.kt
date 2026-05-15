package com.k3i.stickerbook.rig

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JsonMotionData(
    @SerialName("motion_id") val motionId: String,
    @SerialName("frame_count") val frameCount: Int,
    val fps: Int,
    val backend: PoseBackend,
    val frames: List<List<List<Float>>>,  // [F, 17, 2]
)
