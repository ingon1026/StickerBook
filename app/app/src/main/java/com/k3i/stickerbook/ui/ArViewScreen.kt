package com.k3i.stickerbook.ui

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.k3i.stickerbook.ar.PaperTracker
import com.k3i.stickerbook.ar.yPlaneToGrayMat
import com.k3i.stickerbook.camera.CameraXPreview
import com.k3i.stickerbook.camera.ImageCaptureController
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.ui.components.ArStickerOverlay
import org.opencv.android.OpenCVLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArViewScreen(
    manifest: Manifest,
    stickerId: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val entry = remember(manifest, stickerId) {
        manifest.stickers.firstOrNull { it.id == stickerId }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(android.Manifest.permission.CAMERA)
    }

    var anchor by remember { mutableStateOf<Offset?>(null) }
    var screenSize by remember { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    val centroidState = remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val density = LocalDensity.current
    val controller = remember { ImageCaptureController() }

    val tracker = remember {
        if (!OpenCVLoader.initLocal()) {
            Log.e("ArViewScreen", "OpenCV init failed")
        }
        PaperTracker()
    }
    val homographyState = remember { mutableStateOf<DoubleArray?>(null) }

    val analyzer = remember {
        val alpha = 0.1  // EMA factor: higher = faster response, lower = smoother
        ImageAnalysis.Analyzer { imageProxy ->
            try {
                val mat = yPlaneToGrayMat(imageProxy)
                try {
                    if (!tracker.isReferenceSet) {
                        tracker.setReference(mat)
                        centroidState.value = tracker.referenceCentroidNormalized()
                    } else {
                        val hNew = tracker.update(mat)
                        if (hNew != null) {
                            val hPrev = homographyState.value
                            val hSmoothed = if (hPrev == null) {
                                hNew
                            } else {
                                DoubleArray(9) { i -> alpha * hNew[i] + (1 - alpha) * hPrev[i] }
                            }
                            homographyState.value = hSmoothed
                        }
                        // if hNew == null (lost), keep previous homographyState (sticker stays)
                    }
                } finally {
                    mat.release()
                }
            } finally {
                imageProxy.close()
            }
        }
    }

    LaunchedEffect(centroidState.value, screenSize) {
        val c = centroidState.value
        val s = screenSize
        if (c != null && s != null && s.width > 0 && s.height > 0) {
            anchor = Offset(c.first * s.width, c.second * s.height)
        }
    }

    DisposableEffect(Unit) {
        onDispose { tracker.reset() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.name ?: "AR") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (hasPermission) {
                CameraXPreview(controller = controller, analyzer = analyzer, modifier = Modifier.fillMaxSize())
            } else {
                Text("카메라 권한이 필요합니다")
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        screenSize = size
                        if (anchor == null && size.width > 0 && size.height > 0) {
                            // Fallback: center sticker immediately. centroid will override when available.
                            anchor = Offset(size.width.toFloat() / 2f, size.height.toFloat() * 2f / 3f)
                        }
                    }
                    .pointerInput(stickerId) {
                        detectTapGestures { offset ->
                            anchor = offset
                        }
                    },
            ) {
                val a = anchor
                val e = entry
                if (e != null && a != null) {
                    val widthPx = with(density) { 200.dp.toPx() }
                    val h by homographyState
                    ArStickerOverlay(
                        framesDir = e.framesDir,
                        frameCount = e.frameCount,
                        fps = e.fps,
                        anchor = a,
                        stickerWidthPx = widthPx,
                        homography = h,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
