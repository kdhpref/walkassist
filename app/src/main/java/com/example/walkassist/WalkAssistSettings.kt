package com.example.walkassist

import android.content.Context
import com.google.mlkit.genai.prompt.ModelPreference

enum class VlmModelSelection(
    val preference: Int,
    val label: String,
) {
    E2B_FAST(ModelPreference.FAST, "E2B / Fast"),
    E4B_FULL(ModelPreference.FULL, "E4B / Full"),
}

object WalkAssistSettings {
    private const val PREF_NAME = "walkassist_settings"
    private const val KEY_VLM_MODEL = "vlm_model"
    private const val KEY_ARCORE_TTS_ENABLED = "arcore_tts_enabled"

    fun preferences(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun vlmModelSelection(context: Context): VlmModelSelection {
        val value = preferences(context).getString(KEY_VLM_MODEL, VlmModelSelection.E2B_FAST.name)
        return VlmModelSelection.entries.firstOrNull { it.name == value } ?: VlmModelSelection.E2B_FAST
    }

    fun setVlmModelSelection(
        context: Context,
        selection: VlmModelSelection,
    ) {
        preferences(context)
            .edit()
            .putString(KEY_VLM_MODEL, selection.name)
            .apply()
    }

    fun isArcoreTtsEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ARCORE_TTS_ENABLED, true)
    }

    fun setArcoreTtsEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        preferences(context)
            .edit()
            .putBoolean(KEY_ARCORE_TTS_ENABLED, enabled)
            .apply()
    }
}
