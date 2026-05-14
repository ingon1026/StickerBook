package com.k3i.stickerbook.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

class AnimationSaver(private val context: Context) {

    /** Saves the entry's animation.gif into Pictures/Stickerbook/. Returns content URI string or null. */
    fun saveGif(entry: StickerEntry): String? {
        val repo = AssetRepository(context)
        val handle = repo.resolve(entry.gifPath)
        val input: InputStream = when (handle) {
            is AssetHandle.Bundled -> context.assets.open(handle.assetPath)
            is AssetHandle.InternalFile -> File(handle.file.absolutePath).inputStream()
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${entry.id}_${entry.motion}.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Stickerbook")
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return runCatching {
            resolver.openOutputStream(uri).use { out ->
                input.use { it.copyTo(out!!) }
            }
            uri.toString()
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }
}
