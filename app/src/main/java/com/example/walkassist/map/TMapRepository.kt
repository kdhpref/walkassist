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
                    startName = "내 위치",
                    endName = destinationName,
                ),
                appKey = apiKey,
            )
            response.toPedestrianRoute()
        }
    }

    private fun TMapRouteResponse.toPedestrianRoute(): PedestrianRoute {
        val features = features.orEmpty()
        val routePoints = features.flatMap { feature ->
            val geometry = feature.geometry ?: return@flatMap emptyList()
            if (geometry.type == "LineString") {
                geometry.coordinates.toRoutePoints()
            } else {
                emptyList()
            }
        }

        if (routePoints.isEmpty()) {
            throw IllegalStateException("TMap route response has no route coordinates.")
        }

        val instructions = features.mapIndexedNotNull { index, feature ->
            val geometry = feature.geometry ?: return@mapIndexedNotNull null
            if (geometry.type != "Point") return@mapIndexedNotNull null

            val point = geometry.coordinates.asCoordinatePairOrNull() ?: return@mapIndexedNotNull null
            val description = feature.properties
                ?.description
                ?.replace(Regex("\\[.*?]"), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null

            if (description.isImportantInstruction(index)) {
                RouteInstruction(
                    point = point,
                    description = description,
                )
            } else {
                null
            }
        }

        val firstProperties = features.firstNotNullOfOrNull { it.properties }

        return PedestrianRoute(
            points = routePoints,
            instructions = instructions,
            totalDistanceMeters = firstProperties?.totalDistance ?: 0,
            totalTimeSeconds = firstProperties?.totalTime ?: 0,
        )
    }

    private fun JsonElement?.toRoutePoints(): List<RouteLatLng> {
        val coordinates = this?.asJsonArrayOrNull() ?: return emptyList()
        return coordinates.mapNotNull { it.asCoordinatePairOrNull() }
    }

    private fun String.isImportantInstruction(index: Int): Boolean {
        return index == 0 ||
            contains("좌회전") ||
            contains("우회전") ||
            contains("횡단보도") ||
            contains("유턴") ||
            contains("도착")
    }

    private fun JsonElement?.asJsonArrayOrNull(): JsonArray? {
        return if (this != null && isJsonArray) asJsonArray else null
    }

    private fun JsonElement?.asCoordinatePairOrNull(): RouteLatLng? {
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
