package com.shaka.scoring

import com.shaka.model.ScoreBreakdown
import com.shaka.model.ShakaScore
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Shaka Score Calculator
 * 
 * Calculates an overall score (0-100) for spearfishing conditions.
 * Takes ONLY the primitive values it actually uses — no unused fields.
 * 
 * Inputs:
 *   windSpeedKmh    → Weather score   (28%)
 *   waveHeightM     → Swell score     (22%)
 *   chlorophyllMgM3 → Visibility score (35%)  — satellite chlorophyll-a concentration
 *   solunarDayRating → Solunar score   (15%)  — from api.solunar.org (0-5 scale), with moon phase fallback
 */
object ShakaScorer {

    /**
     * Calculate confidence based on days until target date.
     * Same-day forecasts are most reliable, 30-day out are least.
     */
    fun confidenceForDate(targetDate: String): Int {
        // A blank date means the caller did not choose one. Defaulting to today
        // is the honest reading of "now" and keeps the whole spot-detail
        // response alive; LocalDate.parse("") would otherwise throw and take
        // the entire request down with a 500, which is a far worse answer than
        // a mid-range confidence.
        val target = targetDate.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        val today = LocalDate.now()
        val daysOut = ChronoUnit.DAYS.between(today, target).toInt()

        return when {
            daysOut <= 0 -> 95
            daysOut <= 1 -> 90
            daysOut <= 3 -> 85
            daysOut <= 5 -> 75
            daysOut <= 7 -> 70
            daysOut <= 10 -> 60
            daysOut <= 14 -> 50
            daysOut <= 21 -> 40
            else -> 30
        }
    }

    /**
     * Calculate visibility score from chlorophyll-a concentration (mg/m³).
     * Lower chlorophyll = clearer water = higher score.
     * Scored on a log scale (ocean chlorophyll ranges ~0.01 to 20+).
     * Falls back to 40 (below average) when no satellite data available.
     */
    fun scoreVisibility(chlorophyllMgM3: Double?): Int {
        if (chlorophyllMgM3 == null) return 40  // Unknown = below average

        return when {
            chlorophyllMgM3 < 0.1  -> 100  // Ultra-clear open ocean
            chlorophyllMgM3 < 0.3  -> 85   // Clear tropical
            chlorophyllMgM3 < 0.5  -> 65   // Average ocean (log midpoint)
            chlorophyllMgM3 < 1.0  -> 45   // Below average, slightly green
            chlorophyllMgM3 < 3.0  -> 25   // Green, murky coastal
            chlorophyllMgM3 < 5.0  -> 10   // Can't see your fins
            chlorophyllMgM3 < 10.0 -> 5    // Stay home
            else                   -> 0    // Algae bloom
        }
    }

    /**
     * Calculate weather score based on wind speed in km/h.
     * 
     * Reference (knots → km/h):
     *   5 kts =  9 km/h    Glass
     *  10 kts = 18 km/h    Light breeze
     *  15 kts = 28 km/h    Moderate, fine for most divers
     *  20 kts = 37 km/h    Choppy, Small Craft Advisory territory
     *  25 kts = 46 km/h    Rough, marginal
     *  25+ kts              Stay home
     */
    fun scoreWeather(windSpeedKmh: Double?): Int {
        if (windSpeedKmh == null) return 50  // Unknown = neutral, not fabricated-calm
        return when {
            windSpeedKmh <= 9  -> 100  // 0-5 kts: glass
            windSpeedKmh <= 18 -> 85   // 5-10 kts: light breeze
            windSpeedKmh <= 28 -> 65   // 10-15 kts: moderate
            windSpeedKmh <= 37 -> 45   // 15-20 kts: choppy
            windSpeedKmh <= 46 -> 25   // 20-25 kts: rough
            else               -> 10   // 25+ kts: dangerous
        }
    }

