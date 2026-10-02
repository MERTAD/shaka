package com.shaka.api.routes

import com.shaka.data.db.DatabaseFactory
import com.shaka.pfz.PfzSpeciesRegistry
import com.shaka.pfz.PfzStatus
import com.shaka.pfz.PfzZone
import com.shaka.pfz.PfzZoneStore
import com.shaka.pfz.PfzZonesResponse
import org.junit.Assume.assumeTrue
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The history read path, exercised at the level that decides what a caller sees.
 *
 * These are route-level tests because the store tests cannot catch the class of
 * bug that mattered here. Every store test passes rows through the store using
 * the same key the store would later use, so read and write agree by
 * construction. The route is where the two meet, and where a query parameter is
 * translated into that key — the one place the pairing can drift.
 *
 * That drift is silent. The endpoint answers 200 with an empty `days` list, not
 * an error: a new location legitimately has no history, so an empty list is a
 * valid, expected response. A key mismatch therefore produces output
 * indistinguishable from "we have never seen this spot", and the app renders it
 * as "No history yet" forever.
 *
 * The specific failure these guard against: mode ids are matched exactly, and an
 * id that does not match falls back to the species' default instead of erroring.
 * So `?mode=FEEDING` is answered for `feeding`, and the row it writes is stored
 * under `feeding`. Filtering on the raw string looked for a `FEEDING` row that
 * can never exist. Confirmed against a running API before this was fixed: with a
 * row stored under `feeding`, history reported no days for `?mode=FEEDING`.
 *
 * Opt-in, like [com.shaka.pfz.PfzZoneStoreIT]: needs a live Postgres at
 * `DATABASE_URL`, because there is no store to read without one.
 */
class PfzHistoryRouteTest {

    private val url: String? = System.getenv("DATABASE_URL")

    /** Distinct, unlikely-to-collide anchor so this never disturbs real rows. */
    private val anchorLat = 12.3456
    private val anchorLon = -45.6789
    private val species = "bluefin_tuna"
    private val today = LocalDate.now()

    private fun connected() = url != null && try {
        DatabaseFactory.init(url!!, "", "")
        DatabaseFactory.isConnected()
    } catch (e: Throwable) {
        println("Skipping PfzHistoryRouteTest: cannot reach $url (${e.message})")
        false
    }

    @BeforeTest
    fun setUp() {
        assumeTrue("no live database at DATABASE_URL; skipping", connected())
        PfzZoneStore.createTablesIfNotExists()
    }

    @AfterTest
    fun tearDown() {
        if (url != null) {
            runCatching {
                deleteTestRows()
            }
        }
    }

