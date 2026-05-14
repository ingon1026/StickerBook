package com.k3i.stickerbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.k3i.stickerbook.ui.nav.AppNavHost
import com.k3i.stickerbook.ui.theme.StickerbookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StickerbookTheme {
                AppNavHost()
            }
        }
    }
}
