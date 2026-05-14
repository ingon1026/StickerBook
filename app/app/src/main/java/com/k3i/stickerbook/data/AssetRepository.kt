package com.k3i.stickerbook.data

import android.content.Context
import android.util.Log
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
        val manifestFile = File(context.filesDir, "stickerbook_assets/manifest.json")
        manifestFile.parentFile?.mkdirs()

        val existing = loadManifest() ?: Manifest(
            formatVersion = SUPPORTED_FORMAT_VERSION,
            generatedAt = "",
            stickers = emptyList(),
        )
        val merged = existing.copy(
            stickers = existing.stickers.filter { it.id != entry.id } + entry,
        )

        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }
        manifestFile.writeText(json.encodeToString(Manifest.serializer(), merged))
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
    }
}

sealed interface AssetHandle {
    data class Bundled(val assetPath: String) : AssetHandle
    data class InternalFile(val file: File) : AssetHandle
}
