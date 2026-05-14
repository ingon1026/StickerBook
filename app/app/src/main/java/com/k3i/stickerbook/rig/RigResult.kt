package com.k3i.stickerbook.rig

data class RigResult(
    val framesDir: String,
    val fps: Int,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val texturePath: String,
    val gifPath: String,
    val sourcePath: String,
)
