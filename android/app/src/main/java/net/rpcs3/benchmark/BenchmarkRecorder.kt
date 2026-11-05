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
        val coreMhz: List<Int>
    )

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
                coreMhz = (0 until coreCount).map { readCoreMhz(it) }
            )
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
            out.appendLine("# running seconds with fps>0: ${active.size} of ${samples.size}")
            out.appendLine("# avg fps: ${fmt(avgFps)}")
            out.appendLine("# median fps: ${fmt(percentile(fpsSorted, 0.5f))}")
            out.appendLine("# min fps (1 s): ${fmt(fpsSorted.firstOrNull() ?: Float.NaN)}")
            out.appendLine("# 1% low fps (frame times sampled every ${FRAME_SAMPLE_MS} ms): ${fmt(onePercentLow)}")
            out.appendLine("# max temperature C: $maxTemp")
            out.appendLine("# worst thermal status (0 none .. 6 shutdown): $worstThermal")
            out.appendLine("# avg power W: ${fmt(avgWatts)}")

            val coreHeader = (0 until coreCount).joinToString(",") { "cpu${it}_mhz" }
            out.appendLine(
                "t_s,state,fps,frametime_ms,cpu_pct,gpu_pct,ram_used_mb,temp_c," +
                    "thermal_status,thermal_headroom,watts,battery_pct,$coreHeader"
            )
            for (s in samples) {
                out.appendLine(
                    listOf(
                        fmt(s.seconds), s.state, fmt(s.fps), fmt(s.frametimeMs),
                        s.cpuPercent, s.gpuPercent, s.ramUsedMb, s.temperatureC,
                        s.thermalStatus, fmt(s.thermalHeadroom), fmt(s.watts),
                        s.batteryPercent, s.coreMhz.joinToString(",")
                    ).joinToString(",")
                )
            }
        }

        Log.i(TAG, "Benchmark written to ${file.path}")
        return file
    }
}
