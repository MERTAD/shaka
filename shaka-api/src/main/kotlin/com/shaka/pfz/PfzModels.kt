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

    /**
     * Arithmetic we performed over real measurements, rather than a number
     * anyone published.
     *
     * Sea surface height anomaly is `zos_daily - zos_monthly_mean` of the same
     * Copernicus product, because that product's static dataset carries no mean
     * dynamic topography. A band written against such a value is better founded
     * than an [EXPERT] guess — every input is a measurement — but it is still not
     * a citation, so it is reported as a distinct tier rather than being passed
     * off as one. It counts as low confidence: [PfzEngine] only treats [CITED] as
     * fully trusted.
     */
    @SerialName("derived") DERIVED,

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

    // v2 factors. All of these are real measurements from the Copernicus physics
    // products (see CopernicusField); the benthic ones are what the hake and
    // red shrimp profiles are actually written against.

    /** Potential temperature at the sea floor, °C. Copernicus `bottomT`. */
    const val BOTTOM_TEMP = "bottom_temp"

    /** Practical salinity at the sea floor, psu. Copernicus `so`, deepest level. */
    const val BOTTOM_SALINITY = "bottom_salinity"

    /** Current speed at the sea floor, m/s. Copernicus `uo`/`vo`, deepest level. */
    const val BOTTOM_CURRENT = "bottom_current"

    /** Mixed layer thickness, m. Copernicus `mlotst`. */
    const val MLD = "mld"

    /**
     * Sea surface height anomaly, m — DERIVED as daily `zos` minus the monthly
     * mean `zos` of the same product. The static dataset carries no `mdt`, so
     * absolute dynamic topography is not available and this is our arithmetic
     * over two real model fields, not a published value.
     */
    const val SSH_ANOMALY = "ssh_anomaly"

    /** 30-day change in sea surface temperature, °C. Derived from the SST series. */
    const val SST_WARMING = "sst_warming"

    val ALL = listOf(
        SST, CHL, CHLA_GRADIENT, SST_GRADIENT, SST_ANOMALY,
        DEPTH, DEPTH_GRADIENT, WIND, SWELL, CURRENT, SOLUNAR, SEASON,
        BOTTOM_TEMP, BOTTOM_SALINITY, BOTTOM_CURRENT, MLD, SSH_ANOMALY, SST_WARMING
    )
}

/**
 * One behavioural mode of a species, optionally split by size class.
 *
 * Bluefin is the reason this exists: Druon et al. (2016) fit *separate*
 * parameterisations for feeding and for spawning, and separate thermal envelopes
 * for small (<=25 kg) and large (>25 kg) fish. Collapsing that into one band set
 * is what made the v1 bluefin profile claim to represent "the species" while
 * actually holding the large-fish feeding numbers.
 */
@Serializable
data class ModeSpec(
    val id: String,
    val label: String,
    /** The mode used when a caller does not pass `?mode=`. */
    val isDefault: Boolean = false,
    /** Size classes this mode is parameterised for, e.g. ["small", "large"]. */
    val sizeClasses: List<String> = emptyList(),
    /** Used when a caller does not pass `?sizeClass=`. Null when unsplit. */
    val defaultSizeClass: String? = null,
    val sstC: BandSpec? = null,
    val chlMgM3: BandSpec? = null,
    val sshAnomalyM: BandSpec? = null,
    val sstWarmingC: BandSpec? = null,
    val depth: DepthSpec? = null,
    val season: SeasonSpec? = null,
    /** Replaces the base weights entirely when non-empty. */
    val weights: Map<String, Double> = emptyMap(),
    /** Replaces the base required factors entirely when non-empty. */
    val requiredFactors: List<String> = emptyList(),
    val conf: FactorConfidence = FactorConfidence.UNKNOWN,
    val src: String? = null
)

