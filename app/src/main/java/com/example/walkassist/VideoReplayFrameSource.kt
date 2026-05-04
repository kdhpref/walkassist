package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class VideoReplayFrame(
    val bitmap: Bitmap,
    val timeMs: Long,
    val durationMs: Long,
)

interface FrameSource {
    suspend fun replay(onFrame: suspend (VideoReplayFrame) -> Unit)
}

class VideoReplayFrameSource(
    private val context: Context,
    private val uri: Uri,
    private val frameIntervalMs: Long = VideoFrameAnalyzer.DEFAULT_FRAME_INTERVAL_MS,
    private val maxFrameWidth: Int = 480,
) : FrameSource {
    /*
     * This source intentionally emits Bitmap frames only. It is a test adapter
     * for image-based spatial recognition, not an ARCore recording/playback
     * replacement for pose, raw depth, plane, or hit-test data.
     */
    override suspend fun replay(onFrame: suspend (VideoReplayFrame) -> Unit) {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                if (durationMs <= 0L) {
                    throw IllegalArgumentException("Video duration is unavailable.")
                }
                val sourceWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull()?.coerceAtLeast(1) ?: maxFrameWidth
                val sourceHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull()?.coerceAtLeast(1) ?: maxFrameWidth
                val targetWidth = minOf(maxFrameWidth, sourceWidth)
                val targetHeight = ((sourceHeight * (targetWidth / sourceWidth.toFloat())).toInt())
                    .coerceAtLeast(1)

                var timeMs = 0L
                while (timeMs <= durationMs) {
                    coroutineContext.ensureActive()
                    val scaledFrame = retriever.getReplayFrameAt(
                        timeMs = timeMs,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                    )
                    if (scaledFrame == null) {
                        Log.w(TAG, "Skipping unavailable replay frame at ${timeMs}ms")
                        timeMs += frameIntervalMs
                        continue
                    }
                    onFrame(
                        VideoReplayFrame(
                            bitmap = scaledFrame,
                            timeMs = timeMs,
                            durationMs = durationMs,
                        ),
                    )
                    timeMs += frameIntervalMs
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                retriever.release()
            }
        }
    }

    private fun MediaMetadataRetriever.getReplayFrameAt(
        timeMs: Long,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val timeUs = timeMs * 1_000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return runCatching {
                getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight,
                )
            }.getOrNull()
        }

        val frame = getFrameAtTime(
            timeUs,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        val scaledFrame = frame.scaleDownTo(maxFrameWidth)
        if (scaledFrame !== frame) {
            frame.recycle()
        }
        return scaledFrame
    }

    private fun Bitmap.scaleDownTo(maxWidth: Int): Bitmap {
        if (width <= maxWidth) return this
        val targetHeight = (height * (maxWidth / width.toFloat())).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, maxWidth, targetHeight, true)
    }

    companion object {
        private const val TAG = "VideoReplayFrameSource"
    }
}
