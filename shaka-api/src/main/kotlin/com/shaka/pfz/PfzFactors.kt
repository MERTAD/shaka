package com.shaka.pfz

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-factor evaluators for PFZ.
 *
 * Every function takes the species' [SpeciesProfile] and the [PfzObservation] and
 * returns a [FactorOutcome]. None of them invent a measurement: a null input
 * becomes [FactorOutcome.Missing], never a neutral or favourable default.
 *
 * Hard gates and soft scores are kept strictly apart. A gate means "this is
 * known to be impossible", which yields [PfzStatus.UNAVAILABLE]. A soft score
 * means "this is possible but more or less favourable".
 */
object PfzFactors {

    /**
     * Conservative shared operational envelope, used only when a species does
     * not declare its own wind or swell limit.
     *
     * This is a threshold assumption, not fabricated data: the wind and swell
     * values themselves are real measurements. When the default is used the
     * factor is reported as EXPERT-tier so the assumption is visible.
     */
    const val DEFAULT_WIND_KMH_MAX = 25.0
    const val DEFAULT_SWELL_M_MAX = 1.2

    // ---------------------------------------------------------------- depth

    /**
     * Seafloor depth vs the species' preferred band, for substrate-bound species.
     *
     * Outside the gate the species cannot be there at all. Inside, the score
     * falls off linearly from the preferred band to zero at the gate edges, so
     * a 20 m spot is excellent for a 5-30 m species and poor for a 100-400 m one.
     *
     * Species whose band is [DepthScope.WATER_COLUMN] (surface-layer pelagics
     * such as bluefin, little tunny and swordfish) are excluded entirely: their
     * band describes depth below the surface, so seafloor depth is not a
     * constraint and must never gate or score them.
     */
    fun depth(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.depth

        // Water-column species (bluefin, little tunny, swordfish) live in the
        // top layer and are routinely found over basins thousands of metres
        // deep. Their [spec] band is a below-surface band, not a seafloor one,
        // so bathymetry must not gate or score them at all: report it missing
        // and let the real drivers (SST, fronts, anomaly) carry the weight.
        if (spec.scope == DepthScope.WATER_COLUMN) {
            return FactorOutcome.Missing(
                "water-column habitat (${spec.gateMinM.roundToInt()}-${spec.gateMaxM.roundToInt()}m " +
                    "below surface); seafloor depth is not a constraint for this species"
            )
        }

        val d = obs.depthM ?: return FactorOutcome.Missing("no bathymetry depth for spot")

        if (d < spec.gateMinM || d > spec.gateMaxM) {
            return FactorOutcome.Gated(
                "depth: spot ${d.roundToInt()}m, species requires " +
                    "${spec.gateMinM.roundToInt()}-${spec.gateMaxM.roundToInt()}m"
            )
        }

        val prefMin = spec.prefMinM ?: spec.gateMinM
        val prefMax = spec.prefMaxM ?: spec.gateMaxM
        val score = trapezoid(d, prefMin, prefMax, spec.gateMinM, spec.gateMaxM)
        return FactorOutcome.Scored(score, spec.conf)
    }

    // ------------------------------------------------------------------ sst

    /**
     * Sea-surface temperature vs the species' thermal band.
     *
     * SST is the strongest single predictor for pelagics and is well cited for
     * bluefin, so it is weighted heavily there and gates hard outside the band.
     *
     * A region-specific band wins over the basin-wide one when the profile
     * declares one, because several species have materially different thermal
     * optima between the north-western Mediterranean and the Maghreb. That
     * override has already been applied by [SpeciesProfile.resolve], so this
     * reads a single resolved band and does not re-derive the region.
     */
    fun sst(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.sstC ?: return FactorOutcome.Missing("no SST band for species")
        val t = obs.waterTempC ?: return FactorOutcome.Missing("no water temperature measured")
        return scoreBand(spec, t, "sst")
    }

    // ------------------------------------------------------------------ chl

