package com.k3i.stickerbook.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.data.StickerEntry
import com.k3i.stickerbook.ui.components.StickerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerListScreen(onStickerClick: (StickerEntry) -> Unit) {
    val ctx = LocalContext.current
    val manifest by produceState<Manifest?>(initialValue = null) {
        value = AssetRepository(ctx).loadManifest()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("스티커북") }) }
    ) { inner ->
        val m = manifest
        if (m == null) {
            Text(
                "자산이 없습니다.\nADB push 또는 APK assets/stickerbook_assets/ 로 넣어주세요.",
                modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize().padding(inner).padding(8.dp),
            ) {
                items(m.stickers, key = { it.id }) { entry ->
                    StickerCard(entry = entry, onClick = { onStickerClick(entry) })
                }
            }
        }
    }
}
