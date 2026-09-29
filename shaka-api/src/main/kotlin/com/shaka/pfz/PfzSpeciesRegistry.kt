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
