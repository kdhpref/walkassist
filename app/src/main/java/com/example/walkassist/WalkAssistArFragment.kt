package com.example.walkassist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import com.example.walkassist.ocr.OneShotOcrReader
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Point
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.Track
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.RecordingFailedException
import com.google.ar.core.exceptions.UnavailableException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
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

    private data class FloorMaskState(
        val segmentation: FloorSegmentationResult,
        val imageWidth: Int,
        val imageHeight: Int,
        val timestampNanos: Long,
    )

    private data class TtcRiskResult(
        val label: String,
        val timeToCollisionSeconds: Float?,
    )

    private data class ObjectMotionMemory(
        val centerXRatio: Float,
        val distanceMeters: Float?,
        val timestampNanos: Long,
    )

    private data class CrosswalkVisionState(
        val result: CrosswalkPatternResult,
        val timestampNanos: Long,
    )

    private data class VlmVisionState(
        val interpretation: VlmSceneInterpretation,
        val timestampNanos: Long,
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
    private var lastCameraX: Float? = null
    private var lastCameraY: Float? = null
    private var lastCameraZ: Float? = null

    private val objectAnalyzerDelegate = lazy { ObjectAnalyzer(requireContext().applicationContext) }
    private val objectAnalyzer by objectAnalyzerDelegate
    private val floorSegmenterDelegate = lazy { ModelFloorSegmenter(requireContext().applicationContext) }
    private val floorSegmenter by floorSegmenterDelegate
    private val crosswalkPatternDetector by lazy { CrosswalkPatternDetector() }
    private val vlmSceneInterpreter by lazy { WalkAssistVlmFactory.create(requireContext().applicationContext) }
    private val vlmInvocationPolicy = VlmInvocationPolicy()
    private var oneShotOcrReader: OneShotOcrReader? = null
    private val objectTracker = ObjectTracker()
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
    private var lastFloorMaskState: FloorMaskState? = null
    @Volatile
    private var lastCrosswalkState: CrosswalkVisionState? = null
    @Volatile
    private var lastVlmVisionState: VlmVisionState? = null
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

    fun requestOneShotOcr() {
        if (oneShotOcrRequested.get() || oneShotOcrInFlight.get()) {
            dispatchOneShotOcrResult("이미 문자 인식 중입니다.")
            return
        }
        oneShotOcrRequested.set(true)
    }

    fun requestOneShotVlm() {
        if (oneShotVlmRequested.get() || oneShotVlmInFlight.get()) {
            dispatchOneShotVlmResult("이미 앞쪽 장면을 분석 중입니다. 잠시만 기다려 주세요.")
            return
        }
        oneShotVlmRequested.set(true)
    }

    fun setPlaneMeshDebugVisible(visible: Boolean) {
        planeMeshDebugVisible = visible
        updatePlaneMeshRendererVisibility()
    }

    private fun updatePlaneMeshRendererVisibility(visible: Boolean = planeMeshDebugVisible) {
        planeMeshDebugVisible = visible
    }

    private fun createSessionConfig(session: Session): Config {
        return Config(session).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                depthMode = Config.DepthMode.AUTOMATIC
            }
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
                session.configure(createSessionConfig(session))
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
                if (floorSegmenterDelegate.isInitialized()) {
                    floorSegmenter.close()
                }
            }.get(1, TimeUnit.SECONDS)
        }
        detectorExecutor.shutdownNow()
        vlmExecutor.shutdownNow()
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

    private fun publishFrameState(frame: Frame) {
        val debugFlags = WalkAssistSettings.debugPipelineFlags(requireContext())
        val camera = frame.camera
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
            ArMeasurementBridge.publish(
                ArMeasurementState(
                    trackingLabel = camera.trackingState.name.lowercase(),
                    trackingFailureLabel = camera.trackingFailureReason.name.lowercase().replace('_', ' '),
                    horizontalPlaneCount = horizontalPlaneCount,
                    verticalPlaneCount = verticalPlaneCount,
                    guidanceLabel = "Scan the floor and wall slowly.",
                    statusLabel = "Move the phone left and right over a textured floor.",
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
        val corridorHits = if (debugFlags.arCoreHitTestEnabled) sampleWorldCorridor(frame) else emptyList()
        val floorMaskState = if (debugFlags.floorSegmentationEnabled) currentFloorMaskState(frame.timestamp) else null
        val floorDebugMaskState =
            if (debugFlags.floorSegmentationEnabled) currentFloorDebugMaskState(frame.timestamp) else null
        val crosswalkState = if (debugFlags.crosswalkEnabled) currentCrosswalkState(frame.timestamp) else null
        val vlmVisionState = if (debugFlags.vlmEnabled) currentVlmVisionState(frame.timestamp) else null
        val rawDepthHits = if (debugFlags.rawDepthEnabled) sampleRawDepthCorridor(frame) else emptyList()
        val depthGridCells = if (debugFlags.rawDepthEnabled) sampleRawDepthGrid(frame) else emptyList()
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

        val leftLane = corridorLaneDistances(corridorHits, rawDepthHits, "left", floorMaskState)
        val centerLane = corridorLaneDistances(corridorHits, rawDepthHits, "center", floorMaskState)
        val rightLane = corridorLaneDistances(corridorHits, rawDepthHits, "right", floorMaskState)

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
        val sensingConfidenceScore = computeSensingConfidenceScore(
            horizontalPlaneCount = horizontalPlaneCount,
            verticalPlaneCount = verticalPlaneCount,
            collisionDistance = collisionDistance,
            rawDepthDistance = smoothedRawDepth,
            motionSpeed = motionSpeed,
            trackingFailureLabel = camera.trackingFailureReason.name,
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
                "Sweep the camera slowly until planes or depth points appear.",
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

        ArMeasurementBridge.publish(
            ArMeasurementState(
                trackingLabel = "tracking",
                horizontalPlaneCount = horizontalPlaneCount,
                verticalPlaneCount = verticalPlaneCount,
                sensingConfidenceScore = sensingConfidenceScore,
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
                crosswalkDetected = crosswalkState?.detected == true,
                crosswalkScore = crosswalkState?.score ?: 0f,
                crosswalkStripeCount = crosswalkState?.stripeCount ?: 0,
                crosswalkYoloConfidence = crosswalkState?.yoloConfidence ?: 0f,
                crosswalkModeLabel = crosswalkState?.modeLabel.orEmpty(),
                objectDetections = overlayDetections,
                depthGridCells = depthGridCells,
                floorOverlayColumns = floorDebugMaskState?.toOverlayColumns().orEmpty(),
                floorOverlayConfidence = floorDebugMaskState?.segmentation?.confidence ?: 0f,
                semanticClassMask = floorDebugMaskState?.segmentation?.classMask?.toOverlayMask(),
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

    private fun scheduleVisionAnalysis(
        frame: Frame,
        debugFlags: DebugPipelineFlags,
    ) {
        if (detectionInFlight.get()) return

        val ocrRequested = oneShotOcrRequested.get()
        val vlmRequested = oneShotVlmRequested.get()
        val backgroundVisionEnabled = debugFlags.yoloEnabled ||
            debugFlags.floorSegmentationEnabled ||
            debugFlags.crosswalkEnabled
        if (!backgroundVisionEnabled && !ocrRequested && !vlmRequested) {
            lastObjectDetections = emptyList()
            lastFloorMaskState = null
            lastCrosswalkState = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!ocrRequested && !vlmRequested && now - lastDetectionStartedAtMs < 450L) return

        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        } catch (_: IllegalStateException) {
            return
        }
        val rotationDegrees = displayRotationDegrees()
        val localCrosswalkDetector = crosswalkPatternDetector
        val pitchRadians = Math.toRadians(computePitchDownDegrees(frame).toDouble()).toFloat()
        val arStateSnapshot = ArMeasurementBridge.state.value

        lastDetectionStartedAtMs = now
        detectionInFlight.set(true)
        detectorExecutor.execute {
            var shouldRunOcr = false
            var ocrStarted = false
            var shouldRunVlm = false
            var vlmStarted = false
            try {
                val bitmap = image.toUprightBitmap(rotationDegrees)
                image.close()
                shouldRunOcr = oneShotOcrRequested.getAndSet(false)
                shouldRunVlm = oneShotVlmRequested.getAndSet(false)
                if (shouldRunVlm && debugFlags.vlmEnabled) {
                    val vlmFrame = SpatialFrame(
                        bitmap = bitmap,
                        timestampMillis = frame.timestamp / 1_000_000L,
                        source = SpatialFrameSource.LIVE_CAMERA,
                        pitchRadians = pitchRadians,
                        arState = arStateSnapshot,
                        requestMode = VlmRequestMode.MANUAL,
                    )
                    startBackgroundVlmCaption(
                        bitmap = bitmap,
                        spatialFrame = vlmFrame,
                        primaryAnalysis = EMPTY_FRAME_ANALYSIS,
                        crosswalk = EMPTY_CROSSWALK_RESULT,
                        timestampNanos = frame.timestamp,
                    )
                    vlmStarted = true
                } else if (shouldRunVlm) {
                    dispatchOneShotVlmResult("디버그 설정에서 VLM 파이프라인이 꺼져 있습니다.")
                }
                if (shouldRunOcr) {
                    ocrStarted = startOneShotOcr(bitmap)
                }
                val floorSegmentation = if (debugFlags.floorSegmentationEnabled) {
                    val localFloorSegmenter = floorSegmenter
                    localFloorSegmenter.segment(bitmap)
                } else {
                    null
                }
                lastFloorMaskState = floorSegmentation?.let {
                    FloorMaskState(
                        segmentation = it,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        timestampNanos = frame.timestamp,
                    )
                }
                val localObjectAnalyzer = if (debugFlags.yoloEnabled) objectAnalyzer else null
                val detectedObjects = if (localObjectAnalyzer?.isReady() == true) {
                    localObjectAnalyzer.detect(bitmap)
                } else {
                    emptyList()
                }
                val yoloCrosswalkConfidence = detectedObjects
                    .filter { it.label.equals("crosswalk", ignoreCase = true) }
                    .maxOfOrNull { it.confidence } ?: 0f
                lastCrosswalkState = if (debugFlags.crosswalkEnabled) {
                    CrosswalkVisionState(
                        result = localCrosswalkDetector.detect(
                            bitmap = bitmap,
                            floorSegmentation = floorSegmentation,
                            yoloConfidence = yoloCrosswalkConfidence,
                        ),
                        timestampNanos = frame.timestamp,
                    )
                } else {
                    null
                }
                val crosswalk = lastCrosswalkState?.result ?: CrosswalkPatternResult(
                    detected = false,
                    score = 0f,
                    stripeCount = 0,
                    yoloConfidence = 0f,
                    modeLabel = "unavailable",
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
                    floorSegmentation = floorSegmentation,
                    pathMetrics = null,
                )
                val spatialFrame = SpatialFrame(
                    bitmap = bitmap,
                    timestampMillis = frame.timestamp / 1_000_000L,
                    source = SpatialFrameSource.LIVE_CAMERA,
                    pitchRadians = pitchRadians,
                    arState = arStateSnapshot,
                    requestMode = if (shouldRunVlm) VlmRequestMode.MANUAL else VlmRequestMode.AUTO,
                )
                if (shouldRunVlm && debugFlags.vlmEnabled && !vlmStarted) {
                    startBackgroundVlmCaption(
                        bitmap = bitmap,
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
                            ((detection.boundingBox.left + detection.boundingBox.right) * 0.5f / bitmap.width)
                                .coerceIn(0f, 1f)
                        ObjectOverlayDetection(
                            leftRatio = (detection.boundingBox.left / bitmap.width).coerceIn(0f, 1f),
                            topRatio = (detection.boundingBox.top / bitmap.height).coerceIn(0f, 1f),
                            widthRatio = (detection.boundingBox.width() / bitmap.width).coerceIn(0.02f, 1f),
                            heightRatio = (detection.boundingBox.height() / bitmap.height).coerceIn(0.02f, 1f),
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
                bitmap.recycle()
            } catch (_: Exception) {
                runCatching { image.close() }
                if (shouldRunVlm) {
                    oneShotVlmRequested.set(false)
                    oneShotVlmInFlight.set(false)
                    dispatchOneShotVlmResult("장면 분석에 실패했습니다. 잠시 멈춰 주세요.")
                }
                if (shouldRunOcr && !ocrStarted) {
                    oneShotOcrInFlight.set(false)
                    dispatchOneShotOcrResult("문자를 읽을 이미지를 가져오지 못했습니다.")
                }
            } finally {
                detectionInFlight.set(false)
            }
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
            return
        }
        val localVlmInterpreter = vlmSceneInterpreter
        val modelFrame = spatialFrame.copy(bitmap = vlmBitmap)
        vlmExecutor.execute {
            val vlmStartedAtMs = SystemClock.elapsedRealtime()
            try {
                Log.d(TAG, "VLM invoke manual=true timestampMs=${modelFrame.timestampMillis}")
                val interpretation = localVlmInterpreter.interpret(
                    frame = modelFrame,
                    primaryAnalysis = primaryAnalysis,
                    crosswalk = crosswalk,
                )
                Log.d(
                    TAG,
                    "VLM result manual=true elapsedMs=${SystemClock.elapsedRealtime() - vlmStartedAtMs} risk=${interpretation?.risk}",
                )
                if (interpretation != null) {
                    lastVlmVisionState = VlmVisionState(
                        interpretation = interpretation,
                        timestampNanos = timestampNanos,
                    )
                    dispatchOneShotVlmResult(interpretation.pathSummary)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Background VLM caption failed", error)
            } finally {
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
        floorMaskState: FloorMaskState?,
    ): LaneDistances {
        val filtered = hits.filter { classifyLane(it.lateralMeters) == lane }
        val obstacleHits = filtered.filter {
            it.source != HitSource.FLOOR && !isInsideWalkableFloorMask(it, floorMaskState)
        }
        val rawDepth = robustLaneRawDepthDistance(rawDepthHits, lane, floorMaskState)
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
        floorMaskState: FloorMaskState?,
    ): Float? {
        val laneDistances = rawDepthHits
            .filter { classifyLane(it.lateralMeters) == lane }
            .filterNot { isInsideWalkableFloorMask(it, floorMaskState) }
            .map { it.distanceMeters }
            .sorted()

        if (laneDistances.isEmpty()) return null

        // Avoid using the single nearest raw-depth point because it is often a flickering outlier.
        val percentileIndex = ((laneDistances.size - 1) * 0.3f).toInt().coerceIn(0, laneDistances.lastIndex)
        return laneDistances[percentileIndex]
    }

    private fun currentFloorMaskState(frameTimestampNanos: Long): FloorMaskState? {
        val state = lastFloorMaskState ?: return null
        val ageSeconds = (frameTimestampNanos - state.timestampNanos) / 1_000_000_000f
        return if (ageSeconds in 0f..2.0f && state.segmentation.confidence >= 0.25f) state else null
    }

    private fun currentFloorDebugMaskState(frameTimestampNanos: Long): FloorMaskState? {
        val state = lastFloorMaskState ?: return null
        val ageSeconds = (frameTimestampNanos - state.timestampNanos) / 1_000_000_000f
        return if (ageSeconds in 0f..10.0f) state else null
    }

    private fun FloorMaskState.toOverlayColumns(): List<FloorOverlayColumn> {
        val boundary = segmentation.boundaryYByColumn
        if (boundary.isEmpty()) return emptyList()

        return buildList {
            for (column in boundary.indices) {
                val boundaryY = boundary[column]
                if (boundaryY < 0) continue
                add(
                    FloorOverlayColumn(
                    xRatio = if (boundary.size == 1) 0f else column / (boundary.size - 1).toFloat(),
                    boundaryYRatio = boundaryY / segmentation.height.toFloat(),
                    ),
                )
            }
        }
    }

    private fun SemanticClassMask.toOverlayMask(): SemanticClassMaskOverlay {
        return SemanticClassMaskOverlay(
            width = width,
            height = height,
            classIds = classIds,
        )
    }

    private fun currentCrosswalkState(frameTimestampNanos: Long): CrosswalkPatternResult? {
        val state = lastCrosswalkState ?: return null
        val ageSeconds = (frameTimestampNanos - state.timestampNanos) / 1_000_000_000f
        return if (ageSeconds in 0f..2.5f) state.result else null
    }

    private fun currentVlmVisionState(frameTimestampNanos: Long): VlmVisionState? {
        val state = lastVlmVisionState ?: return null
        val ageSeconds = (frameTimestampNanos - state.timestampNanos) / 1_000_000_000f
        return if (ageSeconds in 0f..4.0f) state else null
    }

    private fun isInsideWalkableFloorMask(
        hit: CorridorHit,
        floorMaskState: FloorMaskState?,
    ): Boolean {
        val state = floorMaskState ?: return false
        val imageX = hit.viewXRatio * state.imageWidth.toFloat()
        val imageY = hit.viewYRatio * state.imageHeight.toFloat()
        val floorBoundaryY = state.segmentation.boundaryYAt(
            imageX = imageX,
            imageWidth = state.imageWidth,
            imageHeight = state.imageHeight,
        ) ?: return false
        val floorMarginPixels = state.imageHeight * 0.025f
        return imageY >= floorBoundaryY - floorMarginPixels
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
    ): List<ObjectOverlayDetection> {
        if (detections.isEmpty()) return emptyList()
        if (!rawDepthEnabled) {
            return detections
        }

        return try {
            frame.acquireRawDepthImage16Bits().use { rawDepthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    detections.map { detection ->
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
            }
        } catch (_: NotYetAvailableException) {
            detections
        } catch (_: IllegalStateException) {
            detections
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

    private fun sampleRawDepthCorridor(frame: Frame): List<CorridorHit> {
        val width = viewWidth()
        val height = viewHeight()
        val sampleXs = listOf(0.18f, 0.3f, 0.42f, 0.5f, 0.58f, 0.7f, 0.82f)
        val sampleYs = listOf(0.42f, 0.54f, 0.66f, 0.78f)

        return try {
            frame.acquireRawDepthImage16Bits().use { rawDepthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    buildList {
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
                                    add(hit)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: NotYetAvailableException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    private fun sampleRawDepthGrid(frame: Frame): List<DepthGridCell> {
        val width = viewWidth()
        val height = viewHeight()
        return try {
            frame.acquireRawDepthImage16Bits().use { rawDepthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    buildList {
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
                                )
                                add(
                                    DepthGridCell(
                                        column = column,
                                        row = row,
                                        distanceMeters = sample?.depthMillimeters?.let { it / 1000f },
                                        confidence = sample?.confidence ?: 0f,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: NotYetAvailableException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    private fun rawDepthSampleAtViewPoint(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        viewX: Float,
        viewY: Float,
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
                if (depthMillimeters in 150..10000) {
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

    private fun computeSensingConfidenceScore(
        horizontalPlaneCount: Int,
        verticalPlaneCount: Int,
        collisionDistance: Float?,
        rawDepthDistance: Float?,
        motionSpeed: Float?,
        trackingFailureLabel: String,
    ): Int {
        var score = 35
        score += (horizontalPlaneCount.coerceAtMost(2) * 10)
        score += (verticalPlaneCount.coerceAtMost(2) * 7)
        if (collisionDistance != null) score += 16
        if (rawDepthDistance != null) score += 18

        val motionPenalty = when {
            motionSpeed == null -> 0
            motionSpeed > 0.45f -> 18
            motionSpeed > 0.22f -> 10
            motionSpeed > 0.12f -> 4
            else -> 0
        }
        score -= motionPenalty

        if (trackingFailureLabel != "NONE") {
            score -= 15
        }
        return score.coerceIn(0, 100)
    }

    private fun Image.toUprightBitmap(rotationDegrees: Int): Bitmap {
        val nv21 = yuv420888ToNv21(this)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 88, out)
        val jpegBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: error("Failed to decode ARCore camera frame")

        if (rotationDegrees == 0) {
            return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = image.width * image.height
        val uvSize = image.width * image.height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        copyPlane(
            buffer = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
            width = image.width,
            height = image.height,
            output = nv21,
            outputOffset = 0,
            outputStride = 1,
        )
        copyPlane(
            buffer = vPlane.buffer,
            rowStride = vPlane.rowStride,
            pixelStride = vPlane.pixelStride,
            width = image.width / 2,
            height = image.height / 2,
            output = nv21,
            outputOffset = ySize,
            outputStride = 2,
        )
        copyPlane(
            buffer = uPlane.buffer,
            rowStride = uPlane.rowStride,
            pixelStride = uPlane.pixelStride,
            width = image.width / 2,
            height = image.height / 2,
            output = nv21,
            outputOffset = ySize + 1,
            outputStride = 2,
        )

        return nv21
    }

    private fun copyPlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputStride: Int,
    ) {
        buffer.rewind()
        val rowBuffer = ByteArray(rowStride)
        var outputIndex = outputOffset

        for (row in 0 until height) {
            val length = if (pixelStride == 1 && outputStride == 1) {
                width
            } else {
                (width - 1) * pixelStride + 1
            }

            buffer.get(rowBuffer, 0, length)

            var inputIndex = 0
            for (col in 0 until width) {
                output[outputIndex] = rowBuffer[inputIndex]
                outputIndex += outputStride
                inputIndex += pixelStride
            }

            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - length)
            }
        }
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
            VlmWalkingRisk.BLOCKED -> "앞쪽 장면 분석상 이동이 어려워 보입니다. $primaryGuidanceLabel"
            VlmWalkingRisk.CAUTION -> "앞쪽 장면 분석상 주의가 필요합니다. $primaryGuidanceLabel"
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
