package com.k3i.adclient

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.k3i.adclient.net.AdApi
import com.k3i.adclient.util.ImageRotator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var gifResult: ImageView
    private lateinit var spinnerMotion: Spinner
    private lateinit var statusText: TextView

    private var pickedUri: Uri? = null
    private var originalBitmap: Bitmap? = null
    private var rotationDeg: Int = 0   // 0 / 90 / 180 / 270

    // shared/API.md 의 motion 목록과 일치해야 함.
    // server motion_registry.py 의 _REGISTRY 와 동기화.
    private val motions = listOf(
        "dab", "wave_hello", "jumping", "jumping_jacks", "zombie",
        "dance_1", "dance_2", "dance_3",
        "my_dance", "my_dance_2", "my_dance_3",
    )

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            rotationDeg = 0
            originalBitmap = ImageRotator.decode(contentResolver, uri)
            refreshPreview()
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

        spinnerMotion.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            motions,
        )

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImage.launch("image/*")
        }
        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            // 90° 시계방향 회전. 4번 누르면 원래 자리.
            rotationDeg = (rotationDeg + 90) % 360
            refreshPreview()
        }
        findViewById<Button>(R.id.btnCombine).setOnClickListener {
            onCombine()
        }
    }

    /** 현재 originalBitmap + rotationDeg 로 미리보기 갱신. */
    private fun refreshPreview() {
        val src = originalBitmap ?: return
        val rotated = ImageRotator.rotate(src, rotationDeg)
        imagePreview.setImageBitmap(rotated)
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

            // 화면에 보이는 그대로 → JPEG bytes (서버 cv2.imread 와 일관)
            val bytes = withContext(Dispatchers.IO) {
                val rotated = ImageRotator.rotate(src, rotationDeg)
                ImageRotator.toJpegBytes(rotated)
            }

            when (val result = AdApi.process(bytes, "drawing.jpg", motion)) {
                is AdApi.Result.Ok -> {
                    statusText.text = "완료 (${result.gifBytes.size} B)"
                    Glide.with(this@MainActivity)
                        .asGif()
                        .load(result.gifBytes)
                        .into(gifResult)
                }
                is AdApi.Result.Err -> {
                    statusText.text = "에러 ${result.code}: ${result.message}"
                }
            }
        }
    }
}