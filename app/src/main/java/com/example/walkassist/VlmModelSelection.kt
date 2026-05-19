package com.example.walkassist

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class VlmModelOption(
    val preferenceValue: String,
    val displayName: String,
    val florenceVariant: Florence2OnnxVariant?,
) {
    GEMINI_API(
        preferenceValue = "gemini_api",
        displayName = "Gemini API",
        florenceVariant = null,
    ),
    GEMINI_3_1_FLASH_LIVE_API(
        preferenceValue = "gemini_3_1_flash_live_api",
        displayName = "Gemini 3.1 Flash Live Preview",
        florenceVariant = null,
    ),
    FLORENCE2_INT4(
        preferenceValue = "florence2_int4",
        displayName = "Florence-2 INT4",
        florenceVariant = Florence2OnnxVariant.INT4,
    ),
    FLORENCE2_INT8(
        preferenceValue = "florence2_int8",
        displayName = "Florence-2 INT8",
        florenceVariant = Florence2OnnxVariant.INT8,
    );

    companion object {
        fun fromPreferenceValue(value: String): VlmModelOption {
            return values().firstOrNull { it.preferenceValue == value } ?: GEMINI_API
        }
    }
}

enum class Florence2OnnxVariant(
    val displayName: String,
    val directoryName: String,
    val modelName: String,
    val onnxSuffix: String,
) {
    INT4(
        displayName = "Florence-2 INT4",
        directoryName = "int4",
        modelName = "onnx-community/Florence-2-base-ft INT4",
        onnxSuffix = "q4",
    ),
    INT8(
        displayName = "Florence-2 INT8",
        directoryName = "int8",
        modelName = "onnx-community/Florence-2-base-ft INT8",
        onnxSuffix = "int8",
    );

    val modelFiles: List<Florence2ModelFileSpec>
        get() = Florence2ModelStore.commonFiles + listOf(
            Florence2ModelFileSpec("onnx/encoder_model_$onnxSuffix.onnx"),
            Florence2ModelFileSpec("onnx/decoder_model_merged_$onnxSuffix.onnx"),
            Florence2ModelFileSpec("onnx/embed_tokens_$onnxSuffix.onnx"),
            Florence2ModelFileSpec("onnx/vision_encoder_$onnxSuffix.onnx"),
        )
}

data class Florence2ModelFileSpec(
    val relativePath: String,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$FLORENCE2_REPOSITORY/resolve/main/$relativePath"

    companion object {
        const val FLORENCE2_REPOSITORY = "onnx-community/Florence-2-base-ft"
    }
}

data class Florence2ModelLocalStatus(
    val variant: Florence2OnnxVariant,
    val rootDirectory: File,
    val missingFiles: List<Florence2ModelFileSpec>,
) {
    val isAvailable: Boolean = missingFiles.isEmpty()
}

object Florence2ModelStore {
    val commonFiles = listOf(
        Florence2ModelFileSpec("config.json"),
        Florence2ModelFileSpec("generation_config.json"),
        Florence2ModelFileSpec("preprocessor_config.json"),
        Florence2ModelFileSpec("tokenizer.json"),
        Florence2ModelFileSpec("tokenizer_config.json"),
        Florence2ModelFileSpec("vocab.json"),
        Florence2ModelFileSpec("merges.txt"),
        Florence2ModelFileSpec("added_tokens.json"),
        Florence2ModelFileSpec("special_tokens_map.json"),
    )

    fun variantRoot(
        context: Context,
        variant: Florence2OnnxVariant,
    ): File {
        val baseDirectory = context.getExternalFilesDir("models")
            ?: File(context.filesDir, "models")
        return File(baseDirectory, "florence-2-base-ft/${variant.directoryName}")
    }

    fun localStatus(
        context: Context,
        variant: Florence2OnnxVariant,
    ): Florence2ModelLocalStatus {
        val root = variantRoot(context, variant)
        val missingFiles = variant.modelFiles.filter { spec ->
            val localFile = File(root, spec.relativePath.replace('/', File.separatorChar))
            !localFile.isFile || localFile.length() <= 0L
        }
        return Florence2ModelLocalStatus(
            variant = variant,
            rootDirectory = root,
            missingFiles = missingFiles,
        )
    }

    fun downloadVariant(
        context: Context,
        variant: Florence2OnnxVariant,
        onFileComplete: ((Florence2ModelFileSpec) -> Unit)? = null,
    ) {
        val root = variantRoot(context, variant)
        variant.modelFiles.forEach { spec ->
            val localFile = File(root, spec.relativePath.replace('/', File.separatorChar))
            if (localFile.isFile && localFile.length() > 0L) {
                onFileComplete?.invoke(spec)
                return@forEach
            }
            downloadFile(spec.downloadUrl, localFile)
            onFileComplete?.invoke(spec)
        }
    }

    private fun downloadFile(
        sourceUrl: String,
        destinationFile: File,
    ) {
        destinationFile.parentFile?.mkdirs()
        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.download")
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode while downloading $sourceUrl")
            }
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempFile.renameTo(destinationFile)) {
                destinationFile.delete()
                if (!tempFile.renameTo(destinationFile)) {
                    throw IllegalStateException("Could not move ${tempFile.absolutePath}")
                }
            }
        } finally {
            connection.disconnect()
            if (tempFile.exists() && !destinationFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
