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

    /**
     * A corridor-complete summer observation at a 30 m rock.
     *
     * "Complete" now includes the two derived mesoscale fields, because bluefin
     * feeding declares `chla_gradient` and `ssh_anomaly` REQUIRED: without them
     * the result is `insufficient_data` by design, so a fixture that omitted them
     * could not exercise any scoring behaviour at all. Use [bareObs] to test the
     * missing-data paths.
     */
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
        // Strong chlorophyll front, and a NEGATIVE SSH anomaly: both resolved real
        // measurements, so the required factors are satisfied rather than
        // accidentally absent. The sign matters. The cited feeding window is
        // -0.10..0.0 m — cyclonic, depressed sea level is where the fronts and
        // the prey concentrate — so a positive anomaly is outside the evidence
        // and is correctly refused rather than scored.
        chlaGradient = 0.4,
        sshAnomalyM = -0.02,
        // Only the spawning mode weighs this, but the fixture is complete for
        // both modes so a mode switch is the only thing a test varies.
        sstWarmingC = 0.8,
        region = "sicily"
    )

    /** A summer observation with no ocean corridor at all: the missing-data case. */
    private fun bareObs(
        spotId: String = "sicily-ustica-north",
        depthM: Double? = 30.0,
        sst: Double? = 24.0
    ) = PfzObservation(
        spotId = spotId,
        date = july,
        depthM = depthM,
        waterTempC = sst,
        region = "sicily"
    )

    /**
     * A demersal observation on the slope these species actually occupy, with
     * the benthic measurements their cited envelopes are written from.
     *
     * [depthM] defaults into the red shrimp band; the hake's cited gate is much
     * shallower, so its tests pass their own depth rather than inheriting this.
     */
    private fun slopeObs(
        spotId: String = "sicily-ustica-north",
        depthM: Double = 500.0,
        region: String? = "sicily",
        bottomTempC: Double? = 13.7,
        bottomSalinityPsu: Double? = 38.3,
        bottomCurrentMs: Double? = 0.01
    ) = PfzObservation(
        spotId = spotId,
        date = july,
        depthM = depthM,
        depthSource = "ncei_dem",
        waterTempC = 20.0,
        bottomTempC = bottomTempC,
        bottomSalinityPsu = bottomSalinityPsu,
        bottomCurrentMs = bottomCurrentMs,
        mldM = 40.0,
        windSpeedKmh = 8.0,
        region = region
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
            // A mode replaces the base weights outright, so a mode whose weights
            // do not sum to 1 silently rescales the whole species score.
            for (m in p.modes) {
                assertTrue(
                    abs(m.weights.values.sum() - 1.0) < 0.001,
                    "${p.id}/${m.id} weights sum to ${m.weights.values.sum()}"
                )
            }
        }
    }

    @Test
    fun `a default mode does not leave a divergent copy of the base weights`() {
        // The base weights are dead for any species with a default mode, because
        // SpeciesProfile.resolve() takes the mode's weights. Two copies of the
        // same number in one profile is a drift bug waiting to happen, so they
        // are required to agree.
        for (p in PfzSpeciesRegistry.all()) {
            val mode = p.defaultMode() ?: continue
            if (mode.weights.isEmpty()) continue
            assertEquals(
                p.weights,
                mode.weights,
                "${p.id}: base weights differ from its default mode '${mode.id}'"
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
        // Bluefin, little tunny, swordfish, the two clupeiform-adjacent pelagics
        // and the horse mackerel / chub mackerel live in the top layer and range
        // across basins with >2000 m of bottom depth. Bathymetry must never gate
        // them: a 1500 m zone is still valid habitat.
        for (id in listOf(
            "bluefin_tuna", "little_tunny", "swordfish",
            "sardinella", "anchovy", "horse_mackerel", "scomber"
        )) {
            val result = PfzEngine.evaluateSpecies(profile(id), goodObs(depthM = 1500.0))
            assertEquals(PfzStatus.SCOREABLE, result.status, "$id must score over deep water")
            assertNotNull(result.pfz, "$id must carry a numeric score over deep water")
            assertFalse(
                result.blockers.any { it.startsWith("depth:") },
                "$id must not report a depth blocker, got ${result.blockers}"
            )
            // Bathymetry is not a gap for these species: their band describes the
            // water column, not the seabed, so there is nothing to be missing.
            assertFalse(
                result.missingFactors.contains(PfzFactor.DEPTH),
                "$id must not report bathymetry as a missing factor, got ${result.missingFactors}"
            )
            assertTrue(
                result.factors.none { it.factor == PfzFactor.DEPTH },
                "$id must not score bathymetry, got ${result.factors.map { it.factor }}"
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
        for (id in listOf(
            "bluefin_tuna", "little_tunny", "swordfish",
            "sardinella", "anchovy", "horse_mackerel", "scomber"
        )) {
            assertEquals(DepthScope.WATER_COLUMN, profile(id).depth.scope, "$id scope")
        }
        for (id in listOf(
            "sardine", "common_sole", "deep_rose_shrimp", "european_hake", "red_shrimp",
            "common_dentex", "common_pandora", "white_seabream", "gilthead_seabream",
            "dusky_grouper", "white_grouper", "european_seabass", "bogue",
            "red_scorpiofish", "red_mullet", "striped_red_mullet",
            "european_conger", "grey_mullet", "common_cuttlefish", "common_octopus"
        )) {
            assertEquals(DepthScope.BATHYMETRY, profile(id).depth.scope, "$id scope")
        }
    }

    /**
     * A water_column species is not missing a depth measurement.
     *
     * Its band describes depth below the surface, so seafloor depth is not a
     * requirement for it. Reporting depth as a missing factor read as "we lack
     * depth data" for a species that does not care about the seabed, and because
     * the discarded weight shrank the measured fraction of the weight budget it
     * also depressed confidence for a gap that does not exist.
     */
    @Test
    fun `water_column species never report a missing depth factor`() {
        for (id in listOf("bluefin_tuna", "little_tunny", "swordfish", "sardinella")) {
            val result = PfzEngine.evaluateSpecies(profile(id), goodObs())
            assertFalse(
                "depth" in result.missingFactors,
                "$id reported a missing depth factor: ${result.missingFactors}"
            )
        }
    }

    /**
     * No weight budget may leak.
     *
     * A profile that weights a factor the engine cannot evaluate loses that
     * weight from the budget, so the reported factor weights no longer account
     * for the whole score. The demersal profiles were written against
     * bottom-temperature and depth bands and must spend their entire budget on
     * factors they actually define.
     */
    @Test
    fun `a water_column species spends its whole weight budget`() {
        for (id in listOf(
            "little_tunny", "swordfish", "sardinella", "anchovy",
            "horse_mackerel", "scomber"
        )) {
            val result = PfzEngine.evaluateSpecies(profile(id), goodObs())
            assertEquals(PfzStatus.SCOREABLE, result.status, "$id status ${result.blockers}")
            assertTrue(
                result.factors.none { it.factor == "depth" },
                "$id scored the depth factor"
            )
            val total = result.factors.sumOf { it.weight }
            assertTrue(abs(total - 1.0) < 0.001, "$id factor weights sum to $total")
        }
    }

    /**
     * The roster must never ask a water_column species for a depth measurement.
     *
     * Such a profile does not define the depth factor, so the requirement could
     * never be reported unmet and would be satisfied silently. [PfzSpeciesRegistry]
     * rejects this at load time; this asserts the shipped roster is clean.
     */
    @Test
    fun `no water_column species requires the depth factor`() {
        for (p in PfzSpeciesRegistry.all()) {
            if (p.depth.scope != DepthScope.WATER_COLUMN) continue
            assertTrue(
                "depth" !in p.requiredFactors,
                "${p.id} declares water_column scope but requires depth"
            )
            assertTrue(
                (p.weights["depth"] ?: 0.0) == 0.0,
                "${p.id} declares water_column scope but weights depth " +
                    "${p.weights["depth"]}"
            )
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
        // sardine, not bluefin: bluefin's season now lives in its MODES, so
        // patching the base season would test nothing.
        val sardine = profile("sardine").copy(
            season = SeasonSpec(peakMonths = listOf(6, 7, 8), closedMonths = listOf(2))
        )
        val winter = PfzEngine.evaluateSpecies(sardine, bareObs().copy(date = LocalDate.of(2026, 2, 10)))
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
        // Bluefin feeding, with its two REQUIRED factors resolved, but no wind,
        // swell, chl or solunar. The optional gaps renormalize; the required ones
        // would have returned insufficient_data instead.
        val partial = goodObs(sst = 18.0).copy(
            windSpeedKmh = null,
            swellHeightM = null,
            solunarDayRating = null,
            moonPhase = null,
            chlorophyllMgM3 = null
        )
        val result = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), partial)

        assertEquals(PfzStatus.SCOREABLE, result.status)
        val sum = result.factors.sumOf { it.weight }
        assertTrue(abs(sum - 1.0) < 0.0001, "renormalized weights sum to $sum")

        assertTrue(result.missingFactors.contains(PfzFactor.WIND))
        assertTrue(result.missingFactors.contains(PfzFactor.SWELL))
        assertTrue(result.missingFactors.contains(PfzFactor.SOLUNAR))
        assertTrue(result.missingFactors.contains(PfzFactor.CHL))

        // Every factor that did resolve is at or near its best for this cell, so
        // nothing was scored as a pessimistic stand-in for the gaps. The SSH
        // anomaly of -0.02 m sits a fifth of the way up the cited -0.10..0.0
        // window, so it scores 80 by design — the assertion is a floor, not an
        // exact number, so it keeps holding if a band is re-cited.
        assertTrue(
            result.factors.all { it.score >= 80 },
            "a resolved factor must not be scored as a placeholder: ${result.factors}"
        )
    }

    @Test
    fun `a required factor that did not resolve refuses the whole score`() {
        // The hake's cited habitat equation is a product, so this is the case the
        // mechanism exists for: depth + chlorophyll are known, bottom temperature
        // is not, and the answer must not be a number.
        val hake = profile("european_hake")
        val withoutBottomT = slopeObs(depthM = 300.0, bottomTempC = null)
        val result = PfzEngine.evaluateSpecies(hake, withoutBottomT)

        assertEquals(PfzStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.pfz, "a partial product model must not produce a score")
        assertTrue(
            result.blockers.any { it.contains(PfzFactor.BOTTOM_TEMP) && it.contains("required") },
            "the blocker must name the required factor, got ${result.blockers}"
        )
        assertTrue(
            result.missingFactors.contains(PfzFactor.BOTTOM_TEMP),
            "and the gap must be visible in missingFactors, got ${result.missingFactors}"
        )
    }

    @Test
    fun `every declared required factor is genuinely load-bearing`() {
        for (p in PfzSpeciesRegistry.all()) {
            for (factor in p.requiredFactors) {
                val resolution = p.resolve()
                assertTrue(
                    resolution.definesFactor(factor),
                    "${p.id} requires '$factor' but declares no band for it, so it " +
                        "could never resolve and every score would be insufficient_data"
                )
                assertTrue(
                    resolution.normalizedWeights().containsKey(factor),
                    "${p.id} requires '$factor' but assigns it no weight, so requiring " +
                        "it would be a veto on a factor that does not affect the score"
                )
            }
            for (m in p.modes) {
                for (factor in m.requiredFactors) {
                    assertTrue(
                        p.resolve(m.id).definesFactor(factor),
                        "${p.id}/${m.id} requires '$factor' but declares no band for it"
                    )
                }
            }
        }
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
    fun `a position classifies when the id carries no known prefix`() {
        // Offshore corridor cells have no spot id at all, so a position has to be
        // enough on its own.
        assertEquals(PfzRegions.WESTERN, PfzRegions.classify("offshore-91", lat = 38.0, lon = 4.0))
        assertEquals(PfzRegions.MAGHREB, PfzRegions.classify("offshore-92", lat = 36.0, lon = 5.0))
        assertEquals(PfzRegions.MAGHREB, PfzRegions.classify("offshore-93", lat = 33.0, lon = 12.0))
        assertEquals(PfzRegions.CENTRAL, PfzRegions.classify("offshore-94", lat = 37.0, lon = 14.0))
        assertEquals(PfzRegions.EASTERN, PfzRegions.classify("offshore-95", lat = 35.0, lon = 25.0))
    }

    @Test
    fun `positions outside the mediterranean are not classified`() {
        // The classifier must refuse rather than guess: the Atlantic and the Black
        // Sea are not Mediterranean even though the fallbacks would place them.
        assertNull(PfzRegions.classify(null, lat = 44.0, lon = -10.0), "open Atlantic")
        assertNull(PfzRegions.classify(null, lat = 45.0, lon = 30.0), "Black Sea")
        assertNull(PfzRegions.classify(null, lat = 20.0, lon = 15.0), "Sahara")
        assertNull(PfzRegions.classify(null, lat = 40.0), "latitude without a longitude")
        assertNull(PfzRegions.classify(null, lon = 5.0), "longitude without a latitude")
    }

    @Test
    fun `an explicit named region outranks both the id prefix and the position`() {
        // Sicily is central, but the water off its south coast is Tunisian and
        // the Strait of Gibraltar is not Maghreb. The name is the curated answer.
        assertEquals(PfzRegions.MAGHREB, PfzRegions.classify("sicily-ustica-north", region = "maghreb"))
        assertEquals(
            PfzRegions.EASTERN,
            PfzRegions.classify("sicily-ustica-north", lat = 37.5, lon = 12.0, region = "eastern_med")
        )
        // An unknown name falls through to the prefix/position chain rather than
        // becoming a region of its own.
        assertEquals(
            PfzRegions.CENTRAL,
            PfzRegions.classify("sicily-ustica-north", region = "atlantis")
        )
    }

    @Test
    fun `a known spot id outranks a disagreeing position`() {
        // Guards against the Alboran-Sardinian back-merge: a box that cuts
        // 8E would put the Sardinian approaches in the east, but the curated
        // prefix is authoritative.
        assertEquals(
            PfzRegions.WESTERN,
            PfzRegions.classify("spain-medes", lat = 41.0, lon = 8.0)
        )
        assertEquals(
            PfzRegions.CENTRAL,
            PfzRegions.classify("sicily-ustica-north", lat = 36.0, lon = 2.0)
        )
    }

    @Test
    fun `a partial position is not classified from half the information`() {
        assertNull(PfzRegions.classify("offshore-96", lat = 38.0))
        assertNull(PfzRegions.classify("offshore-96", lon = 4.0))
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

        // The deep crustaceans and the hake must all be present and gated at a
        // 30 m spot. Hake is now in that set for a cited reason: Colloca et al.
        // (2014) fit its habitat as depth (60-385 m) x bottom temperature, and
        // every one of the 67 inventory spots is a nominal 20-30 m coastal site,
        // so the roster has no ground to score hake against.
        val gated = response.species.filter { it.status == PfzStatus.UNAVAILABLE }
        assertEquals(
            setOf("deep_rose_shrimp", "red_shrimp", "european_hake"),
            gated.map { it.id }.toSet(),
            "only the depth-gated species should be gated at 30 m"
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
            response.coverageNotes.any { it.contains("deepest non-masked level") },
            "the surface-vs-bottom sampling caveat must be disclosed"
        )
        assertTrue(
            response.coverageNotes.any { it.contains("DERIVED by Shaka") },
            "the derived — not published — mesoscale fields must be disclosed"
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
    fun `demersal species with bottom-temperature evidence score bottom temperature, not sst`() {
        // Regression guard. The cited thermal ranges for these species are BOTTOM
        // temperatures measured at depth (hake 11.8-15.0C at 38-312m, Bousquet 2015;
        // Mullus 13.6-23.8C at 28-310m, Machias 1998). The app now measures bottom
        // temperature directly, so the hake's range moves onto `bottomTempC`. It
        // must still NOT be promoted to an `sstC` gate: the two are different
        // water masses and a surface reading cannot stand in for the slope.
        val hake = profile("european_hake")
        assertNull(hake.sstC, "hake must not define a surface sstC band")
        assertNotNull(
            hake.bottomTempC,
            "hake's cited 11.8-15.0C must now be scored against measured bottom temperature"
        )

        // With the required bottom temperature absent, the hake refuses rather
        // than falling back to any surface reading.
        val noBottom = PfzEngine.evaluateSpecies(hake, slopeObs(depthM = 300.0, bottomTempC = null))
        assertEquals(PfzStatus.INSUFFICIENT_DATA, noBottom.status)
        assertTrue(noBottom.blockers.any { it.contains("bottom_temp") }, "got ${noBottom.blockers}")

        // Wrong bottom temperature is a real gate, not a missing measurement.
        val tooWarm = PfzEngine.evaluateSpecies(hake, slopeObs(depthM = 300.0, bottomTempC = 19.0))
        assertEquals(PfzStatus.UNAVAILABLE, tooWarm.status)
        assertTrue(tooWarm.blockers.any { it.contains("bottom_temp") }, "got ${tooWarm.blockers}")

        // The two mullets now carry their OWN bottom band rather than having no
        // band at all. The regression this still guards is inheritance: they must
        // not take the hake's CITED numbers, and must not be promoted to a surface
        // sstC gate, because a slope reading still cannot come from the surface.
        for (id in listOf("red_mullet", "striped_red_mullet")) {
            val p = profile(id)
            assertNull(p.sstC, "$id must not define a surface sstC band")
            assertNotNull(p.bottomTempC, "$id must carry its own bottom band")
            assertEquals(
                FactorConfidence.EXPERT,
                p.bottomTempC!!.conf,
                "$id must not present an uncited bottom envelope as cited"
            )
            assertTrue(
                p.bottomTempC!!.gateMin > hake.bottomTempC!!.gateMin,
                "$id must not inherit the hake's cited bottom band"
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
    fun `an unresolved chlorophyll gradient refuses the bluefin feeding score`() {
        // The gradient is the documented feeding driver (Druon et al. 2011) and is
        // REQUIRED, so the honest answer at a spot with no resolved L4 grid is a
        // refusal that names the driver — not a temperature lookup.
        val result = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), bareObs())

        assertEquals(PfzStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.pfz, "a bluefin score without gradCHL would be a different model")
        assertTrue(
            result.missingFactors.contains(PfzFactor.CHLA_GRADIENT),
            "Druon's actual feeding driver must be named as unmeasured, got ${result.missingFactors}"
        )
    }

    @Test
    fun `a measured gradient is scored, not merely disclosed`() {
        val bluefin = profile("bluefin_tuna")
        val weak = PfzEngine.evaluateSpecies(bluefin, goodObs().copy(chlaGradient = 0.0))
        val strong = PfzEngine.evaluateSpecies(bluefin, goodObs().copy(chlaGradient = 0.8))

        assertEquals(PfzStatus.SCOREABLE, strong.status)
        // It is now a required, weighted factor, so a stronger front must actually
        // move the score rather than only raising confidence.
        assertTrue(
            strong.pfz!! > weak.pfz!!,
            "a stronger chlorophyll front must score higher: ${weak.pfz} -> ${strong.pfz}"
        )
        assertTrue(
            !strong.missingFactors.contains(PfzFactor.CHLA_GRADIENT),
            "a resolved factor is no longer missing, got ${strong.missingFactors}"
        )
    }

    @Test
    fun `a missing required factor is disclosed at response level too`() {
        // A spot with no corridor at all: the gaps must surface in the
        // response-wide missing list, not only inside the failing species.
        val response = PfzEngine.evaluate(bareObs())
        assertTrue(
            response.missingFactors.contains(PfzFactor.CHLA_GRADIENT),
            "expected a response-wide chla_gradient gap, got ${response.missingFactors}"
        )
        assertTrue(
            response.species.any { it.status == PfzStatus.INSUFFICIENT_DATA },
            "the roster must not report a confident answer with no ocean data"
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
        // Now a unit test of the evaluator, not of bluefin: the feeding model
        // weights chla_gradient and ssh_anomaly, which are the cited front
        // signals, so it deliberately does not also weight the surface anomaly.
        val weak = (PfzFactors.sstAnomaly(bareObs(sst = 20.0).copy(sstAnomalyC = 0.1)) as FactorOutcome.Scored).score
        val strong = (PfzFactors.sstAnomaly(bareObs(sst = 20.0).copy(sstAnomalyC = 2.5)) as FactorOutcome.Scored).score

        assertTrue(
            strong > weak,
            "a larger |anomaly| is a stronger dynamic signal: $weak -> $strong"
        )
    }

    // ------------------------------------------------------- required: ssh anomaly

    @Test
    fun `a missing ssh anomaly refuses the bluefin feeding score`() {
        // SSH is the second of bluefin feeding's two required factors: the
        // derived anomaly is the mesoscale signal behind Druon's frontal
        // aggregation, so a warm SST alone must not produce a feeding score.
        val noSsh = PfzEngine.evaluateSpecies(
            profile("bluefin_tuna"),
            goodObs().copy(sshAnomalyM = null)
        )
        assertEquals(PfzStatus.INSUFFICIENT_DATA, noSsh.status)
        assertTrue(
            noSsh.blockers.any { it.contains(PfzFactor.SSH_ANOMALY) && it.contains("required") },
            "got ${noSsh.blockers}"
        )
    }

    @Test
    fun `bluefin refuses a negative ssh anomaly it cannot explain`() {
        // Outside the cited -0.10..0.0 m window the animal is on the wrong side
        // of the feature. That is a known out-of-range value, not missing data.
        val result = PfzEngine.evaluateSpecies(
            profile("bluefin_tuna"),
            goodObs().copy(sshAnomalyM = -0.25)
        )
        assertEquals(PfzStatus.UNAVAILABLE, result.status)
        assertTrue(result.blockers.any { it.contains(PfzFactor.SSH_ANOMALY) }, "got ${result.blockers}")
    }

    // ------------------------------------------------------------- size classes

    @Test
    fun `an unparameterised size class is refused rather than borrowed`() {
        // Bluefin is written for large fish only. Scoring a 20 kg juvenile with
        // the large-fish thresholds would be a fabricated number.
        val small = PfzEngine.evaluateSpecies(
            profile("bluefin_tuna"),
            goodObs(),
            sizeClass = "small"
        )
        assertEquals(PfzStatus.INSUFFICIENT_DATA, small.status)
        assertNull(small.pfz, "an unparameterised size class must not inherit another class's bands")
        assertTrue(
            small.blockers.any { it.contains("small") },
            "the blocker must name the size class, got ${small.blockers}"
        )
    }

    @Test
    fun `the parameterised size class scores and is disclosed`() {
        val large = PfzEngine.evaluateSpecies(profile("bluefin_tuna"), goodObs(), sizeClass = "large")
        assertEquals(PfzStatus.SCOREABLE, large.status)
        assertEquals("large", large.sizeClass, "the applied size class must reach the caller")

        val unknown = PfzEngine.evaluateSpecies(
            profile("bluefin_tuna"),
            goodObs(),
            sizeClass = "juvenile"
        )
        assertEquals(PfzStatus.INSUFFICIENT_DATA, unknown.status)
    }

    // ------------------------------------------------------------------- modes

    @Test
    fun `bluefin feeding and spawning invert the chlorophyll requirement`() {
        // The single most important thing the mode switch gets right. The two
        // cited windows are near-disjoint: feeding is parameterised over
        // 0.02-20 mg/m3 with a 0.2-1.5 ideal, spawning over 0-0.15 with
        // lower_better. So a rich cell is not merely worse for spawning, it is
        // outside the documented envelope entirely.
        val p = profile("bluefin_tuna")
        val rich = goodObs(sst = 18.0, chl = 0.9)

        val feeding = PfzEngine.evaluateSpecies(p, rich, mode = "feeding")
        assertEquals(PfzStatus.SCOREABLE, feeding.status)

        val spawningInRichWater = PfzEngine.evaluateSpecies(p, rich, mode = "spawning")
        assertEquals(
            PfzStatus.UNAVAILABLE,
            spawningInRichWater.status,
            "0.9 mg/m3 is outside the cited 0-0.15 spawning window"
        )
        assertTrue(
            spawningInRichWater.blockers.any { it.startsWith("chl:") },
            "got ${spawningInRichWater.blockers}"
        )

        // Inside the narrow overlap the preference really does invert: the same
        // oligotrophic cell scores the chl factor higher for spawning.
        val oligotrophic = goodObs(sst = 18.0, chl = 0.08)
        val f = PfzEngine.evaluateSpecies(p, oligotrophic, mode = "feeding")
        val s = PfzEngine.evaluateSpecies(p, oligotrophic, mode = "spawning")
        assertEquals(PfzStatus.SCOREABLE, f.status)
        assertEquals(PfzStatus.SCOREABLE, s.status)
        assertTrue(
            s.factors.first { it.factor == PfzFactor.CHL }.score >
                f.factors.first { it.factor == PfzFactor.CHL }.score,
            "a chlorophyll-poor cell must favour spawning on the chl factor"
        )
    }

    @Test
    fun `a known mode is used and an unknown mode falls back to the default`() {
        val p = profile("bluefin_tuna")
        assertEquals("feeding", PfzEngine.evaluateSpecies(p, goodObs()).mode)
        assertEquals(
            "spawning",
            PfzEngine.evaluateSpecies(p, goodObs(sst = 18.0, chl = 0.08), mode = "spawning").mode
        )
        // An unrecognised mode is a client typo, not a reason to refuse the fish.
        val typo = PfzEngine.evaluateSpecies(p, goodObs(), mode = "spawnning")
        assertEquals("feeding", typo.mode, "an unknown mode must fall back to the default")
    }

    @Test
    fun `spawning declares sst warming as required`() {
        // Corridor-wide warming is the cue the stock responds to, so a spot
        // without a resolved dSST30 cannot be called a spawning ground. Kept
        // inside the cited spawning chlorophyll window so the missing factor, not
        // the chl gate, is what the test exercises.
        val cold = PfzEngine.evaluateSpecies(
            profile("bluefin_tuna"),
            goodObs(sst = 18.0, chl = 0.08).copy(sstWarmingC = null),
            mode = "spawning"
        )
        assertEquals(PfzStatus.INSUFFICIENT_DATA, cold.status)
        assertTrue(
            cold.blockers.any { it.contains(PfzFactor.SST_WARMING) && it.contains("required") },
            "got ${cold.blockers}"
        )
    }

    // ----------------------------------------------------------- region exclusions

    @Test
    fun `red shrimp is excluded from the western mediterranean`() {
        // The cited thermal and salinity envelope is Levantine Intermediate
        // Water, which is not formed in the Alboran Sea. A numeric score there
        // would contradict the source, so it is a hard exclusion.
        val western = PfzEngine.evaluateSpecies(
            profile("red_shrimp"),
            slopeObs(spotId = "spain-medes", region = "spain")
        )
        assertEquals(PfzStatus.UNAVAILABLE, western.status)
        assertNull(western.pfz)
        assertTrue(
            western.blockers.any { it.contains("western_med") },
            "the exclusion must name the region, got ${western.blockers}"
        )
    }

    @Test
    fun `red shrimp is excluded from the maghreb`() {
        val maghreb = PfzEngine.evaluateSpecies(
            profile("red_shrimp"),
            slopeObs(spotId = "algeria-tipaza", region = "algeria")
        )
        assertEquals(PfzStatus.UNAVAILABLE, maghreb.status)
        assertTrue(
            maghreb.blockers.any { it.contains("maghreb") },
            "the exclusion must name the region, got ${maghreb.blockers}"
        )
    }

    @Test
    fun `red shrimp scores inside its cited slope habitat`() {
        val inHabitat = PfzEngine.evaluateSpecies(profile("red_shrimp"), slopeObs())
        assertEquals(PfzStatus.SCOREABLE, inHabitat.status)
        assertNotNull(inHabitat.pfz)
        assertEquals(PfzRegions.CENTRAL, inHabitat.region, "the applied region must reach the caller")

        val tooShallow = PfzEngine.evaluateSpecies(profile("red_shrimp"), slopeObs().copy(depthM = 30.0))
        assertEquals(PfzStatus.UNAVAILABLE, tooShallow.status)

        val tooWarm = PfzEngine.evaluateSpecies(profile("red_shrimp"), slopeObs(bottomTempC = 20.0))
        assertEquals(PfzStatus.UNAVAILABLE, tooWarm.status)

        val tooFresh = PfzEngine.evaluateSpecies(profile("red_shrimp"), slopeObs(bottomSalinityPsu = 36.0))
        assertEquals(PfzStatus.UNAVAILABLE, tooFresh.status)
    }

    @Test
    fun `red shrimp requires the slope measurements its envelope is written from`() {
        for (dropped in listOf(
            slopeObs(depthM = 300.0, bottomTempC = null),
            slopeObs(bottomSalinityPsu = null)
        )) {
            val result = PfzEngine.evaluateSpecies(profile("red_shrimp"), dropped)
            assertEquals(PfzStatus.INSUFFICIENT_DATA, result.status)
            assertTrue(result.blockers.any { it.contains("required") }, "got ${result.blockers}")
        }
    }

    // ----------------------------------------------------- regional SST overrides

    @Test
    fun `a regional band is resolved from the observation and changes the verdict`() {
        // The eastern Aegean band is both cooler (12-24 C) and shallower
        // (10-110 m) than the basin-wide one, so the same cell answers
        // differently in the two basins. That is the mechanism working, not a
        // duplicated global number with extra words attached.
        val sardine = profile("sardine")
        val cell = goodObs(sst = 25.0, chl = 0.9)

        val central = PfzEngine.evaluateSpecies(sardine, cell, region = PfzRegions.CENTRAL)
        val eastern = PfzEngine.evaluateSpecies(sardine, cell, region = PfzRegions.EASTERN)

        assertEquals(PfzStatus.SCOREABLE, central.status, "25C is inside the basin-wide 10-28C gate")
        assertEquals(
            PfzStatus.UNAVAILABLE,
            eastern.status,
            "25C is above the cited Aegean 24C gate"
        )
        assertTrue(eastern.blockers.any { it.startsWith("sst:") }, "got ${eastern.blockers}")
    }

    @Test
    fun `sardine is gated by its cited western band at the basin-wide optimum`() {
        // Tugores et al. 2011 Spanish autumn: 14-18.5 C. The basin-wide band is
        // 10-28 C, so a 25 C cell scores well basin-wide and is refused in the
        // west. That inversion is the whole reason the override exists.
        val p = profile("sardine")
        assertTrue(
            p.byRegion.getValue(PfzRegions.WESTERN).sstC!!.gateMax <
                p.sstC!!.gateMax,
            "the western override must actually be narrower than the basin-wide gate"
        )

        val tooWarm = PfzEngine.evaluateSpecies(
            p,
            goodObs(spotId = "spain-medes", sst = 25.0).copy(region = "spain")
        )
        assertEquals(PfzStatus.UNAVAILABLE, tooWarm.status)

        val inBand = PfzEngine.evaluateSpecies(
            p,
            goodObs(spotId = "spain-medes", sst = 17.0).copy(region = "spain")
        )
        assertEquals(PfzStatus.SCOREABLE, inBand.status)
        assertEquals(
            100,
            inBand.factors.first { it.factor == PfzFactor.SST }.score,
            "17C is the middle of the cited 16-18 C ideal"
        )
    }

    @Test
    fun `an uncited regional band is disclosed rather than invented`() {
        // There is no Maghreb override for sardine. The literature behind this
        // profile does not resolve one, so the gap has to be visible to the
        // caller instead of quietly falling back to basin-wide bands as if they
        // were cited for that water.
        val sardine = profile("sardine")
        assertFalse(
            sardine.byRegion.containsKey(PfzRegions.MAGHREB),
            "no Maghreb band is cited, so none may be declared"
        )
        assertTrue(
            sardine.note!!.contains("NO maghreb override"),
            "the missing Maghreb band must be disclosed in the note, got ${sardine.note}"
        )
        // The disclosure has to survive all the way to the caller, or naming the
        // gap here accomplishes nothing.
        val result = PfzEngine.evaluateSpecies(
            sardine,
            goodObs(spotId = "algeria-tipaza", sst = 20.0).copy(region = "algeria")
        )
        assertEquals(PfzStatus.SCOREABLE, result.status)
        assertEquals(PfzRegions.MAGHREB, result.region, "the band actually applied must be named")
        assertTrue(
            result.note!!.contains("maghreb", ignoreCase = true),
            "the caller must see that the Algerian score used an uncited band: ${result.note}"
        )
    }

    @Test
    fun `every regional override names a known region`() {
        for (p in PfzSpeciesRegistry.all()) {
            for (region in p.sstCByRegion.keys) {
                assertTrue(
                    region in PfzRegions.ALL,
                    "${p.id} sstCByRegion declares unknown region '$region'"
                )
            }
            for ((region, override) in p.byRegion) {
                assertTrue(
                    region in PfzRegions.ALL,
                    "${p.id} byRegion declares unknown region '$region'"
                )
                // An exclusion and a band for the same region cannot both be
                // declared: one says the species is absent, the other that it is
                // present, and the engine would silently prefer the band.
                assertTrue(
                    !(override.unavailable != null && override.sstC != null),
                    "${p.id} byRegion.$region is both declared absent and given an sstC band"
                )
            }
        }
    }
}
