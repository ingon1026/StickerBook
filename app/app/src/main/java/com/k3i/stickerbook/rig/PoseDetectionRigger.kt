package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class PoseDetectionRigger private constructor(
    private val context: Context,
    private val detect: (Bitmap) -> List<Detection>,
    private val estimate: (Bitmap, RectF?) -> SkeletonData,
) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detect(image)
        val top = detections.maxByOrNull { it.score }
        val bbox = top?.bbox

        val skeleton: SkeletonData? = runCatching { estimate(image, bbox) }
            .onFailure { Log.w(TAG, "pose estimate failed; continuing without skeleton", it) }
            .getOrNull()

        val stickerId = "pose_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        val character = if (top != null) {
            applyMask(image, top.mask, top.bbox)
        } else {
            image
        }
        val finalFrame = if (skeleton != null && skeleton.landmarks.isNotEmpty()) {
            SkeletonOverlay.draw(character, skeleton)
        } else {
            character
        }

        writePng(finalFrame, File(framesDir, "0001.png"))
        writePng(finalFrame, File(sDir, "texture.png"))
        writePng(finalFrame, File(sDir, "animation.gif"))
        writePng(image, File(sDir, "source.png"))

        val rel = "stickers/$stickerId"
        var skeletonPath: String? = null
        if (skeleton != null) {
            val skeletonFile = File(sDir, "skeleton.json")
            skeletonFile.writeText(Json.encodeToString(SkeletonData.serializer(), skeleton))
            skeletonPath = "$rel/skeleton.json"
        }

        return RigResult(
            framesDir = "$rel/frames",
            fps = 30,
            frameCount = 1,
            width = finalFrame.width,
            height = finalFrame.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
            skeletonPath = skeletonPath,
        )
    }

    private fun applyMask(image: Bitmap, mask: Bitmap, bbox: RectF): Bitmap {
        val left = bbox.left.toInt().coerceAtLeast(0)
        val top = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(image, left, top, cropW, cropH)
        val maskScaled = Bitmap.createScaledBitmap(mask, cropW, cropH, true)
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(cropped, 0f, 0f, null)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskScaled, 0f, 0f, paint)
        return out
    }

    private fun writePng(bmp: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    companion object {
        private const val TAG = "PoseDetectionRigger"

        fun realAd(context: Context): PoseDetectionRigger {
            val detector = MaskRcnnDetector(context)
            val estimator = AdPoseEstimator(context)
            return PoseDetectionRigger(
                context,
                detect = { detector.detect(it) },
                estimate = { image, bbox -> estimator.estimate(image, bbox) },
            )
        }

        fun realMediaPipe(context: Context): PoseDetectionRigger {
            val detector = MaskRcnnDetector(context)
            val estimator = MediaPipePoseEstimator(context)
            return PoseDetectionRigger(
                context,
                detect = { detector.detect(it) },
                estimate = { image, _ -> estimator.estimate(image, null) },
            )
        }

        fun withStubs(
            context: Context,
            detect: (Bitmap) -> List<Detection>,
            estimate: (Bitmap, RectF?) -> SkeletonData,
        ): PoseDetectionRigger = PoseDetectionRigger(context, detect, estimate)
    }
}
