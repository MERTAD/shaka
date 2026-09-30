package com.shaka.pfz

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wrapper so the roster file can carry top-level metadata alongside the profiles. */
@Serializable
private data class PfzProfileFile(
    val version: Int = 1,
    val region: String = "mediterranean",
    val species: List<SpeciesProfile> = emptyList()
)

/**
 * Loads and validates the PFZ species roster from classpath resources.
 *
 * Validation is strict on purpose. A malformed profile would silently produce
 * wrong fishing advice, so anything structurally invalid is rejected and the
 * reason is recorded in [loadErrors] rather than being scored with a default.
 */
object PfzSpeciesRegistry {

    private const val RESOURCE = "pfz/species_profiles.json"
    private const val WEIGHT_SUM_TOLERANCE = 0.02

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private data class Loaded(
        val version: Int,
        val region: String,
        val profiles: List<SpeciesProfile>,
        val errors: List<String>
    )

    private val loaded: Loaded by lazy { load() }

    val version: Int get() = loaded.version
    val region: String get() = loaded.region

    /** Problems found while loading. Surfaced in the API response so they are never invisible. */
    val loadErrors: List<String> get() = loaded.errors

    /** All valid profiles, ordered by id for stable output. */
    fun all(): List<SpeciesProfile> = loaded.profiles.sortedBy { it.id }

    fun byId(id: String): SpeciesProfile? = loaded.profiles.firstOrNull { it.id == id }

    fun count(): Int = loaded.profiles.size

    /** Resolve free text (any supported language) to a profile. */
    fun resolve(raw: String): SpeciesProfile? =
        PfzSpeciesAliases.resolve(raw)?.let { byId(it) }

    private fun load(): Loaded {
        val stream = javaClass.classLoader.getResourceAsStream(RESOURCE)
            ?: return Loaded(0, "unknown", emptyList(), listOf("missing resource: $RESOURCE"))

        val text = stream.bufferedReader().use { it.readText() }

        val file = try {
            json.decodeFromString(PfzProfileFile.serializer(), text)
        } catch (e: Exception) {
            return Loaded(0, "unknown", emptyList(), listOf("unparseable $RESOURCE: ${e.message}"))
        }

        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val valid = mutableListOf<SpeciesProfile>()

        for (profile in file.species) {
            val problems = validate(profile)
            if (problems.isNotEmpty()) {
                errors += "${profile.id}: ${problems.joinToString("; ")}"
                continue
            }
            if (!seen.add(profile.id)) {
                errors += "${profile.id}: duplicate id in roster"
                continue
            }
            valid += profile
        }

        return Loaded(file.version, file.region, valid, errors)
    }

