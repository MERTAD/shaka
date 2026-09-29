package com.shaka.pfz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * PFZ — Potential Fishing Zone.
 *
 * Species-specific habitat suitability, scored per (observation, species).
 *
 * Design contract: [PfzEngine] never sees a latitude or longitude. It consumes
 * an already-resolved [PfzObservation]. That is what lets a v2 offshore
 * front-finder feed the exact same engine — it only changes how the observation
 * is produced, never how species are scored.
 *
 * Honesty rules that this package exists to enforce:
 *  - A factor we have no measurement for is ABSENT. It is never defaulted.
 *  - A species outside its documented habitat is UNVAILABLE with a reason,
 *    never a low number. "Swordfish at a 30 m rock" is not a 5/100 spot.
 *  - Every numeric threshold carries [FactorConfidence]. EXPERT values are
 *    surfaced to the caller so a guess is never mistaken for a citation.
 */

/** Provenance of a threshold in a [SpeciesProfile]. */
@Serializable
enum class FactorConfidence {
    /** Traceable to a published source; see the `src` field. */
    @SerialName("cited") CITED,

    /** Standard fisheries knowledge, not traceable to a specific citation. */
    @SerialName("expert") EXPERT,

    /** Not yet researched. The factor is excluded and reported as missing. */
    @SerialName("unknown") UNKNOWN
}

/** How a measured value is compared against a band. */
@Serializable
enum class Preference {
    /** Trapezoid: 100 inside [idealMin, idealMax], falling linearly to 0 at the gate. */
    @SerialName("band") BAND,

    /** Monotonic: smaller is better across the whole gate. */
    @SerialName("lower_better") LOWER_BETTER,

    /** Monotonic: larger is better across the whole gate. */
    @SerialName("higher_better") HIGHER_BETTER
}

/**
 * One measurable habitat requirement, e.g. sea-surface temperature.
 *
 * [gateMin]..[gateMax] is the hard biological limit: outside it the species is
 * [PfzStatus.UNAVAILABLE], not merely scored down. [idealMin]..[idealMax] is
 * where the species is most comfortable.
 *
 * Null fields mean "not yet researched" — the engine excludes the factor and
 * names it in the response rather than inventing a value.
 */
@Serializable
data class BandSpec(
    val gateMin: Double,
    val gateMax: Double,
    val idealMin: Double? = null,
    val idealMax: Double? = null,
    val preference: Preference = Preference.BAND,
    val conf: FactorConfidence = FactorConfidence.UNKNOWN,
    val src: String? = null
) {
    /** Effective scoring band, falling back to the gate when no ideal band is known. */
    val effectiveIdealMin: Double get() = idealMin ?: gateMin
    val effectiveIdealMax: Double get() = idealMax ?: gateMax
}

/**
 * What a [DepthSpec] band measures.
 *
 * - [BATHYMETRY]: the gate is a seafloor-depth band. The species is tied to the
 *   substrate ("species depth range"), so bathymetry gates hard and scores.
 *   This is correct for demersals, cephalopods, crustaceans, inshore species
 *   and the coastal small-pelagic guild they school over the shelf.
 * - [WATER_COLUMN]: the gate is how deep below the surface the species swims
 *   ("top 200 m"). Seafloor depth is NOT a constraint: bluefin tuna roam open
 *   basins with >2000 m bottom depth while living in the surface layer. For
 *   these species bathymetry must never enter the score.
 */
enum class DepthScope {
    @SerialName("bathymetry")
    BATHYMETRY,

    @SerialName("water_column")
    WATER_COLUMN
}

/**
 * Depth requirement in metres. [gateMinM]..[gateMaxM] is where the species can
 * physically be; [prefMinM]..[prefMaxM] is where it prefers to hold.
 *
 * [scope] decides whether the band is measured against the seabed
 * ([DepthScope.BATHYMETRY]) or only describes the water column
 * ([DepthScope.WATER_COLUMN]).
 */
@Serializable
data class DepthSpec(
    val gateMinM: Double,
    val gateMaxM: Double,
    val prefMinM: Double? = null,
    val prefMaxM: Double? = null,
    val scope: DepthScope = DepthScope.BATHYMETRY,
    val conf: FactorConfidence = FactorConfidence.UNKNOWN,
    val src: String? = null
)

/**
 * Calendar behaviour.
 *
 * [closedMonths] is a HARD gate and must only be used for genuine absence or a
 * legal closure. Putting "this is not the target season" in closedMonths
 * produces a false unavailable verdict: sardine are present and catchable in
 * Jul-Aug, they are simply nursery-dominated, so that belongs in [peakMonths]
 * where it decays smoothly instead of switching the species off.
 */
