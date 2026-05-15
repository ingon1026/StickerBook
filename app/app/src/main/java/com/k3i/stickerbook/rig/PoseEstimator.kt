package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Estimates 2D pose landmarks from an image.
 *
 * Implementations:
 * - [MediaPipePoseEstimator] — 33 BlazePose keypoints. bbox 무시.
 * - [AdPoseEstimator] — 17 COCO keypoints. bbox 필수 (top-down).
 */
interface PoseEstimator {

    /**
     * @param image full-frame bitmap (camera capture)
     * @param bbox optional bounding box for top-down crop. AD backend requires it.
     */
    fun estimate(image: Bitmap, bbox: RectF? = null): SkeletonData
}
