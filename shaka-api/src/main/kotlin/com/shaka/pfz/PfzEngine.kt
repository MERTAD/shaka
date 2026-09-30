package com.shaka.pfz

import com.shaka.scoring.ShakaScorer
import kotlin.math.roundToInt

/**
 * PFZ scoring engine.
 *
 * Evaluates every roster species against one [PfzObservation]. The engine has
 * no notion of latitude, longitude or geometry — it scores an already-resolved
 * bundle of measurements. That is the extension point for the v2 offshore
 * front-finder: it will produce a different [PfzObservation], not a different
 * scoring path.
 *
 * Honesty guarantees implemented here:
 *  1. A gated species returns [PfzStatus.UNAVAILABLE] with `pfz = null`. It is
 *     never converted into a small number.
 *  2. A missing factor is dropped and the remaining weights are renormalized,
 *     UNLESS the profile declares it in `requiredFactors`, in which case the
 *     whole result is [PfzStatus.INSUFFICIENT_DATA] with `pfz = null`.
 *  3. If no factor can be scored, the result is [PfzStatus.INSUFFICIENT_DATA]
 *     with `pfz = null`, not a guess.
 *  4. Confidence falls with data coverage, with EXPERT-tier thresholds, and with
 *     forecast distance, and is reported to the caller so a weak score looks weak.
 *
 * On (2): Colloca et al. (2014) fit hake habitat as a product,
 * `Trophic x Depth x SBT x SBCmax`, where any term at zero makes the cell zero.
 * A 70/100 assembled from depth and chlorophyll because bottom temperature was
 * unavailable is not a weaker hake score, it is a different model. The same
 * reasoning applies to bluefin, whose feeding model needs the chlorophyll-a
 * gradient and the SSH anomaly to be separable from a plain temperature lookup.
 */
object PfzEngine {

    /**
     * Structural gaps in what the app can currently represent. Surfaced in every
     * response so a poor bluefin score in the open Mediterranean is
     * attributable to the missing Balearic / Gulf of Lions spots rather than
     * looking like a bad forecast.
     */
    val COVERAGE_NOTES: List<String> = listOf(
        "Spot inventory has no North Africa, Balearic Islands or Gulf of Lions locations. " +
            "The Gulf of Lions is a primary Atlantic bluefin feeding and spawning ground " +
            "(Druon et al. 2011, 2016), so bluefin scores in this build are systematically " +
            "pessimistic across the basin. Every one of the 67 Mediterranean spots in the " +
            "inventory is also a nominal 20-30 m coastal site, so the deep-slope profiles have " +
            "no ground to score against until offshore spots exist.",
        "Bluefin tuna, little tunny and swordfish are surface-layer species: their depth " +
            "band is measured below the surface, not against the seafloor. Bathymetry never " +
            "enters their score, so they are scored over deep basins (e.g. >2000 m of bottom " +
            "depth) wherever a Copernicus SST/front grid resolves.",
        "Bottom temperature, bottom salinity, bottom current and mixed layer thickness are " +
            "real Copernicus physics measurements, and the demersal profiles are written " +
            "against them. They are scored from the deepest non-masked level of each product, " +
            "which on the open abyssal plain is 800-1400 m down — correct for a slope species, " +
            "but a coastal 25 m spot is sampled almost in the top few metres of the column.",
        "Sea surface height anomaly and 30-day SST warming are DERIVED by Shaka, not " +
            "published: the SSH dataset's static fields carry no mean dynamic topography, so " +
            "SSHa is daily sea level minus the monthly mean of the same product. The anomaly " +
            "spans only 18 mm across a 9 km box (276 mm across 5x8 degrees), so it behaves as " +
            "a regional gate rather than a local one.",
        "Chlorophyll-a gradient (gradCHL) is the documented driver of bluefin feeding habitat " +
            "(Druon et al. 2011) and is computed from Copernicus Med L4 grids when they resolve. " +
            "A spot whose grid did not resolve reports it missing instead of scoring a " +
            "concentration proxy. Because the bluefin feeding model requires it, a bluefin " +
            "feeding score without it is insufficient_data, not a temperature lookup.",
        "No bottom-type (substrate) data source. Hard substrate for common octopus spawning and " +
            "sand for common sole are real requirements the score cannot see."
    )

