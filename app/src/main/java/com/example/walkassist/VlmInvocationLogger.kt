package com.example.walkassist

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

object VlmInvocationLogger {
    private const val TAG = "VlmInvocationLogger"
    private const val FILE_NAME = "vlm-invocations.jsonl"

    fun append(
        context: Context,
        selectedModelName: String,
        resultModelName: String?,
        latencyMs: Long,
        success: Boolean,
        risk: VlmWalkingRisk?,
        errorMessage: String? = null,
    ) {
        val record = JSONObject()
            .put("wallTimeMs", System.currentTimeMillis())
            .put("selectedModelName", selectedModelName)
            .put("resultModelName", resultModelName ?: JSONObject.NULL)
            .put("buttonToAnswerLatencyMs", latencyMs)
            .put("success", success)
            .put("risk", risk?.name ?: JSONObject.NULL)
            .put("errorMessage", errorMessage ?: JSONObject.NULL)

        runCatching {
            File(context.filesDir, FILE_NAME).appendText(record.toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "Failed to append VLM invocation log", it)
        }
    }
}
