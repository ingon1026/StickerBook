package com.k3i.stickerbook.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.AssetHandle
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.StickerEntry

@Composable
fun StickerCard(entry: StickerEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = remember { AssetRepository(ctx) }
    var bmp by remember(entry.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.id) {
        val handle = repo.resolve(entry.sourcePath)
        bmp = when (handle) {
            is AssetHandle.Bundled ->
                ctx.assets.open(handle.assetPath).use { BitmapFactory.decodeStream(it) }
            is AssetHandle.InternalFile ->
                BitmapFactory.decodeFile(handle.file.absolutePath)
        }
    }

    Card(
        modifier = modifier.clickable { onClick() }.padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            val image = bmp
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = entry.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .background(Color(0xFFEFEFEF)),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .background(Color(0xFFEFEFEF)),
                )
            }
            Text(text = entry.name, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
