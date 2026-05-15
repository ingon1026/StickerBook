package com.k3i.stickerbook.rig

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonMotionSourceTest {

    private fun fakeInitialPins(): FloatArray {
        val pins = FloatArray(17 * 2)
        for (i in 0 until 17) {
            pins[i * 2] = (i * 10).toFloat()
            pins[i * 2 + 1] = (i * 20).toFloat()
        }
        pins[5 * 2] = 100f;  pins[5 * 2 + 1] = 200f   // l_shoulder
        pins[6 * 2] = 50f;   pins[6 * 2 + 1] = 200f   // r_shoulder (shoulder_dist = 50)
        pins[11 * 2] = 70f;  pins[11 * 2 + 1] = 400f  // l_hip
        pins[12 * 2] = 80f;  pins[12 * 2 + 1] = 400f  // r_hip (hip center = (75, 400))
        return pins
    }

    @Test
    fun `unknown motion returns identity frames`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pins = fakeInitialPins()
        val src = JsonMotionSource(ctx)
        val frames = src.frames("does_not_exist", pins, frameCount = 5)
        assertEquals(5, frames.size)
        for (f in frames) {
            for (i in pins.indices) {
                assertEquals(pins[i], f[i], 0.001f)
            }
        }
    }

    @Test
    fun `frame 0 of normalized motion equals initialPins anchor`() {
        val normalizedFixed = List(1) {
            List(17) { k ->
                when (k) {
                    5 -> listOf(0.5f, 0f)       // l_shoulder normalized
                    6 -> listOf(-0.5f, 0f)      // r_shoulder normalized
                    11 -> listOf(-0.05f, 0f)
                    12 -> listOf(0.05f, 0f)
                    else -> listOf(0f, 0f)
                }
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = JsonMotionSource.withAssetLoader(ctx) { id ->
            if (id == "synth1") JsonMotionData(
                motionId = "synth1", frameCount = 1, fps = 30,
                backend = PoseBackend.AD_COCO_17, frames = normalizedFixed,
            ) else null
        }
        val pins = fakeInitialPins()
        val frames = src.frames("synth1", pins, frameCount = 1)
        assertEquals(1, frames.size)
        // hip center = (75, 400), nose = (0, 0)
        // torso = sqrt((0-75)^2 + (0-400)^2) = sqrt(165625) ≈ 406.97, scale = 406.97*1.5 ≈ 610.45
        // l_shoulder normalized (0.5, 0) → image (75 + 0.5*610.45, 400 + 0*610.45) ≈ (380.2, 400)
        assertEquals(380.2f, frames[0][5*2], 1.0f)
        assertEquals(400f, frames[0][5*2+1], 0.5f)
        // face keypoint (kp 0) = initialPins (static)
        assertEquals(pins[0], frames[0][0], 0.001f)
        assertEquals(pins[1], frames[0][1], 0.001f)
    }

    @Test
    fun `interpolation maps source N frames to target M frames evenly`() {
        val normalized = List(3) { f ->
            List(17) { k ->
                if (k == 9) listOf(f.toFloat(), 0f) else listOf(0f, 0f)
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = JsonMotionSource.withAssetLoader(ctx) { id ->
            if (id == "synth2") JsonMotionData(
                motionId = "synth2", frameCount = 3, fps = 30,
                backend = PoseBackend.AD_COCO_17, frames = normalized,
            ) else null
        }
        val pins = fakeInitialPins()
        val target = 5
        val frames = src.frames("synth2", pins, frameCount = target)
        assertEquals(target, frames.size)
        // hip center = (75, 400), nose = (0, 0)
        // torso ≈ 406.97, scale ≈ 610.45
        // frame 0: l_wrist normalized x = 0 → image x = 75
        assertEquals(75f, frames[0][9*2], 1.0f)
        // frame 4 (last): l_wrist normalized x = 2 → image x = 75 + 2*610.45 ≈ 1295.9
        assertEquals(1295.9f, frames[target-1][9*2], 2.0f)
        // frame 2 mid: t = 2/4 * 2 = 1.0 → source frame 1 (x=1) → image x = 75 + 610.45 ≈ 685.45
        assertEquals(685.45f, frames[2][9*2], 2.0f)
    }

    @Test
    fun `target frameCount of 1 returns first source frame`() {
        val normalized = List(3) { f ->
            List(17) { k ->
                if (k == 9) listOf(f.toFloat(), 0f) else listOf(0f, 0f)
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = JsonMotionSource.withAssetLoader(ctx) { id ->
            if (id == "synth3") JsonMotionData(
                motionId = "synth3", frameCount = 3, fps = 30,
                backend = PoseBackend.AD_COCO_17, frames = normalized,
            ) else null
        }
        val pins = fakeInitialPins()
        val frames = src.frames("synth3", pins, frameCount = 1)
        assertEquals(1, frames.size)
        assertEquals(75f, frames[0][9*2], 0.5f)
    }
}
