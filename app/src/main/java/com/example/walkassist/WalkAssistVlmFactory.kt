package com.example.walkassist

import android.content.Context

object WalkAssistVlmFactory {
    fun create(context: Context): VlmSceneInterpreter {
        return when (val option = WalkAssistSettings.vlmModelOption(context)) {
            VlmModelOption.GEMINI_API -> GeminiVlmSceneInterpreter()
            VlmModelOption.GEMINI_3_1_FLASH_LIVE_API -> GeminiVlmSceneInterpreter(
                model = GeminiVlmSceneInterpreter.GEMINI_3_1_FLASH_LIVE_MODEL,
            )
            VlmModelOption.FLORENCE2_INT4,
            VlmModelOption.FLORENCE2_INT8,
            -> Florence2OnnxVlmSceneInterpreter(
                context = context,
                variant = option.florenceVariant ?: Florence2OnnxVariant.INT4,
            )
        }
    }

    fun prepareSelected(context: Context): VlmModelPreparationStatus {
        return when (val option = WalkAssistSettings.vlmModelOption(context)) {
            VlmModelOption.GEMINI_API -> GeminiVlmSceneInterpreter().prepareForUse()
            VlmModelOption.GEMINI_3_1_FLASH_LIVE_API -> GeminiVlmSceneInterpreter(
                model = GeminiVlmSceneInterpreter.GEMINI_3_1_FLASH_LIVE_MODEL,
            ).prepareForUse()
            VlmModelOption.FLORENCE2_INT4,
            VlmModelOption.FLORENCE2_INT8,
            -> Florence2OnnxVlmSceneInterpreter(
                context = context,
                variant = option.florenceVariant ?: Florence2OnnxVariant.INT4,
            ).prepareForUse()
        }
    }
}
