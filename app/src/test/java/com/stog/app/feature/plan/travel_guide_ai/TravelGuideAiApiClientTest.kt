package com.stog.app.feature.plan.travel_guide_ai

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TravelGuideAiApiClientTest {
    @Test
    fun selectedTripLoadsCurrentItineraryThenPreviewsAndExplicitlyApplies() {
        // Given
        val previewWrites = AtomicInteger()
        val applyWrites = AtomicInteger()
        val server = fakeBackend(previewWrites, applyWrites)
        try {
            val client = TravelGuideAiApiClient("http://127.0.0.1:${server.address.port}")

            // When
            val proposal = client.previewCurrentItinerary("token", 14)

            // Then
            assertEquals(14L, proposal.tripId)
            assertEquals(listOf(41L), proposal.fixedBasketItemIds)
            assertEquals(15, proposal.trailLegs.single().durationMinutes)
            assertEquals("encoded", proposal.trailLegs.single().encodedPolyline)
            assertEquals(1, previewWrites.get())
            assertEquals(0, applyWrites.get())

            val applied = client.apply("token", proposal, "00000000-0000-0000-0000-000000000015")
            assertEquals(14L, applied.tripId)
            assertEquals(2L, applied.itineraryVersion)
            assertEquals(1, applyWrites.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun authenticationFailureIsTypedAndDoesNotClaimApply() {
        // Given
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/trips/14/itinerary") { exchange ->
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            }
            start()
        }
        try {
            val client = TravelGuideAiApiClient("http://127.0.0.1:${server.address.port}")

            // When
            val error = runCatching { client.previewCurrentItinerary("expired", 14) }.exceptionOrNull()

            // Then
            assertTrue(error is TravelGuideAiRequestException)
            assertEquals(true, (error as TravelGuideAiRequestException).authExpired)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun emptyBasketStillCreatesPreview() {
        val previewWrites = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/trips/14") { exchange ->
                val path = exchange.requestURI.path
                val response = if (path.endsWith("/proposals/preview")) {
                    val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    assertEquals(0, request.getJSONArray("actions").length())
                    previewWrites.incrementAndGet()
                    """
                        {"suggestion_id":"00000000-0000-0000-0000-000000000016",
                         "trip_id":14,"base_version":1,"status":"ready",
                         "proposal_fingerprint":"${"a".repeat(64)}","feasible":true,
                         "actions":[],"fixed_basket_item_ids":[],
                         "excluded_unresolved_basket_item_ids":[],"violations":[]}
                    """.trimIndent()
                } else {
                    when (path) {
                        "/trips/14" -> """
                            {"id":14,"title":"빈 바구니 여행","activity_type":"tour",
                             "mode":"planning","visibility":"private",
                             "planned_start_date":"2026-09-01","planned_end_date":"2026-09-02"}
                        """.trimIndent()
                        "/trips/14/basket" -> "[]"
                        "/trips/14/itinerary" -> """{"version":1,"items":[]}"""
                        else -> error("Unexpected path: $path")
                    }
                }
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val client = TravelGuideAiApiClient("http://127.0.0.1:${server.address.port}")

            val proposal = client.previewBasketItinerary("token", 14)

            assertEquals(14L, proposal.tripId)
            assertEquals(0, proposal.actions.size)
            assertEquals(1, previewWrites.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun emptyBasketUsesStobeeRecommendationsBeforePreview() {
        val recommendationCalls = AtomicInteger()
        val basketWrites = AtomicInteger()
        val previewWrites = AtomicInteger()
        val recommendations = (1..5).joinToString(
            prefix = "[",
            postfix = "]",
        ) { index ->
            """
                {"place_id":$index,"provider":"canonical","external_id":"place-$index",
                 "name":"추천 장소 $index","formatted_address":"전북 주소 $index",
                 "latitude":35.8,"longitude":127.1,"types":["tourist_attraction"],
                 "source_type":"catalog","source_id":1,"catalog_status":"active"}
            """.trimIndent()
        }
        val previewActions = (1..5).joinToString(",") { index ->
            """
                {"action_order":${index - 1},"type":"set_itinerary_item",
                 "basket_item_id":${200 + index},"day_number":1,
                 "order_index":${index - 1},"planned_arrival":"${9 + index}:00",
                 "planned_duration_min":30,"travel_minutes_from_previous":0,
                 "is_fixed":false}
            """.trimIndent()
        }
        fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stobee/chat") { exchange ->
                recommendationCalls.incrementAndGet()
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                assertTrue(request.getString("message").contains("추천"))
                respond(
                    exchange,
                    """{"session_id":"session-1","message":"추천 장소입니다.","recommendations":$recommendations}""",
                )
            }
            createContext("/basket-items") { exchange ->
                basketWrites.incrementAndGet()
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                assertEquals(14L, request.getLong("trip_id"))
                respond(
                    exchange,
                    """{"id":${200 + basketWrites.get()},"place_id":1,"status":"active"}""",
                )
            }
            createContext("/trips/14") { exchange ->
                val path = exchange.requestURI.path
                when {
                    path.endsWith("/proposals/preview") -> {
                        val request = JSONObject(
                            exchange.requestBody.bufferedReader().use { it.readText() }
                        )
                        assertEquals(5, request.getJSONArray("actions").length())
                        previewWrites.incrementAndGet()
                        respond(
                            exchange,
                            """
                                {"suggestion_id":"00000000-0000-0000-0000-000000000018",
                                 "trip_id":14,"base_version":1,"status":"ready",
                                 "proposal_fingerprint":"${"b".repeat(64)}","feasible":true,
                                 "actions":[$previewActions],"fixed_basket_item_ids":[],
                                 "excluded_unresolved_basket_item_ids":[],"violations":[]}
                            """.trimIndent(),
                        )
                    }
                    path == "/trips/14" -> respond(
                        exchange,
                        """
                            {"id":14,"title":"추천 여행","activity_type":"tour",
                             "mode":"planning","visibility":"private",
                             "planned_start_date":"2026-09-01","planned_end_date":"2026-09-02"}
                        """.trimIndent(),
                    )
                    path == "/trips/14/basket" -> respond(
                        exchange,
                        (1..5).joinToString(",", prefix = "[", postfix = "]") { index ->
                            """
                                {"id":${200 + index},"item_type":"place",
                                 "title":"추천 장소 $index","category":"tour",
                                 "source":"canonical","status":"active"}
                            """.trimIndent()
                        },
                    )
                    path == "/trips/14/itinerary" -> respond(
                        exchange,
                        """{"version":1,"items":[]}""",
                    )
                    else -> error("Unexpected path: $path")
                }
            }
            start()
        }
        try {
            val client = TravelGuideAiApiClient("http://127.0.0.1:${server.address.port}")

            val proposal = client.previewRecommendedItinerary("token", 14, "추천 여행")

            assertEquals(5, proposal.actions.size)
            assertEquals(1, recommendationCalls.get())
            assertEquals(5, basketWrites.get())
            assertEquals(1, previewWrites.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun basketPreviewRetriesWithoutRoutesWhenRoutesProviderFails() {
        assertBasketPreviewRetriesWithoutRoutes("GOOGLE_ROUTES_FAILED")
    }

    @Test
    fun basketPreviewRetriesWithoutRoutesWhenRoutesProviderReturnsEmpty() {
        assertBasketPreviewRetriesWithoutRoutes("GOOGLE_ROUTES_EMPTY")
    }

    private fun assertBasketPreviewRetriesWithoutRoutes(routeErrorCode: String) {
        val previewWrites = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/trips/14") { exchange ->
                val path = exchange.requestURI.path
                val response: String
                val status: Int
                if (path.endsWith("/proposals/preview")) {
                    val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    previewWrites.incrementAndGet()
                    if (request.has("travel_mode")) {
                        status = 502
                        response = """{"code":"$routeErrorCode","message":"route unavailable"}"""
                    } else {
                        status = 200
                        response = """
                            {"suggestion_id":"00000000-0000-0000-0000-000000000017",
                             "trip_id":14,"base_version":1,"status":"ready",
                             "proposal_fingerprint":"${"a".repeat(64)}","feasible":true,
                             "actions":[],"fixed_basket_item_ids":[],
                             "excluded_unresolved_basket_item_ids":[],"violations":[]}
                        """.trimIndent()
                    }
                } else {
                    status = 200
                    response = when (path) {
                        "/trips/14" -> """
                            {"id":14,"title":"장소 여행","activity_type":"tour",
                             "mode":"planning","visibility":"private",
                             "planned_start_date":"2026-09-01","planned_end_date":"2026-09-02"}
                        """.trimIndent()
                        "/trips/14/basket" -> """
                            [{"id":41,"item_type":"place","title":"장소 1","category":"tour",
                              "source":"canonical","status":"resolved"},
                             {"id":42,"item_type":"place","title":"장소 2","category":"tour",
                              "source":"canonical","status":"resolved"}]
                        """.trimIndent()
                        "/trips/14/itinerary" -> """{"version":1,"items":[]}"""
                        else -> error("Unexpected path: $path")
                    }
                }
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val client = TravelGuideAiApiClient("http://127.0.0.1:${server.address.port}")

            val proposal = client.previewBasketItinerary("token", 14)

            assertEquals(14L, proposal.tripId)
            assertEquals(2, previewWrites.get())
        } finally {
            server.stop(0)
        }
    }

    private fun fakeBackend(previewWrites: AtomicInteger, applyWrites: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/trips/14/itinerary") { exchange ->
                val response = """
                    {"version":1,"items":[
                      {"basket_item_id":41,"day_number":1,"order_index":0,
                       "planned_arrival":"09:00","planned_duration_min":60,"is_fixed":true},
                      {"basket_item_id":42,"day_number":1,"order_index":1,
                       "planned_arrival":"10:30","planned_duration_min":45,"is_fixed":false}
                    ]}
                """.trimIndent().toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/trips/14/itinerary/proposals") { exchange ->
                val path = exchange.requestURI.path
                if (path.endsWith("/apply")) {
                    val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    assertEquals("a".repeat(64), request.getString("proposal_fingerprint"))
                    applyWrites.incrementAndGet()
                    val response = """
                        {"suggestion_id":"00000000-0000-0000-0000-000000000014",
                         "trip_id":14,"status":"applied","itinerary_version":2,
                         "itinerary_change_id":31,"items":[]}
                    """.trimIndent().toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                } else {
                    val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    assertEquals(1L, request.getLong("base_version"))
                    assertEquals("WALK", request.getString("travel_mode"))
                    assertEquals(41L, request.getJSONArray("actions").getJSONObject(0).getLong("basket_item_id"))
                    previewWrites.incrementAndGet()
                    val response = """
                        {"suggestion_id":"00000000-0000-0000-0000-000000000014",
                         "trip_id":14,"base_version":1,"status":"ready",
                         "proposal_fingerprint":"${"a".repeat(64)}","feasible":true,
                         "actions":[{"action_order":0,"type":"set_itinerary_item",
                           "basket_item_id":41,"day_number":1,"order_index":0,
                           "planned_arrival":"09:00","planned_duration_min":60,
                           "travel_minutes_from_previous":0,"is_fixed":true}],
                         "fixed_basket_item_ids":[41],
                         "excluded_unresolved_basket_item_ids":[],"violations":[],
                         "trail_legs":[{"from_basket_item_id":41,"to_basket_item_id":42,
                           "travel_mode":"WALK","duration_minutes":15,"distance_meters":1200,
                           "encoded_polyline":"encoded"}]}
                    """.trimIndent().toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            start()
        }
}
