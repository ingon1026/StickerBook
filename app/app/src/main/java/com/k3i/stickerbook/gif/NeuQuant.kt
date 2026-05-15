package com.k3i.stickerbook.gif

/* NeuQuant Neural-Net Quantization Algorithm
 * ------------------------------------------
 *
 * Copyright (c) 1994 Anthony Dekker
 *
 * NEUQUANT Neural-Net quantization algorithm by Anthony Dekker, 1994.
 * See "Kohonen neural networks for optimal colour quantization"
 * in "Network: Computation in Neural Systems" Vol. 5 (1994) pp 351-367.
 *
 * Any party obtaining a copy of these files from the author, directly or
 * indirectly, is granted, free of charge, a full and unrestricted irrevocable,
 * world-wide, paid up, royalty-free, nonexclusive right and license to deal
 * in this software and documentation files (the "Software"), including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons who receive
 * copies from any such party to do so, with the only requirement being
 * that this copyright notice remain intact.
 */

// Ported to Java 12/00 K Weiner
// Ported to Kotlin

internal class NeuQuant(thepic: ByteArray, len: Int, sample: Int) {

    companion object {
        internal const val netsize = 256

        internal const val prime1 = 499
        internal const val prime2 = 491
        internal const val prime3 = 487
        internal const val prime4 = 503

        internal const val minpicturebytes = (3 * prime4)

        internal const val maxnetpos = (netsize - 1)
        internal const val netbiasshift = 4
        internal const val ncycles = 100

        internal const val intbiasshift = 16
        internal const val intbias = (1 shl intbiasshift)
        internal const val gammashift = 10
        internal const val gamma = (1 shl gammashift)
        internal const val betashift = 10
        internal const val beta = (intbias shr betashift)
        internal const val betagamma = (intbias shl (gammashift - betashift))

        internal const val initrad = (netsize shr 3)
        internal const val radiusbiasshift = 6
        internal const val radiusbias = (1 shl radiusbiasshift)
        internal const val initradius = (initrad * radiusbias)
        internal const val radiusdec = 30

        internal const val alphabiasshift = 10
        internal const val initalpha = (1 shl alphabiasshift)

        internal const val radbiasshift = 8
        internal const val radbias = (1 shl radbiasshift)
        internal const val alpharadbshift = (alphabiasshift + radbiasshift)
        internal const val alpharadbias = (1 shl alpharadbshift)
    }

    private val thepicture: ByteArray = thepic
    private val lengthcount: Int = len
    private val samplefac: Int = sample

    private val network: Array<IntArray> = Array(netsize) { IntArray(4) }
    private val netindex = IntArray(256)
    private val bias = IntArray(netsize)
    private val freq = IntArray(netsize)
    private val radpower = IntArray(initrad)

    private var alphadec: Int = 0

    init {
        for (i in 0 until netsize) {
            val p = network[i]
            p[0] = (i shl (netbiasshift + 8)) / netsize
            p[1] = (i shl (netbiasshift + 8)) / netsize
            p[2] = (i shl (netbiasshift + 8)) / netsize
            freq[i] = intbias / netsize
            bias[i] = 0
        }
    }

    private fun colorMap(): ByteArray {
        val map = ByteArray(3 * netsize)
        val index = IntArray(netsize)
        for (i in 0 until netsize)
            index[network[i][3]] = i
        var k = 0
        for (i in 0 until netsize) {
            val j = index[i]
            map[k++] = network[j][0].toByte()
            map[k++] = network[j][1].toByte()
            map[k++] = network[j][2].toByte()
        }
        return map
    }

