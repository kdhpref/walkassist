package com.example.walkassist.map

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TMapRepository(
    private val apiKey: String,
    private val service: TMapService = RetrofitClient.service,
) {
    suspend fun fetchPedestrianRoute(
        startLongitude: Double,
        startLatitude: Double,
        endLongitude: Double,
        endLatitude: Double,
        destinationName: String,
    ): Result<PedestrianRoute> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("TMap API key is not configured."))
        }

        return runCatching {
            val response = service.fetchPedestrianRoute(
                body = RouteRequest(
                    startX = startLongitude,
                    startY = startLatitude,
                    endX = endLongitude,
                    endY = endLatitude,
                    startName = "현재 위치",
                    endName = destinationName,
                ),
                appKey = apiKey,
            )
            response.toPedestrianRoute()
        }
    }

    private fun TMapRouteResponse.toPedestrianRoute(): PedestrianRoute {
        val routePoints = features.orEmpty().flatMap { feature ->
            val geometry = feature.geometry ?: return@flatMap emptyList()
            geometry.coordinates.toRoutePoints(geometry.type)
        }

        if (routePoints.isEmpty()) {
            throw IllegalStateException("TMap route response has no route coordinates.")
        }

        val firstProperties = features
            .orEmpty()
            .firstNotNullOfOrNull { it.properties }

        return PedestrianRoute(
            points = routePoints,
            totalDistanceMeters = firstProperties?.totalDistance ?: 0,
            totalTimeSeconds = firstProperties?.totalTime ?: 0,
        )
    }

    private fun JsonElement?.toRoutePoints(type: String?): List<RouteLatLng> {
        val coordinates = this?.asJsonArrayOrNull() ?: return emptyList()
        return when (type) {
            "LineString" -> coordinates.mapNotNull { it.asCoordinatePairOrNull() }
            "Point" -> listOfNotNull(coordinates.asCoordinatePairOrNull())
            else -> emptyList()
        }
    }

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? {
        return if (isJsonArray) asJsonArray else null
    }

    private fun JsonElement.asCoordinatePairOrNull(): RouteLatLng? {
        val array = asJsonArrayOrNull() ?: return null
        if (array.size() < 2) return null
        val longitude = array[0].asDoubleOrNull() ?: return null
        val latitude = array[1].asDoubleOrNull() ?: return null
        return RouteLatLng(latitude = latitude, longitude = longitude)
    }

    private fun JsonElement.asDoubleOrNull(): Double? {
        return runCatching { asDouble }.getOrNull()
    }

    private object RetrofitClient {
        val service: TMapService = Retrofit.Builder()
            .baseUrl("https://apis.openapi.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TMapService::class.java)
    }
}
