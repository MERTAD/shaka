package com.shaka.pfz

import com.shaka.data.cache.SpotDataCache
import com.shaka.data.client.CopernicusField
import com.shaka.data.client.CopernicusGrid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the spot -> observation mapping.
 *
 * These exist to lock down the one property that makes PFZ honest: **a value
 * that was not measured must arrive as null, never as a plausible number.**
 *
 * The codebase already contains a live example of that failure mode. `OceanData`
 * still fills missing wave/swell with 1.0 m / 0.5 m / 8 s defaults because the
 * legacy SoCal pipeline reads those as non-null. In a habitat model that is
 * worse than useless: a fabricated calm 0.5 m swell scores 100 for every
 * species in the roster, converting an unknown into a perfect answer.
 *
 * So [PfzService.observe] is specified to read only genuinely-absent-capable
 * sources, and these tests fail the build if that ever changes.
 */
class PfzServiceTest {

    private val service = PfzService()
    private val date = LocalDate.of(2026, 7, 14)

    /** Synthetic spot ids, following the WindContractTest convention: never
     *  collide with a real catalog spot. Injected rather than written to the
     *  global cache, whose update* methods no-op without a live database. */
    private fun serviceWith(
        spotId: String,
        data: SpotDataCache.SpotData
    ): PfzService = PfzService(cacheLookup = { if (it == spotId) data else null })

    private fun exposure(
        depthM: Double?,
        source: String? = "ncei"
    ) = SpotDataCache.ExposureInfo(
        bearing = 270,
        width = 90,
        depthM = depthM,
        depthSource = source
    )

    private fun cached(
        exposure: SpotDataCache.ExposureInfo? = null,
        swell: SpotDataCache.SwellInfo? = null
    ) = SpotDataCache.SpotData(
        exposure = exposure,
        swell = swell?.let {
            SpotDataCache.CachedValue(it, Instant.now())
        }
    )

    // ------------------------------------------------- no data means no data

    @Test
    fun `an unseeded spot yields an all-null observation, never defaults`() {
        val obs = service.observe("pfz-contract-empty", date)

        assertNull(obs.depthM, "depth must be null, not a nominal SpotRecord value")
        assertNull(obs.waterTempC)
        assertNull(obs.chlorophyllMgM3)
        assertNull(obs.chlaGradient, "gradCHL is not computed anywhere in the app")
        assertNull(obs.windSpeedKmh)
        assertNull(obs.waveHeightM)
        assertNull(obs.swellHeightM, "the 0.5 m OceanData default must not leak in here")
        assertNull(obs.oceanCurrentVelocityKmh)
        assertNull(obs.solunarDayRating)
    }

    @Test
    fun `no measurement means no high-confidence score`() {
        // Calendar season is legitimately derivable from the date with no
        // measurements at all, so a few species can still be SCOREABLE here.
        // That is defensible — "it is in season" is a real fact — but it is not
        // a habitat assessment, and the engine's single-factor penalty must
        // keep it from reading like one. Anything stronger would mean season
        // had been allowed to stand in for a measurement.
        val response = PfzEngine.evaluate(service.observe("pfz-contract-empty", date))

        for (result in response.species) {
            if (result.pfz == null) continue
            assertTrue(
                result.confidence <= 50,
                "${result.id} scored ${result.pfz} at confidence ${result.confidence} " +
                    "with zero measurements; single-factor results must be heavily discounted"
            )
            assertEquals(
                1, result.factors.size,
                "${result.id} scored from ${result.factors.map { it.factor }} with no measurements"
            )
        }

        // And every real measurement gap must be named.
        val bluefin = result_of(response, "bluefin_tuna")
        assertTrue(
            bluefin.missingFactors.containsAll(
                listOf(PfzFactor.SST, PfzFactor.CHL, PfzFactor.WIND, PfzFactor.SWELL)
            ),
            "bluefin must report every unmeasured factor, got ${bluefin.missingFactors}"
        )
        // Bathymetry is not one of them. Bluefin's depth band describes the water
        // column, not the seabed, so naming it as missing would claim a data
        // shortfall that does not exist — and it would cost the species confidence
        // for a gap it never had.
        assertFalse(
            bluefin.missingFactors.contains(PfzFactor.DEPTH),
            "bluefin must not report bathymetry as missing, got ${bluefin.missingFactors}"
        )
    }