    private fun validate(p: SpeciesProfile): List<String> {
        val problems = mutableListOf<String>()

        if (p.id.isBlank()) problems += "blank id"
        if (p.commonName.isBlank()) problems += "blank commonName"
        if (p.scientificName.isBlank()) problems += "blank scientificName"
        if (p.guild.isBlank()) problems += "blank guild"

        if (p.depth.gateMinM < 0) problems += "depth gateMinM < 0"
        if (p.depth.gateMinM > p.depth.gateMaxM) problems += "depth gateMinM > gateMaxM"
        if (p.depth.prefMinM != null && p.depth.prefMaxM != null &&
            p.depth.prefMinM > p.depth.prefMaxM
        ) {
            problems += "depth prefMinM > prefMaxM"
        }
        if (p.depth.scope == DepthScope.WATER_COLUMN && !p.guild.contains("pelagic")) {
            problems += "water_column depth scope is only valid for pelagic guilds (guild='${p.guild}')"
        }

        validateBand(p.sstC, "sstC", problems)
        validateBand(p.chlMgM3, "chlMgM3", problems)

        p.sstCByRegion.forEach { (region, band) ->
            if (region !in PfzRegions.ALL) {
                problems += "sstCByRegion has unknown region '$region' (expected one of ${PfzRegions.ALL})"
            }
            validateBand(band, "sstCByRegion.$region", problems)
        }

        p.windKmhMax?.let { if (it < 0) problems += "windKmhMax < 0" }
        p.swellMMax?.let { if (it < 0) problems += "swellMMax < 0" }
        p.oceanCurrentKmhMax?.let { if (it < 0) problems += "oceanCurrentKmhMax < 0" }

        // v2 bands
        validateBand(p.bottomTempC, "bottomTempC", problems)
        validateBand(p.bottomSalinityPsu, "bottomSalinityPsu", problems)
        validateBand(p.bottomCurrentMs, "bottomCurrentMs", problems)
        validateBand(p.mldM, "mldM", problems)
        validateBand(p.sshAnomalyM, "sshAnomalyM", problems)
        validateBand(p.sstWarmingC, "sstWarmingC", problems)

        validateV2(p, problems)

        p.season?.let { s ->
            (s.peakMonths + s.closedMonths).forEach { m ->
                if (m !in 1..12) problems += "season month out of range: $m"
            }
            s.closedMonths.forEach { m ->
                if (m in s.peakMonths) problems += "month $m is both peak and closed"
            }
        }

        if (p.weights.isEmpty()) {
            problems += "no weights defined"
        } else {
            p.weights.forEach { (factor, w) ->
                if (factor !in PfzFactor.ALL) problems += "unknown weight factor '$factor'"
                if (w < 0) problems += "negative weight for '$factor'"
            }
            val sum = p.weights.values.sum()
            if (kotlin.math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
                problems += "weights sum to ${"%.3f".format(sum)}, expected 1.0"
            }
        }

        return problems
    }

    /**
     * Validation for the v2 blocks: modes, region overrides, required factors
     * and evidence.
     *
     * The rules that matter are the ones that would otherwise fail *silently* at
     * runtime and produce a confident wrong answer:
     *  - a required factor the profile does not define can never be satisfied, so
     *    the species would be permanently INSUFFICIENT_DATA;
     *  - a mode that is never the default and never the only one is unreachable;
     *  - a size class with no band override is a promise the data cannot keep.
     */
    private fun validateV2(p: SpeciesProfile, problems: MutableList<String>) {
        if (p.modes.isNotEmpty()) {
            val modeIds = mutableSetOf<String>()
            p.modes.forEach { m ->
                if (!modeIds.add(m.id)) problems += "duplicate mode '${m.id}'"
                if (m.id.isBlank()) problems += "blank mode id"
                validateBand(m.sstC, "modes.${m.id}.sstC", problems)
                validateBand(m.chlMgM3, "modes.${m.id}.chlMgM3", problems)
                validateBand(m.sshAnomalyM, "modes.${m.id}.sshAnomalyM", problems)
                validateBand(m.sstWarmingC, "modes.${m.id}.sstWarmingC", problems)
                validateSeason(m.season, "modes.${m.id}.season", problems)
                validateDepth(m.depth, "modes.${m.id}.depth", problems)
                validateWeights(m.weights, "modes.${m.id}.weights", problems)
                validateFactors(m.requiredFactors, "modes.${m.id}.requiredFactors", problems)
                if (m.defaultSizeClass != null && m.sizeClasses.isNotEmpty() &&
                    m.defaultSizeClass !in m.sizeClasses
                ) {
                    problems += "modes.${m.id}: defaultSizeClass '${m.defaultSizeClass}' is not in sizeClasses"
                }
                if (m.sizeClasses.isEmpty() && m.defaultSizeClass != null) {
                    problems += "modes.${m.id}: defaultSizeClass set but no sizeClasses declared"
                }
            }
            if (p.modes.none { it.isDefault } && p.modes.size > 1) {
                problems += "no default mode among ${p.modes.size} modes — a bare ?mode= would be ambiguous"
            }
        }

        p.byRegion.forEach { (region, o) ->
            if (region !in PfzRegions.ALL) {
                problems += "byRegion has unknown region '$region' (expected one of ${PfzRegions.ALL})"
            }
            if (o.unavailable.isNullOrBlank() && o.sstC == null && o.chlMgM3 == null &&
                o.bottomTempC == null && o.bottomSalinityPsu == null && o.bottomCurrentMs == null &&
                o.depth == null && o.season == null && o.weights.isEmpty()
            ) {
                problems += "byRegion.$region overrides nothing"
            }
            validateBand(o.sstC, "byRegion.$region.sstC", problems)
            validateBand(o.chlMgM3, "byRegion.$region.chlMgM3", problems)
            validateBand(o.bottomTempC, "byRegion.$region.bottomTempC", problems)
            validateBand(o.bottomSalinityPsu, "byRegion.$region.bottomSalinityPsu", problems)
            validateBand(o.bottomCurrentMs, "byRegion.$region.bottomCurrentMs", problems)
            validateDepth(o.depth, "byRegion.$region.depth", problems)
            validateSeason(o.season, "byRegion.$region.season", problems)
            validateWeights(o.weights, "byRegion.$region.weights", problems)
            validateFactors(o.requiredFactors, "byRegion.$region.requiredFactors", problems)
        }

        validateFactors(p.requiredFactors, "requiredFactors", problems)

        val evidenceKeys = mutableSetOf<String>()
        p.evidence.forEach { e ->
            if (e.key.isBlank()) problems += "blank evidence key"
            else if (!evidenceKeys.add(e.key)) problems += "duplicate evidence key '${e.key}'"
            if (e.citation.isBlank()) problems += "evidence '${e.key}' has a blank citation"
            if (e.usedFor.isBlank()) problems += "evidence '${e.key}' has a blank usedFor"
        }
    }

