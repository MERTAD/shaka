package com.shaka.monitoring

import kotlinx.serialization.Serializable

/**
 * Pure severity-aggregation logic behind GET /v1/health/summary.
 * No I/O in this file — everything here is unit-testable (HealthSummaryTest).
 * Route-side gathering lives in SpotRoutes; policy lives here.
 */

enum class Severity(val wire: String) {
    OK("ok"), DEGRADED("degraded"), CRITICAL("critical");

    companion object {
        fun worst(severities: Iterable<Severity>): Severity =
            severities.maxByOrNull { it.ordinal } ?: OK
    }
}

@Serializable
data class HealthCause(
    val check: String,
    val severity: String,
    val observed: String,
    val threshold: String,
)

@Serializable
data class HealthSummaryResponse(
    val severity: String,
    val checkedAt: String,
    val causes: List<HealthCause>,
)

object HealthSummaryLogic {

    fun aggregate(causes: List<Pair<Severity, HealthCause>>, checkedAt: String): HealthSummaryResponse {
        val overall = Severity.worst(causes.map { it.first })
        return HealthSummaryResponse(
            severity = overall.wire,
            checkedAt = checkedAt,
            causes = causes.map { it.second },
        )
    }

    // ---------- Per-check policy ----------

    /**
     * What the database is actually doing, as distinct from what was asked for.
     *
     * The four states exist because `connected == false` alone is ambiguous. It
     * is the steady state of an installation that deliberately runs in-memory,
     * and it is also what a wrong password looks like after `tryInitDatabase`
     * falls back. Reporting both as `ok` is how a deployment silently stops
     * persisting while every health check stays green.
     */
    enum class DbState(val wire: String, val severity: Severity) {
        /** No database configured. In-memory by design, nothing to ping. */
        ABSENT("absent", Severity.OK),

        /** Connected and answering SELECT 1. */
        REACHABLE("reachable", Severity.OK),

        /**
         * Configured, never connected. The app is serving reads from memory and
         * dropping every write, which is worse than being down because it looks
         * like it works.
         */
        NOT_CONNECTED("not_connected", Severity.CRITICAL),

        /** Was connected and is no longer answering. */
        UNREACHABLE("unreachable", Severity.CRITICAL)
    }

    /**
     * @param configured a database was requested, whether or not it came up
     * @param connected the pool is running
     * @param pingOk a bounded SELECT 1 succeeded; only meaningful when connected
     */
    fun dbState(configured: Boolean, connected: Boolean, pingOk: Boolean): DbState = when {
        !configured -> DbState.ABSENT
        !connected -> DbState.NOT_CONNECTED
        pingOk -> DbState.REACHABLE
        else -> DbState.UNREACHABLE
    }

    fun dbCause(state: DbState): Pair<Severity, HealthCause> {
        val expected = when (state) {
            DbState.ABSENT -> "no DATABASE_URL set; running in-memory by design"
            DbState.REACHABLE -> "must respond to SELECT 1 within 2s"
            DbState.NOT_CONNECTED ->
                "DATABASE_URL is set, so the connection must come up; this instance is " +
                    "running in-memory and is not persisting anything"
            DbState.UNREACHABLE -> "must respond to SELECT 1 within 2s"
        }
        return state.severity to HealthCause("db", state.severity.wire, state.wire, expected)
    }

    fun jobSuccessSeverity(spec: MonitoringConfig.JobSpec, successRate: Double): Severity = when {
        successRate < spec.criticalBelow -> Severity.CRITICAL
        successRate < spec.degradedBelow -> Severity.DEGRADED
        else -> Severity.OK
    }

    /**
     * Job cause combining latest-run success rate and missed-run detection.
     *
     * @param lastRunAgeMs null when the job has never reported since boot
     *        (in-memory) AND has no persisted row.
     * @param uptimeMs process uptime, for deploy grace.
     */
    fun jobCause(
        spec: MonitoringConfig.JobSpec,
        successRate: Double?,
        succeeded: Int?,
        total: Int?,
        lastRunAgeMs: Long?,
        uptimeMs: Long,
        schedulersDisabled: Boolean,
    ): Pair<Severity, HealthCause> {
        val check = "job:${spec.name}"
        if (schedulersDisabled) {
            return Severity.OK to HealthCause(check, Severity.OK.wire, "schedulers disabled", "n/a (DISABLE_SCHEDULED_JOBS)")
        }

        val degradedAfter = MonitoringConfig.missedRunDegradedAfterMs(spec)
        val criticalAfter = MonitoringConfig.missedRunCriticalAfterMs(spec)
        val inDeployGrace = uptimeMs < MonitoringConfig.deployGraceMs(spec)

        // Missed-run detection first: a stale success is not a success.
        if (lastRunAgeMs == null) {
            return if (inDeployGrace) {
                Severity.OK to HealthCause(check, Severity.OK.wire, "no run yet (deploy grace)", "grace=${MonitoringConfig.deployGraceMs(spec)}ms")
            } else {
                Severity.CRITICAL to HealthCause(check, Severity.CRITICAL.wire, "never reported since deploy, past grace", "grace=${MonitoringConfig.deployGraceMs(spec)}ms")
            }
        }
        if (lastRunAgeMs > criticalAfter && !inDeployGrace) {
            return Severity.CRITICAL to HealthCause(
                check, Severity.CRITICAL.wire,
                "lastRunAge=${lastRunAgeMs / 60000}min", "critical>${criticalAfter / 60000}min (interval+maxRun x2)",
            )
        }
        if (lastRunAgeMs > degradedAfter && !inDeployGrace) {
            return Severity.DEGRADED to HealthCause(
                check, Severity.DEGRADED.wire,
                "lastRunAge=${lastRunAgeMs / 60000}min", "degraded>${degradedAfter / 60000}min (interval+maxRun+1h)",
            )
        }

        val rate = successRate ?: 1.0
        val sev = jobSuccessSeverity(spec, rate)
        return sev to HealthCause(
            check, sev.wire,
            "successRate=${"%.4f".format(rate)} (${succeeded ?: "-"}/${total ?: "-"})",
            "degradedBelow=${spec.degradedBelow}, criticalBelow=${spec.criticalBelow}",
        )
    }