    private fun result_of(response: PfzResponse, id: String): PfzSpeciesResult =
        response.species.first { it.id == id }

    // ------------------------------------------------------ bathymetry, not nominal

    @Test
    fun `depth comes from real bathymetry and carries its provenance`() {
        val obs = serviceWith("pfz-contract-depth", cached(exposure(412.0, "gebco")))
            .observe("pfz-contract-depth", date)

        assertEquals(412.0, obs.depthM)
        assertEquals("gebco", obs.depthSource)
    }

    @Test
    fun `a missing bathymetry reading stays null rather than using the seed depth`() {
        // exposure row exists but bathymetry never resolved
        val obs = serviceWith("pfz-contract-nodepth", cached(exposure(null, null)))
            .observe("pfz-contract-nodepth", date)

        assertNull(obs.depthM, "SpotRecord.depth is a hand-entered nominal and must not be substituted")
    }

    // ------------------------------------------------------------ unit conversions

    @Test
    fun `unit helpers convert against the true international factors`() {
        // The cache stores imperial; PFZ gates are metric.
        //
        // SpotDataCache's existing factors are ROUNDED, not exact: kmhToKnots
        // uses 0.539957 (true 0.539956803...) and metersToFeet uses 3.28084
        // (true 3.280839895...). So neither pair round-trips perfectly. These
        // tests therefore assert the true converted value, not a perfect
        // inverse — asserting an exact round-trip would be asserting a lie
        // about the codebase. The residual error is ~3e-8 relative, which is
        // orders of magnitude below any threshold these feed.
        assertEquals(18.52, SpotDataCache.knotsToKmh(10.0), 1e-9)
        assertEquals(10.0, SpotDataCache.kmhToKnots(18.52), 1e-4)

        // feetToMeters itself is exact: 1 international foot is 0.3048 m.
        assertEquals(0.3048, SpotDataCache.feetToMeters(1.0), 1e-12)
        assertEquals(30.48, SpotDataCache.feetToMeters(100.0), 1e-9)

        // Round trip through the rounded constant, at a tolerance that reflects
        // the constant's actual precision rather than pretending it away.
        assertEquals(
            1.5,
            SpotDataCache.feetToMeters(SpotDataCache.metersToFeet(1.5)),
            1e-6
        )
    }

    @Test
    fun `cached swell is converted from feet to metres`() {
        val obs = serviceWith(
            "pfz-contract-swell",
            cached(
                exposure = exposure(30.0),
                swell = SpotDataCache.SwellInfo(
                    heightFt = 6.0,
                    periodSec = 11.0,
                    direction = "W",
                    swellHeightFt = 3.0
                )
            )
        ).observe("pfz-contract-swell", date)

        // 6 ft = 1.8288 m, 3 ft = 0.9144 m
        assertEquals(1.8288, obs.waveHeightM!!, 1e-6)
        assertEquals(0.9144, obs.swellHeightM!!, 1e-6)
    }

