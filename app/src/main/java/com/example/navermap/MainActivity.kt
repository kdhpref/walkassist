package com.example.navermap

import android.Manifest
import android.content.Context
import android.graphics.*
import android.location.Geocoder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.annotations.SerializedName
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.*
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PathOverlay
import com.naver.maps.map.util.FusedLocationSource
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.*
import kotlin.math.*

data class GuidePoint(val location: LatLng, val description: String, var isAnnounced: Boolean = false)

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap
    private val destMarker = Marker()
    private var pathOverlay = PathOverlay()
    private var tts: TextToSpeech? = null
    private var isRouteActive = false

    private val guidePoints = mutableListOf<GuidePoint>()
    private val guideMarkers = mutableListOf<Marker>()
    private lateinit var stepAdapter: ArrayAdapter<String>
    private val stepList = mutableListOf<String>()

    // 경로 이탈 감지용 변수
    private val fullRouteCoords = mutableListOf<LatLng>()
    private var lastDeviationAnnouncedTime = 0L // 연속 경고 방지 타이머

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN
        }

        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
        locationSource = FusedLocationSource(this, 1000)

        val lvSteps = findViewById<ListView>(R.id.lv_steps)
        stepAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, stepList)
        lvSteps.adapter = stepAdapter

        lvSteps.setOnItemClickListener { _, _, position, _ ->
            if (guidePoints.size > position) {
                val point = guidePoints[position]
                val targetLatLng = point.location
                val cameraUpdate = CameraUpdate.scrollAndZoomTo(targetLatLng, 17.0)
                    .animate(CameraAnimation.Fly, 800)
                naverMap.moveCamera(cameraUpdate)

                tts?.speak(point.description, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                supportFragmentManager.beginTransaction().add(R.id.map_fragment, it).commit()
            }
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        naverMap.locationSource = locationSource
        naverMap.locationTrackingMode = LocationTrackingMode.Follow
        naverMap.uiSettings.isLocationButtonEnabled = true

        val etDestination = findViewById<EditText>(R.id.et_destination)
        val btnSearch = findViewById<ImageButton>(R.id.btn_search)
        val btnClear = findViewById<ImageButton>(R.id.btn_clear)
        val pathCard = findViewById<View>(R.id.path_list_card)
        val tvSummary = findViewById<TextView>(R.id.tv_summary)

        // 실시간 GPS 수신 리스너 (경로 이탈 음성 경고 + 20m 턴 가이드)
        naverMap.addOnLocationChangeListener { location ->
            if (!isRouteActive) return@addOnLocationChangeListener

            val currentLatLng = LatLng(location.latitude, location.longitude)

            // 1. 경로 이탈 검사 (진동 로직 제거, 순수 음성 경고만 유지)
            if (fullRouteCoords.isNotEmpty()) {
                val distanceToLine = getShortestDistanceToPath(currentLatLng, fullRouteCoords)

                // 지정된 전체 경로선에서 수직 거리로 40m 이상 벗어났을 때
                if (distanceToLine > 40.0) {
                    val currentTime = System.currentTimeMillis()
                    // 경고 멘트가 너무 연속으로 나와 겹치지 않도록 10초 간격으로 제어
                    if (currentTime - lastDeviationAnnouncedTime > 10000) {
                        lastDeviationAnnouncedTime = currentTime

                        // [수정] 순수 음성 안내로만 경고 메시지 출력 (진동 함수 제거됨)
                        tts?.speak("경로를 이탈했습니다. 가야 할 길을 다시 확인해 주세요.", TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                    return@addOnLocationChangeListener // 이탈 상황에서는 일반 구간 안내를 건너뜀
                }
            }

            // 2. 정상 주행 중 20m 이내 접근 시 구간 안내 TTS
            for (point in guidePoints) {
                if (!point.isAnnounced && currentLatLng.distanceTo(point.location) <= 20.0) {
                    tts?.speak(point.description, TextToSpeech.QUEUE_FLUSH, null, null)
                    point.isAnnounced = true
                    break
                }
            }
        }

        btnSearch.setOnClickListener {
            val destName = etDestination.text.toString()
            if (destName.isEmpty()) return@setOnClickListener

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etDestination.windowToken, 0)

            Geocoder(this, Locale.KOREAN).getFromLocationName(destName, 1)?.firstOrNull()?.let {
                val destLatLng = LatLng(it.latitude, it.longitude)
                destMarker.position = destLatLng
                destMarker.iconTintColor = Color.RED
                destMarker.map = naverMap

                fetchRoute(naverMap.locationOverlay.position.longitude, naverMap.locationOverlay.position.latitude,
                    destLatLng.longitude, destLatLng.latitude, destName)

                isRouteActive = true
                btnClear.visibility = View.VISIBLE
                pathCard.visibility = View.VISIBLE
                tvSummary.visibility = View.VISIBLE
            }
        }

        btnClear.setOnClickListener {
            stopNavigation(etDestination, btnClear, pathCard, tvSummary)
        }
    }

    /**
     * 내 위치와 전체 경로선 간의 최단 수직 거리를 구하는 수학 알고리즘
     */
    private fun getShortestDistanceToPath(point: LatLng, path: List<LatLng>): Double {
        var minDistance = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val distance = getDistanceToSegment(point, path[i], path[i + 1])
            if (distance < minDistance) {
                minDistance = distance
            }
        }
        return minDistance
    }

    private fun getDistanceToSegment(p: LatLng, s1: LatLng, s2: LatLng): Double {
        val l2 = s1.distanceTo(s2).pow(2)
        if (l2 == 0.0) return p.distanceTo(s1)

        val t = ((p.longitude - s1.longitude) * (s2.longitude - s1.longitude) +
                (p.latitude - s1.latitude) * (s2.latitude - s1.latitude)) /
                ((s2.longitude - s1.longitude).pow(2) + (s2.latitude - s1.latitude).pow(2))

        val clampedT = max(0.0, min(1.0, t))
        val projection = LatLng(
            s1.latitude + clampedT * (s2.latitude - s1.latitude),
            s1.longitude + clampedT * (s2.longitude - s1.longitude)
        )
        return p.distanceTo(projection)
    }

    private fun createNumberMarker(number: Int): OverlayImage {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#2583FF")
        canvas.drawCircle(size/2f, size/2f, size/2f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(size/2f, size/2f, size/2f - 1.5f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 24f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        val text = number.toString()
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val yPos = (size/2f) - bounds.centerY()
        canvas.drawText(text, size/2f, yPos, paint)
        return OverlayImage.fromBitmap(bitmap)
    }

    private fun stopNavigation(et: EditText, btnC: ImageButton, card: View, summary: TextView) {
        isRouteActive = false
        guideMarkers.forEach { it.map = null }
        guideMarkers.clear()
        guidePoints.clear()
        fullRouteCoords.clear()
        stepList.clear()
        stepAdapter.notifyDataSetChanged()
        pathOverlay.map = null
        destMarker.map = null
        et.setText("")
        btnC.visibility = View.GONE
        card.visibility = View.GONE
        summary.visibility = View.GONE
        tts?.speak("안내를 종료합니다.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun fetchRoute(startX: Double, startY: Double, endX: Double, endY: Double, destName: String) {
        val service = Retrofit.Builder()
            .baseUrl("https://apis.openapi.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TMapService::class.java)

        val request = RouteRequest(startX, startY, endX, endY, "내 위치", destName)
        val appKey = "sz9U8KSayD7ceDEYaScvu61V7rtmsOexvbGqyBPh"

        service.fetchPedestrianRoute(request, appKey).enqueue(object : Callback<TMapRouteResponse> {
            override fun onResponse(call: Call<TMapRouteResponse>, response: Response<TMapRouteResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val coords = mutableListOf<LatLng>()

                    runOnUiThread {
                        guideMarkers.forEach { it.map = null }
                        guideMarkers.clear()
                        guidePoints.clear()
                        fullRouteCoords.clear()
                        stepList.clear()
                    }

                    var pointCount = 1
                    body.features?.forEach { feature ->
                        if (feature.geometry?.type == "Point") {
                            val rawPoint = feature.geometry.coordinates as? List<Double>
                            val desc = feature.properties?.description?.replace(Regex("\\[.*?\\]"), "")?.trim()

                            if (rawPoint != null && desc != null) {
                                val isImportant = desc.contains("좌회전") || desc.contains("우회전") ||
                                        desc.contains("횡단보도") || desc.contains("유턴") ||
                                        desc.contains("도착") || pointCount == 1

                                if (isImportant) {
                                    val latLng = LatLng(rawPoint[1], rawPoint[0])
                                    guidePoints.add(GuidePoint(latLng, desc))
                                    stepList.add("${pointCount}. $desc")

                                    runOnUiThread {
                                        val m = Marker().apply {
                                            position = latLng
                                            icon = createNumberMarker(pointCount)
                                            anchor = PointF(0.5f, 0.5f)
                                            zIndex = 100
                                            map = naverMap
                                        }
                                        guideMarkers.add(m)
                                    }
                                    pointCount++
                                }
                            }
                        }
                        if (feature.geometry?.type == "LineString") {
                            (feature.geometry.coordinates as? List<List<Double>>)?.forEach {
                                val currentCoord = LatLng(it[1], it[0])
                                coords.add(currentCoord)
                                fullRouteCoords.add(currentCoord)
                            }
                        }
                    }

                    runOnUiThread {
                        stepAdapter.notifyDataSetChanged()
                        val props = body.features?.get(0)?.properties
                        findViewById<TextView>(R.id.tv_summary).text = "총 ${props?.totalDistance}m | 약 ${props?.totalTime?.div(60)}분"

                        if (coords.isNotEmpty()) {
                            pathOverlay.map = null
                            pathOverlay = PathOverlay().apply {
                                this.coords = coords
                                this.color = Color.parseColor("#2583FF")
                                this.width = 18
                                this.outlineWidth = 2
                                this.outlineColor = Color.WHITE
                                this.map = naverMap
                            }
                            naverMap.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(coords), 300))
                        }

                        if (guidePoints.isNotEmpty()) {
                            val startMsg = "안내를 시작합니다. 첫 번째 안내입니다. ${guidePoints[0].description}"
                            tts?.speak(startMsg, TextToSpeech.QUEUE_FLUSH, null, null)
                        } else {
                            tts?.speak("안내를 시작합니다.", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                }
            }
            override fun onFailure(call: Call<TMapRouteResponse>, t: Throwable) {}
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }

    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
}

interface TMapService {
    @POST("tmap/routes/pedestrian?version=1&format=json")
    fun fetchPedestrianRoute(@Body body: RouteRequest, @Header("appKey") appKey: String): Call<TMapRouteResponse>
}

data class TMapRouteResponse(val features: List<Feature>?)
data class Feature(val geometry: Geometry?, val properties: Properties?)
data class Geometry(val type: String?, val coordinates: Any?)
data class Properties(val totalDistance: Int?, val totalTime: Int?, val description: String?)
data class RouteRequest(
    @SerializedName("startX") val startX: Double, @SerializedName("startY") val startY: Double,
    @SerializedName("endX") val endX: Double, @SerializedName("endY") val endY: Double,
    @SerializedName("startName") val startName: String, @SerializedName("endName") val endName: String
)