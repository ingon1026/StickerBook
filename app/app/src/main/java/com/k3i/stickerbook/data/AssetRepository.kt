package com.k3i.stickerbook.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

class AssetRepository(private val context: Context) {

    fun loadManifest(): Manifest? {
        val internal = File(context.filesDir, "stickerbook_assets/manifest.json")
        if (internal.isFile) {
            return runCatching { ManifestParser.parse(internal.readText()) }
                .onFailure { Log.w(TAG, "internal manifest parse failed", it) }
                .getOrNull()
        }
        return try {
            val text = context.assets.open("stickerbook_assets/manifest.json")
                .bufferedReader().use { it.readText() }
            ManifestParser.parse(text)
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "bundled manifest unsupported", e)
            null
        }
    }

    fun saveSticker(entry: StickerEntry) {
        val existing = loadManifest() ?: Manifest(
            formatVersion = SUPPORTED_FORMAT_VERSION,
            generatedAt = "",
            stickers = emptyList(),
        )
        writeManifest(existing.copy(
            stickers = existing.stickers.filter { it.id != entry.id } + entry,
        ))
    }

    fun deleteSticker(id: String) {
        val existing = loadManifest() ?: return
        writeManifest(existing.copy(stickers = existing.stickers.filter { it.id != id }))
        File(context.filesDir, "stickerbook_assets/stickers/$id").deleteRecursively()
    }

    private fun writeManifest(manifest: Manifest) {
        val file = File(context.filesDir, "stickerbook_assets/manifest.json")
        file.parentFile?.mkdirs()
        file.writeText(WRITE_JSON.encodeToString(Manifest.serializer(), manifest))
    }

    /** Resolves a relative path in the manifest to a usable handle. */
    fun resolve(relativePath: String): AssetHandle {
        val internalFile = File(context.filesDir, "stickerbook_assets/$relativePath")
        return if (internalFile.isFile) {
            AssetHandle.InternalFile(internalFile)
        } else {
            AssetHandle.Bundled("stickerbook_assets/$relativePath")
        }
    }

    companion object {
        private const val TAG = "AssetRepository"
        private val WRITE_JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

sealed interface AssetHandle {
    data class Bundled(val assetPath: String) : AssetHandle
    data class InternalFile(val file: File) : AssetHandle
}
