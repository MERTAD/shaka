package com.shaka.api.routes

import com.shaka.pfz.PfzSpeciesRegistry
import com.shaka.pfz.PfzStatus
import com.shaka.pfz.PfzZone
import com.shaka.pfz.PfzZonesResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The zones endpoint's persistence decision, tested without a database or a
 * live Copernicus corridor.
 *
 * The decision is pure, so these need neither. What they protect is the
 * choice between two operations that look similar and are not: replacing a
 * day and clearing it. Getting that backwards is invisible until someone
 * reads the history — a stale row reads as though it were today's answer,
 * and an empty day reads as a gap, so both produce a plausible, wrong chart.
 *
 * The key rule is that the applied context never comes from the raw query
 * value. Mode ids are matched exactly and an unknown id falls back to the
 * species' default rather than erroring, so `?mode=FEEDING` is answered for
 * `feeding` and any row it writes is stored under `feeding`. Keying off the
 * raw string would clear a `FEEDING` row that cannot exist and leave the real
 * one behind.
 */
class PfzPersistActionTest {

    private val anchorLat = 12.3456
    private val anchorLon = -45.6789
    private val species = "bluefin_tuna"
    private val today = "2026-10-02"

    private fun zone(
        rank: Int = 1,
        mode: String = "feeding",
        sizeClass: String = "large",
        pfz: Int? = 77,
    ) = PfzZone(
        rank = rank,
        name = "zone $rank",
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
        missingFactors = emptyList(),
        lowConfidenceFactors = emptyList(),
        mode = mode,
        sizeClass = sizeClass,
    )

    private fun response(zones: List<PfzZone>) = PfzZonesResponse(
        speciesId = species,
        speciesName = "Atlantic Bluefin Tuna",
        speciesScientificName = "Thunnus thynnus",
        date = today,
        lat = anchorLat,
        lon = anchorLon,
        confidence = 55,
        zones = zones,
        missingFactors = emptyList(),
        coverageNotes = emptyList(),
    )

    @Test
    fun `zones present means replace the day`() {
        val action = pfzPersistAction(response(listOf(zone())), species)

        assertTrue(
            action is PfzPersistAction.ReplaceDay,
            "a response with zones must replace the day, not clear it"
        )
        assertEquals("feeding", action.mode)
        assertEquals("large", action.sizeClass)
    }

    @Test
    fun `no zones means clear the day`() {
        val action = pfzPersistAction(response(emptyList()), species)

        assertTrue(
            action is PfzPersistAction.ClearDay,
            "an empty response must clear the day; treating it as a no-op leaves " +
                "stale rows to be read back as today's answer"
        )
        assertEquals("feeding", action.mode, "the applied mode should be the species default")
        assertEquals("large", action.sizeClass)
    }

    @Test
    fun `a non-empty response keys off the applied mode, not the request`() {
        // The engine echoes its applied mode on every zone. If the caller asked
        // for one thing and the engine answered for another, the row must land
        // under what the engine actually did, or the history is unreadable.
        val action = pfzPersistAction(
            response(listOf(zone(mode = "spawning", sizeClass = "large"))),
            species,
            rawMode = "feeding",
            rawSizeClass = "large"
        )

        assertTrue(action is PfzPersistAction.ReplaceDay)
        assertEquals(
            "spawning",
            action.mode,
            "persisted under the requested mode rather than the applied one"
        )
    }

    @Test
    fun `an empty response resolves the mode from the species default`() {
        val action = pfzPersistAction(
            response(emptyList()),
            species,
            rawMode = "spawning",
            rawSizeClass = "large"
        )

        assertTrue(action is PfzPersistAction.ClearDay)
        assertEquals(
            "spawning",
            action.mode,
            "an explicit valid mode must be honoured when clearing"
        )
    }

    @Test
    fun `an empty response ignores an unrecognised mode rather than clearing nothing`() {
        val action = pfzPersistAction(
            response(emptyList()),
            species,
            rawMode = "FEEDING",
            rawSizeClass = null
        )

        assertTrue(action is PfzPersistAction.ClearDay)
        assertEquals(
            "feeding",
            action.mode,
            "an unrecognised mode id resolves to the default; using the raw string " +
                "would clear a 'FEEDING' row that cannot exist and leave the real one"
        )
    }

    @Test
    fun `clearing with an unknown species yields no context rather than a guess`() {
        val action = pfzPersistAction(response(emptyList()), "not_a_species")

        assertTrue(action is PfzPersistAction.ClearDay)
        assertEquals(
            null,
            action.mode,
            "with no profile there is nothing to resolve; inventing 'default' would " +
                "clear rows for a series nobody asked for"
        )
    }

    @Test
    fun `the roster can actually produce a mismatched key`() {
        // The premise of the raw-key cases above. If every id matched exactly,
        // or every default were literally "default", the bug could not occur
        // and those tests would be asserting something unreachable.
        val bluefin = PfzSpeciesRegistry.byId(species)
        assertNotNull(bluefin, "$species must be in the roster")

        assertEquals(
            null,
            bluefin.modeById("FEEDING"),
            "modeById unexpectedly matched 'FEEDING'; if mode matching ever becomes " +
                "case-insensitive the raw-key tests need revisiting"
        )
        assertTrue(
            bluefin.resolve(null, null).mode != "default",
            "$species resolves to the literal 'default' mode, so the omitted-parameter " +
                "drift cannot occur for it"
        )
    }
}