@Serializable
data class SeasonSpec(
    /** 1-12. Species presence/quality peaks in these months. */
    val peakMonths: List<Int> = emptyList(),
    /** 1-12. Hard gate: species is absent or legally closed. Rare. */
    val closedMonths: List<Int> = emptyList(),
    val conf: FactorConfidence = FactorConfidence.UNKNOWN,
    val src: String? = null
)

/** Factor keys. Kept as constants so a typo in JSON is a load-time error, not a silent zero weight. */
object PfzFactor {
    const val SST = "sst"
    const val CHL = "chl"
    const val CHLA_GRADIENT = "chla_gradient"
    const val SST_GRADIENT = "sst_gradient"
    const val SST_ANOMALY = "sst_anomaly"
    const val DEPTH = "depth"
    const val DEPTH_GRADIENT = "depth_gradient"
    const val WIND = "wind"
    const val SWELL = "swell"
    const val CURRENT = "current"
    const val SOLUNAR = "solunar"
    const val SEASON = "season"

    val ALL = listOf(
        SST, CHL, CHLA_GRADIENT, SST_GRADIENT, SST_ANOMALY,
        DEPTH, DEPTH_GRADIENT, WIND, SWELL, CURRENT, SOLUNAR, SEASON
    )
}

/**
 * A single species' habitat definition.
 *
 * [weights] are this species' own relative importance for each factor and must
 * sum to 1.0. That is what makes "each species has its own algorithm" literal:
 * the same engine, different weights, bands, gates and season.
 */
@Serializable
data class SpeciesProfile(
    val id: String,
    val commonName: String,
    val scientificName: String,
    /** PFZ-local only. Never merged into the global SpeciesNormalizer. */
    val aliases: List<String> = emptyList(),
    /**
     * Short label for satellite-style map target names (e.g. "BluefinTuna").
     * When null, the target tag falls back to [commonName] without punctuation
     * or spaces, which loses qualifiers like "Atlantic ".
     */
    val targetTag: String? = null,
    /** Ecological guild, e.g. `large_pelagic`, `demersal`, `small_pelagic`, `cephalopod`. */
    val guild: String,
    val depth: DepthSpec,
    val sstC: BandSpec? = null,
    /**
     * Region-specific SST overrides, keyed by macro-region (see [PfzRegions]).
     *
     * This exists because a single thermal band is scientifically wrong for some
     * species. Round sardinella spawns at 23-26.5 C at its north-western
     * Mediterranean range limit (Sabatés et al. 2009) but at 18-21 C off the
     * Western Sahara and across the Maghreb, where it is the dominant pelagic
     * species. A single band would grade Algerian water as wrong when it is
     * actually prime spawning habitat.
     */
    val sstCByRegion: Map<String, BandSpec> = emptyMap(),
    val chlMgM3: BandSpec? = null,
    val windKmhMax: Double? = null,
    val swellMMax: Double? = null,
    val oceanCurrentKmhMax: Double? = null,
    val season: SeasonSpec? = null,
    val weights: Map<String, Double>,
    /**
     * Habitat requirements the app has no data source for, so they cannot be
     * scored. Kept visible in the response rather than silently dropped.
     *
     * Common octopus has a documented preference for spawning on hard bottom
     * substrate (Guerra et al. 2015), and common sole requires sand. The app
     * has no bottom-type source, so a PFZ score for either species is a
     * statement about depth and temperature only. The caller should be told that.
     */
    val unscorableRequirements: List<String> = emptyList(),
    /** Free-text note surfaced in the API response, typically a coverage caveat. */
    val note: String? = null
) {
    /** Weights for factors this profile actually defines, renormalized to sum to 1. */
    fun normalizedWeights(): Map<String, Double> {
        val defined = weights.filter { (factor, w) -> w > 0.0 && hasFactor(factor) }
        val total = defined.values.sum()
        if (total <= 0.0) return emptyMap()
        return defined.mapValues { (_, w) -> w / total }
    }

    private fun hasFactor(factor: String): Boolean = when (factor) {
        PfzFactor.SST -> sstC != null || sstCByRegion.isNotEmpty()
        PfzFactor.CHL -> chlMgM3 != null
        // Spatial gradients and the SST anomaly are their own factors, not
        // proxies for concentration. Like the chlorophyll gradient, they are
        // always evaluable so that a species which genuinely weights them
        // reports the data gap instead of silently losing the weight budget.
        PfzFactor.CHLA_GRADIENT -> true
        PfzFactor.SST_GRADIENT -> true
        PfzFactor.SST_ANOMALY -> true
        PfzFactor.DEPTH_GRADIENT -> true
        PfzFactor.DEPTH -> true
        // Wind and swell always resolve: a species may declare its own
        // operational limit, otherwise the engine applies the shared
        // conservative envelope and flags it EXPERT-tier. A measured wind is
        // real data; only the threshold is an assumption, and it is reported.
        PfzFactor.WIND -> true
        PfzFactor.SWELL -> true
        PfzFactor.CURRENT -> oceanCurrentKmhMax != null
        PfzFactor.SOLUNAR -> true
        PfzFactor.SEASON -> season != null && season.peakMonths.isNotEmpty()
        else -> false
    }
}

