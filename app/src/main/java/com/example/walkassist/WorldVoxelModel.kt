package com.example.walkassist

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class WorldVoxelObservation(
    val worldX: Float,
    val worldY: Float,
    val worldZ: Float,
    val source: String,
    val strength: Float,
    val confidence: Float,
)

private data class VoxelKey(
    val x: Int,
    val y: Int,
    val z: Int,
)

private data class VoxelState(
    var occupiedEvidence: Float = 0f,
    var freeEvidence: Float = 0f,
    var observationCount: Int = 0,
) {
    fun occupancyScore(): Float {
        val total = occupiedEvidence + freeEvidence
        if (total <= 0.001f) return 0f
        return ((occupiedEvidence - freeEvidence) / total).coerceIn(-1f, 1f)
    }

    fun confidenceScore(): Float {
        val evidenceFactor = ((occupiedEvidence + freeEvidence) / 2.2f).coerceIn(0f, 1f)
        val observationFactor = (observationCount / 6f).coerceIn(0f, 1f)
        return ((evidenceFactor * 0.55f) + (observationFactor * 0.45f)).coerceIn(0f, 1f)
    }

    fun isKnown(): Boolean = observationCount >= 2 && abs(occupancyScore()) >= 0.08f

    fun isOccupied(): Boolean = occupancyScore() >= 0.2f && confidenceScore() >= 0.22f
}