    /** Aggregate every roster species against one observation. */
    fun evaluate(
        obs: PfzObservation,
        profiles: List<SpeciesProfile> = PfzSpeciesRegistry.all(),
        mode: String? = null,
        sizeClass: String? = null,
        region: String? = null
    ): PfzResponse {
        val effectiveRegion = region ?: regionOf(obs)
        val results = profiles.map { evaluateSpecies(it, obs, mode, sizeClass, effectiveRegion) }
        return PfzResponse(
            spotId = obs.spotId,
            date = obs.date.toString(),
            confidence = results.maxOfOrNull { it.confidence } ?: 0,
            species = results.sortedWith(
                compareByDescending<PfzSpeciesResult> { it.status == PfzStatus.SCOREABLE }
                    .thenByDescending { it.pfz ?: -1 }
            ),
            missingFactors = sharedMissingFactors(results),
            coverageNotes = COVERAGE_NOTES + PfzSpeciesRegistry.loadErrors.map { "roster: $it" }
        )
    }

    /**
     * Score a single species.
     *
     * [region] is the macro-basin used for geographic overrides. When null it is
     * derived from the observation by [regionOf], so a caller that already knows
     * the basin may pass it directly and a caller that only has lat/lon (the
     * offshore path) need not care.
     */
    fun evaluateSpecies(
        profile: SpeciesProfile,
        obs: PfzObservation,
        mode: String? = null,
        sizeClass: String? = null,
        region: String? = null
    ): PfzSpeciesResult {
        val resolved = profile.resolve(mode, sizeClass, region ?: regionOf(obs))
        val outcomes = evaluateAllFactors(resolved, obs)
        val missing = missingKeys(outcomes)

        // A size class the applied mode was not parameterised for is refused
        // rather than silently scored with another class's numbers. Druon et al.
        // (2016) fit small fish (<=25 kg) to a 13.0-26.1 C envelope and large fish
        // (>25 kg) to 7.5-24.0 C; answering a `?sizeClass=small` request with the
        // large-fish band would be a confident wrong number, not a partial one.
        if (sizeClass != null) {
            val supported = resolved.modeSpec?.sizeClasses.orEmpty()
            if (supported.isNotEmpty() && sizeClass !in supported) {
                return result(
                    profile, resolved, PfzStatus.INSUFFICIENT_DATA,
                    blockers = listOf(
                        "size class '$sizeClass' is not parameterised for ${profile.id}" +
                            " mode '${resolved.mode}' (supported: ${supported.joinToString(", ")})"
                    ),
                    missingFactors = missing
                )
            }
        }

        // A geographic exclusion outranks everything else: the species does not
        // occur there, so no measurement could make this a score. The blocker
        // leads with the region slug because the caller has to know *where* the
        // refusal applies, and a prose reason alone cannot be matched against a
        // map.
        resolved.regionExclusion?.let { (slug, reason) ->
            return result(
                profile, resolved, PfzStatus.UNAVAILABLE,
                blockers = listOf("region $slug: $reason"), missingFactors = missing
            )
        }

        val blockers = outcomes.values.filterIsInstance<FactorOutcome.Gated>().map { it.reason }
        if (blockers.isNotEmpty()) {
            return result(
                profile, resolved, PfzStatus.UNAVAILABLE,
                blockers = blockers, missingFactors = missing
            )
        }

        // A declared requirement we could not measure ends scoring. This is
        // checked before the weighted sum so no partial sum can leak out.
        val unmetRequirements = resolved.requiredFactors
            .filter { outcomes[it] is FactorOutcome.Missing }
            .sorted()
        if (unmetRequirements.isNotEmpty()) {
            return result(
                profile, resolved, PfzStatus.INSUFFICIENT_DATA,
                blockers = unmetRequirements.map { factor ->
                    val reason = (outcomes[factor] as FactorOutcome.Missing).reason
                    buildString {
                        append("$factor is required to score ${profile.id}")
                        resolved.mode?.let { append(" in '$it' mode") }
                        append(": $reason")
                    }
                },
                missingFactors = missing
            )
        }

        val scored = outcomes.filterValues { it is FactorOutcome.Scored }
            .mapValues { (_, v) -> v as FactorOutcome.Scored }
        if (scored.isEmpty()) {
            return result(
                profile, resolved, PfzStatus.INSUFFICIENT_DATA, missingFactors = missing
            )
        }

        // Renormalize over the factors we could actually score. A factor we
        // cannot measure must neither inflate nor deflate the result.
        val definedWeights = resolved.normalizedWeights()
        val scoredWeightTotal = definedWeights
            .filterKeys { scored.containsKey(it) }
            .values
            .sum()

        if (scoredWeightTotal <= 0.0) {
            return result(
                profile, resolved, PfzStatus.INSUFFICIENT_DATA, missingFactors = missing
            )
        }

        val factorScores = scored.map { (factor, outcome) ->
            FactorScore(
                factor = factor,
                score = outcome.score,
                weight = (definedWeights[factor] ?: 0.0) / scoredWeightTotal,
                confidence = outcome.confidence
            )
        }

        val pfz = factorScores.sumOf { it.score * it.weight }.roundToInt().coerceIn(0, 100)

        val lowConfidence = factorScores
            .filter { it.confidence != FactorConfidence.CITED }
            .map { it.factor }

        return result(
            profile = profile,
            resolved = resolved,
            status = PfzStatus.SCOREABLE,
            pfz = pfz,
            confidence = confidenceFor(obs, scoredWeightTotal, lowConfidence, scored.size),
            factors = factorScores.sortedByDescending { it.score * it.weight },
            drivers = factorScores
                .filter { it.score >= 80 }
                .sortedByDescending { it.score * it.weight }
                .take(3)
                .map { it.factor },
            missingFactors = missing,
            lowConfidenceFactors = lowConfidence
        )
    }

