package com.example.walkassist

import android.content.Context

object WalkAssistVlmFactory {
    fun create(context: Context): VlmSceneInterpreter {
        return GeminiVlmSceneInterpreter()
    }
}
