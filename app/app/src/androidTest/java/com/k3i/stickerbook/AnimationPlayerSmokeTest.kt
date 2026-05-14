package com.k3i.stickerbook

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k3i.stickerbook.ui.nav.AppNavHost
import com.k3i.stickerbook.ui.theme.StickerbookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimationPlayerSmokeTest {

    @Rule
    @JvmField
    val rule = createComposeRule()

    @Test
    fun list_screen_renders() {
        rule.setContent {
            StickerbookTheme(dynamicColor = false) {
                AppNavHost()
            }
        }
        rule.waitForIdle()

        val empty = rule.onAllNodesWithText("자산이 없습니다", substring = true)
            .fetchSemanticsNodes()
        if (empty.isNotEmpty()) return  // empty-state path — still a valid render

        rule.onNodeWithText("스티커북").assertExists()
    }
}