    /**
     * Single construction point for a species result, so the mode, size class and
     * region that produced it can never be omitted from one branch of the engine.
     */
    private fun result(
        profile: SpeciesProfile,
        resolved: ResolvedProfile,
        status: PfzStatus,
        pfz: Int? = null,
        confidence: Int = 0,
        factors: List<FactorScore> = emptyList(),
        drivers: List<String> = emptyList(),
        blockers: List<String> = emptyList(),
        missingFactors: List<String> = emptyList(),
        lowConfidenceFactors: List<String> = emptyList()
    ) = PfzSpeciesResult(
        id = profile.id,
        commonName = profile.commonName,
        scientificName = profile.scientificName,
        guild = profile.guild,
        status = status,
        pfz = pfz,
        confidence = confidence,
        mode = resolved.mode,
        sizeClass = resolved.sizeClass,
        region = resolved.region,
        factors = factors,
        drivers = drivers,
        blockers = blockers,
        missingFactors = missingFactors,
        lowConfidenceFactors = lowConfidenceFactors,
        unscorableRequirements = profile.unscorableRequirements,
        note = profile.note
    )

    /**
     * Run every factor the resolved profile actually has a requirement for.
     *
     * Factors with no declared band are not evaluated at all, so `missingFactors`
     * names genuine data gaps and not requirements the species never had. Wind,
     * swell, solunar, depth and the spatial gradients are always evaluated —
     * they are real measurements of modelled conditions, and a species that
     * weights one must surface its absence rather than shed the weight budget.
     */
    private fun evaluateAllFactors(
        resolved: ResolvedProfile,
        obs: PfzObservation
    ): Map<String, FactorOutcome> = buildMap {
        fun add(factor: String, outcome: FactorOutcome) {
            if (resolved.definesFactor(factor)) put(factor, outcome)
        }
        add(PfzFactor.DEPTH, PfzFactors.depth(resolved, obs))
        add(PfzFactor.SST, PfzFactors.sst(resolved, obs))
        add(PfzFactor.CHL, PfzFactors.chlorophyll(resolved, obs))
        add(PfzFactor.CHLA_GRADIENT, PfzFactors.chlaGradient(obs))
        add(PfzFactor.SST_GRADIENT, PfzFactors.sstGradient(obs))
        add(PfzFactor.SST_ANOMALY, PfzFactors.sstAnomaly(obs))
        add(PfzFactor.DEPTH_GRADIENT, PfzFactors.depthGradient(obs))
        add(PfzFactor.BOTTOM_TEMP, PfzFactors.bottomTemp(resolved, obs))
        add(PfzFactor.BOTTOM_SALINITY, PfzFactors.bottomSalinity(resolved, obs))
        add(PfzFactor.BOTTOM_CURRENT, PfzFactors.bottomCurrent(resolved, obs))
        add(PfzFactor.MLD, PfzFactors.mld(resolved, obs))
        add(PfzFactor.SSH_ANOMALY, PfzFactors.sshAnomaly(resolved, obs))
        add(PfzFactor.SST_WARMING, PfzFactors.sstWarming(resolved, obs))
        add(PfzFactor.WIND, PfzFactors.wind(resolved, obs))
        add(PfzFactor.SWELL, PfzFactors.swell(resolved, obs))
        add(PfzFactor.CURRENT, PfzFactors.current(resolved, obs))
        add(PfzFactor.SOLUNAR, PfzFactors.solunar(obs))
        add(PfzFactor.SEASON, PfzFactors.season(resolved, obs))
    }

