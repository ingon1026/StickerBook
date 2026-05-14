package com.k3i.stickerbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.LocalCaptureSession
import com.k3i.stickerbook.data.MotionCatalog
import com.k3i.stickerbook.data.MotionEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionPickerScreen(
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val session = LocalCaptureSession.current
    var selected by remember { mutableStateOf<MotionEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("모션 선택") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(MotionCatalog.all, key = { it.id }) { m ->
                    MotionCard(
                        entry = m,
                        isSelected = selected?.id == m.id,
                        onClick = {
                            selected = m
                            session.motion = m.id
                        },
                    )
                }
            }
            Button(
                onClick = onConfirm,
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text("만들기 ▶")
            }
        }
    }
}

@Composable
private fun MotionCard(entry: MotionEntry, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 3.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(entry.displayName)
    }
}
