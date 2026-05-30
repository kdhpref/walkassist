package com.example.walkassist.map

import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SharedRouteGuidePoint(
    val location: LatLng,
    val description: String,
)

data class SharedRouteNavigationState(
    val active: Boolean = false,
    val destinationName: String = "",
    val route: List<LatLng> = emptyList(),
    val guidePoints: List<SharedRouteGuidePoint> = emptyList(),
) {
    fun nextGuidePoint(currentLocation: LatLng): SharedRouteGuidePoint? {
        return guidePoints.firstOrNull { currentLocation.distanceTo(it.location) > NEXT_GUIDE_PASSED_METERS }
            ?: guidePoints.lastOrNull()
    }

    companion object {
        private const val NEXT_GUIDE_PASSED_METERS = 8.0
    }
}

object SharedRouteNavigation {
    private val _state = MutableStateFlow(SharedRouteNavigationState())
    val state: StateFlow<SharedRouteNavigationState> = _state.asStateFlow()

    fun currentState(): SharedRouteNavigationState = _state.value

    fun publishRoute(
        destinationName: String,
        route: List<LatLng>,
        guidePoints: List<SharedRouteGuidePoint>,
    ) {
        _state.value = SharedRouteNavigationState(
            active = route.size >= 2,
            destinationName = destinationName,
            route = route,
            guidePoints = guidePoints,
        )
    }

    fun clear() {
        _state.value = SharedRouteNavigationState()
    }
}
