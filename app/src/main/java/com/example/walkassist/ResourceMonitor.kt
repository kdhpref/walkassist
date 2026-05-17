package com.example.walkassist

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import java.io.File
import kotlin.math.roundToInt

data class ResourceUsageSnapshot(
    val cpuPercent: Int = 0,
    val gpuPercent: Int? = null,
    val ramPercent: Int = 0,
    val ramMegabytes: Int = 0,
)

class ResourceMonitor(
    context: Context,
) {
    private data class CpuTimes(
        val processTicks: Long,
        val totalTicks: Long,
    )

    private data class GpuTimes(
        val busy: Long,
        val total: Long,
    )

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pid = Process.myPid()
    private var previousCpuTimes: CpuTimes? = null
    private var previousGpuTimes: GpuTimes? = null
    private val gpuBusyFiles = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/devfreq/5000000.gpu/gpu_busy",
        "/sys/class/devfreq/13000000.mali/utilization",
    )
    private val gpuPercentFiles = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/devfreq/5000000.gpu/load",
        "/sys/class/devfreq/13000000.mali/load",
    )

    fun sample(): ResourceUsageSnapshot {
        val cpuPercent = sampleCpuPercent()
        val gpuPercent = sampleGpuPercent()
        val ram = sampleRam()
        return ResourceUsageSnapshot(
            cpuPercent = cpuPercent,
            gpuPercent = gpuPercent,
            ramPercent = ram.first,
            ramMegabytes = ram.second,
        )
    }

    private fun sampleCpuPercent(): Int {
        val current = readCpuTimes() ?: return 0
        val previous = previousCpuTimes.also { previousCpuTimes = current } ?: return 0
        val processDelta = (current.processTicks - previous.processTicks).coerceAtLeast(0L)
        val totalDelta = (current.totalTicks - previous.totalTicks).coerceAtLeast(1L)
        return ((processDelta.toDouble() / totalDelta.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun readCpuTimes(): CpuTimes? {
        val processStat = runCatching { File("/proc/self/stat").readText() }.getOrNull() ?: return null
        val statTail = processStat.substringAfterLast(") ").split(' ')
        if (statTail.size <= 13) return null
        val userTicks = statTail.getOrNull(11)?.toLongOrNull() ?: return null
        val systemTicks = statTail.getOrNull(12)?.toLongOrNull() ?: return null
        val totalTicks = runCatching { File("/proc/stat").useLines { lines -> lines.first() } }
            .getOrNull()
            ?.split(Regex("\\s+"))
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            ?.sum()
            ?: return null
        return CpuTimes(
            processTicks = userTicks + systemTicks,
            totalTicks = totalTicks,
        )
    }

    private fun sampleGpuPercent(): Int? {
        readGpuBusyTimes()?.let { current ->
            val previous = previousGpuTimes.also { previousGpuTimes = current } ?: return null
            val busyDelta = (current.busy - previous.busy).coerceAtLeast(0L)
            val totalDelta = (current.total - previous.total).coerceAtLeast(1L)
            return ((busyDelta.toDouble() / totalDelta.toDouble()) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }

        return gpuPercentFiles.firstNotNullOfOrNull { path ->
            val value = runCatching { File(path).readText().trim() }.getOrNull()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toFloatOrNull()
            value?.let {
                val normalized = if (it > 100f) it / 10f else it
                normalized.roundToInt().coerceIn(0, 100)
            }
        }
    }

    private fun readGpuBusyTimes(): GpuTimes? {
        return gpuBusyFiles.firstNotNullOfOrNull { path ->
            val parts = runCatching { File(path).readText().trim().split(Regex("\\s+")) }.getOrNull()
            val busy = parts?.getOrNull(0)?.toLongOrNull()
            val total = parts?.getOrNull(1)?.toLongOrNull()
            if (busy != null && total != null && total > 0L) {
                GpuTimes(busy = busy, total = total)
            } else {
                null
            }
        }
    }

    private fun sampleRam(): Pair<Int, Int> {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val appPssKilobytes = memoryInfo.totalPss.coerceAtLeast(0)
        val systemMemoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemoryInfo)
        val totalKilobytes = (systemMemoryInfo.totalMem / 1024L).coerceAtLeast(1L)
        val percent = ((appPssKilobytes.toDouble() / totalKilobytes.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
        return percent to (appPssKilobytes / 1024)
    }
}
