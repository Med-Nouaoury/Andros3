package net.rpcs3.benchmark

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import net.rpcs3.EmulatorState
import net.rpcs3.R
import net.rpcs3.RPCS3
import net.rpcs3.ui.hud.DeviceStatsReader
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val TAG = "Benchmark"
private const val SAMPLE_MS = 1000L
private const val FRAME_SAMPLE_MS = 50L

object BenchmarkPrefs {
    private const val NAME = "benchmark"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Folder under the app's external files dir; reachable through AppDataDocumentProvider. */
    const val FOLDER = "benchmarks"

    fun directory(): File = File(RPCS3.rootDirectory, FOLDER)

    fun latest(): File? = directory()
        .listFiles { file -> file.isFile && file.name.endsWith(".csv") }
        ?.maxByOrNull { it.lastModified() }
}

/**
 * Records one play session to a CSV file so runs can be compared across builds,
 * settings and emulators on the same device.
 *
 * Every second it stores the emulator FPS and frame time plus device state
 * (CPU/GPU load, per-core clocks, temperature, thermal throttling status, power).
 * Frame times are also sampled every 50 ms to estimate 1% lows. Only seconds where
 * the emulator is running are summarised; paused and loading time is kept in the
 * CSV but marked by its state column.
 */
class BenchmarkRecorder(
    context: Context,
    private val titleIdProvider: () -> String
) {
    private data class Sample(
        val seconds: Float,
        val state: String,
        val fps: Float,
        val frametimeMs: Float,
        val cpuPercent: Int,
        val gpuPercent: Int,
        val ramUsedMb: Int,
        val temperatureC: Int,
        val thermalStatus: Int,
        val thermalHeadroom: Float,
        val watts: Float,
        val batteryPercent: Int,
        val coreMhz: List<Int>,
        val threads: ThreadLoad
    )

    /** Per-thread-group CPU use over the last second, in % of one core. */
    private data class ThreadLoad(
        val ppuMain: Float = Float.NaN,
        val ppuMainCpu: Int = -1,
        val ppuAll: Float = Float.NaN,
        val spuMax: Float = Float.NaN,
        val spuAll: Float = Float.NaN,
        val rsx: Float = Float.NaN,
        val compile: Float = Float.NaN
    )

    private val clockTicks = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
    }.getOrDefault(100L).coerceAtLeast(1L)
    private val lastTicks = HashMap<Int, Long>()
    private var lastTicksAt = 0L

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val stats = DeviceStatsReader(appContext)
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val coreCount = Runtime.getRuntime().availableProcessors()

    private val samples = ArrayList<Sample>()
    private val frameTimes = ArrayList<Float>()
    private var startedAt = 0L
    private var running = false
    private var emulatorRunning = false
    private var titleId = ""

    private val samplePump = object : Runnable {
        override fun run() {
            if (!running) return
            takeSample()
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    private val framePump = object : Runnable {
        override fun run() {
            if (!running) return
            if (emulatorRunning) {
                val ms = runCatching { RPCS3.instance.frameTimeMs() }.getOrDefault(0f)
                if (ms > 0f && ms < 10_000f) frameTimes.add(ms)
            }
            handler.postDelayed(this, FRAME_SAMPLE_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        handler.post(samplePump)
        handler.post(framePump)
        Log.i(TAG, "Benchmark recording started")
    }

    /** Stops recording and writes the CSV in the background. */
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(samplePump)
        handler.removeCallbacks(framePump)

        val snapshot = ArrayList(samples)
        val frames = ArrayList(frameTimes)
        val title = titleId.ifEmpty { "UNKNOWN" }

        thread(name = "benchmark-writer") {
            val file = runCatching { write(snapshot, frames, title) }
                .onFailure { Log.e(TAG, "Failed to write benchmark", it) }
                .getOrNull()

            handler.post {
                val message = if (file != null) {
                    appContext.getString(R.string.benchmark_saved, file.name)
                } else {
                    appContext.getString(R.string.benchmark_not_saved)
                }
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun takeSample() {
        val state = RPCS3.getState()
        emulatorRunning = state == EmulatorState.Running

        if (titleId.isEmpty()) {
            titleId = runCatching { titleIdProvider() }.getOrDefault("")
        }

        val device = stats.read()
        val emu = runCatching { JSONObject(RPCS3.instance.perfMetrics()) }.getOrNull()

        samples.add(
            Sample(
                seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f,
                state = state.name,
                fps = emu?.optDouble("fps", 0.0)?.toFloat() ?: 0f,
                frametimeMs = emu?.optDouble("frametime", 0.0)?.toFloat() ?: 0f,
                cpuPercent = device.cpuPercent,
                gpuPercent = if (device.gpuPercent >= 0) device.gpuPercent else emu?.optInt("rsxLoad", -1) ?: -1,
                ramUsedMb = device.ramUsedMb,
                temperatureC = device.temperatureC,
                thermalStatus = powerManager?.currentThermalStatus ?: -1,
                thermalHeadroom = runCatching { powerManager?.getThermalHeadroom(0) ?: Float.NaN }
                    .getOrDefault(Float.NaN),
                watts = device.watts,
                batteryPercent = device.batteryPercent,
                coreMhz = (0 until coreCount).map { readCoreMhz(it) },
                threads = readThreadLoad()
            )
        )
    }

    // Thread names (Linux keeps 15 chars): "PPU[0x1000000] ", "SPU[0x1000100] ",
    // "rsx::thread", and compile workers "PPU Worker", "SPU Worker N", "RSX.WN".
    private enum class Group { Ppu, Spu, Rsx, Compile, Other }

    private fun groupOf(name: String): Group = when {
        name.startsWith("PPU[") -> Group.Ppu
        name.startsWith("SPU[") -> Group.Spu
        name.startsWith("rsx::thread") || name.startsWith("RSX [") -> Group.Rsx
        name.startsWith("SPU Worker") || name.startsWith("PPU Worker") ||
            name.startsWith("RSX.W") || name.contains("LLVM") -> Group.Compile
        else -> Group.Other
    }

    private fun readThreadLoad(): ThreadLoad {
        val now = SystemClock.elapsedRealtime()
        val elapsedSec = (now - lastTicksAt) / 1000.0
        val tasks = File("/proc/self/task").listFiles() ?: return ThreadLoad()

        val seen = HashMap<Int, Long>(tasks.size)
        var ppuMain = 0.0
        var ppuMainCpu = -1
        var ppuAll = 0.0
        var spuMax = 0.0
        var spuAll = 0.0
        var rsx = 0.0
        var compile = 0.0

        for (task in tasks) {
            val tid = task.name.toIntOrNull() ?: continue
            val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: continue
            val close = stat.lastIndexOf(')')
            val open = stat.indexOf('(')
            if (close < 0 || open < 0) continue

            val name = stat.substring(open + 1, close)
            val fields = stat.substring(close + 2).split(' ')
            if (fields.size < 37) continue

            val ticks = (fields[11].toLongOrNull() ?: 0L) + (fields[12].toLongOrNull() ?: 0L)
            seen[tid] = ticks

            val previous = lastTicks[tid] ?: continue
            if (lastTicksAt == 0L || elapsedSec <= 0.0) continue

            val pct = (ticks - previous) * 100.0 / clockTicks / elapsedSec
            when (groupOf(name)) {
                Group.Ppu -> {
                    ppuAll += pct
                    if (pct > ppuMain) {
                        ppuMain = pct
                        ppuMainCpu = fields[36].toIntOrNull() ?: -1
                    }
                }
                Group.Spu -> {
                    spuAll += pct
                    if (pct > spuMax) spuMax = pct
                }
                Group.Rsx -> rsx += pct
                Group.Compile -> compile += pct
                Group.Other -> Unit
            }
        }

        val valid = lastTicksAt != 0L
        lastTicks.clear()
        lastTicks.putAll(seen)
        lastTicksAt = now

        if (!valid) return ThreadLoad()
        return ThreadLoad(
            ppuMain.toFloat(), ppuMainCpu, ppuAll.toFloat(),
            spuMax.toFloat(), spuAll.toFloat(), rsx.toFloat(), compile.toFloat()
        )
    }

    private fun readCoreMhz(core: Int): Int = runCatching {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            .readText().trim().toInt() / 1000
    }.getOrDefault(-1)

    private fun write(samples: List<Sample>, frames: List<Float>, title: String): File {
        val dir = BenchmarkPrefs.directory()
        dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "${safeTitle}_$stamp.csv")

        val active = samples.filter { it.state == EmulatorState.Running.name && it.fps > 0f }
        val fpsSorted = active.map { it.fps }.sorted()
        val framesSorted = frames.sorted()

        fun fmt(value: Float) = if (value.isNaN()) "" else String.format(Locale.US, "%.2f", value)
        fun percentile(sorted: List<Float>, p: Float): Float =
            if (sorted.isEmpty()) Float.NaN
            else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

        val avgFps = if (active.isEmpty()) Float.NaN else active.map { it.fps }.average().toFloat()
        val p99Frame = percentile(framesSorted, 0.99f)
        val onePercentLow = if (p99Frame.isNaN() || p99Frame <= 0f) Float.NaN else 1000f / p99Frame
        val maxTemp = samples.maxOfOrNull { it.temperatureC } ?: -1
        val worstThermal = samples.maxOfOrNull { it.thermalStatus } ?: -1
        val avgWatts = samples.map { it.watts }.filter { !it.isNaN() }.let {
            if (it.isEmpty()) Float.NaN else it.average().toFloat()
        }

        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty()

        file.bufferedWriter().use { out ->
            out.appendLine("# Andros3 benchmark")
            out.appendLine("# app: ${appContext.packageName} $version")
            out.appendLine("# device: ${Build.MANUFACTURER} ${Build.MODEL} | soc: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} | android ${Build.VERSION.RELEASE}")
            out.appendLine("# title: $title")
            out.appendLine("# scheduler | cpu affinity: ${runCatching { net.rpcs3.utils.PerformanceProfile.describe(title) }.getOrDefault("?")}")
            out.appendLine("# running seconds with fps>0: ${active.size} of ${samples.size}")
            out.appendLine("# avg fps: ${fmt(avgFps)}")
            out.appendLine("# median fps: ${fmt(percentile(fpsSorted, 0.5f))}")
            out.appendLine("# min fps (1 s): ${fmt(fpsSorted.firstOrNull() ?: Float.NaN)}")
            out.appendLine("# 1% low fps (frame times sampled every ${FRAME_SAMPLE_MS} ms): ${fmt(onePercentLow)}")
            out.appendLine("# max temperature C: $maxTemp")
            out.appendLine("# worst thermal status (0 none .. 6 shutdown): $worstThermal")
            out.appendLine("# avg power W: ${fmt(avgWatts)}")

            fun avgOf(list: List<Sample>, pick: (ThreadLoad) -> Float): Float =
                list.map { pick(it.threads) }.filter { !it.isNaN() }
                    .let { if (it.isEmpty()) Float.NaN else it.average().toFloat() }

            val runningSamples = samples.filter { it.state == EmulatorState.Running.name }
            val slow = runningSamples.filter { it.fps in 0.5f..24f }
            val fast = runningSamples.filter { it.fps >= 27f }
            fun loadLine(label: String, list: List<Sample>) {
                out.appendLine(
                    "# $label (${list.size} s) thread load %: ppu_main ${fmt(avgOf(list) { it.ppuMain })}" +
                        " | spu_max ${fmt(avgOf(list) { it.spuMax })} | spu_all ${fmt(avgOf(list) { it.spuAll })}" +
                        " | rsx ${fmt(avgOf(list) { it.rsx })} | compile ${fmt(avgOf(list) { it.compile })}"
                )
            }
            loadLine("full speed, fps>=27", fast)
            loadLine("slow, fps<24", slow)

            val verdict = when {
                slow.isEmpty() -> "no slow seconds"
                avgOf(slow) { it.compile } > 120f ->
                    "slow seconds line up with code/shader compilation; a second run through the same area should be smoother"
                avgOf(slow) { it.ppuMain } >= 85f -> "PPU bound (main game thread saturates one core)"
                avgOf(slow) { it.spuMax } >= 85f -> "SPU bound (an SPU thread saturates one core)"
                avgOf(slow) { it.rsx } >= 85f -> "RSX bound (graphics command thread saturates one core)"
                else -> "no single thread saturated in slow seconds; likely waiting on GPU, sync or clocks"
            }
            out.appendLine("# bottleneck: $verdict")

            val coreHeader = (0 until coreCount).joinToString(",") { "cpu${it}_mhz" }
            out.appendLine(
                "t_s,state,fps,frametime_ms,cpu_pct,gpu_pct,ram_used_mb,temp_c," +
                    "thermal_status,thermal_headroom,watts,battery_pct,$coreHeader," +
                    "ppu_main_pct,ppu_main_cpu,ppu_all_pct,spu_max_pct,spu_all_pct,rsx_pct,compile_pct"
            )
            for (s in samples) {
                out.appendLine(
                    listOf(
                        fmt(s.seconds), s.state, fmt(s.fps), fmt(s.frametimeMs),
                        s.cpuPercent, s.gpuPercent, s.ramUsedMb, s.temperatureC,
                        s.thermalStatus, fmt(s.thermalHeadroom), fmt(s.watts),
                        s.batteryPercent, s.coreMhz.joinToString(","),
                        fmt(s.threads.ppuMain), s.threads.ppuMainCpu, fmt(s.threads.ppuAll),
                        fmt(s.threads.spuMax), fmt(s.threads.spuAll), fmt(s.threads.rsx),
                        fmt(s.threads.compile)
                    ).joinToString(",")
                )
            }
        }

        Log.i(TAG, "Benchmark written to ${file.path}")
        return file
    }
}
