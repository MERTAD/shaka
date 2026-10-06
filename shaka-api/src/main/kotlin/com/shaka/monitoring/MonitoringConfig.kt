package com.shaka.monitoring

/**
 * Single source of truth for job schedules, breach thresholds, and data
 * freshness expectations. See docs/synthetic-monitor-design.md.
 *
 * RULES (enforced by MonitoringRegistryTest + .cursor/rules/monitoring.mdc):
 *  - Every job scheduled via Application.scheduleJob and every name reported
 *    to MonitoringService must have a JobSpec here or be listed in
 *    registryExempt.
 *  - Application.kt reads intervals/watchdogs FROM this registry so config
 *    and reality cannot diverge.
 *  - /health/freshness and /health/summary derive their thresholds from this
 *    object; nothing else in the codebase may hardcode a staleness threshold.
 */
object MonitoringConfig {

    data class JobSpec(
        /** Name reported to MonitoringService (NOT necessarily the scheduled name). */
        val name: String,
        /** Name passed to scheduleJob; null for jobs reported but scheduled elsewhere. */
        val scheduledName: String?,
        val initialDelayMs: Long,
        val intervalMs: Long,
        /** Per-iteration watchdog; jobs report only AFTER completion. */
        val maxRunMs: Long,
        /** Eligibility gate: data younger than this is skipped by the job (hours). */
        val staleGateHours: Long = 0,
        /** Success rate below this -> "degraded". */
        val degradedBelow: Double,
        /** Success rate below this -> "critical". */
        val criticalBelow: Double,
    )

    private const val HOUR = 3_600_000L

