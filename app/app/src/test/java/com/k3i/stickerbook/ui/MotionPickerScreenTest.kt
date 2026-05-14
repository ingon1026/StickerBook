package com.k3i.stickerbook.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.k3i.stickerbook.data.CaptureSession
import com.k3i.stickerbook.data.LocalCaptureSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class MotionPickerScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun lists_motions_and_select_updates_session() {
        val session = CaptureSession()
        rule.setContent {
            CompositionLocalProvider(LocalCaptureSession provides session) {
                MotionPickerScreen(onBack = {}, onConfirm = {})
            }
        }
        rule.onNodeWithText("댑").assertIsDisplayed()
        rule.onNodeWithText("댄스 1").performClick()
        assertEquals("dance_1", session.motion)
    }
}