    /**
     * Regression test for the bug this whole feature exists to prevent.
     *
     * OpenMeteoClient's legacy non-null `swellHeight` falls back to 0.5m when the
     * provider omits swell, and SpotService caches that placeholder labelled
     * "open-meteo". Before the `measured` flag existed, PFZ could not tell a
     * fabricated 0.5m from a real 0.5m swell and would score it, reporting
     * "swell measured" to the angler. It must instead drop the factor and say so.
     */
    @Test
    fun `an unmeasured swell placeholder is reported missing, never scored`() {
        // A real catalog spot, because evaluate() resolves the spot through
        // SpotDatabase and would otherwise answer UnknownSpot.
        val spotId = "spain-medes"
        val unmeasured = SpotDataCache.SwellInfo(
            // Exactly what SpotService writes when the provider returns no
            // swell: the 0.5m fallback, with measured = false.
            heightFt = SpotDataCache.metersToFeet(0.5),
            periodSec = 8.0,
            direction = "N",
            swellHeightFt = SpotDataCache.metersToFeet(0.5),
            source = "open-meteo",
            measured = false
        )
        val data = cached(exposure = exposure(30.0), swell = unmeasured)

        val obs = serviceWith(spotId, data).observe(spotId, date)
        assertNull(
            obs.swellHeightM,
            "a 0.5m provider placeholder must not reach the habitat model as a measurement"
        )
        assertNull(obs.waveHeightM, "same placeholder must not surface as a wave height either")

        // And it must be disclosed, not silently dropped.
        val result = serviceWith(spotId, data).evaluate(spotId, "2026-07-15")
        val ok = result as PfzService.Result.Ok

        val scoredWithSwell = ok.response.species.filter { r ->
            r.factors.any { it.factor == PfzFactor.SWELL }
        }
        assertTrue(
            scoredWithSwell.isEmpty(),
            "no species may be scored on swell while it is unmeasured: " +
                scoredWithSwell.map { it.id }
        )
        val reportingMissingSwell = ok.response.species.filter { r ->
            r.missingFactors.contains(PfzFactor.SWELL)
        }
        assertTrue(
            reportingMissingSwell.isNotEmpty(),
            "the unmeasured swell must be disclosed as a missing factor"
        )
    }

    @Test
    fun `a genuinely measured swell is still scored`() {
        // The guard must not become a blanket "never score swell": a real buoy
        // or Open-Meteo reading is exactly the signal PFZ wants.
        val spotId = "spain-medes"
        val data = cached(
            exposure = exposure(30.0),
            swell = SpotDataCache.SwellInfo(
                heightFt = SpotDataCache.metersToFeet(0.5),
                periodSec = 8.0,
                direction = "N",
                swellHeightFt = SpotDataCache.metersToFeet(0.5),
                source = "open-meteo",
                measured = true
            )
        )
        val obs = serviceWith(spotId, data).observe(spotId, date)
        assertNotNull(obs.swellHeightM, "a measured swell must reach the model")
        assertEquals(0.5, obs.swellHeightM!!, 1e-6)
    }

    @Test
    fun `absent primary swell stays null while the real wave height is still used`() {        val obs = serviceWith(
            "pfz-contract-noswell",
            cached(
                exposure = exposure(30.0),
                swell = SpotDataCache.SwellInfo(
                    heightFt = 4.0,
                    periodSec = 9.0,
                    direction = "NW",
                    swellHeightFt = null
                )
            )
        ).observe("pfz-contract-noswell", date)

        assertNull(obs.swellHeightM, "a missing swell component must not inherit the wave height")
        assertEquals(1.2192, obs.waveHeightM!!, 1e-6)
    }

    // ------------------------------------------------------------------- regions

    @Test
    fun `observation carries the macro-region so regional bands can resolve`() {
        val obs = service.observe("spain-medes", date)
        assertEquals(PfzRegions.WESTERN, obs.region)
    }

    // ------------------------------------------------------------ request outcomes

    @Test
    fun `an unknown spot is distinguished from a spot with no data`() {
        assertTrue(
            service.evaluate("definitely-not-a-real-spot-xyz", "2026-07-14")
                is PfzService.Result.UnknownSpot
        )
    }

    @Test
    fun `a malformed date is rejected rather than silently defaulted to today`() {
        // Every real Mediterranean spot id must exist for the date check to be
        // the one that fires, so use a known id from the catalog.
        val known = com.shaka.data.client.SpotDatabase.getAllSpots().first().id
        val result = service.evaluate(known, "14/07/2026")
        assertTrue(result is PfzService.Result.BadDate, "got $result")
    }

    @Test
    fun `a known spot evaluates even with a cold cache`() {
        val known = com.shaka.data.client.SpotDatabase.getAllSpots().first().id
        val result = service.evaluate(known, "2026-07-14")
        assertTrue(result is PfzService.Result.Ok, "a cold cache is low confidence, not an error")

        val response = (result as PfzService.Result.Ok).response
        assertEquals(known, response.spotId)
        assertNotNull(response.coverageNotes)
        assertTrue(response.coverageNotes.isNotEmpty(), "coverage caveats must always ship")
    }

