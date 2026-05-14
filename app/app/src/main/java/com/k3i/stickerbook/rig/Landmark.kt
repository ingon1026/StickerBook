package com.k3i.stickerbook.rig

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Landmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float,
)

@Serializable
data class SkeletonData(
    val landmarks: List<Landmark>,
    @SerialName("image_width") val imageWidth: Int,
    @SerialName("image_height") val imageHeight: Int,
)
