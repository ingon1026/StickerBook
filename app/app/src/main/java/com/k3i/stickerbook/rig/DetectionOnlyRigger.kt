package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.File
import java.io.FileOutputStream

class DetectionOnlyRigger private constructor(
    private val context: Context,
    private val detect: (Bitmap) -> List<Detection>,
) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detect(image)
        val top = detections.maxByOrNull { it.score }

        val stickerId = "det_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        val character = if (top != null) {
            applyMask(image, top.mask, top.bbox)
        } else {
            // no detection — keep the raw capture as-is
            image
        }

        writePng(character, File(framesDir, "0001.png"))
        writePng(character, File(sDir, "texture.png"))
        writePng(character, File(sDir, "animation.gif"))
        writePng(image, File(sDir, "source.png"))

        val rel = "stickers/$stickerId"
        return RigResult(
            framesDir = "$rel/frames",
            fps = 30,
            frameCount = 1,
            width = character.width,
            height = character.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
        )
    }

    private fun applyMask(image: Bitmap, mask: Bitmap, bbox: android.graphics.RectF): Bitmap {
        // 1) Crop the original image to bbox
        val left = bbox.left.toInt().coerceAtLeast(0)
        val top = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(image, left, top, cropW, cropH)

        // 2) Resize mask to cropped size (mask is 28x28 RoI from ONNX)
        val maskScaled = Bitmap.createScaledBitmap(mask, cropW, cropH, true)

        // 3) Apply mask as alpha channel (DST_IN keeps source where mask has alpha)
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
        /**
         * Production factory — uses real MaskRcnnDetector backed by ONNX model.
         */
        fun real(context: Context): DetectionOnlyRigger {
            val detector = MaskRcnnDetector(context)
            return DetectionOnlyRigger(context) { detector.detect(it) }
        }

        /**
         * Test factory — inject custom detection function.
         */
        fun withDetector(
            context: Context,
            detect: (Bitmap) -> List<Detection>,
        ): DetectionOnlyRigger = DetectionOnlyRigger(context, detect)
    }
}
