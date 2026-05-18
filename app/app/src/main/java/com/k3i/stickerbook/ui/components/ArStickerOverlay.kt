package com.k3i.stickerbook.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.k3i.stickerbook.data.AssetHandle
import com.k3i.stickerbook.data.AssetRepository
import kotlinx.coroutines.delay

private const val TAG = "ArStickerOverlay"

/**
 * Camera overlay sticker. Plays frame sequence at fps + draws as a perspective
 * trapezoid anchored at [anchor] (bottom-center of the sticker) with ellipse
 * shadow under it for fake-AR depth feel.
 */
@Composable
fun ArStickerOverlay(
    framesDir: String,
    frameCount: Int,
    fps: Int,
    anchor: Offset,
    stickerWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val repo = remember { AssetRepository(ctx) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val frameIntervalMs = remember(fps) { (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L) }

    LaunchedEffect(framesDir, frameCount, fps) {
        if (frameCount <= 0) return@LaunchedEffect
        var tick = 0
        while (true) {
            val i = ArStickerMath.frameAt(tick, frameCount)
            val name = "%04d.png".format(i + 1)
            val handle = repo.resolve("$framesDir/$name")
            val newBmp = decodeFrameForAr(ctx, handle)
            bitmap?.recycle()
            bitmap = newBmp
            delay(frameIntervalMs)
            tick++
        }
    }

    Canvas(modifier = modifier) {
        val bmp = bitmap ?: return@Canvas
        val aspectH = stickerWidthPx * bmp.height / bmp.width.coerceAtLeast(1)

        // shadow first (below sticker)
        val shadowH = aspectH * 0.05f
        val shadow = ArStickerMath.shadowRect(
            anchorX = anchor.x, anchorY = anchor.y,
            stickerWidth = stickerWidthPx,
            shadowWidthRatio = 0.7f,
            shadowHeight = shadowH,
        )
        val shadowPaint = Paint().apply {
            color = Color.argb(80, 0, 0, 0)
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawOval(
            RectF(shadow.left, shadow.top, shadow.right, shadow.bottom),
            shadowPaint,
        )

        // trapezoid sticker via drawBitmapMesh (1x1 grid → 4 vertices)
        val verts = ArStickerMath.trapezoidVertices(
            anchorX = anchor.x, anchorY = anchor.y,
            width = stickerWidthPx, height = aspectH,
            bottomNarrowFactor = 0.85f,
        )
        drawContext.canvas.nativeCanvas.drawBitmapMesh(
            bmp,
            1, 1,
            verts, 0,
            null, 0,
            null,
        )
    }
}

private fun decodeFrameForAr(ctx: Context, handle: AssetHandle): Bitmap? {
    return try {
        when (handle) {
            is AssetHandle.Bundled ->
                ctx.assets.open(handle.assetPath).use { BitmapFactory.decodeStream(it) }
            is AssetHandle.InternalFile ->
                BitmapFactory.decodeFile(handle.file.absolutePath)
        }
    } catch (e: Exception) {
        Log.e(TAG, "decode failed for $handle", e)
        null
    }
}
