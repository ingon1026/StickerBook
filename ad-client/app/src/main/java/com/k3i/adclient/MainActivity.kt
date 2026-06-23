package com.k3i.adclient

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.k3i.adclient.net.AdApi
import com.k3i.adclient.util.ImageRotator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var gifResult: ImageView
    private lateinit var spinnerMotion: Spinner
    private lateinit var statusText: TextView
    private lateinit var btnRotate: Button
    private lateinit var btnSave: Button
    private lateinit var progressBar: ProgressBar

    companion object {
        // 평균 30s 기준 estimated progress. 서버가 진짜 % 안 줘서 추정치.
        // 실제 평균이 다르면 이 값만 조정.
        private const val ESTIMATED_SEC = 30L
        private const val TAG = "AdClient"
    }

    private var pickedUri: Uri? = null
    private var pickedFilename: String = "unknown"
    private var lastGifBytes: ByteArray? = null
    private var originalBitmap: Bitmap? = null
    private var rotationDeg: Int = 0   // 0 / 90 / 180 / 270

    // server motion_registry.py 의 _REGISTRY 와 동기화.
    // Custom dance 3 + Mixamo 14 (prefix 없이) = 17.
    // untitled 는 회사 서버 registry 에 미등록(400 unknown motion)이라 제외.
    private val motions = listOf(
        // Custom dance — User Rokoko (3)
        "custom_dance_1", "custom_dance_2", "custom_dance_3",
        // Mixamo (14) — prefix 없는 깔끔한 id
        "salsa", "gangnam", "tut_hiphop", "booty_hiphop",
        "jumping_mixamo", "arms_hiphop", "jazz_2", "waving_mixamo",
        "arms_hiphop_v2", "hiphop", "golf", "kick", "jazz", "jump_side",
    )

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            pickedFilename = queryDisplayName(uri) ?: uri.lastPathSegment ?: "unknown"
            rotationDeg = 0
            originalBitmap = ImageRotator.decode(contentResolver, uri)
            refreshPreview()
            // 이미지 선택 후에만 회전 버튼 노출 (선택 전엔 의미 X)
            btnRotate.visibility = View.VISIBLE
            statusText.text = getString(R.string.status_idle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imagePreview = findViewById(R.id.imagePreview)
        gifResult = findViewById(R.id.gifResult)
        spinnerMotion = findViewById(R.id.spinnerMotion)
        statusText = findViewById(R.id.statusText)
        btnRotate = findViewById(R.id.btnRotate)
        btnSave = findViewById(R.id.btnSave)
        progressBar = findViewById(R.id.progressBar)

        spinnerMotion.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            motions,
        )

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImage.launch("image/*")
        }
        btnRotate.setOnClickListener {
            // 90° 시계방향 회전. 4번 누르면 원래 자리.
            rotationDeg = (rotationDeg + 90) % 360
            refreshPreview()
        }
        findViewById<Button>(R.id.btnCombine).setOnClickListener {
            onCombine()
        }
        btnSave.setOnClickListener { saveGif() }
    }

    /** 갤러리 picker uri 에서 사람이 읽을 수 있는 파일명 추출. testbed logging 용. */
    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cur.getString(idx)
            }
        }
        return null
    }

    /** 현재 originalBitmap + rotationDeg 로 미리보기 갱신. */
    private fun refreshPreview() {
        val src = originalBitmap ?: return
        val rotated = ImageRotator.rotate(src, rotationDeg)
        imagePreview.setImageBitmap(rotated)
    }

    /** 마지막 합성 GIF 를 갤러리(Pictures/AdClient)에 저장. MediaStore (Android 10+). */
    private fun saveGif() {
        val bytes = lastGifBytes ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusText.text = "저장은 Android 10 이상에서만 지원돼요"
            return
        }
        val name = "adclient_${System.currentTimeMillis()}.gif"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AdClient")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            statusText.text = "저장 실패 — 다시 시도해주세요"
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: run { statusText.text = "저장 실패 — 출력 스트림 없음"; return }
            statusText.text = "저장됨 · Pictures/AdClient/$name"
        } catch (e: Exception) {
            statusText.text = "저장 실패 — ${e.message}"
        }
    }

    private fun onCombine() {
        val src = originalBitmap
        if (src == null) {
            statusText.text = getString(R.string.pick_first)
            return
        }
        val motion = spinnerMotion.selectedItem as String

        lifecycleScope.launch {
            statusText.text = getString(R.string.status_loading)
            progressBar.progress = 0
            btnSave.visibility = View.GONE
            lastGifBytes = null
            val startMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "START · image=$pickedFilename motion=$motion")

            // 1초 tick — 경과 시간 + ESTIMATED_SEC 기반 추정 % 를 UI/로그에 갱신.
            // 서버가 진짜 progress 안 줘서 추정치. cancel 되면 자동 종료.
            val tick = launch {
                while (isActive) {
                    val elapsedSec = (SystemClock.elapsedRealtime() - startMs) / 1000.0
                    val percent = (elapsedSec * 100 / ESTIMATED_SEC).toInt().coerceAtMost(99)
                    statusText.text =
                        "처리 중 · %.1fs / ~%ds (~%d%%)".format(elapsedSec, ESTIMATED_SEC, percent)
                    progressBar.progress = percent
                    Log.d(TAG, "tick · elapsed=%.1fs percent=%d%%".format(elapsedSec, percent))
                    delay(1000)
                }
            }

            // 화면에 보이는 그대로 → JPEG bytes (서버 cv2.imread 와 일관)
            val bytes = withContext(Dispatchers.IO) {
                val rotated = ImageRotator.rotate(src, rotationDeg)
                ImageRotator.toJpegBytes(rotated)
            }

            val result = AdApi.process(bytes, "drawing.jpg", motion)
            tick.cancel()
            val totalSec = (SystemClock.elapsedRealtime() - startMs) / 1000.0

            when (result) {
                is AdApi.Result.Ok -> {
                    val kb = result.gifBytes.size / 1024
                    progressBar.progress = 100
                    statusText.text = "완료 · %.1fs · %d KB".format(totalSec, kb)
                    lastGifBytes = result.gifBytes
                    btnSave.visibility = View.VISIBLE
                    Log.d(
                        TAG,
                        "DONE · image=%s motion=%s time=%.1fs size=%dKB code=200".format(
                            pickedFilename, motion, totalSec, kb,
                        ),
                    )
                    Glide.with(this@MainActivity)
                        .asGif()
                        .load(result.gifBytes)
                        .into(gifResult)
                }
                is AdApi.Result.Err -> {
                    progressBar.progress = 0
                    statusText.text = when (result.code) {
                        401 -> "인증 실패 (401) — API 키가 잘못됐어요. local.properties 확인."
                        422 -> "그림에서 캐릭터를 못 찾았어요 — 사람 형체가 더 명확한 그림으로 시도."
                        500, 503 -> "서버 에러 (${result.code}) — 잠시 후 다시."
                        -1 -> "네트워크 오류 — 사내 wifi 연결 확인."
                        else -> "에러 ${result.code}: ${result.message}"
                    }
                    Log.e(
                        TAG,
                        "ERR · image=%s motion=%s time=%.1fs code=%d msg=%s".format(
                            pickedFilename, motion, totalSec, result.code, result.message,
                        ),
                    )
                }
            }
        }
    }
}