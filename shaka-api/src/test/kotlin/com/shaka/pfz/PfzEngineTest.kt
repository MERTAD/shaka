package com.shaka.pfz

import com.shaka.fishing_intel.processing.SpeciesNormalizer
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PfzEngineTest {

    private val today = LocalDate.now()
    private val july = LocalDate.of(2026, 7, 14)

    /** A fully-populated summer observation at a 30 m rock. */
    private fun goodObs(
        spotId: String = "sicily-ustica-north",
        depthM: Double? = 30.0,
        sst: Double? = 24.0,
        chl: Double? = 0.5,
        wind: Double? = 10.0,
        swell: Double? = 0.5
    ) = PfzObservation(
        spotId = spotId,
        date = july,
        depthM = depthM,
        depthSource = "ncei_dem",
        waterTempC = sst,
        chlorophyllMgM3 = chl,
        windSpeedKmh = wind,
        swellHeightM = swell,
        solunarDayRating = 5,
        moonPhase = "new_moon",
        region = "sicily"
    )

    private fun profile(id: String): SpeciesProfile =
        assertNotNull(PfzSpeciesRegistry.byId(id), "missing profile $id")

    // ------------------------------------------------------------- roster

    @Test
    fun `roster loads with no validation errors`() {
        assertEquals(emptyList(), PfzSpeciesRegistry.loadErrors, "roster had load errors")
        assertEquals(27, PfzSpeciesRegistry.count())
    }

    @Test
    fun `every profile has weights summing to one`() {
        for (p in PfzSpeciesRegistry.all()) {
            val sum = p.weights.values.sum()
            assertTrue(
                abs(sum - 1.0) < 0.001,
                "${p.id} weights sum to $sum"
            )
            val normalized = p.normalizedWeights()
            assertTrue(
                abs(normalized.values.sum() - 1.0) < 0.0001,
                "${p.id} normalized weights sum to ${normalized.values.sum()}"
            )
        }
    }

    @Test
    fun `alias table and roster agree`() {
        val rosterIds = PfzSpeciesRegistry.all().map { it.id }.toSet()
        val aliasIds = PfzSpeciesAliases.allSpeciesIds()
        assertEquals(
            emptySet(),
            aliasIds - rosterIds,
            "aliases reference species absent from the roster"
        )
    }

    @Test
    fun `dropped species are absent from the roster`() {
        assertNull(PfzSpeciesRegistry.byId("yellowfin_tuna"))
        assertNull(PfzSpeciesRegistry.resolve("Yellowfin Tuna"))
        // Generic family term, deliberately dropped in favour of the two
        // real grouper species.
        assertNull(PfzSpeciesRegistry.resolve("Grouper"))
    }

    // ------------------------------------------------- alias isolation

    @Test
    fun `pfz treats bonito as little tunny without touching the global normalizer`() {
        assertEquals("little_tunny", PfzSpeciesAliases.resolve("bonito"))
        assertEquals("little_tunny", PfzSpeciesAliases.resolve("Bonito"))
        // The SoCal pipeline must be unaffected: bonito stays its own species there.
        assertEquals("bonito", SpeciesNormalizer.normalize("Bonito"))
    }

    @Test
    fun `a pacific species does not acquire a mediterranean profile`() {
        assertNull(PfzSpeciesRegistry.resolve("yellowtail"))
        assertNull(PfzSpeciesRegistry.resolve("barracuda"))
    }

    @Test
    fun `multilingual mediterranean names resolve`() {
        assertEquals("gilthead_seabream", PfzSpeciesAliases.resolve("dorada"))
        assertEquals("gilthead_seabream", PfzSpeciesAliases.resolve("Dorade"))
        assertEquals("common_octopus", PfzSpeciesAliases.resolve("pulpo"))
    }

    // ----------------------------------------------------- cited band: bluefin

    @Test
    fun `bluefin scores higher in its cited thermal band than at its cold edge`() {
        val bluefin = profile("bluefin_tuna")

        val ideal = PfzEngine.evaluateSpecies(bluefin, goodObs(sst = 18.0))
        val coldEdge = PfzEngine.evaluateSpecies(bluefin, goodObs(sst = 9.0))

        assertEquals(PfzStatus.SCOREABLE, ideal.status)
        assertEquals(PfzStatus.SCOREABLE, coldEdge.status)
        assertNotNull(ideal.pfz)
        assertNotNull(coldEdge.pfz)
        assertTrue(
            ideal.pfz!! > coldEdge.pfz!!,
            "18C (${ideal.pfz}) should beat 9C (${coldEdge.pfz})"
        )
    }

    @Test
    fun `bluefin is unavailable above its cited 24C feeding ceiling`() {
        val result = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs(sst = 26.0))
        assertEquals(PfzStatus.UNAVAILABLE, result.status)
        assertNull(result.pfz, "a gated species must not receive a numeric score")
        assertEquals(0, result.confidence)
        assertTrue(
            result.blockers.any { it.startsWith("sst:") },
            "expected an sst blocker, got ${result.blockers}"
        )
    }

    @Test
    fun `bluefin chlorophyll is flagged as expert tier not cited`() {
        val result = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs())
        assertEquals(PfzStatus.SCOREABLE, result.status)
        assertTrue(
            result.lowConfidenceFactors.contains(PfzFactor.CHL),
            "chl must be reported as low-confidence for bluefin, got ${result.lowConfidenceFactors}"
        )
    }

    // ------------------------------------------------------------ hard gates

    @Test
    fun `deep shrimp is unavailable at a coastal spot`() {
        for (id in listOf("deep_rose_shrimp", "red_shrimp")) {
            val result = PfzEngine.evaluateSpecies(profile(id), goodObs(depthM = 30.0))
            assertEquals(PfzStatus.UNAVAILABLE, result.status, "$id should be gated at 30m")
            assertNull(result.pfz, "$id must not receive a low numeric score")
            assertTrue(
                result.blockers.any { it.startsWith("depth:") },
                "$id expected a depth blocker, got ${result.blockers}"
            )
        }
    }

    @Test
    fun `swordfish is scoreable at a coastal spot because it feeds the surface at night`() {
        val result = PfzEngine.evaluateSpecies(profile("swordfish"), goodObs(depthM = 30.0))
        assertEquals(
            PfzStatus.SCOREABLE,
            result.status,
            "swordfish is a diel surface feeder, not depth-blocked at 30m"
        )
        assertNotNull(result.pfz)
    }

    @Test
    fun `surface pelagics are not depth-gated over a deep basin`() {
        // Bluefin, little tunny and swordfish live in the top layer and hunt
        // across basins with >2000 m of bottom depth. Bathymetry must never gate
        // them: a 1500 m zone is still valid habitat.
        for (id in listOf("bluefin_tuna", "little_tunny", "swordfish")) {
            val result = PfzEngine.evaluateSpecies(profile(id), goodObs(depthM = 1500.0))
            assertEquals(PfzStatus.SCOREABLE, result.status, "$id must score over deep water")
            assertNotNull(result.pfz, "$id must carry a numeric score over deep water")
            assertFalse(
                result.blockers.any { it.startsWith("depth:") },
                "$id must not report a depth blocker, got ${result.blockers}"
            )
            assertTrue(
                result.missingFactors.contains(PfzFactor.DEPTH),
                "$id must report bathymetry as unused, got ${result.missingFactors}"
            )
        }
    }

    @Test
    fun `substrate-bound species are still depth-gated over a deep basin`() {
        for (id in listOf("common_sole", "common_octopus", "common_dentex")) {
            val result = PfzEngine.evaluateSpecies(profile(id), goodObs(depthM = 1500.0))
            assertEquals(PfzStatus.UNAVAILABLE, result.status, "$id must be depth-gated over deep water")
            assertTrue(
                result.blockers.any { it.startsWith("depth:") },
                "$id expected a depth blocker, got ${result.blockers}"
            )
        }
    }

    @Test
    fun `only the surface pelagics declare the water_column scope`() {
        for (id in listOf("bluefin_tuna", "little_tunny", "swordfish")) {
            assertEquals(DepthScope.WATER_COLUMN, profile(id).depth.scope, "$id scope")
        }
        for (id in listOf("sardine", "anchovy", "horse_mackerel", "common_sole", "deep_rose_shrimp")) {
            assertEquals(DepthScope.BATHYMETRY, profile(id).depth.scope, "$id scope")
        }
    }

    @Test
    fun `exceeding the operational wind limit gates the species`() {
        val result = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs(wind = 60.0))
        assertEquals(PfzStatus.UNAVAILABLE, result.status)
        assertTrue(result.blockers.any { it.startsWith("wind:") }, "got ${result.blockers}")
    }

    @Test
    fun `closed season gates the species`() {
        val bluefin = profile("bluefin_tuna").copy(
            season = SeasonSpec(peakMonths = listOf(6, 7, 8), closedMonths = listOf(2))
        )
        val winter = PfzEngine.evaluateSpecies(bluefin, goodObs().copy(date = LocalDate.of(2026, 2, 10)))
        assertEquals(PfzStatus.UNAVAILABLE, winter.status)
        assertTrue(winter.blockers.any { it.startsWith("season:") }, "got ${winter.blockers}")
    }

    // --------------------------------------------------- missing-data honesty

    @Test
    fun `no data yields insufficient data and never a fabricated number`() {
        val bare = PfzObservation(spotId = "sicily-ustica-north", date = july)
        val result = PfzEngine.evaluateSpecies(profile("common_dentex"), bare)

        assertEquals(PfzStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.pfz, "must not invent a score from nothing")
        assertEquals(0, result.confidence)
        assertTrue(result.missingFactors.contains(PfzFactor.DEPTH))
        assertTrue(result.missingFactors.contains(PfzFactor.SOLUNAR))
    }

    @Test
    fun `missing factors are dropped and remaining weights renormalized to one`() {
        // Bluefin at a known depth and temperature, but no wind, swell, chl or solunar.
        val partial = PfzObservation(
            spotId = "sicily-ustica-north",
            date = july,
            depthM = 30.0,
            waterTempC = 18.0
        )
        val result = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), partial)

        assertEquals(PfzStatus.SCOREABLE, result.status)
        val sum = result.factors.sumOf { it.weight }
        assertTrue(abs(sum - 1.0) < 0.0001, "renormalized weights sum to $sum")

        assertTrue(result.missingFactors.contains(PfzFactor.WIND))
        assertTrue(result.missingFactors.contains(PfzFactor.SWELL))
        assertTrue(result.missingFactors.contains(PfzFactor.SOLUNAR))

        // sst is ideal here (and depth is dropped as bathymetry does not apply
        // to this water-column species), so the renormalized score is 100.
        assertEquals(100, result.pfz)
    }

    @Test
    fun `less coverage means less confidence`() {
        val bluefin = profile("bluefin_tuna")
        val full = PfzEngine.evaluateSpecies(bluefin, goodObs())
        val partial = PfzEngine.evaluateSpecies(
            bluefin,
            goodObs(wind = null, swell = null, chl = null)
        )
        assertTrue(
            partial.confidence < full.confidence,
            "partial ${partial.confidence} should be below full ${full.confidence}"
        )
    }

    @Test
    fun `a single scored factor is treated as an anecdote`() {
        // Depth only: no temperature, wind, swell or solunar.
        val depthOnly = PfzObservation(
            spotId = "sicily-ustica-north",
            date = july,
            depthM = 20.0
        )
        val result = PfzEngine.evaluateSpecies(profile("common_dentex"), depthOnly)
        assertEquals(PfzStatus.SCOREABLE, result.status)
        assertEquals(1, result.factors.size)
        assertTrue(
            result.confidence < 60,
            "single-factor confidence ${result.confidence} should be heavily discounted"
        )
    }

    // ------------------------------------------------ regional SST overrides

    @Test
    fun `spot id prefixes classify into macro-regions`() {
        assertEquals(PfzRegions.MAGHREB, PfzRegions.classify("algeria-tipaza"))
        assertEquals(PfzRegions.CENTRAL, PfzRegions.classify("sicily-ustica-north"))
        assertEquals(PfzRegions.WESTERN, PfzRegions.classify("spain-medes"))
        assertEquals(PfzRegions.EASTERN, PfzRegions.classify("greece-crete"))
        assertEquals(PfzRegions.WESTERN, PfzRegions.classify("France-Nice"))
        assertNull(PfzRegions.classify(null))
        assertNull(PfzRegions.classify("hawaii-kona"))
    }

    @Test
    fun `sardinella uses the maghreb thermal band in algerian waters`() {
        val sardinella = profile("sardinella")

        // 20 C is inside the Maghreb band (18-21.5C) and below the NW-Med
        // ideal band (23-26.5C). Without the override this would be a poor score.
        val algeria = PfzEngine.evaluateSpecies(
            sardinella,
            goodObs(spotId = "algeria-tipaza", sst = 20.0).copy(region = "algeria")
        )
        val western = PfzEngine.evaluateSpecies(
            sardinella,
            goodObs(spotId = "spain-medes", sst = 20.0).copy(region = "spain")
        )

        assertEquals(PfzStatus.SCOREABLE, algeria.status)
        assertEquals(PfzStatus.SCOREABLE, western.status)
        assertTrue(
            algeria.pfz!! > western.pfz!!,
            "20C should suit Algerian sardinella (${algeria.pfz}) better than " +
                "NW-Med sardinella (${western.pfz})"
        )
    }

    @Test
    fun `sardinella is gated out of algerian water at the NW-Med optimum`() {
        // 25 C is the NW-Med peak but is above the 24C Maghreb gate.
        val result = PfzEngine.evaluateSpecies(
            profile("sardinella"),
            goodObs(spotId = "algeria-tipaza", sst = 25.0).copy(region = "algeria")
        )
        assertEquals(PfzStatus.UNAVAILABLE, result.status)
        assertTrue(result.blockers.any { it.startsWith("sst:") }, "got ${result.blockers}")
    }

    @Test
    fun `sardinella cited sst band is respected in the western mediterranean`() {
        val result = PfzEngine.evaluateSpecies(
            profile("sardinella"),
            goodObs(spotId = "spain-medes", sst = 25.0).copy(region = "spain")
        )
        assertEquals(PfzStatus.SCOREABLE, result.status)
        assertEquals(100, result.factors.first { it.factor == PfzFactor.SST }.score)
    }

    @Test
    fun `every regional sst override names a known region`() {
        for (p in PfzSpeciesRegistry.all()) {
            for (region in p.sstCByRegion.keys) {
                assertTrue(
                    region in PfzRegions.ALL,
                    "${p.id} declares unknown region '$region'"
                )
            }
        }
    }

    // ------------------------------------------------------------ aggregation

    @Test
    fun `evaluate ranks scoreable species above unavailable ones`() {
        val response = PfzEngine.evaluate(goodObs())
        val statuses = response.species.map { it.status }

        val lastScoreable = statuses.indexOfLast { it == PfzStatus.SCOREABLE }
        val firstNonScoreable = statuses.indexOfFirst { it != PfzStatus.SCOREABLE }
        if (lastScoreable >= 0 && firstNonScoreable >= 0) {
            assertTrue(
                lastScoreable < firstNonScoreable,
                "scoreable species must all sort before gated ones, got $statuses"
            )
        }

        // Both deep crustaceans must be present and gated at a 30 m spot.
        val gated = response.species.filter { it.status == PfzStatus.UNAVAILABLE }
        assertEquals(
            setOf("deep_rose_shrimp", "red_shrimp"),
            gated.map { it.id }.toSet(),
            "only the deep crustaceans should be gated at 30 m"
        )
        assertTrue(gated.all { it.pfz == null }, "gated species must carry no numeric score")

        assertEquals("sicily-ustica-north", response.spotId)
        assertEquals(27, response.species.size)
    }

    @Test
    fun `response carries the coverage caveats`() {
        val response = PfzEngine.evaluate(goodObs())
        assertTrue(
            response.coverageNotes.any { it.contains("Gulf of Lions") },
            "the missing bluefin habitat must be disclosed"
        )
        assertTrue(
            response.coverageNotes.any { it.contains("gradCHL") },
            "the missing chlorophyll gradient must be disclosed"
        )
        assertTrue(
            response.coverageNotes.any { it.contains("BOTTOM") && it.contains("SURFACE") },
            "the surface-vs-bottom temperature gap must be disclosed"
        )
    }

    // -------------------------------------------- unscorable requirements

    @Test
    fun `substrate requirements reach the caller as structured data`() {
        val octopus = PfzEngine.evaluateSpecies(profile("common_octopus"), goodObs())
        val sole = PfzEngine.evaluateSpecies(profile("common_sole"), goodObs())

        assertTrue(
            octopus.unscorableRequirements.any { it.contains("substrate", ignoreCase = true) },
            "octopus hard-bottom requirement must be surfaced, got ${octopus.unscorableRequirements}"
        )
        assertTrue(
            sole.unscorableRequirements.any { it.contains("substrate", ignoreCase = true) },
            "sole sand requirement must be surfaced, got ${sole.unscorableRequirements}"
        )
    }

    @Test
    fun `unscorable requirements are also reported when the species is gated`() {
        // Gating must not erase the disclosure: an unavailable octopus at 300 m
        // still cannot be told apart from one on sand vs rock.
        val deep = PfzEngine.evaluateSpecies(
            profile("common_octopus"),
            goodObs(depthM = 400.0)
        )
        assertEquals(PfzStatus.UNAVAILABLE, deep.status)
        assertTrue(
            deep.unscorableRequirements.isNotEmpty(),
            "a gated result must still disclose what the score was blind to"
        )
    }

    // ------------------------- bottom temperature must not become an SST gate

    @Test
    fun `demersal species with bottom-temperature evidence define no sst band`() {
        // Regression guard. The cited thermal ranges for these species are BOTTOM
        // temperatures measured at depth (hake 11.8-15.0C at 38-312m, Bousquet 2015;
        // Mullus 13.6-23.8C at 28-310m, Machias 1998). The app only measures
        // SURFACE temperature, so promoting those ranges to an sstC gate would
        // fabricate a constraint the data cannot support.
        val bottomOnly = listOf("european_hake", "red_mullet", "striped_red_mullet")

        for (id in bottomOnly) {
            val p = profile(id)
            assertNull(p.sstC, "$id must not define a surface sstC band")
            assertTrue(
                p.unscorableRequirements.any { it.contains("Bottom temperature") },
                "$id must disclose the unscored bottom-temperature requirement"
            )
        }
    }

    @Test
    fun `surface-water species do define a sst band`() {
        // The flip side: a shallow benthic cephalopod genuinely occupies the
        // water the app measures, so omitting its band would be over-cautious.
        val cuttlefish = profile("common_cuttlefish")
        assertNotNull(cuttlefish.sstC, "cuttlefish sits in the photic zone; its 10-30C limit is a surface fact")

        // Below the cited 10C floor the animal stops feeding and dies within days.
        val tooCold = PfzEngine.evaluateSpecies(cuttlefish, goodObs(sst = 6.0))
        assertEquals(PfzStatus.UNAVAILABLE, tooCold.status)
        assertTrue(tooCold.blockers.any { it.startsWith("sst:") }, "got ${tooCold.blockers}")

        val inRange = PfzEngine.evaluateSpecies(cuttlefish, goodObs(sst = 18.0))
        assertEquals(PfzStatus.SCOREABLE, inRange.status)
    }

    // ------------------------------------- gradCHL: the factor we cannot measure

    @Test
    fun `bluefin reports the chlorophyll gradient it cannot measure`() {
        val result = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs())

        assertTrue(
            result.missingFactors.contains(PfzFactor.CHLA_GRADIENT),
            "Druon's actual feeding driver must be named as unmeasured, got ${result.missingFactors}"
        )
    }

    @Test
    fun `a measured gradient raises bluefin confidence`() {
        val bluefin = profile("bluefin_tuna")

        val without = PfzEngine.evaluateSpecies(bluefin, goodObs())
        val with_ = PfzEngine.evaluateSpecies(
            bluefin,
            goodObs().copy(chlaGradient = 0.35)
        )

        assertEquals(PfzStatus.SCOREABLE, with_.status)
        assertTrue(
            with_.confidence > without.confidence,
            "resolving the documented driver must raise confidence: " +
                "${without.confidence} -> ${with_.confidence}"
        )
        // A supplied gradient is SCORED, so it leaves missingFactors. But it has
        // no cited band, so it must show up as low-confidence instead — the
        // caller learns we measured it and that we have no threshold for it.
        assertTrue(
            !with_.missingFactors.contains(PfzFactor.CHLA_GRADIENT),
            "a resolved factor is no longer missing, got ${with_.missingFactors}"
        )
        assertTrue(
            with_.lowConfidenceFactors.contains(PfzFactor.CHLA_GRADIENT),
            "an unbanded gradient must be flagged EXPERT, got ${with_.lowConfidenceFactors}"
        )
    }

    @Test
    fun `the unavailable gradient is disclosed at response level, not just per species`() {
        // It is the only factor in the roster that nothing can score, so it
        // should surface in the response-wide missing list too.
        val response = PfzEngine.evaluate(goodObs())
        assertTrue(
            response.missingFactors.contains(PfzFactor.CHLA_GRADIENT),
            "expected a response-wide chla_gradient gap, got ${response.missingFactors}"
        )
    }

    // --------------------------------------- frontal corridor (Copernicus grids)

    /** A bluefin observation with the corridor measured but sea state withheld. */
    private fun corridorObs(coincidence: FrontCoincidence) = goodObs().copy(
        windSpeedKmh = null,
        swellHeightM = null,
        solunarDayRating = null,
        chlaGradient = 0.4,
        sstGradientCkm = 0.4,
        sstAnomalyC = 1.2,
        frontCoincidence = coincidence,
        forecastDay = 0
    )

    @Test
    fun `a coincident sst and chl front raises confidence over a measured none`() {
        val without = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), corridorObs(FrontCoincidence.NONE))
        val withCoincidence = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), corridorObs(FrontCoincidence.COINCIDENT))

        assertEquals(PfzStatus.SCOREABLE, withCoincidence.status)
        assertTrue(
            withCoincidence.confidence > without.confidence,
            "coincident fronts must corroborate the answer: ${without.confidence} -> ${withCoincidence.confidence}"
        )
    }

    @Test
    fun `stale ocean analysis lowers confidence through the forecast day`() {
        val fresh = PfzEngine.evaluateSpecies(
            profile("bluefin_tuna"),
            corridorObs(FrontCoincidence.NONE).copy(forecastDay = 0)
        )
        val stale = PfzEngine.evaluateSpecies(
            profile("bluefin_tuna"),
            corridorObs(FrontCoincidence.NONE).copy(forecastDay = 3)
        )
        assertTrue(
            stale.confidence < fresh.confidence,
            "persisted data must score weaker than same-day data: ${fresh.confidence} -> ${stale.confidence}"
        )
    }

    @Test
    fun `sst gradient is scored when resolved and disclosed when missing`() {
        val missing = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs())
        assertTrue(
            missing.missingFactors.contains(PfzFactor.SST_GRADIENT),
            "an unresolved thermal front must be named, got ${missing.missingFactors}"
        )

        val scored = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs().copy(sstGradientCkm = 0.3))
        assertFalse(
            scored.missingFactors.contains(PfzFactor.SST_GRADIENT),
            "a resolved gradient is no longer missing"
        )
        assertTrue(
            scored.lowConfidenceFactors.contains(PfzFactor.SST_GRADIENT),
            "an EXPERT-threshold factor must be flagged, got ${scored.lowConfidenceFactors}"
        )
    }

    @Test
    fun `sst anomaly scoring is monotonic in magnitude`() {
        val weak = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs().copy(sstAnomalyC = 0.1))
        val strong = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs().copy(sstAnomalyC = 2.5))

        val weakScore = weak.factors.first { it.factor == PfzFactor.SST_ANOMALY }.score
        val strongScore = strong.factors.first { it.factor == PfzFactor.SST_ANOMALY }.score
        assertTrue(
            strongScore > weakScore,
            "a larger |anomaly| is a stronger dynamic signal: $weakScore -> $strongScore"
        )
    }
}