/**
 * A geographic override for one macro-region (see [PfzRegions]).
 *
 * [unavailable] is a hard exclusion with a stated reason, and is the single most
 * important field here. Red shrimp are absent from the Ligurian, Catalan and
 * Balearic seas (Ragonese & Bianchini 1995; Papaconstantinou & Kapiris 2003) yet
 * their documented band is 13.6-13.8 °C of bottom temperature — which describes
 * Levantine Intermediate Water, a water mass that simply is not present in those
 * sub-basins. Scored basin-wide, the band would grade a Catalan slope as prime
 * red shrimp ground. The absence has to be a gate, not a note.
 */
@Serializable
data class RegionOverride(
    val unavailable: String? = null,
    val sstC: BandSpec? = null,
    val chlMgM3: BandSpec? = null,
    val bottomTempC: BandSpec? = null,
    val bottomSalinityPsu: BandSpec? = null,
    val bottomCurrentMs: BandSpec? = null,
    val depth: DepthSpec? = null,
    val season: SeasonSpec? = null,
    val weights: Map<String, Double> = emptyMap(),
    val requiredFactors: List<String> = emptyList(),
    val note: String? = null
)

/**
 * A single published source behind one or more numbers in a profile.
 *
 * Surfaced in the API response so a caller can see *why* a band is what it is
 * without having to read the JSON, and so an EXPERT band is visibly the odd one
 * out rather than sitting silently beside a citation.
 */