    /**
     * Macro-basin for an observation, from its region tag or spot id.
     *
     * The observation carries no latitude, so the offshore caller supplies the
     * position-based region itself and passes it in explicitly.
     */
    private fun regionOf(obs: PfzObservation): String? =
        PfzRegions.classifyNamed(obs.region ?: obs.spotId)

    private fun missingKeys(outcomes: Map<String, FactorOutcome>): List<String> =
        outcomes.filterValues { it is FactorOutcome.Missing }.keys.sorted()

    /**
     * Confidence as a function of forecast distance, data coverage and
     * threshold provenance.
     *
     * Coverage is weighted by how much of the profile's weight budget was
     * actually measurable, so a species that resolved 30% of its intent gets a
     * materially lower confidence than one that resolved 100%.
     */
    private fun confidenceFor(
        obs: PfzObservation,
        coverageRatio: Double,
        lowConfidenceFactors: List<String>,
        scoredFactorCount: Int
    ): Int {
        // Reuse the forecast decay the rest of the app already trusts.
        val dateConfidence = ShakaScorer.confidenceForDate(obs.date.toString())

        val coverageTerm = 0.35 + 0.65 * coverageRatio.coerceIn(0.0, 1.0)
        val expertRatio = (lowConfidenceFactors.size.toDouble() / scoredFactorCount.coerceAtLeast(1))
        val provenanceTerm = 1.0 - 0.35 * expertRatio.coerceIn(0.0, 1.0)

        // One factor is not a habitat assessment, it is an anecdote.
        val samplePenalty = if (scoredFactorCount < 2) 0.5 else 1.0

        // A measured SST+chl front coincidence is corroborating evidence the
        // habitat question was actually answered. Modest, EXPERT, and only
        // when the grids genuinely resolved.
        val coincidenceTerm =
            if (obs.frontCoincidence == FrontCoincidence.COINCIDENT) 1.08 else 1.0

        // Persistence distance: ocean analysis lags the requested date. The
        // further back the data the weaker the answer, transparently.
        val persistenceTerm = 1.0 - 0.15 * obs.forecastDay.coerceIn(0, 4)

        return (dateConfidence * coverageTerm * provenanceTerm * samplePenalty *
            coincidenceTerm * persistenceTerm)
            .roundToInt()
            .coerceIn(0, 100)
    }

    /** Factors that no species in the roster could score — a shared data gap. */
    private fun sharedMissingFactors(results: List<PfzSpeciesResult>): List<String> {
        if (results.isEmpty()) return emptyList()
        val perSpecies = results.map { it.missingFactors.toSet() }
        val common = perSpecies.reduce { acc, set -> acc intersect set }
        val scored = results.filter { it.status == PfzStatus.SCOREABLE }
        val scoredFactorNames = scored.flatMap { it.factors.map { f -> f.factor } }.toSet()
        // A factor nothing could score and nothing scored.
        return (common - scoredFactorNames).sorted()
    }
}
