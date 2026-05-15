package com.k3i.stickerbook.rig

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Loads normalized COCO 17 motions from assets/motions/<id>.json and
 * denormalizes them into character-bitmap pixel space using initialPins.
 *
 * Normalization convention (PC convert.py):
 * - frame 0 hip center = (0, 0)
 * - max abs coord = 1.0 (motion bbox → [-1, 1])
 * - y axis points down (image convention)
 *
 * Face keypoints (0-4) are not stored in JSON — kept static at initialPins.
 * Body keypoints (5-16) are scaled and translated.
 *
 * Linear interpolation maps source frame count → requested frameCount.
 *
 * Unknown motion id → identity frames (initialPins for every frame).
 */
class JsonMotionSource(
    private val context: Context,
    private val assetLoader: (String) -> JsonMotionData? = { id -> defaultLoad(context, id) },
) : MotionSource {

    private val cache = mutableMapOf<String, JsonMotionData?>()

    override fun frames(
        motionId: String,
        initialPins: FloatArray,
        frameCount: Int,
    ): List<FloatArray> {
        val data = cache.getOrPut(motionId) { assetLoader(motionId) }
        if (data == null) {
            Log.w(TAG, "motion '$motionId' not found, returning identity frames")
            return List(frameCount) { initialPins.copyOf() }
        }
        val source = denormalize(data, initialPins)
        Log.i(TAG, "loaded motion '$motionId' (${data.frameCount} frames @ ${data.fps}fps)")
        return interpolate(source, frameCount)
    }

    private fun denormalize(
        data: JsonMotionData,
        initialPins: FloatArray,
    ): List<FloatArray> {
        val hipCx = (initialPins[11 * 2] + initialPins[12 * 2]) / 2f
        val hipCy = (initialPins[11 * 2 + 1] + initialPins[12 * 2 + 1]) / 2f
        // Sub-4 fix #2: scale = character keypoint bbox max dimension × 0.5.
        // This bounds motion to about half the character body so even motion's
        // max-abs (1.0) keypoint stays inside the character bitmap.
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until 17) {
            val x = initialPins[i * 2]; val y = initialPins[i * 2 + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val bboxW = maxX - minX
        val bboxH = maxY - minY
        var scale = maxOf(bboxW, bboxH) * 0.5f
        if (scale < 1f) {
            Log.w(TAG, "keypoint bbox max < 1px; using 100 fallback")
            scale = 100f
        }
        return data.frames.map { frame ->
            val out = initialPins.copyOf()
            for (k in 5..16) {
                out[k * 2]     = hipCx + frame[k][0] * scale
                out[k * 2 + 1] = hipCy + frame[k][1] * scale
            }
            out
        }
    }

    private fun interpolate(src: List<FloatArray>, target: Int): List<FloatArray> {
        if (src.isEmpty()) return emptyList()
        if (src.size == target) return src
        return List(target) { i ->
            val t = if (target == 1) 0f else i.toFloat() / (target - 1) * (src.size - 1)
            val lo = t.toInt().coerceAtMost(src.size - 2).coerceAtLeast(0)
            val hi = (lo + 1).coerceAtMost(src.size - 1)
            val frac = t - lo
            val a = src[lo]; val b = src[hi]
            FloatArray(a.size) { idx -> a[idx] * (1 - frac) + b[idx] * frac }
        }
    }

    companion object {
        private const val TAG = "JsonMotionSource"
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        fun withAssetLoader(context: Context, loader: (String) -> JsonMotionData?): JsonMotionSource =
            JsonMotionSource(context, loader)

        private fun defaultLoad(context: Context, motionId: String): JsonMotionData? = try {
            val text = context.assets.open("motions/$motionId.json")
                .bufferedReader().use { it.readText() }
            DEFAULT_JSON.decodeFromString(JsonMotionData.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }
}
