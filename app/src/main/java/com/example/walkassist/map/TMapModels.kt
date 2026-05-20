package com.example.walkassist.map

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class RouteRequest(
    @SerializedName("startX") val startX: Double,
    @SerializedName("startY") val startY: Double,
    @SerializedName("endX") val endX: Double,
    @SerializedName("endY") val endY: Double,
    @SerializedName("startName") val startName: String,
    @SerializedName("endName") val endName: String,
)

data class TMapRouteResponse(
    val features: List<TMapFeature>?,
)

data class TMapFeature(
    val geometry: TMapGeometry?,
    val properties: TMapProperties?,
)

data class TMapGeometry(
    val type: String?,
    val coordinates: JsonElement?,
)

data class TMapProperties(
    val totalDistance: Int?,
    val totalTime: Int?,
    val description: String?,
)

data class PedestrianRoute(
    val points: List<RouteLatLng>,
    val instructions: List<RouteInstruction>,
    val totalDistanceMeters: Int,
    val totalTimeSeconds: Int,
)

data class RouteInstruction(
    val point: RouteLatLng,
    val description: String,
)

data class RouteLatLng(
    val latitude: Double,
    val longitude: Double,
)
