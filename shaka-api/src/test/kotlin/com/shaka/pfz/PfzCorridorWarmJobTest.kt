package com.shaka.pfz

import com.shaka.data.client.CopernicusField
import com.shaka.data.client.CopernicusGrid
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [PfzCorridorWarmJob] against a fake grid source, so the
 * corridor loop and reporting are pinned without touching the network.
 */
class PfzCorridorWarmJobTest {

    private val date = LocalDate.of(2026, 9, 26)

    private class GridSource : PfzGridSource {
        override suspend fun fetchGrid(
            field: CopernicusField,
            lat: Double,
            lon: Double,
            date: LocalDate
        ): CopernicusGrid? = when (field) {
            CopernicusField.SST -> CopernicusGrid(
                field = CopernicusField.SST,
                lats = listOf(35.0, 35.1, 35.2),
                lons = listOf(-0.5, -0.4, -0.3),
                values = listOf(listOf(19.1, 19.2, 19.3), listOf(18.9, 19.0, 19.1), listOf(18.7, 18.8, 18.9)),
                dataDate = date,
                sourceLabel = "test"
            )
            CopernicusField.SSTA -> CopernicusGrid(
                field = CopernicusField.SSTA,
                lats = listOf(35.0, 35.1, 35.2),
                lons = listOf(-0.5, -0.4, -0.3),
                values = listOf(listOf(0.1, 0.1, 0.2), listOf(0.1, 0.2, 0.2), listOf(0.2, 0.2, 0.3)),
                dataDate = date,
                sourceLabel = "test"
            )
            CopernicusField.CHL -> CopernicusGrid(
                field = CopernicusField.CHL,
                lats = listOf(35.0, 35.1, 35.2),
                lons = listOf(-0.5, -0.4, -0.3),
                values = listOf(listOf(0.4, 0.4, 0.5), listOf(0.5, 0.5, 0.6), listOf(0.6, 0.6, 0.7)),
                dataDate = date,
                sourceLabel = "test"
            )
            else -> null
        }

        override suspend fun depthM(lat: Double, lon: Double): Double? = null
    }

    @Test
    fun `warm reports every anchor whose front fields resolve`() {
        val job = PfzCorridorWarmJob(service = PfzGridService(GridSource()))

        val summary = runBlocking { job.warm(date) }

        assertEquals(PfzCorridorWarmJob.CORRIDOR_ANCHORS.size, summary.anchors)
        assertEquals(PfzCorridorWarmJob.CORRIDOR_ANCHORS.size, summary.resolved)
        assertTrue(summary.failures.isEmpty())
    }
}