    /**
     * Chlorophyll-a concentration.
     *
     * NOTE: for bluefin this is a weak proxy. Druon et al. 2011 identifies the
     * chlorophyll-a *front* (gradCHL) as the driver of feeding habitat, not
     * concentration alone. The app stores one point value, so [PfzObservation
     * .chlaGradient] is always null in v1 and bluefin's chl band is scored
     * EXPERT-tier with that caveat carried in the profile note.
     */
    /**
     * Chlorophyll-a spatial gradient (gradCHL).
     *
     * This is the single most important factor in the whole package and it is
     * the one we cannot measure. Druon et al. 2011 identifies the chlorophyll-a
     * *front* — the gradient between cells — as the driver of Atlantic bluefin
     * feeding habitat, not the concentration at any one point. The app fetches
     * a single point value per spot, which is structurally incapable of
     * representing a front.
     *
     * So rather than quietly proxying this with concentration (which is what
     * [chlorophyll] does, at EXPERT tier), it is modelled as its own factor and
     * currently always reports Missing. That has a real, intended consequence:
     * bluefin loses weight budget to a factor it cannot resolve, so its
     * confidence drops and `missingFactors` names the gap in the response.
     * A bluefin score in this build is knowingly incomplete, and says so.
     *
     * When a v2 front-finder supplies a gradient, this returns a scored
     * outcome and bluefin's confidence recovers. Nothing else must change.
     */
    fun chlaGradient(obs: PfzObservation): FactorOutcome {
        val g = obs.chlaGradient
            ?: return FactorOutcome.Missing(
                "chlorophyll-a gradient (gradCHL) not computed; grid did not resolve"
            )

        // Scored against the shared front threshold so a real front reads as a
        // favourable signal and a flat field as neutral. EXPERT tier: the
        // per-km threshold is an operational starting point, not a published
        // universal Mediterranean value.
        return FactorOutcome.Scored(
            gradientScore(g, CHL_FRONT_MG_M3_PER_KM),
            FactorConfidence.EXPERT
        )
    }

    fun chlorophyll(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.chlMgM3 ?: return FactorOutcome.Missing("no chlorophyll band for species")
        val c = obs.chlorophyllMgM3
            ?: return FactorOutcome.Missing("no chlorophyll measurement")

        if (spec.preference == Preference.BAND) {
            return scoreBand(spec, c, "chl")
        }

        // Monotonic case: no ideal band, score straight across the gate.
        if (c < spec.gateMin || c > spec.gateMax) {
            return FactorOutcome.Gated(
                "chl: ${fmt(c)}mg/m3 outside ${fmt(spec.gateMin)}-${fmt(spec.gateMax)}"
            )
        }
        val score = when (spec.preference) {
            Preference.LOWER_BETTER -> descending(c, spec.gateMin, spec.gateMax)
            Preference.HIGHER_BETTER -> descending(c, spec.gateMax, spec.gateMin)
            Preference.BAND -> error("unreachable")
        }
        return FactorOutcome.Scored(score, spec.conf)
    }

    /*
     * Spatial front detection thresholds, shared with [PfzGridService].
     *
     * Starting bands from INCOIS's published PFZ guidance — an SST front at
     * ~0.3°C per 3 pixels and a chlorophyll front at ~0.5 mg/m3 per 5 pixels —
     * expressed per kilometre at the products' native resolution. These are
     * operational starting points, not published Mediterranean citations, so
     * every score that leans on them carries FactorConfidence.EXPERT.
     */
    const val SST_FRONT_C_PER_KM = 0.10
    const val CHL_FRONT_MG_M3_PER_KM = 0.10

    /**
     * Sea-surface temperature spatial gradient (the thermal front).
     *
     * [PfzObservation.sstGradientCkm] is the strongest in-box gradient in
     * °C/km. The score rises from a neutral 50 at no gradient to 100 at twice
     * the [SST_FRONT_C_PER_KM] detection threshold — a front is the signal,
     * so the presence of a strong one is favourable and the absence is not a
     * punishment, merely nothing to report. Null gradient = missing, never a
     * quiet zero.
     */
    fun sstGradient(obs: PfzObservation): FactorOutcome {
        val g = obs.sstGradientCkm
            ?: return FactorOutcome.Missing("SST spatial gradient (frontal) not computed")
        return FactorOutcome.Scored(gradientScore(g, SST_FRONT_C_PER_KM), FactorConfidence.EXPERT)
    }

    /**
     * Sea-surface temperature anomaly (deviation from the monthly climatology).
     *
     * Large |anomaly| marks a dynamic water mass — recent upwelling or a
     * heating episode — the kind of transient signal fronts concentrate on.
     * A larger anomaly is scored higher, saturating near ±2°C. EXPERT tier:
     * the exact response curve is this app's assumption, not a citation.
     */
    fun sstAnomaly(obs: PfzObservation): FactorOutcome {
        val a = obs.sstAnomalyC
            ?: return FactorOutcome.Missing("SST anomaly not measured")
        val magnitude = abs(a)
        val score = (50 + min(50.0, magnitude * 25.0)).roundToInt().coerceIn(0, 100)
        return FactorOutcome.Scored(score, FactorConfidence.EXPERT)
    }

