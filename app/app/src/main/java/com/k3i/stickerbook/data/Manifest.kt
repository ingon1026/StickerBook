package com.k3i.stickerbook.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SUPPORTED_FORMAT_VERSION = 1

@Serializable
data class StickerEntry(
    val id: String,
    val name: String,
    val motion: String,
    @SerialName("duration_ms") val durationMs: Int,
    val fps: Int,
    @SerialName("frame_count") val frameCount: Int,
    val width: Int,
    val height: Int,
    @SerialName("frames_dir") val framesDir: String,
    @SerialName("gif_path") val gifPath: String,
    @SerialName("texture_path") val texturePath: String,
    @SerialName("source_path") val sourcePath: String,
    @SerialName("skeleton_path") val skeletonPath: String? = null,
)

@Serializable
data class Manifest(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val stickers: List<StickerEntry> = emptyList(),
)

object ManifestParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Manifest {
        val m = json.decodeFromString(Manifest.serializer(), text)
        check(m.formatVersion == SUPPORTED_FORMAT_VERSION) {
            "unsupported format_version=${m.formatVersion}, expected $SUPPORTED_FORMAT_VERSION"
        }
        return m
    }
}
