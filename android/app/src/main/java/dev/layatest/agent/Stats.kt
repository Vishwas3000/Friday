package dev.layatest.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import java.io.File

/**
 * What Friday and the whole phone are using right now (owner, 2026-10-19): CPU, memory, core clocks, GPU where the
 * phone exposes a counter an app may read, heat and battery. One line each, sampled once a second by MainActivity.
 * shortcut: overall CPU and GPU come from /proc and /sys files that Android may hide from apps; then they read "n/a".
 */
object Stats {
    private var lastCpu = 0L
    private var lastAt = 0L
    private var lastAll: LongArray? = null
    private const val HZ = 100.0  // clock ticks per second in /proc stat files

    fun sample(ctx: Context): String {
        val now = SystemClock.elapsedRealtime()
        val self = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        val cpu = self[11].toLong() + self[12].toLong()  // utime + stime
        val mine = if (lastAt > 0) 100.0 * (cpu - lastCpu) / HZ / ((now - lastAt) / 1000.0) else 0.0
        lastCpu = cpu; lastAt = now
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { "%.1f °C".format(it / 10.0) } ?: "n/a"
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }?.let { " $it%" } ?: ""
        val laya = if (AgentService.instance?.layaWarm == true) "loaded" else "not loaded"
        return "Friday: CPU ${"%.0f".format(mine)}% of a core · ${Debug.getPss() / 1024} MB · native ${Debug.getNativeHeapAllocatedSize() shr 20} MB · Laya $laya\n" +
            "Phone: CPU ${allCpu()} · memory ${(mi.totalMem - mi.availMem) shr 20} of ${mi.totalMem shr 20} MB · clocks ${clocks()}\n" +
            "GPU ${gpu()} · ${heat(ctx)} · battery $temp$level"
    }

    /** Busy share of all cores since the last sample, from /proc/stat's first line. */
    private fun allCpu(): String = try {
        val f = File("/proc/stat").readLines().first().split(Regex("\\s+")).drop(1).take(8).map { it.toLong() }
        val cur = longArrayOf(f.sum(), f[3] + f[4])  // total, idle + iowait
        val prev = lastAll
        lastAll = cur
        if (prev == null || cur[0] == prev[0]) "…" else "${100 * ((cur[0] - prev[0]) - (cur[1] - prev[1])) / (cur[0] - prev[0])}%"
    } catch (e: Exception) { "n/a" }

    /** Current clock of the first core in each cluster (little / mid / big on the Pixel 8). */
    private fun clocks(): String = listOf(0, 4, 8).mapNotNull { c ->
        runCatching { File("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000 }.getOrNull()
    }.let { if (it.isEmpty()) "n/a" else it.joinToString("/") + " MHz" }

    /** A GPU busy counter, if this phone exposes one an app may read (Adreno kgsl, Mali). */
    private fun gpu(): String {
        File("/sys/class/kgsl/kgsl-3d0/gpubusy").takeIf { it.canRead() }?.let { f ->
            val (busy, total) = f.readText().trim().split(Regex("\\s+")).map { it.toLong() }
            return if (total > 0) "${100 * busy / total}%" else "idle"
        }
        for (p in listOf("/sys/class/misc/mali0/device/utilization", "/sys/class/misc/mali0/device/gpu_busy_percentage",
                         "/sys/kernel/gpu/gpu_busy")) {
            File(p).takeIf { it.canRead() }?.let { return it.readText().trim().take(12) + "%" }
        }
        return "n/a"
    }
}