class WorldVoxelModel(
    private val halfRangeMeters: Float = 5f,
    private val heightRangeMeters: Float = 2.5f,
    private val voxelSizeMeters: Float = 0.25f,
) {
    private val voxels = mutableMapOf<VoxelKey, VoxelState>()

    fun update(
        cameraWorldX: Float,
        cameraWorldY: Float,
        cameraWorldZ: Float,
        observations: List<WorldVoxelObservation>,
    ) {
        trimOutsideRange(cameraWorldX, cameraWorldY, cameraWorldZ)
        observations.forEach { observation ->
            clearRay(
                startX = cameraWorldX,
                startY = cameraWorldY,
                startZ = cameraWorldZ,
                endX = observation.worldX,
                endY = observation.worldY,
                endZ = observation.worldZ,
                confidence = observation.confidence,
            )
            markOccupied(observation)
        }
        pruneWeakVoxels()
    }

    fun snapshot(
        cameraWorldX: Float,
        cameraWorldY: Float,
        cameraWorldZ: Float,
        cameraXAxisX: Float,
        cameraXAxisZ: Float,
        cameraZAxisX: Float,
        cameraZAxisZ: Float,
    ): List<VoxelColumnUi> {
        data class ColumnAccumulator(
            var maxOccupancy: Float = -1f,
            var maxConfidence: Float = 0f,
            var highestWorldY: Float = Float.NEGATIVE_INFINITY,
            var voxelCount: Int = 0,
        )

        val columns = mutableMapOf<Pair<Int, Int>, ColumnAccumulator>()

        voxels.forEach { (key, state) ->
            if (!state.isKnown() || !state.isOccupied()) return@forEach
            val columnKey = key.x to key.z
            val accumulator = columns.getOrPut(columnKey) { ColumnAccumulator() }
            accumulator.maxOccupancy = maxOf(accumulator.maxOccupancy, state.occupancyScore())
            accumulator.maxConfidence = maxOf(accumulator.maxConfidence, state.confidenceScore())
            accumulator.highestWorldY = maxOf(accumulator.highestWorldY, key.y * voxelSizeMeters)
            accumulator.voxelCount += 1
        }

        return columns.mapNotNull { (columnKey, accumulator) ->
            val worldX = columnKey.first * voxelSizeMeters
            val worldZ = columnKey.second * voxelSizeMeters
            val dx = worldX - cameraWorldX
            val dz = worldZ - cameraWorldZ
            val relativeX = (dx * cameraXAxisX) + (dz * cameraXAxisZ)
            val relativeZ = -((dx * cameraZAxisX) + (dz * cameraZAxisZ))
            if (abs(relativeX) > halfRangeMeters || abs(relativeZ) > halfRangeMeters) {
                return@mapNotNull null
            }

            VoxelColumnUi(
                relativeX = relativeX,
                relativeZ = relativeZ,
                occupancyScore = accumulator.maxOccupancy.coerceIn(0f, 1f),
                confidenceScore = accumulator.maxConfidence.coerceIn(0f, 1f),
                heightMeters = (accumulator.highestWorldY - cameraWorldY).coerceIn(-1.5f, heightRangeMeters),
                voxelCount = accumulator.voxelCount,
            )
        }
    }

    fun snapshotPoints(
        cameraWorldX: Float,
        cameraWorldY: Float,
        cameraWorldZ: Float,
        cameraXAxisX: Float,
        cameraXAxisZ: Float,
        cameraZAxisX: Float,
        cameraZAxisZ: Float,
    ): List<VoxelPointUi> {
        return voxels.mapNotNull { (key, state) ->
            if (!state.isKnown() || !state.isOccupied()) return@mapNotNull null

            val worldX = key.x * voxelSizeMeters
            val worldY = key.y * voxelSizeMeters
            val worldZ = key.z * voxelSizeMeters
            val dx = worldX - cameraWorldX
            val dy = worldY - cameraWorldY
            val dz = worldZ - cameraWorldZ
            val relativeX = (dx * cameraXAxisX) + (dz * cameraXAxisZ)
            val relativeZ = -((dx * cameraZAxisX) + (dz * cameraZAxisZ))
            if (abs(relativeX) > halfRangeMeters || abs(relativeZ) > halfRangeMeters || abs(dy) > heightRangeMeters) {
                return@mapNotNull null
            }

            VoxelPointUi(
                worldX = worldX,
                worldY = worldY,
                worldZ = worldZ,
                relativeX = relativeX,
                relativeY = dy,
                relativeZ = relativeZ,
                occupancyScore = state.occupancyScore().coerceIn(0f, 1f),
                confidenceScore = state.confidenceScore().coerceIn(0f, 1f),
            )
        }
    }

    fun knownVoxelCount(): Int = voxels.values.count { it.isKnown() }

    fun occupiedVoxelCount(): Int = voxels.values.count { it.isOccupied() }

    fun obstacleColumnCount(): Int {
        return voxels.entries
            .filter { (_, state) -> state.isOccupied() }
            .map { (key, _) -> key.x to key.z }
            .distinct()
            .count()
    }

    fun averageConfidenceScore(): Int {
        val known = voxels.values.filter { it.isKnown() }
        if (known.isEmpty()) return 0
        return (known.map { it.confidenceScore() }.average() * 100.0).toInt().coerceIn(0, 100)
    }

    fun rangeMeters(): Float = halfRangeMeters

    fun voxelSizeMeters(): Float = voxelSizeMeters

    private fun trimOutsideRange(
        cameraWorldX: Float,
        cameraWorldY: Float,
        cameraWorldZ: Float,
    ) {
        val iterator = voxels.iterator()
        while (iterator.hasNext()) {
            val (key, _) = iterator.next()
            val dx = (key.x * voxelSizeMeters) - cameraWorldX
            val dy = (key.y * voxelSizeMeters) - cameraWorldY
            val dz = (key.z * voxelSizeMeters) - cameraWorldZ
            if (sqrt((dx * dx) + (dz * dz)) > halfRangeMeters || abs(dy) > heightRangeMeters) {
                iterator.remove()
            }
        }
    }

    private fun clearRay(
        startX: Float,
        startY: Float,
        startZ: Float,
        endX: Float,
        endY: Float,
        endZ: Float,
        confidence: Float,
    ) {
        val dx = endX - startX
        val dy = endY - startY
        val dz = endZ - startZ
        val distance = sqrt((dx * dx) + (dy * dy) + (dz * dz))
        if (distance < voxelSizeMeters * 1.5f) return

        val steps = (distance / voxelSizeMeters).roundToInt().coerceAtLeast(1)
        for (index in 1 until steps) {
            val t = index.toFloat() / steps.toFloat()
            updateVoxel(
                worldX = startX + (dx * t),
                worldY = startY + (dy * t),
                worldZ = startZ + (dz * t),
                occupiedDelta = 0f,
                freeDelta = 0.04f + (0.05f * confidence.coerceIn(0f, 1f)),
            )
        }
    }

    private fun markOccupied(observation: WorldVoxelObservation) {
        val weightedStrength = observation.strength * observation.confidence.coerceIn(0f, 1f)
        val horizontalRadius = if (weightedStrength >= 0.34f) 1 else 0
        val verticalRadius = if (observation.source == "WALL") 1 else 0
        for (offsetX in -horizontalRadius..horizontalRadius) {
            for (offsetY in -verticalRadius..verticalRadius) {
                for (offsetZ in -horizontalRadius..horizontalRadius) {
                    val attenuation = when {
                        offsetX == 0 && offsetY == 0 && offsetZ == 0 -> 1f
                        offsetY != 0 -> 0.46f
                        else -> 0.38f
                    }
                    updateVoxel(
                        worldX = observation.worldX + (offsetX * voxelSizeMeters),
                        worldY = observation.worldY + (offsetY * voxelSizeMeters),
                        worldZ = observation.worldZ + (offsetZ * voxelSizeMeters),
                        occupiedDelta = weightedStrength * attenuation,
                        freeDelta = 0f,
                    )
                }
            }
        }
    }

    private fun updateVoxel(
        worldX: Float,
        worldY: Float,
        worldZ: Float,
        occupiedDelta: Float,
        freeDelta: Float,
    ) {
        val key = VoxelKey(
            x = (worldX / voxelSizeMeters).roundToInt(),
            y = (worldY / voxelSizeMeters).roundToInt(),
            z = (worldZ / voxelSizeMeters).roundToInt(),
        )
        val state = voxels.getOrPut(key) { VoxelState() }
        if (occupiedDelta > 0f) {
            state.occupiedEvidence = (state.occupiedEvidence + occupiedDelta).coerceAtMost(5f)
            state.observationCount += 1
        }
        if (freeDelta > 0f) {
            state.freeEvidence = (state.freeEvidence + freeDelta).coerceAtMost(5f)
            state.observationCount += 1
        }
    }

    private fun pruneWeakVoxels() {
        val iterator = voxels.iterator()
        while (iterator.hasNext()) {
            val (_, state) = iterator.next()
            val totalEvidence = state.occupiedEvidence + state.freeEvidence
            if (state.observationCount <= 1 && totalEvidence < 0.12f) {
                iterator.remove()
            }
        }
    }
}
