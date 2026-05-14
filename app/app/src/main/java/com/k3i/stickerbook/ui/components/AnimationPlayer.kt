package com.k3i.stickerbook.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.k3i.stickerbook.data.AssetHandle
import com.k3i.stickerbook.data.AssetRepository
import kotlinx.coroutines.delay

@Composable
fun AnimationPlayer(
    framesDir: String,
    frameCount: Int,
    fps: Int,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val repo = remember { AssetRepository(ctx) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val frameIntervalMs = remember(fps) { (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L) }

    LaunchedEffect(framesDir, frameCount, fps) {
        if (frameCount <= 0) return@LaunchedEffect
        var i = 0
        while (true) {
            val name = "%04d.png".format(i + 1)
            val handle = repo.resolve("$framesDir/$name")
            bitmap = decodeFrame(ctx, handle)
            delay(frameIntervalMs)
            i = (i + 1) % frameCount
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val bmp = bitmap ?: return@Canvas
        val image = bmp.asImageBitmap()
        val sx = size.width / image.width
        val sy = size.height / image.height
        val scale = minOf(sx, sy)
        val w = image.width * scale
        val h = image.height * scale
        drawImage(
            image = image,
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
        )
    }
}

private fun decodeFrame(ctx: Context, handle: AssetHandle): Bitmap? {
    return runCatching {
        when (handle) {
            is AssetHandle.Bundled ->
                ctx.assets.open(handle.assetPath).use { BitmapFactory.decodeStream(it) }
            is AssetHandle.InternalFile ->
                BitmapFactory.decodeFile(handle.file.absolutePath)
        }
    }.getOrNull()
}
