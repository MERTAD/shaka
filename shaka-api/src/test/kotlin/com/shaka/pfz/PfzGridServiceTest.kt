package com.shaka.pfz

import com.shaka.data.client.CopernicusField
import com.shaka.data.client.CopernicusGrid
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spatial-analysis tests for [PfzGridService].
 *
 * The Copernicus satellite products are real, but the geometry and threshold
 * logic around them are ours, so it is pinned down against contrived grids:
 * a known gradient must produce the known °C/km magnitude, a flat but resolved
 * grid is a measured "no front" (never null), a coincident pair of fronts is
 * reported as such, and a grid that never resolves stays null — the honesty
 * contract of this corridor.
 */
class PfzGridServiceTest {

    private val date = LocalDate.of(2026, 9, 26)
    private val lat = 41.515
    private val lon = 2.515

    /** Pluggable source returning whichever grids/depths the test lays down. */
    private class FakeSource(
        private val grids: Map<CopernicusField, CopernicusGrid> = emptyMap(),
        private val depthAt: (lat: Double, lon: Double) -> Double? = { _, _ -> null }
    ) : PfzGridSource {
        override suspend fun fetchGrid(
            field: CopernicusField,
            lat: Double,
            lon: Double,
            date: LocalDate
        ): CopernicusGrid? = grids[field]

        override suspend fun depthM(lat: Double, lon: Double): Double? = depthAt(lat, lon)
    }

    private fun grid(
        field: CopernicusField,
        lats: List<Double> = listOf(41.51, 41.52, 41.53),
        lons: List<Double> = listOf(2.51, 2.52, 2.53),
        cell: (i: Int, j: Int) -> Double = { _, _ -> 0.0 },
        date: String = "2026-09-26"
    ): CopernicusGrid {
        val values = List(lats.size) { i -> List(lons.size) { j -> cell(i, j) } }
        return CopernicusGrid(field, lats, lons, values, LocalDate.parse(date), field.label)
    }

    private fun analyze(source: PfzGridSource): PfzSpatialGrid =
        runBlocking { PfzGridService(source).analyze(lat, lon, date) }

    // ------------------------------------------------------------------ maths

