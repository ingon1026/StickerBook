package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object SkeletonOverlay {

    private val BLAZE_33_CONNECTIONS = listOf(
        // face
        0 to 1, 1 to 2, 2 to 3, 3 to 7,
        0 to 4, 4 to 5, 5 to 6, 6 to 8,
        9 to 10,
        // arms
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
        15 to 17, 15 to 19, 15 to 21, 17 to 19,
        16 to 18, 16 to 20, 16 to 22, 18 to 20,
        // torso
        11 to 23, 12 to 24, 23 to 24,
        // legs
        23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
        24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
    )

    // COCO 17 connections (head, arms, torso, legs)
    private val COCO_17_CONNECTIONS = listOf(
        // head
        0 to 1, 0 to 2, 1 to 3, 2 to 4,
        // shoulders + arms
        5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10,
        // torso
        5 to 11, 6 to 12, 11 to 12,
        // legs
        11 to 13, 13 to 15, 12 to 14, 14 to 16,
    )

    /**
     * Draws landmark dots and connection lines on top of [bitmap].
     * Low-visibility landmarks (< 0.5) are drawn dim (alpha 0.3).
     */
    fun draw(bitmap: Bitmap, skeleton: SkeletonData): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (skeleton.landmarks.isEmpty()) return out
        val canvas = Canvas(out)
        val connections = when (skeleton.backend) {
            PoseBackend.MEDIAPIPE_33 -> BLAZE_33_CONNECTIONS
            PoseBackend.AD_COCO_17 -> COCO_17_CONNECTIONS
        }
        drawConnections(canvas, skeleton.landmarks, connections)
        drawDots(canvas, skeleton.landmarks)
        return out
    }

    private fun drawConnections(
        canvas: Canvas,
        landmarks: List<Landmark>,
        connections: List<Pair<Int, Int>>,
    ) {
        val paint = Paint().apply {
            color = Color.argb(255, 0, 200, 255)
            strokeWidth = 4f
            isAntiAlias = true
        }
        for ((a, b) in connections) {
            if (a >= landmarks.size || b >= landmarks.size) continue
            val la = landmarks[a]
            val lb = landmarks[b]
            paint.alpha = if (la.visibility < 0.5f || lb.visibility < 0.5f) 76 else 255
            canvas.drawLine(la.x, la.y, lb.x, lb.y, paint)
        }
    }

    private fun drawDots(canvas: Canvas, landmarks: List<Landmark>) {
        val paint = Paint().apply {
            color = Color.argb(255, 255, 80, 80)
            isAntiAlias = true
        }
        for (lm in landmarks) {
            paint.alpha = if (lm.visibility < 0.5f) 76 else 255
            canvas.drawCircle(lm.x, lm.y, 6f, paint)
        }
    }
}
