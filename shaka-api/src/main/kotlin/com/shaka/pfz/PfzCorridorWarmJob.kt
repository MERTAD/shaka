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
 * Keeps the primary Algerian-coast corridor warm in the Copernicus grid cache.
 *
 * ## Why this set, and not a grid
 *
 * The daily persist job ([PfzZonePersistJob]) only re-scores requests users
 * have actually made, so the *first* request for a coast point still has to
 * pay for a cold download - and on a slow network that first scan is exactly
 * the one that dies at the analysis deadline. The user's primary fishing area
 * is the Algerian coast, so this job pre-fetches only the anchor boxes that
 * actually cover it: a handful of ports spaced along the coast. Nothing
 * speculative is fetched, so the Copernicus quota stays proportional to what a
 * user of that coast could actually reach.
 *
 * ## What it fetches
 *
 * Only the front fields (SST/SSTA/CHL), which are what segment zones. If
 * enrichment is missing the scores come out weaker but the zones exist, which
 * is the honest floor this job exists to guarantee.
 *
 * ## Cost
 *
 * Each anchor is one batched subset run (three front products close to each
 * other are fetched concurrently by the client). The job runs twice a day and
 * the cache TTL is 36h, so most runs are cache reads rather than downloads.
 */
class PfzCorridorWarmJob(
    private val service: PfzGridService = PfzGridService()
) {
    private val logger = LoggerFactory.getLogger(PfzCorridorWarmJob::class.java)

    /** An anchor box and the label the run log uses for it. */
    data class Anchor(val lat: Double, val lon: Double, val label: String)

    companion object {
        /** Anchors spaced along the Algerian coast. */
        val CORRIDOR_ANCHORS = listOf(
            Anchor(35.70, -0.60, "Oran"),
            Anchor(36.10, 0.10, "Mostaganem"),
            Anchor(36.75, 3.06, "Algiers"),
            Anchor(36.75, 5.08, "Bejaia"),
            Anchor(36.82, 5.77, "Jijel"),
            Anchor(36.88, 6.90, "Skikda"),
            Anchor(36.90, 7.77, "Annaba")
        )
    }

    /** Warm the front fields for every corridor anchor for [date]. */
    suspend fun warm(date: LocalDate = LocalDate.now()): WarmSummary = withContext(Dispatchers.IO) {
        val started = Instant.now()
        val failures = mutableListOf<ItemFailure>()
        var resolved = 0

        for (anchor in CORRIDOR_ANCHORS) {
            val itemKey = "${anchor.label}@${anchor.lat},${anchor.lon}"
            try {
                val grids = service.warmFrontFields(anchor.lat, anchor.lon, date)
                if (grids.isEmpty()) {
                    logger.warn("PFZ corridor warm: $itemKey $date resolved no front fields")
                } else {
                    resolved++
                    logger.info(
                        "PFZ corridor warm: $itemKey $date = ${grids.size} front field(s)"
                    )
                }
            } catch (e: Exception) {
                MonitoringService.captureItemFailure("pfz_corridor_warm", itemKey, itemKey, e)
                failures.add(
                    ItemFailure(itemKey, itemKey, e.message ?: "unknown", MonitoringService.classifyError(e))
                )
            }
        }

        MonitoringService.reportRun(
            "pfz_corridor_warm", CORRIDOR_ANCHORS.size, resolved, failures,
            Duration.between(started, Instant.now()).toMillis()
        )
        logger.info(
            "PFZ corridor warm {}: {} anchors, {} resolved, {} failed",
            date, CORRIDOR_ANCHORS.size, resolved, failures.size
        )
        WarmSummary(CORRIDOR_ANCHORS.size, resolved, failures, started)
    }

    data class WarmSummary(
        val anchors: Int,
        val resolved: Int,
        val failures: List<ItemFailure>,
        val startedAt: Instant
    )
}