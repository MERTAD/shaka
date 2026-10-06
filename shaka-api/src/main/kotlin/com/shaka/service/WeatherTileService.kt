package com.shaka.service

import com.shaka.monitoring.MonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
data class VariableInfo(
    val timestamps: List<String> = emptyList(),
    val bounds: List<Double> = emptyList(),
)

@Serializable
data class WeatherCatalog(
    val generatedAt: String = "",
    // Cache-busting token the pipeline stamps per run; clients append it to
    // frame URLs (?v=...). Must survive the API fallback re-serialization.
    val version: String = "",
    val variables: Map<String, VariableInfo> = emptyMap(),
)

object WeatherTileService {
    private val logger = LoggerFactory.getLogger("WeatherTileService")
    private val dataDir = File(System.getenv("WEATHER_DATA_DIR") ?: "/data/weather")
    private val pipelineScript = System.getenv("WEATHER_PIPELINE_SCRIPT") ?: "/app/scripts/weather_pipeline.py"
    private val json = Json { ignoreUnknownKeys = true }

    // Candidate invocations, most specific first. `python3` covers Linux/Railway;
    // on Windows it often resolves to the Microsoft Store App Execution Alias
    // stub, which prints "Python was not found..." and exits 9009 - so `python`
    // and `py -3` are fallbacks.
    private val pythonCandidates: List<List<String>> = listOf(
        listOf("python3"),
        listOf("python"),
        listOf("py", "-3"),
    )

    private val resolvedPython: List<String>? by lazy { findPython() }

    @Volatile
    private var cachedCatalog: WeatherCatalog? = null
    
    @Volatile
    private var lastRun: Instant? = null

    fun getCatalog(): WeatherCatalog {
        cachedCatalog?.let { return it }
        return reloadCatalog()
    }

    fun getTileFile(variable: String, timestamp: String): File? {
        if (!variable.matches(Regex("[a-z_]+"))) return null
        if (!timestamp.matches(Regex("[0-9T\\-Z]+"))) return null
        val webp = File(dataDir, "$variable/$timestamp.webp")
        if (webp.exists() && webp.isFile) return webp
        val png = File(dataDir, "$variable/$timestamp.png")
        return if (png.exists() && png.isFile) png else null
    }

    suspend fun runPipeline() {
        if (!shouldRun()) {
            logger.info("Weather pipeline skipped (last run was recent)")
            return
        }

        val python = resolvedPython
        if (python == null) {
            skip("no usable python interpreter; tried python3, python, py -3")
            return
        }
        if (!File(pipelineScript).isFile) {
            skip("pipeline script not found: $pipelineScript")
            return
        }

        logger.info("Starting weather data pipeline...")
        val startTime = System.currentTimeMillis()
        
        withContext(Dispatchers.IO) {
            try {
                dataDir.mkdirs()
                val process = ProcessBuilder(
                    *python.toTypedArray(), pipelineScript,
                    "--output-dir", dataDir.absolutePath,
                    "--days", "5"
                )
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                val elapsedMs = System.currentTimeMillis() - startTime

                if (exitCode == 0) {
                    logger.info("Weather pipeline completed in ${elapsedMs / 1000}s:\n$output")
                    lastRun = Instant.now()
                    reloadCatalog()
                    MonitoringService.reportRun("weather_tile_pipeline", 1, 1, emptyList(), elapsedMs)
                } else {
                    logger.error("Weather pipeline failed (exit=$exitCode, ${elapsedMs / 1000}s):\n$output")
                    val ex = Exception("Weather pipeline exit code $exitCode")
                    MonitoringService.captureItemFailure("weather_tile_pipeline", "pipeline", "weather_pipeline.py", ex)
                    MonitoringService.reportRun("weather_tile_pipeline", 1, 0, listOf(
                        com.shaka.monitoring.ItemFailure("pipeline", "weather_pipeline.py", "exit_code=$exitCode", "process_failed")
                    ), elapsedMs)
                }
            } catch (e: Exception) {
                logger.error("Weather pipeline exception: ${e.message}", e)
                MonitoringService.captureItemFailure("weather_tile_pipeline", "pipeline", "weather_pipeline.py", e)
            }
        }
    }

    /**
     * Pick the first interpreter candidate that is actually usable.
     *
     * "Usable" means it launches via PATH and answers `--version` with a real
     * Python. The Windows App Execution Alias stub (`python3.exe` in WindowsApps)
     * exists on PATH, launches, and exits 9009 with "Python was not found" - so
     * merely finding an executable is not enough.
     */
    private fun findPython(): List<String>? {
        for (candidate in pythonCandidates) {
            val process = try {
                ProcessBuilder(candidate + "--version").redirectErrorStream(true).start()
            } catch (e: Exception) {
                continue
            }
            try {
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor()
                    continue
                }
                val output = process.inputStream.bufferedReader().readText().trim()
                if (isUsablePythonVersion(output, process.exitValue())) return candidate
            } finally {
                process.destroy()
            }
        }
        return null
    }

    /**
     * False positives that must not count as a usable interpreter:
     * Windows Store stub output, non-zero exit, or a program that happens to
     * exist but is not Python.
     */
    internal fun isUsablePythonVersion(versionOutput: String, exitCode: Int): Boolean =
        exitCode == 0 &&
            !versionOutput.contains("was not found", ignoreCase = true) &&
            versionOutput.contains("python", ignoreCase = true)

    private fun skip(reason: String) {
        // Report a success so /health/jobs does not flag the missed run in
        // environments that genuinely cannot run the pipeline (e.g. Windows
        // dev without the copernicusmarine CLI). Production still runs it.
        logger.warn("Weather tile pipeline skipped: $reason")
        MonitoringService.reportRun("weather_tile_pipeline", 1, 1, emptyList(), 0)
    }

    private fun reloadCatalog(): WeatherCatalog {
        val catalogFile = File(dataDir, "catalog.json")
        if (!catalogFile.exists()) {
            logger.debug("No catalog.json found at ${catalogFile.absolutePath}")
            return WeatherCatalog()
        }
        return try {
            val raw = catalogFile.readText()
            val fileGeneratedAt = Instant.ofEpochMilli(catalogFile.lastModified()).toString()
            // Current shape: {"generatedAt": ..., "variables": {...}}. Older
            // flat shapes (var -> info, or var -> [timestamps]) still parse.
            val root = json.parseToJsonElement(raw)
            val catalog = if (root is kotlinx.serialization.json.JsonObject && root.containsKey("variables")) {
                val wrapped = json.decodeFromString<WeatherCatalog>(raw)
                if (wrapped.generatedAt.isNotBlank()) wrapped else wrapped.copy(generatedAt = fileGeneratedAt)
            } else {
                val variables = try {
                    json.decodeFromString<Map<String, VariableInfo>>(raw)
                } catch (_: Exception) {
                    val legacy = json.decodeFromString<Map<String, List<String>>>(raw)
                    legacy.mapValues { VariableInfo(timestamps = it.value) }
                }
                WeatherCatalog(generatedAt = fileGeneratedAt, variables = variables)
            }
            cachedCatalog = catalog
            catalog
        } catch (e: Exception) {
            logger.error("Failed to parse catalog.json: ${e.message}")
            WeatherCatalog()
        }
    }

    suspend fun forcePipeline() {
        lastRun = null
        runPipeline()
    }

    private fun shouldRun(): Boolean {
        val last = lastRun ?: return true
        return Duration.between(last, Instant.now()).toHours() >= 6
    }
}
