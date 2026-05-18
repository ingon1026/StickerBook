package com.k3i.stickerbook.ui.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.Composable

enum class NavMode { NORMAL, AR_AUTO;

    fun routeOf(base: String): String = "$base?mode=$name"

    companion object {
        fun parse(raw: String?): NavMode = when (raw) {
            AR_AUTO.name -> AR_AUTO
            else -> NORMAL
        }
    }
}

fun NavGraphBuilder.modeRoute(base: String, content: @Composable (NavMode) -> Unit) {
    composable(
        route = "$base?mode={mode}",
        arguments = listOf(navArgument("mode") {
            type = NavType.StringType
            defaultValue = NavMode.NORMAL.name
        }),
    ) { backStack ->
        content(NavMode.parse(backStack.arguments?.getString("mode")))
    }
}
