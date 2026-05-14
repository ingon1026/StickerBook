package com.k3i.stickerbook.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.ui.StickerDetailScreen
import com.k3i.stickerbook.ui.StickerListScreen

@Composable
fun AppNavHost() {
    val ctx = LocalContext.current
    val manifest = remember { AssetRepository(ctx).loadManifest() }
        ?: Manifest(formatVersion = 1, generatedAt = "", stickers = emptyList())
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            StickerListScreen(onStickerClick = { entry ->
                nav.navigate("detail/${entry.id}")
            })
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStack ->
            val id = backStack.arguments?.getString("id") ?: return@composable
            val idx = manifest.stickers.indexOfFirst { it.id == id }
            if (idx < 0) return@composable
            val entry = manifest.stickers[idx]
            StickerDetailScreen(
                entry = entry,
                onBack = { nav.popBackStack() },
                onPrev = {
                    val prev = manifest.stickers.getOrNull(idx - 1)
                    if (prev != null) {
                        nav.navigate("detail/${prev.id}") { popUpTo("list") }
                    }
                },
                onNext = {
                    val next = manifest.stickers.getOrNull(idx + 1)
                    if (next != null) {
                        nav.navigate("detail/${next.id}") { popUpTo("list") }
                    }
                },
                onSave = {
                    val saver = com.k3i.stickerbook.data.AnimationSaver(ctx)
                    val uri = saver.saveGif(entry)
                    val msg = if (uri != null) "갤러리에 저장됨" else "저장 실패"
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
