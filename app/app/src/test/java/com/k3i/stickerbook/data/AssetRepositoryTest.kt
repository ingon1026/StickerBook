package com.k3i.stickerbook.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AssetRepositoryTest {

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
}
