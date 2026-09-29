package com.shaka.data.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Grid fetch for the PFZ front-finder from the Copernicus Marine Data Store.
 *
 * The modern Copernicus Marine Data Store has NO REST API — the only
 * programmatic path is the Python Toolbox CLI (`copernicusmarine subset`),
 * which authenticates with the user's own account and writes a CSV of the
 * requested box. This client shells out to that CLI, parses the CSV into a
 * regular lat/lon grid, and returns it (or null, honestly, on any failure).
 *
 * Credentials are read from the environment and forwarded to the child only
 * through its process environment — never in argv, never in logs. Every
 * returned grid remembers which dataset it came from (`sourceLabel`) so a
 * caller can tag the value's provenance.
 *
 * The CLI path defaults to this development machine's installation and can be
 * overridden with `COPERNICUSMARINE_CLI`. Credentials come from
 * `COPERNICUSMARINE_SERVICE_USERNAME` / `_PASSWORD`, falling back to
 * `COP_USER` / `COP_PASS`.
 *
 * Enterprise patterns shared with [CopernicusWMTSClient]: rate limited through
 * [RateLimiters.copernicus] and circuit-breaker protected, so a dead or slow
 * service makes PFZ report missing factors instead of hanging or retrying in
 * a loop.
 */
class CopernicusGridClient(
    private val runner: CopernicusSubsetRunner = CopernicusSubsetProcess(),
    private val username: String? = System.getenv("COPERNICUSMARINE_SERVICE_USERNAME")
        ?: System.getenv("COP_USER"),
    private val password: String? = System.getenv("COPERNICUSMARINE_SERVICE_PASSWORD")
        ?: System.getenv("COP_PASS"),
    private val cliPath: String = System.getenv("COPERNICUSMARINE_CLI") ?: DEFAULT_CLI_PATH
) {
    private val logger = LoggerFactory.getLogger(CopernicusGridClient::class.java)

    private val circuitBreaker = CircuitBreaker(
        name = "copernicus-subset",
        failureThreshold = 5,
        successThreshold = 2,
        resetTimeoutMs = 120_000
    )

    companion object {
        /**
         * Installed toolbox on the development machine. Point `COPERNICUSMARINE_CLI`
         * elsewhere on any other host — the exe is not on PATH by default.
         */
        const val DEFAULT_CLI_PATH =
            "C:/Users/cc/AppData/Roaming/Python/Python313/Scripts/copernicusmarine.exe"
        const val DEFAULT_BOX_DEG = 0.3
    }

    private val credsReady: Boolean get() = username != null && password != null

    /**
     * Fetch one field as a lat/lon grid centred on [lat], [lon] within [boxDeg].
     *
     * Returns null (never a guessed grid) when credentials are missing, the
     * service is down, the CLI fails, or the response is unparseable. The
     * caller reports this as a named missing factor.
     */
    suspend fun fetchGrid(
        field: CopernicusField,
        lat: Double,
        lon: Double,
        boxDeg: Double = DEFAULT_BOX_DEG,
        date: LocalDate
    ): CopernicusGrid? {
        if (!credsReady) {
            logger.warn(
                "Copernicus subset skipped: COPERNICUSMARINE_SERVICE_USERNAME/PASSWORD " +
                    "not set in the environment"
            )
            return null
        }

        RateLimiters.copernicus.acquire()
        return try {
            circuitBreaker.execute {
                withContext(Dispatchers.IO) { runSubset(field, lat, lon, boxDeg, safeDate(date)) }
            }
        } catch (e: CircuitBreakerOpenException) {
            logger.debug("Circuit breaker open for Copernicus subset - reporting grid unavailable")
            null
        } catch (e: Exception) {
            logger.warn("Copernicus subset for ${field.datasetId} failed: ${e.message}")
            null
        }
    }

    /** True when a later fetch could join the log without re-running the CLI. */
    fun allowsRequests(): Boolean = circuitBreaker.allowsRequests()

    /**
     * NRT products always lag at least one day, so clamp any requested date
     * (e.g. "today" from a client) back to the latest the store can serve.
     */
    private fun safeDate(date: LocalDate): LocalDate {
        val latest = LocalDate.now().minusDays(1)
        if (date.isAfter(latest)) {
            logger.debug("Clamping Copernicus subset date $date to latest NRT release $latest")
        }
        return minOf(date, latest)
    }

    // ------------------------------------------------------------ subset run

    private fun runSubset(
        field: CopernicusField,
        lat: Double,
        lon: Double,
        boxDeg: Double,
        date: LocalDate
    ): CopernicusGrid? {
        val minLat = (lat - boxDeg).coerceAtLeast(field.minLat)
        val maxLat = (lat + boxDeg).coerceAtMost(field.maxLat)
        val minLon = (lon - boxDeg).coerceAtLeast(field.minLon)
        val maxLon = (lon + boxDeg).coerceAtMost(field.maxLon)
        if (minLat >= maxLat || minLon >= maxLon) {
            logger.warn("Box for ($lat, $lon) falls outside ${field.label} coverage")
            return null
        }

        val tmp = Files.createTempDirectory("copernicus-grid")
        try {
            val args = listOf(
                cliPath, "subset",
                "--dataset-id", field.datasetId,
                "--variable", field.variable,
                "--minimum-longitude", fmt(minLon),
                "--maximum-longitude", fmt(maxLon),
                "--minimum-latitude", fmt(minLat),
                "--maximum-latitude", fmt(maxLat),
                "--start-datetime", date.toString(),
                "--end-datetime", date.toString(),
                "--file-format", "csv",
                "--output-directory", tmp.toString(),
                "--overwrite"
            )
            val env = buildStringMap()
            val result = runner.run(args, env, tmp)
            if (result.timedOut) {
                logger.warn("Copernicus subset for ${field.datasetId} timed out")
                return null
            }
            if (result.exitCode != 0) {
                logger.warn(
                    "Copernicus subset for ${field.datasetId} exited ${result.exitCode}: " +
                        result.toString().take(500)
                )
                return null
            }

            val csv = firstCsv(tmp) ?: run {
                logger.warn("Copernicus subset for ${field.datasetId} produced no CSV")
                return null
            }
            return parseCsv(csv, field)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun buildStringMap(): Map<String, String> = buildMap {
        username?.let { put("COPERNICUSMARINE_SERVICE_USERNAME", it) }
        password?.let { put("COPERNICUSMARINE_SERVICE_PASSWORD", it) }
    }

    private fun firstCsv(dir: Path): Path? =
        Files.newDirectoryStream(dir, "*.csv").use { stream -> stream.firstOrNull() }

    // ---------------------------------------------------------------- parsing

    /**
     * Parse a `copernicusmarine subset --file-format csv` output file.
     *
     * Known shape (verified live): header `time,latitude,longitude,<variable>`,
     * one row per grid point, values in the variable's native unit. Rows are
     * NOT guaranteed ordered, so the grid is indexed by exact lat/lon values:
     * sorted unique latitudes/longitudes with a value matrix in between.
     * Unparseable or explicitly NaN cells stay NaN (missing), never 0.
     *
     * Internal so tests can drive the parser directly, like NOAAClient's parser.
     */
    internal fun parseCsv(path: Path, field: CopernicusField): CopernicusGrid? {
        val lines = try {
            Files.readAllLines(path)
        } catch (e: Exception) {
            logger.warn("Cannot read subset CSV ${path.fileName}: ${e.message}")
            return null
        }
        if (lines.size < 2) return null

        val header = splitCsvLine(lines.first())
        val latIdx = header.indexOf("latitude")
        val lonIdx = header.indexOf("longitude")
        val valIdx = header.indexOf(field.variable)
        val timeIdx = header.indexOf("time")
        if (latIdx < 0 || lonIdx < 0 || valIdx < 0) {
            logger.warn("Subset CSV for ${field.datasetId} lacks expected columns: $header")
            return null
        }

        val rows = mutableListOf<Triple<Double, Double, Double>>()
        var dataDate: LocalDate? = null
        for (line in lines.drop(1)) {
            val cells = splitCsvLine(line)
            if (cells.size <= maxOf(latIdx, lonIdx, valIdx, timeIdx)) continue

            if (timeIdx >= 0 && dataDate == null) {
                dataDate = parseDate(cells[timeIdx])
            }
            val lat = cells[latIdx].toDoubleOrNull() ?: continue
            val lon = cells[lonIdx].toDoubleOrNull() ?: continue
            val raw = cells[valIdx].trim()
            val value = if (raw.isEmpty() || raw.equals("nan", ignoreCase = true)) {
                Double.NaN
            } else {
                raw.toDoubleOrNull() ?: Double.NaN
            }
            if (field.celsius && !value.isNaN()) {
                rows.add(Triple(lat, lon, value - 273.15))
            } else {
                rows.add(Triple(lat, lon, value))
            }
        }

        if (rows.isEmpty() || dataDate == null) {
            logger.warn("Subset CSV for ${field.datasetId} had no usable rows")
            return null
        }

        val lats = rows.map { it.first }.distinct().sorted()
        val lons = rows.map { it.second }.distinct().sorted()
        val latIndex = lats.withIndex().associate { it.value to it.index }
        val lonIndex = lons.withIndex().associate { it.value to it.index }

        val values = List(lats.size) { MutableList(lons.size) { Double.NaN } }
        for ((lat, lon, v) in rows) {
            values[latIndex.getValue(lat)][lonIndex.getValue(lon)] = v
        }

        return CopernicusGrid(
            field = field,
            lats = lats,
            lons = lons,
            values = values,
            dataDate = dataDate,
            sourceLabel = field.label
        )
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        logger.warn("Unparseable data date '$raw' in subset CSV")
        null
    }

    /** Split a CSV line on commas. `copernicusmarine` output has no quoting. */
    private fun splitCsvLine(line: String): List<String> = line.trim().split(',')

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
}

/**
 * One dataset the PFZ front-finder subscribes to. Each maps to a concrete
 * subset-able dataset id, its variable, the human product label used for
 * provenance, whether the raw value needs Kelvin->Celsius conversion, and the
 * dataset's supported lon/lat coverage (the box is clamped to it).
 */
enum class CopernicusField(
    val datasetId: String,
    val variable: String,
    val label: String,
    val celsius: Boolean,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    /** 1/120-degree (0.00833) gap-free L4 SST analysis. */
    SST(
        "SST_MED_SST_L4_NRT_OBSERVATIONS_010_004_c_V2",
        "analysed_sst",
        "Copernicus Med L4 SST analysis (gap-free, daily)",
        celsius = true,
        minLat = 30.25, maxLat = 46.0, minLon = -18.12, maxLon = 36.25
    ),

    /** 1/120-degree SST anomaly in degrees Celsius. */
    SSTA(
        "SST_MED_SSTA_L4_NRT_OBSERVATIONS_010_004_d",
        "sst_anomaly",
        "Copernicus Med L4 SST anomaly",
        celsius = false,
        minLat = 30.25, maxLat = 46.0, minLon = -18.12, maxLon = 36.25
    ),

    /** 1 km daily gap-free L4 chlorophyll-a, in mg/m3. */
    CHL(
        "cmems_obs-oc_med_bgc-plankton_nrt_l4-gapfree-multi-1km_P1D",
        "CHL",
        "Copernicus Med gap-free L4 chlorophyll (1 km, daily)",
        celsius = false,
        minLat = 30.0, maxLat = 46.0, minLon = -18.5, maxLon = 36.5
    )
}

/**
 * A regular lat/lon grid of one [CopernicusField] for one date.
 *
 * [values] is indexed `values[latIndex][lonIndex]`; `Double.NaN` marks a cell
 * the dataset knowingly left empty (cloud gap) as opposed to a real zero.
 */
data class CopernicusGrid(
    val field: CopernicusField,
    val lats: List<Double>,
    val lons: List<Double>,
    val values: List<List<Double>>,
    val dataDate: LocalDate,
    val sourceLabel: String
)

/** Outcome of one subprocess run, isolated so tests can fake the CLI. */
data class SubsetRunResult(
    val exitCode: Int,
    val stdout: String,
    val timedOut: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/** Spawns the real `copernicusmarine subset` process. */
class CopernicusSubsetProcess(
    private val timeoutMs: Long = 180_000
) : CopernicusSubsetRunner {
    override fun run(
        command: List<String>,
        env: Map<String, String>,
        outputDir: Path
    ): SubsetRunResult {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(env)
        builder.redirectErrorStream(true)
        val process = builder.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return SubsetRunResult(-1, "timed out after ${timeoutMs}ms", timedOut = true)
        }
        val output = process.inputStream.bufferedReader().readText()
        return SubsetRunResult(process.exitValue(), output)
    }
}

/** Functional seam so [CopernicusGridClient] is testable without the CLI. */
fun interface CopernicusSubsetRunner {
    fun run(command: List<String>, env: Map<String, String>, outputDir: Path): SubsetRunResult
}