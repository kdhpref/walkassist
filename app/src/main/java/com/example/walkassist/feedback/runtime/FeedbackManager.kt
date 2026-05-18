package com.example.walkassist.feedback.runtime

import android.content.Context
import android.widget.TextView
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackOutputMode
import com.example.walkassist.feedback.core.FeedbackRequest
import com.example.walkassist.feedback.core.FeedbackSource
import com.example.walkassist.feedback.core.HapticStrength

/**
 * 실제 사용자 피드백을 실행하는 매니저.
 *
 * 역할:
 * - TalkBack 접근성 안내
 * - TTS 음성 안내
 * - 진동 안내
 *
 * FeedbackRequest의 outputMode를 보고
 * 음성 / 진동을 선택적으로 실행합니다.
 *
 * 주의:
 * - Activity에서 TextToSpeech, Vibrator를 직접 호출하지 않도록 하기 위한 실행 담당 클래스입니다.
 * - FeedbackPolicy는 요청 생성만 담당합니다.
 * - FeedbackQueue는 우선순위와 반복 제한만 담당합니다.
 * - FeedbackManager는 실제 출력만 담당합니다.
 */
class FeedbackManager(context: Context) {
    private val accessibilityAnnouncer = AccessibilityAnnouncer(context)
    private val hapticController = HapticFeedbackController(context)
    private val speechController = SpeechFeedbackController(context)

    /**
     * FeedbackRequest를 직접 받아서 처리하는 통합 함수.
     *
     * 출력 모드에 따라 분기합니다.
     *
     * useHaptic = true  → 진동 실행
     * useSpeech = true  → TalkBack 또는 TTS 실행
     *
     * 예:
     * - 1순위 장애물: 음성 + 강한 진동
     * - 2순위 장애물: 음성 + 중간 진동
     * - 3순위 장애물: 음성 + 약한 진동
     * - 4순위 장애물: 진동만
     * - OCR: 음성만
     */
    fun provideFeedback(
        request: FeedbackRequest,
        announcementView: TextView? = null,
    ) {
        // 출력할 내용이 전혀 없으면 바로 종료합니다.
        if (
            request.message.isBlank() &&
            !request.outputMode.useHaptic &&
            !request.outputMode.useSpeech
        ) {
            return
        }

        // 출력 모드에 따라 진동 실행
        if (request.outputMode.useHaptic) {
            hapticController.vibrate(
                request.alertLevel,
                request.outputMode.hapticStrength,
            )
        }

        // 출력 모드에 따라 음성 안내 실행
        if (request.outputMode.useSpeech && request.message.isNotBlank()) {
            val announced = accessibilityAnnouncer.announce(
                request.message,
                announcementView,
            )

            if (!announced) {
                speechController.speak(
                    request.message,
                    request.alertLevel,
                )
            }
        }
    }

    /**
     * 기존 코드 호환용 함수.
     *
     * 예전 코드에서 message와 level만 넘기고 있다면
     * 이 함수가 기본 출력 모드를 적용해서 처리합니다.
     */
    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView? = null,
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
        )
    }

    /**
     * message, level, outputMode를 직접 받는 함수.
     *
     * FeedbackRequest 없이도 출력 모드를 지정해서 사용할 수 있게 유지합니다.
     */
    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        outputMode: FeedbackOutputMode,
        announcementView: TextView? = null,
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
            ),
            announcementView = announcementView,
        )
    }

    /**
     * 위험도에 따른 기본 진동 강도.
     *
     * 기존 provideFeedback(message, level)을 호출하는 코드가 있을 때 사용됩니다.
     *
     */
    private fun defaultHapticStrength(
        level: FeedbackAlertLevel,
    ): HapticStrength {
        return when (level) {
            FeedbackAlertLevel.DANGER -> HapticStrength.STRONG
            FeedbackAlertLevel.CAUTION -> HapticStrength.MEDIUM
            FeedbackAlertLevel.SAFE -> HapticStrength.LIGHT
        }
    }

    /**
     * 위험도에 따른 기본 우선순위.
     *
     * 기존 provideFeedback(message, level)을 호출하는 코드가 있을 때 사용됩니다.
     *
     * B안 기준:
     * - DANGER = 1순위
     * - CAUTION = 3순위
     * - SAFE = 5순위
     */
    private fun defaultPriority(
        level: FeedbackAlertLevel,
    ): Int {
        return when (level) {
            FeedbackAlertLevel.DANGER -> 1
            FeedbackAlertLevel.CAUTION -> 3
            FeedbackAlertLevel.SAFE -> 5
        }
    }

    /**
     * 현재 진행 중인 피드백을 중단합니다.
     *
     * FeedbackQueue에서 높은 우선순위 안내가 들어왔을 때
     * 기존 안내를 멈추기 위한 호환용 함수입니다.
     */
    fun stop() {
        hapticController.cancel()
    }

    /**
     * 리소스 해제.
     *
     * Activity 또는 ViewModel이 종료될 때 호출합니다.
     */
    fun release() {
        hapticController.cancel()
        speechController.release()
    }
}