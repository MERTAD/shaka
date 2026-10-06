package com.shaka.data.client

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for the transport-retry helper.
 *
 * `CopernicusWMTSClient` declared `MAX_RETRIES`, `INITIAL_BACKOFF_MS` and
 * `MAX_BACKOFF_MS` and documented itself as having "smart retry with
 * exponential backoff", but nothing ever called them - every request was a
 * single attempt. That cost 1388 requests in one satellite run: a dropped SYN
 * makes a connect take 15156ms against the shared client's 5s connectTimeout,
 * and because the failure arrived as an exception it was recorded as
 * "no_data", so packet loss was reported as absent satellite coverage.
 *
 * The contract that matters: retry transport failures, never retry an answer,
 * and give up after a bounded number of attempts.
 */
class TransientRetryTest {

    private val client = CopernicusWMTSClient()

    private fun transient(message: String) = RuntimeException(message)

    @Test
    fun `returns the value once a transient failure succeeds`() = runBlocking {
        var attempts = 0

        val result = client.withRetry("test", initialBackoffMs = 0, maxBackoffMs = 0) {
            attempts++
            if (attempts < 3) throw transient("Connect timeout has expired")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts, "should have burned two attempts before succeeding")
    }

    @Test
    fun `does not retry a failure that is not a transport failure`() = runBlocking {
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            client.withRetry("test", initialBackoffMs = 0, maxBackoffMs = 0) {
                attempts++
                throw IllegalStateException("bad tile matrix")
            }
        }

        assertEquals(1, attempts, "a real error must not be retried")
    }

    @Test
    fun `gives up after exactly maxRetries attempts`() = runBlocking {
        var attempts = 0

        assertFailsWith<RuntimeException> {
            client.withRetry("test", maxRetries = 4, initialBackoffMs = 0, maxBackoffMs = 0) {
                attempts++
                throw transient("Connect timeout has expired")
            }
        }

        assertEquals(4, attempts, "must stop rather than retry forever")
    }

    @Test
    fun `an answer that is empty is not treated as a failure`() = runBlocking {
        var attempts = 0

        val result = client.withRetry<String?>("test", initialBackoffMs = 0, maxBackoffMs = 0) {
            attempts++
            null
        }

        assertEquals(null, result)
        assertEquals(1, attempts, "no data is an answer, not something to retry")
    }

    @Test
    fun `retries the transport failures this endpoint actually produced, but not answers`() = runBlocking {
        val transport = listOf(
            "Connect timeout has expired",
            "Request timeout has expired",
            "Connection reset by peer",
            "Broken pipe",
        )
        val answers = listOf(
            "HTTP 400",
            "tile out of range",
        )

        transport.forEach { message ->
            var attempts = 0
            assertFailsWith<RuntimeException>("should retry: $message") {
                client.withRetry<String>("probe", maxRetries = 2, initialBackoffMs = 0, maxBackoffMs = 0) {
                    attempts++
                    throw RuntimeException(message)
                }
            }
            assertEquals(2, attempts, "transport failure should have been retried once: $message")
        }

        answers.forEach { message ->
            var attempts = 0
            assertFailsWith<RuntimeException>("should not retry: $message") {
                client.withRetry<String>("probe", maxRetries = 3, initialBackoffMs = 0, maxBackoffMs = 0) {
                    attempts++
                    throw RuntimeException(message)
                }
            }
            assertEquals(1, attempts, "an answer must surface immediately: $message")
        }
    }
}
