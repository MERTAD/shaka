package com.shaka.pfz

import com.shaka.monitoring.ItemFailure
import com.shaka.monitoring.MonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Daily persistence of ranked PFZ zone snapshots, and the retention sweep.
 *
 * ## What it does
 *
 * Each run re-scores the requests the API has actually been asked for — the
 * distinct (anchor, species, mode, size class) tuples in the table — and replaces
 * that day's rows for each one. It does not invent a grid of boxes: a job that
 * scored locations nobody queried would be spending Copernicus quota on answers
 * no user can reach, and would leave rows in the history that no request will
 * ever match.
 *
 * ## Why "replace the day" rather than upsert
 *
 * A zone that ranked first yesterday and is absent today has to disappear from
 * today's row set. An upsert cannot express "this cell no longer exists", so the
 * day's rows for the exact request key are cleared first. The unique key then
 * makes the write idempotent if the run is retried.
 *
 * ## Quota
 *
 * Each request is a full [PfzService.evaluateZones] call, which is a Copernicus
 * subset run. The tracked set is capped at [PfzZoneStore.MAX_TRACKED_REQUESTS]
 * and ordered by most-recently-persisted first, so a busy species is not starved
 * and the daily ceiling is a known number rather than an emergent one.
 */
class PfzZonePersistJob(
    private val service: PfzService = PfzService(gridSource = PfzGridService()),
    private val store: PfzZoneStore = PfzZoneStore
) {
    private val logger = LoggerFactory.getLogger(PfzZonePersistJob::class.java)

    /** Re-score and persist every tracked request for [date]. */
    suspend fun persistDaily(date: LocalDate = LocalDate.now()): PersistSummary = withContext(Dispatchers.IO) {
        val started = Instant.now()
        val requests = store.trackedRequests(PfzZoneStore.MAX_TRACKED_REQUESTS)
        if (requests.isEmpty()) {
            MonitoringService.reportRun("pfz_zones_daily", 0, 0, emptyList(), 0)
            return@withContext PersistSummary(0, 0, 0, emptyList(), started)
        }

        val failures = mutableListOf<ItemFailure>()
        var attempted = 0
        var written = 0
        var empty = 0

        for (request in requests) {
            attempted++
            val requestKey = "${request.speciesId}@${request.anchorLat},${request.anchorLon}"
            val result = try {
                service.evaluateZones(
                    request.anchorLat, request.anchorLon, date.toString(), request.speciesId,
                    mode = request.mode, sizeClass = request.sizeClass
                )
            } catch (e: Exception) {
                MonitoringService.captureItemFailure("pfz_zones_daily", requestKey, requestKey, e)
                failures.add(
                    ItemFailure(
                        requestKey, requestKey, e.message ?: "unknown", MonitoringService.classifyError(e)
                    )
                )
                null
            }

            when (result) {
                null -> Unit
                is PfzService.ZonesResult.Ok -> {
                    if (result.response.zones.isEmpty()) {
                        // Not a failure. The corridor did not resolve for this box
                        // today, and the honest record is an absent day rather than
                        // a fabricated placeholder row.
                        //
                        // The day's rows are still cleared: this run is the current
                        // answer for the date, so zones that have stopped resolving
                        // must not survive as though today still had them. What
                        // replaces them is nothing, which the history layer already
                        // reports as a gap.
                        store.deleteDay(
                            result.response.date,
                            result.response.lat,
                            result.response.lon,
                            result.response.speciesId,
                            request.mode,
                            request.sizeClass
                        )
                        empty++
                    } else {
                        val rows = store.replaceDay(result.response, request.mode, request.sizeClass)
                        written += rows
                    }
                }
                is PfzService.ZonesResult.UnknownSpecies -> failures.add(
                    ItemFailure(
                        requestKey, request.speciesId,
                        "species is no longer in the roster; its persisted history is now unreachable",
                        "config"
                    )
                )
                is PfzService.ZonesResult.BadDate -> failures.add(
                    ItemFailure(requestKey, request.speciesId, result.message, "data")
                )
                is PfzService.ZonesResult.BadLocation -> failures.add(
                    ItemFailure(requestKey, requestKey, result.message, "data")
                )
            }
        }

        MonitoringService.reportRun(
            "pfz_zones_daily", attempted, attempted - failures.size, failures,
            Duration.between(started, Instant.now()).toMillis()
        )
        logger.info(
            "PFZ daily persist {}: {} requests, {} zone rows, {} unresolved, {} failed",
            date, attempted, written, empty, failures.size
        )
        PersistSummary(attempted, written, empty, failures, started)
    }

    /**
     * Drop history beyond the retention window.
     *
     * Separate from [persistDaily] and registry-exempt: it is a cheap delete with
     * no reportRun, matching the tide/swell cleanup jobs.
     */
    fun pruneOldHistory() {
        val cutoff = LocalDate.now().minusDays(PfzZoneStore.RETENTION_DAYS)
        val deleted = store.deleteOlderThan(cutoff)
        if (deleted > 0) {
            logger.info("Pruned {} PFZ zone history rows older than {}", deleted, cutoff)
        }
    }

    data class PersistSummary(
        val requests: Int,
        val rowsWritten: Int,
        val requestsUnresolved: Int,
        val failures: List<ItemFailure>,
        val startedAt: Instant
    )
}
