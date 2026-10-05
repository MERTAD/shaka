package com.shaka

import com.shaka.api.routes.configureRouting
import com.shaka.data.cache.SpotDataCache
import com.shaka.data.client.*
import com.shaka.data.db.DatabaseFactory
import com.shaka.service.DataPrefetchJobs
import com.shaka.service.WeatherTileService
import com.shaka.fishing_intel.db.FishingIntelDb
import com.shaka.fishing_intel.jobs.FishingIntelPrefetchJob
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import io.sentry.Sentry
import org.slf4j.LoggerFactory

val PrefetchJobsKey = AttributeKey<DataPrefetchJobs>("PrefetchJobs")

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val sentryDsn = System.getenv("SENTRY_DSN")
    if (!sentryDsn.isNullOrBlank()) {
        Sentry.init { options ->
            options.dsn = sentryDsn
            options.environment = System.getenv("RAILWAY_ENVIRONMENT") ?: "production"
            options.sampleRate = 1.0
            options.tracesSampleRate = 0.0
        }
        println("Sentry initialized (env=${System.getenv("RAILWAY_ENVIRONMENT") ?: "production"})")
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    println("Starting Shaka API on port $port")
    
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    println("Initializing Shaka API module...")

    com.shaka.config.EnvValidation.validateAndReport()

    // Try database connection (non-blocking - app works without it)
    tryInitDatabase()
    
    // Ensure spot_cache table exists
    SpotDataCache.createTableIfNotExists()
    
    // Load cached data from database (survives restarts!)
    val cachedSpots = SpotDataCache.loadFromDatabase()
    if (cachedSpots > 0) {
        logger.info("Restored $cachedSpots spots from database cache")
    }
    
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    install(Compression) {
        gzip {
            priority = 1.0
            matchContentType(ContentType.Application.Json, ContentType.Text.Any)
        }
        deflate {
            priority = 0.9
            matchContentType(ContentType.Application.Json, ContentType.Text.Any)
        }
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Request failed: ${cause.message}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Unknown error"))
            )
        }
    }

    configureRouting()
    
    // Start background data prefetch jobs
    configureScheduledJobs()
    
    logger.info("Shaka API initialized successfully")
}

/**
 * Configure background prefetch jobs for ocean data.
 * 
 * Schedule:
 * - Startup: Full prefetch of all data types (staggered)
 * - Hourly: Tide data (changes frequently)
 * - Every 3 hours: Weather/swell data
 * - Every 6 hours: Satellite data (SST, visibility)
 * 
 * Uses SupervisorJob to prevent failures from cancelling other jobs.
 */