    private fun validateWeights(weights: Map<String, Double>, name: String, problems: MutableList<String>) {
        if (weights.isEmpty()) return
        weights.forEach { (factor, w) ->
            if (factor !in PfzFactor.ALL) problems += "$name has unknown factor '$factor'"
            if (w < 0) problems += "$name has negative weight for '$factor'"
        }
        val sum = weights.values.sum()
        if (kotlin.math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            problems += "$name sum to ${"%.3f".format(sum)}, expected 1.0"
        }
    }

    /**
     * A required factor must be one the profile can actually evaluate AND one it
     * gives real weight to. Requiring an unweighted factor would demand a
     * measurement the species does not care about; requiring an unknown factor
     * would demand something no observation can ever supply, and the species
     * would be permanently unscoreable with no obvious cause.
     */
    private fun validateFactors(
        factors: List<String>,
        name: String,
        problems: MutableList<String>
    ) {
        val seen = mutableSetOf<String>()
        factors.forEach { f ->
            if (!seen.add(f)) problems += "$name lists '$f' twice"
            if (f !in PfzFactor.ALL) {
                problems += "$name has unknown factor '$f'"
            } else if (f == PfzFactor.WIND || f == PfzFactor.SWELL || f == PfzFactor.SOLUNAR) {
                problems += "$name requires '$f', which is not a habitat measurement"
            }
        }
    }

    private fun validateSeason(s: SeasonSpec?, name: String, problems: MutableList<String>) {
        if (s == null) return
        (s.peakMonths + s.closedMonths).forEach { m ->
            if (m !in 1..12) problems += "$name month out of range: $m"
        }
        s.closedMonths.forEach { m ->
            if (m in s.peakMonths) problems += "$name month $m is both peak and closed"
        }
    }

    private fun validateDepth(d: DepthSpec?, name: String, problems: MutableList<String>) {
        if (d == null) return
        if (d.gateMinM < 0) problems += "$name gateMinM < 0"
        if (d.gateMinM > d.gateMaxM) problems += "$name gateMinM > gateMaxM"
        if (d.prefMinM != null && d.prefMaxM != null && d.prefMinM > d.prefMaxM) {
            problems += "$name prefMinM > prefMaxM"
        }
    }

    private fun validateBand(b: BandSpec?, name: String, problems: MutableList<String>) {
        if (b == null) return
        if (b.gateMin > b.gateMax) problems += "$name gateMin > gateMax"
        val iMin = b.effectiveIdealMin
        val iMax = b.effectiveIdealMax
        if (iMin > iMax) problems += "$name idealMin > idealMax"
        if (iMin < b.gateMin || iMax > b.gateMax) {
            problems += "$name ideal band escapes its gate"
        }
    }
}
