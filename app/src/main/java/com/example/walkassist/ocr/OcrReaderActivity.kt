package com.example.walkassist.ocr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.example.walkassist.R
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OcrReaderActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var textResult: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewModel: OcrViewModel
    private lateinit var ttsManager: OcrTtsManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            announce("앱을 사용하려면 카메라 권한을 허용해 주세요.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr_reader)

        previewView = findViewById(R.id.ocrPreview)
        textResult = findViewById(R.id.ocrTextResult)
        cameraExecutor = Executors.newSingleThreadExecutor()
        viewModel = ViewModelProvider(this)[OcrViewModel::class.java]
        ttsManager = OcrTtsManager(this) {
            announce("문자 인식을 시작합니다.")
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            announce("문자 인식을 위해 카메라 권한이 필요합니다.")
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            cameraExecutor,
                            OcrTextAnalyzer(
                                onTextFound = ::handleRecognizedText,
                                onError = ::handleOcrError,
                            ),
                        )
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalyzer,
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Camera binding failed", error)
                    announce("카메라를 시작할 수 없습니다.")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun handleRecognizedText(text: String) {
        lifecycleScope.launch {
            val speechText = viewModel.onRecognizedText(text)
            val recognizedText = viewModel.uiState.value.recognizedText
            if (recognizedText.isNotBlank()) {
                textResult.text = recognizedText
            }
            if (speechText != null) {
                ttsManager.speak(speechText)
            }
        }
    }

    private fun handleOcrError(error: Throwable) {
        Log.e(TAG, "Text recognition failed", error)
    }

    private fun announce(message: String) {
        textResult.text = message
        textResult.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        ttsManager.speak(message)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ttsManager.shutdown()
    }

    companion object {
        private const val TAG = "OcrReaderActivity"
    }
}
