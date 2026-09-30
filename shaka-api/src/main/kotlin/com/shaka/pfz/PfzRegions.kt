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
 * Two classifiers, in this order of authority:
 *
 * 1. **[classifyNamed]** — an explicit region tag or a spot-id prefix. This is
 *    authoritative because it encodes sub-basin knowledge that coordinates do
 *    not: Corsica sits at 8.5-9.4 E, east of Sardinia, yet both are
 *    conventionally Western and Central respectively only because of how the
 *    basins circulate, not where they are on a map.
 * 2. **[classifyByPosition]** — a lat/lon box, for coordinates that have no
 *    spot id. The offshore zone endpoint has nothing but a lat/lon, so without
 *    this a bluefin zone 30 km off Algiers could not be told from one in the
 *    Aegean, and a red shrimp score would be basin-wide where the species does
 *    not occur at all.
 *
 * The boxes are a coarse fallback and are wrong in two places, by design rather
 * than by accident: the Balearic/Ligurian divide at 8 E cuts across Sardinia's
 * western approaches, and the Adriatic is grouped with the Central basin. Both
 * only matter for unnamed coordinates; every named spot resolves through [1].
 */
object PfzRegions {

    const val WESTERN = "western_med"
    const val CENTRAL = "central_med"
    const val EASTERN = "eastern_med"
    const val MAGHREB = "maghreb"

    val ALL = listOf(WESTERN, CENTRAL, EASTERN, MAGHREB)

    /** Spot id prefix -> macro-region. Authoritative for named spots. */
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

    /**
     * Divide between the western and central basins.
     *
     * 8 E keeps the whole of Sardinia (8.15-9.72 E) in the central basin, which
     * is where convention puts it, and still leaves every Spanish and French spot
     * (max 7.32 E) in the western one.
     */
    private const val WEST_CENTRAL_LON = 8.0

    /** North shore of the African margin, from Cape Espichel to the Sicily Channel. */
    private const val MAGHREB_NORTH_LAT = 36.9

    /** East of the Tunisian shelf the African coast turns south; below this it is Libya. */
    private const val MAGHREB_SOUTH_LAT = 34.0

    /** The Tunisian margin's eastern edge, at the Sicily Channel. */
    private const val MAGHREB_EAST_LON = 11.75

    /** Classify an explicit region tag or a spot id (e.g. "sicily-ustica-north"). */
    fun classifyNamed(regionOrSpotId: String?): String? {
        if (regionOrSpotId.isNullOrBlank()) return null
        val key = regionOrSpotId.lowercase().trim()
        PREFIX_TO_REGION[key]?.let { return it }
        if (key in ALL) return key
        val prefix = key.substringBefore('-')
        return PREFIX_TO_REGION["$prefix-"]
    }

    /**
     * North-east corner of the bounding box, which the Mediterranean does not
     * fill: north of the Bosphorus line and east of the Crimea longitude is the
     * Black Sea. Sinop at 42 N, 35 E sits inside a plain lat/lon box, and
     * classifying it as eastern_med would silently un-exclude a red shrimp
     * request off a coast where the species has never been recorded.
     */
    private const val BLACK_SEA_NORTH_LAT = 41.5
    private const val BLACK_SEA_EAST_LON = 28.0

    /**
     * Coarse macro-basin for a bare coordinate.
     *
     * Null outside the Mediterranean, so an offshore request in the Atlantic or
     * the Black Sea reports no region rather than a wrong one.
     */
    fun classifyByPosition(lat: Double, lon: Double): String? {
        if (lat < 30.0 || lat > 46.0 || lon < -6.0 || lon > 36.5) return null
        if (lat >= BLACK_SEA_NORTH_LAT && lon >= BLACK_SEA_EAST_LON) return null
        return when {
            // Aegean, Levant, Cyprus.
            lon >= 20.0 -> EASTERN
            // The African margin: the Gulf of Gabes and the Libyan shelf.
            lat <= MAGHREB_SOUTH_LAT -> MAGHREB
            // Morocco, Algeria and Tunisia north of the Gulf of Gabes.
            lat <= MAGHREB_NORTH_LAT && lon <= MAGHREB_EAST_LON -> MAGHREB
            lon < WEST_CENTRAL_LON -> WESTERN
            // Tyrrhenian, Ionian, Sicily Channel and the Adriatic.
            else -> CENTRAL
        }
    }

    /**
     * Best available region for a place, in strict order of authority.
     *
     * 1. [region] — an explicit tag from the spot inventory. Authoritative: it is
     *    a curated sub-basin assignment, not a guess.
     * 2. [spotId] — the same curated assignment when only the id is available.
     * 3. [lat]/[lon] — the coarse box, for bare coordinates.
     *
     * The three can legitimately disagree (Corsica, Sardinia's western
     * approaches, the Adriatic); the curated ones win because they encode how
     * the basins circulate rather than where a point sits on a map. Each stage
     * falls through only when the one above it resolves to nothing, so an
     * unrecognised tag degrades to the id and then to the position instead of
     * inventing a region.
     */
    fun classify(
        spotId: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        region: String? = null
    ): String? = classifyNamed(region)
        ?: classifyNamed(spotId)
        ?: if (lat != null && lon != null) classifyByPosition(lat, lon) else null
}
