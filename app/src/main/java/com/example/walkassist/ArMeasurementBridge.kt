package com.example.walkassist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ArMeasurementBridge {
    private val _state = MutableStateFlow(ArMeasurementState())
    val state: StateFlow<ArMeasurementState> = _state.asStateFlow()

    fun publish(state: ArMeasurementState) {
        _state.value = state
    }
}
