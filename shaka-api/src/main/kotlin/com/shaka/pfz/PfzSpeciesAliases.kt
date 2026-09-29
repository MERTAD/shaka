package com.shaka.pfz

/**
 * PFZ-local species alias resolution.
 *
 * This is deliberately NOT the global `SpeciesNormalizer`. That normalizer is
 * shared with the SoCal fishing-intel pipeline, which maps `"bonito" ->
 * "bonito"` as a distinct Pacific species. The PFZ roster treats "bonito" as
 * the common Spanish/Maghrebi name for Little Tunny (*Euthynnus alletteratus*).
 * Remapping it globally would corrupt SoCal reports, so the two vocabularies
 * stay separate and this layer is the only place the divergence lives.
 *
 * Aliases are multilingual on purpose: the roster is Mediterranean, so the
 * same fish arrives as "dorada", "dorade", "orata" and "d-enhadda".
 */
object PfzSpeciesAliases {

    private val PUNCT_TRIMMER = Regex("^[\\s.,;:]+|[\\s.,;:]+$")

    /** Common name / vernacular / scientific-epithet -> PFZ species id. */
    private val ALIASES: Map<String, String> = buildMap {
        // --- pelagics -------------------------------------------------------
        put("bluefin", "bluefin_tuna")
        put("bluefin tuna", "bluefin_tuna")
        put("bluefin_tuna", "bluefin_tuna")
        put("albacora", "bluefin_tuna")
        put("atun", "bluefin_tuna")
        put("atún", "bluefin_tuna")
        put("thon", "bluefin_tuna")
        put("thunnus thynnus", "bluefin_tuna")

        // "bonito" is Little Tunny in Spanish/Maghrebi usage, NOT Sarda sarda.
        put("little tunny", "little_tunny")
        put("bonito", "little_tunny")
        put("tunaigger", "little_tunny")
        put("false albacore", "little_tunny")
        put("euthynnus alletteratus", "little_tunny")

        // --- small pelagics -------------------------------------------------
        put("anchovy", "anchovy")
        put("anchoa", "anchovy")
        put("anchois", "anchovy")
        put("engraulis encrasicolus", "anchovy")

        put("sardine", "sardine")
        put("sardines", "sardine")
        put("sardina", "sardine")
        put("sardina pilchardus", "sardine")

        put("sardinella", "sardinella")
        put("sardinelle", "sardinella")
        put("allache", "sardinella")
        put("sardinella aurita", "sardinella")

        put("horse mackerel", "horse_mackerel")
        put("jack mackerel", "horse_mackerel")
        put("caballa", "horse_mackerel")
        put("chincharda", "horse_mackerel")
        put("trachurus trachurus", "horse_mackerel")

        // ASSUMED SPECIES. Scomber is a genus, not a species. Defaulted to
        // S. scombrus (Atlantic chub mackerel). The other candidate is
        // S. japonicus. Change this one line to switch.
        put("scomber", "scomber")
        put("chub mackerel", "scomber")
        put("scomber scombrus", "scomber")

        put("bogue", "bogue")
        put("boops boops", "bogue")
        put("boga", "bogue")

        put("swordfish", "swordfish")
        put("espada", "swordfish")
        put("xiphias gladius", "swordfish")

        // --- demersal: grouper ----------------------------------------------
        put("dusky grouper", "dusky_grouper")
        put("epinephelus marginatus", "dusky_grouper")
        put("cherne", "dusky_grouper")

        put("white grouper", "white_grouper")
        put("epinephelus aeneus", "white_grouper")
        put("mero blanco", "white_grouper")

        // --- demersal: bream / sparids -------------------------------------
        put("gilthead seabream", "gilthead_seabream")
        put("gilthead", "gilthead_seabream")
        put("dorada", "gilthead_seabream")
        put("dorade", "gilthead_seabream")
        put("orata", "gilthead_seabream")
        put("sparus aurata", "gilthead_seabream")

        put("white seabream", "white_seabream")
        put("white bream", "white_seabream")
        put("sargo", "white_seabream")
        put("diplodus sargus", "white_seabream")

        put("common pandora", "common_pandora")
        put("pandora", "common_pandora")
        put("pargo", "common_pandora")
        put("pagellus erythrinus", "common_pandora")

        put("common dentex", "common_dentex")
        put("dentex", "common_dentex")
        put("dentex dentex", "common_dentex")

        put("striped red mullet", "striped_red_mullet")
        put("red mullet striped", "striped_red_mullet")
        put("mullus barbatus", "striped_red_mullet")

        put("red mullet", "red_mullet")
        put("mullus surmuletus", "red_mullet")

        // --- demersal: other -------------------------------------------------
        put("red scorpionfish", "red_scorpiofish")
        put("scorpionfish", "red_scorpiofish")
        put("escorpena", "red_scorpiofish")
        put("scorpaena porcus", "red_scorpiofish")

        put("european seabass", "european_seabass")
        put("sea bass", "european_seabass")
        put("loup de mer", "european_seabass")
        put("dicentrarchus labrax", "european_seabass")

        put("european hake", "european_hake")
        put("hake", "european_hake")
        put("merlu", "european_hake")
        put("merluccius merluccius", "european_hake")

        put("european conger", "european_conger")
        put("conger", "european_conger")
        put("congrio", "european_conger")
        put("conger conger", "european_conger")

        put("common sole", "common_sole")
        put("sole", "common_sole")
        put("lenguado", "common_sole")
        put("solea solea", "common_sole")

        put("grey mullet", "grey_mullet")
        put("mullet", "grey_mullet")
        put("mugil cephalus", "grey_mullet")

        // --- cephalopods -----------------------------------------------------
        put("common octopus", "common_octopus")
        put("octopus", "common_octopus")
        put("pulpo", "common_octopus")
        put("octopus vulgaris", "common_octopus")

        put("common cuttlefish", "common_cuttlefish")
        put("cuttlefish", "common_cuttlefish")
        put("sepia", "common_cuttlefish")
        put("seiche", "common_cuttlefish")
        put("sepia officinalis", "common_cuttlefish")

        // --- deep crustaceans ------------------------------------------------
        put("deep rose shrimp", "deep_rose_shrimp")
        put("gamba", "deep_rose_shrimp")
        put("parapenaeus longirostris", "deep_rose_shrimp")

        put("red shrimp", "red_shrimp")
        put("aristeomorpha foliacea", "red_shrimp")
    }

    /**
     * Resolve a free-text species name to a PFZ species id.
     *
     * Returns null when the name is not in the PFZ roster. This intentionally
     * does not fall through to the global `SpeciesNormalizer`: a Pacific
     * species must not silently acquire a Mediterranean profile.
     */
    fun resolve(raw: String): String? {
        val cleaned = PUNCT_TRIMMER.replace(raw.trim(), "").lowercase()
        if (cleaned.isBlank()) return null
        return ALIASES[cleaned]
    }

    /** Every alias string the PFZ vocabulary accepts. Useful for API docs and tests. */
    fun allAliases(): Set<String> = ALIASES.keys

    /**
     * Canonical ids present in [ALIASES] values.
     * Used by tests to assert the JSON roster and the alias table agree.
     */
    fun allSpeciesIds(): Set<String> = ALIASES.values.toSet()
}
