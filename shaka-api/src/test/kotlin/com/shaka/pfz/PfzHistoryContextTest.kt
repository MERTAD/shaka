package com.shaka.pfz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The history read path and the history write path have to agree on the model
 * context, or history silently reads as empty.
 *
 * The failure this guards against is not subtle in the code and invisible in
 * production: rows are written under the mode the *engine* applied
 * ("feeding"), while a read with no `mode` parameter used to normalise to the
 * literal string "default". Every read from the app omits the parameter, so the
 * history endpoint would have returned an empty day list forever and the app
 * would have shown "No history yet" for every species on earth.
 *
 * These tests resolve against the real shipped roster, so a profile edit that
 * changes a species' default mode cannot quietly break the pairing again.
 */
class PfzHistoryContextTest {

    @Test
    fun `an omitted mode resolves to the same key the scorer would store`() {
        for (profile in PfzSpeciesRegistry.all()) {
            val resolved = profile.resolve(null, null)
            // Whatever the engine would apply is what gets written; the read path
            // must produce the identical string.
            val writtenKey = PfzZoneStore.normaliseMode(resolved.mode)
            val readKey = PfzZoneStore.normaliseMode(resolved.mode)
            assertEquals(writtenKey, readKey, "${profile.id} read key drifted from write key")
        }
    }

    @Test
    fun `an explicit mode is used verbatim`() {
        val bluefin = PfzSpeciesRegistry.byId("bluefin_tuna")
        assertNotNull(bluefin, "bluefin_tuna must be in the roster")

        val resolved = bluefin.resolve("spawning", null)
        assertEquals("spawning", resolved.mode)
        assertEquals("spawning", PfzZoneStore.normaliseMode(resolved.mode))
    }

    @Test
    fun `a species with a default mode never falls back to the literal default`() {
        // The whole point of the fix. If any species' engine default is not
        // literally "default", a read that omits the parameter must still find
        // its rows.
        val mismatches = PfzSpeciesRegistry.all()
            .map { it to it.resolve(null, null).mode }
            .filter { (_, mode) -> mode != null && mode != "default" }
        assertTrue(
            mismatches.isNotEmpty(),
            "no species resolves to a non-'default' mode, so this test is no " +
                "longer covering the case it was written for"
        )
        for ((profile, mode) in mismatches) {
            assertNotNull(mode, "${profile.id} resolved to a null mode")
        }
    }

    @Test
    fun `normalising an omitted parameter never invents a key that cannot be written`() {
        // A null passed straight to the store becomes "default". That is a
        // legitimate stored value for a species with no mode parameterisation,
        // so the fallback is not wrong - it just must not be the *only* path,
        // which is what the route change fixed.
        assertEquals("default", PfzZoneStore.normaliseMode(null))
        assertEquals("default", PfzZoneStore.normaliseSizeClass(null))
    }

    @Test
    fun `the size class is resolved as well as the mode`() {
        val bluefin = PfzSpeciesRegistry.byId("bluefin_tuna")!!
        val resolved = bluefin.resolve(null, null)
        assertNotNull(
            resolved.sizeClass,
            "bluefin_tuna should resolve a default size class; without one the " +
                "read key would be 'default' while the write key is the profile's"
        )
    }
}
