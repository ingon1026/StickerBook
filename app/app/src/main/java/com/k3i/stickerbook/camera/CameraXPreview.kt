package com.k3i.stickerbook.camera

import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun CameraXPreview(
    controller: ImageCaptureController,
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer? = null,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(controller, analyzer) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        val cameraProvider: ProcessCameraProvider = suspendCoroutine { cont ->
            providerFuture.addListener(
                { cont.resume(providerFuture.get()) },
                ContextCompat.getMainExecutor(ctx),
            )
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val useCases = mutableListOf<UseCase>(preview, controller.useCase)
        if (analyzer != null) {
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(640, 480))
                .build()
            analysis.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
            useCases.add(analysis)
        }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray(),
        )
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
