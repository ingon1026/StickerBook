package com.k3i.stickerbook.ui.components

/**
 * Pure math helpers for ArStickerOverlay.
 */
object ArStickerMath {

    /** [TLx, TLy, TRx, TRy, BLx, BLy, BRx, BRy] in pixel coords. */
    fun trapezoidVertices(
        anchorX: Float, anchorY: Float,
        width: Float, height: Float,
        bottomNarrowFactor: Float,
    ): FloatArray {
        val halfTop = width / 2f
        val halfBottom = width * bottomNarrowFactor / 2f
        val topY = anchorY - height
        return floatArrayOf(
            anchorX - halfTop,    topY,
            anchorX + halfTop,    topY,
            anchorX - halfBottom, anchorY,
            anchorX + halfBottom, anchorY,
        )
    }

    fun frameAt(tickIndex: Int, frameCount: Int): Int =
        if (frameCount <= 0) 0 else ((tickIndex % frameCount) + frameCount) % frameCount

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun shadowRect(
        anchorX: Float, anchorY: Float,
        stickerWidth: Float,
        shadowWidthRatio: Float,
        shadowHeight: Float,
    ): Rect {
        val half = stickerWidth * shadowWidthRatio / 2f
        return Rect(
            left = anchorX - half,
            top = anchorY,
            right = anchorX + half,
            bottom = anchorY + shadowHeight,
        )
    }
}
