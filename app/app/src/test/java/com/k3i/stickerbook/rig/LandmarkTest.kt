package com.k3i.stickerbook.rig

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LandmarkTest {

    @Test
    fun landmark_serializes_round_trip() {
        val original = Landmark(x = 100.5f, y = 200.3f, z = -0.5f, visibility = 0.95f, presence = 0.99f)
        val json = Json.encodeToString(Landmark.serializer(), original)
        val parsed = Json.decodeFromString(Landmark.serializer(), json)
        assertEquals(original.x, parsed.x, 0.001f)
        assertEquals(original.y, parsed.y, 0.001f)
        assertEquals(original.visibility, parsed.visibility, 0.001f)
    }

    @Test
    fun skeleton_data_holds_33_landmarks() {
        val landmarks = List(33) { i ->
            Landmark(x = i.toFloat(), y = i * 2f, z = 0f, visibility = 1f, presence = 1f)
        }
        val data = SkeletonData(landmarks = landmarks, imageWidth = 1280, imageHeight = 720)
        val json = Json.encodeToString(SkeletonData.serializer(), data)
        val parsed = Json.decodeFromString(SkeletonData.serializer(), json)
        assertEquals(33, parsed.landmarks.size)
        assertEquals(1280, parsed.imageWidth)
        assertEquals(720, parsed.imageHeight)
        assertEquals(5f, parsed.landmarks[5].x, 0.001f)
    }
}
