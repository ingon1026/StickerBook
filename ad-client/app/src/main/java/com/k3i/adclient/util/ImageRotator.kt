package com.k3i.adclient.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * 갤러리 Uri → EXIF orientation 적용된 JPEG bytes.
 *
 * 왜 필요한가:
 *   폰 카메라가 가로로 찍은 사진은 JPEG 의 raw pixel 이 가로 그대로 저장되고,
 *   EXIF Orientation 태그 (예: 6 = "90° 시계 회전") 로 "표시할 때 회전" 표시.
 *   Android ImageView 는 EXIF 자동 적용해서 세로로 보여주지만,
 *   서버 측 OpenCV (cv2.imread) 는 EXIF 무시 → raw pixel 그대로 → 누운 그림으로 detection.
 *
 * 이 helper 는 InputStream raw bytes 대신 *EXIF 적용된 회전 Bitmap → 새 JPEG* 으로 보냄.
 * 서버는 EXIF 없는 정상 방향 JPEG 받음 → detection 도 갤탭 미리보기와 같은 방향.
 */
object ImageRotator {

    /** Uri 의 이미지를 EXIF 따라 회전 후 JPEG bytes 로 반환. */
    fun readAndRotate(resolver: ContentResolver, uri: Uri): ByteArray {
        val orientation = readOrientation(resolver, uri)
        val original = decodeBitmap(resolver, uri)
            ?: return ByteArray(0)
        val rotated = applyOrientation(original, orientation)
        return toJpegBytes(rotated)
    }

    private fun readOrientation(resolver: ContentResolver, uri: Uri): Int {
        return resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }

    private fun decodeBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return src   // ORIENTATION_NORMAL 또는 UNDEFINED
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun toJpegBytes(bmp: Bitmap, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}