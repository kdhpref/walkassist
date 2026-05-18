package com.example.walkassist

import android.content.Context

object WalkAssistSettings {
    private const val PREF_NAME = "walkassist_settings"
    private const val KEY_GEMMA_3N_MODEL_PATH = "gemma_3n_model_path"
    private const val KEY_ARCORE_TTS_ENABLED = "arcore_tts_enabled"
    private const val KEY_DEBUG_YOLO_ENABLED = "debug_yolo_enabled"
    private const val KEY_DEBUG_FLOOR_SEGMENTATION_ENABLED = "debug_floor_segmentation_enabled"
    private const val KEY_DEBUG_ARCORE_HIT_TEST_ENABLED = "debug_arcore_hit_test_enabled"
    private const val KEY_DEBUG_RAW_DEPTH_ENABLED = "debug_raw_depth_enabled"
    private const val KEY_DEBUG_LOCAL_MAP_ENABLED = "debug_local_map_enabled"
    private const val KEY_DEBUG_CROSSWALK_ENABLED = "debug_crosswalk_enabled"
    private const val KEY_DEBUG_VLM_ENABLED = "debug_vlm_enabled"
    private const val KEY_VLM_MODEL_OPTION = "vlm_model_option"

    const val GEMMA_3N_MODEL_REPOSITORY = "google/gemma-3n-E2B-it-litert-lm"
    const val DEFAULT_GEMMA_3N_MODEL_FILENAME = "gemma-3n-E2B-it-int4.litertlm"

    fun preferences(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun gemma3nModelPath(context: Context): String {
        val storedPath = preferences(context).getString(KEY_GEMMA_3N_MODEL_PATH, "").orEmpty()
        return storedPath.ifBlank {
            context.getExternalFilesDir("models")
                ?.resolve(DEFAULT_GEMMA_3N_MODEL_FILENAME)
                ?.absolutePath
                ?: "/sdcard/Android/data/${context.packageName}/files/models/$DEFAULT_GEMMA_3N_MODEL_FILENAME"
        }
    }

    fun setGemma3nModelPath(
        context: Context,
        modelPath: String,
    ) {
        preferences(context)
            .edit()
            .putString(KEY_GEMMA_3N_MODEL_PATH, modelPath)
            .apply()
    }

    fun vlmModelOption(context: Context): VlmModelOption {
        val storedValue = preferences(context).getString(KEY_VLM_MODEL_OPTION, "").orEmpty()
        return VlmModelOption.fromPreferenceValue(storedValue)
    }

    fun setVlmModelOption(
        context: Context,
        option: VlmModelOption,
    ) {
        preferences(context)
            .edit()
            .putString(KEY_VLM_MODEL_OPTION, option.preferenceValue)
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

    fun debugPipelineFlags(context: Context): DebugPipelineFlags {
        val prefs = preferences(context)
        return DebugPipelineFlags(
            yoloEnabled = prefs.getBoolean(KEY_DEBUG_YOLO_ENABLED, true),
            floorSegmentationEnabled = prefs.getBoolean(KEY_DEBUG_FLOOR_SEGMENTATION_ENABLED, true),
            arCoreHitTestEnabled = prefs.getBoolean(KEY_DEBUG_ARCORE_HIT_TEST_ENABLED, true),
            rawDepthEnabled = prefs.getBoolean(KEY_DEBUG_RAW_DEPTH_ENABLED, true),
            localMapEnabled = prefs.getBoolean(KEY_DEBUG_LOCAL_MAP_ENABLED, true),
            crosswalkEnabled = prefs.getBoolean(KEY_DEBUG_CROSSWALK_ENABLED, true),
            vlmEnabled = prefs.getBoolean(KEY_DEBUG_VLM_ENABLED, true),
        )
    }

    fun setDebugPipelineFlags(
        context: Context,
        flags: DebugPipelineFlags,
    ) {
        preferences(context)
            .edit()
            .putBoolean(KEY_DEBUG_YOLO_ENABLED, flags.yoloEnabled)
            .putBoolean(KEY_DEBUG_FLOOR_SEGMENTATION_ENABLED, flags.floorSegmentationEnabled)
            .putBoolean(KEY_DEBUG_ARCORE_HIT_TEST_ENABLED, flags.arCoreHitTestEnabled)
            .putBoolean(KEY_DEBUG_RAW_DEPTH_ENABLED, flags.rawDepthEnabled)
            .putBoolean(KEY_DEBUG_LOCAL_MAP_ENABLED, flags.localMapEnabled)
            .putBoolean(KEY_DEBUG_CROSSWALK_ENABLED, flags.crosswalkEnabled)
            .putBoolean(KEY_DEBUG_VLM_ENABLED, flags.vlmEnabled)
            .apply()
    }
}

data class DebugPipelineFlags(
    val yoloEnabled: Boolean = true,
    val floorSegmentationEnabled: Boolean = true,
    val arCoreHitTestEnabled: Boolean = true,
    val rawDepthEnabled: Boolean = true,
    val localMapEnabled: Boolean = true,
    val crosswalkEnabled: Boolean = true,
    val vlmEnabled: Boolean = true,
)
