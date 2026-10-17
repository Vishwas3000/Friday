package dev.layatest.agent

import java.io.File

/** Friday's native helpers (src/main/cpp). */
object Native {
    init { System.loadLibrary("friday") }

    /** Pin every thread of this process to the CPUs in [mask]; returns how many threads were pinned (-1: failed). */
    external fun pinAllThreads(mask: Int): Int

    /**
     * The fast cores, from the kernel's per-core capacity: those with at least half the biggest one's
     * (Pixel 8: the four A715s and the X3; the kernel drops the X3 while the app isn't on top).
     */
    val fastCores: Int by lazy {
        val caps = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
            .associate { it.name.removePrefix("cpu").toInt() to runCatching { File(it, "cpu_capacity").readText().trim().toInt() }.getOrDefault(0) }
        val max = caps.values.maxOrNull() ?: 0
        caps.filter { max > 0 && it.value * 2 >= max }.keys.fold(0) { m, i -> m or (1 shl i) }
    }
}
