package com.example.walkassist.feedback.runtime

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackOutputMode
import com.example.walkassist.feedback.core.FeedbackRequest
import com.example.walkassist.feedback.core.FeedbackSource
import com.example.walkassist.feedback.core.FeedbackThresholds
import com.example.walkassist.feedback.core.HapticStrength
import java.util.Locale

class FeedbackManager(context: Context) {
    private val accessibilityAnnouncer = AccessibilityAnnouncer(context)
    private val hapticController = HapticFeedbackController(context)
    private val speechController = acquireSpeechController(context.applicationContext)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME_PERCENT)
    private var prioritySpeechProtectedUntilMs = 0L
    private var speechLocale: Locale = Locale.KOREAN

    fun setSpeechLocale(locale: Locale) {
        speechLocale = locale
        speechController.setLanguage(locale)
    }

    fun provideFeedback(
        request: FeedbackRequest,
        announcementView: TextView? = null,
        queueSpeech: Boolean = false,
    ) {
        if (
            request.message.isBlank() &&
            !request.outputMode.useHaptic &&
            !request.outputMode.useSpeech
        ) {
            return
        }

        if (shouldDropByThrottle(request)) {
            Log.d(TAG, "Dropping throttled feedback key=${request.throttleKey}")
            return
        }

        if (request.outputMode.useHaptic) {
            hapticController.vibrate(
                level = request.alertLevel,
                strength = request.outputMode.hapticStrength,
            )
        }

        if (request.outputMode.useSpeech && request.message.isNotBlank()) {
            speakIfNeeded(
                message = request.message,
                level = request.alertLevel,
                announcementView = announcementView,
                priority = request.priority,
                interruptCurrent = request.interruptCurrent,
                queueSpeech = queueSpeech,
            )
        }

        markThrottleExecuted(request)
    }

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView? = null,
        prioritySpeech: Boolean = false,
        queueSpeech: Boolean = false,
    ) {
        provideFeedback(
            message = message,
            level = level,
            outputMode = FeedbackOutputMode(
                useSpeech = true,
                useHaptic = true,
                hapticStrength = defaultHapticStrength(level),
            ),
            announcementView = announcementView,
            prioritySpeech = prioritySpeech,
            queueSpeech = queueSpeech,
        )
    }

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        outputMode: FeedbackOutputMode,
        announcementView: TextView? = null,
        prioritySpeech: Boolean = false,
        queueSpeech: Boolean = false,
    ) {
        if (message.isBlank() && !outputMode.useHaptic) return

        provideFeedback(
            request = FeedbackRequest(
                priority = defaultPriority(level),
                source = FeedbackSource.AR_OBSTACLE,
                alertLevel = level,
                message = message,
                outputMode = outputMode,
                distanceMeters = null,
                interruptCurrent = prioritySpeech && !queueSpeech,
            ),
            announcementView = announcementView,
            queueSpeech = queueSpeech,
        )
    }

    fun stop() {
        hapticController.cancel()
    }

    fun stopSpeech() {
        speechController.stopSpeaking()
    }

    fun speakQueued(
        message: String,
        level: FeedbackAlertLevel,
    ) {
        val spokenMessage = localizedSpeechMessage(message)
        speechController.speak(
            message = spokenMessage,
            level = level,
            queueMode = TextToSpeech.QUEUE_ADD,
            priority = 3,
        )
    }

    fun playObstaclePulse(urgent: Boolean) {
        val level = if (urgent) FeedbackAlertLevel.DANGER else FeedbackAlertLevel.CAUTION
        toneGenerator.startTone(
            ToneGenerator.TONE_PROP_BEEP,
            if (urgent) URGENT_PULSE_TONE_MS else WATCH_PULSE_TONE_MS,
        )
        hapticController.vibrate(
            level = level,
            strength = if (urgent) HapticStrength.STRONG else HapticStrength.MEDIUM,
        )
    }

    fun release() {
        hapticController.cancel()
        toneGenerator.release()
        releaseSpeechController(speechController)
    }

    private fun speakIfNeeded(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView?,
        priority: Int,
        interruptCurrent: Boolean,
        queueSpeech: Boolean = false,
    ) {
        val spokenMessage = localizedSpeechMessage(message)
        if (spokenMessage.isBlank()) return

        if (
            isSpatialScanningMessage(message) &&
            speechController.isSpeakingOrPending(spokenMessage)
        ) {
            Log.d(TAG, "Skipping duplicate spatial scanning speech")
            return
        }

        val announcedByTalkBack = accessibilityAnnouncer.announce(spokenMessage, announcementView)
        if (announcedByTalkBack) return

        val now = SystemClock.elapsedRealtime()
        val shouldInterruptCurrent = interruptCurrent ||
            priority <= 2 ||
            shouldSpatialReadyInterruptScanning(message)
        val activePriority = speechController.currentPriority()
        if (!queueSpeech && !shouldInterruptCurrent && activePriority != null && priority >= activePriority) {
            Log.d(TAG, "Skipping non-interrupting speech priority=$priority active=$activePriority")
            return
        }
        if (!queueSpeech && !shouldInterruptCurrent && priority >= 5 && speechController.hasActiveOrPendingSpeech()) {
            Log.d(TAG, "Skipping low-priority speech while another speech is active")
            return
        }
        if (queueSpeech) {
            speechController.speak(
                message = spokenMessage,
                level = level,
                queueMode = TextToSpeech.QUEUE_ADD,
                priority = priority,
            )
            return
        }

        if (shouldInterruptCurrent) {
            prioritySpeechProtectedUntilMs = now + PRIORITY_SPEECH_PROTECTION_MS
            speechController.speak(
                message = spokenMessage,
                level = level,
                queueMode = TextToSpeech.QUEUE_FLUSH,
                priority = priority,
            )
            return
        }

        if (now < prioritySpeechProtectedUntilMs) {
            Log.d(TAG, "Skipping non-priority speech while priority speech is protected")
            return
        }

        speechController.speak(
            message = spokenMessage,
            level = level,
            queueMode = TextToSpeech.QUEUE_FLUSH,
            priority = priority,
        )
    }

    private fun localizedSpeechMessage(message: String): String {
        if (speechLocale.language != Locale.ENGLISH.language) return message

        return when {
            message.contains("문자 인식 준비") -> "Text recognition is getting ready."
            message.contains("문자 인식을 시작") -> "Starting text recognition."
            message.contains("장면 분석을 준비") -> "Scene analysis is getting ready. Please try again shortly."
            message.contains("보행 가능 방향 확인 필요") -> "Check the safe walking direction."
            message.contains("왼쪽 공간 확보") -> "There is space on the left."
            message.contains("오른쪽 공간 확보") -> "There is space on the right."
            message.contains("전방 공간 확보") -> "There is space ahead."
            message.contains("공간 확보 안됨") -> "No clear space ahead. Stop and check the safe walking direction."
            message.contains("거리 신뢰도") -> "Distance confidence is low. Move the camera slowly so the floor and nearby edges can be recognized."
            message.contains("디버그 설정") -> "The VLM pipeline is turned off in debug settings."
            else -> message
        }
    }

    private fun shouldDropByThrottle(
        request: FeedbackRequest,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = request.throttleKey
        if (key.isBlank()) return false
        val throttleMillis = effectiveThrottleMillis(request)
        if (throttleMillis <= 0L) return false
        val lastMillis = synchronized(throttleLock) {
            lastAnnouncementMillisByKey[key]
        } ?: return false
        return nowMillis - lastMillis < throttleMillis
    }

    private fun markThrottleExecuted(
        request: FeedbackRequest,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val key = request.throttleKey
        if (key.isBlank()) return
        synchronized(throttleLock) {
            lastAnnouncementMillisByKey[key] = nowMillis
        }
    }

    private fun effectiveThrottleMillis(request: FeedbackRequest): Long {
        if (request.throttleMillis > 0L) return request.throttleMillis
        return if (request.priority == 1) {
            FeedbackThresholds.DANGER_THROTTLE_MS
        } else {
            FeedbackThresholds.ANNOUNCE_THROTTLE_MS
        }
    }

    private fun defaultHapticStrength(
        level: FeedbackAlertLevel,
    ): HapticStrength {
        return when (level) {
            FeedbackAlertLevel.DANGER -> HapticStrength.STRONG
            FeedbackAlertLevel.CAUTION -> HapticStrength.MEDIUM
            FeedbackAlertLevel.SAFE -> HapticStrength.LIGHT
        }
    }

    private fun defaultPriority(
        level: FeedbackAlertLevel,
    ): Int {
        return when (level) {
            FeedbackAlertLevel.DANGER -> 1
            FeedbackAlertLevel.CAUTION -> 3
            FeedbackAlertLevel.SAFE -> 5
        }
    }

    private fun isSpatialScanningMessage(message: String): Boolean {
        return message == SPATIAL_SCANNING_MESSAGE
    }

    private fun isSpatialReadyMessage(message: String): Boolean {
        return message == SPATIAL_READY_MESSAGE
    }

    private fun shouldSpatialReadyInterruptScanning(message: String): Boolean {
        return isSpatialReadyMessage(message) &&
            speechController.isActiveSpeech(SPATIAL_SCANNING_MESSAGE)
    }

    companion object {
        private const val TAG = "FeedbackManager"
        private const val SPATIAL_SCANNING_MESSAGE = "공간 인식중입니다 카메라를 천천히 주위를 비춰주세요"
        private const val SPATIAL_READY_MESSAGE = "공간 인식 준비가 되었습니다."
        private const val PRIORITY_SPEECH_PROTECTION_MS = 8_000L
        private const val TONE_VOLUME_PERCENT = 80
        private const val WATCH_PULSE_TONE_MS = 120
        private const val URGENT_PULSE_TONE_MS = 80
        private val speechLock = Any()
        private val throttleLock = Any()
        private val lastAnnouncementMillisByKey = mutableMapOf<String, Long>()
        private var sharedSpeechController: SpeechFeedbackController? = null
        private var sharedSpeechControllerUsers = 0

        private fun acquireSpeechController(context: Context): SpeechFeedbackController {
            return synchronized(speechLock) {
                val controller = sharedSpeechController ?: SpeechFeedbackController(context).also {
                    sharedSpeechController = it
                }
                sharedSpeechControllerUsers += 1
                controller
            }
        }

        private fun releaseSpeechController(controller: SpeechFeedbackController) {
            synchronized(speechLock) {
                sharedSpeechControllerUsers = (sharedSpeechControllerUsers - 1).coerceAtLeast(0)
                if (sharedSpeechControllerUsers == 0 && sharedSpeechController === controller) {
                    controller.release()
                    sharedSpeechController = null
                }
            }
        }
    }
}
