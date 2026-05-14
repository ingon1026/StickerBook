package com.k3i.stickerbook.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AssetRepositoryTest {

    @Ignore("Bundled APK assets/stickerbook_assets/manifest.json defeats the empty-state assumption from Phase 1; superseded by saveSticker_appends_entry test below")
    @Test
    fun returns_null_when_no_manifest_anywhere() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AssetRepository(ctx)
        assertNull(repo.loadManifest())
    }

    @Test
    fun loads_manifest_from_internal_storage_when_present() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(ctx.filesDir, "stickerbook_assets")
        root.mkdirs()
        File(root, "manifest.json").writeText(
            """{"format_version":1,"generated_at":"x","stickers":[]}"""
        )
        val repo = AssetRepository(ctx)
        val m = repo.loadManifest()!!
        assertEquals(1, m.formatVersion)
        assertEquals(0, m.stickers.size)
    }

    @Test
    fun saveSticker_appends_entry_to_internal_manifest() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AssetRepository(ctx)

        val entry = StickerEntry(
            id = "s_test",
            name = "테스트",
            motion = "dance_1",
            durationMs = 0,
            fps = 30,
            frameCount = 1,
            width = 64,
            height = 64,
            framesDir = "stickers/s_test/frames",
            gifPath = "stickers/s_test/animation.gif",
            texturePath = "stickers/s_test/texture.png",
            sourcePath = "stickers/s_test/source.png",
        )
        repo.saveSticker(entry)

        val m = repo.loadManifest()!!
        assertEquals(1, m.formatVersion)
        assertTrue(m.stickers.any { it.id == "s_test" })
    }
}
