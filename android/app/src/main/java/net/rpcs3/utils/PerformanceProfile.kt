package net.rpcs3.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import net.rpcs3.RPCS3
import org.json.JSONObject
import java.io.File

/**
 * Andros3 big.LITTLE thread placement for Snapdragon-class CPUs.
 *
 * Out of the box every CPU is "General", the scheduler mode is "Operating System",
 * and Android is free to run a PPU or SPU thread on a low-power core, which drags
 * the whole emulation down. This profile reads each core's capacity and assigns:
 *  - the fastest core(s) to PPU, so the main game thread always has a prime core
 *  - low-power cores to Background, so no emulator thread is pinned there
 *  - everything else to General, shared by PPU, SPU and RSX
 * and switches the scheduler to "RPCS3 Scheduler" so the masks are honoured.
 *
 * It is applied once per config (global and each per-game config) and only while
 * that config is still at the stock values, so a user's own choices are kept.
 */
object PerformanceProfile {
    private const val TAG = "PerformanceProfile"
    private const val PREFS = "PerformanceProfile"
    private const val VERSION = 1
    private const val MAX_CONFIG_CPUS = 8

    private const val SCHEDULER_KEY = "Core@@Thread Scheduler Mode"
    private const val SCHEDULER_OS = "Operating System"
    private const val SCHEDULER_RPCS3 = "RPCS3 Scheduler"

    private fun cpuKey(index: Int) = "Core@@Affinity@@CPU$index"

    data class Plan(val classes: List<String>) {
        val isUseful: Boolean get() = classes.any { it != "General" }
    }

    /** Builds the per-CPU plan from sysfs, or null on homogeneous / unreadable CPUs. */
    fun plan(): Plan? {
        val count = Runtime.getRuntime().availableProcessors().coerceAtMost(MAX_CONFIG_CPUS)
        val capacities = (0 until count).map { readCapacity(it) }

        if (capacities.any { it <= 0 }) {
            Log.w(TAG, "Unreadable core capacities $capacities, leaving affinity alone")
            return null
        }

        val max = capacities.max()
        val min = capacities.min()
        if (min == max) {
            return null
        }

        val primeCount = capacities.count { it == max }
        // Keep the PPU core(s) exclusive only when that still leaves enough room for the rest.
        val reservePrime = primeCount <= 2 && count - primeCount >= 4

        val classes = capacities.map { capacity ->
            when {
                capacity == max && reservePrime -> "PPU"
                capacity * 2 < max -> "Background"
                else -> "General"
            }
        }

        // Never leave fewer than three cores that SPU and RSX threads may use.
        if (classes.count { it == "General" } < 3) {
            return null
        }

        return Plan(classes)
    }

    /** Startup: the global config and every existing per-game config. */
    fun apply(context: Context) {
        val plan = plan()?.takeIf { it.isUseful } ?: return
        Log.i(TAG, "Core plan: ${plan.classes}")

        val targets = buildList {
            add("")
            File(RPCS3.rootDirectory + "config/custom_configs")
                .listFiles { f -> f.name.startsWith("config_") && f.name.endsWith(".yml") }
                ?.forEach { add(it.name.removePrefix("config_").removeSuffix(".yml")) }
        }

        targets.forEach { applyTo(context, plan, it) }
        RPCS3.instance.settingsFlush()
    }

    /** Before boot: a per-game config created since startup still gets the plan. */
    fun applyForGame(context: Context, titleId: String) {
        if (titleId.isEmpty() || !DriverSelection.customConfigExists(titleId)) {
            return
        }
        val plan = plan()?.takeIf { it.isUseful } ?: return
        applyTo(context, plan, titleId)
        RPCS3.instance.settingsFlush()
    }

    private fun applyTo(context: Context, plan: Plan, titleId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = "applied_v${VERSION}_${titleId.ifEmpty { "global" }}"
        if (prefs.getBoolean(stamp, false)) {
            return
        }

        if (isStock(titleId)) {
            write(plan, titleId)
            Log.i(TAG, "Applied core plan to ${titleId.ifEmpty { "global config" }}")
        }

        prefs.edit { putBoolean(stamp, true) }
    }

    private fun isStock(titleId: String): Boolean {
        if (read(SCHEDULER_KEY, titleId) != SCHEDULER_OS) {
            return false
        }
        return (0 until MAX_CONFIG_CPUS).all { read(cpuKey(it), titleId).let { v -> v.isEmpty() || v == "General" } }
    }

    private fun write(plan: Plan, titleId: String) {
        plan.classes.forEachIndexed { index, cls ->
            RPCS3.instance.settingsSet(cpuKey(index), "\"$cls\"", titleId)
        }
        RPCS3.instance.settingsSet(SCHEDULER_KEY, "\"$SCHEDULER_RPCS3\"", titleId)
    }

    private fun read(path: String, titleId: String): String = runCatching {
        val raw = RPCS3.instance.settingsGet(path, titleId).trim()
        if (raw.startsWith("{")) JSONObject(raw).optString("value", "") else raw.trim('"')
    }.getOrDefault("")

    private fun readCapacity(core: Int): Int {
        val base = "/sys/devices/system/cpu/cpu$core"
        val capacity = runCatching { File("$base/cpu_capacity").readText().trim().toInt() }.getOrNull()
        if (capacity != null && capacity > 0) {
            return capacity
        }
        // Fall back to the max frequency in MHz, which orders cores the same way.
        return runCatching { File("$base/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000 }
            .getOrDefault(-1)
    }
}
