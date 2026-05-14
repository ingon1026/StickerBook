package com.k3i.stickerbook.rig

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

class StubRigger(private val context: Context) : CharacterRigger {

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        delay(1500)  // fake processing time

        val stickerId = "stub_${System.currentTimeMillis()}"
        val root = File(context.filesDir, "stickerbook_assets")
        val sDir = File(root, "stickers/$stickerId")
        val framesDir = File(sDir, "frames")
        framesDir.mkdirs()

        writePng(image, File(framesDir, "0001.png"))
        writePng(image, File(sDir, "texture.png"))
        writePng(image, File(sDir, "animation.gif"))  // not a real gif; placeholder
        writePng(image, File(sDir, "source.png"))

        val rel = "stickers/$stickerId"
        return RigResult(
            framesDir = "$rel/frames",
            fps = 30,
            frameCount = 1,
            width = image.width,
            height = image.height,
            texturePath = "$rel/texture.png",
            gifPath = "$rel/animation.gif",
            sourcePath = "$rel/source.png",
        )
    }

    private fun writePng(bmp: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
