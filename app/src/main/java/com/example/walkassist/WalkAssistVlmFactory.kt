package com.example.walkassist

object WalkAssistVlmFactory {
    const val SELECTED_MODEL_NAME = GeminiVlmSceneInterpreter.MODEL_NAME
    const val SELECTED_MODEL_DISPLAY_NAME = "Gemini 2.5 Flash Lite"

    fun create(): VlmSceneInterpreter = GeminiVlmSceneInterpreter()

    fun prepareSelected(): VlmModelPreparationStatus = GeminiVlmSceneInterpreter().prepareForUse()
}
