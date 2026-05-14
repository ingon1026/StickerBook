package com.k3i.stickerbook.perf

import android.util.Log

class FrameRateTracker(private val tag: String = "Stickerbook.FPS") {
    private var lastLogMs = 0L
    private var framesSinceLog = 0

    fun mark() {
        framesSinceLog++
        val now = System.currentTimeMillis()
        if (lastLogMs == 0L) {
            lastLogMs = now
            return
        }
        val elapsed = now - lastLogMs
        if (elapsed >= 1000) {
            val fps = framesSinceLog * 1000.0 / elapsed
            Log.i(tag, "fps=%.1f frames=%d in %dms".format(fps, framesSinceLog, elapsed))
            framesSinceLog = 0
            lastLogMs = now
        }
    }
}