/**
 * Whether a thermal and/or chlorophyll front sits inside the spot's analysis
 * box. A real measured fact: NONE means both grids resolved and neither held a
 * front; null (not part of this type) would mean we could not tell.
 */
@Serializable
enum class FrontCoincidence {
    @SerialName("sst_only") SST_ONLY,
    @SerialName("chl_only") CHL_ONLY,
    @SerialName("coincident") COINCIDENT,
    @SerialName("none") NONE
}

/**
 * Everything the engine is allowed to know about one place and moment.
 *
 * Every field is nullable and nullable means UNKNOWN. There is deliberately no
 * default constructor with plausible values.
 */
data class PfzObservation(
    val spotId: String,
    val date: LocalDate,
    /** Real bathymetric depth at the spot, positive-down metres. Not a hand-entered nominal. */
    val depthM: Double? = null,
    /** e.g. "ncei_dem" / "gebco" — lets a caller see how much to trust [depthM]. */
    val depthSource: String? = null,
    val waterTempC: Double? = null,
    val chlorophyllMgM3: Double? = null,
    /**
     * Chlorophyll-a spatial gradient. This is the actual documented driver for
     * bluefin feeding habitat (Druon et al. 2011). When the Copernicus gap-free
     * grid resolves, this carries the strongest in-box gradient in mg/m3/km.
     */
    val chlaGradient: Double? = null,
    /**
     * Strongest sea-surface temperature gradient inside the analysis box, in
     * °C/km. Null when no SST grid resolved (a front may still exist).
     */
    val sstGradientCkm: Double? = null,
    /** SST anomaly at the spot, °C. Null when the SSTA grid did not resolve. */
    val sstAnomalyC: Double? = null,
    /** Strongest bathymetric slope centre-to-probe, metres per km. */
    val depthGradientMperKm: Double? = null,
    /** Distance from the spot to the nearest detected front cell, km. */
    val frontKm: Double? = null,
    /** Which fronts actually sit in the analysis box. Null only if no grid resolved. */
    val frontCoincidence: FrontCoincidence? = null,
    /** The calendar date the ocean fields were analysed for (persistence distance). */
    val dataDate: LocalDate? = null,
    /** Days between the requested date and [dataDate]; 0 means same-day data. */
    val forecastDay: Int = 0,
    val windSpeedKmh: Double? = null,
    val waveHeightM: Double? = null,
    val swellHeightM: Double? = null,
    val oceanCurrentVelocityKmh: Double? = null,
    val solunarDayRating: Int? = null,
    val moonPhase: String? = null,
    val tideState: String? = null,
    val exposureBearingDeg: Int? = null,
    /**
     * Angular spread of the open-water arc, in DEGREES (not km).
     *
     * Named `...Deg` deliberately: this value was previously stored as
     * `exposureWidthKm`, which invited a downstream consumer to treat a
     * compass arc as a physical distance.
     */
    val exposureWidthDeg: Int? = null,
    /** Coarse region tag, e.g. "sicily" — used for coverage caveats. */
    val region: String? = null
)

/** Outcome of scoring one species against one observation. */
@Serializable
enum class PfzStatus {
    /** A defensible 0-100 score was produced. */
    @SerialName("scoreable") SCOREABLE,

    /** Outside the species' documented habitat. [PfzSpeciesResult.pfz] is null. */
    @SerialName("unavailable") UNAVAILABLE,

    /** Habitat is plausible but we lack enough data to score it honestly. [pfz] is null. */
    @SerialName("insufficient_data") INSUFFICIENT_DATA
}

/** One factor's contribution, with the weight actually applied after renormalization. */
@Serializable
data class FactorScore(
    val factor: String,
    val score: Int,
    /** Renormalized weight actually used in the weighted sum. */
    val weight: Double,
    val confidence: FactorConfidence
)

/**
 * Result of evaluating one factor.
 *
 * The three cases are deliberately distinct. [Missing] is not a zero: an
 * absent measurement removes the factor from the weighted sum, it does not
 * drag the score down. [Gated] is different again — it means the observation is
 * positively known to be outside the species' range.
 */
sealed interface FactorOutcome {
    data class Scored(val score: Int, val confidence: FactorConfidence) : FactorOutcome

