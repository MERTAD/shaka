package com.shaka.data.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.round

/**
 * Land/water classifier for arbitrary coordinates.
 *
 * Used to:
 * - Block user-created land spots (UX: "move into the water")
 * - Audit default spots that accidentally landed on land
 *
 * NOTE: This intentionally treats "water" broadly (ocean + inland water bodies),
 * matching the product decision.
 */
class LandWaterClient(
    // null = use the (rebuildable) shared client; non-null only for tests.
    private val httpOverride: HttpClient? = null,
    private val baseUrl: String = System.getenv("LAND_WATER_API_URL")
        ?: "https://is-on-water.balbona.me/api/v1/get"
) {
    private val http: HttpClient get() = httpOverride ?: HttpClientFactory.shared
    private val logger = LoggerFactory.getLogger(LandWaterClient::class.java)

    private data class CacheEntry(val isWater: Boolean, val fetchedAt: Instant)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    companion object {
        // 5 decimals ~ 1.1m latitude precision; good cache hit rate without changing semantics.
        private const val CACHE_KEY_DECIMALS = 5

        // We keep these results for a long time; land/water doesn't change frequently.
        private val CACHE_TTL: Duration = Duration.ofDays(30)

        // Fail fast; this is a UX guardrail, not a critical path dependency.
        private const val REQUEST_TIMEOUT_MS = 1500L
    }

    /**
     * @return true if coordinate is water, false if land, null if unknown (timeout/error)
     */
    suspend fun isWater(lat: Double, lon: Double): Boolean? {
        val key = cacheKey(lat, lon)

        cache[key]?.let { entry ->
            if (!isExpired(entry.fetchedAt)) return entry.isWater
        }

        return try {
            // Be respectful to the free service. tryAcquire reports whether the
            // token actually arrived; ignoring that made this call hammer the
            // endpoint at a 1320% throttle rate. On timeout we return null,
            // which is the documented "unknown" answer, rather than issuing a
            // request we already know we are not allowed to make yet.
            if (!RateLimiters.landWater.acquireWithin(REQUEST_TIMEOUT_MS)) return null

            val url = "${baseUrl.trimEnd('/')}/${lat}/${lon}"
            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                http.get(url) {
                    headers.append("User-Agent", "shaka-api/1.0")
                }.body<IsOnWaterResponse>()
            } ?: return null

            val isWater = result.isWater ?: return null
            cache[key] = CacheEntry(isWater = isWater, fetchedAt = Instant.now())
            isWater
        } catch (e: Exception) {
            logger.debug("Land/water check failed for ($lat,$lon): ${e.message}")
            null
        }
    }

    fun getCacheStats(): Map<String, Any> {
        val now = Instant.now()
        val fresh = cache.values.count { !isExpired(it.fetchedAt, now) }
        val expired = cache.size - fresh
        return mapOf(
            "entries" to cache.size,
            "fresh" to fresh,
            "expired" to expired,
            "ttlDays" to CACHE_TTL.toDays()
        )
    }

    private fun isExpired(fetchedAt: Instant, now: Instant = Instant.now()): Boolean {
        return Duration.between(fetchedAt, now) > CACHE_TTL
    }

    private fun cacheKey(lat: Double, lon: Double): String {
        val latR = roundToDecimals(lat, CACHE_KEY_DECIMALS)
        val lonR = roundToDecimals(lon, CACHE_KEY_DECIMALS)
        return "$latR,$lonR"
    }

    private fun roundToDecimals(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals.toDouble())
        return round(value * factor) / factor
    }

    @Serializable
    private data class IsOnWaterResponse(
        val isWater: Boolean? = null
    )
}

