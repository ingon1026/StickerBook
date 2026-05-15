package com.k3i.stickerbook.gif

// Adapted from Jef Poskanzer's Java port by way of J. M. G. Elliott.
// K Weiner 12/00
// Ported to Kotlin

import java.io.IOException
import java.io.OutputStream

internal class LzwEncoder(width: Int, height: Int, pixels: ByteArray, colorDepth: Int) {

    companion object {
        private const val EOF = -1
        internal const val BITS = 12
        internal const val HSIZE = 5003
    }

    private val imgW = width
    private val imgH = height
    private val pixAry = pixels
    private val initCodeSize = maxOf(2, colorDepth)

    private var remaining = 0
    private var curPixel = 0

    private var nBits = 0
    private val maxbits = BITS
    private var maxcode = 0
    private val maxmaxcode = 1 shl BITS

    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)
    private val hsize = HSIZE
    private var freeEnt = 0

    private var clearFlg = false

    private var gInitBits = 0
    private var clearCode = 0
    private var eofCode = 0

    private var curAccum = 0
    private var curBits = 0

    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F,
        0x00FF, 0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )

    private var aCount = 0
    private val accum = ByteArray(256)

    private fun charOut(c: Byte, outs: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar(outs)
    }

    private fun clBlock(outs: OutputStream) {
        clHash(hsize)
        freeEnt = clearCode + 2
        clearFlg = true
        output(clearCode, outs)
    }

    private fun clHash(hsize: Int) {
        for (i in 0 until hsize) htab[i] = -1
    }

    private fun compress(initBits: Int, outs: OutputStream) {
        var fcode: Int
        var i: Int
        var c: Int
        var ent: Int
        var disp: Int
        val hsizeReg: Int
        var hshift: Int

        gInitBits = initBits
        clearFlg = false
        nBits = gInitBits
        maxcode = MAXCODE(nBits)

        clearCode = 1 shl (initBits - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2

        aCount = 0

        ent = nextPixel()

        hshift = 0
        fcode = hsize
        while (fcode < 65536) {
            ++hshift
            fcode *= 2
        }
        hshift = 8 - hshift

        hsizeReg = hsize
        clHash(hsizeReg)

        output(clearCode, outs)

        outer@ while (nextPixel().also { c = it } != EOF) {
            fcode = (c shl maxbits) + ent
            i = (c shl hshift) xor ent

            if (htab[i] == fcode) {
                ent = codetab[i]
                continue
            } else if (htab[i] >= 0) {
                disp = hsizeReg - i
                if (i == 0) disp = 1
                do {
                    i -= disp
                    if (i < 0) i += hsizeReg
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        continue@outer
                    }
                } while (htab[i] >= 0)
            }
            output(ent, outs)
            ent = c
            if (freeEnt < maxmaxcode) {
                codetab[i] = freeEnt++
                htab[i] = fcode
            } else {
                clBlock(outs)
            }
        }
        output(ent, outs)
        output(eofCode, outs)
    }

    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        remaining = imgW * imgH
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0)
    }

    private fun flushChar(outs: OutputStream) {
        if (aCount > 0) {
            outs.write(aCount)
            outs.write(accum, 0, aCount)
            aCount = 0
        }
    }

    private fun MAXCODE(nBits: Int) = (1 shl nBits) - 1

    private fun nextPixel(): Int {
        if (remaining == 0) return EOF
        --remaining
        val pix = pixAry[curPixel++]
        return pix.toInt() and 0xff
    }

    private fun output(code: Int, outs: OutputStream) {
        curAccum = curAccum and masks[curBits]

        curAccum = if (curBits > 0) curAccum or (code shl curBits) else code

        curBits += nBits

        while (curBits >= 8) {
            charOut((curAccum and 0xff).toByte(), outs)
            curAccum = curAccum ushr 8
            curBits -= 8
        }

        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                maxcode = MAXCODE(gInitBits.also { nBits = it })
                clearFlg = false
            } else {
                ++nBits
                maxcode = if (nBits == maxbits) maxmaxcode else MAXCODE(nBits)
            }
        }

        if (code == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xff).toByte(), outs)
                curAccum = curAccum ushr 8
                curBits -= 8
            }
            flushChar(outs)
        }
    }
}
