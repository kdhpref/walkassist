package com.example.walkassist

import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.walkassist.map.RouteCameraGuidance
import com.example.walkassist.map.RouteCameraGuidanceEngine
import com.example.walkassist.map.SharedRouteNavigation
import com.example.walkassist.ocr.OneShotOcrReader
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Plane
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Point
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.Track
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.RecordingFailedException
import com.google.ar.core.exceptions.UnavailableException
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.naver.maps.geometry.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class WalkAssistArFragment : Fragment(), GLSurfaceView.Renderer {
    private data class BoxDepthEstimate(
        val distanceMeters: Float?,
        val isReference: Boolean = false,
    )

    private data class LaneDistances(
        val floor: Float?,
        val wall: Float?,
        val depth: Float?,
        val rawDepth: Float?,
        val collision: Float?,
    )

    private data class RawDepthCorridorResult(
        val hits: List<CorridorHit>,
        val plannedSampleCount: Int,
        val samples: List<WalkingZoneDepthSample>,
    )

    private data class WorldMapLaneMetrics(
        val leftDistance: Float?,
        val centerDistance: Float?,
        val rightDistance: Float?,
        val collisionDistance: Float?,
        val leftOccupancyRatio: Float,
        val centerOccupancyRatio: Float,
        val rightOccupancyRatio: Float,
        val leftFreeSpaceMeters: Float?,
        val centerFreeSpaceMeters: Float?,
        val rightFreeSpaceMeters: Float?,
    )

    private data class CrosswalkFrameSnapshot(
        val pattern: CrosswalkPatternResult,
        val timestampNanos: Long,
    )

    private data class CorridorHit(
        val distanceMeters: Float,
        val lateralMeters: Float,
        val forwardMeters: Float,
        val worldX: Float,
        val worldY: Float,
        val worldZ: Float,
        val viewXRatio: Float,
        val viewYRatio: Float,
        val source: HitSource,
        val observationConfidence: Float,
        val isBeyondReliableRange: Boolean = false,
    )

    private data class RawDepthSample(
        val depthMillimeters: Int,
        val confidence: Float,
    )

    private data class ArDepthFrame(
        val rawDepthImage: Image?,
        val rawDepthConfidenceImage: Image?,
        val rawDepthTimestampNanos: Long?,
        val isNewRawDepth: Boolean,
        val status: DepthAcquisitionStatus,
    ) : Closeable {
        val hasRawDepth: Boolean
            get() = rawDepthImage != null && rawDepthConfidenceImage != null

        override fun close() {
            rawDepthConfidenceImage?.close()
            rawDepthImage?.close()
        }
    }

    private enum class DepthAcquisitionStatus {
        DISABLED,
        AVAILABLE,
        NOT_YET_AVAILABLE,
        UNAVAILABLE,
    }

    private data class TtcRiskResult(
        val label: String,
        val timeToCollisionSeconds: Float?,
    )

    private data class ObjectMotionMemory(
        val centerXRatio: Float,
        val distanceMeters: Float?,
        val timestampNanos: Long,
    )

    private data class VlmVisionState(
        val interpretation: VlmSceneInterpretation,
        val timestampNanos: Long,
    )

    private data class GeospatialRuntimeStatus(
        val statusLabel: String,
        val earthStateLabel: String = "",
        val streetscapeGeometryCount: Int = 0,
    )

    private enum class HitSource {
        FLOOR,
        WALL,
        DEPTH,
    }

    private var smoothedFloorDistance: Float? = null
    private var smoothedWallDistance: Float? = null
    private var smoothedDepthDistance: Float? = null
    private var smoothedRawDepthDistance: Float? = null
    private var smoothedCollisionDistance: Float? = null
    private var smoothedLeftDistance: Float? = null
    private var smoothedCenterDistance: Float? = null
    private var smoothedRightDistance: Float? = null

    private var lastTimestampNs: Long? = null
    private var lastRawDepthImageTimestampNanos: Long? = null
    private var lastDepthMeasurementLogAtMs = 0L
    private var lastCameraX: Float? = null
    private var lastCameraY: Float? = null
    private var lastCameraZ: Float? = null
    private var configuredGeospatialRequested: Boolean? = null
    private var geospatialConfiguredActive = false
    private var geospatialConfigureStatusLabel = "off"
    private var lastGeospatialConfigureAttemptAtMs = 0L

    private val objectAnalyzerDelegate = lazy { ObjectAnalyzer(requireContext().applicationContext) }
    private val objectAnalyzer by objectAnalyzerDelegate
    private val vlmSceneInterpreter by lazy { WalkAssistVlmFactory.create(requireContext().applicationContext) }
    private val routeGuidanceEngine = RouteCameraGuidanceEngine()
    private var oneShotOcrReader: OneShotOcrReader? = null
    private val objectTracker = ObjectTracker()
    private val crosswalkPatternDetector = CrosswalkPatternDetector()
    private val detectorExecutor = Executors.newSingleThreadExecutor()
    private val vlmExecutor = Executors.newSingleThreadExecutor()
    private val detectionInFlight = AtomicBoolean(false)
    private val oneShotOcrRequested = AtomicBoolean(false)
    private val oneShotOcrInFlight = AtomicBoolean(false)
    private val oneShotVlmRequested = AtomicBoolean(false)
    private val oneShotVlmInFlight = AtomicBoolean(false)
    private var lastDetectionStartedAtMs = 0L
    private var lastObjectDetections: List<ObjectOverlayDetection> = emptyList()
    @Volatile
    private var lastVlmVisionState: VlmVisionState? = null
    @Volatile
    private var lastCrosswalkFrameSnapshot = CrosswalkFrameSnapshot(
        pattern = EMPTY_CROSSWALK_RESULT,
        timestampNanos = 0L,
    )
    private var smoothedCrosswalkScore = 0f
    private val crosswalkDetectionHistory = ArrayDeque<Boolean>()
    private val objectMotionMemory = mutableMapOf<Int, ObjectMotionMemory>()
    private val worldLocalMap = WorldLocalMap(
        halfRangeMeters = 5f,
        cellSizeMeters = 0.2f,
    )

    private val collisionHistory = ArrayDeque<Float>()
    private val directionHistory = ArrayDeque<String>()
    private var stableDirection = "searching"
    var onOneShotOcrResult: ((String) -> Unit)? = null
    var onOneShotVlmResult: ((String) -> Unit)? = null
    var onLiveVlmGuidanceResult: ((String) -> Unit)? = null
    var onLiveVlmStateChanged: ((Boolean) -> Unit)? = null
    var recordReplayOnSessionStart: Boolean = false
    var playbackDatasetUri: Uri? = null
    private var activeRecordingDatasetUri: Uri? = null
    private var customReplayTrackEnabled = false
    private var planeMeshDebugVisible = false
    private var installRequested = false
    private var glSurfaceView: GLSurfaceView? = null
    private var arSession: Session? = null
    private val backgroundRenderer = ArCameraBackgroundRenderer()
    private var cameraTextureId = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var frameArgbPixels = IntArray(0)
    @Volatile
    private var oneShotVlmRequestedAtMs: Long = 0L
    private val liveVlmSessionActive = AtomicBoolean(false)
    private var lastLiveVlmFrameSentAtMs = 0L

    fun requestOneShotOcr() {
        if (oneShotOcrRequested.get() || oneShotOcrInFlight.get()) {
            dispatchOneShotOcrResult("이미 문자 인식 중입니다.")
            return
        }
        oneShotOcrRequested.set(true)
    }

    fun requestOneShotVlm() {
        Log.d(TAG, "VLM button requested")
        if (WalkAssistSettings.vlmModelOption(requireContext().applicationContext) ==
            VlmModelOption.GEMINI_2_5_FLASH_LIVE_API
        ) {
            toggleGeminiLiveSession()
            return
        }
        if (oneShotVlmRequested.get() || oneShotVlmInFlight.get()) {
            dispatchOneShotVlmResult("이미 전방 화면을 분석 중입니다. 잠시만 기다려 주세요.")
            return
        }
        oneShotVlmRequestedAtMs = SystemClock.elapsedRealtime()
        oneShotVlmRequested.set(true)
    }

    fun requestLiveVlmGuidance() {
        if (WalkAssistSettings.vlmModelOption(requireContext().applicationContext) !=
            VlmModelOption.GEMINI_2_5_FLASH_LIVE_API
        ) {
            dispatchOneShotVlmResult("Gemini 2.5 Live 모델을 선택해야 사용할 수 있습니다.")
            return
        }
        if (!liveVlmSessionActive.get()) {
            dispatchOneShotVlmResult("먼저 VLM 시작을 눌러 Live 영상 스트림을 켜 주세요.")
            return
        }
        if (!vlmSceneInterpreter.requestLiveGuidance()) {
            dispatchOneShotVlmResult("Live 영상 응답을 기다리는 중입니다. 잠시 후 다시 눌러 주세요.")
        }
    }

    private fun toggleGeminiLiveSession() {
        if (liveVlmSessionActive.get()) {
            vlmSceneInterpreter.stopLiveSession()
            liveVlmSessionActive.set(false)
            onLiveVlmStateChanged?.invoke(false)
            dispatchOneShotVlmResult("Gemini 2.5 Live 세션을 종료했습니다.")
            return
        }

        val started = vlmSceneInterpreter.startLiveSession(
            onText = { message ->
                if (message.isNotBlank()) {
                    dispatchLiveVlmGuidanceResult(message)
                }
            },
            onError = { message ->
                liveVlmSessionActive.set(false)
                onLiveVlmStateChanged?.invoke(false)
                dispatchOneShotVlmResult("Gemini 2.5 Live 세션 오류. $message")
            },
            onTiming = { event ->
                val appContext = requireContext().applicationContext
                VlmInvocationLogger.appendLive(
                    context = appContext,
                    selectedModelName = WalkAssistSettings.vlmModelOption(appContext).displayName,
                    eventName = event.eventName,
                    elapsedMs = event.elapsedMs,
                    audioBytes = event.audioBytes,
                    transcriptChars = event.transcriptChars,
                    success = event.errorMessage == null,
                    errorMessage = event.errorMessage,
                )
                Log.d(
                    TAG,
                    "Gemini Live timing event=${event.eventName} elapsedMs=${event.elapsedMs} " +
                        "audioBytes=${event.audioBytes} transcriptChars=${event.transcriptChars}",
                )
            },
        )
        if (started) {
            liveVlmSessionActive.set(true)
            lastLiveVlmFrameSentAtMs = 0L
            onLiveVlmStateChanged?.invoke(true)
            dispatchOneShotVlmResult("Gemini 2.5 Live 세션을 시작합니다.")
        } else {
            dispatchOneShotVlmResult("Gemini 2.5 Live 세션을 사용할 수 없습니다.")
        }
    }

    fun setPlaneMeshDebugVisible(visible: Boolean) {
        planeMeshDebugVisible = visible
        updatePlaneMeshRendererVisibility()
    }

    private fun updatePlaneMeshRendererVisibility(visible: Boolean = planeMeshDebugVisible) {
        planeMeshDebugVisible = visible
    }

    private fun createSessionConfig(
        session: Session,
        geospatialEnabled: Boolean = false,
    ): Config {
        return Config(session).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                depthMode = Config.DepthMode.AUTOMATIC
            }
            if (
                geospatialEnabled &&
                session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
            ) {
                geospatialMode = Config.GeospatialMode.ENABLED
                streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
            } else {
                geospatialMode = Config.GeospatialMode.DISABLED
                streetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED
            }
        }
    }

    private fun configureSessionForFlags(
        session: Session,
        debugFlags: DebugPipelineFlags,
        force: Boolean = false,
    ) {
        val geospatialRequested = debugFlags.geospatialEnabled
        if (!force && configuredGeospatialRequested == geospatialRequested) return

        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastGeospatialConfigureAttemptAtMs < GEOSPATIAL_RECONFIGURE_RETRY_MS) return
        lastGeospatialConfigureAttemptAtMs = nowMs

        val geospatialSupported = runCatching {
            session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
        }.getOrDefault(false)
        val config = createSessionConfig(
            session = session,
            geospatialEnabled = geospatialRequested && geospatialSupported,
        )

        runCatching {
            session.configure(config)
        }.onSuccess {
            configuredGeospatialRequested = geospatialRequested
            geospatialConfiguredActive = geospatialRequested && geospatialSupported
            geospatialConfigureStatusLabel = when {
                !geospatialRequested -> "off"
                geospatialSupported -> "enabled"
                else -> "unsupported"
            }
            Log.i(TAG, "ARCore session configured geospatial=$geospatialConfigureStatusLabel")
        }.onFailure { error ->
            geospatialConfiguredActive = false
            geospatialConfigureStatusLabel = geospatialConfigureFailureLabel(error)
            Log.w(TAG, "Failed to configure ARCore geospatial", error)
        }
    }

    private fun geospatialConfigureFailureLabel(error: Throwable): String {
        val className = error::class.java.simpleName
        return when {
            className.contains("FineLocationPermissionNotGranted", ignoreCase = true) -> "needs precise location"
            className.contains("UnsupportedConfiguration", ignoreCase = true) -> "unsupported"
            error.message.isNullOrBlank() -> "configure failed"
            else -> "configure failed: ${error.message}"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return GLSurfaceView(requireContext()).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@WalkAssistArFragment)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            glSurfaceView = this
        }
    }

    override fun onResume() {
        super.onResume()
        ensureSession()
        glSurfaceView?.onResume()
        runCatching {
            arSession?.resume()
        }.onFailure { error ->
            Log.e(TAG, "Failed to resume ARCore session", error)
            ArMeasurementBridge.publish(
                ArMeasurementState(
                    trackingLabel = "unavailable",
                    guidanceLabel = "ARCore session failed to start.",
                    statusLabel = error.message ?: "ARCore resume failed.",
                    statusLevel = ArStatusLevel.WARNING,
                    note = "Check Google Play Services for AR and camera permission.",
                ),
            )
        }
    }

    override fun onPause() {
        if (liveVlmSessionActive.get()) {
            vlmSceneInterpreter.stopLiveSession()
            liveVlmSessionActive.set(false)
            onLiveVlmStateChanged?.invoke(false)
        }
        arSession?.pause()
        glSurfaceView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        glSurfaceView = null
        super.onDestroyView()
    }

    private fun ensureSession() {
        if (arSession != null) return

        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }

            arSession = Session(requireContext()).also { session ->
                if (cameraTextureId != 0) {
                    session.setCameraTextureName(cameraTextureId)
                }
                configurePlaybackIfRequested(session)
                configureSessionForFlags(
                    session = session,
                    debugFlags = WalkAssistSettings.debugPipelineFlags(requireContext()),
                    force = true,
                )
                configureRecordingIfRequested(session)
            }
        } catch (error: UnavailableException) {
            Log.e(TAG, "ARCore is unavailable", error)
            ArMeasurementBridge.publish(
                ArMeasurementState(
                    trackingLabel = "unavailable",
                    guidanceLabel = "ARCore is not available on this device.",
                    statusLabel = error.message ?: "ARCore unavailable.",
                    statusLevel = ArStatusLevel.WARNING,
                    note = "Install or update Google Play Services for AR.",
                ),
            )
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        cameraTextureId = backgroundRenderer.textureId
        arSession?.setCameraTextureName(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        arSession?.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arSession ?: return
        if (cameraTextureId == 0) return

        try {
            session.setCameraTextureName(cameraTextureId)
            session.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            configureSessionForFlags(
                session = session,
                debugFlags = WalkAssistSettings.debugPipelineFlags(requireContext()),
            )
            val frame = session.update()
            backgroundRenderer.draw(frame)
            publishFrameState(frame)
        } catch (_: CameraNotAvailableException) {
            Log.w(TAG, "ARCore camera is not available for this frame")
        } catch (error: IllegalStateException) {
            Log.w(TAG, "ARCore frame update skipped", error)
        }
    }

    fun stopArCoreReplayRecording() {
        val session = arSession
        if (session == null || session.recordingStatus != RecordingStatus.OK) {
            ArCoreReplayController.updateMessage("진행 중인 ARCore 녹화가 없습니다.")
            return
        }
        try {
            session.stopRecording()
            activeRecordingDatasetUri?.let { ArCoreReplayController.saveLastDataset(requireContext(), it) }
            ArCoreReplayController.update(
                ArCoreReplayUiState(
                    mode = ArCoreReplayMode.LIVE,
                    recordingStatus = session.recordingStatus.name,
                    playbackStatus = session.playbackStatus.name,
                    datasetUri = activeRecordingDatasetUri,
                    message = "ARCore 데이터셋 녹화를 저장했습니다.",
                ),
            )
        } catch (error: RecordingFailedException) {
            ArCoreReplayController.update(
                ArCoreReplayUiState(
                    mode = ArCoreReplayMode.LIVE,
                    recordingStatus = session.recordingStatus.name,
                    playbackStatus = session.playbackStatus.name,
                    datasetUri = activeRecordingDatasetUri,
                    message = "ARCore 녹화 중지 실패: ${error.message ?: "알 수 없는 오류"}",
                ),
            )
        }
    }

    override fun onDestroy() {
        runCatching {
            detectorExecutor.submit {
                if (objectAnalyzerDelegate.isInitialized()) {
                    objectAnalyzer.close()
                }
            }.get(1, TimeUnit.SECONDS)
        }
        detectorExecutor.shutdownNow()
        vlmExecutor.shutdownNow()
        liveVlmSessionActive.set(false)
        vlmSceneInterpreter.close()
        oneShotOcrReader?.close()
        arSession?.close()
        arSession = null
        super.onDestroy()
    }

    private fun configurePlaybackIfRequested(session: Session) {
        val datasetUri = playbackDatasetUri ?: return
        try {
            session.setPlaybackDatasetUri(datasetUri)
            ArCoreReplayController.update(
                ArCoreReplayUiState(
                    mode = ArCoreReplayMode.PLAYBACK,
                    recordingStatus = session.recordingStatus.name,
                    playbackStatus = session.playbackStatus.name,
                    datasetUri = datasetUri,
                    message = "ARCore 데이터셋 재생을 준비했습니다.",
                ),
            )
        } catch (error: Exception) {
            ArCoreReplayController.update(
                ArCoreReplayUiState(
                    mode = ArCoreReplayMode.LIVE,
                    recordingStatus = session.recordingStatus.name,
                    playbackStatus = session.playbackStatus.name,
                    datasetUri = datasetUri,
                    message = "ARCore 데이터셋 재생 준비 실패: ${error.message ?: "알 수 없는 오류"}",
                ),
            )
        }
    }

    private fun configureRecordingIfRequested(session: Session) {
        if (!recordReplayOnSessionStart || session.recordingStatus == RecordingStatus.OK) return

        val datasetUri = ArCoreReplayController.createDatasetUri(requireContext())
        activeRecordingDatasetUri = datasetUri
        val recordingConfig = RecordingConfig(session)
            .setMp4DatasetUri(datasetUri)
            .setAutoStopOnPause(true)

        runCatching {
            val metadata = "WalkAssist ARCore replay metadata track v1"
                .toByteArray(StandardCharsets.UTF_8)
            val track = Track(session)
                .setId(REPLAY_METADATA_TRACK_ID)
                .setMimeType("application/vnd.walkassist.replay+json")
                .setMetadata(ByteBuffer.wrap(metadata))
            recordingConfig.addTrack(track)
            customReplayTrackEnabled = true
        }.onFailure {
            customReplayTrackEnabled = false
            Log.w(TAG, "Custom ARCore replay metadata track is unavailable", it)
        }

        try {
            session.startRecording(recordingConfig)
            ArCoreReplayController.saveLastDataset(requireContext(), datasetUri)
            ArCoreReplayController.update(
                ArCoreReplayUiState(
                    mode = ArCoreReplayMode.RECORDING,
                    recordingStatus = session.recordingStatus.name,
                    playbackStatus = session.playbackStatus.name,
                    datasetUri = datasetUri,
                    message = "ARCore 데이터셋 녹화를 시작했습니다.",
                ),
            )
        } catch (error: Exception) {
            activeRecordingDatasetUri = null
            customReplayTrackEnabled = false
            ArCoreReplayController.update(
                ArCoreReplayUiState(
                    mode = ArCoreReplayMode.LIVE,
                    recordingStatus = session.recordingStatus.name,
                    playbackStatus = session.playbackStatus.name,
                    datasetUri = datasetUri,
                    message = "ARCore 녹화 시작 실패: ${error.message ?: "알 수 없는 오류"}",
                ),
            )
        }
    }

    private fun updateReplayRuntimeState(frame: Frame) {
        val session = arSession ?: return
        val recordingStatus = session.recordingStatus
        val playbackStatus = session.playbackStatus
        val isRecording = recordingStatus == RecordingStatus.OK
        val isPlayback = playbackStatus == PlaybackStatus.OK || playbackStatus == PlaybackStatus.FINISHED
        if (!isRecording && !isPlayback && ArCoreReplayController.currentState().mode == ArCoreReplayMode.LIVE) return

        val datasetUri = activeRecordingDatasetUri ?: playbackDatasetUri
        val playbackMetadataCount = if (playbackStatus == PlaybackStatus.OK) {
            runCatching { frame.getUpdatedTrackData(REPLAY_METADATA_TRACK_ID).size }.getOrDefault(0)
        } else {
            0
        }
        val message = when {
            isRecording -> "ARCore 데이터셋 녹화 중입니다."
            playbackStatus == PlaybackStatus.OK && playbackMetadataCount > 0 ->
                "ARCore 데이터셋 재생 중입니다. 앱 메타데이터 ${playbackMetadataCount}건을 읽었습니다."
            playbackStatus == PlaybackStatus.OK -> "ARCore 데이터셋 재생 중입니다."
            playbackStatus == PlaybackStatus.FINISHED -> "ARCore 데이터셋 재생이 끝났습니다."
            playbackStatus == PlaybackStatus.IO_ERROR -> "ARCore 데이터셋 재생 중 I/O 오류가 발생했습니다."
            else -> ArCoreReplayController.currentState().message
        }

        ArCoreReplayController.update(
            ArCoreReplayUiState(
                mode = when {
                    isRecording -> ArCoreReplayMode.RECORDING
                    isPlayback -> ArCoreReplayMode.PLAYBACK
                    else -> ArCoreReplayMode.LIVE
                },
                recordingStatus = recordingStatus.name,
                playbackStatus = playbackStatus.name,
                datasetUri = datasetUri,
                message = message,
            ),
        )
    }

    private fun recordReplayAppMetadata(frame: Frame) {
        if (!customReplayTrackEnabled) return
        val session = arSession ?: return
        if (session.recordingStatus != RecordingStatus.OK) return

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return
        val pose = camera.displayOrientedPose
        val metadata = buildString {
            append('{')
            append("\"timestampNanos\":").append(frame.timestamp).append(',')
            append("\"tracking\":\"").append(camera.trackingState.name).append("\",")
            append("\"pose\":[")
            append(pose.tx()).append(',').append(pose.ty()).append(',').append(pose.tz())
            append("]")
            append('}')
        }.toByteArray(StandardCharsets.UTF_8)

        runCatching {
            frame.recordTrackData(REPLAY_METADATA_TRACK_ID, ByteBuffer.wrap(metadata))
        }.onFailure {
            customReplayTrackEnabled = false
            Log.w(TAG, "Failed to record ARCore replay metadata", it)
        }
    }

    private fun acquireArDepthFrame(
        frame: Frame,
        rawDepthEnabled: Boolean,
    ): ArDepthFrame {
        if (!rawDepthEnabled) {
            return ArDepthFrame(
                rawDepthImage = null,
                rawDepthConfidenceImage = null,
                rawDepthTimestampNanos = null,
                isNewRawDepth = false,
                status = DepthAcquisitionStatus.DISABLED,
            )
        }

        var rawDepthImage: Image? = null
        var confidenceImage: Image? = null
        return try {
            rawDepthImage = frame.acquireRawDepthImage16Bits()
            confidenceImage = frame.acquireRawDepthConfidenceImage()
            val timestampNanos = rawDepthImage.timestamp
            val isNewRawDepth = timestampNanos != lastRawDepthImageTimestampNanos
            lastRawDepthImageTimestampNanos = timestampNanos
            ArDepthFrame(
                rawDepthImage = rawDepthImage,
                rawDepthConfidenceImage = confidenceImage,
                rawDepthTimestampNanos = timestampNanos,
                isNewRawDepth = isNewRawDepth,
                status = DepthAcquisitionStatus.AVAILABLE,
            )
        } catch (_: NotYetAvailableException) {
            confidenceImage?.close()
            rawDepthImage?.close()
            ArDepthFrame(
                rawDepthImage = null,
                rawDepthConfidenceImage = null,
                rawDepthTimestampNanos = null,
                isNewRawDepth = false,
                status = DepthAcquisitionStatus.NOT_YET_AVAILABLE,
            )
        } catch (_: IllegalStateException) {
            confidenceImage?.close()
            rawDepthImage?.close()
            ArDepthFrame(
                rawDepthImage = null,
                rawDepthConfidenceImage = null,
                rawDepthTimestampNanos = null,
                isNewRawDepth = false,
                status = DepthAcquisitionStatus.UNAVAILABLE,
            )
        }
    }

    private fun maybeAppendDepthMeasurementLog(record: ArDepthMeasurementRecord) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDepthMeasurementLogAtMs < DEPTH_MEASUREMENT_LOG_INTERVAL_MS) return
        lastDepthMeasurementLogAtMs = nowMs
        ArDepthMeasurementLogger.append(requireContext().applicationContext, record)
    }

    private fun geospatialRuntimeStatus(
        session: Session?,
        debugFlags: DebugPipelineFlags,
    ): GeospatialRuntimeStatus {
        if (!debugFlags.geospatialEnabled) {
            return GeospatialRuntimeStatus(statusLabel = "off")
        }
        if (!geospatialConfiguredActive) {
            return GeospatialRuntimeStatus(statusLabel = geospatialConfigureStatusLabel)
        }

        val earthStateLabel = runCatching {
            val earth = session?.earth ?: return@runCatching ""
            "earth=${earth.trackingState.name.lowercase()} ${earth.earthState.name.lowercase()}"
        }.getOrDefault("")
        val streetscapeCount = runCatching {
            session
                ?.getAllTrackables(StreetscapeGeometry::class.java)
                .orEmpty()
                .count { it.trackingState == TrackingState.TRACKING }
        }.getOrDefault(0)

        return GeospatialRuntimeStatus(
            statusLabel = geospatialConfigureStatusLabel,
            earthStateLabel = earthStateLabel,
            streetscapeGeometryCount = streetscapeCount,
        )
    }

    private fun computeGeospatialRouteGuidance(
        session: Session?,
        debugFlags: DebugPipelineFlags,
    ): RouteCameraGuidance? {
        if (!debugFlags.geospatialEnabled || !geospatialConfiguredActive) return null
        val sharedRoute = SharedRouteNavigation.currentState()
        if (!sharedRoute.active || sharedRoute.route.size < 2) return null

        val earth = runCatching { session?.earth }.getOrNull() ?: return null
        if (earth.trackingState != TrackingState.TRACKING) return null
        val pose = runCatching { earth.cameraGeospatialPose }.getOrNull() ?: return null
        val latitude = pose.latitude
        val longitude = pose.longitude
        val heading = cameraHeadingDegrees(pose) ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || !heading.isFinite()) return null

        val currentLocation = LatLng(latitude, longitude)
        val nextGuidePoint = sharedRoute.nextGuidePoint(currentLocation)?.location
            ?: sharedRoute.route.lastOrNull()

        return routeGuidanceEngine.compute(
            route = sharedRoute.route,
            currentLocation = currentLocation,
            cameraHeadingDegrees = heading,
            nextGuidePoint = nextGuidePoint,
        )
    }

    private fun cameraHeadingDegrees(pose: GeospatialPose): Double? {
        val quaternion = pose.eastUpSouthQuaternion
        if (quaternion.size < 4) return null
        val qx = quaternion[0].toDouble()
        val qy = quaternion[1].toDouble()
        val qz = quaternion[2].toDouble()
        val qw = quaternion[3].toDouble()

        // Rotate the camera's local -Z optical axis into East-Up-South coordinates.
        val east = -2.0 * ((qx * qz) + (qw * qy))
        val south = -1.0 + (2.0 * qx * qx) + (2.0 * qy * qy)
        val north = -south
        val horizontalMagnitude = sqrt((east * east) + (north * north))
        if (horizontalMagnitude < 0.12) return null

        var heading = Math.toDegrees(atan2(east, north))
        if (heading < 0.0) heading += 360.0
        return heading
    }

    private fun computeCrosswalkFusion(
        frame: Frame,
        debugFlags: DebugPipelineFlags,
    ): CrosswalkFusionResult {
        val snapshot = lastCrosswalkFrameSnapshot
        val pattern = if (
            snapshot.timestampNanos > 0L &&
            frame.timestamp >= snapshot.timestampNanos &&
            frame.timestamp - snapshot.timestampNanos <= CROSSWALK_PATTERN_STALE_NANOS
        ) {
            snapshot.pattern
        } else {
            EMPTY_CROSSWALK_RESULT
        }
        val mapCue = computeCrosswalkMapCue(arSession, debugFlags)
        val rawFusion = crosswalkPatternDetector.fuse(
            pattern = pattern,
            mapCue = mapCue,
            previousScore = smoothedCrosswalkScore,
        )
        smoothedCrosswalkScore = rawFusion.score
        crosswalkDetectionHistory += rawFusion.detected
        while (crosswalkDetectionHistory.size > CROSSWALK_HISTORY_SIZE) {
            crosswalkDetectionHistory.removeFirst()
        }
        val stableDetected = if (rawFusion.detected) {
            crosswalkDetectionHistory.count { it } >= 2 || rawFusion.score >= 0.72f
        } else {
            crosswalkDetectionHistory.count { it } >= 3 && rawFusion.score >= 0.48f
        }
        return rawFusion.copy(detected = stableDetected)
    }

    private fun computeCrosswalkMapCue(
        session: Session?,
        debugFlags: DebugPipelineFlags,
    ): CrosswalkMapCue {
        if (!debugFlags.geospatialEnabled || !geospatialConfiguredActive) return CrosswalkMapCue(active = false, confidence = 0f)
        val sharedRoute = SharedRouteNavigation.currentState()
        if (!sharedRoute.active) return CrosswalkMapCue(active = false, confidence = 0f)
        val crosswalkGuidePoints = sharedRoute.guidePoints.filter { point ->
            point.description.contains("횡단보도") ||
                point.description.contains("crosswalk", ignoreCase = true)
        }
        if (crosswalkGuidePoints.isEmpty()) return CrosswalkMapCue(active = false, confidence = 0f)

        val earth = runCatching { session?.earth }.getOrNull() ?: return CrosswalkMapCue(active = false, confidence = 0f)
        if (earth.trackingState != TrackingState.TRACKING) return CrosswalkMapCue(active = false, confidence = 0f)
        val pose = runCatching { earth.cameraGeospatialPose }.getOrNull() ?: return CrosswalkMapCue(active = false, confidence = 0f)
        val heading = cameraHeadingDegrees(pose)
        if (!pose.latitude.isFinite() || !pose.longitude.isFinite()) return CrosswalkMapCue(active = false, confidence = 0f)
        val currentLocation = LatLng(pose.latitude, pose.longitude)

        val best = crosswalkGuidePoints
            .map { point ->
                val distance = currentLocation.distanceTo(point.location)
                val bearing = bearingDegrees(currentLocation, point.location)
                val delta = heading?.let { normalizeDeltaDegrees(bearing - it) }
                val distanceScore = (1.0 - (distance / CROSSWALK_MAP_PRIOR_RADIUS_METERS)).coerceIn(0.0, 1.0)
                val headingScore = delta
                    ?.let { (1.0 - (abs(it) / CROSSWALK_HEADING_PRIOR_DEGREES)).coerceIn(0.0, 1.0) }
                    ?: 0.55
                val confidence = ((distanceScore * 0.68) + (headingScore * 0.32)).toFloat().coerceIn(0f, 1f)
                Triple(point, distance, delta) to confidence
            }
            .filter { (_, confidence) -> confidence > 0f }
            .maxByOrNull { it.second }
            ?: return CrosswalkMapCue(active = false, confidence = 0f)

        val distance = best.first.second
        val delta = best.first.third
        val headingOk = delta == null || abs(delta) <= CROSSWALK_HEADING_PRIOR_DEGREES
        val active = distance <= CROSSWALK_MAP_PRIOR_RADIUS_METERS && headingOk && best.second >= 0.22f
        return CrosswalkMapCue(
            active = active,
            confidence = if (active) best.second else 0f,
            distanceMeters = distance.toFloat(),
            headingDeltaDegrees = delta?.toFloat(),
        )
    }

    private fun bearingDegrees(from: LatLng, to: LatLng): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(toLat)
        val x = (cos(fromLat) * sin(toLat)) - (sin(fromLat) * cos(toLat) * cos(deltaLon))
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0.0) bearing += 360.0
        return bearing
    }

    private fun normalizeDeltaDegrees(value: Double): Double {
        var normalized = ((value + 540.0) % 360.0) - 180.0
        if (normalized == -180.0) normalized = 180.0
        return normalized
    }

    private fun routeGuidanceDetail(guidance: RouteCameraGuidance?): String {
        if (guidance == null) return ""
        val headingDelta = guidance.headingDeltaDegrees?.let { "${it.toInt()}deg" } ?: "--"
        val routeBearing = guidance.routeBearingDegrees?.let { "${it.toInt()}deg" } ?: "--"
        val pathDistance = guidance.distanceToPathMeters.toInt().coerceAtLeast(0)
        return "경로 $routeBearing / 차이 $headingDelta / 이탈 ${pathDistance}m"
    }

    private fun publishFrameState(frame: Frame) {
        val debugFlags = WalkAssistSettings.debugPipelineFlags(requireContext())
        val camera = frame.camera
        val geospatialStatus = geospatialRuntimeStatus(arSession, debugFlags)
        val routeGuidance = computeGeospatialRouteGuidance(arSession, debugFlags)
        updatePlaneMeshRendererVisibility(debugFlags.arCoreHitTestEnabled && planeMeshDebugVisible)
        updateReplayRuntimeState(frame)
        recordReplayAppMetadata(frame)
        scheduleVisionAnalysis(frame, debugFlags)
        val trackedPlanes = if (debugFlags.arCoreHitTestEnabled) {
            arSession?.getAllTrackables(Plane::class.java).orEmpty()
        } else {
            emptyList()
        }
        val horizontalPlaneCount = trackedPlanes.count {
            it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        val verticalPlaneCount = trackedPlanes.count {
            it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.VERTICAL
        }

        if (camera.trackingState != TrackingState.TRACKING) {
            directionHistory.clear()
            stableDirection = "searching"
            maybeAppendDepthMeasurementLog(
                ArDepthMeasurementRecord(
                    frameTimestampNanos = frame.timestamp,
                    trackingState = camera.trackingState.name,
                    trackingFailureReason = camera.trackingFailureReason.name,
                    sensingConfidenceScore = 0,
                    acquisitionStatus = DepthAcquisitionStatus.DISABLED.name,
                    rawDepthTimestampNanos = null,
                    isNewRawDepth = false,
                    rawDepthWidth = null,
                    rawDepthHeight = null,
                    pitchDownDegrees = null,
                    motionMetersPerSecond = null,
                    plannedCorridorSamples = 0,
                    validCorridorHits = 0,
                    corridorValidRatio = 0f,
                    validGridCells = 0,
                    gridAverageConfidence = 0f,
                    objectCount = if (debugFlags.yoloEnabled) lastObjectDetections.size else 0,
                    objectDepthCount = 0,
                    leftNearestMeters = null,
                    centerNearestMeters = null,
                    rightNearestMeters = null,
                    rawDepthNearestMeters = null,
                ),
            )
            ArMeasurementBridge.publish(
                ArMeasurementState(
                    trackingLabel = camera.trackingState.name.lowercase(),
                    trackingFailureLabel = camera.trackingFailureReason.name.lowercase().replace('_', ' '),
                    horizontalPlaneCount = horizontalPlaneCount,
                    verticalPlaneCount = verticalPlaneCount,
                    sensingConfidenceScore = 0,
                    geospatialStatusLabel = geospatialStatus.statusLabel,
                    geospatialEarthStateLabel = geospatialStatus.earthStateLabel,
                    geospatialStreetscapeGeometryCount = geospatialStatus.streetscapeGeometryCount,
                    routeRealityGuidanceLabel = routeGuidance?.message.orEmpty(),
                    routeRealityGuidanceDetail = routeGuidanceDetail(routeGuidance),
                    routeRealityGuidanceAction = routeGuidance?.action?.name?.lowercase().orEmpty(),
                    routeRealityGuidanceSource = if (routeGuidance != null) "geospatial" else "",
                    guidanceLabel = "Scan the walking area slowly.",
                    statusLabel = "Move the phone slowly while depth samples stabilize.",
                    statusLevel = ArStatusLevel.INFO,
                    objectDetections = if (debugFlags.yoloEnabled) lastObjectDetections else emptyList(),
                    planeDetections = emptyList(),
                    planePolygons = emptyList(),
                    worldMapRangeMeters = worldLocalMap.rangeMeters(),
                    worldMapCellSizeMeters = worldLocalMap.cellSizeMeters(),
                    worldMapObservationCount = if (debugFlags.localMapEnabled) {
                        worldLocalMap.totalObservationCount()
                    } else {
                        0
                    },
                    worldMapConfidenceScore = if (debugFlags.localMapEnabled) {
                        worldLocalMap.averageConfidenceScore()
                    } else {
                        0
                    },
                    note = "ARCore needs visible feature points and steady motion.",
                ),
            )
            return
        }

        val pitchDownDegrees = computePitchDownDegrees(frame)
        acquireArDepthFrame(frame, debugFlags.rawDepthEnabled).use { depthFrame ->
        val corridorHits = if (debugFlags.arCoreHitTestEnabled) sampleWorldCorridor(frame) else emptyList()
        val vlmVisionState = if (debugFlags.vlmEnabled) currentVlmVisionState(frame.timestamp) else null
        val crosswalkFusion = computeCrosswalkFusion(frame, debugFlags)
        val rawDepthCorridorResult = if (debugFlags.rawDepthEnabled) {
            sampleRawDepthCorridor(frame, depthFrame)
        } else {
            RawDepthCorridorResult(hits = emptyList(), plannedSampleCount = 0, samples = emptyList())
        }
        val rawDepthHits = rawDepthCorridorResult.hits
        val depthGridCells = if (debugFlags.rawDepthEnabled) {
            sampleRawDepthGrid(
                frame = frame,
                depthFrame = depthFrame,
                geospatialLongRangeEnabled = debugFlags.geospatialEnabled && geospatialConfiguredActive,
            )
        } else {
            emptyList()
        }
        val cameraPose = frame.camera.displayOrientedPose
        val mapObservations = (corridorHits + rawDepthHits).map {
            WorldMapObservation(
                worldX = it.worldX,
                worldZ = it.worldZ,
                source = it.source.name,
                strength = obstacleStrengthForHit(
                    source = it.source,
                    relativeHeightMeters = it.worldY - cameraPose.ty(),
                ),
                confidence = it.observationConfidence,
            )
        }
        val worldMapSnapshot = if (debugFlags.localMapEnabled) {
            worldLocalMap.update(
                cameraWorldX = cameraPose.tx(),
                cameraWorldZ = cameraPose.tz(),
                observations = mapObservations,
            )
            worldLocalMap.snapshot(
                cameraWorldX = cameraPose.tx(),
                cameraWorldZ = cameraPose.tz(),
                cameraXAxisX = cameraPose.xAxis[0],
                cameraXAxisZ = cameraPose.xAxis[2],
                cameraZAxisX = cameraPose.zAxis[0],
                cameraZAxisZ = cameraPose.zAxis[2],
            )
        } else {
            emptyList()
        }
        val worldMapLaneMetrics = evaluateWorldMapLanes(worldMapSnapshot)
        val overlayDetections = updateObjectMotionTags(
            detections = if (debugFlags.yoloEnabled) {
                enrichObjectDetections(
                    frame = frame,
                    detections = lastObjectDetections,
                    rawDepthEnabled = debugFlags.rawDepthEnabled,
                    depthFrame = depthFrame,
                )
            } else {
                emptyList()
            },
            timestampNanos = frame.timestamp,
            worldMapLaneMetrics = worldMapLaneMetrics,
        )
        val nearestPersonDetection = overlayDetections
            .filter {
                it.label.equals("person", ignoreCase = true) &&
                    it.distanceMeters != null &&
                    !it.distanceIsReference
            }
            .sortedWith(
                compareBy<ObjectOverlayDetection> { if (it.isStable) 0 else 1 }
                    .thenBy { it.distanceMeters ?: Float.MAX_VALUE },
            )
            .firstOrNull()

        val leftLane = corridorLaneDistances(corridorHits, rawDepthHits, "left")
        val centerLane = corridorLaneDistances(corridorHits, rawDepthHits, "center")
        val rightLane = corridorLaneDistances(corridorHits, rawDepthHits, "right")

        val floorDistance = listOfNotNull(leftLane.floor, centerLane.floor, rightLane.floor).minOrNull()
        val wallDistance = listOfNotNull(leftLane.wall, centerLane.wall, rightLane.wall).minOrNull()
        val depthDistance = listOfNotNull(leftLane.depth, centerLane.depth, rightLane.depth).minOrNull()
        val rawDepthDistance = listOfNotNull(leftLane.rawDepth, centerLane.rawDepth, rightLane.rawDepth).minOrNull()
        val rawCollisionDistance = listOfNotNull(
            leftLane.collision,
            centerLane.collision,
            rightLane.collision,
        ).minOrNull()

        val smoothedFloor = smoothDistance(smoothedFloorDistance, floorDistance).also { smoothedFloorDistance = it }
        val smoothedWall = smoothDistance(smoothedWallDistance, wallDistance).also { smoothedWallDistance = it }
        val smoothedDepth = smoothDistance(smoothedDepthDistance, depthDistance).also { smoothedDepthDistance = it }
        val smoothedRawDepth = smoothDistance(smoothedRawDepthDistance, rawDepthDistance).also {
            smoothedRawDepthDistance = it
        }
        val fusedRawCollisionDistance = nearestPersonDetection?.distanceMeters ?: rawCollisionDistance
        val collisionDistance = smoothDistance(smoothedCollisionDistance, fusedRawCollisionDistance).also {
            smoothedCollisionDistance = it
        }
        val leftDistance = smoothDistance(
            smoothedLeftDistance,
            leftLane.collision,
        ).also {
            smoothedLeftDistance = it
        }
        val centerDistance = smoothDistance(
            smoothedCenterDistance,
            centerLane.collision,
        ).also {
            smoothedCenterDistance = it
        }
        val rightDistance = smoothDistance(
            smoothedRightDistance,
            rightLane.collision,
        ).also {
            smoothedRightDistance = it
        }

        val motionSpeed = computeMotionSpeed(frame)
        val approachSpeed = computeApproachSpeed(collisionDistance)
        val ttcRisk = computeTtcRisk(collisionDistance, approachSpeed, motionSpeed)
        val riskLabel = ttcRisk.label
        val rawDepthValidRatio = if (rawDepthCorridorResult.plannedSampleCount > 0) {
            rawDepthHits.size / rawDepthCorridorResult.plannedSampleCount.toFloat()
        } else {
            0f
        }
        val gridValidRatio = if (depthGridCells.isNotEmpty()) {
            depthGridCells.count { it.distanceMeters != null } / depthGridCells.size.toFloat()
        } else {
            0f
        }
        val validGridCells = depthGridCells.count { it.distanceMeters != null }
        val gridAverageConfidence = depthGridCells
            .takeIf { it.isNotEmpty() }
            ?.map { it.confidence }
            ?.average()
            ?.toFloat()
            ?: 0f
        val sensingConfidenceScore = computeRawDepthConfidenceScore(
            rawDepthStatus = depthFrame.status,
            rawDepthValidRatio = rawDepthValidRatio,
            gridValidRatio = gridValidRatio,
            gridAverageConfidence = gridAverageConfidence,
        )
        val instantDirection = computeSuggestedDirection(
            leftDistance = leftDistance,
            centerDistance = centerDistance,
            rightDistance = rightDistance,
            leftLaneScore = null,
            centerLaneScore = null,
            rightLaneScore = null,
        )
        val suggestedDirection = stabilizeDirection(instantDirection, riskLabel)
        val sceneGuidanceLabel = computeGuidanceLabelForScene(
            suggestedDirection = suggestedDirection,
            collisionDistance = collisionDistance,
            riskLabel = riskLabel,
            nearestPerson = nearestPersonDetection,
        )

        val (baseStatusLabel, level, baseNote) = when {
            collisionDistance == null -> Triple(
                "No surface locked yet",
                ArStatusLevel.INFO,
                "Sweep the camera slowly until depth samples appear.",
            )
            riskLabel == "critical" -> Triple(
                "Obstacle ahead",
                ArStatusLevel.DANGER,
                "Close and getting closer.",
            )
            riskLabel == "high" -> Triple(
                "Obstacle ahead",
                ArStatusLevel.WARNING,
                "Avoidance recommended.",
            )
            collisionDistance < 1.5f -> Triple(
                "Obstacle ahead",
                ArStatusLevel.WARNING,
                "Keep moving carefully.",
            )
            else -> Triple(
                "Path measured",
                ArStatusLevel.SAFE,
                "Lane distances are stable.",
            )
        }

        val guidanceLabel = guidanceLabelWithVlm(sceneGuidanceLabel, vlmVisionState?.interpretation)
        val statusLabel = baseStatusLabel
        val note = if (vlmVisionState != null) {
            "$baseNote ${vlmVisionState.interpretation.pathSummary}"
        } else {
            baseNote
        }
        maybeAppendDepthMeasurementLog(
            ArDepthMeasurementRecord(
                frameTimestampNanos = frame.timestamp,
                trackingState = camera.trackingState.name,
                trackingFailureReason = camera.trackingFailureReason.name,
                sensingConfidenceScore = sensingConfidenceScore,
                acquisitionStatus = depthFrame.status.name,
                rawDepthTimestampNanos = depthFrame.rawDepthTimestampNanos,
                isNewRawDepth = depthFrame.isNewRawDepth,
                rawDepthWidth = depthFrame.rawDepthImage?.width,
                rawDepthHeight = depthFrame.rawDepthImage?.height,
                pitchDownDegrees = pitchDownDegrees,
                motionMetersPerSecond = motionSpeed,
                plannedCorridorSamples = rawDepthCorridorResult.plannedSampleCount,
                validCorridorHits = rawDepthHits.size,
                corridorValidRatio = if (rawDepthCorridorResult.plannedSampleCount > 0) {
                    rawDepthHits.size / rawDepthCorridorResult.plannedSampleCount.toFloat()
                } else {
                    0f
                },
                validGridCells = validGridCells,
                gridAverageConfidence = gridAverageConfidence,
                objectCount = overlayDetections.size,
                objectDepthCount = overlayDetections.count { it.distanceMeters != null && !it.distanceIsReference },
                leftNearestMeters = leftLane.rawDepth,
                centerNearestMeters = centerLane.rawDepth,
                rightNearestMeters = rightLane.rawDepth,
                rawDepthNearestMeters = rawDepthDistance,
            ),
        )

        ArMeasurementBridge.publish(
            ArMeasurementState(
                trackingLabel = "tracking",
                horizontalPlaneCount = horizontalPlaneCount,
                verticalPlaneCount = verticalPlaneCount,
                sensingConfidenceScore = sensingConfidenceScore,
                geospatialStatusLabel = geospatialStatus.statusLabel,
                geospatialEarthStateLabel = geospatialStatus.earthStateLabel,
                geospatialStreetscapeGeometryCount = geospatialStatus.streetscapeGeometryCount,
                routeRealityGuidanceLabel = routeGuidance?.message.orEmpty(),
                routeRealityGuidanceDetail = routeGuidanceDetail(routeGuidance),
                routeRealityGuidanceAction = routeGuidance?.action?.name?.lowercase().orEmpty(),
                routeRealityGuidanceSource = if (routeGuidance != null) "geospatial" else "",
                pitchDownDegrees = pitchDownDegrees,
                leftLaneWidthRatio = 0.33f,
                centerLaneWidthRatio = 0.34f,
                rightLaneWidthRatio = 0.33f,
                leftDistanceMeters = leftDistance,
                centerDistanceMeters = centerDistance,
                rightDistanceMeters = rightDistance,
                suggestedDirection = suggestedDirection,
                floorDistanceMeters = smoothedFloor,
                wallDistanceMeters = smoothedWall,
                depthDistanceMeters = smoothedDepth,
                rawDepthDistanceMeters = smoothedRawDepth,
                collisionDistanceMeters = collisionDistance,
                approachSpeedMetersPerSecond = approachSpeed,
                motionMetersPerSecond = motionSpeed,
                timeToCollisionSeconds = ttcRisk.timeToCollisionSeconds,
                riskLabel = riskLabel,
                guidanceLabel = guidanceLabel,
                statusLabel = statusLabel,
                statusLevel = level,
                crosswalkDetected = crosswalkFusion.detected,
                crosswalkScore = crosswalkFusion.score,
                crosswalkStripeCount = crosswalkFusion.stripeCount,
                crosswalkYoloConfidence = crosswalkFusion.yoloConfidence,
                crosswalkModeLabel = crosswalkFusion.modeLabel,
                crosswalkMapDistanceMeters = crosswalkFusion.mapDistanceMeters,
                crosswalkMapHeadingDeltaDegrees = crosswalkFusion.mapHeadingDeltaDegrees,
                objectDetections = overlayDetections,
                depthGridCells = depthGridCells,
                walkingZoneDepthSamples = if (debugFlags.walkingZoneDistanceEnabled) {
                    rawDepthCorridorResult.samples
                } else {
                    emptyList()
                },
                planeDetections = emptyList(),
                planePolygons = emptyList(),
                worldMapCells = worldMapSnapshot,
                worldMapRangeMeters = worldLocalMap.rangeMeters(),
                worldMapCellSizeMeters = worldLocalMap.cellSizeMeters(),
                worldMapKnownCells = if (debugFlags.localMapEnabled) worldLocalMap.knownCellCount() else 0,
                worldMapOccupiedCells = if (debugFlags.localMapEnabled) worldLocalMap.occupiedCellCount() else 0,
                worldMapObservationCount = if (debugFlags.localMapEnabled) {
                    worldLocalMap.totalObservationCount()
                } else {
                    0
                },
                worldMapConfidenceScore = if (debugFlags.localMapEnabled) worldLocalMap.averageConfidenceScore() else 0,
                worldMapLeftOpenScore = laneOpennessScore(
                    freeSpaceMeters = worldMapLaneMetrics.leftFreeSpaceMeters,
                    occupancyRatio = worldMapLaneMetrics.leftOccupancyRatio,
                    nearestDistance = worldMapLaneMetrics.leftDistance,
                ),
                worldMapCenterOpenScore = laneOpennessScore(
                    freeSpaceMeters = worldMapLaneMetrics.centerFreeSpaceMeters,
                    occupancyRatio = worldMapLaneMetrics.centerOccupancyRatio,
                    nearestDistance = worldMapLaneMetrics.centerDistance,
                ),
                worldMapRightOpenScore = laneOpennessScore(
                    freeSpaceMeters = worldMapLaneMetrics.rightFreeSpaceMeters,
                    occupancyRatio = worldMapLaneMetrics.rightOccupancyRatio,
                    nearestDistance = worldMapLaneMetrics.rightDistance,
                ),
                worldMapLeftFreeSpaceMeters = worldMapLaneMetrics.leftFreeSpaceMeters,
                worldMapCenterFreeSpaceMeters = worldMapLaneMetrics.centerFreeSpaceMeters,
                worldMapRightFreeSpaceMeters = worldMapLaneMetrics.rightFreeSpaceMeters,
                vlmModelName = vlmVisionState?.interpretation?.modelName.orEmpty(),
                vlmRiskLabel = vlmVisionState?.interpretation?.risk?.name?.lowercase().orEmpty(),
                vlmSuggestedAction = vlmVisionState?.interpretation?.suggestedAction?.name?.lowercase().orEmpty(),
                vlmConfidenceScore =
                    ((vlmVisionState?.interpretation?.confidence ?: 0f) * 100f).toInt().coerceIn(0, 100),
                vlmSummary = vlmVisionState?.interpretation?.pathSummary.orEmpty(),
                note = note,
            ),
        )
        }
    }

    private fun scheduleVisionAnalysis(
        frame: Frame,
        debugFlags: DebugPipelineFlags,
    ) {
        if (detectionInFlight.get()) return

        val ocrRequested = oneShotOcrRequested.get()
        val vlmRequested = oneShotVlmRequested.get()
        val liveVlmStreaming = liveVlmSessionActive.get() && debugFlags.vlmEnabled
        val backgroundVisionEnabled = debugFlags.yoloEnabled
        if (!backgroundVisionEnabled && !ocrRequested && !vlmRequested && !liveVlmStreaming) {
            lastObjectDetections = emptyList()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val liveFrameDue = liveVlmStreaming && now - lastLiveVlmFrameSentAtMs >= LIVE_VLM_FRAME_INTERVAL_MS
        if (!ocrRequested && !vlmRequested && !liveFrameDue && now - lastDetectionStartedAtMs < 450L) return
        if (!ocrRequested && !vlmRequested && liveVlmStreaming && !liveFrameDue && !backgroundVisionEnabled) return

        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        } catch (_: IllegalStateException) {
            return
        }
        val rotationDegrees = displayRotationDegrees()
        val pitchRadians = Math.toRadians(computePitchDownDegrees(frame).toDouble()).toFloat()
        val arStateSnapshot = ArMeasurementBridge.state.value

        lastDetectionStartedAtMs = now
        detectionInFlight.set(true)
        detectorExecutor.execute {
            var shouldRunOcr = false
            var ocrStarted = false
            var shouldRunVlm = false
            var vlmStarted = false
            var shouldStreamLiveVlm = false
            var bitmap: Bitmap? = null
            try {
                shouldRunOcr = oneShotOcrRequested.getAndSet(false)
                shouldRunVlm = oneShotVlmRequested.getAndSet(false)
                shouldStreamLiveVlm = liveVlmSessionActive.get() &&
                    debugFlags.vlmEnabled &&
                    SystemClock.elapsedRealtime() - lastLiveVlmFrameSentAtMs >= LIVE_VLM_FRAME_INTERVAL_MS
                if (shouldRunVlm && !debugFlags.vlmEnabled) {
                    appendVlmInvocationLog(
                        success = false,
                        resultModelName = null,
                        risk = null,
                        errorMessage = "vlm_pipeline_disabled",
                    )
                    dispatchOneShotVlmResult("디버그 설정에서 VLM 파이프라인이 꺼져 있습니다.")
                }
                if (!backgroundVisionEnabled &&
                    !shouldRunOcr &&
                    !(shouldRunVlm && debugFlags.vlmEnabled) &&
                    !shouldStreamLiveVlm
                ) {
                    image.close()
                    return@execute
                }

                val analysisBitmap = image.toUprightBitmap(
                    rotationDegrees = rotationDegrees,
                    maxLongSide = analysisInputMaxLongSide(
                        shouldRunOcr = shouldRunOcr,
                        shouldRunManualVlm = shouldRunVlm && debugFlags.vlmEnabled,
                        shouldStreamLiveVlm = shouldStreamLiveVlm,
                        backgroundVisionEnabled = backgroundVisionEnabled,
                    ),
                ).also { bitmap = it }
                image.close()
                if (shouldRunOcr) {
                    ocrStarted = startOneShotOcr(analysisBitmap)
                }
                val localObjectAnalyzer = if (debugFlags.yoloEnabled) objectAnalyzer else null
                val detectedObjects = if (localObjectAnalyzer?.isReady() == true) {
                    localObjectAnalyzer.detect(analysisBitmap)
                } else {
                    emptyList()
                }
                val yoloCrosswalkConfidence = detectedObjects
                    .filter { it.label.equals("crosswalk", ignoreCase = true) }
                    .maxOfOrNull { it.confidence }
                    ?: 0f
                val crosswalk = crosswalkPatternDetector.detect(
                    bitmap = analysisBitmap,
                    floorSegmentation = null,
                    yoloConfidence = yoloCrosswalkConfidence,
                )
                lastCrosswalkFrameSnapshot = CrosswalkFrameSnapshot(
                    pattern = crosswalk,
                    timestampNanos = frame.timestamp,
                )
                val trackedDetections = objectTracker.update(
                    detections = detectedObjects.map { detection ->
                        DetectedObjectResult(
                            boundingBox = detection.boundingBox,
                            confidence = detection.confidence,
                            imageHeight = detection.imageHeight,
                            imageWidth = detection.imageWidth,
                            label = detection.label,
                            distanceEstimate = DetectionDistanceEstimate(
                                distanceMeters = null,
                                rawGeometryDistanceMeters = null,
                                qualityScore = detection.confidence,
                                source = DistanceSource.UNKNOWN,
                                riskLevel = RiskLevel.SAFE,
                            ),
                            segmentCoverageRatio = detection.segmentCoverageRatio,
                            segmentCenterXRatio = detection.segmentCenterXRatio,
                            segmentCenterYRatio = detection.segmentCenterYRatio,
                        )
                    },
                    timestampNanos = frame.timestamp,
                )
                val primaryAnalysis = FrameAnalysis(
                    detections = trackedDetections,
                    nearestObstacle = null,
                    floorSegmentation = null,
                    pathMetrics = null,
                )
                val spatialFrame = SpatialFrame(
                    bitmap = analysisBitmap,
                    timestampMillis = frame.timestamp / 1_000_000L,
                    source = SpatialFrameSource.LIVE_CAMERA,
                    pitchRadians = pitchRadians,
                    arState = arStateSnapshot,
                    requestMode = if (shouldRunVlm) VlmRequestMode.MANUAL else VlmRequestMode.AUTO,
                )
                if (shouldStreamLiveVlm) {
                    vlmSceneInterpreter.streamLiveFrame(spatialFrame)
                    lastLiveVlmFrameSentAtMs = SystemClock.elapsedRealtime()
                }
                if (shouldRunVlm && debugFlags.vlmEnabled && !vlmStarted) {
                    startBackgroundVlmCaption(
                        bitmap = analysisBitmap,
                        spatialFrame = spatialFrame,
                        primaryAnalysis = primaryAnalysis,
                        crosswalk = crosswalk,
                        timestampNanos = frame.timestamp,
                    )
                }
                val prioritizedDetections = trackedDetections
                    .sortedWith(
                        compareByDescending<DetectedObjectResult> { it.trackingState?.isStable == true }
                            .thenBy { it.trackingState?.missedFrames ?: 0 }
                            .thenByDescending { it.confidence },
                    )
                    .take(6)
                    .map { detection ->
                        val centerXRatio =
                            ((detection.boundingBox.left + detection.boundingBox.right) * 0.5f / analysisBitmap.width)
                                .coerceIn(0f, 1f)
                        ObjectOverlayDetection(
                            leftRatio = (detection.boundingBox.left / analysisBitmap.width).coerceIn(0f, 1f),
                            topRatio = (detection.boundingBox.top / analysisBitmap.height).coerceIn(0f, 1f),
                            widthRatio = (detection.boundingBox.width() / analysisBitmap.width).coerceIn(0.02f, 1f),
                            heightRatio = (detection.boundingBox.height() / analysisBitmap.height).coerceIn(0.02f, 1f),
                            label = detection.label,
                            confidence = detection.confidence,
                            lane = classifyScreenLane(centerXRatio),
                            isStable = detection.trackingState?.isStable == true,
                            trackId = detection.trackingState?.trackId,
                            segmentCoverageRatio = detection.segmentCoverageRatio,
                            segmentCenterXRatio = detection.segmentCenterXRatio,
                            segmentCenterYRatio = detection.segmentCenterYRatio,
                        )
                    }
                lastObjectDetections = prioritizedDetections
            } catch (_: Exception) {
                runCatching { image.close() }
                if (shouldRunVlm && debugFlags.vlmEnabled) {
                    oneShotVlmRequested.set(false)
                    oneShotVlmInFlight.set(false)
                    appendVlmInvocationLog(
                        success = false,
                        resultModelName = null,
                        risk = null,
                        errorMessage = "frame_processing_failed",
                    )
                    dispatchOneShotVlmResult("화면 분석에 실패했습니다. 잠시 멈춰 주세요.")
                }
                if (shouldRunOcr && !ocrStarted) {
                    oneShotOcrInFlight.set(false)
                    dispatchOneShotOcrResult("문자를 읽을 이미지를 가져오지 못했습니다.")
                }
            } finally {
                bitmap?.recycle()
                detectionInFlight.set(false)
            }
        }
    }

    private fun analysisInputMaxLongSide(
        shouldRunOcr: Boolean,
        shouldRunManualVlm: Boolean,
        shouldStreamLiveVlm: Boolean,
        backgroundVisionEnabled: Boolean,
    ): Int? {
        return when {
            shouldRunOcr -> null
            shouldRunManualVlm -> VLM_ANALYSIS_MAX_LONG_SIDE
            backgroundVisionEnabled -> YOLO_ANALYSIS_MAX_LONG_SIDE
            shouldStreamLiveVlm -> LIVE_VLM_ANALYSIS_MAX_LONG_SIDE
            else -> YOLO_ANALYSIS_MAX_LONG_SIDE
        }
    }

    private fun startOneShotOcr(bitmap: Bitmap): Boolean {
        if (!oneShotOcrInFlight.compareAndSet(false, true)) {
            return false
        }

        val ocrBitmap = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            oneShotOcrInFlight.set(false)
            dispatchOneShotOcrResult("문자를 읽을 이미지를 가져오지 못했습니다.")
            return false
        }

        val reader = oneShotOcrReader ?: OneShotOcrReader().also {
            oneShotOcrReader = it
        }
        reader.read(ocrBitmap) { message ->
            oneShotOcrInFlight.set(false)
            dispatchOneShotOcrResult(message)
        }
        return true
    }

    private fun dispatchOneShotOcrResult(message: String) {
        activity?.runOnUiThread {
            onOneShotOcrResult?.invoke(message)
        }
    }

    private fun appendVlmInvocationLog(
        success: Boolean,
        resultModelName: String?,
        risk: VlmWalkingRisk?,
        errorMessage: String?,
    ) {
        val appContext = requireContext().applicationContext
        val requestedAtMs = oneShotVlmRequestedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        VlmInvocationLogger.append(
            context = appContext,
            selectedModelName = WalkAssistSettings.vlmModelOption(appContext).displayName,
            resultModelName = resultModelName,
            latencyMs = SystemClock.elapsedRealtime() - requestedAtMs,
            success = success,
            risk = risk,
            errorMessage = errorMessage,
        )
        oneShotVlmRequestedAtMs = 0L
    }

    private fun startBackgroundVlmCaption(
        bitmap: Bitmap,
        spatialFrame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
        timestampNanos: Long,
    ) {
        if (!oneShotVlmInFlight.compareAndSet(false, true)) {
            return
        }

        val vlmBitmap = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            oneShotVlmInFlight.set(false)
            val appContext = requireContext().applicationContext
            val requestedAtMs = oneShotVlmRequestedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
            VlmInvocationLogger.append(
                context = appContext,
                selectedModelName = WalkAssistSettings.vlmModelOption(appContext).displayName,
                resultModelName = null,
                latencyMs = SystemClock.elapsedRealtime() - requestedAtMs,
                success = false,
                risk = null,
                errorMessage = "bitmap_copy_failed",
            )
            oneShotVlmRequestedAtMs = 0L
            return
        }
        val localVlmInterpreter = vlmSceneInterpreter
        val modelFrame = spatialFrame.copy(bitmap = vlmBitmap)
        val appContext = requireContext().applicationContext
        val selectedModelName = WalkAssistSettings.vlmModelOption(appContext).displayName
        val requestedAtMs = oneShotVlmRequestedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        vlmExecutor.execute {
            val inferenceStartedAtMs = SystemClock.elapsedRealtime()
            try {
            Log.d(TAG, "VLM invoke manual=true model=$selectedModelName timestampMs=${modelFrame.timestampMillis}")
                val interpretation = localVlmInterpreter.interpret(
                    frame = modelFrame,
                    primaryAnalysis = primaryAnalysis,
                    crosswalk = crosswalk,
                )
                val buttonToAnswerLatencyMs = SystemClock.elapsedRealtime() - requestedAtMs
                Log.d(
                    TAG,
                    "VLM result manual=true model=${interpretation?.modelName ?: selectedModelName} " +
                        "buttonToAnswerLatencyMs=$buttonToAnswerLatencyMs " +
                        "inferenceElapsedMs=${SystemClock.elapsedRealtime() - inferenceStartedAtMs} " +
                        "risk=${interpretation?.risk}",
                )
                VlmInvocationLogger.append(
                    context = appContext,
                    selectedModelName = selectedModelName,
                    resultModelName = interpretation?.modelName,
                    latencyMs = buttonToAnswerLatencyMs,
                    success = interpretation != null,
                    risk = interpretation?.risk,
                    errorMessage = if (interpretation == null) "empty_interpretation" else null,
                )
                if (interpretation != null) {
                    lastVlmVisionState = VlmVisionState(
                        interpretation = interpretation,
                        timestampNanos = timestampNanos,
                    )
                    dispatchOneShotVlmResult(interpretation.pathSummary)
                } else {
                    dispatchOneShotVlmResult("화면 분석 결과를 받지 못했습니다. 잠시 후 다시 눌러 주세요.")
                }
            } catch (error: Exception) {
                val buttonToAnswerLatencyMs = SystemClock.elapsedRealtime() - requestedAtMs
                VlmInvocationLogger.append(
                    context = appContext,
                    selectedModelName = selectedModelName,
                    resultModelName = null,
                    latencyMs = buttonToAnswerLatencyMs,
                    success = false,
                    risk = null,
                    errorMessage = error.message ?: error::class.java.simpleName,
                )
                Log.w(TAG, "Background VLM caption failed", error)
                dispatchOneShotVlmResult("화면 분석에 실패했습니다. 네트워크 상태를 확인해 주세요.")
            } finally {
                oneShotVlmRequestedAtMs = 0L
                oneShotVlmInFlight.set(false)
                vlmBitmap.recycle()
            }
        }
    }

    private fun dispatchOneShotVlmResult(message: String) {
        activity?.runOnUiThread {
            onOneShotVlmResult?.invoke(message)
        }
    }

    private fun dispatchLiveVlmGuidanceResult(message: String) {
        activity?.runOnUiThread {
            onLiveVlmGuidanceResult?.invoke(message)
        }
    }

    private fun displayRotationDegrees(): Int {
        val rotation = displayRotation()
        return when (rotation) {
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    }

    private fun displayRotation(): Int {
        return glSurfaceView?.display?.rotation
            ?: activity?.display?.rotation
            ?: Surface.ROTATION_0
    }

    private fun viewWidth(): Float = viewportWidth.toFloat().coerceAtLeast(1f)

    private fun viewHeight(): Float = viewportHeight.toFloat().coerceAtLeast(1f)

    private fun sampleWorldCorridor(frame: Frame): List<CorridorHit> {
        val width = viewWidth()
        val height = viewHeight()
        val sampleXs = listOf(0.14f, 0.24f, 0.34f, 0.44f, 0.5f, 0.56f, 0.66f, 0.76f, 0.86f)
        val sampleYs = listOf(0.44f, 0.56f, 0.68f, 0.8f)

        return buildList {
                for (yRatio in sampleYs) {
                    for (xRatio in sampleXs) {
                        val hit = nearestWorldHit(
                            frame = frame,
                            x = width * xRatio,
                            y = height * yRatio,
                            viewXRatio = xRatio,
                            viewYRatio = yRatio,
                        )
                        if (hit != null) {
                            add(hit)
                        }
                    }
                }
        }
    }

    private fun corridorLaneDistances(
        hits: List<CorridorHit>,
        rawDepthHits: List<CorridorHit>,
        lane: String,
    ): LaneDistances {
        val filtered = hits.filter { classifyLane(it.lateralMeters) == lane }
        val obstacleHits = filtered.filter { it.source != HitSource.FLOOR }
        val rawDepth = robustLaneRawDepthDistance(rawDepthHits, lane)
        val floor = filtered.filter { it.source == HitSource.FLOOR }.minOfOrNull { it.distanceMeters }
        val wall = obstacleHits.filter { it.source == HitSource.WALL }.minOfOrNull { it.distanceMeters }
        val depth = obstacleHits.filter { it.source == HitSource.DEPTH }.minOfOrNull { it.distanceMeters }
        return LaneDistances(
            floor = floor,
            wall = wall,
            depth = depth,
            rawDepth = rawDepth,
            collision = listOfNotNull(wall, depth, rawDepth).minOrNull(),
        )
    }

    private fun robustLaneRawDepthDistance(
        rawDepthHits: List<CorridorHit>,
        lane: String,
    ): Float? {
        val laneDistances = rawDepthHits
            .filter { classifyLane(it.lateralMeters) == lane }
            .map { it.distanceMeters }
            .sorted()

        if (laneDistances.isEmpty()) return null

        // Avoid using the single nearest raw-depth point because it is often a flickering outlier.
        val percentileIndex = ((laneDistances.size - 1) * 0.3f).toInt().coerceIn(0, laneDistances.lastIndex)
        return laneDistances[percentileIndex]
    }

    private fun currentVlmVisionState(frameTimestampNanos: Long): VlmVisionState? {
        val state = lastVlmVisionState ?: return null
        val ageSeconds = (frameTimestampNanos - state.timestampNanos) / 1_000_000_000f
        return if (ageSeconds in 0f..4.0f) state else null
    }

    private fun evaluateWorldMapLanes(
        cells: List<WorldMapCellUi>,
    ): WorldMapLaneMetrics {
        val obstacleCells = cells.filter { cell ->
            cell.relativeZ in 0.25f..5.0f &&
                cell.occupancyScore >= 0.16f &&
                cell.confidenceScore >= 0.18f
        }

        fun laneCells(lane: String): List<WorldMapCellUi> {
            return obstacleCells.filter { classifyLane(it.relativeX) == lane }
        }

        fun nearestDistance(lane: String): Float? {
            return laneCells(lane)
                .map { sqrt((it.relativeX * it.relativeX) + (it.relativeZ * it.relativeZ)) }
                .minOrNull()
        }

        fun occupancyRatio(lane: String): Float {
            val relevant = cells.filter { it.relativeZ in 0.25f..4.5f && classifyLane(it.relativeX) == lane }
            if (relevant.isEmpty()) return 0f
            val occupied = relevant.count {
                it.occupancyScore >= 0.16f && it.confidenceScore >= 0.18f
            }
            return (occupied.toFloat() / relevant.size.toFloat()).coerceIn(0f, 1f)
        }

        fun freeSpaceLength(lane: String): Float? {
            val relevant = cells.filter { classifyLane(it.relativeX) == lane && it.relativeZ in 0.25f..5.0f }
            if (relevant.isEmpty()) return null

            val stepMeters = 0.25f
            val maxDistance = 5.0f
            var probe = 0.25f
            var lastFree = 0.25f
            while (probe <= maxDistance) {
                val blocked = relevant.any { cell ->
                    cell.relativeZ in (probe - 0.18f)..(probe + 0.18f) &&
                        cell.occupancyScore >= 0.16f &&
                        cell.confidenceScore >= 0.18f
                }
                if (blocked) {
                    return lastFree.coerceAtLeast(0.25f)
                }
                lastFree = probe
                probe += stepMeters
            }
            return maxDistance
        }

        val leftDistance = nearestDistance("left")
        val centerDistance = nearestDistance("center")
        val rightDistance = nearestDistance("right")
        return WorldMapLaneMetrics(
            leftDistance = leftDistance,
            centerDistance = centerDistance,
            rightDistance = rightDistance,
            collisionDistance = listOfNotNull(leftDistance, centerDistance, rightDistance).minOrNull(),
            leftOccupancyRatio = occupancyRatio("left"),
            centerOccupancyRatio = occupancyRatio("center"),
            rightOccupancyRatio = occupancyRatio("right"),
            leftFreeSpaceMeters = freeSpaceLength("left"),
            centerFreeSpaceMeters = freeSpaceLength("center"),
            rightFreeSpaceMeters = freeSpaceLength("right"),
        )
    }

    private fun computeLaneScore(
        distance: Float?,
        occupancyRatio: Float,
        freeSpaceMeters: Float?,
    ): Float {
        val normalizedDistance = (distance ?: 0f).coerceIn(0f, 5f) / 5f
        val normalizedFreeSpace = (freeSpaceMeters ?: 0f).coerceIn(0f, 5f) / 5f
        val openRatio = (1f - occupancyRatio.coerceIn(0f, 1f))
        return (
            (normalizedDistance * 0.45f) +
                (normalizedFreeSpace * 0.4f) +
                (openRatio * 0.15f)
            ).coerceIn(0f, 1f)
    }

    private fun obstacleStrengthForHit(
        source: HitSource,
        relativeHeightMeters: Float,
    ): Float {
        return when (source) {
            HitSource.FLOOR -> 0f
            HitSource.WALL -> 0.42f
            HitSource.DEPTH -> {
                when {
                    relativeHeightMeters <= -1.05f -> 0f
                    relativeHeightMeters <= -0.9f -> 0.08f
                    relativeHeightMeters <= -0.75f -> 0.18f
                    else -> 0.34f
                }
            }
        }
    }

    private fun classifyLane(lateralMeters: Float): String? {
        return when {
            lateralMeters < -0.28f && lateralMeters >= -1.05f -> "left"
            lateralMeters in -0.28f..0.28f -> "center"
            lateralMeters > 0.28f && lateralMeters <= 1.05f -> "right"
            else -> null
        }
    }

    private fun classifyScreenLane(centerXRatio: Float): String {
        return when {
            centerXRatio < 0.38f -> "left"
            centerXRatio > 0.62f -> "right"
            else -> "center"
        }
    }

    private fun enrichObjectDetections(
        frame: Frame,
        detections: List<ObjectOverlayDetection>,
        rawDepthEnabled: Boolean,
        depthFrame: ArDepthFrame,
    ): List<ObjectOverlayDetection> {
        if (detections.isEmpty()) return emptyList()
        val rawDepthImage = depthFrame.rawDepthImage
        val confidenceImage = depthFrame.rawDepthConfidenceImage
        if (!rawDepthEnabled || rawDepthImage == null || confidenceImage == null) {
            return detections
        }

        return detections.map { detection ->
            val centerXRatio = (detection.leftRatio + (detection.widthRatio * 0.5f)).coerceIn(0f, 1f)
            val lane = classifyScreenLane(centerXRatio)
            val boxDepthEstimate = computeBoxDepthEstimate(
                frame = frame,
                rawDepthImage = rawDepthImage,
                confidenceImage = confidenceImage,
                detection = detection,
            )

            detection.copy(
                distanceMeters = boxDepthEstimate.distanceMeters,
                distanceIsReference = boxDepthEstimate.isReference,
                lane = lane,
            )
        }
    }

    private fun computeBoxDepthEstimate(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        detection: ObjectOverlayDetection,
    ): BoxDepthEstimate {
        val width = viewWidth()
        val height = viewHeight()
        val innerHits = sampleBoxDepthHits(
            frame = frame,
            rawDepthImage = rawDepthImage,
            confidenceImage = confidenceImage,
            width = width,
            height = height,
            detection = detection,
            sampleXs = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f),
            sampleYs = listOf(0.25f, 0.4f, 0.55f, 0.7f, 0.85f),
        )
        if (innerHits.size < 6) return BoxDepthEstimate(distanceMeters = null)

        val surroundingHits = sampleBoxDepthHits(
            frame = frame,
            rawDepthImage = rawDepthImage,
            confidenceImage = confidenceImage,
            width = width,
            height = height,
            detection = detection,
            sampleXs = listOf(0.1f, 0.5f, 0.9f),
            sampleYs = listOf(0.12f, 0.5f, 0.88f),
            expandRatioX = 0.18f,
            expandRatioY = 0.18f,
            edgeOnly = true,
        )

        val farInnerCount = innerHits.count { it.isBeyondReliableRange }
        val farSurroundingCount = surroundingHits.count { it.isBeyondReliableRange }
        val hasReliableFarContext =
            farInnerCount >= (innerHits.size / 2) &&
                farSurroundingCount >= maxOf(2, surroundingHits.size / 3)

        if (hasReliableFarContext) {
            return BoxDepthEstimate(
                distanceMeters = 5f,
                isReference = true,
            )
        }

        return BoxDepthEstimate(
            distanceMeters = innerHits.map { it.distanceMeters }.average().toFloat(),
            isReference = false,
        )
    }

    private fun updateObjectMotionTags(
        detections: List<ObjectOverlayDetection>,
        timestampNanos: Long,
        worldMapLaneMetrics: WorldMapLaneMetrics,
    ): List<ObjectOverlayDetection> {
        if (detections.isEmpty()) {
            objectMotionMemory.clear()
            return emptyList()
        }

        val activeTrackIds = detections.mapNotNull { it.trackId }.toSet()
        objectMotionMemory.keys.removeAll { it !in activeTrackIds }

        return detections.map { detection ->
            val trackId = detection.trackId
            val centerXRatio = (detection.leftRatio + (detection.widthRatio * 0.5f)).coerceIn(0f, 1f)
            if (trackId == null) {
                return@map detection
            }

            val previous = objectMotionMemory[trackId]
            objectMotionMemory[trackId] = ObjectMotionMemory(
                centerXRatio = centerXRatio,
                distanceMeters = detection.distanceMeters?.takeUnless { detection.distanceIsReference },
                timestampNanos = timestampNanos,
            )

            val currentDistance = detection.distanceMeters?.takeUnless { detection.distanceIsReference }
            val dtSeconds = previous?.let { (timestampNanos - it.timestampNanos) / 1_000_000_000f }
            val centerDelta = previous?.let { centerXRatio - it.centerXRatio } ?: 0f
            val closingSpeed = if (
                previous?.distanceMeters != null &&
                currentDistance != null &&
                dtSeconds != null &&
                dtSeconds > 0.08f
            ) {
                ((previous.distanceMeters - currentDistance) / dtSeconds).takeIf { it.isFinite() }
            } else {
                null
            }
            val positiveClosingSpeed = closingSpeed?.coerceAtLeast(0f)
            val ttcSeconds = if (currentDistance != null && positiveClosingSpeed != null && positiveClosingSpeed > 0.05f) {
                (currentDistance / positiveClosingSpeed).takeIf { it.isFinite() && it in 0.1f..12f }
            } else {
                null
            }
            val motionLabel = classifyObjectMotion(
                centerDelta = centerDelta,
                closingSpeed = closingSpeed,
            )
            val avoidanceLabel = suggestAvoidanceForObject(
                lane = detection.lane,
                motionDirectionLabel = motionLabel,
                worldMapLaneMetrics = worldMapLaneMetrics,
            )

            detection.copy(
                objectTimeToCollisionSeconds = ttcSeconds,
                objectClosingSpeedMetersPerSecond = positiveClosingSpeed,
                motionDirectionLabel = motionLabel,
                avoidanceDirectionLabel = avoidanceLabel,
            )
        }
    }

    private fun classifyObjectMotion(
        centerDelta: Float,
        closingSpeed: Float?,
    ): String {
        val horizontalMotion = when {
            centerDelta > 0.035f -> "right"
            centerDelta < -0.035f -> "left"
            else -> null
        }
        return when {
            (closingSpeed ?: 0f) > 0.18f && horizontalMotion == "right" -> "approaching_right"
            (closingSpeed ?: 0f) > 0.18f && horizontalMotion == "left" -> "approaching_left"
            (closingSpeed ?: 0f) > 0.18f -> "approaching"
            (closingSpeed ?: 0f) < -0.18f -> "receding"
            horizontalMotion == "right" -> "moving_right"
            horizontalMotion == "left" -> "moving_left"
            else -> "stationary"
        }
    }

    private fun suggestAvoidanceForObject(
        lane: String?,
        motionDirectionLabel: String,
        worldMapLaneMetrics: WorldMapLaneMetrics,
    ): String? {
        val candidate = when (lane) {
            "left" -> "right"
            "right" -> "left"
            "center" -> when (motionDirectionLabel) {
                "approaching_right", "moving_right" -> "left"
                "approaching_left", "moving_left" -> "right"
                "approaching" -> "stop_or_sidestep"
                else -> null
            }
            else -> null
        }
        return validateAvoidanceWithLocalMap(candidate, worldMapLaneMetrics)
    }

    private fun validateAvoidanceWithLocalMap(
        candidate: String?,
        worldMapLaneMetrics: WorldMapLaneMetrics,
    ): String? {
        if (candidate == null) return null
        if (candidate == "stop_or_sidestep") {
            val leftOpen = isLaneOpenForAvoidance("left", worldMapLaneMetrics)
            val rightOpen = isLaneOpenForAvoidance("right", worldMapLaneMetrics)
            return when {
                leftOpen && rightOpen -> chooseMoreOpenSide(worldMapLaneMetrics)
                leftOpen -> "left"
                rightOpen -> "right"
                else -> "stop_or_sidestep"
            }
        }

        val isOpen = when (candidate) {
            "left", "right", "center" -> isLaneOpenForAvoidance(candidate, worldMapLaneMetrics)
            else -> false
        }
        if (isOpen) return candidate

        val centerFallbackOpen = candidate != "center" && isLaneOpenForAvoidance("center", worldMapLaneMetrics)
        return if (centerFallbackOpen) "center" else "stop_or_sidestep"
    }

    private fun isLaneOpenForAvoidance(
        lane: String,
        worldMapLaneMetrics: WorldMapLaneMetrics,
    ): Boolean {
        val freeSpaceMeters = when (lane) {
            "left" -> worldMapLaneMetrics.leftFreeSpaceMeters
            "center" -> worldMapLaneMetrics.centerFreeSpaceMeters
            "right" -> worldMapLaneMetrics.rightFreeSpaceMeters
            else -> null
        }
        val occupancyRatio = when (lane) {
            "left" -> worldMapLaneMetrics.leftOccupancyRatio
            "center" -> worldMapLaneMetrics.centerOccupancyRatio
            "right" -> worldMapLaneMetrics.rightOccupancyRatio
            else -> 1f
        }
        val nearestDistance = when (lane) {
            "left" -> worldMapLaneMetrics.leftDistance
            "center" -> worldMapLaneMetrics.centerDistance
            "right" -> worldMapLaneMetrics.rightDistance
            else -> 0f
        }
        val opennessScore = laneOpennessScore(
            freeSpaceMeters = freeSpaceMeters,
            occupancyRatio = occupancyRatio,
            nearestDistance = nearestDistance,
        )
        val hasEnoughFreeSpace = (freeSpaceMeters ?: 0f) >= 2.0f
        val hasLowOccupancy = occupancyRatio <= 0.18f
        val nearestObstacleIsNotImmediate = nearestDistance == null || nearestDistance >= 1.2f
        return opennessScore >= 0.58f && hasEnoughFreeSpace && hasLowOccupancy && nearestObstacleIsNotImmediate
    }

    private fun chooseMoreOpenSide(worldMapLaneMetrics: WorldMapLaneMetrics): String {
        val leftScore = laneOpennessScore(
            freeSpaceMeters = worldMapLaneMetrics.leftFreeSpaceMeters,
            occupancyRatio = worldMapLaneMetrics.leftOccupancyRatio,
            nearestDistance = worldMapLaneMetrics.leftDistance,
        )
        val rightScore = laneOpennessScore(
            freeSpaceMeters = worldMapLaneMetrics.rightFreeSpaceMeters,
            occupancyRatio = worldMapLaneMetrics.rightOccupancyRatio,
            nearestDistance = worldMapLaneMetrics.rightDistance,
        )
        return if (leftScore >= rightScore) "left" else "right"
    }

    private fun laneOpennessScore(
        freeSpaceMeters: Float?,
        occupancyRatio: Float,
        nearestDistance: Float?,
    ): Float {
        val freeScore = (freeSpaceMeters ?: 0f).coerceIn(0f, 5f) / 5f
        val occupancyScore = 1f - occupancyRatio.coerceIn(0f, 1f)
        val nearestScore = (nearestDistance ?: 5f).coerceIn(0f, 5f) / 5f
        return (freeScore * 0.5f) + (occupancyScore * 0.3f) + (nearestScore * 0.2f)
    }

    private fun sampleBoxDepthHits(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        width: Float,
        height: Float,
        detection: ObjectOverlayDetection,
        sampleXs: List<Float>,
        sampleYs: List<Float>,
        expandRatioX: Float = 0f,
        expandRatioY: Float = 0f,
        edgeOnly: Boolean = false,
    ): List<CorridorHit> {
        val expandedLeft = (detection.leftRatio - expandRatioX).coerceIn(0f, 1f)
        val expandedTop = (detection.topRatio - expandRatioY).coerceIn(0f, 1f)
        val expandedRight = (detection.leftRatio + detection.widthRatio + expandRatioX).coerceIn(0f, 1f)
        val expandedBottom = (detection.topRatio + detection.heightRatio + expandRatioY).coerceIn(0f, 1f)
        val expandedWidth = (expandedRight - expandedLeft).coerceAtLeast(0.01f)
        val expandedHeight = (expandedBottom - expandedTop).coerceAtLeast(0.01f)

        return buildList {
            sampleYs.forEachIndexed { yIndex, yFactor ->
                sampleXs.forEachIndexed xLoop@{ xIndex, xFactor ->
                    if (edgeOnly) {
                        val isEdgeX = xIndex == 0 || xIndex == sampleXs.lastIndex
                        val isEdgeY = yIndex == 0 || yIndex == sampleYs.lastIndex
                        if (!(isEdgeX || isEdgeY)) return@xLoop
                    }
                    val sampleX = width * (expandedLeft + (expandedWidth * xFactor))
                    val sampleY = height * (expandedTop + (expandedHeight * yFactor))
                    rawDepthHitAt(
                        frame = frame,
                        rawDepthImage = rawDepthImage,
                        confidenceImage = confidenceImage,
                        viewX = sampleX,
                        viewY = sampleY,
                        requireLane = false,
                        maxForwardMeters = 10f,
                    )?.let(::add)
                }
            }
        }
    }

    private fun nearestWorldHit(
        frame: Frame,
        x: Float,
        y: Float,
        viewXRatio: Float,
        viewYRatio: Float,
    ): CorridorHit? {
        val cameraPose = frame.camera.displayOrientedPose
        val xAxis = cameraPose.xAxis
        val zAxis = cameraPose.zAxis
        val cameraX = cameraPose.tx()
        val cameraY = cameraPose.ty()
        val cameraZ = cameraPose.tz()

        return frame.hitTest(x, y)
            .asSequence()
            .mapNotNull { hit ->
                val dx = hit.hitPose.tx() - cameraX
                val dy = hit.hitPose.ty() - cameraY
                val dz = hit.hitPose.tz() - cameraZ

                val lateral = (dx * xAxis[0]) + (dy * xAxis[1]) + (dz * xAxis[2])
                val forward = -((dx * zAxis[0]) + (dy * zAxis[1]) + (dz * zAxis[2]))
                if (forward <= 0.15f || forward > 5.0f) {
                    return@mapNotNull null
                }

                when (val trackable = hit.trackable) {
                    is Plane -> {
                        if (!trackable.isPoseInPolygon(hit.hitPose)) return@mapNotNull null
                        val source = when (trackable.type) {
                            Plane.Type.HORIZONTAL_UPWARD_FACING -> HitSource.FLOOR
                            Plane.Type.VERTICAL -> HitSource.WALL
                            else -> return@mapNotNull null
                        }
                        CorridorHit(
                            distanceMeters = distanceMeters(
                                cameraX,
                                cameraY,
                                cameraZ,
                                hit.hitPose.tx(),
                                hit.hitPose.ty(),
                                hit.hitPose.tz(),
                            ),
                            lateralMeters = lateral,
                            forwardMeters = forward,
                            worldX = hit.hitPose.tx(),
                            worldY = hit.hitPose.ty(),
                            worldZ = hit.hitPose.tz(),
                            viewXRatio = viewXRatio,
                            viewYRatio = viewYRatio,
                            source = source,
                            observationConfidence = if (source == HitSource.FLOOR) 0.92f else 0.88f,
                        )
                    }
                    is DepthPoint -> CorridorHit(
                        distanceMeters = distanceMeters(
                            cameraX,
                            cameraY,
                            cameraZ,
                            hit.hitPose.tx(),
                            hit.hitPose.ty(),
                            hit.hitPose.tz(),
                        ),
                        lateralMeters = lateral,
                        forwardMeters = forward,
                        worldX = hit.hitPose.tx(),
                        worldY = hit.hitPose.ty(),
                        worldZ = hit.hitPose.tz(),
                        viewXRatio = viewXRatio,
                        viewYRatio = viewYRatio,
                        source = HitSource.DEPTH,
                        observationConfidence = 0.72f,
                    )
                    is Point -> {
                        if (trackable.orientationMode != Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                            return@mapNotNull null
                        }
                        CorridorHit(
                            distanceMeters = distanceMeters(
                                cameraX,
                                cameraY,
                                cameraZ,
                                hit.hitPose.tx(),
                                hit.hitPose.ty(),
                                hit.hitPose.tz(),
                            ),
                            lateralMeters = lateral,
                            forwardMeters = forward,
                            worldX = hit.hitPose.tx(),
                            worldY = hit.hitPose.ty(),
                            worldZ = hit.hitPose.tz(),
                            viewXRatio = viewXRatio,
                            viewYRatio = viewYRatio,
                            source = HitSource.DEPTH,
                            observationConfidence = 0.64f,
                        )
                    }
                    else -> null
                }
            }
            .filter { classifyLane(it.lateralMeters) != null }
            .minByOrNull { it.distanceMeters }
    }

    private fun sampleRawDepthCorridor(
        frame: Frame,
        depthFrame: ArDepthFrame,
    ): RawDepthCorridorResult {
        val width = viewWidth()
        val height = viewHeight()
        val sampleXs = listOf(0.18f, 0.3f, 0.42f, 0.5f, 0.58f, 0.7f, 0.82f)
        val sampleYs = listOf(0.42f, 0.54f, 0.66f, 0.78f, 0.9f)
        val plannedSampleCount = sampleXs.size * sampleYs.size
        fun emptySamples(): List<WalkingZoneDepthSample> {
            return sampleYs.flatMap { yRatio ->
                sampleXs.map { xRatio ->
                    WalkingZoneDepthSample(
                        xRatio = xRatio,
                        yRatio = yRatio,
                        distanceMeters = null,
                        confidence = 0f,
                        lane = classifyScreenLane(xRatio),
                    )
                }
            }
        }

        val rawDepthImage = depthFrame.rawDepthImage
            ?: return RawDepthCorridorResult(
                hits = emptyList(),
                plannedSampleCount = plannedSampleCount,
                samples = emptySamples(),
            )
        val confidenceImage = depthFrame.rawDepthConfidenceImage
            ?: return RawDepthCorridorResult(
                hits = emptyList(),
                plannedSampleCount = plannedSampleCount,
                samples = emptySamples(),
            )

        val hits = mutableListOf<CorridorHit>()
        val samples = buildList {
            for (yRatio in sampleYs) {
                for (xRatio in sampleXs) {
                    val hit = rawDepthHitAt(
                        frame = frame,
                        rawDepthImage = rawDepthImage,
                        confidenceImage = confidenceImage,
                        viewX = width * xRatio,
                        viewY = height * yRatio,
                    )
                    if (hit != null) {
                        hits += hit
                    }
                    add(
                        WalkingZoneDepthSample(
                            xRatio = xRatio,
                            yRatio = yRatio,
                            distanceMeters = hit?.distanceMeters,
                            confidence = hit?.observationConfidence ?: 0f,
                            lane = hit?.let { classifyLane(it.lateralMeters) } ?: classifyScreenLane(xRatio),
                        ),
                    )
                }
            }
        }
        return RawDepthCorridorResult(
            hits = hits,
            plannedSampleCount = plannedSampleCount,
            samples = samples,
        )
    }

    private fun sampleRawDepthGrid(
        frame: Frame,
        depthFrame: ArDepthFrame,
        geospatialLongRangeEnabled: Boolean = false,
    ): List<DepthGridCell> {
        val width = viewWidth()
        val height = viewHeight()
        val rawDepthImage = depthFrame.rawDepthImage ?: return emptyList()
        val confidenceImage = depthFrame.rawDepthConfidenceImage ?: return emptyList()
        val maxDepthMillimeters = if (geospatialLongRangeEnabled) {
            GEOSPATIAL_DEPTH_VISUALIZATION_MAX_MM
        } else {
            RAW_DEPTH_VISUALIZATION_MAX_MM
        }

        return buildList {
            for (row in 0 until 4) {
                for (column in 0 until 4) {
                    val viewX = width * ((column + 0.5f) / 4f)
                    val viewY = height * ((row + 0.5f) / 4f)
                    val sample = rawDepthSampleAtViewPoint(
                        frame = frame,
                        rawDepthImage = rawDepthImage,
                        confidenceImage = confidenceImage,
                        viewX = viewX,
                        viewY = viewY,
                        maxDepthMillimeters = maxDepthMillimeters,
                    )
                    add(
                        DepthGridCell(
                            column = column,
                            row = row,
                            distanceMeters = sample?.depthMillimeters?.let { it / 1000f },
                            confidence = sample?.confidence ?: 0f,
                            isLongRange = sample?.depthMillimeters?.let {
                                it > RAW_DEPTH_VISUALIZATION_MAX_MM
                            } ?: false,
                        ),
                    )
                }
            }
        }
    }

    private fun rawDepthSampleAtViewPoint(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        viewX: Float,
        viewY: Float,
        maxDepthMillimeters: Int = RAW_DEPTH_VISUALIZATION_MAX_MM,
    ): RawDepthSample? {
        val textureCoordinates = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW,
            floatArrayOf(viewX, viewY),
            Coordinates2d.TEXTURE_NORMALIZED,
            textureCoordinates,
        )
        val textureX = textureCoordinates[0]
        val textureY = textureCoordinates[1]
        if (textureX !in 0f..1f || textureY !in 0f..1f) return null

        return sampleRawDepthSample(
            depthImage = rawDepthImage,
            confidenceImage = confidenceImage,
            centerX = (textureX * rawDepthImage.width).toInt().coerceIn(0, rawDepthImage.width - 1),
            centerY = (textureY * rawDepthImage.height).toInt().coerceIn(0, rawDepthImage.height - 1),
            maxDepthMillimeters = maxDepthMillimeters,
        )
    }

    private fun rawDepthHitAt(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        viewX: Float,
        viewY: Float,
        requireLane: Boolean = true,
        maxForwardMeters: Float = 5.0f,
    ): CorridorHit? {
        val textureCoordinates = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW,
            floatArrayOf(viewX, viewY),
            Coordinates2d.TEXTURE_NORMALIZED,
            textureCoordinates,
        )
        val textureX = textureCoordinates[0]
        val textureY = textureCoordinates[1]
        if (textureX !in 0f..1f || textureY !in 0f..1f) {
            return null
        }

        val depthX = (textureX * rawDepthImage.width).toInt().coerceIn(0, rawDepthImage.width - 1)
        val depthY = (textureY * rawDepthImage.height).toInt().coerceIn(0, rawDepthImage.height - 1)

        val rawDepthSample = sampleRawDepthSample(
            depthImage = rawDepthImage,
            confidenceImage = confidenceImage,
            centerX = depthX,
            centerY = depthY,
        )
        if (rawDepthSample == null) {
            return null
        }

        val imageCoordinates = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.TEXTURE_NORMALIZED,
            floatArrayOf(textureX, textureY),
            Coordinates2d.IMAGE_PIXELS,
            imageCoordinates,
        )

        val fx = frame.camera.imageIntrinsics.focalLength[0]
        val cx = frame.camera.imageIntrinsics.principalPoint[0]
        if (fx == 0f) {
            return null
        }

        val forwardMeters = rawDepthSample.depthMillimeters / 1000f
        if (forwardMeters <= 0.15f || forwardMeters > maxForwardMeters) {
            return null
        }

        val lateralMeters = ((imageCoordinates[0] - cx) / fx) * forwardMeters
        if (requireLane && classifyLane(lateralMeters) == null) {
            return null
        }

        val rayDistanceMeters = sqrt((forwardMeters * forwardMeters) + (lateralMeters * lateralMeters))
        val cameraPose = frame.camera.displayOrientedPose
        val xAxis = cameraPose.xAxis
        val zAxis = cameraPose.zAxis
        val worldX = cameraPose.tx() + (xAxis[0] * lateralMeters) - (zAxis[0] * forwardMeters)
        val worldY = cameraPose.ty() + (xAxis[1] * lateralMeters) - (zAxis[1] * forwardMeters)
        val worldZ = cameraPose.tz() + (xAxis[2] * lateralMeters) - (zAxis[2] * forwardMeters)
        val viewWidth = viewWidth()
        val viewHeight = viewHeight()
        return CorridorHit(
            distanceMeters = rayDistanceMeters,
            lateralMeters = lateralMeters,
            forwardMeters = forwardMeters,
            worldX = worldX,
            worldY = worldY,
            worldZ = worldZ,
            viewXRatio = (viewX / viewWidth).coerceIn(0f, 1f),
            viewYRatio = (viewY / viewHeight).coerceIn(0f, 1f),
            source = HitSource.DEPTH,
            observationConfidence = rawDepthSample.confidence,
            isBeyondReliableRange = rawDepthSample.depthMillimeters > 5000,
        )
    }

    private fun getMillimetersDepth(
        depthImage: Image,
        x: Int,
        y: Int,
    ): Int {
        val plane = depthImage.planes[0]
        val byteIndex = (x * plane.pixelStride) + (y * plane.rowStride)
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        return buffer.getShort(byteIndex).toInt() and 0xFFFF
    }

    private fun sampleRawDepthSample(
        depthImage: Image,
        confidenceImage: Image,
        centerX: Int,
        centerY: Int,
        maxDepthMillimeters: Int = RAW_DEPTH_VISUALIZATION_MAX_MM,
    ): RawDepthSample? {
        val validDepths = mutableListOf<Int>()
        var confidenceSum = 0f
        val radius = 2

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sampleX = (centerX + dx).coerceIn(0, depthImage.width - 1)
                val sampleY = (centerY + dy).coerceIn(0, depthImage.height - 1)
                val confidence = getConfidence(rawDepthImage = confidenceImage, x = sampleX, y = sampleY)
                if (confidence < 80) continue

                val depthMillimeters = getMillimetersDepth(depthImage = depthImage, x = sampleX, y = sampleY)
                if (depthMillimeters in 150..maxDepthMillimeters) {
                    validDepths += depthMillimeters
                    confidenceSum += confidence / 255f
                }
            }
        }

        if (validDepths.size < 5) return null

        validDepths.sort()
        return RawDepthSample(
            depthMillimeters = validDepths[validDepths.size / 2],
            confidence = (confidenceSum / validDepths.size.toFloat()).coerceIn(0f, 1f),
        )
    }

    private fun getConfidence(
        rawDepthImage: Image,
        x: Int,
        y: Int,
    ): Int {
        val plane = rawDepthImage.planes[0]
        val byteIndex = (x * plane.pixelStride) + (y * plane.rowStride)
        return plane.buffer.get(byteIndex).toInt() and 0xFF
    }


    private fun smoothDistance(previous: Float?, current: Float?): Float? {
        if (current == null) return previous
        if (previous == null) return current
        val delta = abs(current - previous)
        val alpha = if (delta > 0.7f) 0.18f else 0.3f
        return previous + ((current - previous) * alpha)
    }

    private fun formatMetersForNote(distanceMeters: Float): String {
        return if (distanceMeters < 1f) {
            "${(distanceMeters * 100f).toInt()}cm"
        } else {
            String.format("%.1fm", distanceMeters)
        }
    }

    private fun computeRawDepthConfidenceScore(
        rawDepthStatus: DepthAcquisitionStatus,
        rawDepthValidRatio: Float,
        gridValidRatio: Float,
        gridAverageConfidence: Float,
    ): Int {
        return when (rawDepthStatus) {
            DepthAcquisitionStatus.AVAILABLE -> {
                val corridorCoverageScore = rawDepthValidRatio.coerceIn(0f, 1f) * 60f
                val gridCoverageScore = gridValidRatio.coerceIn(0f, 1f) * 25f
                val gridConfidenceScore = gridAverageConfidence.coerceIn(0f, 1f) * 15f
                (corridorCoverageScore + gridCoverageScore + gridConfidenceScore).toInt()
            }
            DepthAcquisitionStatus.NOT_YET_AVAILABLE -> 5
            DepthAcquisitionStatus.UNAVAILABLE,
            DepthAcquisitionStatus.DISABLED -> 0
        }.coerceIn(0, 100)
    }

    private fun Image.toUprightBitmap(
        rotationDegrees: Int,
        maxLongSide: Int?,
    ): Bitmap {
        val imageWidth = width
        val imageHeight = height
        val normalizedRotation = normalizeRotationDegrees(rotationDegrees)
        val fullUprightWidth = if (normalizedRotation == 90 || normalizedRotation == 270) {
            imageHeight
        } else {
            imageWidth
        }
        val fullUprightHeight = if (normalizedRotation == 90 || normalizedRotation == 270) {
            imageWidth
        } else {
            imageHeight
        }
        val boundedLongSide = maxLongSide?.takeIf { it > 0 }
        val scale = boundedLongSide
            ?.takeIf { max(fullUprightWidth, fullUprightHeight) > it }
            ?.let { it.toFloat() / max(fullUprightWidth, fullUprightHeight).toFloat() }
            ?: 1f
        val outputWidth = (fullUprightWidth * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (fullUprightHeight * scale).roundToInt().coerceAtLeast(1)
        val pixelCount = outputWidth * outputHeight
        if (frameArgbPixels.size < pixelCount) {
            frameArgbPixels = IntArray(pixelCount)
        }
        yuv420888ToUprightArgbPixels(
            image = this,
            rotationDegrees = normalizedRotation,
            output = frameArgbPixels,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            fullUprightWidth = fullUprightWidth,
            fullUprightHeight = fullUprightHeight,
        )
        return bitmapFromArgbPixels(
            pixels = frameArgbPixels,
            width = outputWidth,
            height = outputHeight,
        )
    }

    private fun normalizeRotationDegrees(rotationDegrees: Int): Int {
        return ((rotationDegrees % 360) + 360) % 360
    }

    private fun bitmapFromArgbPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun yuv420888ToUprightArgbPixels(
        image: Image,
        output: IntArray,
        rotationDegrees: Int,
        outputWidth: Int,
        outputHeight: Int,
        fullUprightWidth: Int,
        fullUprightHeight: Int,
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val width = image.width
        val height = image.height
        val xScale = fullUprightWidth.toFloat() / outputWidth.toFloat()
        val yScale = fullUprightHeight.toFloat() / outputHeight.toFloat()

        var outputIndex = 0
        for (outputY in 0 until outputHeight) {
            val uprightY = ((outputY + 0.5f) * yScale) - 0.5f
            for (outputX in 0 until outputWidth) {
                val uprightX = ((outputX + 0.5f) * xScale) - 0.5f
                val sourceXFloat: Float
                val sourceYFloat: Float
                when (rotationDegrees) {
                    90 -> {
                        sourceXFloat = uprightY
                        sourceYFloat = (height - 1) - uprightX
                    }
                    180 -> {
                        sourceXFloat = (width - 1) - uprightX
                        sourceYFloat = (height - 1) - uprightY
                    }
                    270 -> {
                        sourceXFloat = (width - 1) - uprightY
                        sourceYFloat = uprightX
                    }
                    else -> {
                        sourceXFloat = uprightX
                        sourceYFloat = uprightY
                    }
                }
                val sourceX = sourceXFloat.roundToInt().coerceIn(0, width - 1)
                val sourceY = sourceYFloat.roundToInt().coerceIn(0, height - 1)
                val yValue = yBuffer.getUnsigned((sourceY * yPlane.rowStride) + (sourceX * yPlane.pixelStride))
                val uOffset = ((sourceY / 2) * uPlane.rowStride) + ((sourceX / 2) * uPlane.pixelStride)
                val vOffset = ((sourceY / 2) * vPlane.rowStride) + ((sourceX / 2) * vPlane.pixelStride)
                val uValue = uBuffer.getUnsigned(uOffset)
                val vValue = vBuffer.getUnsigned(vOffset)
                output[outputIndex++] = yuvToArgb(yValue, uValue, vValue)
            }
        }
    }

    private fun ByteBuffer.getUnsigned(index: Int): Int {
        return get(index).toInt() and 0xFF
    }

    private fun yuvToArgb(
        yValue: Int,
        uValue: Int,
        vValue: Int,
    ): Int {
        val y = (yValue - 16).coerceAtLeast(0)
        val u = uValue - 128
        val v = vValue - 128

        val y1192 = 1192 * y
        val red = clampRgb((y1192 + (1634 * v)) shr 10)
        val green = clampRgb((y1192 - (833 * v) - (400 * u)) shr 10)
        val blue = clampRgb((y1192 + (2066 * u)) shr 10)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun clampRgb(value: Int): Int {
        return value.coerceIn(0, 255)
    }


    private fun computePitchDownDegrees(frame: Frame): Float {
        val zAxis = frame.camera.displayOrientedPose.zAxis
        val forwardX = -zAxis[0]
        val forwardY = -zAxis[1]
        val forwardZ = -zAxis[2]
        val horizontal = sqrt((forwardX * forwardX) + (forwardZ * forwardZ))
        return Math.toDegrees(atan2(forwardY.toDouble(), horizontal.toDouble())).toFloat().coerceIn(0f, 85f)
    }

    private fun computeMotionSpeed(frame: Frame): Float? {
        val timestampNs = frame.timestamp
        val cameraPose = frame.camera.displayOrientedPose
        val previousTimestampNs = lastTimestampNs
        val previousX = lastCameraX
        val previousY = lastCameraY
        val previousZ = lastCameraZ

        lastTimestampNs = timestampNs
        lastCameraX = cameraPose.tx()
        lastCameraY = cameraPose.ty()
        lastCameraZ = cameraPose.tz()

        if (previousTimestampNs == null || previousX == null || previousY == null || previousZ == null) {
            return null
        }

        val dtSeconds = (timestampNs - previousTimestampNs) / 1_000_000_000f
        if (dtSeconds <= 0f) return null

        val movement = distanceMeters(
            previousX,
            previousY,
            previousZ,
            cameraPose.tx(),
            cameraPose.ty(),
            cameraPose.tz(),
        )
        return movement / dtSeconds
    }

    private fun computeApproachSpeed(collisionDistance: Float?): Float? {
        if (collisionDistance == null) {
            collisionHistory.clear()
            return null
        }
        collisionHistory.addLast(collisionDistance)
        while (collisionHistory.size > 8) {
            collisionHistory.removeFirst()
        }
        if (collisionHistory.size < 3) return null

        val oldest = collisionHistory.first()
        val newest = collisionHistory.last()
        val samples = collisionHistory.size - 1
        return ((oldest - newest) / samples).coerceAtLeast(0f) * 10f
    }

    private fun computeTtcRisk(
        collisionDistance: Float?,
        approachSpeed: Float?,
        motionSpeed: Float?,
    ): TtcRiskResult {
        if (collisionDistance == null) return TtcRiskResult("searching", null)

        val relativeClosingSpeed = maxOf(approachSpeed ?: 0f, motionSpeed ?: 0f)
        val ttcSeconds = if (relativeClosingSpeed > 0.05f) {
            (collisionDistance / relativeClosingSpeed).takeIf { it.isFinite() && it in 0.1f..12f }
        } else {
            null
        }

        val label = when {
            collisionDistance < 0.55f -> "critical"
            ttcSeconds != null && ttcSeconds <= 1.5f -> "critical"
            collisionDistance < 0.9f -> "high"
            ttcSeconds != null && ttcSeconds <= 3.0f -> "high"
            ttcSeconds != null && ttcSeconds <= 5.0f -> "watch"
            collisionDistance < 1.6f -> "watch"
            else -> "stable"
        }

        return TtcRiskResult(label, ttcSeconds)
    }

    private fun computeSuggestedDirection(
        leftDistance: Float?,
        centerDistance: Float?,
        rightDistance: Float?,
        leftLaneScore: Float? = null,
        centerLaneScore: Float? = null,
        rightLaneScore: Float? = null,
    ): String {
        val options = listOf(
            Triple("left", leftDistance, leftLaneScore),
            Triple("center", centerDistance, centerLaneScore),
            Triple("right", rightDistance, rightLaneScore),
        ).filter { it.second != null || it.third != null }

        if (options.isEmpty()) return "searching"

        val best = options.maxByOrNull {
            it.third ?: ((it.second ?: 0f).coerceIn(0f, 5f) / 5f)
        } ?: return "searching"
        val bestDistance = best.second
        val bestScore = best.third
        val center = centerDistance

        return when {
            center != null &&
                center >= 1.4f &&
                centerLaneScore != null &&
                bestScore != null &&
                centerLaneScore >= (bestScore - 0.05f) -> "center"
            bestDistance != null && bestDistance < 0.9f -> "blocked"
            else -> best.first
        }
    }

    private fun stabilizeDirection(
        instantDirection: String,
        riskLabel: String,
    ): String {
        directionHistory.addLast(instantDirection)
        while (directionHistory.size > 7) {
            directionHistory.removeFirst()
        }
        val scores = linkedMapOf(
            "left" to 0f,
            "center" to 0f,
            "right" to 0f,
            "blocked" to 0f,
            "searching" to 0f,
        )
        directionHistory.forEachIndexed { index, direction ->
            val weight = (index + 1).toFloat()
            scores[direction] = (scores[direction] ?: 0f) + weight
        }
        val candidate = scores.maxByOrNull { it.value }?.key ?: instantDirection
        if (stableDirection == "searching") {
            stableDirection = candidate
            return stableDirection
        }

        val currentScore = scores[stableDirection] ?: 0f
        val candidateScore = scores[candidate] ?: 0f
        val hysteresis = if (riskLabel == "critical" || riskLabel == "high") 1.05f else 1.2f

        if (candidate != stableDirection && candidateScore > currentScore * hysteresis) {
            stableDirection = candidate
        }
        return stableDirection
    }

    private fun computeGuidanceLabel(
        suggestedDirection: String,
        collisionDistance: Float?,
        riskLabel: String,
    ): String {
        if (collisionDistance == null) {
            return "주변을 인식하는 중입니다."
        }
        if (riskLabel == "critical") {
            return "전방 장애물이 매우 가깝습니다."
        }
        if (riskLabel == "high") {
            return "전방 장애물을 주의하세요."
        }
        return when (suggestedDirection) {
            "blocked" -> "전방 공간이 좁아 보입니다."
            else -> "전방 공간을 인식 중입니다."
        }
    }

    private fun computeGuidanceLabelForScene(
        suggestedDirection: String,
        collisionDistance: Float?,
        riskLabel: String,
        nearestPerson: ObjectOverlayDetection?,
    ): String {
        val baseLabel = computeGuidanceLabel(
            suggestedDirection = suggestedDirection,
            collisionDistance = collisionDistance,
            riskLabel = riskLabel,
        )
        val personDistance = nearestPerson?.distanceMeters ?: return baseLabel
        if (personDistance > 3.5f) return baseLabel
        return "전방에 사람이 감지되었습니다."
    }

    private fun guidanceLabelWithVlm(
        primaryGuidanceLabel: String,
        vlmInterpretation: VlmSceneInterpretation?,
    ): String {
        if (vlmInterpretation == null) return primaryGuidanceLabel
        return when (vlmInterpretation.risk) {
            VlmWalkingRisk.BLOCKED -> "전방 화면 분석상 이동이 어려워 보입니다. $primaryGuidanceLabel"
            VlmWalkingRisk.CAUTION -> "전방 화면 분석상 주의가 필요합니다. $primaryGuidanceLabel"
            else -> primaryGuidanceLabel
        }
    }

    private fun distanceMeters(
        ax: Float,
        ay: Float,
        az: Float,
        bx: Float,
        by: Float,
        bz: Float,
    ): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        private const val TAG = "WalkAssistVlm"
        private const val LIVE_VLM_FRAME_INTERVAL_MS = 500L
        private const val YOLO_ANALYSIS_MAX_LONG_SIDE = 640
        private const val VLM_ANALYSIS_MAX_LONG_SIDE = 768
        private const val LIVE_VLM_ANALYSIS_MAX_LONG_SIDE = 384
        private const val DEPTH_MEASUREMENT_LOG_INTERVAL_MS = 1_000L
        private const val GEOSPATIAL_RECONFIGURE_RETRY_MS = 2_000L
        private const val CROSSWALK_PATTERN_STALE_NANOS = 1_500_000_000L
        private const val CROSSWALK_HISTORY_SIZE = 5
        private const val CROSSWALK_MAP_PRIOR_RADIUS_METERS = 45.0
        private const val CROSSWALK_HEADING_PRIOR_DEGREES = 100.0
        private const val RAW_DEPTH_VISUALIZATION_MAX_MM = 10_000
        private const val GEOSPATIAL_DEPTH_VISUALIZATION_MAX_MM = 65_000
        private val EMPTY_FRAME_ANALYSIS = FrameAnalysis(
            detections = emptyList(),
            nearestObstacle = null,
            floorSegmentation = null,
            pathMetrics = null,
        )
        private val EMPTY_CROSSWALK_RESULT = CrosswalkPatternResult(
            detected = false,
            score = 0f,
            stripeCount = 0,
            yoloConfidence = 0f,
            modeLabel = "unavailable",
        )
        private val REPLAY_METADATA_TRACK_ID: UUID =
            UUID.fromString("7f5ee9f8-52b9-4e43-9c2c-89bb5f927af4")
    }
}
