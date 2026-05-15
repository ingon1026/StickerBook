package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class MediaPipePoseEstimator(context: Context) : PoseEstimator {

    private val landmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = PoseLandmarker.createFromOptions(context, options)
        Log.i(TAG, "MediaPipe PoseLandmarker initialized")
    }

    /**
     * @param bbox ignored by MediaPipe — full-image detection
     */
    override fun estimate(image: Bitmap, bbox: RectF?): SkeletonData {
        val mpImage = BitmapImageBuilder(image).build()
        val result: PoseLandmarkerResult = landmarker.detect(mpImage)

        if (result.landmarks().isEmpty()) {
            Log.w(TAG, "no pose detected")
            return SkeletonData(
                backend = PoseBackend.MEDIAPIPE_33,
                landmarks = emptyList(),
                imageWidth = image.width,
                imageHeight = image.height,
            )
        }

        val mpLandmarks = result.landmarks()[0]
        val landmarks = mpLandmarks.map { lm ->
            val visibility = lm.visibility().orElse(1f)
            val presence = lm.presence().orElse(1f)
            Landmark(
                x = lm.x() * image.width,
                y = lm.y() * image.height,
                z = lm.z(),
                visibility = visibility,
                presence = presence,
            )
        }
        Log.i(TAG, "pose detected: ${landmarks.size} landmarks")
        return SkeletonData(
            backend = PoseBackend.MEDIAPIPE_33,
            landmarks = landmarks,
            imageWidth = image.width,
            imageHeight = image.height,
        )
    }

    fun close() {
        landmarker.close()
    }

    companion object {
        private const val TAG = "MediaPipePoseEstimator"
        private const val MODEL_ASSET_PATH = "models/pose_landmarker_heavy.task"
    }
}
