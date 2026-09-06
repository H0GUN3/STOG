package com.stog.app.feature.space

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlanningApiRealHttpDriverTest {
    @Test
    fun tripDetailAndBasketRemovalUseBackendHttpEndpoints() {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/trips/7") { exchange ->
                methods += exchange.requestMethod
                exchange.requestBody.close()
                val response = """
                    {
                      "id": 7,
                      "title": "서버 여행",
                      "activity_type": "tour",
                      "mode": "dormant",
                      "visibility": "private",
                      "planned_start_date": "2026-09-01",
                      "planned_end_date": "2026-09-03"
                    }
                """.trimIndent().toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/trips/7/basket/3") { exchange ->
                methods += exchange.requestMethod
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        try {
            val client = PlanningApiClient("http://127.0.0.1:${server.address.port}")

            assertEquals(7L, client.trip("token", 7L).id)
            client.updateTrip("token", 7L, "수정 여행", "2026-09-01", "2026-09-03")
            client.removeBasketItem("token", 7L, 3L)

            assertEquals(listOf("GET", "PUT", "DELETE"), methods)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun confirmedProviderCardUsesImmutableIdentityThroughHttp() {
        val configured = System.getenv("STOG_TASK9_HTTP_BASE_URL")
        val fake = if (configured == null) fakeBackend() else null
        try {
            val baseUrl = configured ?: "http://127.0.0.1:${fake!!.address.port}"
            val credentials = if (configured == null) {
                Credentials("token", 9L)
            } else {
                createAuthenticatedTrip(baseUrl)
            }
            val candidate = PlaceSearchCandidate(
                externalId = "ChIJtask9",
                name = "untrusted Android carrier name",
                address = null,
                latitude = 0.0,
                longitude = 0.0,
                types = listOf("place"),
            )
            val clientItemId = "task9-android-${UUID.randomUUID()}"
            val payload = basketRequestPayload(credentials.tripId, candidate, clientItemId)
            val client = PlanningApiClient(baseUrl)

            val first = client.addConfirmedShareToBasket(
                credentials.token,
                credentials.tripId,
                clientItemId,
                payload.payloadFingerprint,
                candidate,
            )
            val replay = client.addConfirmedShareToBasket(
                credentials.token,
                credentials.tripId,
                clientItemId,
                payload.payloadFingerprint,
                candidate,
            )

            assertEquals(first, replay)
        } finally {
            fake?.stop(0)
        }
    }

    private fun fakeBackend(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/basket-items") { exchange ->
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            require(request.getString("client_item_id").isNotBlank())
            require(request.getString("payload_fingerprint").matches(Regex("[0-9a-f]{64}")))
            val response = "{\"id\":1,\"place_id\":2,\"cell_id\":null,\"status\":\"resolved\"}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        start()
    }

    private fun createAuthenticatedTrip(baseUrl: String): Credentials {
        val suffix = UUID.randomUUID().toString()
        val signup = post(
            baseUrl,
            "/auth/signup",
            JSONObject()
                .put("email", "task9-android-$suffix@example.test")
                .put("password", "Task9-password-123!")
                .put("nickname", "task9-android-host-$suffix"),
            null,
        )
        val token = signup.getString("access_token")
        val trip = post(
            baseUrl,
            "/trips",
            JSONObject().put("title", "Android host share review").put("activity_type", "tour"),
            token,
        )
        return Credentials(token, trip.getLong("id"))
    }

    private fun post(baseUrl: String, path: String, body: JSONObject, token: String?): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            check(connection.responseCode in 200..299)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private data class Credentials(val token: String, val tripId: Long)
}
