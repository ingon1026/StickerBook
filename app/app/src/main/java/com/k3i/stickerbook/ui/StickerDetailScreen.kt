package com.k3i.stickerbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.StickerEntry
import com.k3i.stickerbook.ui.components.AnimationPlayer
import com.k3i.stickerbook.ui.components.MotionSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerDetailScreen(
    entry: StickerEntry,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    onAR: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Player takes the remaining vertical space (weight = 1f), not a fixed
            // aspect ratio — keeps MotionSelector and Save button on screen in landscape.
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AnimationPlayer(
                    framesDir = entry.framesDir,
                    frameCount = entry.frameCount,
                    fps = entry.fps,
                )
            }
            MotionSelector(motionLabel = entry.motion, onPrev = onPrev, onNext = onNext)
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("갤러리에 저장")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onAR, modifier = Modifier.fillMaxWidth()) {
                Text("AR 로 보기")
            }
        }
    }
}