    private fun inxbuild() {
        var previouscol = 0
        var startpos = 0
        for (i in 0 until netsize) {
            val p = network[i]
            var smallpos = i
            var smallval = p[1]
            for (j in i + 1 until netsize) {
                val q = network[j]
                if (q[1] < smallval) {
                    smallpos = j
                    smallval = q[1]
                }
            }
            val q = network[smallpos]
            if (i != smallpos) {
                var j = q[0]; q[0] = p[0]; p[0] = j
                j = q[1]; q[1] = p[1]; p[1] = j
                j = q[2]; q[2] = p[2]; p[2] = j
                j = q[3]; q[3] = p[3]; p[3] = j
            }
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) shr 1
                for (j in previouscol + 1 until smallval)
                    netindex[j] = i
                previouscol = smallval
                startpos = i
            }
        }
        netindex[previouscol] = (startpos + maxnetpos) shr 1
        for (j in previouscol + 1 until 256)
            netindex[j] = maxnetpos
    }

    private fun learn() {
        var i: Int
        var j: Int
        var b: Int
        var g: Int
        var r: Int
        val p = thepicture
        var pix: Int
        val lim: Int

        if (lengthcount < minpicturebytes)
            alphadec = 1  // samplefac forced to 1 below
        else
            alphadec = 30 + ((samplefac - 1) / 3)

        var localSamplefac = samplefac
        if (lengthcount < minpicturebytes) localSamplefac = 1

        pix = 0
        lim = lengthcount
        val samplepixels = lengthcount / (3 * localSamplefac)
        var delta = samplepixels / ncycles
        var alpha = initalpha
        var radius = initradius

        var rad = radius shr radiusbiasshift
        if (rad <= 1) rad = 0
        for (ii in 0 until rad)
            radpower[ii] = alpha * (((rad * rad - ii * ii) * radbias) / (rad * rad))

        val step: Int
        if (lengthcount < minpicturebytes)
            step = 3
        else if ((lengthcount % prime1) != 0)
            step = 3 * prime1
        else {
            step = if ((lengthcount % prime2) != 0) 3 * prime2
            else if ((lengthcount % prime3) != 0) 3 * prime3
            else 3 * prime4
        }

        i = 0
        while (i < samplepixels) {
            b = (p[pix].toInt() and 0xff) shl netbiasshift
            g = (p[pix + 1].toInt() and 0xff) shl netbiasshift
            r = (p[pix + 2].toInt() and 0xff) shl netbiasshift
            j = contest(b, g, r)

            altersingle(alpha, j, b, g, r)
            if (rad != 0) alterneigh(rad, j, b, g, r)

            pix += step
            if (pix >= lim) pix -= lengthcount

            i++
            if (delta == 0) delta = 1
            if (i % delta == 0) {
                alpha -= alpha / alphadec
                radius -= radius / radiusdec
                rad = radius shr radiusbiasshift
                if (rad <= 1) rad = 0
                for (jj in 0 until rad)
                    radpower[jj] = alpha * (((rad * rad - jj * jj) * radbias) / (rad * rad))
            }
        }
    }

    fun map(b: Int, g: Int, r: Int): Int {
        var bestd = 1000
        var best = -1
        var i = netindex[g]
        var j = i - 1

        while (i < netsize || j >= 0) {
            if (i < netsize) {
                val p = network[i]
                var dist = p[1] - g
                if (dist >= bestd) {
                    i = netsize
                } else {
                    i++
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
            if (j >= 0) {
                val p = network[j]
                var dist = g - p[1]
                if (dist >= bestd) {
                    j = -1
                } else {
                    j--
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
        }
        return best
    }

    fun process(): ByteArray {
        learn()
        unbiasnet()
        inxbuild()
        return colorMap()
    }

    private fun unbiasnet() {
        for (i in 0 until netsize) {
            network[i][0] = network[i][0] shr netbiasshift
            network[i][1] = network[i][1] shr netbiasshift
            network[i][2] = network[i][2] shr netbiasshift
            network[i][3] = i
        }
    }

    private fun alterneigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        val lo = if (i - rad < -1) -1 else i - rad
        val hi = if (i + rad > netsize) netsize else i + rad

        var j = i + 1
        var k = i - 1
        var m = 1
        while (j < hi || k > lo) {
            val a = radpower[m++]
            if (j < hi) {
                val p = network[j++]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (e: Exception) {
                }
            }
            if (k > lo) {
                val p = network[k--]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun altersingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - b)) / initalpha
        n[1] -= (alpha * (n[1] - g)) / initalpha
        n[2] -= (alpha * (n[2] - r)) / initalpha
    }

    private fun contest(b: Int, g: Int, r: Int): Int {
        var bestd = Int.MAX_VALUE
        var bestbiasd = bestd
        var bestpos = -1
        var bestbiaspos = bestpos

        for (i in 0 until netsize) {
            val n = network[i]
            var dist = n[0] - b
            if (dist < 0) dist = -dist
            var a = n[1] - g
            if (a < 0) a = -a
            dist += a
            a = n[2] - r
            if (a < 0) a = -a
            dist += a
            if (dist < bestd) {
                bestd = dist
                bestpos = i
            }
            val biasdist = dist - (bias[i] shr (intbiasshift - netbiasshift))
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist
                bestbiaspos = i
            }
            val betafreq = freq[i] shr betashift
            freq[i] -= betafreq
            bias[i] += (betafreq shl gammashift)
        }
        freq[bestpos] += beta
        bias[bestpos] -= betagamma
        return bestbiaspos
    }
}
