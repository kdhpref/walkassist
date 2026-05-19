package com.example.walkassist

import android.util.Log
import android.os.SystemClock
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import kotlin.math.max

class Florence2OnnxGenerator(
    private val modelRoot: File,
    private val variant: Florence2OnnxVariant,
) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = mutableListOf<OrtSession.SessionOptions>()
    private val backendSelections = mutableListOf<String>()
    private val embedSession = createSession("onnx/embed_tokens_${variant.onnxSuffix}.onnx")
    private val visionSession = createSession("onnx/vision_encoder_${variant.onnxSuffix}.onnx")
    private val encoderSession = createSession("onnx/encoder_model_${variant.onnxSuffix}.onnx")
    private val decoderSession = createSession("onnx/decoder_model_merged_${variant.onnxSuffix}.onnx")
    private val tokenizer = Florence2Tokenizer(modelRoot)

    val executionBackendSummary: String
        get() = backendSelections.joinToString(", ")

    fun generateCaption(bitmap: android.graphics.Bitmap): String {
        val startMs = SystemClock.elapsedRealtime()
        val promptIds = tokenizer.encode(CAPTION_PROMPT)
        val promptStartMs = SystemClock.elapsedRealtime()
        val promptEmbeds = encodeText(promptIds)
        val imageStartMs = SystemClock.elapsedRealtime()
        val imageFeatures = encodeImage(bitmap)
        val encoderInputStartMs = SystemClock.elapsedRealtime()
        val encoderInputs = concatImageAndTextEmbeds(imageFeatures, promptEmbeds)
        val encoderAttentionMask = LongArray(encoderInputs.sequenceLength) { 1L }
        val encoderStartMs = SystemClock.elapsedRealtime()
        val encoderHiddenStates = encodeCombinedInputs(
            inputsEmbeds = encoderInputs.data,
            sequenceLength = encoderInputs.sequenceLength,
            attentionMask = encoderAttentionMask,
        )

        val decoderStartMs = SystemClock.elapsedRealtime()
        val generatedIds = generateTokenIds(
            encoderHiddenStates = encoderHiddenStates,
            encoderSequenceLength = encoderInputs.sequenceLength,
            encoderAttentionMask = encoderAttentionMask,
        )
        val decodeStartMs = SystemClock.elapsedRealtime()
        val caption = tokenizer.decode(generatedIds)
        val endMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "Florence-2 ${variant.displayName} timings backend=[$executionBackendSummary] " +
                "promptEmbedMs=${imageStartMs - promptStartMs} " +
                "imageMs=${encoderInputStartMs - imageStartMs} " +
                "encoderMs=${decoderStartMs - encoderStartMs} " +
                "decoderMs=${decodeStartMs - decoderStartMs} " +
                "decodeMs=${endMs - decodeStartMs} " +
                "totalMs=${endMs - startMs} tokens=${generatedIds.size}",
        )
        return caption
    }

    private fun encodeText(inputIds: LongArray): Tensor3D {
        val feeds = mutableMapOf<String, OnnxTensor>()
        feeds["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1L, inputIds.size.toLong()))
        embedSession.run(feeds).use { result ->
            feeds.values.forEach { it.close() }
            return tensor3D(result.getRequiredTensor("inputs_embeds"))
        }
    }

    private fun encodeImage(bitmap: android.graphics.Bitmap): Tensor3D {
        val pixelValues = Florence2ImageProcessor.preprocess(bitmap)
        val feeds = mutableMapOf<String, OnnxTensor>()
        feeds["pixel_values"] = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(pixelValues),
            Florence2ImageProcessor.pixelShape(),
        )
        visionSession.run(feeds).use { result ->
            feeds.values.forEach { it.close() }
            return tensor3D(result.getRequiredTensor("image_features"))
        }
    }

    private fun encodeCombinedInputs(
        inputsEmbeds: FloatArray,
        sequenceLength: Int,
        attentionMask: LongArray,
    ): Tensor3D {
        val feeds = mutableMapOf<String, OnnxTensor>()
        feeds["inputs_embeds"] = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputsEmbeds),
            longArrayOf(1L, sequenceLength.toLong(), HIDDEN_SIZE.toLong()),
        )
        feeds["attention_mask"] = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(attentionMask),
            longArrayOf(1L, sequenceLength.toLong()),
        )
        encoderSession.run(feeds).use { result ->
            feeds.values.forEach { it.close() }
            return tensor3D(result.getRequiredTensor("last_hidden_state"))
        }
    }

    private fun generateTokenIds(
        encoderHiddenStates: Tensor3D,
        encoderSequenceLength: Int,
        encoderAttentionMask: LongArray,
    ): List<Int> {
        val generated = mutableListOf<Int>()
        val decoderSequence = mutableListOf(Florence2Tokenizer.EOS_TOKEN_ID.toLong())

        repeat(MAX_NEW_TOKENS) {
            val decoderEmbeds = encodeText(decoderSequence.toLongArray())
            val ownedFeeds = mutableListOf<OnnxTensor>()
            val feeds = LinkedHashMap<String, OnnxTensorLike>()

            putTensorIfExpected(
                feeds = feeds,
                ownedFeeds = ownedFeeds,
                name = "inputs_embeds",
                tensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(decoderEmbeds.data),
                    longArrayOf(1L, decoderEmbeds.sequenceLength.toLong(), HIDDEN_SIZE.toLong()),
                ),
            )
            putTensorIfExpected(
                feeds = feeds,
                ownedFeeds = ownedFeeds,
                name = "encoder_attention_mask",
                tensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(encoderAttentionMask),
                    longArrayOf(1L, encoderSequenceLength.toLong()),
                ),
            )
            putTensorIfExpected(
                feeds = feeds,
                ownedFeeds = ownedFeeds,
                name = "encoder_hidden_states",
                tensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(encoderHiddenStates.data),
                    longArrayOf(1L, encoderSequenceLength.toLong(), HIDDEN_SIZE.toLong()),
                ),
            )
            putScalarLongIfExpected(feeds, ownedFeeds, "num_logits_to_keep", 0L)
            putScalarBooleanIfExpected(feeds, ownedFeeds, "use_cache_branch", false)
            addEmptyPastKeyValues(feeds, ownedFeeds)

            decoderSession.run(feeds).use { result ->
                ownedFeeds.forEach { it.close() }
                val logits = logitsArray(result.getRequiredTensor("logits"))
                val nextToken = argmaxLastToken(logits)
                if (nextToken == Florence2Tokenizer.EOS_TOKEN_ID) {
                    return generated
                }
                generated.add(nextToken)
                decoderSequence.add(nextToken.toLong())
            }
        }
        return generated
    }

    private fun concatImageAndTextEmbeds(
        imageFeatures: Tensor3D,
        textFeatures: Tensor3D,
    ): Tensor3D {
        val sequenceLength = imageFeatures.sequenceLength + textFeatures.sequenceLength
        val output = FloatArray(sequenceLength * HIDDEN_SIZE)
        System.arraycopy(imageFeatures.data, 0, output, 0, imageFeatures.data.size)
        System.arraycopy(textFeatures.data, 0, output, imageFeatures.data.size, textFeatures.data.size)
        return Tensor3D(
            data = output,
            sequenceLength = sequenceLength,
            hiddenSize = HIDDEN_SIZE,
        )
    }

    private fun addEmptyPastKeyValues(
        feeds: MutableMap<String, OnnxTensorLike>,
        ownedFeeds: MutableList<OnnxTensor>,
    ) {
        decoderSession.inputInfo.forEach { (name, nodeInfo) ->
            if (!name.startsWith("past_key_values")) return@forEach
            if (feeds.containsKey(name)) return@forEach
            val info = nodeInfo.tensorInfoOrNull() ?: return@forEach
            val shape = info.shape.map { dimension ->
                when {
                    dimension > 0L -> dimension
                    name.contains(".encoder.") && dimension < 0L -> 0L
                    name.contains(".decoder.") && dimension < 0L -> 0L
                    else -> 0L
                }
            }.toLongArray()
            val size = shape.fold(1L) { acc, value -> acc * max(value, 0L) }.toInt()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(size)), shape)
            feeds[name] = tensor
            ownedFeeds.add(tensor)
        }
    }

    private fun putTensorIfExpected(
        feeds: MutableMap<String, OnnxTensorLike>,
        ownedFeeds: MutableList<OnnxTensor>,
        name: String,
        tensor: OnnxTensor,
    ) {
        if (decoderSession.inputInfo.containsKey(name)) {
            feeds[name] = tensor
            ownedFeeds.add(tensor)
        } else {
            tensor.close()
        }
    }

    private fun putScalarLongIfExpected(
        feeds: MutableMap<String, OnnxTensorLike>,
        ownedFeeds: MutableList<OnnxTensor>,
        name: String,
        value: Long,
    ) {
        if (!decoderSession.inputInfo.containsKey(name)) return
        val tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value)), longArrayOf())
        feeds[name] = tensor
        ownedFeeds.add(tensor)
    }

    private fun putScalarBooleanIfExpected(
        feeds: MutableMap<String, OnnxTensorLike>,
        ownedFeeds: MutableList<OnnxTensor>,
        name: String,
        value: Boolean,
    ) {
        if (!decoderSession.inputInfo.containsKey(name)) return
        val tensor = OnnxTensor.createTensor(env, booleanArrayOf(value))
        feeds[name] = tensor
        ownedFeeds.add(tensor)
    }

    private fun argmaxLastToken(logits: FloatArray): Int {
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        val start = logits.size - VOCAB_SIZE
        for (index in 0 until VOCAB_SIZE) {
            val value = logits[start + index]
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun createSession(relativePath: String): OrtSession {
        val modelFile = File(modelRoot, relativePath.replace('/', File.separatorChar))
        listOf(NnapiMode.STRICT, NnapiMode.MIXED).forEach { nnapiMode ->
            val acceleratorOptions = runCatching {
                createSessionOptions(nnapiMode = nnapiMode)
            }.onFailure { error ->
                Log.w(TAG, "NNAPI $nnapiMode options are unavailable for $relativePath; trying next backend.", error)
            }.getOrNull()

            if (acceleratorOptions != null) {
                runCatching {
                    env.createSession(modelFile.absolutePath, acceleratorOptions)
                }.onSuccess {
                    sessionOptions += acceleratorOptions
                    backendSelections += "${modelFile.name}=nnapi_${nnapiMode.label}"
                    Log.i(TAG, "Created ${modelFile.name} with NNAPI ${nnapiMode.label}.")
                    return it
                }.onFailure { error ->
                    acceleratorOptions.close()
                    Log.w(TAG, "NNAPI ${nnapiMode.label} session failed for $relativePath; trying next backend.", error)
                }
            }
        }

        val cpuOptions = createSessionOptions(nnapiMode = null)
        return try {
            env.createSession(modelFile.absolutePath, cpuOptions).also {
                sessionOptions += cpuOptions
                backendSelections += "${modelFile.name}=cpu"
                Log.i(TAG, "Created ${modelFile.name} with CPU fallback.")
            }
        } catch (error: OrtException) {
            cpuOptions.close()
            throw error
        }
    }

    private fun createSessionOptions(nnapiMode: NnapiMode?): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(cpuFallbackThreadCount())
            when (nnapiMode) {
                NnapiMode.STRICT -> addNnapi(NNAPI_STRICT_ACCELERATOR_FLAGS)
                NnapiMode.MIXED -> addNnapi(NNAPI_MIXED_ACCELERATOR_FLAGS)
                null -> Unit
            }
        }
    }

    private fun cpuFallbackThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return cores.coerceAtMost(4)
    }

    private fun tensor3D(tensor: OnnxTensor): Tensor3D {
        val info = tensor.info as TensorInfo
        val shape = info.shape
        val sequenceLength = shape.getOrNull(1)?.toInt() ?: 0
        val hiddenSize = shape.getOrNull(2)?.toInt() ?: HIDDEN_SIZE
        val buffer = tensor.floatBuffer
        buffer.rewind()
        val data = FloatArray(buffer.remaining())
        buffer.get(data)
        return Tensor3D(
            data = data,
            sequenceLength = sequenceLength,
            hiddenSize = hiddenSize,
        )
    }

    private fun logitsArray(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        buffer.rewind()
        val data = FloatArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    private fun OrtSession.Result.getRequiredTensor(name: String): OnnxTensor {
        return get(name)
            .orElseThrow { IllegalStateException("Missing ONNX output: $name") } as OnnxTensor
    }

    private fun NodeInfo.tensorInfoOrNull(): TensorInfo? {
        return info as? TensorInfo
    }

    override fun close() {
        embedSession.close()
        visionSession.close()
        encoderSession.close()
        decoderSession.close()
        sessionOptions.forEach { it.close() }
        sessionOptions.clear()
    }

    private data class Tensor3D(
        val data: FloatArray,
        val sequenceLength: Int,
        val hiddenSize: Int,
    )

    private enum class NnapiMode(val label: String) {
        STRICT("strict"),
        MIXED("mixed"),
    }

    companion object {
        private const val TAG = "Florence2OnnxGenerator"
        private const val CAPTION_PROMPT =
            "이미지에 보이는 보행 장면을 한국어 두 문장으로 묘사하세요. 사람, 차량, 문, 계단, 턱, 표지판, 통로 상태를 구체적으로 말하세요."
        private const val HIDDEN_SIZE = 768
        private const val VOCAB_SIZE = 51289
        private const val MAX_NEW_TOKENS = 64
        private val NNAPI_STRICT_ACCELERATOR_FLAGS: EnumSet<NNAPIFlags> = EnumSet.of(
            NNAPIFlags.USE_FP16,
            NNAPIFlags.USE_NCHW,
            NNAPIFlags.CPU_DISABLED,
        )
        private val NNAPI_MIXED_ACCELERATOR_FLAGS: EnumSet<NNAPIFlags> = EnumSet.of(
            NNAPIFlags.USE_FP16,
            NNAPIFlags.USE_NCHW,
        )
    }
}
