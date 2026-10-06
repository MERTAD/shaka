package com.shaka.data.client

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the token-bucket contract, specifically the distinction between
 * "wait as long as it takes" and "give up after N".
 *
 * `acquire` used to return a Boolean that every caller in the codebase threw
 * away. The one caller that passed a timeout — LandWaterClient — therefore
 * never noticed when the limiter gave up, and issued the request anyway; the
 * live rate-limiter stats showed `landWater` at a 1320% throttle rate as a
 * result. `acquire` is now Unit so the mistake cannot be written, and
 * `acquireWithin` is the explicit opt-in for a bounded wait.
 */
class RateLimiterTest {

    @Test
    fun `acquireWithin returns true while tokens remain`() = runBlocking {
        val limiter = RateLimiter(name = "test", requestsPerSecond = 100.0, burstSize = 5)
        repeat(5) {
            assertTrue(limiter.acquireWithin(timeoutMs = 0), "token $it should be available")
        }
    }

    @Test
    fun `acquireWithin returns false once the bucket is empty and the wait times out`() = runBlocking {
        // 1 req/s with a burst of 1: the second call has to wait ~1s for a token.
        val limiter = RateLimiter(name = "test", requestsPerSecond = 1.0, burstSize = 1)
        assertTrue(limiter.acquireWithin(timeoutMs = 0), "first call should take the burst token")

        // 50ms is far less than the 1000ms refill period, so this must give up.
        assertFalse(limiter.acquireWithin(timeoutMs = 50), "should report the timeout rather than hang")
    }

    @Test
    fun `acquireWithin waits longer than the timeout and still succeeds`() = runBlocking {
        val limiter = RateLimiter(name = "test", requestsPerSecond = 20.0, burstSize = 1)
        assertTrue(limiter.acquireWithin(timeoutMs = 0))
        // 20/s means a token every 50ms; 3000ms of patience is ample.
        assertTrue(limiter.acquireWithin(timeoutMs = 3_000), "should wait for the refill instead of failing")
    }

    @Test
    fun `acquire waits indefinitely and never reports failure`() = runBlocking {
        val limiter = RateLimiter(name = "test", requestsPerSecond = 50.0, burstSize = 1)
        // Burst of 1 at 50/s: the next token arrives in ~20ms. acquire() has no
        // timeout by construction, so these all succeed no matter how long.
        repeat(4) { limiter.acquire() }
    }

    @Test
    fun `tryAcquire still never blocks`() = runBlocking {
        val limiter = RateLimiter(name = "test", requestsPerSecond = 0.5, burstSize = 1)
        assertTrue(limiter.tryAcquire())
        // 0.5/s means a 2s refill; a non-blocking probe must return immediately.
        val started = System.currentTimeMillis()
        assertFalse(limiter.tryAcquire())
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 1_000, "tryAcquire blocked for ${elapsed}ms; it must never wait")
    }
}
