package com.shaka.api.routes

import com.shaka.pfz.PfzService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PFZ endpoints at the HTTP boundary.
 *
 * Everything below this layer is covered: the engine by [com.shaka.pfz.PfzEngineTest],
 * the service by [com.shaka.pfz.PfzServiceTest], the persistence decision by
 * [PfzPersistActionTest] and the history read by [PfzHistoryRouteTest]. What
 * none of those can see is the boundary itself - the status codes, the
 * `return@get` guards that reject a malformed query, and the JSON a caller
 * actually receives.
 *
 * That last part is not hypothetical. `mapOf("error" to String, "knownSpecies"
 * to List<String>)` is a `Map<String, Any>`: kotlinx serialisation cannot find
 * a serializer for the erased value type, so the encode fails and both unknown
 * species endpoints answered 500 with no body on a live server. Every error path
 * carrying only strings was unaffected, and all of them were tested. Only a real
 * request finds this class of fault, and only a declared response type prevents
 * it.
 *
 * No database is needed here. The endpoints under test answer without
 * persisting: a response with no zones clears a day, and clearing an absent
 * day is a no-op. The injected service has no grid corridor, which is what an
 * installation without Copernicus configured looks like, and yields the empty
 * but well-formed answer that makes these assertions stable.
 */
class PfzRoutesHttpTest {

    @Test
    fun `history rejects a missing lat with a 400`() = withApi {
        val response = client.get("/v1/pfz/zones/history?lon=2.1686&species=bluefin_tuna")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("lat required", response.json()["error"]?.toString()?.trim('"'))
    }

    @Test
    fun `history rejects a missing species with a 400`() = withApi {
        val response = client.get("/v1/pfz/zones/history?lat=41.3874&lon=2.1686")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("species required", response.json()["error"]?.toString()?.trim('"'))
    }

    @Test
    fun `history rejects an out-of-range coordinate with a 400`() = withApi {
        val response = client.get("/v1/pfz/zones/history?lat=999&lon=2.1686&species=bluefin_tuna")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        // The caller needs the accepted range, not just the rejection.
        val error = response.json()["error"].toString()
        assertTrue(error.contains("lat in [-90, 90]"), "expected the range in $error")
    }

    @Test
    fun `history returns 404 with the known species for an unknown one`() = withApi {
        val response =
            client.get("/v1/pfz/zones/history?lat=41.3874&lon=2.1686&species=not_a_species")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.json()
        assertEquals("Unknown species", body["error"]?.toString()?.trim('"'))
        // The app surfaces this specific message to the user, so the roster has
        // to travel with it: without the list a typo is indistinguishable from a
        // species the server has simply not heard of.
        val known = body["knownSpecies"]!!.jsonArray.map { it.toString().trim('"') }
        assertTrue(known.contains("bluefin_tuna"), "roster missing bluefin_tuna: $known")
    }

    @Test
    fun `history for a real but unstored anchor is a 200 with no days`() = withApi {
        val response = client.get(
            "/v1/pfz/zones/history?lat=41.3874&lon=2.1686&species=bluefin_tuna&days=7"
        )

        // Not a 404. History only exists once something is persisted, and a
        // location nobody has ever queried legitimately has none; the client
        // distinguishes "no history" from "no such place" on this status code.
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        // Absent rather than `[]`: the encoder omits defaults, and an empty
        // "no history yet" and a missing key mean the same thing here.
        val days = body["days"]?.jsonArray ?: JsonArray(emptyList())
        assertEquals(JsonArray(emptyList()), days, "expected no days, got $body")
        // The note is the point of this response. A 200 with an empty series
        // and no explanation reads as a broken feature.
        val notes = body["coverageNotes"]!!.jsonArray.joinToString(" ") { it.toString() }
        assertTrue(notes.contains("has been persisted", ignoreCase = true), "got $notes")
    }

    @Test
    fun `zones rejects a missing lat with a 400`() = withApi {
        val response = client.get("/v1/pfz/zones?lon=2.1686&species=bluefin_tuna")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("lat required", response.json()["error"]?.toString()?.trim('"'))
    }

    @Test
    fun `zones rejects an unknown species with a 404`() = withApi {
        val response = client.get("/v1/pfz/zones?lat=41.3874&lon=2.1686&species=not_a_species")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Unknown species", response.json()["error"]?.toString()?.trim('"'))
    }

    @Test
    fun `zones without a corridor answers 200 with an empty set and a reason`() = withApi {
        val response = client.get("/v1/pfz/zones?lat=41.3874&lon=2.1686&species=bluefin_tuna")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals(JsonArray(emptyList()), body["zones"], "expected no zones, got $body")

        // An empty result with no explanation is indistinguishable from a bug
        // at the call site, so the reason is part of the contract.
        val notes = body["coverageNotes"]!!.jsonArray.joinToString(" ") { it.toString() }
        assertTrue(
            notes.contains("did not resolve") || notes.contains("No spatial grid corridor"),
            "an empty zone set must say why, got $notes"
        )
    }
}

private val jsonFormat = Json { ignoreUnknownKeys = true }

/** Parse the body, so assertions do not depend on the encoder's whitespace. */
private suspend fun io.ktor.client.statement.HttpResponse.json(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject

/**
 * The PFZ routes, mounted the way `Application.module()` mounts them.
 *
 * The empty `config` is load-bearing, and it is here because these tests were
 * silently green for the wrong reason. `application.conf` lives in *main*
 * resources, so it is on the test classpath, and it declares
 * `modules = [com.shaka.ApplicationKt.module]`. `testApplication` auto-loads it
 * and runs the real `module()`, which registers `/v1/pfz/zones` with a live
 * `PfzService(gridSource = PfzGridService())` and installs the plugins below.
 * Ktor resolves a duplicated path to the first registration, so the real route
 * won and the stub passed here was never consulted: the injected service was
 * dead code. Only assertions that short-circuit before the grid (400 on a
 * missing lat, 404 on an unknown species) could still pass, which is exactly
 * the 7-of-8 that did while the eighth read real Copernicus output off the warm
 * toolbox cache and failed.
 *
 * Replacing the config stops that auto-load, so this block is the only thing
 * that registers these routes and installs these plugins.
 *
 * `testApplication` does *not* supply ContentNegotiation or StatusPages - I
 * had that written here as fact and it was wrong; both were arriving from the
 * auto-loaded `module()`, which is the same defect wearing a different hat.
 * With `module()` gone they have to be installed explicitly, and they are
 * installed with the same Json configuration production uses so the bytes on
 * the wire match. Without ContentNegotiation every response is a bodiless 406.
 *
 * If a test here starts returning live measurements, the empty config has been
 * undone; if every status is 406, the plugin installs have been.
 */
private fun withApi(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    environment { config = MapApplicationConfig() }
    application {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Unknown error"))
                )
            }
        }
        configureRouting(pfzService = PfzService(gridSource = null))
    }
    block()
}