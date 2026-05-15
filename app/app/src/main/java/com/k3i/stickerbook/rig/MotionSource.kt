package com.k3i.stickerbook.rig

/**
 * Provides per-frame pin positions for a named motion.
 *
 * Implementations:
 * - [MotionStub] — hardcoded sequences (wave, …), used until Sub-4
 * - BvhMotionSource (Sub-4) — driven by BVH retarget pipeline
 */
interface MotionSource {
    /**
     * @param motionId motion catalog id (e.g. "wave")
     * @param initialPins flat [P*2] xy pairs in character-bitmap coord space
     * @param frameCount number of frames to generate
     * @return list of size [frameCount], each entry a fresh FloatArray same shape as [initialPins]
     */
    fun frames(motionId: String, initialPins: FloatArray, frameCount: Int): List<FloatArray>
}
