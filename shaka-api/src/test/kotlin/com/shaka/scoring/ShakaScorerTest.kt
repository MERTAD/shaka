package com.shaka.scoring

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShakaScorerTest {

    private val today = LocalDate.now().toString()

    @Test
    fun `glass conditions score high`() {
        val score = ShakaScorer.generateScore(
            targetDate = today,
            windSpeedKmh = 5.0,
            waveHeightM = 0.2,
            chlorophyllMgM3 = 0.05,
            solunarDayRating = 5,
            moonPhase = null
        )
        assertTrue(score.overall >= 90, "expected >=90, got ${score.overall}")
        assertEquals(95, score.confidence)
    }

    @Test
    fun `dangerous conditions score low`() {
        val score = ShakaScorer.generateScore(
            targetDate = today,
            windSpeedKmh = 50.0,
            waveHeightM = 3.0,
            chlorophyllMgM3 = 8.0,
            solunarDayRating = 0,
            moonPhase = null
        )
        assertTrue(score.overall <= 25, "expected <=25, got ${score.overall}")
    }

    @Test
    fun `missing factors score neutral not fabricated-calm`() {
        // Regression for the Jun 2026 incident: missing data used to be
        // replaced with calm defaults, inflating scores everywhere.
        val score = ShakaScorer.generateScore(
            targetDate = today,
            windSpeedKmh = null,
            waveHeightM = null,
            chlorophyllMgM3 = null,
            solunarDayRating = null,
            moonPhase = null
        )
        assertEquals(50, score.breakdown.weather)
        assertEquals(50, score.breakdown.swell)
        assertEquals(40, score.breakdown.visibility)
    }

    @Test
    fun `missing factors reduce confidence`() {
        val full = ShakaScorer.generateScore(today, 10.0, 0.5, 0.2, 3, null)
        val missing = ShakaScorer.generateScore(today, null, null, null, 3, null)
        assertTrue(missing.confidence < full.confidence,
            "missing-data confidence ${missing.confidence} should be < ${full.confidence}")
        assertTrue(missing.confidence >= 10)
    }

    /**
     * A client that sends `?date=` means "no date chosen", not "parse the empty
     * string". This threw DateTimeParseException out of getSpotDetail and took
     * the whole spot-detail response down with a 500, so a missing date cost the
     * user the entire screen rather than one field.
     */
    @Test
    fun `blank date is treated as today rather than throwing`() {
        assertEquals(95, ShakaScorer.confidenceForDate(""))
        assertEquals(95, ShakaScorer.confidenceForDate("   "))
    }

    @Test
    fun `unparseable date is treated as today rather than throwing`() {
        assertEquals(95, ShakaScorer.confidenceForDate("not-a-date"))
    }

    @Test
    fun `a real date still scores its own confidence`() {
        val tomorrow = LocalDate.now().plusDays(1).toString()
        assertEquals(90, ShakaScorer.confidenceForDate(tomorrow))
    }
}
