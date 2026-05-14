package com.k3i.stickerbook.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.data.StickerEntry
import com.k3i.stickerbook.ui.components.StickerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerListScreen(
    manifest: Manifest,
    onStickerClick: (StickerEntry) -> Unit,
    onCaptureClick: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("스티커북") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCaptureClick) {
                Icon(Icons.Default.Add, contentDescription = "새 스티커")
            }
        },
    ) { inner ->
        if (manifest.stickers.isEmpty()) {
            Text(
                "자산이 없습니다.\n+ 버튼으로 새 스티커를 만들어보세요.",
                modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize().padding(inner).padding(8.dp),
            ) {
                items(manifest.stickers, key = { it.id }) { entry ->
                    StickerCard(entry = entry, onClick = { onStickerClick(entry) })
                }
            }
        }
    }
}
