package com.k3i.stickerbook.rig

/**
 * Decodes a flattened heatmap [K, H, W] tensor into [K] keypoints (heatmap coord space).
 * Sub-pixel refinement: shift peak by 0.25 toward higher neighbor on each axis.
 *
 * Output coordinates are in heatmap space (e.g., 0..47 for W=48, 0..63 for H=64).
 * AdPoseEstimator maps them to crop-space and then to original-frame-space via affine inverse.
 */
object PoseHeatmapDecoder {

    data class Peak(val x: Float, val y: Float, val score: Float)

    fun decode(
        heatmap: FloatArray,
        numKeypoints: Int,
        height: Int,
        width: Int,
    ): List<Peak> {
        require(heatmap.size == numKeypoints * height * width) {
            "heatmap size ${heatmap.size} != $numKeypoints * $height * $width"
        }
        val out = ArrayList<Peak>(numKeypoints)
        val plane = height * width
        for (k in 0 until numKeypoints) {
            val base = k * plane
            var bestIdx = 0
            var bestVal = heatmap[base]
            for (i in 1 until plane) {
                val v = heatmap[base + i]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = i
                }
            }
            val py = bestIdx / width
            val px = bestIdx % width
            // Sub-pixel: shift toward higher neighbor by 0.25
            var fx = px.toFloat()
            var fy = py.toFloat()
            if (px in 1 until (width - 1)) {
                val right = heatmap[base + py * width + (px + 1)]
                val left = heatmap[base + py * width + (px - 1)]
                if (right > left) fx += 0.25f else if (left > right) fx -= 0.25f
            }
            if (py in 1 until (height - 1)) {
                val down = heatmap[base + (py + 1) * width + px]
                val up = heatmap[base + (py - 1) * width + px]
                if (down > up) fy += 0.25f else if (up > down) fy -= 0.25f
            }
            out.add(Peak(fx, fy, bestVal))
        }
        return out
    }
}
