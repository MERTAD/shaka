package com.shaka.pfz

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the daily-zone-history store's logic.
 *
 * These run without a database on purpose. The SQL is a plain
 * insert/upsert/select against a key we define here, so it is not where the
 * risk lives. The risk is the *interpretation*: whether a day with no scoreable
 * zone is reported as a gap rather than a zero, whether a trend follows a grid
 * cell rather than a rank, and whether the model context is part of the key.
 * Those are pure functions of a list of rows and are asserted directly.
 */
class PfzZoneStoreTest {

    private val anchorLat = 38.2
    private val anchorLon = 12.4

    private fun row(
        date: String,
        lat: Double = 38.20,
        lon: Double = 12.40,
        pfz: Int? = 60,
        status: String = PfzZoneStore.SCOREABLE,
        rank: Int = 1,
        mode: String = "default",
        sizeClass: String = "default",
        species: String = "bluefin_tuna",
        confidence: Int = 70
    ) = PfzZoneStore.Row(
        localDate = date,
        anchorLat = anchorLat,
        anchorLon = anchorLon,
        speciesId = species,
        mode = mode,
        sizeClass = sizeClass,
        cellLat = lat,
        cellLon = lon,
        zoneName = "BluefinTuna 14/07 ${rank.toString().padStart(3, '0')}",
        rank = rank,
        status = status,
        pfz = pfz,
        confidence = confidence,
        frontKm = 3.2,
        depthM = 820.0,
        sstGradientCkm = 0.4,
        chlaGradient = 0.02,
        sstAnomalyC = -0.3,
        region = "western_med",
        polygonJson = "[[38.2,12.4]]",
        driversJson = """["chl"]""",
        blockersJson = "[]",
        missingFactorsJson = "[]",
        lowConfidenceJson = "[]",
        persistedAt = java.time.Instant.parse("2026-07-14T04:00:00Z")
    )

    private fun trendOf(rows: List<PfzZoneStore.Row>) =
        PfzZoneStore.trend(rows, anchorLat, anchorLon, "bluefin_tuna", null, null)

    // ---------------------------------------------------------------- grouping

    @Test
    fun `days are grouped in chronological order regardless of row order`() {
        val days = PfzZoneStore.fromRows(
            listOf(
                row("2026-07-16", rank = 1),
                row("2026-07-14", rank = 1),
                row("2026-07-15", rank = 2),
                row("2026-07-15", rank = 1)
            )
        )
        assertEquals(listOf("2026-07-14", "2026-07-15", "2026-07-16"), days.map { it.date })
        assertEquals(listOf(1, 2), days[1].zones.map { it.rank })
    }

    @Test
    fun `a day whose zones are all unscored has no top score rather than zero`() {
        val day = PfzZoneStore.fromRows(
            listOf(
                row("2026-07-14", pfz = null, status = PfzZoneStore.INSUFFICIENT_DATA, rank = 1),
                row("2026-07-14", pfz = null, status = PfzZoneStore.UNAVAILABLE, rank = 2)
            )
        ).single()

        // The distinction this whole table rests on: 0 would read as "consistently
        // bad ground" when the truth is "we could not see it".
        assertNull(day.topPfz)
        assertEquals(0, day.confidence)
    }

    @Test
    fun `a day mixes scored and unscored zones by taking the best scored one`() {
        val day = PfzZoneStore.fromRows(
            listOf(
                row("2026-07-14", pfz = 55, status = PfzZoneStore.SCOREABLE, rank = 1),
                row("2026-07-14", pfz = null, status = PfzZoneStore.INSUFFICIENT_DATA, rank = 2),
                row("2026-07-14", pfz = 71, status = PfzZoneStore.SCOREABLE, rank = 3)
            )
        ).single()
        assertEquals(71, day.topPfz)
        assertEquals(3, day.zones.size)
    }

    @Test
    fun `an empty history is an empty list, not an error`() {
        assertEquals(emptyList(), PfzZoneStore.fromRows(emptyList()))
        val trend = trendOf(emptyList())
        assertTrue(trend.days.isEmpty())
        assertNull(trend.latestTopPfz)
        assertNull(trend.dayOverDay)
    }

