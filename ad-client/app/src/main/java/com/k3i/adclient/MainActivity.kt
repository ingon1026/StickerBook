package com.k3i.adclient

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var gifResult: ImageView
    private lateinit var spinnerMotion: Spinner
    private lateinit var statusText: TextView
    private var pickedUri: Uri? = null

    // M1~M4: motion 목록 하드코딩. M5(옵션) 에서 GET /motions 호출로 전환.
    // shared/API.md 의 motion id 와 일치해야 함.
    private val motions = listOf("dab", "wave_hello", "jumping")

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            imagePreview.setImageURI(uri)
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

        findViewById<Button>(R.id.btnCombine).setOnClickListener {
            onCombine()
        }
    }

    private fun onCombine() {
        val uri = pickedUri
        if (uri == null) {
            statusText.text = getString(R.string.pick_first)
            return
        }
        val motion = spinnerMotion.selectedItem as String

        lifecycleScope.launch {
            statusText.text = getString(R.string.status_loading)

            // 갤러리 Uri → ByteArray (백그라운드 스레드에서)
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: ByteArray(0)
            }

            if (bytes.isEmpty()) {
                statusText.text = "이미지 읽기 실패"
                return@launch
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
