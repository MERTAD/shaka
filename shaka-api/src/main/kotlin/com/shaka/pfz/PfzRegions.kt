package com.shaka.pfz

/**
 * Macro-region classification for PFZ.
 *
 * Why this exists: several species have genuinely different habitat parameters
 * in different parts of the Mediterranean, and the difference is large enough to
 * invert a verdict. Round sardinella spawns at 23-26.5 C at its north-western
 * range limit (Sabatés et al. 2009) but at 18-21 C off the Western Sahara and
 * across the Maghreb (Ettahiri et al. 2003; Quaatey & Maravelias 1999). With
 * one global band, Algerian water — the prime habitat for the species that
 * dominates Algerian landings — would score as unsuitable.
 *
 * Region labels are matched against the spot id prefix, which is how the
 * curated spot database is already namespaced.
 */
object PfzRegions {

    const val WESTERN = "western_med"
    const val CENTRAL = "central_med"
    const val EASTERN = "eastern_med"
    const val MAGHREB = "maghreb"

    val ALL = listOf(WESTERN, CENTRAL, EASTERN, MAGHREB)

    /** Spot id prefix -> macro-region. */
    private val PREFIX_TO_REGION = mapOf(
        "spain-" to WESTERN,
        "france-" to WESTERN,
        "corsica-" to WESTERN,
        "sardinia-" to CENTRAL,
        "sicily-" to CENTRAL,
        "italy-" to CENTRAL,
        "turkey-" to CENTRAL,
        "greece-" to EASTERN,
        "croatia-" to EASTERN,
        "algeria-" to MAGHREB,
        "algeria" to MAGHREB,
        "tunisia-" to MAGHREB,
        "tunisia" to MAGHREB,
        "morocco-" to MAGHREB,
        "morocco" to MAGHREB
    )

    /** Classify a spot id (e.g. "sicily-ustica-north") or a bare region tag. */
    fun classify(regionOrSpotId: String?): String? {
        if (regionOrSpotId.isNullOrBlank()) return null
        val key = regionOrSpotId.lowercase().trim()
        PREFIX_TO_REGION[key]?.let { return it }
        val prefix = key.substringBefore('-')
        return PREFIX_TO_REGION["$prefix-"]
    }

    /**
     * Resolve the SST band for a species at a location, preferring a regional
     * override and falling back to the basin-wide band.
     *
     * Returns the band together with a flag telling the caller whether a
     * regional override was used, so the response can disclose that the score
     * came from a region-specific citation.
     */
    fun resolveSst(
        profile: SpeciesProfile,
        regionOrSpotId: String?
    ): Pair<BandSpec, Boolean>? {
        val region = classify(regionOrSpotId)
        if (region != null) {
            profile.sstCByRegion[region]?.let { return it to true }
        }
        profile.sstC?.let { return it to false }
        return null
    }
}
