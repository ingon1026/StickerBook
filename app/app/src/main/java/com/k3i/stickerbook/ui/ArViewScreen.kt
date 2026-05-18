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
        ImageAnalysis.Analyzer { imageProxy ->
            try {
                val mat = yPlaneToGrayMat(imageProxy)
                try {
                    if (!tracker.isReferenceSet) {
                        tracker.setReference(mat)
                    } else {
                        val h = tracker.update(mat)
                        if (h != null) homographyState.value = h
                    }
                } finally {
                    mat.release()
                }
            } finally {
                imageProxy.close()
            }
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
                    .pointerInput(stickerId) {
                        if (anchor == null) {
                            anchor = Offset(size.width / 2f, size.height * 2f / 3f)
                        }
                        detectTapGestures { offset ->
                            anchor = offset
                        }
                    },
            ) {
                val a = anchor
                val e = entry
                if (e != null && a != null) {
                    val widthPx = with(density) { 200.dp.toPx() }
                    ArStickerOverlay(
                        framesDir = e.framesDir,
                        frameCount = e.frameCount,
                        fps = e.fps,
                        anchor = a,
                        stickerWidthPx = widthPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
