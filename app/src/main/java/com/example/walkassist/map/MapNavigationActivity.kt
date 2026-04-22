package com.example.walkassist.map

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.walkassist.BuildConfig
import com.example.walkassist.R
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PathOverlay
import com.naver.maps.map.util.FusedLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MapNavigationActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var locationSource: FusedLocationSource
    private lateinit var repository: TMapRepository
    private lateinit var voiceAnnouncer: RouteVoiceAnnouncer
    private var naverMap: NaverMap? = null
    private val destinationMarker = Marker()
    private var pathOverlay = PathOverlay()
    private var isRouteActive = false

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

        destinationInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showKeyboard(destinationInput)
        }
        destinationInput.setOnClickListener { showKeyboard(destinationInput) }

        destinationMarker.setOnClickListener {
            clearRoute()
            voiceAnnouncer.speak("목적지가 취소되었습니다.")
            true
        }

        searchButton.setOnClickListener {
            val destinationText = destinationInput.text.toString().trim()
            if (destinationText.isBlank()) {
                voiceAnnouncer.speak("목적지를 입력해 주세요.")
                return@setOnClickListener
            }
            hideKeyboard(destinationInput)
            searchRoute(destinationText)
        }

        map.setOnMapClickListener { _, latLng ->
            clearRoute()
            hideKeyboard(destinationInput)
            destinationMarker.position = latLng
            destinationMarker.map = map
            speakAddressAt(latLng)
        }
    }

    private fun searchRoute(destinationText: String) {
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
            voiceAnnouncer.speak("보행자 경로를 탐색합니다.")

            val result = repository.fetchPedestrianRoute(
                startLongitude = start.longitude,
                startLatitude = start.latitude,
                endLongitude = destination.longitude,
                endLatitude = destination.latitude,
                destinationName = destinationText,
            )
            result
                .onSuccess { route -> renderRoute(route, destinationText) }
                .onFailure { voiceAnnouncer.speak("경로를 찾을 수 없습니다.") }
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

    private fun renderRoute(route: PedestrianRoute, destinationName: String) {
        val map = naverMap ?: return
        val coords = route.points.map { LatLng(it.latitude, it.longitude) }
        if (coords.isEmpty()) {
            voiceAnnouncer.speak("경로 좌표를 찾을 수 없습니다.")
            return
        }

        val destination = coords.last()
        destinationMarker.position = destination
        destinationMarker.map = map
        pathOverlay.map = null
        pathOverlay = PathOverlay().apply {
            this.coords = coords
            color = ROUTE_COLOR
            width = ROUTE_WIDTH
            this.map = map
        }
        isRouteActive = true
        map.moveCamera(CameraUpdate.scrollTo(destination).animate(CameraAnimation.Easing))

        val distanceKm = route.totalDistanceMeters / 1000.0
        val timeMin = route.totalTimeSeconds / 60
        voiceAnnouncer.speak(
            "$destinationName 보행자 경로를 찾았습니다. 총 거리 ${String.format("%.1f", distanceKm)}킬로미터, 예상 시간 ${timeMin}분입니다.",
        )
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
        isRouteActive = false
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
        private const val ROUTE_COLOR = 0xFF2DAA57.toInt()
        private const val ROUTE_WIDTH = 15
    }
}