    /**
     * Bathymetric slope across the spot's immediate vicinity (m of depth per km).
     *
     * A sharp depth change is structure — a shelf edge or pinnacle that
     * aggregates fish — so a steeper slope is scored higher, saturating near
     * 2 m/km. EXPERT tier: the response curve is an assumption.
     */
    fun depthGradient(obs: PfzObservation): FactorOutcome {
        val g = obs.depthGradientMperKm
            ?: return FactorOutcome.Missing("bathymetric gradient not computed")
        val score = (40 + min(60.0, g * 30.0)).roundToInt().coerceIn(0, 100)
        return FactorOutcome.Scored(score, FactorConfidence.EXPERT)
    }

    /** 50 at no gradient, 100 at twice the front detection threshold. */
    private fun gradientScore(gradient: Double, threshold: Double): Int =
        (50 + 50 * (gradient / (2 * threshold)).coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, 100)

    // ------------------------------------------------------- wind and swell

    /**
     * Wind vs the operational limit for reaching and working the spot.
     *
     * Exceeding the limit is a gate, not a low score: "potential fishing zone"
     * implies you can actually get there. Note the current Open-Meteo marine
     * path fabricates swell fallbacks, so a null here means the caller has no
     * trustworthy value and the factor is excluded.
     */
    fun wind(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val w = obs.windSpeedKmh ?: return FactorOutcome.Missing("no wind speed measured")
        val max = profile.windKmhMax ?: DEFAULT_WIND_KMH_MAX
        if (w > max) {
            return FactorOutcome.Gated(
                "wind: ${w.roundToInt()}km/h exceeds ${max.roundToInt()}km/h operational limit"
            )
        }
        return FactorOutcome.Scored(
            descending(w, 0.0, max),
            if (profile.windKmhMax != null) FactorConfidence.CITED else FactorConfidence.EXPERT
        )
    }

    fun swell(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val s = obs.swellHeightM ?: return FactorOutcome.Missing("no swell height measured")
        val max = profile.swellMMax ?: DEFAULT_SWELL_M_MAX
        if (s > max) {
            return FactorOutcome.Gated(
                "swell: ${fmt(s)}m exceeds ${fmt(max)}m operational limit"
            )
        }
        return FactorOutcome.Scored(
            descending(s, 0.0, max),
            if (profile.swellMMax != null) FactorConfidence.CITED else FactorConfidence.EXPERT
        )
    }

    // -------------------------------------------------------------- current

    /**
     * Surface current speed. No profile declares this yet and the value is not
     * currently mapped into [com.shaka.model.OceanData], so this always reports
     * Missing rather than guessing.
     */
    fun current(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val max = profile.oceanCurrentKmhMax
            ?: return FactorOutcome.Missing("no current threshold for species")
        val v = obs.oceanCurrentVelocityKmh
            ?: return FactorOutcome.Missing("no current velocity measured")
        if (v > max) {
            return FactorOutcome.Gated("current: ${fmt(v)}km/h exceeds ${fmt(max)}km/h")
        }
        return FactorOutcome.Scored(descending(v, 0.0, max), profile.depth.conf)
    }

    // ------------------------------------------------- v2 benthic and mesoscale

    /**
     * Potential temperature at the sea floor, °C (Copernicus `bottomT`).
     *
     * This is the term that makes a demersal profile a demersal profile. Colloca
     * et al. (2014) give a 5–95 percentile SBT band of 11.78–15.04 °C with a
     * 13.8 ± 1 °C optimum for high-recruitment cells, and Lleonart (2001)
     * independently reports young hake absent from the north-western
     * Mediterranean wherever the bottom exceeds 15 °C — so above the band the
     * species is *absent*, not merely discouraged, and this gates.
     *
     * Note the consequence for red shrimp, whose cited band is 13.6–13.8 °C
     * (Maiorano et al. 2020): that band is only 0.2 °C wide, so it gates
     * hard almost everywhere. That is the correct reading — the band
     * describes Levantine Intermediate Water, and the species' absence outside
     * the central basins is what the narrow gate is detecting.
     */
    fun bottomTemp(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.bottomTempC
            ?: return FactorOutcome.Missing("no bottom temperature band for species")
        val t = obs.bottomTempC
            ?: return FactorOutcome.Missing("no bottom temperature measured")
        return scoreBand(spec, t, "bottom_temp")
    }

