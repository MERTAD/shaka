package com.shaka.data.client

import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.math.floor
import kotlin.random.Random

/**
 * Client for Copernicus Marine WMTS service - L3 NRT (Near Real Time) data.
 * 
 * Product: OCEANCOLOUR_GLO_BGC_L3_NRT_009_101
 * This is the CORRECT product for daily satellite observations.
 * 
 * Provides ACTUAL MEASURED data (not interpolated/gap-filled):
 * - ZSD (Secchi disk depth) = underwater visibility in meters
 * - CHL (Chlorophyll-a) = plankton concentration in mg/m³
 * 
 * L3 = Level 3 = actual daily satellite passes
 * - Updated daily at 22:00 UTC
 * - May return null for cloud-covered areas (this is honest, not an estimate!)
 * - 4km resolution, global coverage
 * 
 * ENTERPRISE PATTERNS:
 * - Uses shared HttpClient (no connection pool proliferation)
 * - Rate limited (1 req/sec via RateLimiters.copernicus)
 * - Circuit breaker protected (fails fast when API is down)
 * - Smart retry with exponential backoff
 * 
 * @see https://data.marine.copernicus.eu/product/OCEANCOLOUR_GLO_BGC_L3_NRT_009_101/description
 */
class CopernicusWMTSClient {

    private val logger = LoggerFactory.getLogger(CopernicusWMTSClient::class.java)

    // Use shared HTTP client - DO NOT create a new one
    private val client: io.ktor.client.HttpClient get() = HttpClientFactory.shared
    
    // Circuit breaker for this API
    private val circuitBreaker = CircuitBreaker(
        name = "copernicus-wmts",
        failureThreshold = 5,
        successThreshold = 2,
        resetTimeoutMs = 120_000  // 2 minutes before retry after circuit opens
    )

    companion object {
        private const val WMTS_BASE = "https://wmts.marine.copernicus.eu/teroWmts"
        
        // L3 NRT (Near Real Time) - actual daily satellite observations
        // This is the CORRECT product - updated daily, real measurements
        private const val ZSD_LAYER = "OCEANCOLOUR_GLO_BGC_L3_NRT_009_101/cmems_obs-oc_glo_bgc-transp_nrt_l3-multi-4km_P1D_202311/ZSD"
        private const val CHL_LAYER = "OCEANCOLOUR_GLO_BGC_L3_NRT_009_101/cmems_obs-oc_glo_bgc-plankton_nrt_l3-multi-4km_P1D_202411/CHL"
        
        // Tile matrix level 8 gives good resolution (~16km tiles)
        private const val TILE_MATRIX_LEVEL = 8
        private const val TILES_X = 512  // Number of tiles at level 8
        private const val TILES_Y = 256
        private const val TILE_SIZE = 256
        
        // Retry configuration
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 8000L
    }

    /**
     * True when the failure came from the transport rather than from an answer,
     * so a second attempt is worth making.
     *
     * A 400 ("date not available yet") or a valid null payload is an answer and
     * must not be retried - the date is not going to become available because we
     * asked twice.
     */
    private fun isTransient(e: Exception): Boolean {
        val message = e.message ?: return false
        return message.contains("timeout", ignoreCase = true) ||
            message.contains("connect", ignoreCase = true) ||
            message.contains("connection reset", ignoreCase = true) ||
            message.contains("broken pipe", ignoreCase = true)
    }

