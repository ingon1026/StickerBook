package com.k3i.stickerbook.rig

import kotlin.math.PI
import kotlin.math.cos

/**
 * Hardcoded motions for Sub-3 standalone validation.
 *
 * Conventions:
 * - 17 COCO keypoints (indices match Sub-2b AD_COCO_17 backend)
 *   5=l_shoulder, 6=r_shoulder, 7=l_elbow, 8=r_elbow, 9=l_wrist, 10=r_wrist
 *
 * Coordinate space: character-bitmap pixels (y increases downward).
 */
class MotionStub : MotionSource {

    override fun frames(
        motionId: String,
        initialPins: FloatArray,
        frameCount: Int,
    ): List<FloatArray> = when (motionId) {
        "wave" -> wave(initialPins, frameCount)
        else -> identity(initialPins, frameCount)
    }

    private fun identity(pins: FloatArray, n: Int): List<FloatArray> =
        List(n) { pins.copyOf() }

    /**
     * Wave: both wrists swing from down (initial position) to up (around shoulder height)
     * and back, sinusoidally over [frameCount] frames.
     */
    private fun wave(pins: FloatArray, n: Int): List<FloatArray> {
        val lShoulderY = pins[5 * 2 + 1]
        val rShoulderY = pins[6 * 2 + 1]
        val lWristY = pins[9 * 2 + 1]
        val rWristY = pins[10 * 2 + 1]
        return List(n) { f ->
            val phase = (1f - cos(2.0 * PI * f / n).toFloat()) / 2f  // 0 → 1 → 0
            val frame = pins.copyOf()
            frame[9 * 2 + 1]  = lWristY + (lShoulderY - lWristY) * phase
            frame[10 * 2 + 1] = rWristY + (rShoulderY - rWristY) * phase
            frame
        }
    }
}
