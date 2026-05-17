package com.example.walkassist

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GuideSettingsActivity : AppCompatActivity() {
    private val preferences by lazy {
        WalkAssistSettings.preferences(this)
    }
    private var replayPickerDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "설정"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(0xFF101820.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val titleText = TextView(this).apply {
            text = "WalkAssist 설정"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        val descriptionText = TextView(this).apply {
            text = "긴급 연락처와 ARCore 리플레이 녹화 파일을 관리합니다."
            textSize = 16f
            setTextColor(0xFFD8E3EE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 34)
        }

        val emergencyButton = Button(this).apply {
            text = "긴급 연락처 설정"
            textSize = 20f
            minHeight = 72
            setOnClickListener { showEmergencyContactDialog() }
        }

        val vlmModelButton = Button(this).apply {
            text = geminiModelButtonText()
            textSize = 20f
            minHeight = 72
            setOnClickListener { showGeminiConnectionDialog() }
        }

        val arcoreTtsButton = Button(this).apply {
            text = arcoreTtsButtonText()
            textSize = 20f
            minHeight = 72
            setOnClickListener {
                val nextEnabled = !WalkAssistSettings.isArcoreTtsEnabled(this@GuideSettingsActivity)
                WalkAssistSettings.setArcoreTtsEnabled(this@GuideSettingsActivity, nextEnabled)
                text = arcoreTtsButtonText()
                Toast.makeText(
                    this@GuideSettingsActivity,
                    if (nextEnabled) "ARCore TTS enabled" else "ARCore TTS disabled",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        val arcoreRecordButton = Button(this).apply {
            text = "ARCore 리플레이 녹화"
            textSize = 20f
            minHeight = 72
            setOnClickListener {
                startArCoreRecord()
            }
        }

        val arcoreStorageText = TextView(this).apply {
            text = arcoreStorageDescription()
            textSize = 14f
            setTextColor(0xFFB9C7D5.toInt())
            setPadding(0, 14, 0, 0)
        }

        val arcorePlaybackButton = Button(this).apply {
            text = "마지막 ARCore 리플레이 재생"
            textSize = 20f
            minHeight = 72
            setOnClickListener {
                val lastUri = ArCoreReplayController.lastDatasetUri(this@GuideSettingsActivity)
                if (lastUri == null) {
                    Toast.makeText(
                        this@GuideSettingsActivity,
                        "저장된 ARCore 데이터셋이 없습니다. 먼저 녹화해 주세요.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    startArCorePlayback(lastUri)
                }
            }
        }

        val arcoreSavedPlaybackButton = Button(this).apply {
            text = "저장된 ARCore 리플레이 선택"
            textSize = 20f
            minHeight = 72
            setOnClickListener {
                showRecordedDatasetPicker()
            }
        }

        val closeButton = Button(this).apply {
            text = "닫기"
            textSize = 18f
            minHeight = 64
            setOnClickListener { finish() }
        }

        root.addView(titleText, fullWidthParams())
        root.addView(descriptionText, fullWidthParams())
        root.addView(emergencyButton, fullWidthParams())
        root.addView(vlmModelButton, fullWidthParams(topMargin = 18))
        root.addView(arcoreTtsButton, fullWidthParams(topMargin = 18))
        root.addView(arcoreRecordButton, fullWidthParams(topMargin = 18))
        root.addView(arcoreStorageText, fullWidthParams())
        root.addView(arcorePlaybackButton, fullWidthParams(topMargin = 18))
        root.addView(arcoreSavedPlaybackButton, fullWidthParams(topMargin = 18))
        root.addView(closeButton, fullWidthParams(topMargin = 18))
        setContentView(root)
    }

    private fun startArCorePlayback(uri: Uri) {
        ArCoreReplayController.saveLastDataset(this, uri)
        startActivity(
            arCoreReplayIntent()
                .putExtra(ArCoreReplayController.EXTRA_PLAYBACK_DATASET_URI, uri.toString()),
        )
    }

    private fun startArCoreRecord() {
        startActivity(
            arCoreReplayIntent()
                .putExtra(ArCoreReplayController.EXTRA_RECORD_ON_START, true),
        )
    }

    private fun arCoreReplayIntent(): Intent {
        return Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    }

    private fun arcoreStorageDescription(): String {
        val directory = ArCoreReplayController.recordingDirectory(this)
        val count = ArCoreReplayController.recordedDatasets(this).size
        return "녹화 파일 저장 위치: ${directory.absolutePath}\n저장된 ARCore 리플레이: ${count}개"
    }

    private fun showRecordedDatasetPicker() {
        val datasets = ArCoreReplayController.recordedDatasets(this)
        if (datasets.isEmpty()) {
            Toast.makeText(
                this,
                "저장된 ARCore 리플레이가 없습니다. 먼저 녹화해 주세요.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        datasets.forEach { dataset ->
            list.addView(createReplayDatasetRow(dataset))
        }

        val scrollView = ScrollView(this).apply {
            addView(list)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420),
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("저장된 ARCore 리플레이")
            .setView(scrollView)
            .setNegativeButton("취소", null)
            .create()
        replayPickerDialog = dialog
        dialog.setOnDismissListener {
            if (replayPickerDialog === dialog) {
                replayPickerDialog = null
            }
        }
        dialog.show()
    }

    private fun createReplayDatasetRow(dataset: ArCoreReplayDataset): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener {
                replayPickerDialog?.dismiss()
                startArCorePlayback(dataset.uri)
            }
        }

        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF24313D.toInt())
            createReplayThumbnail(dataset)?.let { setImageBitmap(it) }
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(64))
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = dataset.fileName
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            maxLines = 2
        }

        val details = TextView(this).apply {
            text = "${formatDateTime(dataset.lastModifiedMillis)} · ${formatSize(dataset.sizeBytes)}"
            textSize = 13f
            setTextColor(0xFF5C6670.toInt())
            setPadding(0, dp(4), 0, 0)
        }

        val deleteButton = Button(this).apply {
            text = "삭제"
            minWidth = dp(72)
            minHeight = dp(44)
            setOnClickListener {
                showDeleteReplayDialog(dataset)
            }
        }

        textColumn.addView(title)
        textColumn.addView(details)
        row.addView(thumbnail)
        row.addView(textColumn)
        row.addView(deleteButton)
        return row
    }

    private fun showDeleteReplayDialog(dataset: ArCoreReplayDataset) {
        AlertDialog.Builder(this)
            .setTitle("리플레이 삭제")
            .setMessage("${dataset.fileName} 파일을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                replayPickerDialog?.dismiss()
                val deleted = ArCoreReplayController.deleteRecordedDataset(this, dataset)
                Toast.makeText(
                    this,
                    if (deleted) "ARCore 리플레이를 삭제했습니다." else "ARCore 리플레이를 삭제하지 못했습니다.",
                    Toast.LENGTH_SHORT,
                ).show()
                showRecordedDatasetPicker()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun createReplayThumbnail(dataset: ArCoreReplayDataset): Bitmap? {
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(dataset.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }.getOrNull()
    }

    private fun formatDateTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(timeMillis))
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.KOREA, "%.1f MB", mb)
    }

    private fun geminiModelButtonText(): String {
        return if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            "Gemini VLM: API 키 필요"
        } else {
            "Gemini VLM: 연결 준비됨"
        }
    }

    private fun showGeminiConnectionDialog() {
        val configured = BuildConfig.GEMINI_API_KEY.isNotBlank()
        AlertDialog.Builder(this)
            .setTitle("Gemini VLM 연결 상태")
            .setMessage(
                if (configured) {
                    "API 키가 설정되어 있습니다. VLM 버튼을 누르면 현재 카메라 이미지 1장을 Gemini API로 보내 장면 묘사를 요청합니다."
                } else {
                    "API 키가 아직 설정되지 않았습니다. local.properties에 GEMINI_API_KEY를 추가한 뒤 앱을 다시 빌드하세요."
                },
            )
            .setPositiveButton("확인", null)
            .show()
    }

    private fun arcoreTtsButtonText(): String {
        return if (WalkAssistSettings.isArcoreTtsEnabled(this)) {
            "ARCore TTS: ON"
        } else {
            "ARCore TTS: OFF"
        }
    }

    private fun showEmergencyContactDialog() {
        val currentName = preferences.getString(KEY_EMERGENCY_NAME, "").orEmpty()
        val currentPhone = preferences.getString(KEY_EMERGENCY_PHONE, "").orEmpty()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 8)
        }
        val nameInput = EditText(this).apply {
            hint = "보호자 이름"
            setText(currentName)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val phoneInput = EditText(this).apply {
            hint = "전화번호 예: 010-1234-5678"
            setText(currentPhone)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_PHONE
        }
        container.addView(nameInput, fullWidthParams())
        container.addView(phoneInput, fullWidthParams(topMargin = 16))

        val dialog = AlertDialog.Builder(this)
            .setTitle("긴급 연락처 설정")
            .setView(container)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .setNeutralButton("삭제", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim()
                if (!isValidPhoneNumber(phone)) {
                    Toast.makeText(this, "올바른 전화번호를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString(KEY_EMERGENCY_NAME, name)
                    .putString(KEY_EMERGENCY_PHONE, phone)
                    .apply()
                Toast.makeText(this, "긴급 연락처를 저장했습니다.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                preferences.edit()
                    .remove(KEY_EMERGENCY_NAME)
                    .remove(KEY_EMERGENCY_PHONE)
                    .apply()
                Toast.makeText(this, "긴급 연락처를 삭제했습니다.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        if (phone.isBlank()) return false
        if (!phone.matches(Regex("[0-9+\\-\\s()]+"))) return false
        val digitsOnly = phone.filter { it.isDigit() }
        return digitsOnly.length in 8..15
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun fullWidthParams(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (topMargin > 0) {
                setMargins(0, topMargin, 0, 0)
            }
        }
    }

    companion object {
        private const val KEY_EMERGENCY_NAME = "emergency_name"
        private const val KEY_EMERGENCY_PHONE = "emergency_phone"
    }
}
