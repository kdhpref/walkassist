package com.example.walkassist

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class WorldMapObservation(
    val worldX: Float,
    val worldZ: Float,
    val source: String,
    val strength: Float,
    val confidence: Float,
)

data class WorldMapCellUi(
    val relativeX: Float,
    val relativeZ: Float,
    val occupancyScore: Float,
    val confidenceScore: Float,
    val observationCount: Int,
)

private data class CellKey(
    val x: Int,
    val z: Int,
)

private data class CellState(
    var occupiedEvidence: Float = 0f,
    var freeEvidence: Float = 0f,
    var observationCount: Int = 0,
    var occupiedUpdates: Int = 0,
    var freeUpdates: Int = 0,
) {
    fun occupancyScore(): Float {
        val total = occupiedEvidence + freeEvidence
        if (total <= 0.001f) return 0f
        return ((occupiedEvidence - freeEvidence) / total).coerceIn(-1f, 1f)
    }

    fun confidenceScore(): Float {
        val evidence = (occupiedEvidence + freeEvidence).coerceAtLeast(0f)
        val observationFactor = (observationCount / 8f).coerceIn(0f, 1f)
        val evidenceFactor = (evidence / 2.4f).coerceIn(0f, 1f)
        return ((observationFactor * 0.65f) + (evidenceFactor * 0.35f)).coerceIn(0f, 1f)
    }

    fun isKnown(): Boolean = observationCount >= 2 && abs(occupancyScore()) >= 0.08f

    fun isOccupied(): Boolean = occupancyScore() >= 0.22f && confidenceScore() >= 0.2f
}

class WorldLocalMap(
    private val halfRangeMeters: Float = 5f,
    private val cellSizeMeters: Float = 0.2f,
) {
    private val cells = mutableMapOf<CellKey, CellState>()

    fun update(
        cameraWorldX: Float,
        cameraWorldZ: Float,
        observations: List<WorldMapObservation>,
    ) {
        trimOutsideRange(cameraWorldX, cameraWorldZ)
        observations.forEach { observation ->
            clearRay(
                startX = cameraWorldX,
                startZ = cameraWorldZ,
                endX = observation.worldX,
                endZ = observation.worldZ,
                confidence = observation.confidence,
            )
            markOccupied(observation)
        }
        pruneWeakCells()
    }

    fun snapshot(
        cameraWorldX: Float,
        cameraWorldZ: Float,
        cameraXAxisX: Float,
        cameraXAxisZ: Float,
        cameraZAxisX: Float,
        cameraZAxisZ: Float,
    ): List<WorldMapCellUi> {
        return cells.entries.mapNotNull { (key, state) ->
            if (!state.isKnown()) return@mapNotNull null

            val worldX = key.x * cellSizeMeters
            val worldZ = key.z * cellSizeMeters
            val dx = worldX - cameraWorldX
            val dz = worldZ - cameraWorldZ
            val relativeX = (dx * cameraXAxisX) + (dz * cameraXAxisZ)
            val relativeZ = -((dx * cameraZAxisX) + (dz * cameraZAxisZ))
            if (abs(relativeX) > halfRangeMeters || abs(relativeZ) > halfRangeMeters) {
                return@mapNotNull null
            }

            WorldMapCellUi(
                relativeX = relativeX,
                relativeZ = relativeZ,
                occupancyScore = state.occupancyScore(),
                confidenceScore = state.confidenceScore(),
                observationCount = state.observationCount,
            )
        }
    }

    fun occupiedCellCount(): Int = cells.values.count { it.isOccupied() }

    fun knownCellCount(): Int = cells.values.count { it.isKnown() }

    fun totalObservationCount(): Int = cells.values.sumOf { it.observationCount }

    fun averageConfidenceScore(): Int {
        val knownCells = cells.values.filter { it.isKnown() }
        if (knownCells.isEmpty()) return 0
        val average = knownCells.map { it.confidenceScore() }.average()
        return (average * 100.0).toInt().coerceIn(0, 100)
    }

    fun rangeMeters(): Float = halfRangeMeters

    fun cellSizeMeters(): Float = cellSizeMeters

    private fun trimOutsideRange(cameraWorldX: Float, cameraWorldZ: Float) {
        val iterator = cells.iterator()
        while (iterator.hasNext()) {
            val (key, _) = iterator.next()
            val dx = (key.x * cellSizeMeters) - cameraWorldX
            val dz = (key.z * cellSizeMeters) - cameraWorldZ
            if (sqrt((dx * dx) + (dz * dz)) > halfRangeMeters) {
                iterator.remove()
            }
        }
    }

    private fun clearRay(
        startX: Float,
        startZ: Float,
        endX: Float,
        endZ: Float,
        confidence: Float,
    ) {
        val dx = endX - startX
        val dz = endZ - startZ
        val distance = sqrt((dx * dx) + (dz * dz))
        if (distance < cellSizeMeters * 1.5f) return

        val steps = (distance / cellSizeMeters).roundToInt().coerceAtLeast(1)
        for (index in 1 until steps) {
            val t = index.toFloat() / steps.toFloat()
            val sampleX = startX + (dx * t)
            val sampleZ = startZ + (dz * t)
            updateCell(
                worldX = sampleX,
                worldZ = sampleZ,
                occupiedDelta = 0f,
                freeDelta = 0.05f + (0.05f * confidence.coerceIn(0f, 1f)),
            )
        }
    }

    private fun markOccupied(observation: WorldMapObservation) {
        val weightedStrength = observation.strength * observation.confidence.coerceIn(0f, 1f)
        val radiusInCells = if (weightedStrength >= 0.42f) 1 else 0
        for (offsetX in -radiusInCells..radiusInCells) {
            for (offsetZ in -radiusInCells..radiusInCells) {
                val attenuation = if (offsetX == 0 && offsetZ == 0) 1f else 0.42f
                val sampleX = observation.worldX + (offsetX * cellSizeMeters)
                val sampleZ = observation.worldZ + (offsetZ * cellSizeMeters)
                updateCell(
                    worldX = sampleX,
                    worldZ = sampleZ,
                    occupiedDelta = weightedStrength * attenuation,
                    freeDelta = 0f,
                )
            }
        }
    }

    private fun updateCell(
        worldX: Float,
        worldZ: Float,
        occupiedDelta: Float,
        freeDelta: Float,
    ) {
        val key = CellKey(
            x = (worldX / cellSizeMeters).roundToInt(),
            z = (worldZ / cellSizeMeters).roundToInt(),
        )
        val state = cells.getOrPut(key) { CellState() }

        if (occupiedDelta > 0f) {
            state.occupiedEvidence = (state.occupiedEvidence + occupiedDelta).coerceAtMost(6f)
            state.occupiedUpdates += 1
            state.observationCount += 1
        }
        if (freeDelta > 0f) {
            state.freeEvidence = (state.freeEvidence + freeDelta).coerceAtMost(6f)
            state.freeUpdates += 1
            state.observationCount += 1
        }
    }

    private fun pruneWeakCells() {
        val iterator = cells.iterator()
        while (iterator.hasNext()) {
            val (_, state) = iterator.next()
            val totalEvidence = state.occupiedEvidence + state.freeEvidence
            if (state.observationCount <= 1 && totalEvidence < 0.12f) {
                iterator.remove()
            }
        }
    }
}
