package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import com.k3i.stickerbook.rig.arap.ArapSolver
import com.k3i.stickerbook.rig.arap.GridMeshBuilder
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class ArapRigger private constructor(
    private val context: Context,
    private val detect: (Bitmap) -> List<Detection>,
    private val estimate: (Bitmap, RectF?) -> SkeletonData,
    private val motionSource: MotionSource,
) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detections = detect(image)
        val top = detections.maxByOrNull { it.score }
        val bbox = top?.bbox

        val skeleton: SkeletonData? = runCatching { estimate(image, bbox) }
            .onFailure { Log.w(TAG, "pose estimate failed; continuing without skeleton", it) }
            .getOrNull()

        val stickerId = "arap_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        val character = if (top != null) applyMask(image, top.mask, top.bbox) else image
        val rel = "stickers/$stickerId"

        val shiftedSkeleton = if (skeleton != null && top != null) {
            val offsetX = top.bbox.left.coerceAtLeast(0f)
            val offsetY = top.bbox.top.coerceAtLeast(0f)
            skeleton.copy(
                landmarks = skeleton.landmarks.map { it.copy(x = it.x - offsetX, y = it.y - offsetY) },
                imageWidth = character.width,
                imageHeight = character.height,
            )
        } else {
            skeleton
        }

        if (shiftedSkeleton == null || shiftedSkeleton.landmarks.isEmpty() || top == null) {
            writePng(character, File(framesDir, "0001.png"))
            writePng(character, File(sDir, "texture.png"))
            writePng(character, File(sDir, "animation.gif"))
            writePng(image, File(sDir, "source.png"))
            return RigResult(
                framesDir = "$rel/frames",
                fps = 30, frameCount = 1,
                width = character.width, height = character.height,
                texturePath = "$rel/texture.png",
                gifPath = "$rel/animation.gif",
                sourcePath = "$rel/source.png",
                skeletonPath = null,
            )
        }

        val mesh = GridMeshBuilder.build(character, GRID_W, GRID_H)
        val initialPins = landmarksToFloatArray(shiftedSkeleton)
        val solver = ArapSolver(initialPins = initialPins, mesh = mesh)
        val frameSeqs = motionSource.frames(motion, initialPins, FRAME_COUNT)

        for (i in 0 until FRAME_COUNT) {
            val deformed = solver.solve(frameSeqs[i])
            val frameBmp = MeshRenderer.draw(character, mesh.gridWidth, mesh.gridHeight, deformed)
            val name = (i + 1).toString().padStart(4, '0') + ".png"
            writePng(frameBmp, File(framesDir, name))
            if (i == 0) writePng(frameBmp, File(sDir, "texture.png"))
            frameBmp.recycle()
        }

        File(framesDir, "0001.png").copyTo(File(sDir, "animation.gif"), overwrite = true)
        writePng(image, File(sDir, "source.png"))

        val skeletonFile = File(sDir, "skeleton.json")
        skeletonFile.writeText(Json.encodeToString(SkeletonData.serializer(), shiftedSkeleton))

        return RigResult(
            framesDir = "$rel/frames",
            fps = 30, frameCount = FRAME_COUNT,
            width = character.width, height = character.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
            skeletonPath = "$rel/skeleton.json",
        )
    }

    private fun landmarksToFloatArray(skeleton: SkeletonData): FloatArray {
        val out = FloatArray(skeleton.landmarks.size * 2)
        for ((i, lm) in skeleton.landmarks.withIndex()) {
            out[i * 2] = lm.x
            out[i * 2 + 1] = lm.y
        }
        return out
    }

    private fun applyMask(image: Bitmap, mask: Bitmap, bbox: RectF): Bitmap {
        val left = bbox.left.toInt().coerceAtLeast(0)
        val topI = bbox.top.toInt().coerceAtLeast(0)
        val right = bbox.right.toInt().coerceAtMost(image.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(image.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - topI).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(image, left, topI, cropW, cropH)
        val maskScaled = Bitmap.createScaledBitmap(mask, cropW, cropH, true)
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(cropped, 0f, 0f, null)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        canvas.drawBitmap(maskScaled, 0f, 0f, paint)
        return out
    }

    private fun writePng(bmp: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    companion object {
        private const val TAG = "ArapRigger"
        private const val GRID_W = 20
        private const val GRID_H = 26
        private const val FRAME_COUNT = 30

        fun real(context: Context): ArapRigger {
            val detector = MaskRcnnDetector(context)
            val estimator = AdPoseEstimator(context)
            return ArapRigger(
                context,
                detect = { detector.detect(it) },
                estimate = { image, bbox -> estimator.estimate(image, bbox) },
                motionSource = MotionStub(),
            )
        }

        fun withStubs(
            context: Context,
            detect: (Bitmap) -> List<Detection>,
            estimate: (Bitmap, RectF?) -> SkeletonData,
            motionSource: MotionSource,
        ): ArapRigger = ArapRigger(context, detect, estimate, motionSource)
    }
}
