package com.example.walkassist

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class GuideSettingsActivity : AppCompatActivity() {
    private val preferences by lazy {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

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
            text = "긴급 상황에 사용할 보호자 연락처를 관리합니다."
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

        val closeButton = Button(this).apply {
            text = "닫기"
            textSize = 18f
            minHeight = 64
            setOnClickListener { finish() }
        }

        root.addView(titleText, fullWidthParams())
        root.addView(descriptionText, fullWidthParams())
        root.addView(emergencyButton, fullWidthParams())
        root.addView(closeButton, fullWidthParams(topMargin = 18))
        setContentView(root)
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
        private const val PREF_NAME = "walkassist_settings"
        private const val KEY_EMERGENCY_NAME = "emergency_name"
        private const val KEY_EMERGENCY_PHONE = "emergency_phone"
    }
}
