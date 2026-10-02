package com.shaka.api.routes

import com.shaka.data.cache.OceanDataCache
import com.shaka.data.cache.SpotDataCache
import com.shaka.data.client.CopernicusClient
import com.shaka.data.client.LandWaterClient
import com.shaka.data.client.SpotDatabase
import com.shaka.data.db.UserSpotRepository
import com.shaka.data.db.LegalAcceptanceRepository
import com.shaka.model.*
import com.shaka.fishing_intel.api.FishingIntelRoutes
import com.shaka.fishing_intel.api.IngestPostRequest
import com.shaka.fishing_intel.api.IngestResponse
import com.shaka.fishing_intel.db.FishingIntelDb
import com.shaka.fishing_intel.ai.FishingIntelAiService
import com.shaka.fishing_intel.processing.Deduplicator
import com.shaka.fishing_intel.processing.GeoResolver
import com.shaka.fishing_intel.processing.ResolvedGeo
import com.shaka.fishing_intel.processing.SpeciesNormalizer
import com.shaka.fishing_intel.processing.SoCalGazetteer
import com.shaka.fishing_intel.models.*
import com.shaka.PrefetchJobsKey
import com.shaka.pfz.PfzGridService
import com.shaka.pfz.PfzHistoryCell
import com.shaka.pfz.PfzHistoryDay
import com.shaka.pfz.PfzHistoryPoint
import com.shaka.pfz.PfzHistoryResponse
import com.shaka.pfz.PfzHistoryZone
import com.shaka.pfz.PfzService
import com.shaka.pfz.PfzSpeciesRegistry
import com.shaka.pfz.PfzZoneStore
import com.shaka.service.SpotService
import com.shaka.service.ForecastService
import com.shaka.service.HealthService
import com.shaka.service.WeatherTileService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.util.UUID

private val logger = org.slf4j.LoggerFactory.getLogger("SpotRoutes")

/**
 * Decode a JSON string list persisted as TEXT, tolerating a null or corrupt cell.
 *
 * A history row's driver/blocker list is written as JSON, but a hand-edited or
 * truncated row must not be able to fail the whole history response. A list that
 * cannot be read degrades to empty and the caller's other fields still render.
 */
private fun decodeList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

/** Parse BD thread title for leading general location tag (Inshore, Offshore, Islands, Bay, Harbor). Returns (zone, cleanedTitle) or (null, null). */
private fun parseBdTitleForLocationTag(title: String?): Pair<String?, String?> {
    if (title.isNullOrBlank()) return null to null
    val regex = Regex("^(Inshore|Offshore|Islands|Bay|Harbor)\\s*(.*)$", RegexOption.IGNORE_CASE)
    val match = regex.find(title.trim()) ?: return null to null
    val zone = match.groupValues[1].replaceFirstChar { it.uppercase() }
    val rest = match.groupValues[2].trim()
    return zone to rest.ifBlank { null }
}