    /** Age-based freshness: warn-tier only, evaluated against the median age. */
    fun freshnessCause(type: String, medianMinAgo: Long?): Pair<Severity, HealthCause>? {
        val threshold = MonitoringConfig.freshnessThresholdMinutes(type) ?: return null
        if (medianMinAgo == null) return null  // no data yet -> covered by job causes
        val sev = if (medianMinAgo > threshold) Severity.DEGRADED else Severity.OK
        return sev to HealthCause(
            "freshness:$type", sev.wire,
            "medianAge=${medianMinAgo}min", "stale>${threshold}min (gate+interval+maxRun+1h)",
        )
    }

    /** Tide horizon: from spot_tide_series.generated_through, never fetchedAt age. */
    fun tideHorizonCause(medianRemainingDays: Long?, coverage: Double?): Pair<Severity, HealthCause> {
        val threshold = "degraded<${MonitoringConfig.TIDE_HORIZON_DEGRADED_DAYS}d, critical<${MonitoringConfig.TIDE_HORIZON_CRITICAL_DAYS}d, coverage>=${MonitoringConfig.TIDE_COVERAGE_MIN}"
        if (medianRemainingDays == null || coverage == null) {
            return Severity.DEGRADED to HealthCause("tide_horizon", Severity.DEGRADED.wire, "no tide series data", threshold)
        }
        val sev = when {
            medianRemainingDays < MonitoringConfig.TIDE_HORIZON_CRITICAL_DAYS -> Severity.CRITICAL
            medianRemainingDays < MonitoringConfig.TIDE_HORIZON_DEGRADED_DAYS -> Severity.DEGRADED
            coverage < MonitoringConfig.TIDE_COVERAGE_MIN -> Severity.DEGRADED
            else -> Severity.OK
        }
        return sev to HealthCause(
            "tide_horizon", sev.wire,
            "medianRemainingDays=$medianRemainingDays, coverage=${"%.3f".format(coverage)}", threshold,
        )
    }

    /**
     * AI region insights: degraded when today's slot is still empty past the
     * grace window. "No reports scraped in the past week" counts as ok. Never
     * critical — the app hides the card gracefully.
     */
    fun aiInsightsCause(
        hasInsightsForToday: Boolean,
        hoursIntoLocalDay: Int,
        hadReportsLastWeek: Boolean,
        aiEnabled: Boolean,
    ): Pair<Severity, HealthCause> {
        val threshold = "degraded when today's slot empty after ${MonitoringConfig.AI_INSIGHTS_SLOT_GRACE_HOURS}h local"
        val sev = when {
            !aiEnabled -> Severity.OK  // deliberately disabled is not an incident
            hasInsightsForToday -> Severity.OK
            !hadReportsLastWeek -> Severity.OK
            hoursIntoLocalDay >= MonitoringConfig.AI_INSIGHTS_SLOT_GRACE_HOURS -> Severity.DEGRADED
            else -> Severity.OK
        }
        val observed = when {
            !aiEnabled -> "AI disabled"
            hasInsightsForToday -> "today's slot populated"
            !hadReportsLastWeek -> "no reports last week (nothing to summarize)"
            else -> "today's slot empty at ${hoursIntoLocalDay}h local"
        }
        return sev to HealthCause("ai_region_insights", sev.wire, observed, threshold)
    }

    /** Upstreams the backend already degrades around are never critical here. */
    fun upstreamCause(name: String, status: String): Pair<Severity, HealthCause> {
        val sev = if (status == "ok") Severity.OK else Severity.DEGRADED
        return sev to HealthCause("upstream:$name", sev.wire, status, "capped at degraded (backend has fallbacks)")
    }
}
