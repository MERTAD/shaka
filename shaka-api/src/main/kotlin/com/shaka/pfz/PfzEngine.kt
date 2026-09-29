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
 *  2. A missing factor is dropped and the remaining weights are renormalized.
 *     It never contributes a neutral or favourable value.
 *  3. If no factor can be scored, the result is [PfzStatus.INSUFFICIENT_DATA]
 *     with `pfz = null`, not a guess.
 *  4. Confidence falls with data coverage, with EXPERT-tier thresholds, and with
 *     forecast distance, and is reported to the caller so a weak score looks weak.
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
            "pessimistic across the basin.",
        "Bluefin tuna, little tunny and swordfish are surface-layer species: their depth " +
            "band is measured below the surface, not against the seafloor. Bathymetry never " +
            "enters their score, so they are scored over deep basins (e.g. >2000 m of bottom " +
            "depth) wherever a Copernicus SST/front grid resolves.",
        "All 72 Mediterranean spots are nominal 15-30 m. Deep Rose Shrimp and Red Shrimp " +
            "will report unavailable at every one of them until deep/offshore spots are added.",
        "Chlorophyll-a gradient (gradCHL) is the documented driver of bluefin feeding habitat " +
            "(Druon et al. 2011) and is computed from Copernicus Med L4 grids when they resolve. " +
            "A spot whose grid did not resolve reports it missing instead of scoring a " +
            "concentration proxy, and bluefin chl is therefore still partly an EXPERT band.",
        "The app measures SURFACE temperature, but most demersal habitat evidence is BOTTOM " +
            "temperature (European hake nurseries 11.8-15.0C at 38-312m, Bousquet 2015; Mullus " +
            "13.6-23.8C at 28-310m, Machias 1998). For those species no SST band is defined at " +
            "all, rather than transposing a bottom range onto the surface. Their scores are " +
            "depth and sea state only, and say so in unscorableRequirements.",
        "No bottom-type (substrate) data source. Hard substrate for common octopus spawning and " +
            "sand for common sole are real requirements the score cannot see."
    )

    /** Aggregate every roster species against one observation. */
    fun evaluate(obs: PfzObservation, profiles: List<SpeciesProfile> = PfzSpeciesRegistry.all()): PfzResponse {
        val results = profiles.map { evaluateSpecies(it, obs) }
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

    /** Score a single species. */
    fun evaluateSpecies(profile: SpeciesProfile, obs: PfzObservation): PfzSpeciesResult {
        val outcomes = evaluateAllFactors(profile, obs)

        val blockers = outcomes.values.filterIsInstance<FactorOutcome.Gated>().map { it.reason }
        if (blockers.isNotEmpty()) {
            return PfzSpeciesResult(
                id = profile.id,
                commonName = profile.commonName,
                scientificName = profile.scientificName,
                guild = profile.guild,
                status = PfzStatus.UNAVAILABLE,
                pfz = null,
                confidence = 0,
                blockers = blockers,
                missingFactors = missingKeys(outcomes),
                unscorableRequirements = profile.unscorableRequirements
            )
        }

        val scored = outcomes.filterValues { it is FactorOutcome.Scored }
            .mapValues { (_, v) -> v as FactorOutcome.Scored }
        if (scored.isEmpty()) {
            return PfzSpeciesResult(
                id = profile.id,
                commonName = profile.commonName,
                scientificName = profile.scientificName,
                guild = profile.guild,
                status = PfzStatus.INSUFFICIENT_DATA,
                pfz = null,
                confidence = 0,
                blockers = emptyList(),
                missingFactors = missingKeys(outcomes),
                unscorableRequirements = profile.unscorableRequirements
            )
        }

        // Renormalize over the factors we could actually score. A factor we
        // cannot measure must neither inflate nor deflate the result.
        val definedWeights = profile.normalizedWeights()
        val scoredWeightTotal = definedWeights
            .filterKeys { scored.containsKey(it) }
            .values
            .sum()

        if (scoredWeightTotal <= 0.0) {
            return PfzSpeciesResult(
                id = profile.id,
                commonName = profile.commonName,
                scientificName = profile.scientificName,
                guild = profile.guild,
                status = PfzStatus.INSUFFICIENT_DATA,
                pfz = null,
                confidence = 0,
                blockers = emptyList(),
                missingFactors = missingKeys(outcomes),
                unscorableRequirements = profile.unscorableRequirements
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
        val missing = missingKeys(outcomes)

        return PfzSpeciesResult(
            id = profile.id,
            commonName = profile.commonName,
            scientificName = profile.scientificName,
            guild = profile.guild,
            status = PfzStatus.SCOREABLE,
            pfz = pfz,
            confidence = confidenceFor(obs, scoredWeightTotal, lowConfidence, scored.size),
            factors = factorScores.sortedByDescending { it.score * it.weight },
            drivers = factorScores
                .filter { it.score >= 80 }
                .sortedByDescending { it.score * it.weight }
                .take(3)
                .map { it.factor },
            blockers = emptyList(),
            missingFactors = missing,
            lowConfidenceFactors = lowConfidence,
            unscorableRequirements = profile.unscorableRequirements
        )
    }

    /** Run every factor for one species. */
    private fun evaluateAllFactors(
        profile: SpeciesProfile,
        obs: PfzObservation
    ): Map<String, FactorOutcome> = mapOf(
        PfzFactor.DEPTH to PfzFactors.depth(profile, obs),
        PfzFactor.SST to PfzFactors.sst(profile, obs),
        PfzFactor.CHL to PfzFactors.chlorophyll(profile, obs),
        PfzFactor.CHLA_GRADIENT to PfzFactors.chlaGradient(obs),
        PfzFactor.SST_GRADIENT to PfzFactors.sstGradient(obs),
        PfzFactor.SST_ANOMALY to PfzFactors.sstAnomaly(obs),
        PfzFactor.DEPTH_GRADIENT to PfzFactors.depthGradient(obs),
        PfzFactor.WIND to PfzFactors.wind(profile, obs),
        PfzFactor.SWELL to PfzFactors.swell(profile, obs),
        PfzFactor.CURRENT to PfzFactors.current(profile, obs),
        PfzFactor.SOLUNAR to PfzFactors.solunar(obs),
        PfzFactor.SEASON to PfzFactors.season(profile, obs)
    )

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
