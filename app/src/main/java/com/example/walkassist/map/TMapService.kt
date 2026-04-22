package com.example.walkassist.map

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface TMapService {
    @POST("tmap/routes/pedestrian?version=1&format=json")
    suspend fun fetchPedestrianRoute(
        @Body body: RouteRequest,
        @Header("appKey") appKey: String,
    ): TMapRouteResponse
}
