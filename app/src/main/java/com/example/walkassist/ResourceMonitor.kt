package com.example.walkassist

import android.app.ActivityManager
import android.os.Build
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import kotlin.math.roundToInt

data class ResourceUsageSnapshot(
    val cpuCorePercent: Float = 0f,
    val cpuDevicePercent: Float = 0f,
    val systemCpuPercent: Float? = null,
    val cpuCoreCount: Int = 1,
    val gpuPercent: Int? = null,
    val gpuSourceLabel: String = "none",
    val appRamPercent: Float = 0f,
    val appRamMegabytes: Int = 0,
    val systemRamPercent: Int = 0,
    val thermalStatusLabel: String = "n/a",
)

class ResourceMonitor(
    context: Context,
) {
    private data class CpuTimes(
        val totalTicks: Long,
        val idleTicks: Long,
    )

    private data class GpuTimes(
        val busy: Long,
        val total: Long,
        val sourceLabel: String,
    )

    private data class GpuSample(
        val percent: Int,
        val sourceLabel: String,
    )

    private data class AppCpuSample(
        val cpuMillis: Long,
        val wallMillis: Long,
    )

    private data class CpuSample(
        val appCorePercent: Float,
        val appDevicePercent: Float,
        val systemPercent: Float?,
    )

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var previousAppCpuSample: AppCpuSample? = null
    private var previousSystemCpuTimes: CpuTimes? = null
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
        val cpu = sampleCpu()
        val gpu = sampleGpu()
        val ram = sampleRam()
        return ResourceUsageSnapshot(
            cpuCorePercent = cpu.appCorePercent,
            cpuDevicePercent = cpu.appDevicePercent,
            systemCpuPercent = cpu.systemPercent,
            cpuCoreCount = cpuCoreCount,
            gpuPercent = gpu?.percent,
            gpuSourceLabel = gpu?.sourceLabel ?: "none",
            appRamPercent = ram.appPercent,
            appRamMegabytes = ram.appMegabytes,
            systemRamPercent = ram.systemPercent,
            thermalStatusLabel = sampleThermalStatus(),
        )
    }

    private fun sampleCpu(): CpuSample {
        val appCorePercent = sampleAppCpuCorePercent()
        val appDevicePercent = (appCorePercent / cpuCoreCount.toFloat()).coerceIn(0f, 100f)
        val rawSystemPercent = sampleSystemCpuPercent()
        val systemPercent = rawSystemPercent
            ?.takeUnless { appCorePercent > 5f && it < 0.1f }
        return CpuSample(
            appCorePercent = appCorePercent,
            appDevicePercent = appDevicePercent,
            systemPercent = systemPercent,
        )
    }

    private fun sampleAppCpuCorePercent(): Float {
        val current = AppCpuSample(
            cpuMillis = Process.getElapsedCpuTime(),
            wallMillis = SystemClock.elapsedRealtime(),
        )
        val previous = previousAppCpuSample.also { previousAppCpuSample = current } ?: return 0f
        val cpuDelta = (current.cpuMillis - previous.cpuMillis).coerceAtLeast(0L)
        val wallDelta = (current.wallMillis - previous.wallMillis).coerceAtLeast(1L)
        return ((cpuDelta.toDouble() / wallDelta.toDouble()) * 100.0)
            .toFloat()
            .coerceIn(0f, 999f)
    }

    private fun sampleSystemCpuPercent(): Float? {
        val current = readSystemCpuTimes() ?: return null
        val previous = previousSystemCpuTimes.also { previousSystemCpuTimes = current } ?: return null
        val totalDelta = current.totalTicks - previous.totalTicks
        if (totalDelta <= 0L) return null
        val idleDelta = (current.idleTicks - previous.idleTicks).coerceAtLeast(0L)
        return (((totalDelta - idleDelta).toDouble() / totalDelta.toDouble()) * 100.0)
            .toFloat()
            .coerceIn(0f, 100f)
    }

    private fun readSystemCpuTimes(): CpuTimes? {
        val ticks = runCatching { File("/proc/stat").useLines { lines -> lines.first() } }
            .getOrNull()
            ?.split(Regex("\\s+"))
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            ?: return null
        if (ticks.size < 5) return null
        val idleTicks = ticks[3] + ticks.getOrElse(4) { 0L }
        return CpuTimes(
            totalTicks = ticks.sum(),
            idleTicks = idleTicks,
        )
    }

    private fun sampleGpu(): GpuSample? {
        readGpuPercentFile()?.let { return it }

        readGpuBusyTimes()?.let { current ->
            val previous = previousGpuTimes.also { previousGpuTimes = current } ?: return null
            val busyDelta = (current.busy - previous.busy).coerceAtLeast(0L)
            val totalDelta = (current.total - previous.total).coerceAtLeast(1L)
            return GpuSample(
                percent = ((busyDelta.toDouble() / totalDelta.toDouble()) * 100.0)
                    .roundToInt()
                    .coerceIn(0, 100),
                sourceLabel = current.sourceLabel,
            )
        }

        return null
    }

    private fun readGpuPercentFile(): GpuSample? {
        return gpuPercentFiles.firstNotNullOfOrNull { path ->
            val value = runCatching { File(path).readText().trim() }.getOrNull()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toFloatOrNull()
            value?.let {
                val normalized = if (it > 100f) it / 10f else it
                GpuSample(
                    percent = normalized.roundToInt().coerceIn(0, 100),
                    sourceLabel = gpuSourceLabel(path),
                )
            }
        }
    }

    private fun readGpuBusyTimes(): GpuTimes? {
        return gpuBusyFiles.firstNotNullOfOrNull { path ->
            val parts = runCatching { File(path).readText().trim().split(Regex("\\s+")) }.getOrNull()
            val busy = parts?.getOrNull(0)?.toLongOrNull()
            val total = parts?.getOrNull(1)?.toLongOrNull()
            if (busy != null && total != null && total > 0L) {
                GpuTimes(
                    busy = busy,
                    total = total,
                    sourceLabel = gpuSourceLabel(path),
                )
            } else {
                null
            }
        }
    }

    private fun gpuSourceLabel(path: String): String {
        return when {
            "kgsl" in path && "percentage" in path -> "kgsl%"
            "kgsl" in path -> "kgsl"
            "mali" in path -> "mali"
            "gpu_busy" in path -> "busy"
            "load" in path -> "load"
            else -> File(path).name.take(8)
        }
    }

    private data class RamSample(
        val appPercent: Float,
        val appMegabytes: Int,
        val systemPercent: Int,
    )

    private fun sampleRam(): RamSample {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val appPssKilobytes = memoryInfo.totalPss.coerceAtLeast(0)
        val systemMemoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemoryInfo)
        val totalKilobytes = (systemMemoryInfo.totalMem / 1024L).coerceAtLeast(1L)
        val appPercent = ((appPssKilobytes.toDouble() / totalKilobytes.toDouble()) * 100.0)
            .toFloat()
            .coerceIn(0f, 100f)
        val systemPercent = (
            ((systemMemoryInfo.totalMem - systemMemoryInfo.availMem).toDouble() /
                systemMemoryInfo.totalMem.toDouble()) * 100.0
            )
            .roundToInt()
            .coerceIn(0, 100)
        return RamSample(
            appPercent = appPercent,
            appMegabytes = appPssKilobytes / 1024,
            systemPercent = systemPercent,
        )
    }

    private fun sampleThermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "mod"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "crit"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emerg"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "stop"
            else -> "n/a"
        }
    }
}