@Serializable
data class EvidenceRef(
    val key: String,
    val citation: String,
    /** What this source was used for, e.g. "bottom temperature band". */
    val usedFor: String
)

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

    // ------------------------------------------------------------- v2 factors

    /**
     * Bottom potential temperature, °C. Copernicus `bottomT` (2D, already °C).
     * This is Colloca et al.'s SBT, the term that makes the hake profile a hake
     * profile rather than an SST lookup.
     */
    val bottomTempC: BandSpec? = null,

    /** Bottom practical salinity, psu. Copernicus `so`, deepest valid level. */
    val bottomSalinityPsu: BandSpec? = null,

    /** Bottom current speed, m/s. Copernicus `uo`/`vo`, deepest valid level. */
    val bottomCurrentMs: BandSpec? = null,

    /** Mixed layer thickness, m. Copernicus `mlotst`. */
    val mldM: BandSpec? = null,

    /**
     * Sea surface height anomaly, m — derived, not published. The SSH family's
     * static dataset has no `mdt`, so this is `zos_daily - zos_monthly_mean`
     * from the same product.
     */
    val sshAnomalyM: BandSpec? = null,

    /** 30-day change in SST, °C. Druon et al.'s ΔSST30, derived from the SST series. */
    val sstWarmingC: BandSpec? = null,

    // ------------------------------------------------------------------- v2 shape

    /**
     * Behavioural modes (feeding / spawning, …) with optional size-class splits.
     * Empty for the 22 species not yet migrated, which score exactly as in v1.
     */
    val modes: List<ModeSpec> = emptyList(),

    /**
     * Geographic overrides keyed by macro-region (see [PfzRegions]). Generalises
     * [sstCByRegion] from a single SST field to every band, and can hard-exclude
     * a region with [RegionOverride.unavailable].
     */
    val byRegion: Map<String, RegionOverride> = emptyMap(),

    /**
     * Factors that must resolve for this species to be scoreable at all.
     *
     * If any of them is missing, the result is [PfzStatus.INSUFFICIENT_DATA] with
     * a null score — never a renormalized score over what happened to arrive.
     *
     * This exists because Colloca et al. (2014) fit hake habitat as a
     * *product*: any one term at zero makes the cell zero. A 70/100 built from
     * depth and chlorophyll while bottom temperature was unknown is not a
     * partial hake score, it is a different model wearing the hake's name.
     */
    val requiredFactors: List<String> = emptyList(),

    /** Published sources behind the numbers in this profile. */
    val evidence: List<EvidenceRef> = emptyList(),

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
        // v2: each of these is a real, individually-resolvable measurement, so
        // the factor is always evaluable. That is deliberate — it means a
        // species that genuinely weights bottom temperature reports the data gap
        // instead of quietly losing the weight budget and inflating the rest.
        PfzFactor.BOTTOM_TEMP -> bottomTempC != null
        PfzFactor.BOTTOM_SALINITY -> bottomSalinityPsu != null
        PfzFactor.BOTTOM_CURRENT -> bottomCurrentMs != null
        PfzFactor.MLD -> mldM != null
        PfzFactor.SSH_ANOMALY -> sshAnomalyM != null
        PfzFactor.SST_WARMING -> sstWarmingC != null
        else -> false
    }

    /** The mode used when a caller passes no `?mode=`. */
    fun defaultMode(): ModeSpec? = modes.firstOrNull { it.isDefault } ?: modes.firstOrNull()

    fun modeById(id: String): ModeSpec? = modes.firstOrNull { it.id == id }

    /**
     * Flatten this profile's mode / size-class / region overrides into one flat,
     * engine-facing set of bands and weights.
     *
     * Precedence, highest first: **mode > region > base**. A mode override wins
     * because a behavioural parameterisation is a different model, not a
     * different location — and the two rarely collide in practice (bluefin
     * spawning season varies by basin, its thermal band by behaviour).
     *
     * `region` falls back through [byRegion] and then the older [sstCByRegion],
     * so the 22 not-yet-migrated species keep their existing region behaviour.
     */
    fun resolve(mode: String? = null, sizeClass: String? = null, region: String? = null): ResolvedProfile {
        val chosen = (mode?.let { modeById(it) }) ?: defaultMode()
        val effectiveSize = sizeClass ?: chosen?.defaultSizeClass
        val regionOverride = region?.let { byRegion[it] }

        fun <T : Any> pick(base: T?, regionValue: T?, modeValue: T?): T? = modeValue ?: regionValue ?: base

        val weights = when {
            !chosen?.weights.isNullOrEmpty() -> chosen!!.weights
            !regionOverride?.weights.isNullOrEmpty() -> regionOverride!!.weights
            else -> weights
        }
        val required = when {
            !chosen?.requiredFactors.isNullOrEmpty() -> chosen!!.requiredFactors
            !regionOverride?.requiredFactors.isNullOrEmpty() -> regionOverride!!.requiredFactors
            else -> requiredFactors
        }

        return ResolvedProfile(
            profile = this,
            modeSpec = chosen,
            mode = chosen?.id,
            sizeClass = effectiveSize,
            region = region,
            regionExclusion = regionOverride?.unavailable?.let { region to it },
            depth = pick(depth, regionOverride?.depth, chosen?.depth) ?: depth,
            sstC = pick(sstC, regionOverride?.sstC ?: sstCByRegion[region], chosen?.sstC),
            chlMgM3 = pick(chlMgM3, regionOverride?.chlMgM3, chosen?.chlMgM3),
            bottomTempC = pick(bottomTempC, regionOverride?.bottomTempC, null),
            bottomSalinityPsu = pick(bottomSalinityPsu, regionOverride?.bottomSalinityPsu, null),
            bottomCurrentMs = pick(bottomCurrentMs, regionOverride?.bottomCurrentMs, null),
            mldM = mldM,
            sshAnomalyM = pick(sshAnomalyM, null, chosen?.sshAnomalyM),
            sstWarmingC = pick(sstWarmingC, null, chosen?.sstWarmingC),
            season = pick(season, regionOverride?.season, chosen?.season),
            windKmhMax = windKmhMax,
            swellMMax = swellMMax,
            oceanCurrentKmhMax = oceanCurrentKmhMax,
            weights = weights,
            requiredFactors = required
        )
    }
}

/**
 * A [SpeciesProfile] with every mode / size-class / region override already
 * applied. The engine consumes only this, so it never has to know that a
 * profile is nested.
 */
