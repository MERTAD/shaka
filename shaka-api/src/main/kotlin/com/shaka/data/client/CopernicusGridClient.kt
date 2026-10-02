package com.shaka.data.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
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
 * regular lat/lon grid, and returns it (or nothing, honestly, on any failure).
 *
 * Three properties matter at scale and are implemented here:
 *
 * 1. **Dataset batching.** A single `subset` call accepts repeated
 *    `--variable` flags, so every field of one product costs one subprocess
 *    rather than one per field. The two current components (`uo`, `vo`) and the
 *    full 141-level salinity/current columns are one request each.
 * 2. **Depth-aware parsing.** Physics products come back either as a 2D
 *    column (`time,latitude,longitude,<var>`) or as a 3D column
 *    (`depth,latitude,longitude,time,<vars>`). For a [CopernicusDepth.BEDROCK]
 *    field the parser keeps the *deepest level that is not masked* at each
 *    lat/lon cell and records the depth it was taken from, so a "bottom"
 *    value is never silently a surface or a mid-water one.
 * 3. **Persistent CSV cache.** The subprocess is 9–20 s of network plus Python
 *    startup; the parse is milliseconds. Caching the raw CSV on disk (keyed by
 *    dataset, variables, box and temporal window) is what makes a second request
 *    for the same day cheap. Cached data is still real, dated, provenance-
 *    tagged data, so it is served even when credentials are absent.
 *
 * Credentials are read from the environment and forwarded to the child only
 * through its process environment — never in argv, never in logs.
 *
 * The CLI path defaults to this development machine's installation and can be
 * overridden with `COPERNICUSMARINE_CLI`. Credentials come from
 * `COPERNICUSMARINE_SERVICE_USERNAME` / `_PASSWORD`, falling back to
 * `COP_USER` / `COP_PASS`.
 *
 * Rate limited through [RateLimiters.copernicus] and circuit-breaker protected,
 * so a dead or slow service makes PFZ report missing factors instead of hanging
 * or retrying in a loop.
 */