/**
 * Bound a route handler's total time. Slow dependencies (hung DB, dead
 * upstream) must surface as a fast 503 the app can render, not an
 * infinite spinner (Jun 2026 outage mode).
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondWithDeadline(
    timeoutMs: Long = 15_000,
    block: suspend () -> Unit
) {
    try {
        kotlinx.coroutines.withTimeout(timeoutMs) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf("error" to "Request timed out; backing data temporarily unavailable")
        )
    }
}

fun Application.configureRouting() {
    val spotService = SpotService()
    val pfzService = PfzService(gridSource = PfzGridService())
    val forecastService = ForecastService()
    val copernicusClient = CopernicusClient()
    val healthService = HealthService()
    val userSpotRepository = UserSpotRepository()
    val legalAcceptanceRepository = LegalAcceptanceRepository()
    val landWaterClient = LandWaterClient()

    routing {
        route("/v1") {
            /**
             * Railway healthcheck. Includes a bounded DB ping so a hung
             * Postgres (Jun 2026 outage: pool exhausted, requests blocked
             * forever while this endpoint kept returning ok) makes Railway
             * restart/alert instead of serving a zombie API.
             */
            get("/health") {
                val dbOk = if (!com.shaka.data.db.DatabaseFactory.isConnected()) {
                    true  // in-memory mode (no DATABASE_URL): nothing to ping
                } else try {
                    kotlinx.coroutines.withTimeout(2000) {
                        com.shaka.data.db.DatabaseFactory.dbQuery {
                            org.jetbrains.exposed.sql.transactions.TransactionManager.current()
                                .exec("SELECT 1") { rs -> rs.next() } ?: false
                        }
                    }
                } catch (e: Exception) {
                    false
                }
                if (dbOk) {
                    call.respond(mapOf("status" to "ok", "service" to "shaka-api", "db" to "ok"))
                } else {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("status" to "unhealthy", "service" to "shaka-api", "db" to "unreachable")
                    )
                }
            }
            
            get("/health/freshness") {
                val cache = com.shaka.data.cache.SpotDataCache
                val allIds = cache.getAllSpotIds()
                val now = java.time.Instant.now()

                fun <T> freshJson(name: String, extract: (com.shaka.data.cache.SpotDataCache.SpotData) -> com.shaka.data.cache.SpotDataCache.CachedValue<T>?): Pair<String, String> {
                    val ages = allIds.mapNotNull { id ->
                        cache.get(id)?.let(extract)?.fetchedAt?.let {
                            java.time.Duration.between(it, now).toMinutes()
                        }
                    }.sorted()
                    val oldest = ages.lastOrNull()
                    val newest = ages.firstOrNull()
                    val median = ages.getOrNull(ages.size / 2)
                    return name to """{"coverage":"${ages.size}/${allIds.size}","oldestMinAgo":${oldest ?: "null"},"newestMinAgo":${newest ?: "null"},"medianMinAgo":${median ?: "null"}}"""
                }

                val types = listOf(
                    freshJson("tide") { it.tide },
                    freshJson("swell") { it.swell },
                    freshJson("wind") { it.wind },
                    freshJson("sst") { it.sst },
                    freshJson("visibility") { it.visibility },
                    freshJson("chlorophyll") { it.chlorophyll },
                    freshJson("gibs_satellite") { it.gibsChlorophyll },
                    freshJson("mpa") { it.mpa },
                    freshJson("solunar") { it.solunar }
                )

                // Thresholds are owned by MonitoringConfig (lawful-max derivation:
                // gate + interval + maxRun + 1h). Tide is NEVER age-based — its
                // spot_cache fetchedAt is months old by design (full-year
                // precompute); horizon health lives in /health/summary.
                val stale = types.filter { (name, json) ->
                    val threshold = com.shaka.monitoring.MonitoringConfig.freshnessThresholdMinutes(name)
                        ?: return@filter false
                    val match = Regex(""""medianMinAgo":(\d+)""").find(json)
                    match != null && match.groupValues[1].toLong() > threshold
                }.map { "\"${it.first}\"" }

                val status = if (stale.isEmpty()) "ok" else "stale"
                val typesJson = types.joinToString(",") { "\"${it.first}\":${it.second}" }

                call.respondText(
                    """{"status":"$status","totalSpots":${allIds.size},"staleTypes":[${stale.joinToString(",")}],"types":{$typesJson}}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }

            /**
             * Per-host metrics for the shared HTTP client (attempts, successes,
             * failures by class, latency, consecutive connect failures). Added
             * after the Jul 2026 outage where the shared client's pool wedged
             * silently for 12 days — this makes a recurrence observable.
             */
            get("/health/http") {
                call.respond(com.shaka.data.client.HttpClientMetrics.snapshot())
            }

            get("/health/jobs") {
                val runs = com.shaka.monitoring.MonitoringService.getAllLatestRuns()
                val nowMs = System.currentTimeMillis()
                // Registered jobs that have never reported are included as
                // "missing" so a silently-dead job is visible (with deploy grace
                // handled by /health/summary severity, not here).
                val registered = com.shaka.monitoring.MonitoringConfig.jobs.associateBy { it.name }
                val allNames = (runs.keys + registered.keys).toSortedSet()
                val json = allNames.joinToString(",") { name ->
                    val r = runs[name]
                    val spec = registered[name]
                    val extras = buildString {
                        if (spec != null) {
                            append(",\"expectedIntervalMs\":${spec.intervalMs},\"maxRunMs\":${spec.maxRunMs}")
                        }
                        if (r != null) {
                            append(",\"lastRunAgeMs\":${nowMs - r.finishedAt.toEpochMilli()}")
                            append(",\"severity\":\"${r.severity.wire}\"")
                        }
                    }
                    if (r != null) {
                        "\"$name\":{\"status\":\"${r.status}\",\"total\":${r.total},\"succeeded\":${r.succeeded},\"failed\":${r.failed},\"successRate\":${String.format("%.4f", r.successRate)},\"durationMs\":${r.durationMs},\"lastRun\":\"${r.finishedAt}\"$extras}"
                    } else {
                        "\"$name\":{\"status\":\"MISSING\",\"lastRun\":null$extras}"
                    }
                }
                call.respondText("{$json}", io.ktor.http.ContentType.Application.Json)
            }

            /**
             * Machine-readable self-assessment: the backend applies its own
             * thresholds (MonitoringConfig) and reports ok/degraded/critical
             * with causes. The synthetic monitor relays this instead of
             * duplicating thresholds. See docs/synthetic-monitor-design.md.
             */
            get("/health/summary") {
                val nowInstant = java.time.Instant.now()
                val logic = com.shaka.monitoring.HealthSummaryLogic
                val causes = mutableListOf<Pair<com.shaka.monitoring.Severity, com.shaka.monitoring.HealthCause>>()
                val schedulersDisabled = com.shaka.monitoring.MonitoringConfig.schedulersDisabled()

                // 1. DB ping (bounded, same as /health)
                val dbOk = if (!com.shaka.data.db.DatabaseFactory.isConnected()) true else try {
                    kotlinx.coroutines.withTimeout(2000) {
                        com.shaka.data.db.DatabaseFactory.dbQuery {
                            org.jetbrains.exposed.sql.transactions.TransactionManager.current()
                                .exec("SELECT 1") { rs -> rs.next() } ?: false
                        }
                    }
                } catch (e: Exception) { false }
                causes += logic.dbCause(dbOk)

                // 2. Jobs: latest-run severity + missed-run detection
                val runs = com.shaka.monitoring.MonitoringService.getAllLatestRuns()
                val uptimeMs = com.shaka.monitoring.MonitoringService.uptimeMs()
                val nowMs = System.currentTimeMillis()
                for (spec in com.shaka.monitoring.MonitoringConfig.jobs) {
                    val r = runs[spec.name]
                    causes += logic.jobCause(
                        spec = spec,
                        successRate = r?.successRate,
                        succeeded = r?.succeeded,
                        total = r?.total,
                        lastRunAgeMs = r?.let { nowMs - it.finishedAt.toEpochMilli() },
                        uptimeMs = uptimeMs,
                        schedulersDisabled = schedulersDisabled,
                    )
                }

                // 3. Age-based freshness (median), warn-tier only
                val cache = com.shaka.data.cache.SpotDataCache
                val allIds = cache.getAllSpotIds()
                fun medianAge(extract: (com.shaka.data.cache.SpotDataCache.SpotData) -> com.shaka.data.cache.SpotDataCache.CachedValue<*>?): Long? {
                    val ages = allIds.mapNotNull { id ->
                        cache.get(id)?.let(extract)?.fetchedAt?.let {
                            java.time.Duration.between(it, nowInstant).toMinutes()
                        }
                    }.sorted()
                    return ages.getOrNull(ages.size / 2)
                }
                if (!schedulersDisabled) {
                    listOf<Pair<String, (com.shaka.data.cache.SpotDataCache.SpotData) -> com.shaka.data.cache.SpotDataCache.CachedValue<*>?>>(
                        "swell" to { it.swell }, "wind" to { it.wind }, "sst" to { it.sst },
                        "visibility" to { it.visibility }, "chlorophyll" to { it.chlorophyll },
                        "gibs_satellite" to { it.gibsChlorophyll }, "mpa" to { it.mpa },
                        "solunar" to { it.solunar },
                    ).forEach { (type, extract) ->
                        logic.freshnessCause(type, medianAge(extract))?.let { causes += it }
                    }
                }

                // 4. Tide horizon (never age-based)
                val horizon = cache.getTideHorizonStats()
                val catalogSize = com.shaka.data.client.SpotDatabase.getAllSpots().size
                val coverage = horizon?.let { (it.readySpots.toDouble() / catalogSize).coerceAtMost(1.0) }
                causes += logic.tideHorizonCause(horizon?.medianRemainingDays, coverage)

                // 5. AI region insights (cached artifact — never triggers an LLM call)
                val aiEnabled = FishingIntelAiService.isEnabled()
                val slotZone = java.time.ZoneOffset.ofHours(-8)
                val slotKey = java.time.ZonedDateTime.now(slotZone).toLocalDate().toString()
                val hoursIntoDay = java.time.ZonedDateTime.now(slotZone).hour
                val hasInsights = try {
                    !FishingIntelDb.getRegionInsights("all_regions", slotKey).isNullOrEmpty()
                } catch (e: Exception) { false }
                val hadReports = if (hasInsights || !aiEnabled) true else try {
                    FishingIntelDb.getAllReportsRecent(hoursBack = 168).isNotEmpty()
                } catch (e: Exception) { true }
                causes += logic.aiInsightsCause(hasInsights, hoursIntoDay, hadReports, aiEnabled)

                // 6. Upstreams from Railway's vantage point (capped at degraded)
                if (!schedulersDisabled) {
                    try {
                        val upstream = healthService.checkHealth()
                        upstream.services.forEach { (name, st) ->
                            causes += logic.upstreamCause(name, st.status)
                        }
                    } catch (e: Exception) {
                        causes += logic.upstreamCause("health_service", "error: ${e.message?.take(60)}")
                    }
                }

                call.respond(logic.aggregate(causes, nowInstant.toString()))
            }

            get("/health/jobs/{name}") {
                val name = call.parameters["name"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name required"))
                val r = com.shaka.monitoring.MonitoringService.getLatestRun(name)
                if (r == null) {
                    call.respondText(
                        """{"status":"unknown","job":"$name"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.NotFound
                    )
                } else {
                    call.respondText(
                        """{"status":"${r.status}","total":${r.total},"succeeded":${r.succeeded},"failed":${r.failed},"successRate":${String.format("%.4f", r.successRate)},"durationMs":${r.durationMs},"lastRun":"${r.finishedAt}"}""",
                        io.ktor.http.ContentType.Application.Json
                    )
                }
            }

            // Detailed health with external service checks
            // Used by Flutter app to auto-degrade features
            get("/health/detailed") {
                val serviceHealth = healthService.checkHealth()
                val cacheStats = OceanDataCache.getStats()
                call.respond(DetailedHealthResponse(
                    status = serviceHealth.status,
                    service = "shaka-api",
                    services = serviceHealth.services,
                    realtimeSatelliteAvailable = copernicusClient.isDirectAccessAvailable(),
                    cache = cacheStats,
                    timestamp = serviceHealth.timestamp
                ))
            }

            // Search for spots by location
            get("/spots/search") {
                val lat = call.parameters["lat"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lat required"))
                val lon = call.parameters["lon"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lon required"))
                val radiusKm = call.parameters["radius"]?.toIntOrNull() ?: 50
                val date = call.parameters["date"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "date required"))

                val results = spotService.searchSpots(lat, lon, radiusKm, date)
                call.respond(results)
            }
            
            // Search spots by name (for type-ahead search)
            get("/spots/search/name") {
                val query = call.parameters["q"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "q (query) required"))
                val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
                
                val results = spotService.searchSpotsByName(query, limit)
                call.respond(results)
            }
            
            // Batch fetch spots by IDs (for favorites/home screen)
            get("/spots/batch") {
                val ids = call.parameters["ids"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ids required (comma-separated)"))
                val date = call.parameters["date"]?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString()
                
                val spotIds = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (spotIds.isEmpty()) {
                    return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one spot ID required"))
                }
                if (spotIds.size > 20) {
                    return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "maximum 20 spots per request"))
                }
                
                val results = spotService.getSpotsBatch(spotIds, date)
                call.respond(results)
            }
            
            // Get all regions (for search autocomplete)
            get("/regions") {
                val regions = spotService.getAllRegions()
                call.respond(regions)
            }
            
            /**
             * Get ALL spots for map display.
             * Returns SpotMapMarker with score and basic conditions from cache.
             * Used by Explore map to load all ~800 spots once on startup.
             */
            get("/spots/all") {
                val allSpots = SpotDatabase.getAllSpots()
                
                val markers = allSpots.map { spot ->
                    // Get cached score if available (fast lookup, no API calls)
                    val score = spotService.getUserSpotScore(spot.id)
                    // Get region from spot ID (e.g., "oahu-sharks-cove" -> "Oahu")
                    val region = spotService.getRegionForSpot(spot.id)
                    // Get cached condition data for display
                    val cached = SpotDataCache.get(spot.id)
                    
                    SpotMapMarker(
                        id = spot.id,
                        name = spot.name,
                        coordinates = spot.coordinates,
                        region = region,
                        shakaScore = score,
                        visibility = spotService.resolveVisibilityLabel(cached),
                        swell = cached?.swell?.value?.let { s ->
                            val ht = s.correctedHeightFt ?: s.heightFt
                            "${ht.roundToInt()}ft @ ${s.periodSec.roundToInt()}s ${s.direction}"
                        },
                        wind = cached?.wind?.value?.let { 
                            com.shaka.data.cache.SpotDataCache.formatWindLabel(it.speedKnots, it.direction) 
                        },
                        waterTemp = cached?.sst?.value?.let { sst ->
                            "${sst.toInt()}°C / ${((sst * 9/5) + 32).toInt()}°F"
                        },
                        waterTempC = cached?.sst?.value,
                        swellHeightFt = cached?.swell?.value?.let { (it.correctedHeightFt ?: it.heightFt).roundToInt().toDouble() },
                        windSpeedKts = cached?.wind?.value?.speedKnots
                    )
                }
                
                call.respond(AllSpotsResponse(
                    spots = markers,
                    count = markers.size
                ))
            }

            // Get spot detail
            get("/spots/{id}") {
                val spotId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                // An absent date and a blank one mean the same thing: the caller
                // never chose. `?:` only covers absent, and a client that sends
                // `?date=` would otherwise hand "" to the scorer.
                val date = call.parameters["date"]?.takeIf { it.isNotBlank() }
                    ?: java.time.LocalDate.now().toString()

                call.respondWithDeadline {
                    val spot = spotService.getSpotDetail(spotId, date)
                    if (spot != null) {
                        call.respond(spot)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Spot not found"))
                    }
                }
            }

            /**
             * Full hourly swell + wind curves for a spot (intraday chart).
             * Served from the in-memory series — fast, no API/DB on the hot path.
             */
            get("/spots/{id}/hourly") {
                val spotId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                val hourly = spotService.getSpotHourly(spotId)
                if (hourly != null) {
                    call.respond(hourly)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Hourly data not available"))
                }
            }

            /**
             * Near-real-time wind for a spot, fetched on demand by the client
             * AFTER the detail page paints so the detail load stays instant.
             * Bucket-cached (~0.05deg, 15min TTL) and bounded so a slow upstream
             * never hangs the request.
             */
            get("/spots/{id}/wind/live") {
                val spotId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                val wind = spotService.getLiveWind(spotId)
                if (wind != null) {
                    call.respond(wind)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Live wind not available"))
                }
            }

            /**
             * Species-specific Potential Fishing Zone for a spot and date.
             *
             * This is NOT a replacement for /spots/{id}; it is the habitat
             * question that endpoint cannot answer. /spots/{id} returns one
             * generic score, while this returns a separate verdict per species
             * with its own gates, weights, evidence provenance and confidence.
             *
             * Contract with the client: `pfz` is null unless `status` is
             * "scoreable". "unavailable" means the species is outside its
             * documented habitat here, NOT that it scored badly. Render the
             * `blockers` and `coverageNotes` rather than showing 0.
             *
             * Reads SpotDataCache and, when a grid corridor is configured,
             * enriches the observation with the Copernicus Med analysis: the
             * SST/SSTA/CHL front corridor plus bottom temperature, bottom
             * salinity, bottom current, mixed layer depth, the derived SSH
             * anomaly and dSST30. Upstream failures are time-bounded and
             * breakered, and surface as named missing factors - never as
             * invented fronts.
             *
             * Query parameters:
             *  - `mode` - behavioural mode, e.g. `feeding` or `spawning`. Applies
             *    only to species that declare modes; anything else is ignored and
             *    the default mode is used. Bluefin feeding and spawning are
             *    different models against near-inverted chlorophyll
             *    requirements, so the applied mode is echoed back per species.
             *  - `sizeClass` - e.g. `large`. A size class the applied mode was
             *    not parameterised for is refused with `insufficient_data`
             *    rather than answered with another class's numbers.
             *
             * A species that declares a `requiredFactor` which did not resolve
             * returns `insufficient_data` with a null `pfz` and a blocker
             * naming the factor. That is not a degradation: Colloca et al. (2014)
             * fit hake habitat as a product in which any zero term zeroes the
             * cell, so a score without bottom temperature is a different model,
             * not a weaker one.
             */
            get("/spots/{id}/pfz") {
                val spotId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                val date = call.parameters["date"]?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString()

                when (val result = pfzService.evaluateWithGrid(
                    spotId, date,
                    mode = call.request.queryParameters["mode"],
                    sizeClass = call.request.queryParameters["sizeClass"]
                )) {
                    is PfzService.Result.Ok -> call.respond(result.response)
                    is PfzService.Result.UnknownSpot ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Spot not found"))
                    is PfzService.Result.BadDate ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                }
            }

            /**
             * Ranked Potential-Fishing-Zone candidates around an arbitrary
             * offshore point, for one species.
             *
             * Unlike /spots/{id}/pfz this is not anchored to a catalog spot: the
             * caller supplies a coordinate and a species id, and the response is
             * a ranked set of candidate zone centres (from the Copernicus Med
             * SST/SSTA/CHL corridor, plus a single-point bathymetric depth each),
             * scored by the same PfzEngine as the spot endpoint.
             *
             * Honesty contract, same as the rest of PFZ: a zone whose grid cell
             * did not resolve reports that field null, and a box where nothing
             * resolved returns an empty `zones` list with a coverage note —
             * never invented fronts. Zones outside a catalog spot have no sea
             * state, solunar or SST/CHL concentration, so those factors are
             * missing (weights renormalize), as the coverageNotes state.
             */
            get("/pfz/zones") {
                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lat required"))
                val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lon required"))
                val date = call.request.queryParameters["date"]?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString()
                val species = call.request.queryParameters["species"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "species required"))

                when (val result = pfzService.evaluateZones(
                    lat, lon, date, species,
                    mode = call.request.queryParameters["mode"],
                    sizeClass = call.request.queryParameters["sizeClass"]
                )) {
                    is PfzService.ZonesResult.Ok -> {
                        val response = result.response
                        // Persist on request so a location/species pair starts
                        // building history immediately, rather than waiting up to
                        // a day for the scheduled job to discover it exists. This
                        // is a fire-and-forget write on a path the user is already
                        // waiting on, so it must never be able to fail the request.
                        //
                        // The mode and size class must be the ones the *engine*
                        // applied, not the raw query values: when the caller omits
                        // them the engine falls back to the species' default mode
                        // and default size class, and persisting under "default"
                        // while the applied model was "feeding/large" would write a
                        // history that the requester can never read back.
                        launch {
                            runCatching {
                                if (response.zones.isNotEmpty()) {
                                    val appliedMode = response.zones.firstNotNullOfOrNull { it.mode }
                                    val appliedSize = response.zones.firstNotNullOfOrNull { it.sizeClass }
                                    // replaceDay, not saveZoneResponse: this response is
                                    // the model's current answer for this date, so a zone
                                    // that has dropped out of the set must lose its row.
                                    // A plain upsert would leave yesterday's rank-2 cell
                                    // in the history and quietly invent a zone.
                                    PfzZoneStore.replaceDay(response, appliedMode, appliedSize)
                                } else {
                                    // Empty is not a no-op. The corridor did not resolve
                                    // for this box today, so no zone can be persisted, but
                                    // rows written by an earlier request for the same date
                                    // would otherwise survive and be read back as though
                                    // they were today's answer. The applied context has to
                                    // come from the profile here rather than from the
                                    // zones, because there are none to read it off —
                                    // the same resolution the history endpoint does, so
                                    // this clears exactly the series a reader would ask
                                    // for. The empty day itself needs no row: the history
                                    // layer already reports an absent day as a gap.
                                    val rawMode = call.request.queryParameters["mode"]
                                    val rawSizeClass = call.request.queryParameters["sizeClass"]
                                    val profile = PfzSpeciesRegistry.byId(species)
                                    PfzZoneStore.deleteDay(
                                        response.date,
                                        response.lat,
                                        response.lon,
                                        response.speciesId,
                                        rawMode ?: profile?.resolve(rawMode, rawSizeClass)?.mode,
                                        rawSizeClass ?: profile?.resolve(rawMode, rawSizeClass)?.sizeClass
                                    )
                                }
                            }.onFailure { logger.warn("PFZ zone persist on request failed: ${it.message}") }
                        }
                        call.respond(response)
                    }
                    is PfzService.ZonesResult.UnknownSpecies -> call.respond(
                        HttpStatusCode.NotFound,
                        mapOf(
                            "error" to "Unknown species",
                            "knownSpecies" to PfzSpeciesRegistry.all().map { it.id }
                        )
                    )
                    is PfzService.ZonesResult.BadDate ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                    is PfzService.ZonesResult.BadLocation ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                }
            }

            /**
             * Persisted day-over-day zone history for one request.
             *
             * Reads `pfz_zones_daily` — it does NOT recompute. A trend is a claim
             * about what the model said on each past day, and re-running today's
             * model over yesterday's date would answer a different question:
             * yesterday's ocean fields have been revised since, and a
             * Copernicus request for a past date can legitimately be refused
             * because the archive ends. What the user saw yesterday is the only
             * honest thing to show them today, which is why it was stored.
             *
             * The trend is per GRID CELL, not per rank. Ranks are re-assigned every
             * day, so "rank 1 improved" would compare two different patches of
             * ocean. A cell's own score series is the only comparison the data
             * supports, and `dayOverDay` is null unless the most recent two
             * persisted days both produced a scoreable zone.
             *
             * A day with no scoreable zone is a GAP, not a zero. `pfz` is null for
             * "outside its habitat" and for "a required measurement did not
             * resolve"; both mean we could not see it, and neither is bad ground.
             *
             * Query parameters: `lat`, `lon`, `species` (all required), plus
             * `mode`, `sizeClass` (must match what the zones call used — the model
             * context is part of the key, because a bluefin feeding score and a
             * bluefin spawning score are near-inverted models) and `days`
             * (1-120, default 7).
             *
             * An empty `days` list is a 200, not a 404: history only exists once
             * something has been persisted, and a new location legitimately has
             * none yet. The response says so in `coverageNotes`.
             */
            get("/pfz/zones/history") {
                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lat required"))
                val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lon required"))
                val species = call.request.queryParameters["species"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "species required"))

                if (!lat.isFinite() || lat > 90 || lat < -90 || !lon.isFinite() || lon > 180 || lon < -180) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "lat in [-90, 90], lon in [-180, 180], got ($lat, $lon)")
                    )
                }
                if (PfzSpeciesRegistry.byId(species) == null) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Unknown species", "knownSpecies" to PfzSpeciesRegistry.all().map { it.id })
                    )
                }

                val rawMode = call.request.queryParameters["mode"]
                val rawSizeClass = call.request.queryParameters["sizeClass"]
                val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).coerceIn(1, 120)
                val to = java.time.LocalDate.now()
                val from = to.minusDays(days - 1L)

                val profile = PfzSpeciesRegistry.byId(species)!!

                // Resolve the model context the same way the scorer did. Rows are
                // stored under the *applied* mode ("feeding"), while an omitted
                // query parameter would otherwise normalise to the literal
                // "default" and match nothing — so a caller that never passes a
                // mode would see an empty history forever.
                val resolved = profile.resolve(rawMode, rawSizeClass)
                val mode = rawMode ?: resolved.mode
                val sizeClass = rawSizeClass ?: resolved.sizeClass

                val rows = PfzZoneStore.loadHistory(lat, lon, species, mode, sizeClass, from, to)
                val trend = PfzZoneStore.trend(rows, lat, lon, species, mode, sizeClass)

                call.respond(
                    PfzHistoryResponse(
                        speciesId = species,
                        speciesName = profile.commonName,
                        speciesScientificName = profile.scientificName,
                        anchorLat = lat,
                        anchorLon = lon,
                        mode = trend.mode,
                        sizeClass = trend.sizeClass,
                        fromDate = from.toString(),
                        toDate = to.toString(),
                        latestTopPfz = trend.latestTopPfz,
                        dayOverDay = trend.dayOverDay,
                        days = trend.days.map { day ->
                            PfzHistoryDay(
                                date = day.date,
                                topPfz = day.topPfz,
                                confidence = day.confidence,
                                zoneCount = day.zones.size,
                                scoreableCount = day.zones.count { it.status == PfzZoneStore.SCOREABLE },
                                zones = day.zones.map { row ->
                                    PfzHistoryZone(
                                        rank = row.rank,
                                        name = row.zoneName,
                                        lat = row.cellLat,
                                        lon = row.cellLon,
                                        status = row.status,
                                        pfz = row.pfz,
                                        confidence = row.confidence,
                                        frontKm = row.frontKm,
                                        depthM = row.depthM,
                                        region = row.region,
                                        drivers = decodeList(row.driversJson),
                                        blockers = decodeList(row.blockersJson),
                                        missingFactors = decodeList(row.missingFactorsJson)
                                    )
                                }
                            )
                        },
                        cells = trend.series.map { cell ->
                            PfzHistoryCell(
                                lat = cell.cellLat,
                                lon = cell.cellLon,
                                firstPfz = cell.firstPfz,
                                lastPfz = cell.lastPfz,
                                change = cell.change,
                                points = cell.points.map { p ->
                                    PfzHistoryPoint(date = p.date, pfz = p.pfz, confidence = p.confidence, rank = p.rank)
                                }
                            )
                        },
                        coverageNotes = trend.coverageNotes
                    )
                )
            }

            /**
             * Multi-day tide chart curves for a spot, grouped by spot-local day.
             * Reads the persisted spot_tide_days rows (no API on the hot path).
             */
            get("/spots/{id}/tide") {
                val spotId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).coerceIn(1, 16)
                val tide = spotService.getTideRange(spotId, days)
                if (tide != null) {
                    call.respond(tide)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Tide data not available"))
                }
            }

            /**
             * Get REAL-TIME satellite water clarity data for a spot.
             * 
             * WARNING: This endpoint is SLOW (30-60 seconds) because it processes
             * raw Sentinel-3 satellite imagery on-demand for the most current data.
             * 
             * Use the regular /spots/{id} endpoint for fast (2-5s) cached data.
             * Only call this endpoint when user explicitly requests real-time satellite data.
             * 
             * Requires COPERNICUS_CLIENT_ID and COPERNICUS_CLIENT_SECRET to be configured.
             */
            get("/spots/{id}/realtime-clarity") {
                val spotId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))
                val date = call.parameters["date"]?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString()

                // Check if direct Sentinel-3 access is available
                if (!copernicusClient.isDirectAccessAvailable()) {
                    return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Real-time satellite data not available",
                            "reason" to "Copernicus credentials not configured",
                            "suggestion" to "Use /spots/{id} for standard water quality data"
                        )
                    )
                }

                // Get spot coordinates
                val spot = SpotDatabase.findSpotById(spotId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Spot not found"))

                try {
                    // Fetch real-time satellite data (SLOW - 30-60 seconds)
                    val waterQuality = copernicusClient.getRealTimeWaterQuality(
                        spot.coordinates.lat,
                        spot.coordinates.lon,
                        date
                    )

                    call.respond(RealtimeWaterQualityResponse(
                        spotId = spotId,
                        spotName = spot.name,
                        date = date,
                        dataSource = waterQuality.dataSource,
                        waterClarity = WaterClarityData(
                            visibility = WaterClarityVisibility(
                                meters = waterQuality.visibility,
                                category = waterQuality.visibilityCategory
                            ),
                            chlorophyll = WaterClarityChlorophyll(
                                value = waterQuality.chlorophyllA,
                                category = waterQuality.chlorophyllCategory
                            ),
                            seaSurfaceTemp = WaterClaritySST(
                                celsius = waterQuality.seaSurfaceTemp,
                                fahrenheit = waterQuality.seaSurfaceTemp?.let { (it * 9/5) + 32 }
                            )
                        ),
                        note = "Real-time Sentinel-3 satellite data. Updated from latest available pass."
                    ))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to fetch real-time satellite data",
                            "message" to e.message,
                            "suggestion" to "Use /spots/{id} for standard water quality data"
                        )
                    )
                }
            }

            // Get forecast for a spot (curated or user-created)
            get("/forecast/{spotId}") {
                val spotId = call.parameters["spotId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "spotId required"))
                val days = call.parameters["days"]?.toIntOrNull() ?: 7

                val curated = SpotDatabase.findSpotById(spotId)
                val forecast = if (curated != null) {
                    forecastService.getForecast(spotId, days)
                } else {
                    val userSpot = userSpotRepository.findById(spotId)
                    if (userSpot != null) {
                        forecastService.getForecastForLocation(
                            userSpot.coordinates.lat,
                            userSpot.coordinates.lon,
                            days
                        )
                    } else {
                        emptyList()
                    }
                }
                call.respond(forecast)
            }

            // Get community reports for a region
            get("/reports/{region}") {
                val region = call.parameters["region"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "region required"))

                val reports = spotService.getCommunityReports(region)
                call.respond(reports)
            }

            // Clear cache (admin endpoint)
            post("/admin/cache/clear") {
                OceanDataCache.clearAll()
                call.respond(mapOf("status" to "ok", "message" to "Cache cleared"))
            }

            // Get cache stats
            get("/admin/cache/stats") {
                call.respond(OceanDataCache.getStats())
            }
            
            // Clear all chlorophyll values (removes fake climatology data)
            post("/admin/chlorophyll/clear") {
                val cleared = com.shaka.data.cache.SpotDataCache.clearAllChlorophyll()
                call.respondText("""{"status":"ok","cleared":$cleared}""", io.ktor.http.ContentType.Application.Json)
            }
            
            // Get chlorophyll stats
            get("/admin/chlorophyll/stats") {
                call.respondText(
                    com.shaka.data.cache.SpotDataCache.getChlorophyllStatsJson(),
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            post("/admin/depth/refetch") {
                val bathymetryClient = com.shaka.data.client.BathymetryClient()

                data class DepthTarget(val cacheId: String, val lat: Double, val lon: Double)

                val curatedTargets = SpotDatabase.getAllSpots().map {
                    DepthTarget(it.id.toString(), it.coordinates.lat, it.coordinates.lon)
                }
                val userTargets = com.shaka.data.db.UserSpotRepository.getAllUserSpots().map {
                    DepthTarget("user-${it.id}", it.coordinates.lat, it.coordinates.lon)
                }
                val needingDepth = (curatedTargets + userTargets).filter { target ->
                    val cached = com.shaka.data.cache.SpotDataCache.get(target.cacheId)
                    cached?.exposure != null && (cached.exposure.depthM == null || cached.exposure.depthSource != "ncei")
                }
                val total = needingDepth.size

                GlobalScope.launch {
                    val logger = org.slf4j.LoggerFactory.getLogger("DepthRefetch")
                    var success = 0
                    var failed = 0
                    for (target in needingDepth) {
                        try {
                            val dr = bathymetryClient.fetchDepthOnly(target.lat, target.lon)
                            if (dr == null) {
                                failed++
                                continue
                            }
                            val existing = com.shaka.data.cache.SpotDataCache.get(target.cacheId)?.exposure ?: continue
                            val updated = existing.copy(depthM = dr.depthM, depthSource = dr.source)
                            com.shaka.data.cache.SpotDataCache.updateExposure(target.cacheId, updated)
                            com.shaka.data.cache.SpotDataCache.saveToDatabase(target.cacheId)
                            success++
                            if (success % 50 == 0) logger.info("Depth refetch progress: $success/$total")
                        } catch (e: Exception) {
                            failed++
                        }
                        delay(15000)
                    }
                    logger.info("Depth refetch complete: $success success, $failed failed out of $total")
                }

                call.respondText(
                    """{"status":"started","spotsToFetch":$total,"message":"Depth refetch started in background (curated + user spots)."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }

            // Identify fake climatology chlorophyll values
            get("/admin/chlorophyll/identify-fake") {
                val fakeSpots = com.shaka.data.cache.SpotDataCache.identifyFakeChlorophyll(SpotDatabase)
                val grouped = fakeSpots.groupBy { it.second }.mapValues { it.value.size }
                call.respondText(
                    """{"totalFake":${fakeSpots.size},"byValue":${grouped.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }},"spots":${fakeSpots.take(20).joinToString(",", "[", "]") { "\"${it.first}\"" }}}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Clear only fake climatology values (preserves real data)
            post("/admin/chlorophyll/clear-fake") {
                val cleared = com.shaka.data.cache.SpotDataCache.clearFakeChlorophyll(SpotDatabase)
                call.respondText("""{"status":"ok","cleared":$cleared}""", io.ktor.http.ContentType.Application.Json)
            }
            
            // Trigger refetch for all spots without chlorophyll
            post("/admin/chlorophyll/refetch") {
                val spotsToFetch = com.shaka.data.cache.SpotDataCache.getSpotsWithoutChlorophyll()
                val total = spotsToFetch.size
                
                // Return immediately with count - actual fetch happens in background
                // This avoids timeout for long-running operations
                kotlinx.coroutines.GlobalScope.launch {
                    var success = 0
                    var failed = 0
                    val today = java.time.LocalDate.now().toString()
                    
                    for (spotId in spotsToFetch) {
                        try {
                            val spot = SpotDatabase.findSpotById(spotId) ?: continue
                            val waterQuality = copernicusClient.getWaterQuality(
                                spot.coordinates.lat, 
                                spot.coordinates.lon, 
                                today
                            )
                            waterQuality.chlorophyllA?.let { chl ->
                                com.shaka.data.cache.SpotDataCache.updateChlorophyll(
                                    spotId,
                                    com.shaka.data.cache.SpotDataCache.CachedValue(
                                        value = chl,
                                        fetchedAt = java.time.Instant.now(),
                                        dataValidAt = java.time.Instant.now()
                                    )
                                )
                                com.shaka.data.cache.SpotDataCache.saveToDatabase(spotId)
                                success++
                            } ?: run { failed++ }
                        } catch (e: Exception) {
                            failed++
                        }
                        // Rate limit
                        kotlinx.coroutines.delay(500)
                    }
                }
                
                call.respondText(
                    """{"status":"started","spotsToFetch":$total,"message":"Refetch started in background. Check /admin/chlorophyll/stats for progress."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Clear all GIBS data (to force refetch with new color fields)
            post("/admin/gibs/clear") {
                val cleared = com.shaka.data.cache.SpotDataCache.clearAllGIBS()
                call.respondText(
                    """{"status":"ok","cleared":$cleared}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Trigger GIBS satellite color fetch for all spots without GIBS data
            post("/admin/gibs/refetch") {
                val spotsToFetch = com.shaka.data.cache.SpotDataCache.getSpotsWithoutGIBS()
                val total = spotsToFetch.size
                
                kotlinx.coroutines.GlobalScope.launch {
                    for (spotId in spotsToFetch) {
                        try {
                            val spot = SpotDatabase.findSpotById(spotId) ?: continue
                            val gibsColors = com.shaka.data.client.GIBSClient.getAllSatelliteColors(
                                spot.coordinates.lat, 
                                spot.coordinates.lon
                            )
                            com.shaka.data.cache.SpotDataCache.updateGIBSChlorophyll(
                                spotId,
                                com.shaka.data.cache.SpotDataCache.CachedValue(
                                    value = com.shaka.data.cache.SpotDataCache.GIBSSatelliteData(
                                        paceTodayColor = gibsColors.paceTodayColor,
                                        paceYesterdayColor = gibsColors.paceYesterdayColor,
                                        noaa20TodayColor = gibsColors.noaa20TodayColor,
                                        noaa20YesterdayColor = gibsColors.noaa20YesterdayColor,
                                        noaa21TodayColor = gibsColors.noaa21TodayColor,
                                        noaa21YesterdayColor = gibsColors.noaa21YesterdayColor,
                                        sentinel3aTodayColor = gibsColors.sentinel3aTodayColor,
                                        sentinel3aYesterdayColor = gibsColors.sentinel3aYesterdayColor,
                                        sentinel3bTodayColor = gibsColors.sentinel3bTodayColor,
                                        sentinel3bYesterdayColor = gibsColors.sentinel3bYesterdayColor,
                                        dataDate = gibsColors.dataDate,
                                        paceObservationTime = gibsColors.paceObservationTime,
                                        noaa20ObservationTime = gibsColors.noaa20ObservationTime,
                                        noaa21ObservationTime = gibsColors.noaa21ObservationTime
                                    ),
                                    fetchedAt = java.time.Instant.now()
                                )
                            )
                            com.shaka.data.cache.SpotDataCache.saveToDatabase(spotId)
                        } catch (e: Exception) { /* continue */ }
                    }
                }
                
                call.respondText(
                    """{"status":"started","spotsToFetch":$total,"message":"GIBS fetch started in background."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Clear all tide data (to force refetch after code changes)
            post("/admin/tide/clear") {
                val cleared = com.shaka.data.cache.SpotDataCache.clearAllTides()
                call.respondText(
                    """{"status":"ok","cleared":$cleared}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Trigger tide fetch for all spots without tide data
            post("/admin/tide/refetch") {
                val spotsToFetch = com.shaka.data.cache.SpotDataCache.getSpotsWithoutTide()
                val total = spotsToFetch.size
                
                val tidesClient = com.shaka.data.client.TideClient.create()
                val today = java.time.LocalDate.now().toString()
                
                kotlinx.coroutines.GlobalScope.launch {
                    var success = 0
                    var failed = 0
                    for (spotId in spotsToFetch) {
                        try {
                            val spot = SpotDatabase.findSpotById(spotId) ?: continue
                            val tideData = tidesClient.getTideData(
                                spot.coordinates.lat,
                                spot.coordinates.lon,
                                today
                            )
                            com.shaka.data.cache.SpotDataCache.updateTide(
                                spotId,
                                com.shaka.data.cache.SpotDataCache.CachedValue(
                                    value = com.shaka.data.cache.SpotDataCache.TideInfo(
                                        state = tideData.tideState,
                                        nextHighTide = tideData.nextHighTide,
                                        nextLowTide = tideData.nextLowTide,
                                        currentHeight = tideData.currentHeight,
                                        stationId = null,
                                        nextHighTideTime = tideData.nextHighTideTime?.let { java.time.Instant.ofEpochMilli(it) },
                                        nextLowTideTime = tideData.nextLowTideTime?.let { java.time.Instant.ofEpochMilli(it) }
                                    ),
                                    fetchedAt = java.time.Instant.now()
                                )
                            )
                            com.shaka.data.cache.SpotDataCache.saveToDatabase(spotId)
                            success++
                        } catch (e: Exception) { 
                            failed++
                        }
                    }
                    org.slf4j.LoggerFactory.getLogger("TideRefetch").info("Tide refetch complete: $success success, $failed failed")
                }
                
                call.respondText(
                    """{"status":"started","spotsToFetch":$total,"message":"Tide fetch started in background."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Upfront full-year tide backfill: materialize a year of tide data
            // for every catalog + user spot (serial, concurrency=1). Run once;
            // the daily/hourly jobs then read from spot_tide_days instead of
            // re-hitting the FES2022 service every day.
            post("/admin/tide/backfill-year") {
                val prefetchJobs = application.attributes.getOrNull(PrefetchJobsKey)
                if (prefetchJobs == null) {
                    call.respondText(
                        """{"status":"error","message":"PrefetchJobs not initialized yet"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                    return@post
                }
                val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 400) ?: 365
                GlobalScope.launch {
                    try {
                        prefetchJobs.backfillTideYears(days)
                    } catch (e: Exception) {
                        org.slf4j.LoggerFactory.getLogger("AdminTideYearBackfill")
                            .error("Tide year backfill failed: ${e.message}", e)
                    }
                }
                call.respondText(
                    """{"status":"started","days":$days,"message":"Full-year tide backfill started in background (serial). This runs for a long time; watch logs for 'TIDE YEAR backfill complete'."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }

            // Materialize a full year for ONLY the remaining spots (horizon
            // missing or already expired). Unbounded serial pass; safe to
            // re-run (idempotent, skips spots already current).
            post("/admin/tide/topup-remaining") {
                val prefetchJobs = application.attributes.getOrNull(PrefetchJobsKey)
                if (prefetchJobs == null) {
                    call.respondText(
                        """{"status":"error","message":"PrefetchJobs not initialized yet"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                    return@post
                }
                GlobalScope.launch {
                    try {
                        prefetchJobs.backfillRemainingTideYears()
                    } catch (e: Exception) {
                        org.slf4j.LoggerFactory.getLogger("AdminTideTopupRemaining")
                            .error("Remaining tide backfill failed: ${e.message}", e)
                    }
                }
                call.respondText(
                    """{"status":"started","message":"Remaining-spots tide backfill started in background (serial). Watch logs for 'TIDE remaining-backfill'."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }

            // Force-run the ocean forecast tile pipeline (ECMWF + CMEMS → WebP)
            post("/admin/weather/tiles/trigger") {
                kotlinx.coroutines.GlobalScope.launch {
                    com.shaka.service.WeatherTileService.forcePipeline()
                }
                call.respondText(
                    """{"status":"started","message":"Weather tile pipeline triggered in background"}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }

            // Clear all weather/swell data (to force refetch after code changes)
            post("/admin/weather/clear") {
                val cleared = com.shaka.data.cache.SpotDataCache.clearAllWeather()
                call.respondText(
                    """{"status":"ok","cleared":$cleared}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }

            // Trigger full V2 weather prefetch pipeline (all stale + missing spots)
            post("/admin/weather/trigger") {
                val prefetchJobs = application.attributes.getOrNull(PrefetchJobsKey)
                if (prefetchJobs == null) {
                    call.respondText(
                        """{"status":"error","message":"PrefetchJobs not initialized yet"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                    return@post
                }
                GlobalScope.launch {
                    try {
                        prefetchJobs.prefetchHourlySwellWind()
                    } catch (e: Exception) {
                        org.slf4j.LoggerFactory.getLogger("AdminWeatherTrigger")
                            .error("Manual hourly swell/wind prefetch failed: ${e.message}", e)
                    }
                }
                call.respondText(
                    """{"status":"started","message":"Hourly swell/wind prefetch triggered in background. Check /admin/cache/stats for progress."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }

            // Trigger the satellite prefetch (SST + Copernicus water quality +
            // GIBS colors) for all stale/missing spots — same run the 6-hourly
            // scheduler fires. Rejects with a clear response when a run is
            // already in flight (the run is rate-limited and takes ~80+ min).
            post("/admin/satellite/trigger") {
                val prefetchJobs = application.attributes.getOrNull(PrefetchJobsKey)
                if (prefetchJobs == null) {
                    call.respondText(
                        """{"status":"error","message":"PrefetchJobs not initialized yet"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                    return@post
                }
                if (prefetchJobs.isSatellitePrefetchRunning()) {
                    call.respondText(
                        """{"status":"already_running","message":"A satellite prefetch run is already in progress; not starting another."}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.Conflict
                    )
                    return@post
                }
                GlobalScope.launch {
                    try {
                        prefetchJobs.prefetchSatelliteData()
                    } catch (e: Exception) {
                        org.slf4j.LoggerFactory.getLogger("AdminSatelliteTrigger")
                            .error("Manual satellite prefetch failed: ${e.message}", e)
                    }
                }
                call.respondText(
                    """{"status":"started","message":"Satellite prefetch triggered in background (rate-limited, ~80+ min for a full fleet pass). Check /v1/health/jobs satellite_* for results."}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.Accepted
                )
            }

            // Trigger a full hourly swell/wind refetch for all spots. Uses the
            // same daily job that drives the in-memory series + derived snapshot,
            // replacing the old single-snapshot hour-index refetch.
            post("/admin/weather/refetch") {
                val prefetchJobs = application.attributes.getOrNull(PrefetchJobsKey)
                if (prefetchJobs == null) {
                    call.respondText(
                        """{"status":"error","message":"PrefetchJobs not initialized yet"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                    return@post
                }
                kotlinx.coroutines.GlobalScope.launch {
                    try {
                        prefetchJobs.prefetchHourlySwellWind()
                    } catch (e: Exception) {
                        org.slf4j.LoggerFactory.getLogger("WeatherRefetch")
                            .error("Hourly swell/wind refetch failed: ${e.message}", e)
                    }
                }
                call.respondText(
                    """{"status":"started","message":"Hourly swell/wind refetch started in background."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Get MPA cache stats
            get("/admin/mpa/stats") {
                val withMPA = com.shaka.data.cache.SpotDataCache.getAllSpotIds().count { spotId ->
                    com.shaka.data.cache.SpotDataCache.get(spotId)?.mpa != null
                }
                val withoutMPA = com.shaka.data.cache.SpotDataCache.getSpotsWithoutMPA().size
                val total = com.shaka.data.cache.SpotDataCache.size()
                
                call.respondText(
                    """{"total":$total,"withMPA":$withMPA,"withoutMPA":$withoutMPA}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Trigger MPA fetch for all spots without MPA data
            post("/admin/mpa/refetch") {
                val spotsToFetch = com.shaka.data.cache.SpotDataCache.getSpotsWithoutMPA()
                val total = spotsToFetch.size
                
                val protectedSeasClient = com.shaka.data.client.ProtectedSeasClient()
                
                kotlinx.coroutines.GlobalScope.launch {
                    for (spotId in spotsToFetch) {
                        try {
                            val spot = SpotDatabase.findSpotById(spotId) ?: continue
                            
                            // Step 1: Check EXACT location (is spot INSIDE an MPA?)
                            val exactResult = protectedSeasClient.getMPAStatusExact(
                                spot.coordinates.lat, 
                                spot.coordinates.lon
                            )
                            
                            // Step 2: If not inside, check with buffer (is spot NEARBY an MPA?)
                            val bufferResult = if (exactResult == null) {
                                protectedSeasClient.getMPAStatus(spot.coordinates.lat, spot.coordinates.lon)
                            } else null
                            
                            val isInside = exactResult != null
                            val mpaInfo = exactResult ?: bufferResult
                            
                            val cacheInfo = mpaInfo?.let {
                                com.shaka.data.cache.SpotDataCache.MPACacheInfo(
                                    siteName = it.siteName,
                                    designation = it.designation,
                                    spearfishingStatus = it.spearfishingStatus,
                                    protectionLevel = it.protectionLevel,
                                    speciesOfConcern = it.speciesOfConcern,
                                    purpose = it.purpose,
                                    detailsUrl = it.detailsUrl,
                                    isInsideMPA = isInside
                                )
                            }
                            
                            com.shaka.data.cache.SpotDataCache.updateMPA(
                                spotId,
                                com.shaka.data.cache.SpotDataCache.CachedValue(cacheInfo, java.time.Instant.now())
                            )
                            com.shaka.data.cache.SpotDataCache.saveToDatabase(spotId)
                            
                            // Small delay to be nice to the API
                            kotlinx.coroutines.delay(300)
                        } catch (e: Exception) { /* continue */ }
                    }
                }
                
                call.respondText(
                    """{"status":"started","spotsToFetch":$total,"message":"MPA fetch started in background."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Test MPA fetch for a single spot (bypasses cache, uses exact-first logic)
            get("/admin/mpa/test/{spotId}") {
                val spotId = call.parameters["spotId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "spotId required")
                )
                val spot = SpotDatabase.findSpotById(spotId) ?: return@get call.respond(
                    HttpStatusCode.NotFound, mapOf("error" to "Spot not found")
                )
                
                val protectedSeasClient = com.shaka.data.client.ProtectedSeasClient()
                
                // Step 1: Check EXACT location (is spot INSIDE an MPA?)
                val exactResult = protectedSeasClient.getMPAStatusExact(spot.coordinates.lat, spot.coordinates.lon)
                
                // Step 2: If not inside, check with buffer (is spot NEARBY an MPA?)
                val bufferResult = if (exactResult == null) {
                    protectedSeasClient.getMPAStatus(spot.coordinates.lat, spot.coordinates.lon)
                } else null
                
                val isInside = exactResult != null
                val mpaInfo = exactResult ?: bufferResult
                
                call.respondText(
                    """{"spotId":"$spotId","lat":${spot.coordinates.lat},"lon":${spot.coordinates.lon},"isInsideMPA":$isInside,"mpa":${if (mpaInfo != null) """{"siteName":"${mpaInfo.siteName}","designation":"${mpaInfo.designation}","spearfishingStatus":${mpaInfo.spearfishingStatus},"protectionLevel":${mpaInfo.protectionLevel}}""" else "null"}}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // Clear all MPA data and re-fetch everything (use after query changes)
            post("/admin/mpa/refetch-all") {
                val allSpots = com.shaka.data.cache.SpotDataCache.getAllSpotIds()
                val total = allSpots.size
                
                val protectedSeasClient = com.shaka.data.client.ProtectedSeasClient()
                
                kotlinx.coroutines.GlobalScope.launch {
                    var processed = 0
                    for (spotId in allSpots) {
                        try {
                            val spot = SpotDatabase.findSpotById(spotId) ?: continue
                            
                            // Step 1: Check EXACT location (is spot INSIDE an MPA?)
                            val exactResult = protectedSeasClient.getMPAStatusExact(
                                spot.coordinates.lat, 
                                spot.coordinates.lon
                            )
                            
                            // Step 2: If not inside, check with buffer (is spot NEARBY an MPA?)
                            val bufferResult = if (exactResult == null) {
                                protectedSeasClient.getMPAStatus(spot.coordinates.lat, spot.coordinates.lon)
                            } else null
                            
                            val isInside = exactResult != null
                            val mpaInfo = exactResult ?: bufferResult
                            
                            val cacheInfo = mpaInfo?.let {
                                com.shaka.data.cache.SpotDataCache.MPACacheInfo(
                                    siteName = it.siteName,
                                    designation = it.designation,
                                    spearfishingStatus = it.spearfishingStatus,
                                    protectionLevel = it.protectionLevel,
                                    speciesOfConcern = it.speciesOfConcern,
                                    purpose = it.purpose,
                                    detailsUrl = it.detailsUrl,
                                    isInsideMPA = isInside
                                )
                            }
                            
                            com.shaka.data.cache.SpotDataCache.updateMPA(
                                spotId,
                                com.shaka.data.cache.SpotDataCache.CachedValue(cacheInfo, java.time.Instant.now())
                            )
                            com.shaka.data.cache.SpotDataCache.saveToDatabase(spotId)
                            processed++
                            
                            // Log progress every 50 spots
                            if (processed % 50 == 0) {
                                println("MPA refetch progress: $processed / $total (isInside=${if (isInside) "YES" else "no"})")
                            }
                            
                            // Small delay to be nice to the API
                            kotlinx.coroutines.delay(300)
                        } catch (e: Exception) { 
                            println("MPA refetch error for $spotId: ${e.message}")
                        }
                    }
                    println("MPA refetch complete: $processed / $total spots updated")
                }
                
                call.respondText(
                    """{"status":"started","spotsToFetch":$total,"message":"Full MPA refetch started in background."}""",
                    io.ktor.http.ContentType.Application.Json
                )
            }
            
            // ============================================
            // USER SPOTS ENDPOINTS
            // ============================================
            
            /**
             * Create a new user spot.
             * Requires X-Device-ID header.
             * Validates coordinates and enforces 100 spot limit per device.
             */
            post("/user-spots") {
                val deviceId = call.request.header("X-Device-ID")
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "X-Device-ID header required")
                    )
                
                val request = try {
                    call.receive<CreateUserSpotRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body: ${e.message}")
                    )
                }
                
                // Validate name
                if (request.name.isBlank() || request.name.length > 100) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Name must be 1-100 characters")
                    )
                }
                
                // Validate coordinates
                if (request.latitude < -90 || request.latitude > 90) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Latitude must be between -90 and 90")
                    )
                }
                if (request.longitude < -180 || request.longitude > 180) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Longitude must be between -180 and 180")
                    )
                }
                
                // Land/water check — block spots on land
                val isWater = landWaterClient.isWater(request.latitude, request.longitude)
                if (isWater == false) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "That location is on land. Move the pin into the water to save a spot.")
                    )
                }
                // If isWater == null the classifier is unavailable — allow creation (log only)
                if (isWater == null) {
                    org.slf4j.LoggerFactory.getLogger("SpotRoutes")
                        .warn("Land/water check unavailable for user spot at (${request.latitude}, ${request.longitude}) — allowing creation")
                }
                
                // Check limit
                val currentCount = userSpotRepository.countByDevice(deviceId)
                if (currentCount >= 100) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf(
                            "error" to "Maximum 100 spots per device",
                            "currentCount" to currentCount
                        )
                    )
                }
                
                // Infer region/country from coordinates
                val (region, country) = UserSpotRepository.inferRegionAndCountry(
                    request.latitude, request.longitude
                )
                
                // Create the spot
                val created = userSpotRepository.create(
                    deviceId = deviceId,
                    name = request.name,
                    latitude = request.latitude,
                    longitude = request.longitude,
                    region = region,
                    country = country
                )
                
                if (created == null) {
                    return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create spot")
                    )
                }
                
                // Trigger background prefetch for the new spot, with retry +
                // backoff (plan 17): one best-effort shot used to leave spots
                // created during a partial outage empty until the next daily
                // job. Also fetches the 7-day hourly swell/wind series so the
                // new spot is self-sufficient immediately.
                val cacheId = userSpotRepository.getCacheId(created.id.toString())
                val prefetchJobs = application.attributes.getOrNull(PrefetchJobsKey)
                val prefetchLogger = org.slf4j.LoggerFactory.getLogger("UserSpotPrefetch")
                GlobalScope.launch {
                    val lat = created.coordinates.lat
                    val lon = created.coordinates.lon
                    var snapshotDone = false
                    var hourlyDone = false
                    for (attempt in 1..3) {
                        if (!snapshotDone) {
                            try {
                                spotService.prefetchSingleSpot(spotId = cacheId, lat = lat, lon = lon)
                                // Consider the snapshot pass done when the key
                                // conditions arrived; otherwise retry it whole.
                                val cached = SpotDataCache.get(cacheId)
                                snapshotDone = cached?.swell != null && cached.wind != null
                            } catch (e: Exception) {
                                prefetchLogger.warn("Prefetch attempt $attempt failed for user spot ${created.id}: ${e.message}")
                            }
                        }
                        if (!hourlyDone) {
                            try {
                                prefetchJobs?.prefetchHourlySeriesForSpot(cacheId, lat, lon, created.name)
                                hourlyDone = true
                            } catch (e: Exception) {
                                prefetchLogger.warn("Hourly series attempt $attempt failed for user spot ${created.id}: ${e.message}")
                            }
                        }
                        if (snapshotDone && hourlyDone) break
                        if (attempt < 3) delay(15_000L * attempt)
                    }
                    if (!snapshotDone || !hourlyDone) {
                        prefetchLogger.error(
                            "User spot ${created.id} prefetch incomplete after 3 attempts " +
                                "(snapshot=$snapshotDone, hourlySeries=$hourlyDone); scheduled jobs will fill the gaps"
                        )
                    }
                }
                
                val response = UserSpotResponse(
                    id = created.id.toString(),
                    name = created.name,
                    coordinates = created.coordinates,
                    region = created.region,
                    country = created.country,
                    createdAt = created.createdAt.toString(),
                    isUserSpot = true,
                    shakaScore = null  // No cached data yet for new spot
                )
                
                call.respond(HttpStatusCode.Created, response)
            }
            
            /**
             * List all user spots for the device.
             * Requires X-Device-ID header.
             */
            get("/user-spots") {
                val deviceId = call.request.header("X-Device-ID")
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "X-Device-ID header required")
                    )
                call.respondWithDeadline {
                val spots = userSpotRepository.findByDeviceId(deviceId)
                val response = UserSpotListResponse(
                    spots = spots.map { spot ->
                        // Calculate score from cached data (fast, no API calls)
                        val cacheId = userSpotRepository.getCacheId(spot.id.toString())
                        val score = spotService.getUserSpotScore(cacheId)
                        // Get cached condition data for display (same pattern as /spots/all)
                        val cached = SpotDataCache.get(cacheId)
                        
                        UserSpotResponse(
                            id = spot.id.toString(),
                            name = spot.name,
                            coordinates = spot.coordinates,
                            region = spot.region,
                            country = spot.country,
                            createdAt = spot.createdAt.toString(),
                            isUserSpot = true,
                            shakaScore = score,
                            visibility = spotService.resolveVisibilityLabel(cached),
                            swell = cached?.swell?.value?.let { s ->
                                val ht = s.correctedHeightFt ?: s.heightFt
                                "${ht.roundToInt()}ft @ ${s.periodSec.roundToInt()}s ${s.direction}"
                            },
                            wind = cached?.wind?.value?.let { 
                                com.shaka.data.cache.SpotDataCache.formatWindLabel(it.speedKnots, it.direction) 
                            },
                            waterTemp = cached?.sst?.value?.let { sst ->
                                "${sst.toInt()}°C / ${((sst * 9/5) + 32).toInt()}°F"
                            },
                            waterTempC = cached?.sst?.value,
                            swellHeightFt = cached?.swell?.value?.let { (it.correctedHeightFt ?: it.heightFt).roundToInt().toDouble() },
                            windSpeedKts = cached?.wind?.value?.speedKnots
                        )
                    },
                    count = spots.size,
                    limit = 100
                )
                
                call.respond(response)
                }
            }
            
            /**
             * Search user spots by name.
             * Requires X-Device-ID header.
             */
            get("/user-spots/search") {
                val deviceId = call.request.header("X-Device-ID")
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "X-Device-ID header required")
                    )
                
                val query = call.parameters["q"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Query parameter 'q' required")
                    )
                
                val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
                
                val spots = userSpotRepository.searchByName(deviceId, query, limit)
                val response = UserSpotListResponse(
                    spots = spots.map { spot ->
                        // Calculate score from cached data (fast, no API calls)
                        val cacheId = userSpotRepository.getCacheId(spot.id.toString())
                        val score = spotService.getUserSpotScore(cacheId)
                        
                        UserSpotResponse(
                            id = spot.id.toString(),
                            name = spot.name,
                            coordinates = spot.coordinates,
                            region = spot.region,
                            country = spot.country,
                            createdAt = spot.createdAt.toString(),
                            isUserSpot = true,
                            shakaScore = score  // null if no cached data
                        )
                    },
                    count = spots.size,
                    limit = limit
                )
                
                call.respond(response)
            }
            
            /**
             * Get user spot detail with full conditions.
             * Requires X-Device-ID header.
             */
            get("/user-spots/{id}") {
                val deviceId = call.request.header("X-Device-ID")
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "X-Device-ID header required")
                    )
                
                val spotId = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Spot ID required")
                    )
                
                val date = call.parameters["date"]?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString()
                
                // Find the user spot
                val userSpot = userSpotRepository.findByIdAndDevice(spotId, deviceId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Spot not found")
                    )
                
                // Get detailed conditions using the service
                call.respondWithDeadline {
                    val cacheId = userSpotRepository.getCacheId(spotId)
                    val spotDetail = spotService.getUserSpotDetail(userSpot, cacheId, date)

                    val response = UserSpotDetailResponse(
                        spot = spotDetail,
                        isUserSpot = true
                    )

                    call.respond(response)
                }
            }
            
            /**
             * Delete a user spot.
             * Requires X-Device-ID header.
             * Also removes the spot from cache.
             */
            delete("/user-spots/{id}") {
                val deviceId = call.request.header("X-Device-ID")
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "X-Device-ID header required")
                    )
                
                val spotId = call.parameters["id"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Spot ID required")
                    )
                
                // Delete from database
                val deleted = userSpotRepository.delete(spotId, deviceId)
                
                if (!deleted) {
                    return@delete call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Spot not found or already deleted")
                    )
                }
                
                // Remove from cache AND delete all dependent DB rows
                // (spot_cache, spot_exposure, tide + hourly tables) — leaving
                // them behind orphaned them forever (Q14).
                val cacheId = userSpotRepository.getCacheId(spotId)
                SpotDataCache.deleteAllForSpot(cacheId)
                
                call.respond(mapOf("status" to "ok", "deleted" to spotId))
            }
            
            // ============================================
            // FISHING INTEL ENDPOINTS (ISOLATED)
            // ============================================
            
            /**
             * Get fishing intel for a spot.
             * Returns highlights, species summary, and bait status.
             */
            get("/spots/{spotId}/intel") intelHandler@{
                val spotId = call.parameters["spotId"]
                    ?: return@intelHandler call.respond(HttpStatusCode.BadRequest, mapOf("error" to "spotId required"))
                val since = call.parameters["since"] ?: "72h"
                val tzOffset = call.parameters["tzOffset"]?.toIntOrNull()

                call.respondWithDeadline {
                    val intel = FishingIntelRoutes.getSpotIntel(spotId, since, tzOffset)
                    if (intel != null) {
                        call.respond(intel)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf(
                            "error" to "No fishing intel available for this spot",
                            "spotId" to spotId
                        ))
                    }
                }
            }
            
            /**
             * Get raw evidence cards for a spot (optionally filtered by species).
             */
            get("/spots/{spotId}/intel/evidence") {
                val spotId = call.parameters["spotId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "spotId required"))
                val species = call.parameters["species"]
                
                val evidence = FishingIntelRoutes.getSpotEvidence(spotId, species)
                call.respond(evidence)
            }
            
            /**
             * Get fishing intel for a region (by sources.regional_report). No geo.
             * e.g. GET /regions/socal/intel?since=72h&tzOffset=-8
             */
            get("/regions/{regionId}/intel") {
                val regionId = call.parameters["regionId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "regionId required"))
                val since = call.parameters["since"] ?: "72h"
                val tzOffset = call.parameters["tzOffset"]?.toIntOrNull()

                call.respondWithDeadline {
                    val intel = FishingIntelRoutes.getIntelForRegion(regionId, since, tzOffset)
                    if (intel != null) {
                        call.respond(intel)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf(
                            "error" to "No fishing intel available for this region",
                            "regionId" to regionId
                        ))
                    }
                }
            }

            /**
             * Get trending species for SoCal (based on report frequency).
             */
            get("/regions/socal/trending") {
                val hours = call.parameters["hours"]?.toIntOrNull() ?: 72
                val trending = FishingIntelRoutes.getTrending(hours)
                call.respond(trending)
            }
            
            /**
             * Fishing intel health check (admin endpoint).
             */
            get("/admin/fishing-intel/health") {
                val health = FishingIntelRoutes.getHealth()
                call.respond(health)
            }
            
            /**
             * Toggle source enabled/disabled (admin endpoint).
             */
            post("/admin/fishing-intel/sources/{sourceId}/toggle") {
                val sourceId = call.parameters["sourceId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sourceId required"))
                val enabled = call.parameters["enabled"]?.toBoolean() ?: true
                
                val result = FishingIntelRoutes.toggleSource(sourceId, enabled)
                call.respond(result)
            }

            /**
             * Manually trigger fishing intel scraper (admin endpoint).
             * Bypasses the deploy guard. Use after deploy to populate data.
             */
            post("/admin/fishing-intel/scrape") {
                try {
                    com.shaka.fishing_intel.jobs.FishingIntelPrefetchJob.run(force = true)
                    call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText(
                        """{"status":"error","message":"${e.message?.replace("\"", "'") ?: "Unknown error"}"}""",
                        io.ktor.http.ContentType.Application.Json,
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }
            
            
            
            /**
             * Ingest fishing intel from local BD Outdoors scraper.
             * Accepts JSON array of forum posts with extracted fishing data.
             * 
             * POST /v1/intel/ingest
             * Body: [{"threadUrl": "...", "title": "...", ...}]
             */
            post("/intel/ingest") {
                val errors = mutableListOf<String>()
                var saved = 0
                var skipped = 0
                val clearSource = call.request.queryParameters["clearSource"]
                if (clearSource == "bd-outdoors") {
                    val deleted = FishingIntelDb.deleteReportsBySource("bd-outdoors")
                    org.slf4j.LoggerFactory.getLogger("SpotRoutes").info("Cleared bd-outdoors before ingest: deleted $deleted reports")
                }
                try {
                    val posts = call.receive<List<IngestPostRequest>>()
                    fun norm(u: String) = u.split("#").first().trimEnd('/').take(512)
                    val byThread = posts.groupBy { norm(it.threadUrl) }
                    val threadTldr = mutableMapOf<String, Pair<String?, Boolean?>>()
                    val threadSpecies = mutableMapOf<String, List<String>>()
                    for ((threadUrl, threadPosts) in byThread) {
                        val title = threadPosts.firstOrNull { it.postRole == "thread_starter" }?.title ?: threadPosts.first().title
                        val combinedContent = threadPosts.sortedBy { it.date }.map { it.content }.joinToString("\n\n").take(4000)
                        if (FishingIntelAiService.isEnabled()) {
                            val result = FishingIntelAiService.analyzeThread(title, combinedContent)
                            if (result != null) {
                                threadTldr[threadUrl] = result.second to result.third
                                threadSpecies[threadUrl] = result.first
                            } else {
                                val fallbackSpecies = threadPosts.flatMap { it.speciesCaught }.distinct()
                                val summary = "Caught ${fallbackSpecies.joinToString(", ").ifBlank { "fish" }}. ${combinedContent.take(120).trim()}".replace(Regex("\\s+"), " ").trim().take(300)
                                threadTldr[threadUrl] = summary to null
                                threadSpecies[threadUrl] = fallbackSpecies
                            }
                        } else {
                            val fallbackSpecies = threadPosts.flatMap { it.speciesCaught }.distinct()
                            val summary = "Caught ${fallbackSpecies.joinToString(", ").ifBlank { "fish" }}. ${combinedContent.take(120).trim()}".replace(Regex("\\s+"), " ").trim().take(300)
                            threadTldr[threadUrl] = summary to null
                            threadSpecies[threadUrl] = fallbackSpecies
                        }
                    }
                    for ((index, post) in posts.withIndex()) {
                        try {
                            // Parse date: accept ISO-8601 with offset (e.g. -0800). Never use now().
                            val publishedDate = try {
                                java.time.OffsetDateTime.parse(post.date).toInstant()
                            } catch (e: Exception) {
                                // OffsetDateTime may reject -0800 (no colon); try with colon in offset
                                try {
                                    val normalized = post.date.replace(Regex("([+-])(\\d{2})(\\d{2})$"), "$1$2:$3")
                                    java.time.OffsetDateTime.parse(normalized).toInstant()
                                } catch (e2: Exception) {
                                    errors.add("Post $index (${post.title.take(30)}): invalid date format")
                                    skipped++
                                    continue
                                }
                            }
                            val localDate = java.time.LocalDateTime.ofInstant(
                                publishedDate, java.time.ZoneOffset.UTC
                            ).toLocalDate()
                            
                            // Fingerprint: reply = thread+post; else narrative (thread, title, date)
                            val fingerprint = if (post.postRole == "reply" && !post.postUrl.isNullOrBlank()) {
                                Deduplicator.buildReplyFingerprint(post.threadUrl, post.postUrl)
                            } else {
                                Deduplicator.buildNarrativeFingerprint(post.threadUrl, post.title, localDate)
                            }
                            
                            if (FishingIntelDb.fingerprintExists(fingerprint)) {
                                skipped++
                                continue
                            }
                            
                            val lastActivityInstant = post.lastActivityAt?.let { s ->
                                try {
                                    java.time.OffsetDateTime.parse(s).toInstant()
                                } catch (e: Exception) {
                                    try {
                                        val norm = s.replace(Regex("([+-])(\\d{2})(\\d{2})$"), "$1$2:$3")
                                        java.time.OffsetDateTime.parse(norm).toInstant()
                                    } catch (e2: Exception) {
                                        null
                                    }
                                }
                            } ?: publishedDate
                            val reportUrl = post.postUrl?.take(512) ?: post.threadUrl
                            val threadUrl = norm(post.threadUrl)
                            val (parsedZone, cleanedTitle) = parseBdTitleForLocationTag(post.title)
                            val effectiveThreadZone = post.threadZone?.takeIf { it.isNotBlank() } ?: parsedZone
                            val effectiveTitle = cleanedTitle?.takeIf { it.isNotBlank() } ?: post.title
                            val isThreadStarter = post.postRole == "thread_starter"
                            val reportTldr = if (isThreadStarter) threadTldr[threadUrl]?.first else null
                            val reportIsCatchIntel = if (isThreadStarter) threadTldr[threadUrl]?.second else null
                            val speciesForCatch = if (isThreadStarter) (threadSpecies[threadUrl] ?: emptyList()) else post.speciesCaught
                            val report = FishingReport(
                                sourceId = "bd-outdoors",
                                url = reportUrl,
                                publishedAt = publishedDate,
                                observedAt = java.time.Instant.now(),
                                reportType = ReportType.NARRATIVE,
                                title = effectiveTitle.take(255),
                                rawExcerpt = post.content.take(500),
                                fingerprint = fingerprint,
                                confidence = 0.8,
                                threadZone = effectiveThreadZone,
                                contentType = post.contentType,
                                lastActivityAt = lastActivityInstant,
                                threadUrl = threadUrl,
                                tldr = reportTldr,
                                isCatchIntel = reportIsCatchIntel
                            )
                            
                            val reportId = FishingIntelDb.saveReport(report)
                            
                            // CATCH only from speciesCaught (or AI species_caught when AI ran)
                            for (species in speciesForCatch) {
                                val normalizedSpecies = SpeciesNormalizer.normalize(species)
                                val claim = FishingClaim(
                                    claimType = ClaimType.CATCH,
                                    species = normalizedSpecies,
                                    countKept = 1,
                                    landingName = post.locationMentioned ?: "BD Outdoors Forum"
                                )
                                FishingIntelDb.saveClaim(reportId, claim)
                            }
                            
                            val geo = GeoResolver.resolve("bd-outdoors", effectiveThreadZone, post.locationMentioned)
                                ?: ResolvedGeo(32.7157, -117.1611, 150_000, GeoType.REGION_FALLBACK)
                            FishingIntelDb.saveReportGeo(
                                reportId = reportId,
                                lat = geo.lat,
                                lon = geo.lon,
                                geoType = geo.geoType,
                                radiusM = geo.radiusM
                            )
                            
                            saved++
                        } catch (e: Exception) {
                            errors.add("Post $index (${post.title.take(30)}): ${e.message}")
                        }
                    }
                    
                    call.respond(IngestResponse(
                        status = if (errors.isEmpty()) "ok" else "partial",
                        saved = saved,
                        skipped = skipped,
                        errors = errors.take(10)
                    ))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        IngestResponse(
                            status = "error",
                            saved = 0,
                            skipped = 0,
                            errors = listOf(e.message ?: "Unknown error")
                        )
                    )
                }
            }

            // ==================== WEATHER TILES (Ocean Forecast) ====================

            get("/weather/catalog.json") {
                val catalog = WeatherTileService.getCatalog()
                call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
                call.respond(catalog)
            }

            get("/weather/{variable}/{file}") {
                val variable = call.parameters["variable"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "variable required"))
                val timestamp = call.parameters["file"]?.removeSuffix(".webp")?.removeSuffix(".png")
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "timestamp required"))

                val file = WeatherTileService.getTileFile(variable, timestamp)
                if (file != null) {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=21600")
                    call.respondFile(file)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "tile not found"))
                }
            }

            get("/weather/tiles/{filename}") {
                val filename = call.parameters["filename"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "filename required"))
                val file = java.io.File("/app/data/$filename")
                if (!file.exists()) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "file not found"))
                    return@get
                }
                val totalLen = file.length()
                call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                val rangeHeader = call.request.header(HttpHeaders.Range)
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val spec = rangeHeader.removePrefix("bytes=").split(",")[0].trim()
                    val dash = spec.indexOf('-')
                    val start = spec.substring(0, dash).toLongOrNull() ?: 0L
                    val end = if (dash + 1 < spec.length) {
                        spec.substring(dash + 1).toLongOrNull() ?: (totalLen - 1)
                    } else {
                        totalLen - 1
                    }
                    val len = end - start + 1
                    val raf = java.io.RandomAccessFile(file, "r")
                    raf.seek(start)
                    val buf = ByteArray(len.toInt())
                    raf.readFully(buf)
                    raf.close()
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalLen")
                    call.respondBytes(buf, ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
                } else {
                    call.respondFile(file)
                }
            }

            // Legal acceptance records (anonymous device id via X-Device-ID)
            legalAcceptanceRoutes(legalAcceptanceRepository)
        }

        // Public legal pages at root level (outside /v1): /legal, /legal/privacy, /legal/terms
        legalRoutes()
    }
}
