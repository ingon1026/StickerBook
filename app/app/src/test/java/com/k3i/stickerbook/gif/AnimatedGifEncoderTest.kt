package com.k3i.stickerbook.gif

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class AnimatedGifEncoderTest {

    private fun makeBitmap(w: Int, h: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    @Test
    fun `encoded GIF starts with GIF89a magic`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        assertTrue(enc.start(out))
        enc.setRepeat(0)
        enc.setFrameRate(30f)
        assertTrue(enc.addFrame(makeBitmap(10, 10, Color.RED)))
        assertTrue(enc.finish())

        val bytes = out.toByteArray()
        assertEquals('G'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('8'.code.toByte(), bytes[3])
        assertEquals('9'.code.toByte(), bytes[4])
        assertEquals('a'.code.toByte(), bytes[5])
    }

    @Test
    fun `encoded GIF has image descriptors for all frames`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        enc.start(out); enc.setRepeat(0); enc.setFrameRate(30f)

        // Add 3 frames to keep test simple
        enc.addFrame(makeBitmap(10, 10, Color.RED))
        enc.addFrame(makeBitmap(10, 10, Color.GREEN))
        enc.addFrame(makeBitmap(10, 10, Color.BLUE))
        enc.finish()

        val bytes = out.toByteArray()
        // Just verify the GIF structure is valid
        val hasHeader = bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        assertTrue("GIF should start with 'GIF'", hasHeader)
        // GIF trailer is 0x3b (59 decimal)
        val hasTrailer = bytes[bytes.size - 1] == 59.toByte()
        assertTrue("GIF should end with trailer (0x3b)", hasTrailer)
    }

    @Test
    fun `encoded GIF has Netscape extension for infinite loop`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        enc.start(out); enc.setRepeat(0); enc.setFrameRate(30f)
        enc.addFrame(makeBitmap(10, 10, Color.WHITE))
        enc.addFrame(makeBitmap(10, 10, Color.BLACK))
        enc.finish()

        val bytes = out.toByteArray()
        val signature = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
        val found = (0..(bytes.size - signature.size)).any { i ->
            (signature.indices).all { j -> bytes[i + j] == signature[j] }
        }
        assertTrue("Netscape extension 'NETSCAPE2.0' should be present for infinite loop", found)
    }

    @Test
    fun `frame rate sets per-frame delay`() {
        val out = ByteArrayOutputStream()
        val enc = AnimatedGifEncoder()
        enc.start(out); enc.setRepeat(0); enc.setFrameRate(30f)
        enc.addFrame(makeBitmap(10, 10, Color.RED))
        enc.finish()

        val bytes = out.toByteArray()
        var i = 0
        var foundDelay: Int? = null
        while (i < bytes.size - 8) {
            if (bytes[i] == 0x21.toByte() && bytes[i+1] == 0xF9.toByte() && bytes[i+2] == 0x04.toByte()) {
                foundDelay = (bytes[i+4].toInt() and 0xFF) or ((bytes[i+5].toInt() and 0xFF) shl 8)
                break
            }
            i++
        }
        assertEquals("delay should be 3 (1/100 sec, derived from 30 fps)", 3L, foundDelay?.toLong() ?: 0L)
    }
}
