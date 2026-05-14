package com.k3i.stickerbook.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.k3i.stickerbook.data.AnimationSaver
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.ui.StickerDetailScreen
import com.k3i.stickerbook.ui.StickerListScreen

@Composable
fun AppNavHost() {
    val ctx = LocalContext.current
    val manifest by produceState<Manifest?>(initialValue = null) {
        value = AssetRepository(ctx).loadManifest()
    }
    val m = manifest
    if (m == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            StickerListScreen(
                manifest = m,
                onStickerClick = { entry -> nav.navigate("detail/${entry.id}") },
            )
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStack ->
            val id = backStack.arguments?.getString("id") ?: return@composable
            val idx = m.stickers.indexOfFirst { it.id == id }
            if (idx < 0) return@composable
            val entry = m.stickers[idx]
            StickerDetailScreen(
                entry = entry,
                onBack = { nav.popBackStack() },
                onPrev = {
                    val prev = m.stickers.getOrNull(idx - 1)
                    if (prev != null) {
                        nav.navigate("detail/${prev.id}") { popUpTo("list") }
                    }
                },
                onNext = {
                    val next = m.stickers.getOrNull(idx + 1)
                    if (next != null) {
                        nav.navigate("detail/${next.id}") { popUpTo("list") }
                    }
                },
                onSave = {
                    val saver = AnimationSaver(ctx)
                    val uri = saver.saveGif(entry)
                    val msg = if (uri != null) "갤러리에 저장됨" else "저장 실패"
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