    /**
     * Practical salinity at the sea floor, psu, from the deepest non-masked
     * level of Copernicus `so`.
     *
     * Red shrimp are the only pilot species scored on this, for the same reason
     * as [bottomTemp]: their 38.1–38.5 psu band identifies Levantine
     * Intermediate Water. Measured live at 38.51–38.60 psu off the Balearic
     * abyssal plain, which is already outside it — consistent with the region
     * gate, not contradicting it.
     */
    fun bottomSalinity(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.bottomSalinityPsu
            ?: return FactorOutcome.Missing("no bottom salinity band for species")
        val s = obs.bottomSalinityPsu
            ?: return FactorOutcome.Missing("no bottom salinity measured")
        return scoreBand(spec, s, "bottom_salinity")
    }

    /**
     * Bottom current speed, m/s, from the deepest non-masked level of
     * Copernicus `uo`/`vo`.
     *
     * Deliberately m/s, not km/h: Colloca et al. (2014) fit hake recruitment to
     * a maximum bottom current of 0.034 m/s, and a unit slip here silently
     * turns a passing cell into a gated one (0.034 m/s is 0.12 km/h). The
     * threshold is an upper limit, so this is LOWER_BETTER across the band.
     */
    fun bottomCurrent(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.bottomCurrentMs
            ?: return FactorOutcome.Missing("no bottom current band for species")
        val v = obs.bottomCurrentMs
            ?: return FactorOutcome.Missing("no bottom current measured")
        return scoreBand(spec, v, "bottom_current")
    }

    /**
     * Mixed layer thickness, m, from Copernicus `mlotst` (de Boyer Montégut 2004).
     *
     * A thin mixed layer concentrates the surface thermal structure a
     * stratification-dependent feeder is looking for. None of the five pilot
     * species is currently gated on it, so the evaluator exists for the 22
     * profiles still to be migrated rather than for a band that is already
     * declared.
     */
    fun mld(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.mldM ?: return FactorOutcome.Missing("no mixed layer band for species")
        val d = obs.mldM ?: return FactorOutcome.Missing("no mixed layer depth measured")
        return scoreBand(spec, d, "mld")
    }

    /**
     * Sea surface height anomaly, m — DERIVED as daily `zos` minus the monthly
     * mean `zos`, because this product's static dataset publishes no `mdt`.
     *
     * Druon et al. (2016) put bluefin *feeding* clusters at SSHa >= -0.10 m. That
     * is a threshold, not a gradient, so the band is declared HIGHER_BETTER over
     * -0.10..0.0: below -0.10 m the cell is gated (the feature is absent), and at
     * or above it the score saturates at 100. A decline above the threshold would
     * be a response curve the paper does not contain, so there is none.
     *
     * **Known limitation, measured not assumed.** This factor barely varies
     * between cells: 18 mm of spread across a 9 km box but 276 mm across a
     * 5x8 degree one. In zone analysis it will therefore behave as a regional
     * gate that is constant across the requested box unless a wider mesoscale
     * window is fetched. It is a legitimate required factor — Druon needed it to
     * de-confound SSHa from SST — but it must not be mistaken for a local one.
     */
    fun sshAnomaly(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.sshAnomalyM
            ?: return FactorOutcome.Missing("no SSH anomaly band for species")
        val a = obs.sshAnomalyM ?: return FactorOutcome.Missing("no SSH anomaly measured")
        return scoreBand(spec, a, "ssh_anomaly")
    }

    /**
     * 30-day change in sea surface temperature, °C (Druon et al.'s dSST30), and
     * also derived by us from the SST series.
     *
     * Bluefin *spawning* is associated with a high positive dSST30 — spring
     * stratification building up. The paper says "high" and gives no number, so
     * the band is EXPERT tier: the 0 °C floor is defensible (cooling is not the
     * documented condition) and the upper end is this app's assumption about how
     * much 30-day warming a Mediterranean spring produces.
     */
    fun sstWarming(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.sstWarmingC
            ?: return FactorOutcome.Missing("no SST warming band for species")
        val w = obs.sstWarmingC ?: return FactorOutcome.Missing("no SST trend measured")
        return scoreBand(spec, w, "sst_warming")
    }

    // -------------------------------------------------------------- solunar

