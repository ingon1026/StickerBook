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

class LandmarkBackendTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mediapipe-33 backend round-trips`() {
        val data = SkeletonData(
            backend = PoseBackend.MEDIAPIPE_33,
            landmarks = listOf(Landmark(1f, 2f, 3f, 0.9f, 0.9f)),
            imageWidth = 100,
            imageHeight = 200,
        )
        val text = json.encodeToString(SkeletonData.serializer(), data)
        val parsed = json.decodeFromString(SkeletonData.serializer(), text)
        assertEquals(PoseBackend.MEDIAPIPE_33, parsed.backend)
        assertEquals(1, parsed.landmarks.size)
    }

    @Test
    fun `ad-coco-17 backend round-trips`() {
        val data = SkeletonData(
            backend = PoseBackend.AD_COCO_17,
            landmarks = List(17) { Landmark(it.toFloat(), 0f, 0f, 0.5f, 0.5f) },
            imageWidth = 1920,
            imageHeight = 1080,
        )
        val text = json.encodeToString(SkeletonData.serializer(), data)
        val parsed = json.decodeFromString(SkeletonData.serializer(), text)
        assertEquals(PoseBackend.AD_COCO_17, parsed.backend)
        assertEquals(17, parsed.landmarks.size)
    }

    @Test
    fun `legacy json without backend field parses as MEDIAPIPE_33`() {
        val legacy = """
            {"landmarks": [{"x":0,"y":0,"z":0,"visibility":1,"presence":1}],
             "image_width": 100, "image_height": 200}
        """.trimIndent()
        val parsed = json.decodeFromString(SkeletonData.serializer(), legacy)
        assertEquals(PoseBackend.MEDIAPIPE_33, parsed.backend)
    }
}