class CopernicusGridClient(
    private val runner: CopernicusSubsetRunner = CopernicusSubsetProcess(),
    private val username: String? = System.getenv("COPERNICUSMARINE_SERVICE_USERNAME")
        ?: System.getenv("COP_USER"),
    private val password: String? = System.getenv("COPERNICUSMARINE_SERVICE_PASSWORD")
        ?: System.getenv("COP_PASS"),
    private val cliPath: String = System.getenv("COPERNICUSMARINE_CLI") ?: resolveCliPath(),
    private val cache: CopernicusGridCache = CopernicusGridCache()
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
         * The toolbox executable to shell out to when `COPERNICUSMARINE_CLI` is
         * unset.
         *
         * A bare command name, not a path. The previous value was an absolute
         * path into one developer's user profile, which made the default
         * unusable on every other host and in CI for no gain: the launcher
         * passes this straight to the process as argv[0], so the OS resolves it
         * through PATH exactly as it would for `git` or `python`. The toolbox is
         * installed by `copernicusmarine install`, which puts it on PATH; where
         * it is not, set `COPERNICUSMARINE_CLI` to the full path.
         */
        const val DEFAULT_CLI_PATH = "copernicusmarine"
        const val DEFAULT_BOX_DEG = 0.3

        /**
         * Absolute path to the toolbox, or [DEFAULT_CLI_PATH] to let the OS
         * resolve it through PATH.
         *
         * A `pip install --user` puts the toolbox in the per-user Scripts
         * directory, which Windows does NOT add to PATH for GUI processes, so
         * PATH alone fails on a normal desktop even when the toolbox is
         * installed. Deriving that location from `APPDATA` keeps the default
         * working on any Windows account without hardcoding one developer's
         * profile into the source.
         */
        fun resolveCliPath(): String {
            val appData = System.getenv("APPDATA") ?: return DEFAULT_CLI_PATH
            val scripts = Path.of(appData, "Python", "Python313", "Scripts")
            val exe = scripts.resolve("copernicusmarine.exe")
            return if (Files.isExecutable(exe)) exe.toString() else DEFAULT_CLI_PATH
        }
    }

    private val credsReady: Boolean get() = username != null && password != null

    /**
     * Fetch several fields, batching by dataset so two variables of one product
     * share a single subprocess. Fields whose product could not be read are
     * simply absent from the result — never present with a guessed value.
     */
    suspend fun fetchGrids(
        fields: List<CopernicusField>,
        lat: Double,
        lon: Double,
        boxDeg: Double = DEFAULT_BOX_DEG,
        date: LocalDate
    ): Map<CopernicusField, CopernicusGrid> {
        if (fields.isEmpty()) return emptyMap()
        val out = LinkedHashMap<CopernicusField, CopernicusGrid>()
        for ((datasetId, group) in fields.groupBy { it.datasetId }) {
            out += fetchGroup(datasetId, group, lat, lon, boxDeg, date)
        }
        return out
    }

    /**
     * Fetch one field as a lat/lon grid centred on [lat], [lon] within [boxDeg].
     *
     * Returns null (never a guessed grid) when the product is out of its
     * temporal coverage, the box falls outside the dataset, credentials are
     * missing and nothing is cached, the service is down, the CLI fails, or the
     * response is unparseable. The caller reports this as a named missing factor.
     */
    suspend fun fetchGrid(
        field: CopernicusField,
        lat: Double,
        lon: Double,
        boxDeg: Double = DEFAULT_BOX_DEG,
        date: LocalDate
    ): CopernicusGrid? = fetchGrids(listOf(field), lat, lon, boxDeg, date)[field]

    /** True when a later fetch could join the log without re-running the CLI. */
    fun allowsRequests(): Boolean = circuitBreaker.allowsRequests()

    // ------------------------------------------------------------- one product

    private suspend fun fetchGroup(
        datasetId: String,
        group: List<CopernicusField>,
        lat: Double,
        lon: Double,
        boxDeg: Double,
        date: LocalDate
    ): Map<CopernicusField, CopernicusGrid> {
        val requested = group.filter { temporalWindow(it, date) != null }
        val skipped = group - requested.toSet()
        if (skipped.isNotEmpty()) {
            logger.debug(
                "Copernicus ${skipped.joinToString { it.variable }} skipped: $date is outside " +
                    "the product's temporal coverage"
            )
        }
        if (requested.isEmpty()) return emptyMap()

        val box = boxFor(requested, lat, lon, boxDeg) ?: return emptyMap()
        val window = requested.first().let { temporalWindow(it, date)!! }
        val key = cacheKey(requested, box, window)

        cache.read(key)?.let { csv ->
            parseCsv(csv, requested).takeIf { it.isNotEmpty() }?.let { return it }
            logger.warn("Cached Copernicus subset for $datasetId was unreadable - refetching")
        }

        if (!credsReady) {
            logger.warn(
                "Copernicus subset skipped for $datasetId: " +
                    "COPERNICUSMARINE_SERVICE_USERNAME/PASSWORD not set in the environment " +
                    "and nothing cached for this box"
            )
            return emptyMap()
        }

        RateLimiters.copernicus.acquire()
        val tmp = Files.createTempDirectory("copernicus-grid")
        return try {
            val csv = try {
                circuitBreaker.execute {
                    withContext(Dispatchers.IO) {
                        runSubset(datasetId, requested, box, window, tmp)
                    }
                }
            } catch (e: CircuitBreakerOpenException) {
                logger.debug("Circuit breaker open for Copernicus subset - reporting grid unavailable")
                null
            } catch (e: Exception) {
                logger.warn("Copernicus subset for $datasetId failed: ${e.message}")
                null
            } ?: return emptyMap()

            cache.write(key, csv)
            parseCsv(csv, requested)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * The single day (daily products) or single month-start (monthly products)
     * this field can actually serve for [date], or null when [date] precedes the
     * product's archive. Clamping is deliberate for the daily NRT lag, but a
     * request *before* the archive is refused rather than answered with some
     * other day's data.
     */
    private fun temporalWindow(field: CopernicusField, date: LocalDate): Pair<LocalDate, LocalDate>? =
        when (field.timeGranularity) {
            TimeGranularity.DAILY -> {
                val clamped = safeDate(date)
                if (clamped.isBefore(field.earliestDate)) null else clamped to clamped
            }

            TimeGranularity.MONTHLY -> {
                // A monthly mean is only published once the month is complete, so
                // the latest servable stamp is the first of the previous month.
                val monthStart = YearMonth.from(date).minusMonths(1).atDay(1)
                if (monthStart.isBefore(field.earliestDate)) null else monthStart to monthStart
            }
        }

    private data class Box(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    /**
     * The requested box clamped to the *intersection* of the products'
     * coverage. Intersection, not union: a batched request must be a rectangle
     * that is inside every product it carries, otherwise the tightest member
     * would silently get fewer rows than the caller expects.
     */
    private fun boxFor(group: List<CopernicusField>, lat: Double, lon: Double, boxDeg: Double): Box? {
        val minLat = (lat - boxDeg).coerceAtLeast(group.maxOf { it.minLat })
        val maxLat = (lat + boxDeg).coerceAtMost(group.minOf { it.maxLat })
        val minLon = (lon - boxDeg).coerceAtLeast(group.maxOf { it.minLon })
        val maxLon = (lon + boxDeg).coerceAtMost(group.minOf { it.maxLon })
        if (minLat >= maxLat || minLon >= maxLon) {
            logger.warn(
                "Box for ($lat, $lon) falls outside coverage of " +
                    group.joinToString { it.variable }
            )
            return null
        }
        return Box(minLat, maxLat, minLon, maxLon)
    }

    /**
     * Identity of one subset request. Hashed because the raw parts contain `|`
     * and `:`, which are not legal in a Windows file name and would silently
     * disable the cache on the platform this app is developed on.
     */
    private fun cacheKey(
        group: List<CopernicusField>,
        box: Box,
        window: Pair<LocalDate, LocalDate>
    ): String = CopernicusGridCache.keyOf(
        group.first().datasetId,
        group.map { it.variable }.sorted().joinToString("+"),
        fmt(box.minLat), fmt(box.maxLat), fmt(box.minLon), fmt(box.maxLon),
        window.first.toString(), window.second.toString()
    )

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
        datasetId: String,
        group: List<CopernicusField>,
        box: Box,
        window: Pair<LocalDate, LocalDate>,
        tmp: Path
    ): Path? {
        val args = buildList {
            add(cliPath)
            add("subset")
            add("--dataset-id"); add(datasetId)
            for (field in group.sortedBy { it.variable }) {
                add("--variable"); add(field.variable)
            }
            add("--minimum-longitude"); add(fmt(box.minLon))
            add("--maximum-longitude"); add(fmt(box.maxLon))
            add("--minimum-latitude"); add(fmt(box.minLat))
            add("--maximum-latitude"); add(fmt(box.maxLat))
            add("--start-datetime"); add(window.first.toString())
            add("--end-datetime"); add(window.second.toString())
            add("--file-format"); add("csv")
            add("--output-directory"); add(tmp.toString())
            add("--overwrite")
        }
        val result = runner.run(args, buildStringMap(), tmp)
        if (result.timedOut) {
            logger.warn("Copernicus subset for $datasetId timed out")
            return null
        }
        if (result.exitCode != 0) {
            logger.warn(
                "Copernicus subset for $datasetId exited ${result.exitCode}: " +
                    result.toString().take(500)
            )
            return null
        }
        return firstCsv(tmp) ?: run {
            logger.warn("Copernicus subset for $datasetId produced no CSV")
            null
        }
    }

    private fun buildStringMap(): Map<String, String> = buildMap {
        username?.let { put("COPERNICUSMARINE_SERVICE_USERNAME", it) }
        password?.let { put("COPERNICUSMARINE_SERVICE_PASSWORD", it) }
    }

    private fun firstCsv(dir: Path): Path? =
        Files.newDirectoryStream(dir, "*.csv").use { stream -> stream.firstOrNull() }

    // ---------------------------------------------------------------- parsing

    /** One CSV data row, still in the file's native (K) units. */
    private class RawRow(
        val depth: Double,
        val lat: Double,
        val lon: Double,
        val values: DoubleArray
    )

    /**
     * Parse a `copernicusmarine subset --file-format csv` output holding every
     * variable in [fields]. Two shapes are handled, both verified live:
     *
     *  - 2D:  `time,latitude,longitude,<var>`
     *  - 3D:  `depth,latitude,longitude,time,<vars>`
     *
     * Rows are NOT guaranteed ordered, so the grid is indexed by exact lat/lon
     * values: sorted unique latitudes/longitudes with a value matrix in between.
     * When a depth column is present and the field is
     * [CopernicusDepth.BEDROCK], the deepest level with a non-masked value wins
     * per cell and the depth it came from is kept in
     * [CopernicusGrid.cellDepthM]. Explicitly NaN cells stay NaN (missing),
     * never 0.
     *
     * A field absent from the header is dropped rather than fabricated, so a
     * product that ignored a `--variable` yields one fewer grid, not a zero grid.
     *
     * Internal so tests can drive the parser directly.
     */
    internal fun parseCsv(
        path: Path,
        fields: List<CopernicusField>
    ): Map<CopernicusField, CopernicusGrid> {
        if (fields.isEmpty()) return emptyMap()
        val lines = try {
            Files.readAllLines(path)
        } catch (e: Exception) {
            logger.warn("Cannot read subset CSV ${path.fileName}: ${e.message}")
            return emptyMap()
        }
        if (lines.size < 2) return emptyMap()

        val header = splitCsvLine(lines.first())
        val latIdx = header.indexOf("latitude")
        val lonIdx = header.indexOf("longitude")
        val timeIdx = header.indexOf("time")
        val depthIdx = header.indexOf("depth")
        if (latIdx < 0 || lonIdx < 0) {
            logger.warn("Subset CSV ${path.fileName} lacks latitude/longitude: $header")
            return emptyMap()
        }

        val present = fields.filter { header.contains(it.variable) }
        if (present.size < fields.size) {
            logger.warn(
                "Subset CSV ${path.fileName} lacks columns " +
                    (fields - present.toSet()).joinToString { it.variable } +
                    " — those fields are reported missing, not zero"
            )
        }
        if (present.isEmpty()) return emptyMap()
        val valIdx = present.associateWith { header.indexOf(it.variable) }
        val minWidth = maxOf(latIdx, lonIdx, depthIdx, timeIdx, valIdx.values.max()) + 1

        val rows = ArrayList<RawRow>(lines.size)
        var dataDate: LocalDate? = null
        for (line in lines.drop(1)) {
            val cells = splitCsvLine(line)
            if (cells.size < minWidth) continue
            if (timeIdx >= 0 && dataDate == null) dataDate = parseDate(cells[timeIdx])
            val lat = cells[latIdx].toDoubleOrNull() ?: continue
            val lon = cells[lonIdx].toDoubleOrNull() ?: continue
            val depth = if (depthIdx >= 0) cells[depthIdx].toDoubleOrNull() ?: 0.0 else 0.0
            val values = DoubleArray(present.size) { k ->
                val raw = cells[valIdx.getValue(present[k])].trim()
                when {
                    raw.isEmpty() || raw.equals("nan", ignoreCase = true) -> Double.NaN
                    else -> raw.toDoubleOrNull() ?: Double.NaN
                }
            }
            rows += RawRow(depth, lat, lon, values)
        }

        if (rows.isEmpty() || dataDate == null) {
            logger.warn("Subset CSV ${path.fileName} had no usable rows")
            return emptyMap()
        }

        val lats = rows.map { it.lat }.distinct().sorted()
        val lons = rows.map { it.lon }.distinct().sorted()
        val latIndex = lats.withIndex().associate { it.value to it.index }
        val lonIndex = lons.withIndex().associate { it.value to it.index }

        val out = LinkedHashMap<CopernicusField, CopernicusGrid>(present.size)
        for ((k, field) in present.withIndex()) {
            val values = List(lats.size) { MutableList(lons.size) { Double.NaN } }
            val levelM = if (depthIdx >= 0) {
                List(lats.size) { MutableList(lons.size) { Double.NaN } }
            } else {
                null
            }
            // Deepest-non-masked for a bedrock field, plain last-write-wins for a
            // single-column product (one row per cell per day, so no conflict).
            val keepDeepest = field.depth == CopernicusDepth.BEDROCK && depthIdx >= 0
            for (row in rows) {
                val v = row.values[k]
                if (v.isNaN()) continue
                val i = latIndex[row.lat] ?: continue
                val j = lonIndex[row.lon] ?: continue
                val level = levelM?.get(i)?.get(j)
                if (keepDeepest && level != null && !level.isNaN() && row.depth < level) continue
                values[i][j] = if (field.celsius) v - 273.15 else v
                levelM?.get(i)?.set(j, row.depth)
            }
            if (values.all { row -> row.all { it.isNaN() } }) {
                logger.warn("Subset CSV ${path.fileName} had no valid ${field.variable} values")
                continue
            }
            out[field] = CopernicusGrid(
                field = field,
                lats = lats,
                lons = lons,
                values = values,
                dataDate = dataDate,
                sourceLabel = field.label,
                cellDepthM = levelM
            )
        }
        return out
    }

    /** Single-field convenience wrapper over [parseCsv]. */
    internal fun parseCsv(path: Path, field: CopernicusField): CopernicusGrid? =
        parseCsv(path, listOf(field))[field]

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
 * Verified `describe` extent shared by every `cmems_mod_med_phy_*_anfc`
 * product: -17.2917..36.2917 E, 30.1875..45.9792 N.
 *
 * Top-level rather than a companion member because the enum's constructor
 * defaults need them, and a companion of the enum is not initialised yet at
 * that point.
 */
const val PHY_MIN_LAT = 30.1875
const val PHY_MAX_LAT = 45.9792
const val PHY_MIN_LON = -17.2917
const val PHY_MAX_LON = 36.2917

/** First stamp in the daily physics archive (2024-08-26). */
val PHY_DAILY_EARLIEST: LocalDate = LocalDate.of(2024, 8, 26)

/** First month in the monthly-mean SSH archive (2023-11-01). */
val SSH_MONTHLY_EARLIEST: LocalDate = LocalDate.of(2023, 11, 1)

/** How a field is read out of its product's CSV. */
enum class CopernicusDepth {
    /** 2D product: one column per lat/lon, no depth axis. */
    COLUMN,

    /**
     * 3D product: take the deepest level that is not masked at each lat/lon cell
     * ("bottom" value). Used for bottom temperature, salinity and bottom current,
     * which is what the benthic species profiles are written against.
     */
    BEDROCK
}

/** Temporal resolution of a product, which decides how a date is clamped. */
enum class TimeGranularity { DAILY, MONTHLY }

/**
 * One dataset the PFZ front-finder subscribes to. Each maps to a concrete
 * subset-able dataset id, its variable, the human product label used for
 * provenance, whether the raw value needs Kelvin->Celsius conversion, the
 * dataset's supported lon/lat coverage (the box is clamped to it), the depth
 * rule the parser applies, the product's temporal granularity, and the first
 * date the product's archive actually contains.
 *
 * The coverage and archive defaults describe the `cmems_mod_med_phy_*_anfc`
 * model family, which every physics entry shares; the observational products
 * declare their own extent.
 */
enum class CopernicusField(
    val datasetId: String,
    val variable: String,
    val label: String,
    val celsius: Boolean,
    val minLat: Double = PHY_MIN_LAT,
    val maxLat: Double = PHY_MAX_LAT,
    val minLon: Double = PHY_MIN_LON,
    val maxLon: Double = PHY_MAX_LON,
    val depth: CopernicusDepth = CopernicusDepth.COLUMN,
    val timeGranularity: TimeGranularity = TimeGranularity.DAILY,
    val earliestDate: LocalDate = PHY_DAILY_EARLIEST
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
    ),

    /**
     * Potential temperature at the sea floor, already 2D and already in
     * degrees Celsius (no Kelvin conversion). This is the hake's `SBT` and the
     * red shrimp's controlling variable.
     */
    BOTTOM_TEMP(
        "cmems_mod_med_phy-tem_anfc_4.2km_P1D-m",
        "bottomT",
        "Copernicus Med daily bottom potential temperature (4.2 km, NRT)",
        celsius = false
    ),

    /**
     * Practical salinity, 3D (141 levels). Already in psu in the CSV despite the
     * `0.001` units string the catalogue reports.
     */
    BOTTOM_SALINITY(
        "cmems_mod_med_phy-sal_anfc_4.2km_P1D-m",
        "so",
        "Copernicus Med daily bottom salinity (4.2 km, NRT)",
        celsius = false,
        depth = CopernicusDepth.BEDROCK
    ),

    /** Zonal velocity, m/s. 3D; batched with [CURRENT_V] in one subset call. */
    CURRENT_U(
        "cmems_mod_med_phy-cur_anfc_4.2km_P1D-m",
        "uo",
        "Copernicus Med daily bottom zonal current (4.2 km, NRT)",
        celsius = false,
        depth = CopernicusDepth.BEDROCK
    ),

    /** Meridional velocity, m/s. 3D; batched with [CURRENT_U]. */
    CURRENT_V(
        "cmems_mod_med_phy-cur_anfc_4.2km_P1D-m",
        "vo",
        "Copernicus Med daily bottom meridional current (4.2 km, NRT)",
        celsius = false,
        depth = CopernicusDepth.BEDROCK
    ),

    /** Mixed layer thickness (sigma-theta), metres. 2D. */
    MLD(
        "cmems_mod_med_phy-mld_anfc_4.2km_P1D-m",
        "mlotst",
        "Copernicus Med daily mixed layer thickness (4.2 km, NRT)",
        celsius = false
    ),

    /** Sea surface height, metres. 2D daily mean; the first half of the anomaly. */
    SSH(
        "cmems_mod_med_phy-ssh_anfc_4.2km_P1D-m",
        "zos",
        "Copernicus Med daily sea surface height (4.2 km, NRT)",
        celsius = false
    ),

    /**
     * Monthly mean of the same SSH product. The static dataset of the SSH family
     * carries only `deptho`, `deptho_lev` and `mask` — there is no `mdt` — so the
     * SSH *anomaly* is derived as `zos_daily - zos_monthly_mean` rather than
     * published. Both terms are real model output; the differencing is ours.
     */
    SSH_MONTHLY(
        "cmems_mod_med_phy-ssh_anfc_4.2km_P1M-m",
        "zos",
        "Copernicus Med monthly mean sea surface height (4.2 km, NRT)",
        celsius = false,
        timeGranularity = TimeGranularity.MONTHLY,
        earliestDate = SSH_MONTHLY_EARLIEST
    )
}

/**
 * A regular lat/lon grid of one [CopernicusField] for one date.
 *
 * [values] is indexed `values[latIndex][lonIndex]`; `Double.NaN` marks a cell
 * the dataset knowingly left empty (cloud gap, land, masked sea bed) as opposed
 * to a real zero.
 *
 * [cellDepthM] is the depth each cell's value was taken from, for the 3D
 * products collapsed to their deepest valid level — null for 2D products, which
 * have no depth axis. It exists so a reported "bottom" value can say how close
 * to the sea bed it actually was instead of asking to be trusted.
 */
data class CopernicusGrid(
    val field: CopernicusField,
    val lats: List<Double>,
    val lons: List<Double>,
    val values: List<List<Double>>,
    val dataDate: LocalDate,
    val sourceLabel: String,
    val cellDepthM: List<List<Double>>? = null
)

/**
 * On-disk cache of raw `copernicusmarine subset` CSVs.
 *
 * The CSV is cached rather than the parsed grid on purpose: the subprocess and
 * network cost is 9–20 s, the parse is milliseconds, and keeping one file format
 * means the cache can never disagree with [CopernicusGridClient.parseCsv] about
 * what a file means.
 *
 * The key is a SHA-256 of the exact request (product, variables, box, temporal
 * window), so a batched two-variable call and two separate one-variable calls
 * do not collide, and a different box is a different entry. Entries expire so
 * late reprocessing of an NRT day can still reach users.
 */
class CopernicusGridCache(
    private val dir: Path = defaultDir(),
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {
    private val logger = LoggerFactory.getLogger(CopernicusGridCache::class.java)

    /** The cached CSV for [key], or null when absent or expired. */
    fun read(key: String): Path? {
        val file = dir.resolve("$key.csv")
        return try {
            if (!Files.isRegularFile(file)) return null
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()
            if (age > maxAgeMs) {
                logger.debug("Discarding expired Copernicus cache entry $key (age ${age}ms)")
                return null
            }
            file
        } catch (e: Exception) {
            logger.debug("Copernicus cache read failed for $key: ${e.message}")
            null
        }
    }

    /**
     * Copy [csv] into the cache and return the stored path, or null when the
     * cache directory is not writable — a read-only or full disk must degrade
     * to "no cache", never to a failed fetch.
     */
    fun write(key: String, csv: Path): Path? = try {
        Files.createDirectories(dir)
        val target = dir.resolve("$key.csv")
        val staging = dir.resolve("$key.$TEMP_SUFFIX")
        Files.copy(csv, staging, StandardCopyOption.REPLACE_EXISTING)
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        target
    } catch (e: Exception) {
        logger.debug("Copernicus cache write skipped for $key: ${e.message}")
        null
    }

    /** Drop expired entries and any interrupted staging files. Returns the count. */
    fun prune(): Int {
        return try {
            if (!Files.isDirectory(dir)) return 0
            val cutoff = System.currentTimeMillis() - maxAgeMs
            var removed = 0
            Files.newDirectoryStream(dir, "*.$TEMP_SUFFIX").use { stream ->
                stream.forEach { Files.deleteIfExists(it) }
            }
            Files.newDirectoryStream(dir, "*.csv").use { stream ->
                stream.forEach {
                    if (Files.getLastModifiedTime(it).toMillis() < cutoff && Files.deleteIfExists(it)) {
                        removed++
                    }
                }
            }
            removed
        } catch (e: Exception) {
            logger.debug("Copernicus cache prune failed: ${e.message}")
            0
        }
    }

    companion object {
        private const val TEMP_SUFFIX = "part"
        private const val DEFAULT_MAX_AGE_MS = 36L * 60 * 60 * 1000

        /**
         * `SHAKA_COPERNICUS_CACHE` when set, otherwise a per-user directory so
         * the cache survives restarts and redeploys of the same host.
         */
        fun defaultDir(): Path = System.getenv("SHAKA_COPERNICUS_CACHE")
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".shaka", "copernicus-cache")

        /** SHA-256 hex of the request parts — filesystem-safe and collision-free. */
        fun keyOf(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

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
