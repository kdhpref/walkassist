package com.example.walkassist.feedback.core

/**
 * 하나의 피드백 요청을 표현하는 데이터 클래스입니다.
 *
 * FeedbackPolicy가 이 객체를 만들고,
 * FeedbackQueue는 이 객체를 큐에 넣어 우선순위 / 반복 제한 / 실행 순서를 관리합니다.
 */
data class FeedbackRequest(
    val priority: Int,
    val source: FeedbackSource,
    val alertLevel: FeedbackAlertLevel,
    val message: String = "",
    val outputMode: FeedbackOutputMode,
    val distanceMeters: Float? = null,

    /**
     * 현재 실행 중인 낮은 우선순위 안내를 중단할지 여부입니다.
     */
    val interruptCurrent: Boolean = false,

    /**
     * 같은 안내 반복을 막기 위한 key입니다.
     */
    val throttleKey: String = buildDefaultThrottleKey(
        source = source,
        priority = priority,
        alertLevel = alertLevel,
        message = message,
        distanceMeters = distanceMeters
    ),

    /**
     * 같은 throttleKey 안내를 다시 허용하기 전까지 막을 시간입니다.
     *
     * 0L이면 FeedbackQueue에서 기본 throttle 시간을 사용할 수 있습니다.
     */
    val throttleMillis: Long = 0L,
) : Comparable<FeedbackRequest> {

    /**
     * 숫자가 작을수록 높은 우선순위입니다.
     */
    override fun compareTo(other: FeedbackRequest): Int {
        return priority.compareTo(other.priority)
    }
}

private fun buildDefaultThrottleKey(
    source: FeedbackSource,
    priority: Int,
    alertLevel: FeedbackAlertLevel,
    message: String,
    distanceMeters: Float?
): String {
    return if (distanceMeters != null) {
        "$source:$priority:$alertLevel"
    } else {
        "$source:$priority:$alertLevel:${message.take(80)}"
    }
}