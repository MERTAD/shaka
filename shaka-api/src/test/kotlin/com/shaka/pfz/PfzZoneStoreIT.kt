package com.shaka.pfz

import com.shaka.data.db.DatabaseFactory
import org.junit.Assume.assumeTrue
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persistence tests that need a real database.
 *
 * These are opt-in: they run only when `DATABASE_URL` points at a live Postgres
 * (`postgresql://user:pass@host:port/db`, the same value the API itself takes),
 * and are skipped otherwise. Run them with:
 *
 * ```
 * $env:DATABASE_URL = "postgresql://shaka:shaka@localhost:5432/shaka"
 * ./gradlew :test --tests "*PfzZoneStoreIT"
 * ```
 *
 * ## Why these exist separately
 *
 * The pure tests in [PfzZoneStoreTest] build [PfzZoneStore.Row] values by hand and
 * never execute the INSERT. That is the right call for the interpretation logic
 * — gaps, cell identity, key composition — but it left the one bug that actually
 * shipped untested: the statement bound 26 values into 25 placeholders, so every
 * column from `anchor_lat` onward received the previous column's value. Nothing
 * in the pure suite could see that, because nothing in it touched SQL.
 *
 * The load-bearing assertion here is therefore [a row round-trips into the right
 * columns]: a shifted bind shows up as a latitude sitting in `anchor_lon`, not
 * as an exception.
 */
class PfzZoneStoreIT {

    private val url: String? = System.getenv("DATABASE_URL")

    /** Distinct, unlikely-to-collide anchor so this never disturbs real rows. */
    private val anchorLat = 12.3456
    private val anchorLon = -45.6789
    private val today = LocalDate.now()

    private fun connected() = url != null && connectIfPossible()

    private fun connectIfPossible(): Boolean = try {
        DatabaseFactory.init(url!!, "", "")
        DatabaseFactory.isConnected()
    } catch (e: Throwable) {
        println("Skipping PfzZoneStoreIT: cannot reach $url (${e.message})")
        false
    }

    @BeforeTest
    fun setUp() {
        assumeTrue("no live database at DATABASE_URL; skipping", connected())
        PfzZoneStore.createTablesIfNotExists()
    }

    @AfterTest
    fun tearDown() {
        if (url != null) runCatching { cleanup() }
    }

