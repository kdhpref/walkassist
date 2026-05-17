package com.example.walkassist

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ArCoreReplayMode {
    LIVE,
    RECORDING,
    PLAYBACK,
}

data class ArCoreReplayUiState(
    val mode: ArCoreReplayMode = ArCoreReplayMode.LIVE,
    val recordingStatus: String = "NONE",
    val playbackStatus: String = "NONE",
    val datasetUri: Uri? = null,
    val message: String = "",
)

data class ArCoreReplayDataset(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val absolutePath: String,
)

object ArCoreReplayController {
    const val EXTRA_RECORD_ON_START = "com.example.walkassist.extra.RECORD_ON_START"
    const val EXTRA_PLAYBACK_DATASET_URI = "com.example.walkassist.extra.PLAYBACK_DATASET_URI"

    private const val KEY_LAST_ARCORE_DATASET_URI = "last_arcore_dataset_uri"
    private const val RECORDING_DIR = "arcore_replay"

    @Volatile
    private var state: ArCoreReplayUiState = ArCoreReplayUiState()
    private val listeners = LinkedHashSet<(ArCoreReplayUiState) -> Unit>()

    fun currentState(): ArCoreReplayUiState = state

    fun addListener(listener: (ArCoreReplayUiState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (ArCoreReplayUiState) -> Unit) {
        listeners -= listener
    }

    fun update(next: ArCoreReplayUiState) {
        state = next
        listeners.toList().forEach { it(next) }
    }

    fun updateMessage(message: String) {
        update(state.copy(message = message))
    }

    fun createDatasetUri(context: Context): Uri {
        val directory = recordingDirectory(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return Uri.fromFile(File(directory, "walkassist_arcore_$timestamp.mp4"))
    }

    fun recordingDirectory(context: Context): File {
        val directory = context.getExternalFilesDir(RECORDING_DIR)
            ?: File(context.filesDir, RECORDING_DIR)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    fun recordedDatasets(context: Context): List<ArCoreReplayDataset> {
        return recordingDirectory(context)
            .listFiles { file ->
                file.isFile && file.extension.equals("mp4", ignoreCase = true)
            }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { file ->
                ArCoreReplayDataset(
                    uri = Uri.fromFile(file),
                    fileName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                    absolutePath = file.absolutePath,
                )
            }
    }

    fun deleteRecordedDataset(context: Context, dataset: ArCoreReplayDataset): Boolean {
        val recordingRoot = recordingDirectory(context).canonicalFile
        val target = File(dataset.absolutePath).canonicalFile
        if (!target.path.startsWith(recordingRoot.path) || target.extension.lowercase(Locale.US) != "mp4") {
            return false
        }

        val deleted = target.delete()
        if (deleted && lastDatasetUri(context)?.toString() == dataset.uri.toString()) {
            WalkAssistSettings.preferences(context)
                .edit()
                .remove(KEY_LAST_ARCORE_DATASET_URI)
                .apply()
        }
        return deleted
    }

    fun saveLastDataset(context: Context, uri: Uri) {
        WalkAssistSettings.preferences(context)
            .edit()
            .putString(KEY_LAST_ARCORE_DATASET_URI, uri.toString())
            .apply()
    }

    fun lastDatasetUri(context: Context): Uri? {
        val stored = WalkAssistSettings.preferences(context)
            .getString(KEY_LAST_ARCORE_DATASET_URI, "")
            .orEmpty()
        return stored.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    fun reset() {
        update(ArCoreReplayUiState())
    }
}
