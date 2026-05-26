package com.example.walkassist

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

data class ArDepthMeasurementRecord(
    val frameTimestampNanos: Long,
    val trackingState: String,
    val trackingFailureReason: String,
    val sensingConfidenceScore: Int,
    val acquisitionStatus: String,
    val rawDepthTimestampNanos: Long?,
    val isNewRawDepth: Boolean,
    val rawDepthWidth: Int?,
    val rawDepthHeight: Int?,
    val pitchDownDegrees: Float?,
    val motionMetersPerSecond: Float?,
    val plannedCorridorSamples: Int,
    val validCorridorHits: Int,
    val corridorValidRatio: Float,
    val validGridCells: Int,
    val gridAverageConfidence: Float,
    val objectCount: Int,
    val objectDepthCount: Int,
    val leftNearestMeters: Float?,
    val centerNearestMeters: Float?,
    val rightNearestMeters: Float?,
    val rawDepthNearestMeters: Float?,
)

object ArDepthMeasurementLogger {
    private const val TAG = "ArDepthMeasurementLogger"
    private const val FILE_NAME = "ar-depth-measurements.jsonl"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    fun append(
        context: Context,
        record: ArDepthMeasurementRecord,
    ) {
        val json = JSONObject()
            .put("wallTimeMs", System.currentTimeMillis())
            .put("frameTimestampNanos", record.frameTimestampNanos)
            .put("trackingState", record.trackingState)
            .put("trackingFailureReason", record.trackingFailureReason)
            .put("sensingConfidenceScore", record.sensingConfidenceScore)
            .put("acquisitionStatus", record.acquisitionStatus)
            .put("rawDepthTimestampNanos", record.rawDepthTimestampNanos ?: JSONObject.NULL)
            .put("isNewRawDepth", record.isNewRawDepth)
            .put("rawDepthWidth", record.rawDepthWidth ?: JSONObject.NULL)
            .put("rawDepthHeight", record.rawDepthHeight ?: JSONObject.NULL)
            .put("pitchDownDegrees", record.pitchDownDegrees ?: JSONObject.NULL)
            .put("motionMetersPerSecond", record.motionMetersPerSecond ?: JSONObject.NULL)
            .put("plannedCorridorSamples", record.plannedCorridorSamples)
            .put("validCorridorHits", record.validCorridorHits)
            .put("corridorValidRatio", record.corridorValidRatio)
            .put("validGridCells", record.validGridCells)
            .put("gridAverageConfidence", record.gridAverageConfidence)
            .put("objectCount", record.objectCount)
            .put("objectDepthCount", record.objectDepthCount)
            .put("leftNearestMeters", record.leftNearestMeters ?: JSONObject.NULL)
            .put("centerNearestMeters", record.centerNearestMeters ?: JSONObject.NULL)
            .put("rightNearestMeters", record.rightNearestMeters ?: JSONObject.NULL)
            .put("rawDepthNearestMeters", record.rawDepthNearestMeters ?: JSONObject.NULL)

        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_FILE_BYTES) {
                File(context.filesDir, "$FILE_NAME.old").also { oldFile ->
                    if (oldFile.exists()) oldFile.delete()
                    file.renameTo(oldFile)
                }
            }
            file.appendText(json.toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "Failed to append AR depth measurement log", it)
        }
    }
}
