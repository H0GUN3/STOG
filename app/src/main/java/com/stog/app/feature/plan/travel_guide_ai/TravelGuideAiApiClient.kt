package com.stog.app.feature.plan.travel_guide_ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.stog.app.feature.space.BasketItem
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.stobee.StobeeChatApiClient
import com.stog.app.feature.stobee.toPlaceSearchCandidate
import org.json.JSONArray
import org.json.JSONObject

internal class TravelGuideAiRequestException(
    val statusCode: Int,
    val code: String,
    val authExpired: Boolean,
) : IOException(code)

internal class TravelGuideAiApiClient(
    private val baseUrl: String,
) {
    fun previewBasketItinerary(
        accessToken: String,
        tripId: Long,
    ): TravelGuideAiProposal {
        val planning = PlanningApiClient(baseUrl)
        val trip = planning.trip(accessToken, tripId)
        val basket = planning.basket(accessToken, tripId)
        val snapshot = itinerary(accessToken, tripId)
        val startDate = trip.plannedStartDate?.let(LocalDate::parse)
        val endDate = trip.plannedEndDate?.let(LocalDate::parse)
        if (startDate == null || endDate == null) {
            throw TravelGuideAiRequestException(0, "PROPOSAL_SCHEDULE_INCOMPLETE", false)
        }
        val dayCount = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
        if (dayCount < 1) {
            throw TravelGuideAiRequestException(0, "PROPOSAL_DAY_WINDOW_INVALID", false)
        }
        val actions = basketActions(basket, snapshot.items, dayCount)
        val windows = (1..dayCount).map { day ->
            JSONObject()
                .put("day_number", day)
                .put("start", "09:00")
                .put("end", "22:00")
        }
        val body = JSONObject()
            .put("base_version", snapshot.version)
            .put("day_windows", JSONArray(windows))
            .put("actions", JSONArray(actions))
            .put("travel_mode", "WALK")
        val request = HttpRequest(
            accessToken,
            "/trips/$tripId/itinerary/proposals/preview",
            "POST",
            body,
        )
        return try {
            parseProposal(execute(request))
        } catch (error: TravelGuideAiRequestException) {
            if (error.code !in setOf("GOOGLE_ROUTES_FAILED", "GOOGLE_ROUTES_EMPTY")) throw error
            val withoutRoutes = JSONObject(body.toString()).apply {
                remove("travel_mode")
            }
            parseProposal(execute(request.copy(body = withoutRoutes)))
        }
    }

    fun previewRecommendedItinerary(
        accessToken: String,
        tripId: Long,
        tripTitle: String,
    ): TravelGuideAiProposal {
        val recommendations = StobeeChatApiClient(baseUrl)
            .chat(
                accessToken = accessToken,
                sessionId = "stobee-itinerary-$tripId-${UUID.randomUUID()}",
                message = "$tripTitle 여행 일정을 추천해줘",
            )
            .recommendations
            .take(5)
        if (recommendations.isEmpty()) {
            throw TravelGuideAiRequestException(0, "STOBEE_RECOMMENDATIONS_EMPTY", false)
        }
        val planning = PlanningApiClient(baseUrl)
        recommendations.forEach { recommendation ->
            planning.addToBasket(accessToken, tripId, recommendation.toPlaceSearchCandidate())
        }
        return previewBasketItinerary(accessToken, tripId)
    }

    fun previewCurrentItinerary(accessToken: String, tripId: Long): TravelGuideAiProposal {
        val snapshot = itinerary(accessToken, tripId)
        val scheduledItems = snapshot.items.mapNotNull { item ->
            val arrival = item.plannedArrival ?: return@mapNotNull null
            val duration = item.plannedDurationMin ?: return@mapNotNull null
            ScheduledItem(
                item.basketItemId,
                item.dayNumber,
                item.orderIndex,
                arrival,
                duration,
                item.fixed,
            )
        }
        if (scheduledItems.size != snapshot.items.size || scheduledItems.isEmpty()) {
            throw TravelGuideAiRequestException(0, "PROPOSAL_SCHEDULE_INCOMPLETE", false)
        }
        val windows = scheduledItems.groupBy(ScheduledItem::dayNumber)
            .toSortedMap()
            .map { (day, items) ->
                val start = items.minOf { LocalTime.parse(it.plannedArrival) }
                val end = items.maxOf {
                    LocalTime.parse(it.plannedArrival).plusMinutes(it.plannedDurationMin.toLong())
                }
                if (!end.isAfter(start)) {
                    throw TravelGuideAiRequestException(0, "PROPOSAL_DAY_WINDOW_INVALID", false)
                }
                JSONObject()
                    .put("day_number", day)
                    .put("start", start.toString())
                    .put("end", end.toString())
            }
        val actions = scheduledItems.sortedWith(
            compareBy(ScheduledItem::dayNumber)
                .thenBy(ScheduledItem::orderIndex)
                .thenBy(ScheduledItem::basketItemId),
        ).map { item ->
            JSONObject()
                .put("basket_item_id", item.basketItemId)
                .put("day_number", item.dayNumber)
                .put("order_index", item.orderIndex)
                .put("planned_arrival", item.plannedArrival)
                .put("planned_duration_min", item.plannedDurationMin)
                .put("travel_minutes_from_previous", 0)
                .put("is_fixed", item.fixed)
        }
        val body = JSONObject()
            .put("base_version", snapshot.version)
            .put("day_windows", JSONArray(windows))
            .put("actions", JSONArray(actions))
            .put("travel_mode", "WALK")
        return parseProposal(
            execute(HttpRequest(accessToken, "/trips/$tripId/itinerary/proposals/preview", "POST", body)),
        )
    }

    fun apply(
        accessToken: String,
        proposal: TravelGuideAiProposal,
        clientApplyId: String = UUID.randomUUID().toString(),
    ): TravelGuideAiApplied {
        val body = JSONObject()
            .put("client_apply_id", clientApplyId)
            .put("proposal_fingerprint", proposal.fingerprint)
        val response = execute(
            HttpRequest(
                accessToken,
                "/trips/${proposal.tripId}/itinerary/proposals/${proposal.suggestionId}/apply",
                "POST",
                body,
            ),
        )
        return TravelGuideAiApplied(
            suggestionId = response.getString("suggestion_id"),
            tripId = response.getLong("trip_id"),
            itineraryVersion = response.getLong("itinerary_version"),
            itineraryChangeId = response.getLong("itinerary_change_id"),
        )
    }

    private fun basketActions(
        basket: List<BasketItem>,
        existing: List<ItinerarySnapshotItem>,
        dayCount: Int,
    ): List<JSONObject> {
        val existingById = existing.associateBy(ItinerarySnapshotItem::basketItemId)
        val nextOrder = IntArray(dayCount)
        existing.forEach { item ->
            if (item.dayNumber in 1..dayCount) {
                nextOrder[item.dayNumber - 1] =
                    maxOf(nextOrder[item.dayNumber - 1], item.orderIndex + 1)
            }
        }
        return basket.map { item ->
            val current = existingById[item.id]
            val scheduled = current?.let {
                val arrival = it.plannedArrival
                val duration = it.plannedDurationMin
                if (arrival != null && duration != null) {
                    ScheduledItem(
                        item.id,
                        it.dayNumber.coerceIn(1, dayCount),
                        it.orderIndex,
                        arrival,
                        duration,
                        it.fixed,
                    )
                } else {
                    null
                }
            }
            val fallbackDay = nextOrder.indices.minBy { nextOrder[it] }
            val fallbackOrder = nextOrder[fallbackDay]
            val resolved = scheduled ?: ScheduledItem(
                item.id,
                fallbackDay + 1,
                fallbackOrder,
                LocalTime.of(9, 0).plusMinutes(fallbackOrder * 90L).toString(),
                60,
                false,
            )
            nextOrder[resolved.dayNumber - 1] =
                maxOf(nextOrder[resolved.dayNumber - 1], resolved.orderIndex + 1)
            JSONObject()
                .put("basket_item_id", resolved.basketItemId)
                .put("day_number", resolved.dayNumber)
                .put("order_index", resolved.orderIndex)
                .put("planned_arrival", resolved.plannedArrival)
                .put("planned_duration_min", resolved.plannedDurationMin)
                .put("travel_minutes_from_previous", 0)
                .put("is_fixed", resolved.fixed)
        }
    }

    private fun itinerary(accessToken: String, tripId: Long): ItinerarySnapshot {
        val response = execute(HttpRequest(accessToken, "/trips/$tripId/itinerary", "GET", null))
        val items = response.getJSONArray("items")
        return ItinerarySnapshot(
            version = response.getLong("version"),
            items = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ItinerarySnapshotItem(
                    basketItemId = item.getLong("basket_item_id"),
                    dayNumber = item.getInt("day_number"),
                    orderIndex = item.getInt("order_index"),
                    plannedArrival = item.optString("planned_arrival").takeIf(String::isNotBlank),
                    plannedDurationMin = item.optInt("planned_duration_min").takeIf {
                        item.has("planned_duration_min") && !item.isNull("planned_duration_min")
                    },
                    fixed = item.optBoolean("is_fixed", false),
                )
            },
        )
    }

    private fun execute(request: HttpRequest): JSONObject {
        val connection = URL("${baseUrl.trimEnd('/')}${request.path}")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = AI_TIMEOUT_MILLIS
            connection.readTimeout = AI_TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "Bearer ${request.accessToken}")
            request.body?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
            val statusCode = connection.responseCode
            val responseBody = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                val code = runCatching { JSONObject(responseBody).optString("code") }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: "TRAVEL_GUIDE_AI_REQUEST_FAILED"
                throw TravelGuideAiRequestException(statusCode, code, statusCode == 401)
            }
            return JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseProposal(response: JSONObject): TravelGuideAiProposal {
        val actions = response.getJSONArray("actions")
        val fixedIds = response.getJSONArray("fixed_basket_item_ids")
        val exclusions = response.getJSONArray("excluded_unresolved_basket_item_ids")
        val violations = response.getJSONArray("violations")
        val trailLegs = response.optJSONArray("trail_legs") ?: JSONArray()
        return TravelGuideAiProposal(
            suggestionId = response.getString("suggestion_id"),
            tripId = response.getLong("trip_id"),
            baseVersion = response.getLong("base_version"),
            status = response.getString("status"),
            fingerprint = response.getString("proposal_fingerprint"),
            feasible = response.getBoolean("feasible"),
            actions = List(actions.length()) { index -> actions.getJSONObject(index).toAction() },
            fixedBasketItemIds = List(fixedIds.length()) { fixedIds.getLong(it) },
            excludedUnresolvedBasketItemIds = List(exclusions.length()) { exclusions.getLong(it) },
            violations = List(violations.length()) { index ->
                val violation = violations.getJSONObject(index)
                TravelGuideAiViolation(
                    violation.getString("code"),
                    violation.optLong("basket_item_id").takeIf {
                        violation.has("basket_item_id") && !violation.isNull("basket_item_id")
                    },
                )
            },
            trailLegs = List(trailLegs.length()) { index ->
                val leg = trailLegs.getJSONObject(index)
                TravelGuideAiTrailLeg(
                    fromBasketItemId = leg.getLong("from_basket_item_id"),
                    toBasketItemId = leg.getLong("to_basket_item_id"),
                    travelMode = leg.getString("travel_mode"),
                    durationMinutes = leg.getInt("duration_minutes"),
                    distanceMeters = leg.optLong("distance_meters").takeIf {
                        leg.has("distance_meters") && !leg.isNull("distance_meters")
                    },
                    encodedPolyline = leg.optString("encoded_polyline").takeIf(String::isNotBlank),
                )
            },
        )
    }

    private fun JSONObject.toAction() = TravelGuideAiAction(
        actionOrder = getInt("action_order"),
        basketItemId = getLong("basket_item_id"),
        dayNumber = getInt("day_number"),
        orderIndex = getInt("order_index"),
        plannedArrival = getString("planned_arrival"),
        plannedDurationMin = getInt("planned_duration_min"),
        travelMinutesFromPrevious = getInt("travel_minutes_from_previous"),
        fixed = getBoolean("is_fixed"),
    )

    private data class HttpRequest(
        val accessToken: String,
        val path: String,
        val method: String,
        val body: JSONObject?,
    )

    private data class ItinerarySnapshot(
        val version: Long,
        val items: List<ItinerarySnapshotItem>,
    )

    private data class ItinerarySnapshotItem(
        val basketItemId: Long,
        val dayNumber: Int,
        val orderIndex: Int,
        val plannedArrival: String?,
        val plannedDurationMin: Int?,
        val fixed: Boolean,
    )

    private data class ScheduledItem(
        val basketItemId: Long,
        val dayNumber: Int,
        val orderIndex: Int,
        val plannedArrival: String,
        val plannedDurationMin: Int,
        val fixed: Boolean,
    )

    private companion object {
        const val AI_TIMEOUT_MILLIS = 15_000
    }
}

internal fun basketItemIdsForProposal(basket: List<BasketItem>): List<Long> =
    basket.map(BasketItem::id)
