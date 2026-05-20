package com.k3i.adclient.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 갤러리 Uri → 사용자가 지정한 회전 각도 적용된 JPEG bytes.
 *
 * 정책: EXIF orientation 무시. 사용자가 버튼으로 90° 단위 회전 결정.
 *   서버 측 cv2.imread 가 어차피 EXIF 무시 → 사용자가 화면에 보이는 방향이 곧
 *   서버 입력 방향. 일관성 확보.
 */
object ImageRotator {

    /** Uri → Bitmap (회전 적용 X). 미리보기에 사용. */
    fun decode(resolver: ContentResolver, uri: Uri): Bitmap? =
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    /** Bitmap 을 주어진 각도 (0/90/180/270) 로 회전. */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return src
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** Bitmap → JPEG bytes (서버 송신용). */
    fun toJpegBytes(bmp: Bitmap, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}