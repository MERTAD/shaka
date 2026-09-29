package com.shaka.pfz

import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in live smoke test of the FRONT-FINDER against the real Copernicus Marine
 * Toolbox and bathymetry services.
 *
 * Not part of the routine suite: every test returns early (passes silently)
 * when `LIVE_PFZ` is unset, so CI and a plain `gradlew test` are never gated on
 * the network, a Copernicus account, or a date-dependent product.
 *
 * Run it on the dev machine with the account in the environment:
 *
 *     $env:LIVE_PFZ="1"
 *     $env:COP_USER="<user>"; $env:COP_PASS="<pass>"
 *     .\gradlew.bat test --tests "com.shaka.pfz.LivePfzSmokeTest" --console=plain
 *
 * The assertions are invariants that must hold whether or not the grids
 * resolve today (a resolved grid implies a consistent coincidence state; a
 * ranked zone has rank 1 first and a bounded pfz). The interesting numbers are
 * in the test stdout — read them from the test report XML:
 * `build/test-results/test/TEST-com.shaka.pfz.LivePfzSmokeTest.xml`.
 */
class LivePfzSmokeTest {

    private val spotId = "spain-medes"
    private val lat = 42.05
    private val lon = 3.2167

    private fun live(): Boolean = System.getenv("LIVE_PFZ") == "1"

    @Test
    fun `spt analysis resolves a consistent corridor`() {
        if (!live()) return
        val date = LocalDate.now().minusDays(1)
        val grid = runBlocking { PfzGridService().analyze(lat, lon, date) }

        println(
            "LIVE spot  lat=$lat lon=$lon date=$date " +
                "sstGrad=${grid.sstGradientCkm?.let { "%.4f".format(it) }} " +
                "chlGrad=${grid.chlaGradientMgM3km?.let { "%.4f".format(it) }} " +
                "anomaly=${grid.sstAnomalyC?.let { "%.3f".format(it) }} " +
                "depthSlope=${grid.depthGradientMperKm?.let { "%.2f".format(it) }} " +
                "frontKm=${grid.frontKm?.let { "%.2f".format(it) }} " +
                "coincidence=${grid.frontCoincidence} dataDate=$date"
        )

        if (grid.sstGradientCkm != null || grid.chlaGradientMgM3km != null) {
            assertNotNull(grid.frontCoincidence, "a resolved gradient always yields a front verdict")
        }
        grid.sstGradientCkm?.let { assertTrue(it.isFinite() && it >= 0.0) }
        grid.chlaGradientMgM3km?.let { assertTrue(it.isFinite() && it >= 0.0) }
        grid.dataDate?.let { assertTrue(!it.isAfter(date), "analysis cannot post-date the request") }
    }

    @Test
    fun `spot evaluation with grid enrichment survives a real corridor`() {
        if (!live()) return
        val date = LocalDate.now().minusDays(1)
        val service = PfzService(gridSource = PfzGridService())

        val result = runBlocking { service.evaluateWithGrid(spotId, date.toString()) }
        assertTrue(result is PfzService.Result.Ok, "spot exists, so evaluation must be Ok, got $result")

        val response = (result as PfzService.Result.Ok).response
        val bluefin = response.species.firstOrNull { it.id == "bluefin_tuna" }
        println(
            "LIVE pfz   spot=$spotId date=${response.date} species=${response.species.size} " +
                "missing=${response.missingFactors} " +
                "bluefin=${bluefin?.status}/${bluefin?.pfz}/conf=${bluefin?.confidence} " +
                "drivers=${bluefin?.drivers}"
        )
        assertTrue(response.species.isNotEmpty())
    }

    @Test
    fun `ranked zones hold their invariants against the real corridor`() {
        if (!live()) return
        val date = LocalDate.now().minusDays(1)
        val service = PfzService(gridSource = PfzGridService())

        val result = runBlocking { service.evaluateZones(lat, lon, date.toString(), "bluefin_tuna") }
        assertTrue(result is PfzService.ZonesResult.Ok, "a known species must be Ok, got $result")

        val response = (result as PfzService.ZonesResult.Ok).response
        val top = response.zones.firstOrNull()
        val topLine = top?.let { z ->
            "rank${z.rank} ${z.status} pfz=${z.pfz} conf=${z.confidence} " +
                "coinc=${z.frontCoincidence} " +
                "frontKm=${z.frontKm?.let { "%.2f".format(it) }} " +
                "sstGrad=${z.sstGradientCkm?.let { "%.4f".format(it) }}"
        }
        println(
            "LIVE zones species=${response.speciesId} date=${response.date} " +
                "zones=${response.zones.size} top=$topLine"
        )
        assertEquals("bluefin_tuna", response.speciesId)
        assertEquals(1, top?.rank)
        top?.pfz?.let { assertTrue(it in 0..100) }
        top?.sstGradientCkm?.let { assertTrue(it.isFinite() && it >= 0.0) }
    }
}