    /**
     * Run [block] up to [MAX_RETRIES] times, backing off between attempts, and
     * rethrow immediately if the failure is not a transport failure.
     *
     * MAX_RETRIES/INITIAL_BACKOFF_MS/MAX_BACKOFF_MS were declared for this and
     * never called: there was no retry, despite the class doc claiming one. In
     * one satellite run that left 14 visibility/chlorophyll requests dead -
     * each one silently recorded as "no_data". Measured on this machine, a
     * TCP connect to wmts.marine.copernicus.eu completes in 60-80ms, but
     * roughly one in fifteen took 15156ms because the SYN was dropped and the
     * kernel retransmitted with backoff - far past the shared client's 5s
     * connectTimeout. Those arrive as exceptions and were recorded as
     * "no_data", so a dropped SYN was reported to the user as "this water has
     * no satellite coverage". The next connection almost always succeeds.
     */
    internal suspend fun <T> withRetry(
        tag: String,
        maxRetries: Int = MAX_RETRIES,
        initialBackoffMs: Long = INITIAL_BACKOFF_MS,
        maxBackoffMs: Long = MAX_BACKOFF_MS,
        block: suspend () -> T,
    ): T {
        var backoff = initialBackoffMs
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                if (!isTransient(e)) throw e
                lastError = e
                if (attempt == maxRetries) break
                logger.debug(
                    "$tag attempt $attempt failed transiently (${e.message}); retrying in ${backoff}ms"
                )
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
            }
        }

        throw lastError ?: IllegalStateException("$tag failed without a cause")
    }

    /**
     * Get real underwater visibility (Secchi disk depth) for a location.
     * Returns visibility in METERS - this is actual measured data, not an estimate!
     * 
     * @param lat Latitude
     * @param lon Longitude
     * @param date Date in YYYY-MM-DD format (uses most recent available if date not available)
     * @return Visibility in meters, or null if unavailable
     */
    suspend fun getVisibility(lat: Double, lon: Double, date: String): Double? {
        // Rate limit - wait for token
        RateLimiters.copernicus.acquire()
        
        return try {
            // Circuit breaker - fail fast if API is down. The retry sits inside
            // the breaker so a dropped SYN that succeeds on the second attempt
            // does not count toward opening the circuit; only a request that
            // stays dead through all attempts does.
            circuitBreaker.execute {
                withRetry("visibility") { fetchVisibility(lat, lon, date) }
            }
        } catch (e: CircuitBreakerOpenException) {
            logger.debug("Circuit breaker open for Copernicus WMTS - skipping request")
            null
        } catch (e: Exception) {
            logger.warn("Copernicus WMTS visibility request failed: ${e.message}")
            null
        }
    }
    
    private suspend fun fetchVisibility(lat: Double, lon: Double, date: String): Double? {
        // Calculate tile coordinates for EPSG:4326
        val tileWidth = 360.0 / TILES_X
        val tileHeight = 180.0 / TILES_Y
        
        val tileCol = floor((lon + 180.0) / tileWidth).toInt().coerceIn(0, TILES_X - 1)
        val tileRow = floor((90.0 - lat) / tileHeight).toInt().coerceIn(0, TILES_Y - 1)
        
        // Calculate pixel position within tile (0-255)
        val tileLonMin = -180.0 + (tileCol * tileWidth)
        val tileLatMax = 90.0 - (tileRow * tileHeight)
        
        val pixelX = ((lon - tileLonMin) / tileWidth * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
        val pixelY = ((tileLatMax - lat) / tileHeight * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
        
        // Build WMTS GetFeatureInfo request
        val url = buildString {
            append(WMTS_BASE)
            append("?SERVICE=WMTS")
            append("&VERSION=1.0.0")
            append("&REQUEST=GetFeatureInfo")
            append("&LAYER=$ZSD_LAYER")
            append("&STYLE=cmap:viridis")
            append("&FORMAT=image/png")
            append("&TILEMATRIXSET=EPSG:4326")
            append("&TILEMATRIX=$TILE_MATRIX_LEVEL")
            append("&TILEROW=$tileRow")
            append("&TILECOL=$tileCol")
            append("&I=$pixelX")
            append("&J=$pixelY")
            append("&INFOFORMAT=application/json")
            append("&TIME=${date}T00:00:00Z")
        }
        
        logger.debug("Fetching ZSD visibility: tile($tileCol,$tileRow) pixel($pixelX,$pixelY)")
        
        // Handle 400 errors gracefully - date may not be available yet
        val httpResponse = client.get(url)
        if (httpResponse.status.value == 400) {
            logger.debug("Copernicus returned 400 for date $date - data not yet available")
            return null
        }
        val response: String = httpResponse.bodyAsText()
        val visibility = parseZSDResponse(response)
        
        if (visibility != null) {
            logger.info("Copernicus ZSD for ($lat, $lon): ${String.format("%.1f", visibility)}m visibility")
        } else {
            logger.debug("Copernicus ZSD unavailable for ($lat, $lon)")
        }
        
        return visibility
    }

    /**
     * Get visibility for the most recent available satellite data.
     * Fires day -1 and day -2 in parallel, picks the most recent hit.
     * 
     * Copernicus L3 NRT has 1-2 day latency depending on time of day/processing.
     * DB analysis: 86.5% of spots get data within 2 days.
     */
    suspend fun getLatestVisibility(lat: Double, lon: Double): VisibilityResult {
        if (!circuitBreaker.allowsRequests()) {
            logger.debug("Circuit breaker open - returning no data")
            return VisibilityResult(
                visibilityM = null,
                date = LocalDate.now().minusDays(1).toString(),
                dataSource = "Circuit breaker open",
                isActualMeasurement = false
            )
        }
        
        val dates = listOf(
            LocalDate.now().minusDays(1).toString(),
            LocalDate.now().minusDays(2).toString()
        )
        
        val results = coroutineScope {
            dates.map { date ->
                async { date to getVisibility(lat, lon, date) }
            }.awaitAll()
        }
        
        val best = results.firstOrNull { it.second != null }
        if (best != null) {
            return VisibilityResult(
                visibilityM = best.second,
                date = best.first,
                dataSource = "Copernicus L3 NRT satellite",
                isActualMeasurement = true
            )
        }
        
        return VisibilityResult(
            visibilityM = null,
            date = dates.first(),
            dataSource = "No satellite data available",
            isActualMeasurement = false
        )
    }

    /**
     * Get chlorophyll-a concentration for a location.
     * Returns mg/m³ - actual satellite measurement.
     */
    suspend fun getChlorophyll(lat: Double, lon: Double, date: String): Double? {
        // Rate limit
        RateLimiters.copernicus.acquire()
        
        return try {
            circuitBreaker.execute {
                withRetry("chlorophyll") { fetchChlorophyll(lat, lon, date) }
            }
        } catch (e: CircuitBreakerOpenException) {
            logger.debug("Circuit breaker open for Copernicus WMTS - skipping chlorophyll request")
            null
        } catch (e: Exception) {
            logger.warn("Copernicus CHL request failed: ${e.message}")
            null
        }
    }
    
    private suspend fun fetchChlorophyll(lat: Double, lon: Double, date: String): Double? {
        val tileWidth = 360.0 / TILES_X
        val tileHeight = 180.0 / TILES_Y
        
        val tileCol = floor((lon + 180.0) / tileWidth).toInt().coerceIn(0, TILES_X - 1)
        val tileRow = floor((90.0 - lat) / tileHeight).toInt().coerceIn(0, TILES_Y - 1)
        
        val tileLonMin = -180.0 + (tileCol * tileWidth)
        val tileLatMax = 90.0 - (tileRow * tileHeight)
        
        val pixelX = ((lon - tileLonMin) / tileWidth * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
        val pixelY = ((tileLatMax - lat) / tileHeight * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
        
        val url = buildString {
            append(WMTS_BASE)
            append("?SERVICE=WMTS")
            append("&VERSION=1.0.0")
            append("&REQUEST=GetFeatureInfo")
            append("&LAYER=$CHL_LAYER")
            append("&STYLE=cmap:viridis")
            append("&FORMAT=image/png")
            append("&TILEMATRIXSET=EPSG:4326")
            append("&TILEMATRIX=$TILE_MATRIX_LEVEL")
            append("&TILEROW=$tileRow")
            append("&TILECOL=$tileCol")
            append("&I=$pixelX")
            append("&J=$pixelY")
            append("&INFOFORMAT=application/json")
            append("&TIME=${date}T00:00:00Z")
        }
        
        // Handle 400 errors gracefully - date may not be available yet (expected)
        // Don't let these trip the circuit breaker
        val httpResponse = client.get(url)
        if (httpResponse.status.value == 400) {
            logger.debug("Copernicus returned 400 for date $date - data not yet available")
            return null
        }
        val response: String = httpResponse.bodyAsText()
        return parseNumericValue(response, "milligram m-3")
    }

    /**
     * Get chlorophyll for the most recent available satellite data.
     * Fires day -1 and day -2 in parallel, picks the most recent hit.
     */
    suspend fun getLatestChlorophyll(lat: Double, lon: Double): ChlorophyllResult {
        if (!circuitBreaker.allowsRequests()) {
            logger.debug("Circuit breaker open - returning no chlorophyll data")
            return ChlorophyllResult(
                chlorophyllMgM3 = null,
                date = LocalDate.now().minusDays(1).toString(),
                dataSource = "Circuit breaker open",
                isActualMeasurement = false
            )
        }
        
        val dates = listOf(
            LocalDate.now().minusDays(1).toString(),
            LocalDate.now().minusDays(2).toString()
        )
        
        val results = coroutineScope {
            dates.map { date ->
                async { date to getChlorophyll(lat, lon, date) }
            }.awaitAll()
        }
        
        val best = results.firstOrNull { it.second != null }
        if (best != null) {
            return ChlorophyllResult(
                chlorophyllMgM3 = best.second,
                date = best.first,
                dataSource = "Copernicus L3 NRT satellite",
                isActualMeasurement = true
            )
        }
        
        return ChlorophyllResult(
            chlorophyllMgM3 = null,
            date = dates.first(),
            dataSource = "No satellite data available",
            isActualMeasurement = false
        )
    }
    
    /**
     * Get circuit breaker status (for monitoring).
     */
    fun getCircuitBreakerStats(): Map<String, Any> = circuitBreaker.getStats()

    /**
     * Parse ZSD value from WMTS GetFeatureInfo JSON response.
     */
    private fun parseZSDResponse(jsonResponse: String): Double? {
        return parseNumericValue(jsonResponse, "m")
    }

    /**
     * Parse numeric value from WMTS GetFeatureInfo JSON response.
     * Handles null values properly (satellite couldn't measure this location).
     */
    private fun parseNumericValue(jsonResponse: String, expectedUnits: String): Double? {
        return try {
            // Check if value is null (satellite couldn't capture this location)
            if (jsonResponse.contains("\"value\":null") || jsonResponse.contains("\"value\": null")) {
                return null
            }
            
            // Response format: {"type":"FeatureCollection","features":[{"properties":{"value":35.5,"units":"m"}}]}
            val valueRegex = """"value"\s*:\s*([\d.]+)""".toRegex()
            val unitsRegex = """"units"\s*:\s*"([^"]+)"""".toRegex()
            
            val valueMatch = valueRegex.find(jsonResponse)
            val unitsMatch = unitsRegex.find(jsonResponse)
            
            if (valueMatch != null && unitsMatch?.groupValues?.get(1) == expectedUnits) {
                val value = valueMatch.groupValues[1].toDoubleOrNull()
                if (value != null && value > 0) {
                    return value
                }
            }
            
            null
        } catch (e: Exception) {
            logger.debug("Value parsing failed: ${e.message}")
            null
        }
    }

    /**
     * Result of visibility query - includes metadata about the measurement.
     */
    data class VisibilityResult(
        val visibilityM: Double?,           // Visibility in meters, null if unavailable
        val date: String,                   // Date of measurement
        val dataSource: String,             // Source attribution
        val isActualMeasurement: Boolean    // True if actual satellite data, false if unavailable
    )

    /**
     * Result of chlorophyll query - includes metadata about the measurement.
     */
    data class ChlorophyllResult(
        val chlorophyllMgM3: Double?,       // Chlorophyll in mg/m³, null if unavailable
        val date: String,                   // Date of measurement
        val dataSource: String,             // Source attribution
        val isActualMeasurement: Boolean    // True if actual satellite data
    )
}
