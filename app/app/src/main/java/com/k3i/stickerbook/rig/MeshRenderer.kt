package com.k3i.stickerbook.rig

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Renders a bitmap onto a deformed grid mesh.
 *
 * The verts array follows Canvas.drawBitmapMesh convention:
 *   length = (meshWidth+1) * (meshHeight+1) * 2, row-major (y * (meshWidth+1) + x) * 2.
 *
 * The returned bitmap has the same dimensions as the input.
 */
object MeshRenderer {

    fun draw(
        source: Bitmap,
        meshWidth: Int,
        meshHeight: Int,
        verts: FloatArray,
    ): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmapMesh(source, meshWidth, meshHeight, verts, 0, null, 0, null)
        return out
    }
}
