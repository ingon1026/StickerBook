package com.k3i.stickerbook.data

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf

class CaptureSession {
    var image: Bitmap? = null
    var motion: String? = null
    fun reset() { image = null; motion = null }
}

val LocalCaptureSession = staticCompositionLocalOf<CaptureSession> {
    error("CaptureSession not provided")
}
