package com.k3i.stickerbook.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.k3i.stickerbook.data.CaptureSession
import com.k3i.stickerbook.data.LocalCaptureSession
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.data.StickerEntry
import com.k3i.stickerbook.rig.ArapRigger
import com.k3i.stickerbook.rig.PoseDetectionRigger
import com.k3i.stickerbook.rig.StubRigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.k3i.stickerbook.ui.ArViewScreen
import com.k3i.stickerbook.ui.CaptureReviewScreen
import com.k3i.stickerbook.ui.CaptureScreen
import com.k3i.stickerbook.ui.MotionPickerScreen
import com.k3i.stickerbook.ui.ProcessingScreen
import com.k3i.stickerbook.ui.StickerDetailScreen
import com.k3i.stickerbook.ui.StickerListScreen

@Composable
fun AppNavHost() {
    val ctx = LocalContext.current
    val session = remember { CaptureSession() }
    val rigger = remember { ArapRigger.real(ctx) }

    var manifestVersion by remember { mutableStateOf(0) }
    val manifest by produceState<Manifest?>(initialValue = null, manifestVersion) {
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

    CompositionLocalProvider(LocalCaptureSession provides session) {
        NavHost(navController = nav, startDestination = "list") {
            composable("list") {
                StickerListScreen(
                    manifest = m,
                    onStickerClick = { entry -> nav.navigate("detail/${entry.id}") },
                    onCaptureClick = { nav.navigate(NavMode.NORMAL.routeOf("capture")) },
                    onArCaptureClick = { nav.navigate(NavMode.AR_AUTO.routeOf("capture")) },
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
                        m.stickers.getOrNull(idx - 1)?.let {
                            nav.navigate("detail/${it.id}") { popUpTo("list") }
                        }
                    },
                    onNext = {
                        m.stickers.getOrNull(idx + 1)?.let {
                            nav.navigate("detail/${it.id}") { popUpTo("list") }
                        }
                    },
                    onSave = {
                        val saver = AnimationSaver(ctx)
                        val uri = saver.saveGif(entry)
                        val msg = if (uri != null) "갤러리에 저장됨" else "저장 실패"
                        android.widget.Toast.makeText(
                            ctx, msg, android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onAR = { nav.navigate("arview/${entry.id}") },
                    onDelete = {
                        AssetRepository(ctx).deleteSticker(entry.id)
                        manifestVersion++
                        nav.popBackStack()
                    },
                )
            }

            modeRoute("capture") { mode ->
                CaptureScreen(
                    onBack = { nav.popBackStack() },
                    onCaptured = {
                        val next = when (mode) {
                            NavMode.NORMAL -> mode.routeOf("review")
                            NavMode.AR_AUTO -> mode.routeOf("motion")
                        }
                        nav.navigate(next)
                    },
                )
            }

            modeRoute("review") { mode ->
                CaptureReviewScreen(
                    onBack = { nav.popBackStack() },
                    onRetake = { nav.popBackStack() },
                    onNext = { nav.navigate(mode.routeOf("motion")) },
                )
            }

            modeRoute("motion") { mode ->
                MotionPickerScreen(
                    onBack = { nav.popBackStack() },
                    onConfirm = {
                        if (session.image != null && session.motion != null) {
                            nav.navigate(mode.routeOf("processing"))
                        }
                    },
                )
            }

            modeRoute("processing") { mode ->
                LaunchedEffect(Unit) {
                    val image = session.image
                    val motion = session.motion
                    if (image == null || motion == null) {
                        nav.popBackStack("list", inclusive = false)
                        return@LaunchedEffect
                    }
                    val result = withContext(Dispatchers.Default) {
                        rigger.rig(image, motion)
                    }
                    val prefix = result.framesDir.substringAfter("stickers/").substringBefore("_")
                    val displayName = "${prefix}_${System.currentTimeMillis() % 100000}"
                    val entry = StickerEntry(
                        id = result.framesDir.substringAfter("stickers/").substringBefore("/"),
                        name = displayName,
                        motion = motion,
                        durationMs = (1000 * result.frameCount / result.fps),
                        fps = result.fps,
                        frameCount = result.frameCount,
                        width = result.width,
                        height = result.height,
                        framesDir = result.framesDir,
                        gifPath = result.gifPath,
                        texturePath = result.texturePath,
                        sourcePath = result.sourcePath,
                        skeletonPath = result.skeletonPath,
                    )
                    AssetRepository(ctx).saveSticker(entry)
                    session.reset()
                    manifestVersion++
                    when (mode) {
                        NavMode.NORMAL -> nav.navigate("list") {
                            popUpTo("list") { inclusive = true }
                        }
                        NavMode.AR_AUTO -> nav.navigate("arview/${entry.id}") {
                            popUpTo("list")
                        }
                    }
                    android.widget.Toast.makeText(
                        ctx, "새 스티커 만들어짐", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                ProcessingScreen()
            }

            composable(
                route = "arview/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { backStack ->
                val id = backStack.arguments?.getString("id") ?: return@composable
                ArViewScreen(
                    manifest = m,
                    stickerId = id,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
