package com.k3i.stickerbook.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureSessionTest {

    @Test
    fun set_and_reset_state() {
        val s = CaptureSession()
        assertNull(s.image)
        assertNull(s.motion)

        s.image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        s.motion = "dance_1"
        assertNotNull(s.image)
        assertNotNull(s.motion)

        s.reset()
        assertNull(s.image)
        assertNull(s.motion)
    }
}