    // ------------------------------------------------------------------ deltas

    @Test
    fun `day over day is the change between the two most recent scored days`() {
        val rows = listOf(
            row("2026-07-13", pfz = 50),
            row("2026-07-14", pfz = 62),
            row("2026-07-15", pfz = 71)
        )
        val days = PfzZoneStore.fromRows(rows)
        assertEquals(12, days[1].deltaFrom(days[0]))
        assertEquals(9, days[2].deltaFrom(days[1]))
        // 9, not 21. Day-over-day is the two most recent scored days, so the
        // 13th is not a participant however far back it is.
        assertEquals(9, trendOf(rows).dayOverDay)
    }

    @Test
    fun `day over day is null when either side of the comparison is unscored`() {
        val days = PfzZoneStore.fromRows(
            listOf(
                row("2026-07-14", pfz = 62),
                row("2026-07-15", pfz = null, status = PfzZoneStore.INSUFFICIENT_DATA)
            )
        )
        // A "change" computed against a day we could not score would be fiction.
        assertNull(days[1].deltaFrom(days[0]))
        assertNull(trendOf(listOf(row("2026-07-14", pfz = 62), row("2026-07-15", pfz = null, status = PfzZoneStore.INSUFFICIENT_DATA))).dayOverDay)
    }

    @Test
    fun `a single scored day has no day-over-day figure at all`() {
        val trend = trendOf(listOf(row("2026-07-15", pfz = 71)))
        assertEquals(71, trend.latestTopPfz)
        assertNull(trend.dayOverDay)
    }

    @Test
    fun `the latest top score is the last scored day, not the last day`() {
        val trend = trendOf(
            listOf(
                row("2026-07-14", pfz = 80),
                row("2026-07-15", pfz = null, status = PfzZoneStore.UNAVAILABLE)
            )
        )
        // 2026-07-15 is the newest persisted day but has no score, so reporting 0
        // for it (or reporting 80 as if it were today) would both be wrong.
        assertEquals(80, trend.latestTopPfz)
        assertNull(trend.dayOverDay)
    }

    // ------------------------------------------------------------ cell series

    @Test
    fun `a trend follows a grid cell rather than a rank`() {
        // Yesterday rank 1 was one patch, today rank 1 is a different patch.
        // A rank-keyed history would call that same patch "improving by 40".
        val trend = trendOf(
            listOf(
                row("2026-07-14", lat = 38.20, lon = 12.40, pfz = 40, rank = 1),
                row("2026-07-15", lat = 38.35, lon = 12.55, pfz = 80, rank = 1)
            )
        )
        assertEquals(2, trend.series.size)

        // Neither patch has a trend: each was observed once, so there is nothing
        // to compare it against and a zero here would be a fabricated "stable".
        assertNull(trend.series[0].change)
        assertNull(trend.series[1].change)

        // The box-level figure is still meaningful, and is a different claim: the
        // best ground anywhere in this box went 40 -> 80. It is reported
        // separately from the per-cell series precisely because the two are not
        // the same measurement.
        assertEquals(80, trend.latestTopPfz)
        assertEquals(40, trend.dayOverDay)
    }

    @Test
    fun `a cell scored on consecutive days reports its own change`() {
        val trend = trendOf(
            listOf(
                row("2026-07-14", lat = 38.20, lon = 12.40, pfz = 40),
                row("2026-07-15", lat = 38.20, lon = 12.40, pfz = 52)
            )
        )
        val cell = trend.series.single()
        assertEquals(40, cell.firstPfz)
        assertEquals(52, cell.lastPfz)
        assertEquals(12, cell.change)
        assertEquals(2, cell.points.size)
    }

    @Test
    fun `a cell that only scored once has no change`() {
        val trend = trendOf(
            listOf(
                row("2026-07-14", lat = 38.20, lon = 12.40, pfz = 40),
                row("2026-07-15", lat = 38.35, lon = 12.55, pfz = 52)
            )
        )
        assertTrue(trend.series.all { it.change == null })
    }

