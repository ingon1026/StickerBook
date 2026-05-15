package com.k3i.stickerbook.gif

/**
 * Class AnimatedGifEncoder - Encodes a GIF file consisting of one or more frames.
 *
 * No copyright asserted on the source code of this class. May be used for any
 * purpose, however, refer to the Unisys LZW patent for restrictions on use of
 * the associated LzwEncoder class. Please forward any corrections to
 * kweiner@fmsware.com.
 *
 * @author Kevin Weiner, FM Software
 * @version 1.03 November 2003
 *
 * Ported to Kotlin (Android Bitmap + OutputStream only, zero external dependencies)
 */

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.OutputStream

class AnimatedGifEncoder {

    private companion object {
        private const val MIN_TRANSPARENT_PERCENTAGE = 4.0
    }

    private var width = 0
    private var height = 0
    private var fixedWidth = 0
    private var fixedHeight = 0
    private var transparent: Int? = null
    private var transIndex = 0
    private var repeat = -1
    private var delay = 0
    private var started = false
    private var out: OutputStream? = null
    private var image: Bitmap? = null
    private var pixels: ByteArray? = null
    private var indexedPixels: ByteArray? = null
    private var colorDepth = 0
    private var colorTab: ByteArray? = null
    private val usedEntry = BooleanArray(256)
    private var palSize = 7
    private var dispose = -1
    private var firstFrame = true
    private var sizeSet = false
    private var sample = 10
    private var hasTransparentPixels = false

    fun setDelay(ms: Int) {
        delay = Math.round(ms / 10.0f)
    }

    fun setDispose(code: Int) {
        if (code >= 0) dispose = code
    }

    fun setRepeat(iter: Int) {
        if (iter >= 0) repeat = iter
    }

    fun setTransparent(color: Int) {
        transparent = color
    }

    fun addFrame(im: Bitmap?): Boolean {
        return addFrame(im, 0, 0)
    }

