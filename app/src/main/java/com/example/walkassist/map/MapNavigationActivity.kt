package com.example.walkassist.map

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.walkassist.BuildConfig
import com.example.walkassist.R
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PathOverlay
import com.naver.maps.map.util.FusedLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MapNavigationActivity : AppCompatActivity(), OnMapReadyCallback {
    private data class GuidePoint(
        val location: LatLng,
        val description: String,
        var isAnnounced: Boolean = false,
    )

    private lateinit var locationSource: FusedLocationSource
    private lateinit var repository: TMapRepository
    private lateinit var voiceAnnouncer: RouteVoiceAnnouncer
    private lateinit var stepAdapter: ArrayAdapter<String>
    private val stepList = mutableListOf<String>()
    private var naverMap: NaverMap? = null
    private val destinationMarker = Marker()
    private var pathOverlay = PathOverlay()
    private var isRouteActive = false
    private val guidePoints = mutableListOf<GuidePoint>()
    private val guideMarkers = mutableListOf<Marker>()
    private val fullRouteCoords = mutableListOf<LatLng>()
    private var lastDeviationAnnouncedTime = 0L

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            voiceAnnouncer.speak("위치 권한이 없어 지도 길찾기를 사용할 수 없습니다.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_navigation)

        repository = TMapRepository(apiKey = BuildConfig.TMAP_API_KEY)
        voiceAnnouncer = RouteVoiceAnnouncer(this)
        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)
        stepAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, stepList)
        findViewById<ListView>(R.id.lv_steps).adapter = stepAdapter
        findViewById<ListView>(R.id.lv_steps).setOnItemClickListener { _, _, position, _ ->
            val point = guidePoints.getOrNull(position) ?: return@setOnItemClickListener
            naverMap?.moveCamera(
                CameraUpdate.scrollAndZoomTo(point.location, GUIDE_ZOOM)
                    .animate(CameraAnimation.Fly, CAMERA_ANIMATION_DURATION_MS),
            )
            voiceAnnouncer.speak(point.description)
        }

        NaverMapSdk.getInstance(this).onAuthFailedListener =
            NaverMapSdk.OnAuthFailedListener { exception ->
                Log.e(
                    TAG,
                    "Naver map auth failed: code=${exception.errorCode}, type=${exception::class.java.simpleName}",
                    exception,
                )
            }

        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                supportFragmentManager.beginTransaction().add(R.id.map_fragment, it).commit()
            }
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: NaverMap) {
        naverMap = map
        map.locationSource = locationSource
        map.locationTrackingMode = LocationTrackingMode.Follow
        map.uiSettings.isLocationButtonEnabled = true

        val destinationInput = findViewById<EditText>(R.id.et_destination)
        val searchButton = findViewById<Button>(R.id.btn_search)
        val clearButton = findViewById<Button>(R.id.btn_clear)
        val routePanel = findViewById<View>(R.id.path_list_panel)
        val summaryView = findViewById<TextView>(R.id.tv_summary)

        destinationInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showKeyboard(destinationInput)
        }
        destinationInput.setOnClickListener { showKeyboard(destinationInput) }

        searchButton.setOnClickListener {
            val destinationText = destinationInput.text.toString().trim()
            if (destinationText.isBlank()) {
                voiceAnnouncer.speak("목적지를 입력해 주세요.")
                return@setOnClickListener
            }
            hideKeyboard(destinationInput)
            searchRoute(destinationText, routePanel, summaryView, clearButton)
        }

        clearButton.setOnClickListener {
            stopNavigation(destinationInput, routePanel, summaryView, clearButton)
        }

        map.setOnMapClickListener { _, latLng ->
            clearRoute()
            routePanel.visibility = View.GONE
            summaryView.visibility = View.GONE
            clearButton.visibility = View.GONE
            hideKeyboard(destinationInput)
            destinationMarker.position = latLng
            destinationMarker.map = map
            speakAddressAt(latLng)
        }

        map.addOnLocationChangeListener { location ->
            handleRouteProgress(LatLng(location.latitude, location.longitude))
        }
    }

    private fun searchRoute(
        destinationText: String,
        routePanel: View,
        summaryView: TextView,
        clearButton: Button,
    ) {
        val map = naverMap ?: return
        lifecycleScope.launch {
            val destination = geocode(destinationText)
            if (destination == null) {
                voiceAnnouncer.speak("목적지를 찾을 수 없습니다.")
                return@launch
            }

            val start = map.locationOverlay.position
            if (!start.isCoordinateReady()) {
                voiceAnnouncer.speak("현재 위치를 확인할 수 없습니다.")
                return@launch
            }

            destinationMarker.position = destination
            destinationMarker.map = map
            voiceAnnouncer.speak("보행자 경로를 검색합니다.")

            repository.fetchPedestrianRoute(
                startLongitude = start.longitude,
                startLatitude = start.latitude,
                endLongitude = destination.longitude,
                endLatitude = destination.latitude,
                destinationName = destinationText,
            )
                .onSuccess { route ->
                    renderRoute(
                        route = route,
                        destinationName = destinationText,
                        routePanel = routePanel,
                        summaryView = summaryView,
                        clearButton = clearButton,
                    )
                }
                .onFailure { error ->
                    Log.w(TAG, "Route search failed", error)
                    voiceAnnouncer.speak("경로를 찾을 수 없습니다.")
                }
        }
    }

    private suspend fun geocode(destinationText: String): LatLng? = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(this@MapNavigationActivity, Locale.KOREAN)
                .getFromLocationName(destinationText, 1)
                ?.firstOrNull()
                ?.let { LatLng(it.latitude, it.longitude) }
        }.getOrNull()
    }

    private fun renderRoute(
        route: PedestrianRoute,
        destinationName: String,
        routePanel: View,
        summaryView: TextView,
        clearButton: Button,
    ) {
        val map = naverMap ?: return
        val coords = route.points.map { LatLng(it.latitude, it.longitude) }
        if (coords.isEmpty()) {
            voiceAnnouncer.speak("경로 좌표를 찾을 수 없습니다.")
            return
        }

        clearRoute()
        fullRouteCoords.addAll(coords)
        destinationMarker.position = coords.last()
        destinationMarker.map = map
        pathOverlay = PathOverlay().apply {
            this.coords = coords
            color = ROUTE_COLOR
            width = ROUTE_WIDTH
            outlineWidth = ROUTE_OUTLINE_WIDTH
            outlineColor = Color.WHITE
            this.map = map
        }
        isRouteActive = true

        route.instructions.forEachIndexed { index, instruction ->
            val location = LatLng(instruction.point.latitude, instruction.point.longitude)
            guidePoints.add(GuidePoint(location = location, description = instruction.description))
            stepList.add("${index + 1}. ${instruction.description}")
            Marker().apply {
                position = location
                icon = createNumberMarker(index + 1)
                anchor = PointF(0.5f, 0.5f)
                zIndex = GUIDE_MARKER_Z_INDEX
                this.map = map
                guideMarkers.add(this)
            }
        }
        stepAdapter.notifyDataSetChanged()

        val timeMin = route.totalTimeSeconds / 60
        summaryView.text = "총 ${route.totalDistanceMeters}m | 약 ${timeMin}분"
        summaryView.visibility = View.VISIBLE
        routePanel.visibility = View.VISIBLE
        clearButton.visibility = View.VISIBLE
        map.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(coords), ROUTE_BOUNDS_PADDING))

        val firstGuide = guidePoints.firstOrNull()?.description
        if (firstGuide != null) {
            voiceAnnouncer.speak("안내를 시작합니다. 첫 번째 안내입니다. $firstGuide")
        } else {
            voiceAnnouncer.speak("$destinationName 보행자 경로 안내를 시작합니다.")
        }
    }

    private fun handleRouteProgress(currentLatLng: LatLng) {
        if (!isRouteActive) return

        if (fullRouteCoords.size > 1) {
            val distanceToLine = getShortestDistanceToPath(currentLatLng, fullRouteCoords)
            if (distanceToLine > ROUTE_DEVIATION_METERS) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastDeviationAnnouncedTime > ROUTE_DEVIATION_COOLDOWN_MS) {
                    lastDeviationAnnouncedTime = currentTime
                    voiceAnnouncer.speak("경로를 이탈했습니다. 가야 할 길을 다시 확인해 주세요.")
                }
                return
            }
        }

        for (point in guidePoints) {
            if (!point.isAnnounced && currentLatLng.distanceTo(point.location) <= GUIDE_ANNOUNCE_METERS) {
                voiceAnnouncer.speak(point.description)
                point.isAnnounced = true
                break
            }
        }
    }

    private fun getShortestDistanceToPath(point: LatLng, path: List<LatLng>): Double {
        var minDistance = Double.MAX_VALUE
        for (index in 0 until path.size - 1) {
            val distance = getDistanceToSegment(point, path[index], path[index + 1])
            if (distance < minDistance) {
                minDistance = distance
            }
        }
        return minDistance
    }

    private fun getDistanceToSegment(point: LatLng, segmentStart: LatLng, segmentEnd: LatLng): Double {
        val segmentLengthSquared = segmentStart.distanceTo(segmentEnd).pow(2)
        if (segmentLengthSquared == 0.0) return point.distanceTo(segmentStart)

        val projectionRatio = (
            (point.longitude - segmentStart.longitude) * (segmentEnd.longitude - segmentStart.longitude) +
                (point.latitude - segmentStart.latitude) * (segmentEnd.latitude - segmentStart.latitude)
            ) / (
            (segmentEnd.longitude - segmentStart.longitude).pow(2) +
                (segmentEnd.latitude - segmentStart.latitude).pow(2)
            )

        val clampedRatio = max(0.0, min(1.0, projectionRatio))
        val projection = LatLng(
            segmentStart.latitude + clampedRatio * (segmentEnd.latitude - segmentStart.latitude),
            segmentStart.longitude + clampedRatio * (segmentEnd.longitude - segmentStart.longitude),
        )
        return point.distanceTo(projection)
    }

    private fun createNumberMarker(number: Int): OverlayImage {
        val bitmap = Bitmap.createBitmap(MARKER_SIZE_PX, MARKER_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ROUTE_COLOR
        canvas.drawCircle(MARKER_SIZE_PX / 2f, MARKER_SIZE_PX / 2f, MARKER_SIZE_PX / 2f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(MARKER_SIZE_PX / 2f, MARKER_SIZE_PX / 2f, MARKER_SIZE_PX / 2f - 1.5f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 24f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true

        val text = number.toString()
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, MARKER_SIZE_PX / 2f, MARKER_SIZE_PX / 2f - bounds.centerY(), paint)
        return OverlayImage.fromBitmap(bitmap)
    }

    private fun stopNavigation(
        destinationInput: EditText,
        routePanel: View,
        summaryView: TextView,
        clearButton: Button,
    ) {
        clearRoute()
        destinationInput.setText("")
        routePanel.visibility = View.GONE
        summaryView.visibility = View.GONE
        clearButton.visibility = View.GONE
        voiceAnnouncer.speak("안내를 종료합니다.")
    }

    private fun speakAddressAt(latLng: LatLng) {
        lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                runCatching {
                    Geocoder(this@MapNavigationActivity, Locale.KOREAN)
                        .getFromLocation(latLng.latitude, latLng.longitude, 1)
                        ?.firstOrNull()
                        ?.getAddressLine(0)
                }.getOrNull()
            }
            voiceAnnouncer.speak(address ?: "선택한 위치의 주소를 찾을 수 없습니다.")
        }
    }

    private fun clearRoute() {
        destinationMarker.map = null
        pathOverlay.map = null
        guideMarkers.forEach { it.map = null }
        guideMarkers.clear()
        guidePoints.clear()
        fullRouteCoords.clear()
        stepList.clear()
        stepAdapter.notifyDataSetChanged()
        isRouteActive = false
        lastDeviationAnnouncedTime = 0L
    }

    private fun showKeyboard(input: EditText) {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(input, 0)
    }

    private fun hideKeyboard(input: EditText) {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun LatLng.isCoordinateReady(): Boolean {
        return latitude != 0.0 || longitude != 0.0
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        voiceAnnouncer.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MapNavigationActivity"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
        private const val ROUTE_COLOR = 0xFF2583FF.toInt()
        private const val ROUTE_WIDTH = 18
        private const val ROUTE_OUTLINE_WIDTH = 2
        private const val ROUTE_BOUNDS_PADDING = 300
        private const val MARKER_SIZE_PX = 48
        private const val GUIDE_ZOOM = 17.0
        private const val CAMERA_ANIMATION_DURATION_MS = 800L
        private const val GUIDE_MARKER_Z_INDEX = 100
        private const val ROUTE_DEVIATION_METERS = 40.0
        private const val ROUTE_DEVIATION_COOLDOWN_MS = 10_000L
        private const val GUIDE_ANNOUNCE_METERS = 20.0
    }
}
