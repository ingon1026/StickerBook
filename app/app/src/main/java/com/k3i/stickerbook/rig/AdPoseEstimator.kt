package com.k3i.stickerbook.rig

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

class AdPoseEstimator(private val context: Context) : PoseEstimator {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelPath = ensureModelOnDisk()
        val opts = OrtSession.SessionOptions().apply {
            // Sub-1 lesson: NNAPI off for mmpose ops too. CPU only.
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        Log.i(TAG, "creating ONNX session from $modelPath ...")
        session = env.createSession(modelPath, opts)
        Log.i(TAG, "loaded ad_pose ONNX, inputs=${session.inputNames}, outputs=${session.outputNames}")
    }

    /**
     * Sub-1 pattern: copy ONNX from APK assets to filesDir on first run.
     * Loading by file path lets ONNX Runtime mmap, avoiding large byte[] heap allocation.
     */
    private fun ensureModelOnDisk(): String {
        val target = File(context.filesDir, "models/$MODEL_NAME")
        if (target.isFile && target.length() > 0) {
            Log.i(TAG, "model already cached at ${target.absolutePath} (${target.length()} bytes)")
            return target.absolutePath
        }
        target.parentFile?.mkdirs()
        context.assets.open("models/$MODEL_NAME").use { input ->
            target.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
        }
        Log.i(TAG, "copied model to ${target.absolutePath} (${target.length()} bytes)")
        return target.absolutePath
    }

    override fun estimate(image: Bitmap, bbox: RectF?): SkeletonData {
        if (bbox == null) {
            Log.w(TAG, "no bbox provided; AdPoseEstimator requires top-down bbox")
            return SkeletonData(PoseBackend.AD_COCO_17, emptyList(), image.width, image.height)
        }

        // 1. Build affine crop using bbox center + padded scale
        val crop = cropAffine(image, bbox, CROP_W, CROP_H, PADDING_RATIO)

        // 2. Normalize ImageNet mean/std, NCHW float
        val inputBuf = preprocess(crop)

        // 3. ONNX inference
        val shape = longArrayOf(1, 3, CROP_H.toLong(), CROP_W.toLong())
        val tensor = OnnxTensor.createTensor(env, inputBuf, shape)
        val outputs = session.run(mapOf(session.inputNames.first() to tensor))
        try {
            @Suppress("UNCHECKED_CAST")
            val raw = outputs[0].value as Array<Array<Array<FloatArray>>>  // [1,17,H,W]
            val heatmapH = raw[0][0].size
            val heatmapW = raw[0][0][0].size
            val flat = FloatArray(NUM_KEYPOINTS * heatmapH * heatmapW)
            for (k in 0 until NUM_KEYPOINTS) {
                for (r in 0 until heatmapH) {
                    raw[0][k][r].copyInto(flat, k * heatmapH * heatmapW + r * heatmapW)
                }
            }

            // 4. Decode heatmap → 17 (x, y, score) in heatmap space
            val peaks = PoseHeatmapDecoder.decode(flat, NUM_KEYPOINTS, heatmapH, heatmapW)

            // 5. Heatmap → crop space (multiply by stride)
            val strideX = CROP_W.toFloat() / heatmapW
            val strideY = CROP_H.toFloat() / heatmapH

            // 6. Crop space → original image space (affine inverse)
            val landmarks = peaks.map { p ->
                val cropX = p.x * strideX
                val cropY = p.y * strideY
                val (imgX, imgY) = unprojectFromCrop(
                    cropX = cropX, cropY = cropY,
                    bbox = bbox, cropW = CROP_W, cropH = CROP_H,
                    paddingRatio = PADDING_RATIO,
                    imageW = image.width, imageH = image.height,
                )
                Landmark(
                    x = imgX,
                    y = imgY,
                    z = 0f,
                    visibility = p.score,
                    presence = p.score,
                )
            }
            Log.i(TAG, "pose detected: ${landmarks.size} landmarks (max score ${peaks.maxOf { it.score }})")
            return SkeletonData(
                backend = PoseBackend.AD_COCO_17,
                landmarks = landmarks,
                imageWidth = image.width,
                imageHeight = image.height,
            )
        } finally {
            outputs.close()
            tensor.close()
        }
    }

    private fun cropAffine(image: Bitmap, bbox: RectF, w: Int, h: Int, padding: Float): Bitmap {
        val (cx, cy, sw, sh) = paddedBbox(bbox, w, h, padding)
        val mat = Matrix()
        mat.postTranslate(-(cx - sw / 2f), -(cy - sh / 2f))
        mat.postScale(w / sw, h / sh)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(image, mat, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(3 * h * w)
        val mean = floatArrayOf(123.675f, 116.28f, 103.53f)
        val std = floatArrayOf(58.395f, 57.12f, 57.375f)
        for (c in 0..2) {
            for (i in 0 until h * w) {
                val argb = pixels[i]
                val v = when (c) {
                    0 -> (argb shr 16) and 0xFF
                    1 -> (argb shr 8) and 0xFF
                    else -> argb and 0xFF
                }
                buf.put((v.toFloat() - mean[c]) / std[c])
            }
        }
        buf.rewind()
        return buf
    }

    fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "AdPoseEstimator"
        private const val MODEL_NAME = "ad_pose.onnx"
        private const val CROP_W = 192
        private const val CROP_H = 256
        private const val PADDING_RATIO = 1.25f
        private const val NUM_KEYPOINTS = 17

        /**
         * Aspect-ratio aware padded bbox following mmpose convention.
         * Returns (centerX, centerY, scaleW, scaleH) in image coordinates.
         */
        private fun paddedBbox(
            bbox: RectF, cropW: Int, cropH: Int, padding: Float,
        ): FloatArray {
            val cx = (bbox.left + bbox.right) / 2f
            val cy = (bbox.top + bbox.bottom) / 2f
            val bw = bbox.width()
            val bh = bbox.height()
            val aspect = cropW.toFloat() / cropH
            var sw = bw
            var sh = bh
            if (sw / sh > aspect) {
                sh = sw / aspect
            } else {
                sw = sh * aspect
            }
            sw *= padding
            sh *= padding
            return floatArrayOf(cx, cy, sw, sh)
        }

        /**
         * Map a point from crop-space (0..cropW, 0..cropH) back to image-space.
         */
        fun unprojectFromCrop(
            cropX: Float, cropY: Float,
            bbox: RectF, cropW: Int, cropH: Int,
            paddingRatio: Float, imageW: Int, imageH: Int,
        ): Pair<Float, Float> {
            val (cx, cy, sw, sh) = paddedBbox(bbox, cropW, cropH, paddingRatio)
            val imgX = (cx - sw / 2f) + (cropX / cropW) * sw
            val imgY = (cy - sh / 2f) + (cropY / cropH) * sh
            return imgX to imgY
        }

        /**
         * Inverse of [unprojectFromCrop]. For test round-trip.
         */
        fun projectToCrop(
            imageX: Float, imageY: Float,
            bbox: RectF, cropW: Int, cropH: Int, paddingRatio: Float,
        ): Pair<Float, Float> {
            val (cx, cy, sw, sh) = paddedBbox(bbox, cropW, cropH, paddingRatio)
            val cropX = (imageX - (cx - sw / 2f)) / sw * cropW
            val cropY = (imageY - (cy - sh / 2f)) / sh * cropH
            return cropX to cropY
        }

        private operator fun FloatArray.component1() = this[0]
        private operator fun FloatArray.component2() = this[1]
        private operator fun FloatArray.component3() = this[2]
        private operator fun FloatArray.component4() = this[3]
    }
}