    /** Positively known to be outside the species' habitat. Ends scoring. */
    data class Gated(val reason: String) : FactorOutcome

    /** We simply do not know. Factor is excluded and reported. */
    data class Missing(val reason: String) : FactorOutcome
}

/** Per-species result. */
@Serializable
data class PfzSpeciesResult(
    val id: String,
    val commonName: String,
    val scientificName: String,
    val guild: String,
    val status: PfzStatus,
    /** 0-100, or null unless [status] is [PfzStatus.SCOREABLE]. */
    val pfz: Int? = null,
    /** 0-100. Reduced by missing data, low-confidence factors and forecast distance. */
    val confidence: Int = 0,
    val factors: List<FactorScore> = emptyList(),
    /** Factors that pushed the score up, most important first. */
    val drivers: List<String> = emptyList(),
    /** Human-readable reasons the species is not available or is weakly supported. */
    val blockers: List<String> = emptyList(),
    /** Factors the profile defines but the observation could not supply. */
    val missingFactors: List<String> = emptyList(),
    /** Factors scored on EXPERT rather than CITED thresholds. */
    val lowConfidenceFactors: List<String> = emptyList(),
    /**
     * Known habitat requirements the app has no data source for.
     *
     * Unlike [missingFactors] these are not gaps in the current forecast — they
     * are permanent capability gaps. The score is still returned, but the caller
     * is told the score is blind to something that may well decide the outcome
     * (e.g. common octopus spawning on hard bottom, which the app cannot see).
     */
    val unscorableRequirements: List<String> = emptyList()
)

/** Full response body for `GET /v1/spots/{id}/pfz`. */
@Serializable
data class PfzResponse(
    val spotId: String,
    val date: String,
    /** Highest per-species confidence in this response. */
    val confidence: Int,
    val species: List<PfzSpeciesResult>,
    /** Factors the profiles wanted that no spot in scope could supply. */
    val missingFactors: List<String> = emptyList(),
    /** Honest statement of what the spot inventory cannot yet represent. */
    val coverageNotes: List<String> = emptyList()
)

/** A single lat/lon vertex of a zone polygon, as serialised to the wire. */
@Serializable
data class PfzPoint(
    val lat: Double,
    val lon: Double
)

/**
 * One ranked candidate fishing zone inside an analysed box.
 *
 * Ranks are computed by the exact same [PfzEngine] as [PfzSpeciesResult] — the
 * "computed as Shaka" half of the deliverable — but presented as a clean,
 * map-drawable list ("presented like SatCatch"). Every corridor field belongs
 * to the grid cell the zone is the centre of; a null means the grid reason for
 * that field did not resolve there, never a default.
 */
@Serializable
data class PfzZone(
    /** 1 = the engine's strongest answer in this box for this species. */
    val rank: Int,
    /** Satellite-style target label, e.g. "BluefinTuna 27/09 001". */
    val name: String = "",
    val lat: Double,
    val lon: Double,
    /**
     * Outer ring of the zone polygon. The zone is one connected grid patch
     * whose shape follows the resolved SST/CHL front and thermal-core
     * structure, not a fixed box; this ring is that patch's outline.
     */
    val polygon: List<PfzPoint> = emptyList(),
    /** Interior rings of [polygon] (holes), when the patch contains any. */
    val holes: List<List<PfzPoint>> = emptyList(),
    val status: PfzStatus,
    /** 0-100, or null unless [status] is [PfzStatus.SCOREABLE]. */
    val pfz: Int? = null,
    val confidence: Int = 0,
    /** Distance from the zone centre to the nearest detected front cell, km. */
    val frontKm: Double? = null,
    /** Which fronts this zone's cell actually sits on. Null only if no grid resolved. */
    val frontCoincidence: FrontCoincidence? = null,
    val sstGradientCkm: Double? = null,
    val chlaGradientMgM3km: Double? = null,
    val sstAnomalyC: Double? = null,
    /** Single-point bathymetric depth at the zone centre, metres. */
    val depthM: Double? = null,
    val drivers: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val missingFactors: List<String> = emptyList(),
    val lowConfidenceFactors: List<String> = emptyList()
)

/** Full response body for `GET /v1/pfz/zones`. */
@Serializable
data class PfzZonesResponse(
    val speciesId: String,
    val speciesName: String,
    val speciesScientificName: String,
    val date: String,
    val lat: Double,
    val lon: Double,
    /** Highest per-zone confidence in this response. */
    val confidence: Int,
    /** Empty when the grid corridor did not resolve: no zones can be ranked, only admitted. */
    val zones: List<PfzZone>,
    /** Factors no zone in the box could supply for this species. */
    val missingFactors: List<String> = emptyList(),
    val coverageNotes: List<String> = emptyList()
)
