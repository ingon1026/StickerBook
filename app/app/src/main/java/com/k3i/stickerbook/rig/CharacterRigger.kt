package com.k3i.stickerbook.rig

import android.graphics.Bitmap

interface CharacterRigger {
    suspend fun rig(image: Bitmap, motion: String): RigResult
}