    /**
     * Solunar day rating, 0-5 from api.solunar.org, with a moon-phase fallback.
     *
     * Unlike [com.shaka.scoring.ShakaScorer], having no solunar data at all is
     * [FactorOutcome.Missing] rather than a neutral 55. Silently scoring an
     * unseen factor as average is exactly how a "favourable" score appears from
     * no data.
     */
    fun solunar(obs: PfzObservation): FactorOutcome {
        obs.solunarDayRating?.let { rating ->
            val score = when (rating.coerceIn(0, 5)) {
                0 -> 30
                1 -> 40
                2 -> 55
                3 -> 65
                4 -> 80
                else -> 90
            }
            return FactorOutcome.Scored(score, FactorConfidence.CITED)
        }

        obs.moonPhase?.let { phase ->
            val score = when (phase) {
                "new_moon" -> 70
                "full_moon" -> 65
                "waxing_gibbous", "waning_gibbous" -> 60
                "first_quarter", "last_quarter" -> 55
                "waxing_crescent", "waning_crescent" -> 50
                else -> return FactorOutcome.Missing("unrecognised moon phase '$phase'")
            }
            return FactorOutcome.Scored(score, FactorConfidence.EXPERT)
        }

        return FactorOutcome.Missing("no solunar or moon phase data")
    }

    // --------------------------------------------------------------- season

    /**
     * Month-of-year suitability.
     *
     * [SeasonSpec.closedMonths] is a hard gate. Otherwise the score decays with
     * circular distance from the nearest peak month, so December and January
     * wrap correctly around a January peak.
     */
    fun season(profile: ResolvedProfile, obs: PfzObservation): FactorOutcome {
        val spec = profile.season
            ?: return FactorOutcome.Missing("no season data for species")
        if (spec.peakMonths.isEmpty()) {
            return FactorOutcome.Missing("no peak months for species")
        }

        val month = obs.date.monthValue
        if (month in spec.closedMonths) {
            return FactorOutcome.Gated("season: month $month is a closed month for ${profile.profile.id}")
        }

        val distance = spec.peakMonths.minOf { circularMonthDistance(month, it) }
        val score = when (distance) {
            0 -> 100
            1 -> 75
            2 -> 55
            else -> 40
        }
        return FactorOutcome.Scored(score, spec.conf)
    }

    // ----------------------------------------------------------------- maths

    /**
     * Trapezoid scoring: 100 across [idealMin, idealMax], decaying linearly to
     * 0 at [gateMin] and [gateMax].
     */
    private fun trapezoid(
        value: Double,
        idealMin: Double,
        idealMax: Double,
        gateMin: Double,
        gateMax: Double
    ): Int {
        val raw = when {
            value < idealMin -> decay(value, gateMin, idealMin)
            value > idealMax -> decay(value, idealMax, gateMax, rising = false)
            else -> 100.0
        }
        return raw.roundToInt().coerceIn(0, 100)
    }

    /** 100 at [best] falling linearly to 0 at [worst]. */
    private fun decay(value: Double, worst: Double, best: Double, rising: Boolean = true): Double {
        if (best == worst) return 100.0
        val t = if (rising) (value - worst) / (best - worst)
        else (best - value) / (best - worst)
        return (t.coerceIn(0.0, 1.0) * 100.0)
    }

    /** 100 at [bestValue] falling to 0 at [worstValue]. */
    private fun descending(value: Double, bestValue: Double, worstValue: Double): Int =
        decay(value, worstValue, bestValue, rising = if (bestValue < worstValue) false else true)
            .roundToInt()
            .coerceIn(0, 100)

    private fun scoreBand(spec: BandSpec, value: Double, label: String): FactorOutcome {
        if (value < spec.gateMin || value > spec.gateMax) {
            return FactorOutcome.Gated(
                "$label: ${fmt(value)} outside ${fmt(spec.gateMin)}-${fmt(spec.gateMax)}"
            )
        }
        val score = when (spec.preference) {
            Preference.BAND ->
                trapezoid(value, spec.effectiveIdealMin, spec.effectiveIdealMax, spec.gateMin, spec.gateMax)
            Preference.LOWER_BETTER -> descending(value, spec.gateMin, spec.gateMax)
            Preference.HIGHER_BETTER -> descending(value, spec.gateMax, spec.gateMin)
        }
        return FactorOutcome.Scored(score, spec.conf)
    }

    /** Shortest distance between two months on a 12-month circle. */
    private fun circularMonthDistance(a: Int, b: Int): Int {
        val raw = abs(a - b) % 12
        return minOf(raw, 12 - raw)
    }

    private fun fmt(v: Double): String {
        val r = (v * 100).roundToInt() / 100.0
        return if (r == r.toLong().toDouble()) "${r.toLong()}" else "$r"
    }
}