    // ------------------------------------------------------------ wire format

    @Test
    fun `the response survives JSON encoding with the app's own config`() {
        // The route hands PfzResponse straight to call.respond(), so an
        // un-encodable field would only surface at runtime in production.
        // Use the same lenient/pretty config the server installs.
        val json = Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        }

        val response = PfzEngine.evaluate(
            serviceWith(
                "spain-medes",
                cached(
                    exposure = exposure(28.0),
                    swell = SpotDataCache.SwellInfo(
                        heightFt = 4.0,
                        periodSec = 10.0,
                        direction = "W",
                        swellHeightFt = 2.0
                    )
                )
            ).observe("spain-medes", date)
        )

        val encoded = json.encodeToString(PfzResponse.serializer(), response)
        assertTrue(
            Regex("\"spotId\"\\s*:\\s*\"spain-medes\"").containsMatchIn(encoded),
            encoded.take(400)
        )
        assertTrue(encoded.contains("coverageNotes"), "caveats must be on the wire, not just in memory")

        // Enum wire values are what the Flutter client switches on.
        assertTrue(
            encoded.contains("\"scoreable\"") || encoded.contains("\"unavailable\"") ||
                encoded.contains("\"insufficient_data\""),
            "status must serialise as its lowercase wire name"
        )
        assertTrue(
            encoded.contains("\"cited\"") || encoded.contains("\"expert\""),
            "factor confidence must serialise as its lowercase wire name"
        )

        // Pin the exact key names the Flutter client parses. Kotlinx uses the
        // property names verbatim here (no naming strategy is installed), so a
        // rename on this side would still compile and still pass every other
        // test — it would just render every species as "Unknown" in the app.
        // These assertions are the contract; change them in the same commit as
        // pfz_models.dart if the API ever renames a field.
        val clientKeys = listOf(
            "spotId", "date", "confidence", "species", "missingFactors", "coverageNotes",
            "id", "commonName", "scientificName", "guild", "status", "pfz",
            "factors", "drivers", "blockers", "lowConfidenceFactors",
            "unscorableRequirements", "factor", "score", "weight"
        )
        val missing = clientKeys.filter { !encoded.contains("\"$it\"") }
        assertTrue(
            missing.isEmpty(),
            "client-facing keys absent from the wire, update pfz_models.dart too: $missing"
        )