    fun addFrame(im: Bitmap?, x: Int, y: Int): Boolean {
        if (im == null || !started) return false
        var ok = true
        try {
            if (sizeSet) {
                setFrameSize(fixedWidth, fixedHeight)
            } else {
                setFrameSize(im.width, im.height)
            }
            image = im
            getImagePixels()
            analyzePixels()
            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) writeNetscapeExt()
            }
            writeGraphicCtrlExt()
            writeImageDesc(x, y)
            if (!firstFrame) writePalette()
            writePixels()
            firstFrame = false
        } catch (e: Exception) {
            ok = false
        }
        return ok
    }

    fun finish(): Boolean {
        if (!started) return false
        var ok = true
        started = false
        try {
            out!!.write(0x3b)
            out!!.flush()
        } catch (e: Exception) {
            ok = false
        }
        transIndex = 0
        out = null
        image = null
        pixels = null
        indexedPixels = null
        colorTab = null
        firstFrame = true
        return ok
    }

    fun setFrameRate(fps: Float) {
        if (fps != 0f) delay = Math.round(100f / fps)
    }

    fun setQuality(quality: Int) {
        sample = if (quality < 1) 1 else quality
    }

    fun setSize(w: Int, h: Int) {
        if (started) return
        fixedWidth = if (w < 1) 320 else w
        fixedHeight = if (h < 1) 240 else h
        sizeSet = true
    }

    private fun setFrameSize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun start(os: OutputStream?): Boolean {
        if (os == null) return false
        var ok = true
        out = os
        try {
            writeString("GIF89a")
        } catch (e: Exception) {
            ok = false
        }
        started = ok
        return started
    }

    private fun analyzePixels() {
        val len = pixels!!.size
        val nPix = len / 3
        indexedPixels = ByteArray(nPix)
        val nq = NeuQuant(pixels!!, len, sample)
        colorTab = nq.process()
        // convert map from BGR to RGB
        for (i in 0 until colorTab!!.size step 3) {
            val temp = colorTab!![i]
            colorTab!![i] = colorTab!![i + 2]
            colorTab!![i + 2] = temp
            usedEntry[i / 3] = false
        }
        var k = 0
        for (i in 0 until nPix) {
            val index = nq.map(
                pixels!![k++].toInt() and 0xff,
                pixels!![k++].toInt() and 0xff,
                pixels!![k++].toInt() and 0xff
            )
            usedEntry[index] = true
            indexedPixels!![i] = index.toByte()
        }
        pixels = null
        colorDepth = 8
        palSize = 7
        if (transparent != null) {
            transIndex = findClosest(transparent!!)
        } else if (hasTransparentPixels) {
            transIndex = findClosest(Color.TRANSPARENT)
        }
    }

    private fun findClosest(color: Int): Int {
        if (colorTab == null) return -1
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        var minpos = 0
        var dmin = 256 * 256 * 256
        val len = colorTab!!.size
        var i = 0
        while (i < len) {
            val dr = r - (colorTab!![i++].toInt() and 0xff)
            val dg = g - (colorTab!![i++].toInt() and 0xff)
            val db = b - (colorTab!![i].toInt() and 0xff)
            val d = dr * dr + dg * dg + db * db
            val index = i / 3
            if (usedEntry[index] && d < dmin) {
                dmin = d
                minpos = index
            }
            i++
        }
        return minpos
    }

    private fun getImagePixels() {
        val w = image!!.width
        val h = image!!.height

        if (w != width || h != height) {
            val temp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(temp)
            canvas.drawBitmap(image!!, 0f, 0f, null)
            image = temp
        }
        val pixelsInt = IntArray(w * h)
        image!!.getPixels(pixelsInt, 0, w, 0, 0, w, h)

        pixels = ByteArray(pixelsInt.size * 3)
        var pixelsIndex = 0
        hasTransparentPixels = false
        var totalTransparentPixels = 0
        for (pixel in pixelsInt) {
            if (pixel == Color.TRANSPARENT) totalTransparentPixels++
            pixels!![pixelsIndex++] = (pixel and 0xFF).toByte()
            pixels!![pixelsIndex++] = ((pixel shr 8) and 0xFF).toByte()
            pixels!![pixelsIndex++] = ((pixel shr 16) and 0xFF).toByte()
        }
        val transparentPercentage = 100.0 * totalTransparentPixels / pixelsInt.size.toDouble()
        hasTransparentPixels = transparentPercentage > MIN_TRANSPARENT_PERCENTAGE
    }

    private fun writeGraphicCtrlExt() {
        out!!.write(0x21)
        out!!.write(0xf9)
        out!!.write(4)
        var transp: Int
        var disp: Int
        if (transparent == null && !hasTransparentPixels) {
            transp = 0
            disp = 0
        } else {
            transp = 1
            disp = 2
        }
        if (dispose >= 0) disp = dispose and 7
        disp = disp shl 2

        out!!.write(0 or disp or 0 or transp)
        writeShort(delay)
        out!!.write(transIndex)
        out!!.write(0)
    }

    private fun writeImageDesc(x: Int, y: Int) {
        out!!.write(0x2c)
        writeShort(x)
        writeShort(y)
        writeShort(width)
        writeShort(height)
        if (firstFrame) {
            out!!.write(0)
        } else {
            out!!.write(0x80 or 0 or 0 or 0 or palSize)
        }
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out!!.write(0x80 or 0x70 or 0x00 or palSize)
        out!!.write(0)
        out!!.write(0)
    }

    private fun writeNetscapeExt() {
        out!!.write(0x21)
        out!!.write(0xff)
        out!!.write(11)
        writeString("NETSCAPE2.0")
        out!!.write(3)
        out!!.write(1)
        writeShort(repeat)
        out!!.write(0)
    }

    private fun writePalette() {
        out!!.write(colorTab!!, 0, colorTab!!.size)
        val n = (3 * 256) - colorTab!!.size
        for (i in 0 until n) out!!.write(0)
    }

    private fun writePixels() {
        val encoder = LzwEncoder(width, height, indexedPixels!!, colorDepth)
        encoder.encode(out!!)
    }

    private fun writeShort(value: Int) {
        out!!.write(value and 0xff)
        out!!.write((value shr 8) and 0xff)
    }

    private fun writeString(s: String) {
        for (c in s) out!!.write(c.code)
    }
}