data class ResolvedProfile(
    val profile: SpeciesProfile,
    /** The [ModeSpec] that was applied, or null when the species declares none. */
    val modeSpec: ModeSpec?,
    /** The mode actually applied, or null when the species declares none. */
    val mode: String?,
    val sizeClass: String?,
    val region: String?,
    /** Set when the species does not occur in this region at all. */
    /**
     * Set when the species does not occur in this region at all: the region slug
     * paired with the reason, so a refusal can be matched against a map and the
     * caller never has to parse prose to find out where it applies.
     */
    val regionExclusion: Pair<String, String>?,
    val depth: DepthSpec,
    val sstC: BandSpec?,
    val chlMgM3: BandSpec?,
    val bottomTempC: BandSpec?,
    val bottomSalinityPsu: BandSpec?,
    val bottomCurrentMs: BandSpec?,
    val mldM: BandSpec?,
    val sshAnomalyM: BandSpec?,
    val sstWarmingC: BandSpec?,
    val season: SeasonSpec?,
    val windKmhMax: Double?,
    val swellMMax: Double?,
    /** Surface-current operational limit, km/h. Distinct from [bottomCurrentMs]. */
    val oceanCurrentKmhMax: Double?,
    val weights: Map<String, Double>,
    val requiredFactors: List<String>
) {
    /** Weights for factors this resolution actually defines, renormalized to 1. */
    fun normalizedWeights(): Map<String, Double> {
        val defined = weights.filter { (factor, w) -> w > 0.0 && definesFactor(factor) }
        val total = defined.values.sum()
        if (total <= 0.0) return emptyMap()
        return defined.mapValues { (_, w) -> w / total }
    }

    /**
     * Whether this species has a real requirement for [factor].
     *
     * The engine evaluates and reports only what is defined here. That keeps
     * `missingFactors` meaningful: `bottom_temp` appears for a hake whose band
     * we could not fill, and does not appear for a sardine that has no bottom
     * temperature requirement at all. Reporting an undeclared band as "missing
     * data" would tell the caller we are blind to something that was never part
     * of the question.
     *
     * Spatial gradients, wind, swell and solunar are always evaluable: they are
     * either real measurements of the conditions the app models, or a shared
     * operational envelope, so a species that genuinely weights one reports its
     * data gap instead of quietly losing the weight budget.
     *
     * [PfzFactor.DEPTH] is the one exception, and deliberately so. For a
     * [DepthScope.WATER_COLUMN] species the depth band describes depth below the
     * surface, so seafloor depth is not a requirement at all. Reporting it as a
     * missing factor both misleads the caller — it reads as "we lack depth data"
     * for a species that does not care about the seabed — and lowers confidence,
     * because the discarded weight shrinks the measured fraction of the budget.
     */
    fun definesFactor(factor: String): Boolean = when (factor) {
        PfzFactor.SST -> sstC != null
        PfzFactor.CHL -> chlMgM3 != null
        // Always evaluable, exactly as in v1: a species that genuinely weights a
        // spatial gradient must report its data gap rather than lose the weight.
        PfzFactor.CHLA_GRADIENT, PfzFactor.SST_GRADIENT, PfzFactor.SST_ANOMALY,
        PfzFactor.DEPTH_GRADIENT,
        PfzFactor.SOLUNAR, PfzFactor.WIND, PfzFactor.SWELL -> true
        PfzFactor.DEPTH -> depth.scope != DepthScope.WATER_COLUMN
        PfzFactor.CURRENT -> oceanCurrentKmhMax != null
        PfzFactor.BOTTOM_TEMP -> bottomTempC != null
        PfzFactor.BOTTOM_SALINITY -> bottomSalinityPsu != null
        PfzFactor.BOTTOM_CURRENT -> bottomCurrentMs != null
        PfzFactor.MLD -> mldM != null
        PfzFactor.SSH_ANOMALY -> sshAnomalyM != null
        PfzFactor.SST_WARMING -> sstWarmingC != null
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

    // ---- v2 benthic and mesoscale factors. All real measurements; null is
    // UNKNOWN and is reported as a named missing factor, never defaulted.

    /**
     * Potential temperature at the sea floor, °C, from Copernicus `bottomT`.
     *
     * This is the measurement the hake profile is built on (Colloca et al. 2014
     * give a 11.8-15.0 °C 5-95 percentile band for Mediterranean recruits) and
     * the first one red shrimp are known to track (Maiorano et al. 2020).
     */
    val bottomTempC: Double? = null,

    /**
     * Practical salinity at the sea floor, psu, from the deepest non-masked
     * level of Copernicus `so`. Measured 38.5-38.6 psu live off the Balearic
     * abyssal plain, which is the Levantine Intermediate Water that red shrimp
     * are coupled to.
     */
    val bottomSalinityPsu: Double? = null,

    /**
     * Current speed at the sea floor, m/s, from the deepest non-masked level of
     * Copernicus `uo`/`vo`.
     *
     * Deliberately in m/s, matching Colloca et al.'s 0.034 m/s condition. The
     * v1 profile had an `oceanCurrentKmhMax` in km/h for an unrelated surface
     * current; keeping the two apart prevents a unit slip turning a passing cell
     * into a gated one.
     */
    val bottomCurrentMs: Double? = null,

    /** Mixed layer thickness, m, from Copernicus `mlotst`. */
    val mldM: Double? = null,

    /**
     * Sea surface height anomaly, m — DERIVED as daily `zos` minus the monthly
     * mean `zos` of the same Copernicus product, because that product's static
     * dataset publishes no `mdt`.
     *
     * Measured live: 18 mm of spread across a 9 km box but 276 mm across a
     * 5x8 degree one. It is therefore an absolute regional gate, and zone
     * analysis needs a wider window than the usual 0.3 degree box for it to vary
     * between zones at all.
     */
    val sshAnomalyM: Double? = null,

    /** 30-day change in sea surface temperature, °C (Druon et al.'s ΔSST30). */
    val sstWarmingC: Double? = null,
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
    /**
     * Behavioural mode actually applied, e.g. `feeding` or `spawning`.
     *
     * Disclosed because a bluefin feeding score and a bluefin spawning score are
     * different models against different thermal envelopes (Druon et al. 2016),
     * and a caller that cannot see which one it received cannot interpret the
     * number. Null when the species declares no modes.
     */
    val mode: String? = null,
    /** Size class the applied mode was parameterised for, when it is split. */
    val sizeClass: String? = null,
    /** Macro-basin used for geographic overrides, when one was resolved. */
    val region: String? = null,
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
    val unscorableRequirements: List<String> = emptyList(),
    /**
     * Species-level caveat that the caller must see next to the number, such as
     * "no Maghreb band is cited, so the basin-wide band is being applied here"
     * for sardine.
     *
     * This travels per species rather than in the response-wide coverage notes
     * because it changes with the applied mode and region: the same species
     * needs no such caveat in one basin and an urgent one in another.
     */
    val note: String? = null
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
     * Stable identity of this zone's grid cell, snapped to the cell centre.
     *
     * [lat]/[lon] are the polygon centroid, which is recomputed from the patch
     * shape on every build and therefore moves whenever the front shifts. That
     * is the right thing to draw a map label at and the wrong thing to key a
     * day-over-day trend on: two consecutive days of the same water would land
     * on slightly different centroids and read as two unrelated cells.
     *
     * These two are the cell nearest the centroid on the underlying grid, so a
     * cell that persists across days keeps the same coordinates and the trend
     * compares one physical grid cell to itself.
     */
    val cellLat: Double = lat,
    val cellLon: Double = lon,
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
    /**
     * The model that produced this number: the behavioural mode and size class
     * that were applied, and the macro-region this zone resolved to.
     *
     * The same request can be run twice for two modes of one species, and the
     * scores differ — near-inverted for bluefin feeding versus spawning. A zone
     * score without its applied context is therefore not reproducible, and the
     * region is what decides whether the score is possible at all.
     */
    val mode: String? = null,
    val sizeClass: String? = null,
    val region: String? = null,
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

/**
 * Full response body for `GET /v1/pfz/zones/history`.
 *
 * This is a record of what the model said on each past day, read from
 * `pfz_zones_daily`. It is deliberately NOT a re-run of the engine over past
 * dates: ocean fields have been revised since they were published, and a
 * Copernicus request for a past date can legitimately be refused because the
 * archive ends. Recomputing would answer "what would today's model say about
 * yesterday", which is a different and less useful question than "what did it
 * say yesterday" — only the stored answer is the latter.
 *
 * The trend is per grid cell ([cells]), not per rank. Ranks are re-assigned on
 * every rebuild, so a rank-based trend would compare unrelated patches of ocean.
 */
@Serializable
data class PfzHistoryResponse(
    val speciesId: String,
    val speciesName: String,
    val speciesScientificName: String,
    val anchorLat: Double,
    val anchorLon: Double,
    /**
     * The behavioural mode these rows were scored under, and the size class.
     *
     * Echoed rather than defaulted so a caller can see which model it is looking
     * at, and so a near-empty history is explainable: a bluefin feeding series
     * will not show a bluefin spawning day in it.
     */
    val mode: String,
    val sizeClass: String,
    val fromDate: String,
    val toDate: String,
    /**
     * Best scoreable zone on the most recent day that had one, or null when no
     * persisted day produced a scoreable zone. Never 0 for "unavailable" — see
     * [PfzHistoryDay.topPfz].
     */
    val latestTopPfz: Int? = null,
    /**
     * Change between the two most recent days that both produced a scoreable
     * zone, or null when there are fewer than two. Null with a non-null
     * [latestTopPfz] is meaningful: today's best was 71, and the previous
     * scoreable day was four days ago.
     */
    val dayOverDay: Int? = null,
    val days: List<PfzHistoryDay> = emptyList(),
    val cells: List<PfzHistoryCell> = emptyList(),
    val coverageNotes: List<String> = emptyList()
)

/**
 * One persisted day.
 *
 * [topPfz] is null unless at least one zone was scoreable. A null day is a gap:
 * the species was outside its habitat, or a required factor such as bottom
 * temperature did not resolve. Neither is a score of 0, and neither should be
 * drawn as a point on a chart — an interpolation across a gap would invent a
 * measurement.
 */
@Serializable
data class PfzHistoryDay(
    val date: String,
    val topPfz: Int? = null,
    val confidence: Int = 0,
    val zoneCount: Int = 0,
    val scoreableCount: Int = 0,
    val zones: List<PfzHistoryZone> = emptyList()
)

/** One persisted zone within a day. */
@Serializable
data class PfzHistoryZone(
    val rank: Int,
    val name: String = "",
    val lat: Double,
    val lon: Double,
    val status: String,
    val pfz: Int? = null,
    val confidence: Int = 0,
    val frontKm: Double? = null,
    val depthM: Double? = null,
    val region: String? = null,
    val drivers: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val missingFactors: List<String> = emptyList()
)

/**
 * One grid cell's score across the days it was persisted.
 *
 * This is the unit a trend is honestly measured in. A cell is a real place inside
 * the analysed box, so its series compares like with like; a rank is not.
 */
@Serializable
data class PfzHistoryCell(
    val lat: Double,
    val lon: Double,
    val firstPfz: Int? = null,
    val lastPfz: Int? = null,
    /** last minus first, or null unless both ends scored. */
    val change: Int? = null,
    val points: List<PfzHistoryPoint> = emptyList()
)

/** One cell observation on one day. */
@Serializable
data class PfzHistoryPoint(
    val date: String,
    val pfz: Int? = null,
    val confidence: Int = 0,
    val rank: Int = 0
)

/**
 * The 404 body for a species the server does not recognise.
 *
 * A declared type rather than a `mapOf`, because a bare `mapOf("error" to
 * String, "knownSpecies" to List<String>)` is a `Map<String, Any>`: kotlinx
 * serialisation cannot find a serializer for the erased value type and throws
 * while encoding the response. The client sees a 500 with no body instead of
 * the 404 it is written to handle, so a typo in a species id presents as a
 * server fault. Bodies of only strings are unaffected, which is why every
 * other error path looked fine.
 *
 * The roster travels with the error on purpose. "Unknown species" alone is
 * indistinguishable from a species this deployment has not heard of; the list
 * is what lets a caller correct a typo without a second round trip.
 */
@Serializable
data class PfzUnknownSpeciesResponse(
    val error: String,
    val knownSpecies: List<String>
)