        // A non-scoreable species must not put a NUMBER on the wire for pfz.
        // kotlinx omits default-valued fields, so this arrives as an absent
        // key rather than an explicit null; both decode to null in the client
        // and neither may ever become 0. Assert the meaning, not the style.
        val unavailable = response.species.first { it.status == PfzStatus.UNAVAILABLE }
        val unavailableJson =
            json.encodeToString(PfzSpeciesResult.serializer(), unavailable)
        assertTrue(
            !Regex("\"pfz\"\\s*:\\s*\\d").containsMatchIn(unavailableJson),
            "an UNAVAILABLE species must not carry a numeric pfz: $unavailableJson"
        )
        assertTrue(
            unavailable.pfz == null,
            "an UNAVAILABLE species must have a null score in memory too"
        )
        assertTrue(
            unavailable.blockers.isNotEmpty(),
            "an UNAVAILABLE species must say why, not just go quiet"
        )
    }

    // ------------------------------------------------------- grid enrichment

    private class StubSource(
        private val grids: Map<CopernicusField, CopernicusGrid> = emptyMap()
    ) : PfzGridSource {
        override suspend fun fetchGrid(
            field: CopernicusField,
            lat: Double,
            lon: Double,
            date: LocalDate
        ): CopernicusGrid? = grids[field]

        override suspend fun depthM(lat: Double, lon: Double): Double? = null
    }

    private fun grid(
        field: CopernicusField,
        date: String = "2026-07-14",
        lat: Double = 41.51,
        lon: Double = 2.51,
        cell: (i: Int, j: Int) -> Double = { _, _ -> 0.0 }
    ): CopernicusGrid {
        val lats = listOf(lat, lat + 0.01)
        val lons = listOf(lon, lon + 0.01)
        val values = List(lats.size) { i -> List(lons.size) { j -> cell(i, j) } }
        return CopernicusGrid(field, lats, lons, values, LocalDate.parse(date), field.label)
    }

    private fun gridService(source: PfzGridSource) = PfzGridService(source)

    /**
     * A corridor that is a physically coherent bluefin *feeding* cell.
     *
     * Every value here is chosen so the v2 model can actually score it, which is
     * the point: bluefin feeding declares `sst`, `chla_gradient` and
     * `ssh_anomaly` REQUIRED, so a stub of arbitrary small integers now resolves
     * to `insufficient_data` or `unavailable` rather than a number. The rows step
     * 16 -> 18 C, the columns step 0.5 -> 1.5 mg/m3 so a coincident SST/CHL front
     * is still detected while the sampled chlorophyll stays inside the cited
     * 0.02-20 mg/m3 feeding window, and the SSH pair differences to -0.02 m,
     * which is inside the cited -0.10..0.0 m window.
     */
    private fun bluefinZoneStub() = StubSource(
        mapOf(
            CopernicusField.SST to grid(CopernicusField.SST) { i, _ -> 16.0 + i * 2.0 },
            CopernicusField.SSTA to grid(CopernicusField.SSTA) { _, _ -> 0.6 },
            CopernicusField.CHL to grid(CopernicusField.CHL) { _, j -> 0.5 + j },
            CopernicusField.SSH to grid(CopernicusField.SSH) { _, _ -> 0.0 },
            CopernicusField.SSH_MONTHLY to grid(CopernicusField.SSH_MONTHLY) { _, _ -> 0.02 }
        )
    )

    @Test
    fun `observeWithGrid enriches the observation when the corridor resolves`() {
        val service = PfzService(
            cacheLookup = { _ -> cached(exposure(28.0)) },
            gridSource = gridService(
                StubSource(
                    mapOf(
                        CopernicusField.SST to grid(CopernicusField.SST) { i, _ -> i.toDouble() },
                        CopernicusField.SSTA to grid(CopernicusField.SSTA) { _, _ -> 0.6 },
                        CopernicusField.CHL to grid(CopernicusField.CHL) { _, _ -> 0.3 }
                    )
                )
            )
        )

        val obs = runBlocking { service.observeWithGrid("spain-medes", date) }
        assertNotNull(obs.sstGradientCkm, "a resolved SST grid must supply a gradient")
        assertEquals(0.6, obs.sstAnomalyC!!, 1e-9)
        assertEquals(FrontCoincidence.SST_ONLY, obs.frontCoincidence)
        assertEquals(date, obs.dataDate)
        assertEquals(0, obs.forecastDay)
        assertEquals(28.0, obs.depthM, "the cache corridor must survive grid enrichment")
    }

    @Test
    fun `observeWithGrid stays honest when nothing resolves`() {
        val service = PfzService(
            cacheLookup = { _ -> cached(exposure(28.0)) },
            gridSource = gridService(StubSource())
        )

        val obs = runBlocking { service.observeWithGrid("spain-medes", date) }
        assertNull(obs.sstGradientCkm)
        assertNull(obs.chlaGradient)
        assertNull(obs.sstAnomalyC)
        assertNull(obs.depthGradientMperKm)
        assertNull(obs.frontCoincidence)
        assertNull(obs.dataDate)
        assertEquals(0, obs.forecastDay)
        assertEquals(28.0, obs.depthM)
    }

    @Test
    fun `forecastDay counts the ocean analysis lag in days`() {
        val service = PfzService(
            cacheLookup = { _ -> cached(exposure(28.0)) },
            gridSource = gridService(
                StubSource(
                    mapOf(
                        CopernicusField.SST to grid(
                            CopernicusField.SST,
                            cell = { _, _ -> 21.0 },
                            date = "2026-07-11"
                        )
                    )
                )
            )
        )

        val obs = runBlocking { service.observeWithGrid("spain-medes", date) }
        assertEquals(3, obs.forecastDay, "requested 07-14, analysed 07-11")
        assertEquals(LocalDate.of(2026, 7, 11), obs.dataDate)
    }

    // ------------------------------------------------------- ranked zones

    private fun rankedZones(service: PfzService): PfzService.ZonesResult =
        runBlocking { service.evaluateZones(41.515, 2.515, "2026-07-14", "bluefin_tuna") }

    @Test
    fun `evaluateZones ranks scoreable grid zones for the species`() {
        val service = PfzService(gridSource = gridService(bluefinZoneStub()))

        when (val result = rankedZones(service)) {
            is PfzService.ZonesResult.Ok -> {
                assertTrue(result.response.zones.isNotEmpty(), "a resolved corridor must yield candidate zones")
                val best = result.response.zones.first()
                assertEquals(1, best.rank)
                assertEquals(PfzStatus.SCOREABLE, best.status)
                assertNotNull(best.pfz)
                assertEquals(FrontCoincidence.COINCIDENT, best.frontCoincidence)
                assertTrue(best.sstGradientCkm!! > PfzFactors.SST_FRONT_C_PER_KM)
                assertEquals("bluefin_tuna", result.response.speciesId)
                assertNotNull(best.frontKm, "a zone sitting on a front must be measured at ~0 km")
                assertTrue(best.frontKm!! < 5.0, "frontKm = ${best.frontKm}")
                // The applied context travels with the zone, or the number is not
                // reproducible: the same corridor run as `spawning` is refused.
                assertEquals("feeding", best.mode)
                assertEquals("large", best.sizeClass)
                // 2.5 E is west of the 8 E divide, so the zone resolves to the
                // western basin from its own centroid.
                assertEquals(PfzRegions.WESTERN, best.region)
            }
            else -> assertTrue(false, "expected Ok for a resolved corridor, got $result")
        }
    }

    @Test
    fun `zones carry satcatch target names and polygon geometry on the wire`() {
        val service = PfzService(gridSource = gridService(bluefinZoneStub()))

        when (val result = rankedZones(service)) {
            is PfzService.ZonesResult.Ok -> {
                val zone = result.response.zones.first()
                // SatCatch-style naming: species tag + day/month + zero-padded rank.
                assertEquals("BluefinTuna 14/07 001", zone.name)
                // Polygon geometry: closed outer ring, no holes for a single cell.
                assertTrue(zone.polygon.size >= 5, "a closed 4-corner box, got ${zone.polygon}")
                assertEquals(zone.polygon.first(), zone.polygon.last(), "ring must be closed")
                assertTrue(zone.holes.isEmpty())
                // The keys the Flutter map overlay parses must be on the wire.
                val json = Json { prettyPrint = true; encodeDefaults = false }
                val encoded = json.encodeToString(PfzZone.serializer(), zone)
                for (key in listOf(
                    "rank", "name", "lat", "lon", "polygon", "pfz", "frontCoincidence",
                    "mode", "sizeClass", "region"
                )) {
                    assertTrue(encoded.contains("\"$key\""), "zone wire key '$key' absent: $encoded")
                }
            }
            else -> assertTrue(false, "expected Ok for a resolved corridor, got $result")
        }
    }

    @Test
    fun `evaluateZones reports unknown species and bad inputs`() {
        val service = PfzService(gridSource = gridService(StubSource()))
        assertTrue(rankedZones(service).let { it is PfzService.ZonesResult.Ok })

        val unknown = runBlocking {
            service.evaluateZones(41.515, 2.515, "2026-07-14", "no-such-species")
        }
        assertTrue(unknown is PfzService.ZonesResult.UnknownSpecies)

        val badDate = runBlocking {
            service.evaluateZones(41.515, 2.515, "not-a-date", "bluefin_tuna")
        }
        assertTrue(badDate is PfzService.ZonesResult.BadDate)

        val badLocation = runBlocking {
            service.evaluateZones(91.0, 200.0, "2026-07-14", "bluefin_tuna")
        }
        assertTrue(badLocation is PfzService.ZonesResult.BadLocation)
    }

    @Test
    fun `evaluateZones stays honest when the corridor is dark`() {
        val service = PfzService(gridSource = gridService(StubSource()))

        val result = rankedZones(service)
        assertTrue(result is PfzService.ZonesResult.Ok)
        val response = (result as PfzService.ZonesResult.Ok).response
        assertTrue(response.zones.isEmpty(), "no resolved grid -> no ranked zones")
        assertTrue(
            response.coverageNotes.any { it.contains("No Copernicus SST/SSTA/CHL grid resolved") },
            "the caller must be told the products never arrived, got ${response.coverageNotes}"
        )
        assertEquals(0, response.confidence)
    }

    @Test
    fun `an empty zone list never claims a fetch timeout was an absence of data`() {
        // The wording is the contract. A deadline that expires is a retryable
        // infrastructure event; reporting it as "no data today" tells the user
        // the sea is uninteresting when in fact nothing was ever measured, and
        // there is no way to tell the two apart afterwards.
        val service = PfzService(gridSource = null)
        val timedOut = PfzGridService.PfzZoneAnalysis.Empty(
            PfzGridService.PfzZoneEmptyReason.TIMED_OUT, pointResolved = false
        )
        val note = service.emptyZonesNote(timedOut, 41.515, 2.515)

        assertTrue(note.contains("fetch timeout"), "must name the timeout, got: $note")
        assertTrue(note.contains("not an absence of data"), "got: $note")
        assertTrue(note.contains("masked cell"), "a masked point must still be said, got: $note")
    }

    @Test
    fun `an empty zone list distinguishes a missing product from an empty sea`() {
        val service = PfzService(gridSource = null)
        val noGrid = PfzGridService.PfzZoneAnalysis.Empty(
            PfzGridService.PfzZoneEmptyReason.NO_GRID, pointResolved = true
        )
        val noPatches = PfzGridService.PfzZoneAnalysis.Empty(
            PfzGridService.PfzZoneEmptyReason.NO_PATCHES, pointResolved = true
        )

        val gridNote = service.emptyZonesNote(noGrid, 41.515, 2.515)
        val patchNote = service.emptyZonesNote(noPatches, 41.515, 2.515)

        assertTrue(gridNote.contains("do not cover these coordinates or the download failed"), gridNote)
        assertTrue(patchNote.contains("no zone patch could be"), patchNote)
        assertTrue(
            gridNote != patchNote,
            "a missing product and a featureless sea are different facts"
        )
        assertFalse(
            gridNote.contains("masked cell"),
            "the point resolved here, so no masked-cell claim may be made: $gridNote"
        )
    }

    @Test
    fun `evaluateZones stays honest when no corridor is configured at all`() {
        // Distinct from the previous case, which has a grid source that
        // resolves nothing. Here there is no grid source, which is what an
        // installation without Copernicus configured actually looks like. The
        // two produce the same empty answer and must say different things:
        // "the corridor went dark" and "no corridor was ever set up" lead a
        // reader to different conclusions, and conflating them hides a
        // misconfiguration behind an ordinary no-data day.
        val service = PfzService(gridSource = null)

        val result = rankedZones(service)
        assertTrue(result is PfzService.ZonesResult.Ok)
        val response = (result as PfzService.ZonesResult.Ok).response

        assertTrue(response.zones.isEmpty(), "no corridor -> no ranked zones")
        assertEquals(0, response.confidence)
        assertTrue(
            response.coverageNotes.any { it.contains("No spatial grid corridor") },
            "the caller must be told no corridor is configured, got ${response.coverageNotes}"
        )
        assertTrue(
            response.coverageNotes.none { it.contains("did not resolve") },
            "an unconfigured corridor did not 'fail to resolve'; that wording " +
                "reports a transient data gap and hides the misconfiguration"
        )
    }

    // ------------------------------------------------- zone mode and size class

    @Test
    fun `a zone request carries the applied mode and size class through to the wire`() {
        val service = PfzService(gridSource = gridService(bluefinZoneStub()))

        val spawning = runBlocking {
            service.evaluateZones(
                41.515, 2.515, "2026-07-14", "bluefin_tuna", mode = "spawning"
            )
        }
        assertTrue(spawning is PfzService.ZonesResult.Ok)
        val zone = (spawning as PfzService.ZonesResult.Ok).response.zones.first()
        assertEquals("spawning", zone.mode)
        assertEquals("large", zone.sizeClass)
        // Same corridor, different model: the cited spawning chlorophyll window
        // is 0-0.15 mg/m3 and this cell carries ~1, so it is refused rather than
        // ranked low. Without the mode parameter this request would have
        // silently returned the feeding answer.
        assertEquals(PfzStatus.UNAVAILABLE, zone.status)
        assertNull(zone.pfz)
        assertTrue(
            zone.blockers.any { it.startsWith("chl:") },
            "got ${zone.blockers}"
        )
    }

    @Test
    fun `a zone request for an unparameterised size class is refused`() {
        val service = PfzService(gridSource = gridService(bluefinZoneStub()))

        val small = runBlocking {
            service.evaluateZones(
                41.515, 2.515, "2026-07-14", "bluefin_tuna", sizeClass = "small"
            )
        }
        assertTrue(small is PfzService.ZonesResult.Ok)
        val zone = (small as PfzService.ZonesResult.Ok).response.zones.first()
        assertEquals(PfzStatus.INSUFFICIENT_DATA, zone.status)
        assertNull(zone.pfz, "a zone must not borrow another size class's thresholds")
        assertTrue(
            zone.blockers.any { it.contains("small") },
            "got ${zone.blockers}"
        )
    }

    // ------------------------------------------- zone benthic required factors

    @Test
    fun `a zone carries the benthic measurements its species requires`() {
        // Red shrimp is the test case that matters offshore: its cited envelope
        // is 13.0-14.5 C bottom temperature in 38.1-38.5 psu, both REQUIRED. If
        // analyzeZones did not fetch the benthic products, every red shrimp zone
        // would be permanently insufficient_data and the species would look
        // absent from the whole basin rather than unmeasured.
        val service = PfzService(
            gridSource = gridService(
                StubSource(
                    mapOf(
                        CopernicusField.SST to
                            grid(CopernicusField.SST, lat = 36.5, lon = 15.0) { i, _ -> 16.0 + i * 2.0 },
                        CopernicusField.SSTA to
                            grid(CopernicusField.SSTA, lat = 36.5, lon = 15.0) { _, _ -> 0.6 },
                        CopernicusField.CHL to
                            grid(CopernicusField.CHL, lat = 36.5, lon = 15.0) { _, j -> 0.5 + j },
                        CopernicusField.BOTTOM_TEMP to
                            grid(CopernicusField.BOTTOM_TEMP, lat = 36.5, lon = 15.0) { _, _ -> 13.7 },
                        CopernicusField.BOTTOM_SALINITY to
                            grid(CopernicusField.BOTTOM_SALINITY, lat = 36.5, lon = 15.0) { _, _ -> 38.3 }
                    )
                )
            )
        )

        val result = runBlocking {
            // The stub cells have to sit where the request does, because the zone
            // is classified from its own centroid. 36.5 N / 15 E resolves to the
            // central basin, so the western exclusion is not what stops this
            // request.
            service.evaluateZones(36.5, 15.0, "2026-07-14", "red_shrimp")
        }
        assertTrue(result is PfzService.ZonesResult.Ok, "got $result")
        val zones = (result as PfzService.ZonesResult.Ok).response.zones
        assertTrue(zones.isNotEmpty(), "a resolved corridor must yield candidate zones")

        // Depth is the one thing the grid cannot give, so the refusal must name
        // depth and only depth: that is a data-source gap, not a bad reading.
        val best = zones.first()
        assertTrue(
            best.blockers.any { it.contains(PfzFactor.DEPTH) && it.contains("required") },
            "expected a depth requirement blocker, got ${best.blockers}"
        )
        assertFalse(
            best.blockers.any { it.contains("bottom_temp") || it.contains("bottom_salinity") },
            "the benthic products must reach the zone: got ${best.blockers}"
        )
    }
}
