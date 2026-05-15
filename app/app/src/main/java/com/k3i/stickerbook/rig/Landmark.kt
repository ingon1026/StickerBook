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
enum class PoseBackend {
    @SerialName("mediapipe-33") MEDIAPIPE_33,
    @SerialName("ad-coco-17") AD_COCO_17;

    val keypointCount: Int get() = when (this) {
        MEDIAPIPE_33 -> 33
        AD_COCO_17 -> 17
    }
}

@Serializable
data class SkeletonData(
    val backend: PoseBackend = PoseBackend.MEDIAPIPE_33,
    val landmarks: List<Landmark>,
    @SerialName("image_width") val imageWidth: Int,
    @SerialName("image_height") val imageHeight: Int,
)