    val jobs: List<JobSpec> = listOf(
        // 6-hourly model refetch (was 24 h — the cause of "current" wind being
        // up to 24 h stale). Open-Meteo free-tier quota math, metered per
        // location: ~850 spots x 2 endpoints (marine + forecast) x 4 runs/day
        // = ~6,800 calls/day (cap 10,000/day) and ~204,000/month (cap
        // 300,000/month). An hourly refetch would be ~40,800/day = 4x over
        // cap; 6-hourly is the densest cadence that fits with headroom.
        // Requests are batched 50 locations per HTTP call (DataPrefetchJobs.
        // WEATHER_BATCH_LOCATIONS) which does not change quota weight.
        // The hourly in-memory snapshot tick (deriveHourlySnapshots) still
        // advances the displayed "now" between refetches.
        JobSpec(
            name = "hourly_swell_wind", scheduledName = "hourly_swell_wind_prefetch",
            initialDelayMs = 120_000, intervalMs = 6 * HOUR, maxRunMs = 2 * HOUR,
            staleGateHours = 0, degradedBelow = 0.99, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "satellite_prefetch", scheduledName = "satellite_prefetch",
            initialDelayMs = 180_000, intervalMs = 6 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        // Per-source sub-reports of satellite_prefetch (scheduledName = null:
        // reported by the same run, not scheduled separately). The aggregate
        // job used to count a spot successful if ANY source returned, hiding
        // e.g. a 100% SST failure behind healthy GIBS fetches.
        JobSpec(
            name = "satellite_sst", scheduledName = null,
            initialDelayMs = 180_000, intervalMs = 6 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "satellite_copernicus", scheduledName = null,
            initialDelayMs = 180_000, intervalMs = 6 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "satellite_gibs", scheduledName = null,
            initialDelayMs = 180_000, intervalMs = 6 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "user_spots_prefetch", scheduledName = "user_spots_prefetch",
            initialDelayMs = 240_000, intervalMs = 3 * HOUR, maxRunMs = 24 * HOUR,
            // Gate matches prefetchUserSpots: skips spots with SST fresher than SATELLITE_STALE_HOURS.
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        // Per-source sub-reports of user_spots_prefetch (same run, Q9).
        JobSpec(
            name = "user_spots_sst", scheduledName = null,
            initialDelayMs = 240_000, intervalMs = 3 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "user_spots_copernicus", scheduledName = null,
            initialDelayMs = 240_000, intervalMs = 3 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "user_spots_gibs", scheduledName = null,
            initialDelayMs = 240_000, intervalMs = 3 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "user_spots_mpa", scheduledName = null,
            initialDelayMs = 240_000, intervalMs = 3 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
        JobSpec(
            name = "user_spots_solunar", scheduledName = null,
            initialDelayMs = 240_000, intervalMs = 3 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 12, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
        JobSpec(
            name = "fishing_intel_prefetch", scheduledName = "solunar_vessel_prefetch",
            initialDelayMs = 300_000, intervalMs = 12 * HOUR, maxRunMs = 48 * HOUR,
            staleGateHours = 12, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
        // Per-source sub-report of fishing_intel_prefetch (same run, Q9).
        // (fishing_intel_vessel removed with vessel deprecation, Q7.)
        JobSpec(
            name = "fishing_intel_solunar", scheduledName = null,
            initialDelayMs = 300_000, intervalMs = 12 * HOUR, maxRunMs = 48 * HOUR,
            staleGateHours = 12, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
        JobSpec(
            name = "buoy_readings", scheduledName = "buoy_readings",
            initialDelayMs = 360_000, intervalMs = 1 * HOUR, maxRunMs = 4 * HOUR,
            staleGateHours = 0, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
        JobSpec(
            name = "tide_horizon_topup", scheduledName = "tide_horizon_topup",
            initialDelayMs = 420_000, intervalMs = 720 * HOUR, maxRunMs = 6_000_000,
            staleGateHours = 0, degradedBelow = 0.95, criticalBelow = 0.50,
        ),
        JobSpec(
            name = "mpa_prefetch", scheduledName = "mpa_prefetch",
            initialDelayMs = 900_000, intervalMs = 168 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 168, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
        JobSpec(
            name = "weather_tile_pipeline", scheduledName = "weather_tile_pipeline",
            initialDelayMs = 30_000, intervalMs = 6 * HOUR, maxRunMs = 24 * HOUR,
            staleGateHours = 0, degradedBelow = 1.00, criticalBelow = 0.99,
        ),
        JobSpec(
            name = "fishing_intel_scrape", scheduledName = "fishing_intel_scrape",
            initialDelayMs = 300_000, intervalMs = 2 * HOUR, maxRunMs = 8 * HOUR,
            staleGateHours = 0, degradedBelow = 0.80, criticalBelow = 0.30,
        ),
        // Weekly anti-join cleanup of orphaned user-% rows (Q14 safety net).
        // Reports rows deleted; deletes never partially fail per-item, so the
        // thresholds only matter for the missed-run detection.
        JobSpec(
            name = "user_spot_orphan_sweep", scheduledName = "user_spot_orphan_sweep",
            initialDelayMs = 1_200_000, intervalMs = 168 * HOUR, maxRunMs = 1 * HOUR,
            staleGateHours = 0, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
        // Daily PFZ zone history. Re-scores the (anchor, species, mode,
        // size_class) requests the API has actually been asked for and replaces
        // that day's rows for each, so /pfz/zones/history can report a real
        // day-over-day trend instead of recomputing yesterday's ocean.
        //
        // DAILY, not 6-hourly, deliberately: the thing being persisted is a
        // habitat verdict for a calendar day, and a 0.1 degree Copernicus SST
        // cell does not change meaningfully six times a day. The tracked set is
        // capped at PfzZoneStore.MAX_TRACKED_REQUESTS and ordered
        // most-recently-persisted first, so the quota ceiling is a known number.
        //
        // degradedBelow 0.90 rather than 0.99: a single unavailable box is a
        // legitimate outcome (the corridor did not resolve there today) and must
        // not alert as a degraded job. criticalBelow 0.30 still pages, because
        // losing 70% of the tracked set means the corridor or the DB is broken.
        JobSpec(
            name = "pfz_zones_daily", scheduledName = "pfz_zones_daily",
            initialDelayMs = 600_000, intervalMs = 24 * HOUR, maxRunMs = 4 * HOUR,
            staleGateHours = 0, degradedBelow = 0.90, criticalBelow = 0.30,
        ),
    )

    /**
     * Registry boundary (documented allowlist for MonitoringRegistryTest):
     *  - Scheduled but intentionally unmonitored (no reportRun, cheap/in-memory
     *    or cleanup work): hourly_snapshot_tick, tide_chart_cleanup,
     *    hourly_series_cleanup, http_pool_watchdog.
     *  - Report to MonitoringService but only from on-demand/admin paths, not
     *    the scheduler: tide_chart_materialize, tide_chart_catchup,
     *    tide_year_backfill, tide_remaining_backfill.
     */
    val registryExempt: Set<String> = setOf(
        "hourly_snapshot_tick", "tide_chart_cleanup", "hourly_series_cleanup",
        "http_pool_watchdog", "pfz_zone_history_cleanup",
        "tide_chart_materialize", "tide_chart_catchup", "tide_year_backfill",
        "tide_remaining_backfill",
    )

    fun jobByName(name: String): JobSpec? = jobs.find { it.name == name }
    fun jobByScheduledName(scheduledName: String): JobSpec =
        jobs.find { it.scheduledName == scheduledName }
            ?: error("No JobSpec for scheduled job '$scheduledName' — add it to MonitoringConfig.jobs")

    // ---------- Freshness (age-based data types) ----------

    /** Data type -> owning job (reporting name). Tide is deliberately absent: never age-based. */
    private val freshnessOwners: Map<String, String> = mapOf(
        "swell" to "hourly_swell_wind",
        "wind" to "hourly_swell_wind",
        "sst" to "satellite_prefetch",
        "visibility" to "satellite_prefetch",
        "chlorophyll" to "satellite_prefetch",
        "gibs_satellite" to "satellite_prefetch",
        "solunar" to "fishing_intel_prefetch",
        "mpa" to "mpa_prefetch",
    )

    /**
     * Freshness threshold = lawful maximum age, NOT cadence + margin:
     * staleGate + interval (wait for next eligible run) + maxRun (jobs report
     * only on completion) + 1h margin. Evaluated against the /health/freshness
     * MEDIAN age. Deliberately conservative: freshness is a warn-tier signal;
     * the tight user-facing bound is the probe's recency anchors.
     *
     * Exception: solunar's fishing_intel_prefetch gate is SOLUNAR_STALE_HOURS
     * (12h), matching DataPrefetchJobs.
     */
    fun freshnessThresholdMinutes(type: String): Long? {
        val owner = freshnessOwners[type] ?: return null
        val spec = jobByName(owner) ?: return null
        val gateHours = when (type) {
            "solunar" -> 12L
            else -> spec.staleGateHours
        }
        val ms = gateHours * HOUR + spec.intervalMs + spec.maxRunMs + HOUR
        return ms / 60_000
    }

    /** True if this data type participates in age-based staleness at all. */
    fun isAgeBasedType(type: String): Boolean = freshnessOwners.containsKey(type)

    // ---------- Tide (horizon-based, never age-based) ----------
    // spot_cache tide fetchedAt is months old by design (full-year precompute);
    // only spot_tide_series.generated_through is meaningful.
    const val TIDE_HORIZON_DEGRADED_DAYS = 30L  // median remaining horizon
    const val TIDE_HORIZON_CRITICAL_DAYS = 7L
    const val TIDE_COVERAGE_MIN = 0.95          // fraction of spots with a ready series

    // ---------- AI region insights (Groq) ----------
    // Generation is attempted after every fishing_intel_scrape (2h). Degraded
    // when today's local-date slot in fishing_intel_region_insights is still
    // empty after this many hours into the day (>= 4 failed attempts). Never
    // critical — the app hides the card gracefully.
    const val AI_INSIGHTS_SLOT_GRACE_HOURS = 8

    // ---------- Missed-run detection ----------
    // Jobs report only after completing, so a legal lastRun age can reach
    // interval + maxRun. Degraded past that + 1h; critical past 2x.
    fun missedRunDegradedAfterMs(spec: JobSpec): Long = spec.intervalMs + spec.maxRunMs + HOUR
    fun missedRunCriticalAfterMs(spec: JobSpec): Long = 2 * (spec.intervalMs + spec.maxRunMs)

    /**
     * Deploy grace: suppress "missed run" until the job has had time to complete once.
     *
     * Must stay in step with `scheduleJob`, which now fires the first run after
     * `initialDelayMs` alone. It used to add a whole extra `intervalMs` for any
     * job that did not opt into `runImmediately`, so `satellite_prefetch` (3 min
     * stated delay, 6h interval) first ran 6h03m after boot and never ran at all
     * on a short-lived instance - leaving `/health/jobs` pinned to a stale
     * critical while the data it described never refreshed.
     */
    fun deployGraceMs(spec: JobSpec): Long = spec.initialDelayMs + spec.maxRunMs

    /** Env flag: skip all scheduler registrations (CI contract test / local mode). */
    fun schedulersDisabled(): Boolean =
        System.getenv("DISABLE_SCHEDULED_JOBS")?.equals("true", ignoreCase = true) == true
}