    /**
     * Calculate swell score based on wave height in meters.
     */
    fun scoreSwell(waveHeightM: Double?): Int {
        if (waveHeightM == null) return 50  // Unknown = neutral
        val waveHeightFt = waveHeightM * 3.28084

        return when {
            waveHeightFt <= 1 -> 100  // Flat
            waveHeightFt <= 2 -> 90
            waveHeightFt <= 3 -> 80
            waveHeightFt <= 4 -> 65
            waveHeightFt <= 6 -> 45
            waveHeightFt <= 8 -> 25
            else -> 10              // Dangerous
        }
    }

    /**
     * Calculate solunar score from the API's day rating.
     * 
     * The api.solunar.org dayRating is on a 0-5 scale:
     *   0 = Poor, 1 = Below average, 2 = Average,
     *   3 = Good, 4 = Very good, 5 = Excellent
     * 
     * We map this to our 0-100 scorer scale.
     * Falls back to named moon phase, then to a neutral default.
     * 
     * @param solunarDayRating  0-5 from api.solunar.org (null if not cached)
     * @param moonPhase         Named phase string: "new_moon", "full_moon", etc. (null if not cached)
     */
    fun scoreSolunar(solunarDayRating: Int?, moonPhase: String?): Int {
        // Best: map the Solunar API's 0-5 day rating to our 0-100 scale
        if (solunarDayRating != null) {
            return when (solunarDayRating) {
                0 -> 30     // Poor
                1 -> 40     // Below average
                2 -> 55     // Average
                3 -> 65     // Good
                4 -> 80     // Very good
                5 -> 90     // Excellent
                else -> 55  // Unknown value, treat as average
            }
        }

        // Fallback: use named moon phase from cache
        if (moonPhase != null) {
            return when (moonPhase) {
                "new_moon" -> 70
                "full_moon" -> 65
                "waxing_gibbous", "waning_gibbous" -> 60
                "first_quarter", "last_quarter" -> 55
                "waxing_crescent", "waning_crescent" -> 50
                else -> 55
            }
        }

        // Last resort: no solunar data at all
        return 55
    }

    /**
     * Calculate overall Shaka Score from all components.
     * 
     * Weights (total 100%):
     * - Visibility: 35% (most important for spearfishing)
     * - Swell: 28% (wave height — affects underwater conditions and entry safety)
     * - Wind: 22% (wind speed — affects surface conditions and comfort)
     * - Solunar: 15% (moon transit, feeding periods, day rating)
     */
    fun calculateOverall(breakdown: ScoreBreakdown): Int {
        val weightedSum = 
            breakdown.visibility * 0.35 +
            breakdown.weather * 0.22 +
            breakdown.swell * 0.28 +
            breakdown.solunar * 0.15
        
        return weightedSum.toInt()
    }

    /**
     * Generate complete Shaka Score for a spot and date.
     * 
     * @param targetDate       ISO date string (for confidence calculation)
     * @param windSpeedKmh     Wind speed in km/h (from Open-Meteo or cache)
     * @param waveHeightM      Wave height in meters (from Open-Meteo or cache)
     * @param chlorophyllMgM3   Chlorophyll-a in mg/m³ (from satellite), null = fallback score 40
     * @param solunarDayRating 0-100 day rating from api.solunar.org, null = use moonPhase fallback
     * @param moonPhase        Named phase string ("new_moon", "full_moon", etc.), null = neutral fallback
     */
    fun generateScore(
        targetDate: String,
        windSpeedKmh: Double?,
        waveHeightM: Double?,
        chlorophyllMgM3: Double?,
        solunarDayRating: Int?,
        moonPhase: String?
    ): ShakaScore {
        val breakdown = ScoreBreakdown(
            visibility = scoreVisibility(chlorophyllMgM3),
            weather = scoreWeather(windSpeedKmh),
            swell = scoreSwell(waveHeightM),
            solunar = scoreSolunar(solunarDayRating, moonPhase)
        )

        // Missing core factors reduce confidence: the score is a guess for
        // that component, and the user should be able to tell.
        val missingPenalty = listOf(windSpeedKmh, waveHeightM, chlorophyllMgM3)
            .count { it == null } * 15

        return ShakaScore(
            overall = calculateOverall(breakdown),
            confidence = (confidenceForDate(targetDate) - missingPenalty).coerceAtLeast(10),
            breakdown = breakdown
        )
    }
}
