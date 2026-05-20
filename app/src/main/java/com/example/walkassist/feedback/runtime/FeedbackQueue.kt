package com.example.walkassist.feedback.runtime

import com.example.walkassist.feedback.core.FeedbackRequest
import com.example.walkassist.feedback.core.FeedbackThresholds
import java.util.PriorityQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 전역 안내 우선순위 큐.
 *
 * FeedbackPolicy가 만든 FeedbackRequest를 받아서
 * 우선순위 / 반복 제한 / 중복 제거 / 실행 순서를 관리합니다.
 *
 * 실제 TTS, 진동, 접근성 안내 실행은 FeedbackManager에게 맡깁니다.
 *
 * 빌드 안정화 기준:
 * - FeedbackRequest 타입을 정상 import해서 사용합니다.
 * - `FeedbackRequest.kt` 같은 파일명을 타입처럼 쓰지 않습니다.
 * - FeedbackSource enum을 when으로 직접 분기하지 않아 SENSOR_STATUS 추가 여부와 무관하게 빌드되게 합니다.
 * - request.throttleMillis가 0이면 FeedbackThresholds 기본값을 사용합니다.
 */
class FeedbackQueue(
    private val manager: FeedbackManager,
    private val scope: CoroutineScope,
) {
    /**
     * 대기 중인 안내 요청 큐입니다.
     *
     * FeedbackRequest는 Comparable을 구현하므로
     * priority 숫자가 작은 요청이 먼저 실행됩니다.
     */
    private val queue = PriorityQueue<FeedbackRequest>()

    /**
     * 큐 상태 동시 접근을 막기 위한 Mutex입니다.
     */
    private val mutex = Mutex()

    /**
     * throttleKey별 마지막 실행 또는 등록 시각입니다.
     */
    private val lastAnnouncementMillisByKey = mutableMapOf<String, Long>()

    /**
     * 현재 실행 중인 요청입니다.
     */
    private var currentRequest: FeedbackRequest? = null

    /**
     * 현재 안내 처리를 담당하는 Job입니다.
     */
    private var processingJob: Job? = null

    /**
     * 새 안내 요청을 큐에 넣습니다.
     */
    fun enqueue(request: FeedbackRequest) {
        scope.launch {
            mutex.withLock {
                // 5순위이면서 음성/진동이 모두 꺼진 요청은 출력할 필요가 없습니다.
                if (request.priority >= 5 &&
                    !request.outputMode.useSpeech &&
                    !request.outputMode.useHaptic
                ) {
                    return@withLock
                }

                // 같은 안내가 너무 짧은 시간 안에 반복되면 버립니다.
                if (shouldDropByThrottle(request)) {
                    return@withLock
                }

                // 같은 의미의 요청이 이미 대기 중이면 기존 대기 요청을 제거합니다.
                removeDuplicatePendingRequests(request)

                // 4순위 중 음성 없이 진동만 쓰는 요청은 큐 대기 없이 즉시 처리합니다.
                if (request.priority == 4 &&
                    !request.outputMode.useSpeech &&
                    request.outputMode.useHaptic
                ) {
                    markThrottleExecuted(request)
                    manager.provideFeedback(request)
                    return@withLock
                }

                val current = currentRequest

                // 숫자가 작을수록 높은 우선순위입니다.
                // interruptCurrent가 true인 요청만 현재 안내를 중단할 수 있습니다.
                if (current != null &&
                    request.interruptCurrent &&
                    request.priority < current.priority
                ) {
                    processingJob?.cancel()
                    currentRequest = null
                    queue.add(request)
                    processNextLocked()
                    return@withLock
                }

                queue.add(request)

                if (currentRequest == null) {
                    processNextLocked()
                }
            }
        }
    }

    /**
     * 큐에서 다음 요청을 꺼내 실행합니다.
     *
     * 이 함수는 반드시 mutex가 잡힌 상태에서 호출합니다.
     */
    private fun processNextLocked() {
        val next = queue.poll() ?: run {
            currentRequest = null
            return
        }

        // 실행 직전 2차 throttle.
        if (shouldDropByThrottle(next)) {
            processNextLocked()
            return
        }

        currentRequest = next

        processingJob = scope.launch {
            markThrottleExecuted(next)

            manager.provideFeedback(next)

            val estimatedDurationMs = if (next.outputMode.useSpeech) {
                estimateSpeechDurationMs(next.message)
            } else {
                300L
            }

            delay(estimatedDurationMs)

            mutex.withLock {
                currentRequest = null
                processNextLocked()
            }
        }
    }

    /**
     * 현재 요청이 throttle 제한에 걸리는지 판단합니다.
     */
    private fun shouldDropByThrottle(
        request: FeedbackRequest,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val key = request.throttleKey
        if (key.isBlank()) return false

        val lastMillis = lastAnnouncementMillisByKey[key] ?: return false

        val throttleMillis = effectiveThrottleMillis(request)
        if (throttleMillis <= 0L) return false

        val elapsedMillis = nowMillis - lastMillis
        return elapsedMillis < throttleMillis
    }

    /**
     * 실제 실행된 요청의 throttle 시간을 기록합니다.
     */
    private fun markThrottleExecuted(
        request: FeedbackRequest,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val key = request.throttleKey
        if (key.isBlank()) return

        lastAnnouncementMillisByKey[key] = nowMillis
    }

    /**
     * request.throttleMillis가 있으면 그 값을 우선 사용하고,
     * 없으면 기존 FeedbackThresholds 기본값을 사용합니다.
     *
     * 이 방식은 기존 FeedbackPolicy.kt가 아직 throttleMillis를 넣지 않아도
     * FeedbackQueue가 빌드되고 기본 반복 제한이 동작하게 해줍니다.
     */
    private fun effectiveThrottleMillis(
        request: FeedbackRequest
    ): Long {
        if (request.throttleMillis > 0L) {
            return request.throttleMillis
        }

        return if (request.priority == 1) {
            FeedbackThresholds.DANGER_THROTTLE_MS
        } else {
            FeedbackThresholds.ANNOUNCE_THROTTLE_MS
        }
    }

    /**
     * 같은 의미의 대기 요청을 큐에서 제거합니다.
     */
    private fun removeDuplicatePendingRequests(
        request: FeedbackRequest
    ) {
        val requestKey = request.throttleKey
        if (requestKey.isBlank()) return

        val iterator = queue.iterator()

        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (pending.throttleKey == requestKey) {
                iterator.remove()
            }
        }
    }

    /**
     * 음성 안내의 예상 지속 시간을 계산합니다.
     */
    private fun estimateSpeechDurationMs(
        text: String
    ): Long {
        return (text.length * 250L)
            .coerceAtLeast(800L)
            .coerceAtMost(8_000L)
    }

    /**
     * 큐 상태를 초기화합니다.
     */
    fun clear() {
        processingJob?.cancel()
        queue.clear()
        currentRequest = null
        lastAnnouncementMillisByKey.clear()
    }
}