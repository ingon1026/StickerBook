package com.k3i.stickerbook.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStubTest {

    private fun fakeInitialPins(): FloatArray {
        val pins = FloatArray(17 * 2)
        for (i in 0 until 17) {
            pins[i * 2] = (i * 10).toFloat()
            pins[i * 2 + 1] = (i * 20).toFloat()
        }
        pins[5 * 2] = 100f;  pins[5 * 2 + 1] = 200f   // l_shoulder
        pins[9 * 2] = 50f;   pins[9 * 2 + 1] = 400f   // l_wrist
        pins[6 * 2] = 150f;  pins[6 * 2 + 1] = 210f   // r_shoulder
        pins[10 * 2] = 200f; pins[10 * 2 + 1] = 410f  // r_wrist
        return pins
    }

    @Test
    fun `identity motion returns frames matching initial pins`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("identity", pins, frameCount = 5)
        assertEquals(5, frames.size)
        for (f in frames) {
            for (i in pins.indices) {
                assertEquals(pins[i].toDouble(), f[i].toDouble(), 0.001)
            }
        }
    }

    @Test
    fun `unknown motion now returns identity frames`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("dance_1", pins, frameCount = 30)
        // All frames should match initial pins (identity: no motion)
        for (f in frames) {
            for (i in pins.indices) {
                assertEquals(pins[i].toDouble(), f[i].toDouble(), 0.001)
            }
        }
    }

    @Test
    fun `wave motion frame 0 equals initial pins`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("wave", pins, frameCount = 30)
        for (i in pins.indices) {
            assertEquals(pins[i].toDouble(), frames[0][i].toDouble(), 0.001)
        }
    }

    @Test
    fun `wave motion moves wrist y over frames`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("wave", pins, frameCount = 30)
        val initialWristY = pins[9 * 2 + 1]
        val midWristY = frames[15][9 * 2 + 1]
        assertTrue(kotlin.math.abs(midWristY - initialWristY) > 1f)
    }

    @Test
    fun `wave motion frame count matches`() {
        val stub = MotionStub()
        val pins = fakeInitialPins()
        val frames = stub.frames("wave", pins, frameCount = 60)
        assertEquals(60, frames.size)
        assertEquals(pins.size, frames[0].size)
    }
}
