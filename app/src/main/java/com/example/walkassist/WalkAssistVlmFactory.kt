package com.example.walkassist

import android.content.Context

object WalkAssistVlmFactory {
    fun create(context: Context): VlmSceneInterpreter {
        return GeminiVlmSceneInterpreter()
    }

    fun prepareSelected(context: Context): VlmModelPreparationStatus {
        return GeminiVlmSceneInterpreter().prepareForUse()
    }
}