    private fun cleanup() {
        DatabaseFactoryTestSupport.connection(url!!).use { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate(
                    "DELETE FROM pfz_zones_daily WHERE anchor_lat = $anchorLat AND anchor_lon = $anchorLon"
                )
            }
        }
    }

    private fun zone(
        rank: Int = 1,
        lat: Double = 12.3456,
        lon: Double = -45.6789,
        cellLat: Double = lat,
        cellLon: Double = lon,
        name: String = "IT zone $rank",
        status: PfzStatus = PfzStatus.SCOREABLE,
        pfz: Int? = 77,
        confidence: Int = 55,
        frontKm: Double? = 3.5,
        region: String = "test_basin",
    ) = PfzZone(
        rank = rank,
        name = name,
        lat = lat,
        lon = lon,
        cellLat = cellLat,
        cellLon = cellLon,
        status = status,
        pfz = pfz,
        confidence = confidence,
        frontKm = frontKm,
        region = region,
        polygon = emptyList(),
        holes = emptyList(),
        drivers = listOf("sst"),
        blockers = emptyList(),
        missingFactors = listOf("bottom_salinity"),
        lowConfidenceFactors = emptyList(),
        mode = "feeding",
        sizeClass = "large",
    )

    private fun response(
        zones: List<PfzZone>,
        date: String = today.toString(),
    ) = PfzZonesResponse(
        speciesId = "bluefin_tuna",
        speciesName = "Atlantic Bluefin Tuna",
        speciesScientificName = "Thunnus thynnus",
        date = date,
        lat = anchorLat,
        lon = anchorLon,
        confidence = 55,
        zones = zones,
        missingFactors = listOf("bottom_salinity"),
        coverageNotes = emptyList(),
    )

    @Test
    fun `a row round-trips into the right columns`() {
        PfzZoneStore.saveZoneResponse(response(listOf(zone())), "feeding", "large")

        val row = PfzZoneStore
            .loadHistory(anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today)
            .single()

        // Every one of these is a distinct value on purpose. A statement that
        // binds 36.4 into anchor_lon instead of anchor_lat passes a naive test
        // and fails this one.
        assertEquals(today.toString(), row.localDate)
        assertEquals(anchorLat, row.anchorLat, "anchor_lat received the wrong value")
        assertEquals(anchorLon, row.anchorLon, "anchor_lon received the wrong value")
        assertEquals("bluefin_tuna", row.speciesId)
        assertEquals("feeding", row.mode)
        assertEquals("large", row.sizeClass)
        assertEquals("IT zone 1", row.zoneName)
        assertEquals(1, row.rank)
        assertEquals(PfzZoneStore.SCOREABLE, row.status)
        assertEquals(77, row.pfz)
        assertEquals(55, row.confidence)
        assertEquals(3.5, row.frontKm)
        assertEquals("test_basin", row.region)
    }

    @Test
    fun `a null score survives as null, not zero`() {
        PfzZoneStore.saveZoneResponse(
            response(listOf(zone(status = PfzStatus.INSUFFICIENT_DATA, pfz = null, frontKm = null))),
            "feeding",
            "large"
        )

        val row = PfzZoneStore
            .loadHistory(anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today)
            .single()

        assertNull(row.pfz, "an unscored zone must not come back as 0")
        assertEquals(PfzZoneStore.INSUFFICIENT_DATA, row.status)
        assertNull(row.frontKm)
    }

    @Test
    fun `the cell coordinates survive distinct from the anchor`() {
        // The whole point of the cell column: the box the angler asked about and
        // the patch inside it are different places.
        PfzZoneStore.saveZoneResponse(
            response(listOf(zone(lat = 12.9999, lon = -45.1111))),
            "feeding",
            "large"
        )

        val row = PfzZoneStore
            .loadHistory(anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today)
            .single()

        assertEquals(anchorLat, row.anchorLat)
        assertEquals(anchorLon, row.anchorLon)
        assertEquals(12.9999, row.cellLat, "the cell latitude did not round-trip")
        assertEquals(-45.1111, row.cellLon, "the cell longitude did not round-trip")
    }

    @Test
    fun `a drifting centroid does not split one grid cell into two series`() {
        // The reason cell_lat/cell_lon exist separately from the zone's own
        // lat/lon. The centroid is averaged over the patch, so a front nudging
        // one edge of the patch moves it a few hundredths of a degree while the
        // zone is still the same grid cell. Keying the trend on the centroid
        // would report that as one cell disappearing and another appearing, and
        // the cell would show no change at all.
        val cellLat = 12.5000
        val cellLon = -45.4000
        PfzZoneStore.saveZoneResponse(
            response(
                listOf(zone(lat = 12.5100, lon = -45.4000, cellLat = cellLat, cellLon = cellLon, pfz = 60)),
                date = today.minusDays(1).toString()
            ),
            "feeding",
            "large"
        )
        PfzZoneStore.saveZoneResponse(
            response(
                listOf(zone(lat = 12.5240, lon = -45.4130, cellLat = cellLat, cellLon = cellLon, pfz = 75)),
                date = today.toString()
            ),
            "feeding",
            "large"
        )

        val rows = PfzZoneStore.loadHistory(
            anchorLat,
            anchorLon,
            "bluefin_tuna",
            "feeding",
            "large",
            today.minusDays(6),
            today
        )
        val trend = PfzZoneStore.trend(rows, anchorLat, anchorLon, "bluefin_tuna", "feeding", "large")

        assertEquals(
            1,
            trend.series.size,
            "a centroid that moved 0.014 degrees was treated as a different cell"
        )
        val series = trend.series.single()
        assertEquals(cellLat, series.cellLat, 1e-9)
        assertEquals(15, series.change, "the cell's own change did not survive the centroid drift")
    }

    @Test
    fun `saving the same cell twice updates rather than duplicates`() {
        PfzZoneStore.saveZoneResponse(response(listOf(zone(pfz = 40))), "feeding", "large")
        PfzZoneStore.saveZoneResponse(response(listOf(zone(pfz = 88))), "feeding", "large")

        val rows = PfzZoneStore.loadHistory(
            anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today
        )
        assertEquals(1, rows.size, "the upsert created a duplicate row")
        assertEquals(88, rows.single().pfz)
    }

    @Test
    fun `replaceDay drops a cell that is no longer in the zone set`() {
        PfzZoneStore.saveZoneResponse(
            response(listOf(zone(rank = 1, lat = 12.1), zone(rank = 2, lat = 12.2))),
            "feeding",
            "large"
        )
        assertEquals(
            2,
            PfzZoneStore.loadHistory(anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today).size
        )

        // The re-score finds only one zone today. The other must not survive.
        PfzZoneStore.replaceDay(response(listOf(zone(rank = 1, lat = 12.1))), "feeding", "large")

        val rows = PfzZoneStore.loadHistory(
            anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today
        )
        assertEquals(1, rows.size, "a zone that dropped out of the set was left behind")
        assertEquals(12.1, rows.single().cellLat)
    }

    @Test
    fun `deleteDay clears the day without touching another mode`() {
        PfzZoneStore.saveZoneResponse(response(listOf(zone())), "feeding", "large")
        PfzZoneStore.saveZoneResponse(response(listOf(zone(pfz = 20))), "spawning", "large")

        PfzZoneStore.deleteDay(today.toString(), anchorLat, anchorLon, "bluefin_tuna", "feeding", "large")

        assertTrue(
            PfzZoneStore.loadHistory(anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today).isEmpty(),
            "the feeding rows survived a delete"
        )
        assertEquals(
            1,
            PfzZoneStore.loadHistory(anchorLat, anchorLon, "bluefin_tuna", "spawning", "large", today, today).size,
            "deleting one model context wiped another"
        )
    }

    @Test
    fun `a day with only unscored zones has no top score`() {
        PfzZoneStore.saveZoneResponse(
            response(listOf(zone(status = PfzStatus.UNAVAILABLE, pfz = null))),
            "feeding",
            "large"
        )

        val trend = PfzZoneStore.trend(
            PfzZoneStore.loadHistory(anchorLat, anchorLon, "bluefin_tuna", "feeding", "large", today, today),
            anchorLat, anchorLon, "bluefin_tuna", "feeding", "large"
        )

        assertEquals(1, trend.days.size)
        assertNull(trend.days.single().topPfz, "an unscored day reported a top score")
        assertNull(trend.latestTopPfz)
        assertNull(trend.dayOverDay, "a single day cannot have a day-over-day figure")
    }

    @Test
    fun `the persisted timestamp is set by the store`() {
        PfzZoneStore.saveZoneResponse(response(listOf(zone())), "feeding", "large")

        val conn = DatabaseFactoryTestSupport.connection(url!!)
        val persistedAt: Timestamp? = conn.use { c ->
            c.prepareStatement(
                "SELECT persisted_at FROM pfz_zones_daily WHERE anchor_lat = ? AND anchor_lon = ?"
            ).use { s ->
                s.setDouble(1, anchorLat)
                s.setDouble(2, anchorLon)
                s.executeQuery().use { rs ->
                    if (rs.next()) rs.getTimestamp(1) else null
                }
            }
        }

        assertNotNull(persistedAt, "persisted_at was not written")
        assertTrue(
            persistedAt.toInstant().isAfter(Instant.now().minusSeconds(120)),
            "persisted_at is not roughly now: $persistedAt"
        )
    }
}

/** Minimal direct connection, for the one assertion that must bypass the store. */
object DatabaseFactoryTestSupport {
    /**
     * Opens a JDBC connection from the same `postgresql://user:pass@host:port/db`
     * form [com.shaka.data.db.DatabaseFactory] accepts.
     */
    fun connection(url: String): java.sql.Connection {
        val dsn = url.removePrefix("postgresql://").removePrefix("jdbc:postgresql://")
        val creds = dsn.substringBeforeLast('@')
        val hostPart = dsn.substringAfterLast('@')
        val user = creds.substringBefore(':')
        val password = creds.substringAfter(':', "")
        val host = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':').substringBefore('/').ifEmpty { "5432" }
        val database = hostPart.substringAfterLast('/')
        return java.sql.DriverManager.getConnection(
            "jdbc:postgresql://$host:$port/$database", user, password
        )
    }
}