    @Test
    fun `cell points are chronological even when rows arrive out of order`() {
        val trend = trendOf(
            listOf(
                row("2026-07-16", lat = 38.20, lon = 12.40, pfz = 60),
                row("2026-07-14", lat = 38.20, lon = 12.40, pfz = 40),
                row("2026-07-15", lat = 38.20, lon = 12.40, pfz = 50)
            )
        )
        assertEquals(listOf("2026-07-14", "2026-07-15", "2026-07-16"), trend.series.single().points.map { it.date })
    }

    // ---------------------------------------------------------------- key bits

    @Test
    fun `an absent mode is stored as the default rather than null`() {
        // The model context is part of the key. A NULL would make two different
        // contexts coexist silently, and Postgres UNIQUE treats NULLs as distinct
        // so the upsert would insert a duplicate instead of updating in place.
        assertEquals("default", PfzZoneStore.normaliseMode(null))
        assertEquals("default", PfzZoneStore.normaliseMode(""))
        assertEquals("default", PfzZoneStore.normaliseMode("   "))
        assertEquals("feeding", PfzZoneStore.normaliseMode("feeding"))
    }

    @Test
    fun `an absent size class is stored as the default rather than null`() {
        assertEquals("default", PfzZoneStore.normaliseSizeClass(null))
        assertEquals("default", PfzZoneStore.normaliseSizeClass(""))
        assertEquals("large", PfzZoneStore.normaliseSizeClass(" large "))
    }

    @Test
    fun `bluefin feeding and spawning are separate series`() {
        val rows = listOf(
            row("2026-07-14", pfz = 70, mode = "feeding"),
            row("2026-07-14", pfz = 22, mode = "spawning")
        )
        val feeding = PfzZoneStore.trend(rows, anchorLat, anchorLon, "bluefin_tuna", "feeding", null)
        val spawning = PfzZoneStore.trend(rows, anchorLat, anchorLon, "bluefin_tuna", "spawning", null)
        assertEquals(70, feeding.latestTopPfz)
        assertEquals(22, spawning.latestTopPfz)
    }

    // ------------------------------------------------------------- disclosure

    @Test
    fun `an empty history explains itself instead of looking like a failure`() {
        val notes = trendOf(emptyList()).coverageNotes.joinToString(" ").lowercase()
        assertTrue("no pfz zone history" in notes)
        assertTrue("daily job" in notes || "written by" in notes)
    }

    @Test
    fun `the trend says out loud that a gap is not a zero`() {
        val notes = trendOf(
            listOf(
                row("2026-07-14", pfz = 60),
                row("2026-07-15", pfz = null, status = PfzZoneStore.INSUFFICIENT_DATA)
            )
        ).coverageNotes.joinToString(" ").lowercase()
        assertTrue("gaps, not zeros" in notes)
    }

    @Test
    fun `the trend states that it tracks cells and not ranks`() {
        val notes = trendOf(
            listOf(
                row("2026-07-14", lat = 38.20, lon = 12.40, pfz = 40),
                row("2026-07-15", lat = 38.20, lon = 12.40, pfz = 55)
            )
        ).coverageNotes.joinToString(" ").lowercase()
        assertTrue("grid cell, not per rank" in notes)
    }

    @Test
    fun `a history with no scored day says so rather than claiming stability`() {
        val notes = trendOf(
            listOf(row("2026-07-14", pfz = null, status = PfzZoneStore.UNAVAILABLE))
        ).coverageNotes.joinToString(" ")
        assertTrue("0 of the last" in notes)
    }

    // -------------------------------------------------------------- retention

    @Test
    fun `the tracked request cap is a stated number not an emergent one`() {
        // Every persistDaily request is a Copernicus subset run, so the daily
        // ceiling has to be knowable before the run rather than discovered by it.
        assertEquals(200, PfzZoneStore.MAX_TRACKED_REQUESTS)
        assertEquals(120L, PfzZoneStore.RETENTION_DAYS)
    }

    @Test
    fun `the retention cutoff is the job's own window, not a second guess`() {
        val cutoff = LocalDate.now().minusDays(PfzZoneStore.RETENTION_DAYS)
        assertTrue(cutoff.isBefore(LocalDate.now()))
    }
}