    @Test
    fun `a known sst gradient yields the expected celsius-per-km magnitude`() {
        // +1.0C per 0.01 deg of latitude -> 1.0 / (0.01 * 110.574) C per km.
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SST to sst)))

        val expected = 1.0 / (0.01 * 110.574)
        assertNotNull(result.sstGradientCkm)
        assertEquals(expected, result.sstGradientCkm!!, 1e-3)
        assertEquals(FrontCoincidence.SST_ONLY, result.frontCoincidence)
        assertEquals(date, result.dataDate)
    }

    @Test
    fun `a flat but resolved grid is a measured no-front, not an unknown`() {
        val sst = grid(CopernicusField.SST, cell = { _, _ -> 21.4 })
        val chl = grid(CopernicusField.CHL, cell = { _, _ -> 0.35 })
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SST to sst, CopernicusField.CHL to chl)))

        assertEquals(0.0, result.sstGradientCkm!!, 1e-9, "flat SST is a measured zero gradient")
        assertEquals(0.0, result.chlaGradientMgM3km!!, 1e-9)
        assertEquals(FrontCoincidence.NONE, result.frontCoincidence)
        assertNull(result.frontKm, "no front means no distance to a front")
    }

    @Test
    fun `sst and chl fronts on the same box report coincident`() {
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val chl = grid(CopernicusField.CHL, cell = { _, j -> j.toDouble() })
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SST to sst, CopernicusField.CHL to chl)))

        assertEquals(FrontCoincidence.COINCIDENT, result.frontCoincidence)
        assertTrue(result.sstGradientCkm!! > PfzFactors.SST_FRONT_C_PER_KM)
        assertTrue(result.chlaGradientMgM3km!! > PfzFactors.CHL_FRONT_MG_M3_PER_KM)
        assertNotNull(result.frontKm, "a detected front carries a distance to the spot")
    }

    @Test
    fun `front distance is small when the strongest edge sits right at the spot`() {
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SST to sst)))
        assertNotNull(result.frontKm)
        assertTrue(result.frontKm!! < 3.0, "the front is on the spot box, got ${result.frontKm}km")
    }

    // ------------------------------------------------------------ ssta & depth

    @Test
    fun `sst anomaly is read at the nearest grid cell`() {
        val ssta = grid(CopernicusField.SSTA, cell = { _, _ -> 0.7 })
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SSTA to ssta)))
        assertEquals(0.7, result.sstAnomalyC!!, 1e-9)
    }

    @Test
    fun `sst anomaly value that is NaN at the spot cell stays null`() {
        val ssta = grid(
            CopernicusField.SSTA,
            lats = listOf(41.4, 41.5),
            lons = listOf(2.4, 2.5),
            cell = { _, _ -> Double.NaN }
        )
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SSTA to ssta)))
        assertNull(result.sstAnomalyC, "a gap cell is missing, not zero")
    }

    @Test
    fun `bathymetric slope is the steepest centre-to-probe drop per km`() {
        val depthAt: (Double, Double) -> Double? = { la, lo ->
            when {
                abs(la - 41.565) < 1e-9 -> 60.0
                abs(la - 41.465) < 1e-9 -> 31.0
                abs(lo - 2.565) < 1e-9 -> 50.0
                abs(lo - 2.465) < 1e-9 -> 30.0
                abs(la - 41.515) < 1e-9 && abs(lo - 2.515) < 1e-9 -> 30.0
                else -> null
            }
        }
        val result = analyze(FakeSource(depthAt = depthAt))

        // North probe: |60 - 30| over 0.05 deg latitude = 5.5287 km.
        val expected = 30.0 / (0.05 * 110.574)
        assertNotNull(result.depthGradientMperKm)
        assertEquals(expected, result.depthGradientMperKm!!, 1e-3)
    }

    @Test
    fun `bathymetric slope with no probe depths is unknown, not flat`() {
        val sst = grid(CopernicusField.SST, cell = { _, _ -> 20.0 })
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SST to sst)))
        assertNull(result.depthGradientMperKm, "no depth readings must stay missing")
    }

    // --------------------------------------------------------------- honesty

    @Test
    fun `every missing grid is reported missing, never a guessed zero`() {
        val result = analyze(FakeSource())
        assertNull(result.sstGradientCkm)
        assertNull(result.chlaGradientMgM3km)
        assertNull(result.sstAnomalyC)
        assertNull(result.depthGradientMperKm)
        assertNull(result.frontKm)
        assertNull(result.frontCoincidence, "nothing resolved -> front state unknown")
        assertNull(result.dataDate)
    }

    @Test
    fun `a resolved sst with no chl grid reports sst-only, not unknown`() {
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val result = analyze(FakeSource(grids = mapOf(CopernicusField.SST to sst)))
        assertEquals(FrontCoincidence.SST_ONLY, result.frontCoincidence)
    }

    // -------------------------------------------------------------- zones

    private fun analyzeZones(source: PfzGridSource): PfzGridService.PfzZoneAnalysis =
        runBlocking { PfzGridService(source).analyzeZones(lat, lon, date) }

    /** Same, but against a coordinate that sits exactly on a lattice node. */
    private fun analyzeZonesAt(
        source: PfzGridSource,
        atLat: Double,
        atLon: Double
    ): PfzGridService.PfzZoneAnalysis =
        runBlocking { PfzGridService(source).analyzeZones(atLat, atLon, date) }

    @Test
    fun `analyzeZones splits the box into patches that do not cross a detected front`() {
        // 3x3 grid -> 2x2 box-cells. The +1C/row SST step is a front on the
        // shared latitude edge, so the box splits into two 2-cell patches
        // (south band and north band) instead of four single cells.
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val zones = analyzeZones(FakeSource(grids = mapOf(CopernicusField.SST to sst))).zones
        assertEquals(2, zones.size)

        // Patches are ranked by gradient strength, so the thermally warmer
        // (north) side legitimately ranks first; neither side may cross the
        // 41.52 front line.
        val south = zones.filter { z -> z.polygon.all { p -> p.lat <= 41.52 + 1e-9 } }
        val north = zones.filter { z -> z.polygon.all { p -> p.lat >= 41.52 - 1e-9 } }
        assertEquals(1, south.size, "exactly one south patch")
        assertEquals(1, north.size, "exactly one north patch")
        assertEquals(41.515, south[0].lat, 1e-9, "south patch centroid")
        assertTrue(south[0].lon in 2.52 - 1e-9..2.52 + 1e-9, "south patch spans both longitude cells")
        assertEquals(41.525, north[0].lat, 1e-9, "north patch centroid")
    }

    @Test
    fun `a flat resolved box merges into one patch whose polygon is the box outline`() {
        val sst = grid(CopernicusField.SST, cell = { _, _ -> 21.4 })
        val zones = analyzeZones(FakeSource(grids = mapOf(CopernicusField.SST to sst))).zones

        assertEquals(1, zones.size, "no front -> the whole box is one patch")
        val ring = zones[0].polygon
        assertTrue(ring.size >= 5, "a closed 4-corner ring has >= 5 points, got $ring")
        assertEquals(ring.first(), ring.last(), "polygon rings are closed")
        assertTrue(zones[0].holes.isEmpty())
        val lats = ring.map { it.lat }.distinct().sorted()
        val lons = ring.map { it.lon }.distinct().sorted()
        assertEquals(listOf(41.51, 41.52, 41.53), lats)
        assertEquals(listOf(2.51, 2.52, 2.53), lons)
    }

    @Test
    fun `a front split keeps both patches interior and together tiles the box`() {
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val zones = analyzeZones(FakeSource(grids = mapOf(CopernicusField.SST to sst))).zones

        assertEquals(2, zones.size)
        for (z in zones) {
            // Every polygon point is a lattice corner: all lats are box rows and
            // every patch touches both longitude columns (its cells span j=0..1).
            assertTrue(z.polygon.all { p -> p.lat in 41.51 - 1e-9..41.53 + 1e-9 })
            assertEquals(setOf(2.51, 2.52, 2.53), z.polygon.map { it.lon }.toSet())
        }
        // The two halves meet only on the front line; lats stay on their own side.
        assertTrue(zones[0].polygon.all { p -> p.lat <= 41.52 + 1e-9 } ||
            zones[0].polygon.all { p -> p.lat >= 41.52 - 1e-9 })
        assertTrue(zones[1].polygon.all { p -> p.lat <= 41.52 + 1e-9 } ||
            zones[1].polygon.all { p -> p.lat >= 41.52 - 1e-9 })
        assertTrue(zones[0].polygon.all { p -> p.lat <= 41.52 + 1e-9 } !=
            zones[1].polygon.all { p -> p.lat <= 41.52 + 1e-9 })
    }

    @Test
    fun `a neutral core inside a warm patch becomes a hole ring in the polygon`() {
        // 4x4 node grid -> 3x3 cells. SSTA is +1.0 everywhere except the
        // single central cell, which is 0.0 (neutral): the warm cells form a
        // ring patch with the neutral core as a hole.
        val lats = listOf(41.50, 41.51, 41.52, 41.53)
        val lons = listOf(2.50, 2.51, 2.52, 2.53)
        val ssta = grid(
            CopernicusField.SSTA,
            lats = lats,
            lons = lons,
            cell = { i, j -> if (i == 1 && j == 1) 0.0 else 1.0 }
        )
        val zones = analyzeZones(FakeSource(grids = mapOf(CopernicusField.SSTA to ssta))).zones

        assertEquals(2, zones.size, "one warm ring patch + one neutral core patch")
        val ring = zones.firstOrNull { it.holes.isNotEmpty() }
        assertNotNull(ring, "the warm ring patch must report its neutral core as a hole")
        assertEquals(1, ring.holes.size)
        assertTrue(ring.polygon.first() == ring.polygon.last())
        assertTrue(ring.holes.first().first() == ring.holes.first().last())
        // The hole is the central cell: 41.51..41.52 x 2.51..2.52.
        val holeLats = ring.holes.first().map { it.lat }.distinct().sorted()
        val holeLons = ring.holes.first().map { it.lon }.distinct().sorted()
        assertEquals(listOf(41.51, 41.52), holeLats)
        assertEquals(listOf(2.51, 2.52), holeLons)
    }

    @Test
    fun `analyzeZones marks a coincident front zone at the gradient plus anomaly`() {
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val chl = grid(CopernicusField.CHL, cell = { _, j -> j.toDouble() })
        val ssta = grid(CopernicusField.SSTA, cell = { _, _ -> 0.6 })
        val zones = analyzeZones(
            FakeSource(grids = mapOf(CopernicusField.SST to sst, CopernicusField.CHL to chl, CopernicusField.SSTA to ssta))
        ).zones

        val zone = zones.first()
        assertEquals(FrontCoincidence.COINCIDENT, zone.frontCoincidence)
        assertTrue(zone.sstGradientCkm!! > PfzFactors.SST_FRONT_C_PER_KM)
        assertTrue(zone.chlaGradientMgM3km!! > PfzFactors.CHL_FRONT_MG_M3_PER_KM)
        assertEquals(0.6, zone.sstAnomalyC!!, 1e-9)
        assertEquals(date, zone.dataDate)
        assertEquals(0, zone.forecastDay)
        assertNotNull(zone.frontKm)
        assertTrue(zone.polygon.size >= 5, "a single-cell zone is still a closed box polygon")
        assertEquals(zone.polygon.first(), zone.polygon.last())
    }

    @Test
    fun `analyzeZones names the reason instead of collapsing every empty case`() {
        // An empty zone list used to mean one thing, which meant a fetch that
        // ran past the deadline was indistinguishable from a day with no data.
        val noGrid = analyzeZones(FakeSource())
        assertTrue(noGrid.zones.isEmpty())
        assertEquals(
            PfzGridService.PfzZoneEmptyReason.NO_GRID,
            (noGrid as PfzGridService.PfzZoneAnalysis.Empty).reason
        )
        assertFalse(noGrid.pointResolved, "nothing resolved, so the point did not")
    }

    @Test
    fun `analyzeZones resolves zones from the front when the enrichment batch fails`() {
        // The benthic and monthly batches must enrich a score, not gate the
        // zone list. If they fail as a batch, the analysis still runs on the
        // front fields that did arrive: a slow or dead enrichment product is
        // a weaker score, not a missing zone list.
        val sst = grid(CopernicusField.SST, cell = { i, _ -> i.toDouble() })
        val chl = grid(CopernicusField.CHL, cell = { i, _ -> (i + 1) / 10.0 })
        val throwing = object : PfzGridSource {
            override suspend fun fetchGrid(
                field: CopernicusField,
                lat: Double,
                lon: Double,
                date: LocalDate
            ): CopernicusGrid? {
                if (field in PfzGridService.BENTHIC_FIELDS) {
                    throw IllegalStateException("enrichment product down")
                }
                return when (field) {
                    CopernicusField.SST -> sst
                    CopernicusField.CHL -> chl
                    else -> null
                }
            }

            override suspend fun depthM(lat: Double, lon: Double): Double? = null
        }

        val analysis = analyzeZones(throwing)

        assertTrue(analysis.zones.isNotEmpty(), "front-only analysis must still resolve zones")
    }

    @Test
    fun `a masked point cell is reported even when the window still yields zones`() {
        // The coast case: the caller's own cell is land-masked, but open water
        // inside the same window is measurable and must still be ranked. The
        // zones are legitimate; what the caller has to be told is that they are
        // not a measurement at the coordinates they asked for. Query exactly on
        // node (1,1) so "nearest cell" is not a floating-point tie.
        val masked = grid(
            CopernicusField.SST,
            cell = { i, j -> if (i == 1 && j == 1) Double.NaN else 21.4 }
        )
        val analysis = analyzeZonesAt(FakeSource(grids = mapOf(CopernicusField.SST to masked)), 41.52, 2.52)

        assertFalse(analysis.pointResolved, "the cell the point reads is masked")
        assertTrue(analysis.zones.isNotEmpty(), "the rest of the window is still open water")

        // Sanity: the identical grid with no masked cell does resolve the point,
        // so the flag above is reading the mask and not the geometry.
        val clear = grid(CopernicusField.SST, cell = { _, _ -> 21.4 })
        assertTrue(
            analyzeZonesAt(FakeSource(grids = mapOf(CopernicusField.SST to clear)), 41.52, 2.52).pointResolved,
            "the same grid with no masked cell resolves the point"
        )
    }

    @Test
    fun `a degenerate lattice is no patches rather than a resolved empty`() {
        // A grid with a single row has no box-cells to build patches from. That
        // is a different failure from "the products never arrived" and must not
        // be reported as either, or a truncated download looks like a no-data
        // day. Note this is not the same as an all-masked grid: patches are
        // fenced on geometry and anomaly class, so a fully NaN grid still yields
        // one patch whose corridor fields are simply null.
        val degenerate = grid(CopernicusField.SST, lats = listOf(41.52), lons = listOf(2.51, 2.52, 2.53))
        val analysis = analyzeZones(FakeSource(grids = mapOf(CopernicusField.SST to degenerate)))

        assertTrue(analysis.zones.isEmpty())
        assertEquals(
            PfzGridService.PfzZoneEmptyReason.NO_PATCHES,
            (analysis as PfzGridService.PfzZoneAnalysis.Empty).reason
        )
    }
}