package com.k3i.stickerbook.rig

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

class MaskRcnnDetector(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelPath = ensureModelOnDisk()
        val opts = OrtSession.SessionOptions().apply {
            // CPU-only. NNAPI tried to partition Mask R-CNN (RoI Align etc.) and crashed
            // in native code on first createSession. Stay on CPU until quantization / NNAPI EP
            // compatibility is verified for this graph.
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        Log.i(TAG, "creating ONNX session from $modelPath ...")
        session = env.createSession(modelPath, opts)
        Log.i(TAG, "loaded ONNX, inputs=${session.inputNames}, outputs=${session.outputNames}")
    }

    /**
     * Copies the bundled ONNX model from APK assets to filesDir on first run.
     * Loading the model by file path lets ONNX Runtime mmap it,
     * avoiding allocating the whole 168MB as a byte[] on the heap.
     */
    private fun ensureModelOnDisk(): String {
        val target = File(context.filesDir, "models/$MODEL_NAME")
        val assetSize = context.assets.open("models/$MODEL_NAME").use { it.available().toLong() }
        if (target.isFile && target.length() == assetSize) {
            Log.i(TAG, "model already cached at ${target.absolutePath} (${target.length()} bytes)")
            return target.absolutePath
        }
        if (target.isFile) {
            Log.i(TAG, "model cache size mismatch (${target.length()} vs $assetSize), re-copying")
        }
        target.parentFile?.mkdirs()
        context.assets.open("models/$MODEL_NAME").use { input ->
            target.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
        }
        Log.i(TAG, "copied model to ${target.absolutePath} (${target.length()} bytes)")
        return target.absolutePath
    }

    fun detect(image: Bitmap, scoreThreshold: Float = 0.5f): List<Detection> {
        val (inputBuf, h, w) = ImagePreprocess.toNchwTensor(image)
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        val tensor = OnnxTensor.createTensor(env, inputBuf, shape)
        val inputName = session.inputNames.first()
        val outputs = session.run(mapOf(inputName to tensor))
        try {
            val detsRaw = outputs[0].value
            val masksRaw = if (outputs.size() >= 3) outputs[2].value else null

            val dets = flattenDets(detsRaw)
            val nDets = dets.size / 5
            val (masksFlat, mh, mw) = flattenMasks(masksRaw, nDets)

            return MaskPostprocess.decode(dets, masksFlat, mh, mw, scoreThreshold)
        } finally {
            outputs.close()
            tensor.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenDets(raw: Any?): FloatArray {
        if (raw == null) return FloatArray(0)
        val batch = raw as Array<Array<FloatArray>>
        if (batch.isEmpty()) return FloatArray(0)
        val rows = batch[0]
        val flat = FloatArray(rows.size * 5)
        rows.forEachIndexed { i, row ->
            row.copyInto(flat, i * 5)
        }
        return flat
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenMasks(raw: Any?, nDets: Int): Triple<FloatArray, Int, Int> {
        if (raw == null || nDets == 0) return Triple(FloatArray(0), 0, 0)
        val batch = raw as Array<Array<Array<FloatArray>>>
        if (batch.isEmpty() || batch[0].isEmpty()) return Triple(FloatArray(0), 0, 0)
        val masks = batch[0]
        val h = masks[0].size
        val w = masks[0][0].size
        val n = nDets.coerceAtMost(masks.size)
        val flat = FloatArray(n * h * w)
        for (i in 0 until n) {
            for (r in 0 until h) {
                masks[i][r].copyInto(flat, i * h * w + r * w)
            }
        }
        return Triple(flat, h, w)
    }

    fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "MaskRcnnDetector"
        private const val MODEL_NAME = "drawn_humanoid_detector.onnx"
    }
}