    /** Remove every row this test could have written, across both modes. */
    private fun deleteTestRows() {
        com.shaka.pfz.DatabaseFactoryTestSupport.connection(url!!).use { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate(
                    "DELETE FROM pfz_zones_daily WHERE anchor_lat = $anchorLat AND anchor_lon = $anchorLon"
                )
            }
        }
    }

    private fun zone(
        rank: Int = 1,
        name: String = "route zone $rank",
        mode: String = "feeding",
        sizeClass: String = "large",
        pfz: Int? = 77,
    ) = PfzZone(
        rank = rank,
        name = name,
        lat = anchorLat,
        lon = anchorLon,
        cellLat = anchorLat,
        cellLon = anchorLon,
        status = PfzStatus.SCOREABLE,
        pfz = pfz,
        confidence = 55,
        frontKm = 3.5,
        region = "test_basin",
        polygon = emptyList(),
        holes = emptyList(),
        drivers = listOf("sst"),
        blockers = emptyList(),
        missingFactors = listOf("bottom_salinity"),
        lowConfidenceFactors = emptyList(),
        mode = mode,
        sizeClass = sizeClass,
    )

    private fun response(zones: List<PfzZone>) = PfzZonesResponse(
        speciesId = species,
        speciesName = "Atlantic Bluefin Tuna",
        speciesScientificName = "Thunnus thynnus",
        date = today.toString(),
        lat = anchorLat,
        lon = anchorLon,
        confidence = 55,
        zones = zones,
        missingFactors = listOf("bottom_salinity"),
        coverageNotes = emptyList(),
    )

    /** Write one scoreable zone for `mode`, exactly as the zones route would. */
    private fun seed(mode: String, pfz: Int) {
        PfzZoneStore.replaceDay(
            response(listOf(zone(mode = mode, pfz = pfz))),
            mode,
            "large"
        )
    }

    private fun read(rawMode: String?, rawSizeClass: String? = null) =
        buildPfzHistoryResponse(
            species = species,
            lat = anchorLat,
            lon = anchorLon,
            rawMode = rawMode,
            rawSizeClass = rawSizeClass,
            from = today.minusDays(6),
            to = today
        )

    @Test
    fun `an omitted mode reads the row stored under the resolved mode`() {
        seed("feeding", 64)

        val res = read(rawMode = null)

        assertEquals(1, res.days.size, "the stored feeding row was not read")
        assertEquals(64, res.latestTopPfz, "latestTopPfz should be the stored score")
    }

    @Test
    fun `a differently cased mode reads the same series as omitting it`() {
        seed("feeding", 64)

        val viaOmitted = read(rawMode = null)
        val viaWrongCase = read(rawMode = "FEEDING")

        assertEquals(
            viaOmitted.days.size,
            viaWrongCase.days.size,
            "an unrecognised mode id read a different number of days than the " +
                "default it actually resolved to"
        )
        assertEquals(
            viaOmitted.latestTopPfz,
            viaWrongCase.latestTopPfz,
            "an unrecognised mode id hid a stored score"
        )
        assertEquals(
            viaOmitted.mode,
            viaWrongCase.mode,
            "the two requests disagreed on the applied mode"
        )
    }

    @Test
    fun `a response reports the mode it actually read`() {
        seed("spawning", 41)

        val res = read(rawMode = "spawning")

        assertEquals("spawning", res.mode, "the response must name the mode it read")
        assertEquals(41, res.latestTopPfz)
    }

    @Test
    fun `one mode cannot see another mode's rows`() {
        seed("feeding", 64)

        val spawning = read(rawMode = "spawning")

        assertEquals(
            0,
            spawning.days.size,
            "a spawning request read the feeding series; a bluefin feeding score " +
                "and a spawning score are near-inverted models"
        )
        assertNull(spawning.latestTopPfz, "latestTopPfz leaked across model contexts")
    }

    @Test
    fun `an unpersisted anchor is an empty response with an explanation`() {
        seed("feeding", 64)

        // A different anchor on the same day: legitimately never persisted.
        val elsewhere = buildPfzHistoryResponse(
            species = species,
            lat = 36.1408,
            lon = -5.3536,
            rawMode = null,
            rawSizeClass = null,
            from = today.minusDays(6),
            to = today
        )

        assertEquals(0, elsewhere.days.size, "history leaked across anchors")
        assertTrue(
            elsewhere.coverageNotes.isNotEmpty(),
            "an empty history must explain itself, or the app shows 'No history yet' " +
                "with no indication that anything is wrong"
        )
    }

    @Test
    fun `the roster has a default mode that is not the literal default`() {
        // The premise of the whole read/write pairing. If every species' default
        // mode were literally "default", the raw-key bug would be invisible and
        // these tests would stop covering anything.
        val resolvedToRealMode = PfzSpeciesRegistry.all()
            .map { it to it.resolve(null, null).mode }
            .filter { (_, mode) -> mode != null && mode != "default" }

        assertTrue(
            resolvedToRealMode.isNotEmpty(),
            "no species resolves to a non-'default' mode, so the pairing bug " +
                "cannot occur and this file needs revisiting"
        )
    }

    @Test
    fun `an unknown mode id resolves to the species default`() {
        val bluefin = PfzSpeciesRegistry.byId(species)
        assertNotNull(bluefin, "$species must be in the roster")

        val fallback = bluefin.resolve("NOT_A_MODE", null)
        val viaDefault = bluefin.resolve(null, null)

        assertEquals(
            viaDefault.mode,
            fallback.mode,
            "an unknown mode id must resolve to the default, so the stored key " +
                "and the requested key cannot drift"
        )
    }
}