package com.k3i.adclient.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ad-server 의 HTTP 클라이언트 wrapper.
 *
 * UI 와 분리 — UI 는 process(bytes, motion) 만 부르면 됨.
 * OkHttp / multipart / 코루틴 디테일은 여기 한 곳에 격리.
 */
object AdApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /**
     * 호출 결과 — sealed class 라 when() 분기가 exhaustive.
     *
     *  - Ok(gifBytes)    : 200, body 가 GIF 바이트
     *  - Err(code, msg)  : 비-200 또는 네트워크 오류
     */
    sealed class Result {
        data class Ok(val gifBytes: ByteArray) : Result()
        data class Err(val code: Int, val message: String) : Result()
    }

    /** GET /health — 서버 살아있는지 확인. */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${Config.BASE_URL}/health").build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * POST /process — image + motion → GIF bytes.
     *
     * @param imageBytes 그림 파일 raw bytes
     * @param imageFilename 서버 로그/디버깅용 (실제 의미는 X)
     * @param motionId 서버에 등록된 motion 식별자
     */
    suspend fun process(
        imageBytes: ByteArray,
        imageFilename: String,
        motionId: String,
    ): Result = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "image",
                filename = imageFilename,
                body = imageBytes.toRequestBody("image/*".toMediaType()),
            )
            .addFormDataPart("motion", motionId)
            .build()

        val req = Request.Builder()
            .url("${Config.BASE_URL}/process")
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    Result.Ok(bytes)
                } else {
                    Result.Err(resp.code, resp.body?.string() ?: "")
                }
            }
        } catch (e: IOException) {
            Result.Err(-1, e.message ?: "network error")
        }
    }
}