private fun Application.configureScheduledJobs() {
    val ndbcClient = NDBCBuoyClient()
    val landWaterClient = LandWaterClient()
    val bathymetryClient = BathymetryClient(landWaterClient)
    val prefetchJobs = DataPrefetchJobs(
        SpotDatabase,
        TideClient.create(),
        OpenMeteoClient(),
        CopernicusClient(),
        NOAAClient(),
        ndbcBuoyClient = ndbcClient,
        bathymetryClient = bathymetryClient
    )
    attributes.put(PrefetchJobsKey, prefetchJobs)
    
    // Create isolated scope for background jobs - failures won't affect other jobs or the app
    val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Run a job on a fixed cadence with a per-iteration watchdog.
     *
     * Hardening from the Jun 2026 outage: a hung dependency froze job
     * coroutines mid-iteration for 8 days (no timeout), and anything
     * escaping the old catch(Exception) would have killed the loop.
     * Each iteration is bounded by maxRunMs and survives Throwable.
     */
    val schedulersDisabled = com.shaka.monitoring.MonitoringConfig.schedulersDisabled()
    if (schedulersDisabled) {
        logger.warn("DISABLE_SCHEDULED_JOBS=true — no background jobs will be scheduled (CI/local mode)")
    }

    fun scheduleJob(
        name: String,
        initialDelayMs: Long,
        intervalMs: Long,
        maxRunMs: Long = intervalMs * 4,
        runImmediately: Boolean = false,
        job: suspend () -> Unit
    ) {
        if (schedulersDisabled) return
        backgroundScope.launch {
            delay(initialDelayMs)
            if (!runImmediately) delay(intervalMs)
            while (true) {
                try {
                    logger.info("Running scheduled job: $name")
                    withTimeout(maxRunMs) { job() }
                } catch (e: TimeoutCancellationException) {
                    logger.error("Scheduled job $name exceeded ${maxRunMs}ms watchdog; iteration aborted")
                } catch (e: Throwable) {
                    logger.error("Scheduled job $name failed: ${e.message}", e)
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * Schedule a job whose cadence is owned by MonitoringConfig (single source
     * of truth — see docs/synthetic-monitor-design.md). The registry is looked
     * up by the scheduled name; missing registration fails fast at boot.
     */
    fun scheduleRegisteredJob(scheduledName: String, job: suspend () -> Unit) {
        val spec = com.shaka.monitoring.MonitoringConfig.jobByScheduledName(scheduledName)
        scheduleJob(
            name = scheduledName,
            initialDelayMs = spec.initialDelayMs,
            intervalMs = spec.intervalMs,
            maxRunMs = spec.maxRunMs,
            runImmediately = spec.runImmediately,
            job = job,
        )
    }
    
    // Hydrate persisted job-run results so /health/jobs survives redeploys.
    com.shaka.monitoring.MonitoringService.initPersistence()

    // Run full prefetch on startup (after a brief delay to let the app initialize)
    backgroundScope.launch {
        delay(5_000)  // Wait 5 seconds for app to fully start
        
        // CRITICAL: Load cached data from database first!
        // This restores chlorophyll/SST/visibility data from previous runs
        try {
            SpotDataCache.createTableIfNotExists()
            val loadedCount = SpotDataCache.loadFromDatabase()
            logger.info("Loaded $loadedCount spots from database cache")
            // Restore hourly swell/wind series and derive current snapshots so
            // "now" values are correct immediately after a restart (no API wait).
            SpotDataCache.loadHourlySeriesFromDatabase()
        } catch (e: Exception) {
            logger.warn("Failed to load cache from database: ${e.message}")
        }
        
        val cacheSize = SpotDataCache.size()
        if (cacheSize > 0) {
            logger.info("Cache has $cacheSize spots - prefetch will update stale data only")
        } else {
            logger.info("Cache empty - starting full data prefetch...")
        }
        
        // Seed NDBC buoy stations (idempotent — updates existing, adds new).
        // Skipped in CI/local mode: external NDBC call.
        if (!schedulersDisabled) {
            try {
                prefetchJobs.seedBuoyStations()
            } catch (e: Exception) {
                logger.warn("Buoy station seeding failed: ${e.message}")
            }
        }
        
        logger.info("Startup complete — scheduled jobs will refresh stale data on cadence")
    }
    
    // Cadences/watchdogs below are owned by MonitoringConfig (single source of
    // truth). Adding a job here without a JobSpec (or registryExempt entry)
    // fails MonitoringRegistryTest — see .cursor/rules/monitoring.mdc.

    // EVERY 6 HOURS (cadence owned by MonitoringConfig): Hourly swell + wind
    // series. Fetches the 7-day hourly curves for every spot via batched
    // multi-location Open-Meteo requests and persists one row per local_date.
    // runImmediately=true so it also backfills on first boot after a deploy.
    // The current-hour value shown to users is derived in-memory from these
    // tables by the hourly tick below; quota math lives at the JobSpec.
    scheduleRegisteredJob("hourly_swell_wind_prefetch") {
        prefetchJobs.prefetchHourlySwellWind()
    }

    // HOURLY: Re-derive the current-hour swell + wind snapshot from the
    // in-memory series. Cheap (no DB/API) — just advances "now" through the day.
    // registryExempt: intentionally unmonitored (in-memory derive, no reportRun).
    scheduleJob("hourly_snapshot_tick", initialDelayMs = 660_000, intervalMs = 3_600_000, maxRunMs = 300_000, runImmediately = true) {
        prefetchJobs.deriveHourlySnapshots()
    }

    // EVERY 5 MIN: HTTP pool-wedge watchdog (Jul 2026 outage hardening).
    // Cheap in-memory check; only fires a canary request when some host has
    // N consecutive connect failures on the shared client. registryExempt:
    // intentionally unmonitored (cheap tick, no reportRun).
    scheduleJob("http_pool_watchdog", initialDelayMs = 300_000, intervalMs = 300_000, maxRunMs = 60_000) {
        HttpClientFactory.watchdogTick()
    }

    // EVERY 6 HOURS: Satellite data refresh (rate-limited, can run very long)
    scheduleRegisteredJob("satellite_prefetch") {
        prefetchJobs.prefetchSatelliteData()
    }

    // EVERY 3 HOURS: User spots refresh (same as weather)
    scheduleRegisteredJob("user_spots_prefetch") {
        prefetchJobs.prefetchUserSpots()
    }

    // EVERY 12 HOURS: Solunar + vessel data refresh (runs 2x/day).
    // Reports to MonitoringService as fishing_intel_prefetch.
    scheduleRegisteredJob("solunar_vessel_prefetch") {
        prefetchJobs.prefetchFishingIntel()
    }

    // HOURLY: Buoy readings refresh
    scheduleRegisteredJob("buoy_readings") {
        prefetchJobs.prefetchBuoyReadings()
    }

    // MONTHLY: Tide horizon top-up. Tide curves are deterministic and a full
    // year is precomputed per spot, so this only regenerates spots whose horizon
    // has neared expiry. It is the ONLY recurring job that calls FES2022 — the
    // now-state shown to users is derived on read from the persisted chart (no
    // FES), and new/user spots are generated on-demand at creation. Each run is
    // bounded by TOPUP_TIME_BUDGET_MS.
    scheduleRegisteredJob("tide_horizon_topup") {
        prefetchJobs.topUpTideHorizons()
    }

    // WEEKLY: MPA boundary refresh. Previously only reachable via the dead
    // prefetchAll() path, so MPA data silently aged for months.
    scheduleRegisteredJob("mpa_prefetch") {
        prefetchJobs.prefetchMPA()
    }

    // WEEKLY: Orphaned user-spot row sweep (anti-join vs user_spots). Safety
    // net behind the delete-time cleanup; reports rows deleted.
    scheduleRegisteredJob("user_spot_orphan_sweep") {
        prefetchJobs.sweepOrphanUserSpotRows()
    }

    // NIGHTLY: Tide chart cleanup (old rows). registryExempt: unmonitored cleanup.
    scheduleJob("tide_chart_cleanup", initialDelayMs = 600_000, intervalMs = 86_400_000, runImmediately = true) {
        prefetchJobs.cleanupOldTideDays()
    }

    // NIGHTLY: Hourly swell/wind series cleanup (old rows). registryExempt: unmonitored cleanup.
    scheduleJob("hourly_series_cleanup", initialDelayMs = 660_000, intervalMs = 86_400_000, runImmediately = true) {
        prefetchJobs.cleanupOldHourly()
    }

    // ==================== PFZ DAILY ZONE HISTORY ====================
    // Persists one ranked-zone snapshot per day for every (anchor, species, mode,
    // size class) the zones API has actually been asked for, so the app can show a
    // real day-over-day trend instead of the same afternoon recomputed. Reports to
    // MonitoringService as pfz_zones_daily.
    val pfzZoneStore = com.shaka.pfz.PfzZoneStore
    com.shaka.pfz.PfzZoneStore.createTablesIfNotExists()
    val pfzZonePersistJob = com.shaka.pfz.PfzZonePersistJob()

    scheduleRegisteredJob("pfz_zones_daily") {
        pfzZonePersistJob.persistDaily()
    }

    // NIGHTLY: PFZ zone history retention sweep. registryExempt: unmonitored
    // cleanup, like the tide/swell row prunes.
    scheduleJob("pfz_zone_history_cleanup", initialDelayMs = 900_000, intervalMs = 86_400_000, runImmediately = true) {
        pfzZonePersistJob.pruneOldHistory()
    }

    // ==================== WEATHER TILES (Ocean Forecast) ====================
    // Runs the Copernicus CMEMS pipeline every 6 hours to generate PNG tiles
    scheduleRegisteredJob("weather_tile_pipeline") {
        WeatherTileService.runPipeline()
    }

    // ==================== FISHING INTEL (ISOLATED) ====================
    // Scrapes SoCal fishing reports every 2 hours
    // Fully isolated - can be disabled without affecting other features
    
    // Initialize fishing intel database tables IMMEDIATELY at startup
    try {
        FishingIntelDb.createTablesIfNotExists()
        FishingIntelDb.seedLandings()
        FishingIntelDb.seedSources()
        FishingIntelDb.backfillBdOutdoorsGeos()
        FishingIntelDb.backfillAllMissingGeos()
        logger.info("Fishing intel tables initialized")
    } catch (e: Exception) {
        logger.error("Failed to initialize fishing intel tables: ${e.message}")
    }
    
    // Schedule scraping job (delayed start, then every 2 hours)
    scheduleRegisteredJob("fishing_intel_scrape") {
        FishingIntelPrefetchJob.run()
    }
    
    logger.info("Fishing intel job configured: every 2 hours")
    
    // Clean up when application stops
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Shutting down Shaka API...")
        
        // Cancel background jobs
        backgroundScope.cancel()
        logger.info("Background prefetch jobs stopped")
        
        // Close shared HTTP client (releases connection pool)
        HttpClientFactory.close()
        logger.info("HTTP client closed")
        
        // Log final rate limiter stats
        logger.info("Final rate limiter stats: ${RateLimiters.getAllStats()}")
    }
    
    logger.info("Background prefetch jobs configured: 3h (weather + user spots), 6h (satellite), monthly (tide horizon top-up)")
}

private fun Application.tryInitDatabase() {
    val dbUrl = System.getenv("DATABASE_URL")
    
    if (dbUrl.isNullOrBlank()) {
        println("DATABASE_URL not set - using in-memory database")
        return
    }

    // Recorded before the attempt, not after: the point is to remember that a
    // database was wanted even when connecting to it throws, which is the only
    // way /health can tell a broken database from an absent one.
    DatabaseFactory.markConfigured()

    try {
        val dbUser = System.getenv("DATABASE_USER") ?: ""
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: ""
        
        println("Attempting database connection...")
        println("DB User: $dbUser")
        println("DB URL prefix: ${dbUrl.take(30)}...")
        
        DatabaseFactory.init(dbUrl, dbUser, dbPassword)
        println("Database connection established!")
    } catch (e: Throwable) {
        // Catch Throwable to handle Errors too, not just Exceptions
        println("Database connection failed: ${e.message}")
        e.printStackTrace()
        println("Continuing with in-memory database...")
    }
}

// Triggered redeploy 2026-02-01_fix_user_spots_no_updated_at
