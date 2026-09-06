package com.stog.app.feature.space

import com.stog.app.feature.auth.AuthSessionRuntime
import com.stog.app.feature.record.PhotoApiClient
import com.stog.app.feature.record.PhotoHttpTransport
import com.stog.app.feature.record.UrlConnectionPhotoTransport
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class CreatedTrip(
    val id: Long,
    val title: String,
    val plannedStartDate: String? = null,
    val plannedEndDate: String? = null,
    val coverImageUrl: String? = null,
    val regionCode: String = "JEONBUK",
)

data class TripSummary(
    val id: Long,
    val title: String,
    val activityType: String,
    val mode: String,
    val visibility: String,
    val plannedStartDate: String? = null,
    val plannedEndDate: String? = null,
    val coverImageUrl: String? = null,
    val regionCode: String = "JEONBUK",
)

data class HomeSummary(
    val monthlyTripCount: Long,
    val visitedCellCount: Long,
    val savedPlaceCount: Long,
    val monthlyReceivedLikeCount: Long,
    val trips: List<TripSummary>,
)

data class TripPhotoPreview(
    val id: Long,
    val thumbnailUrl: String?,
)

data class AddedBasketItem(
    val id: Long,
    val placeId: Long,
    val cellId: String?,
    val status: String,
)

class PlanningRequestException(
    val statusCode: Int,
    val code: String,
    val path: String? = null,
    val responseBody: String = "",
) : IOException("$code ($statusCode)${path?.let { " $it" }.orEmpty()}")

internal class TripCoverUploadException(
    val stage: String,
    cause: Throwable,
) : IOException("TRIP_COVER_$stage failed", cause)

data class BasketItem(
    val id: Long,
    val itemType: String,
    val title: String,
    val category: String?,
    val source: String?,
    val status: String,
    val addedAt: String?,
    val imageUrl: String? = null,
)

data class ItineraryItem(
    val basketItemId: Long,
    val dayNumber: Int,
    val orderIndex: Int,
    val plannedArrival: String?,
    val plannedDurationMin: Int?,
    val fixed: Boolean = false,
    val placeId: Long? = null,
    val title: String? = null,
    val category: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val imageUrl: String? = null,
)

data class ItineraryChange(
    val id: Long,
    val userId: Long,
    val action: String,
    val createdAt: String,
)

data class TripModeUpdate(
    val tripId: Long,
    val mode: String,
    val startedAt: String?,
    val endedAt: String?,
)

data class TripArchiveTrailPoint(
    val visitId: Long,
    val userId: Long,
    val cellId: String,
    val latitude: Double,
    val longitude: Double,
    val enteredAt: String,
    val leftAt: String,
    val status: String,
    val isInterpolated: Boolean,
)

data class TripMember(
    val userId: Long,
    val nickname: String,
    val joinedAt: String,
)

data class TripMembers(
    val viewerId: Long,
    val ownerId: Long,
    val members: List<TripMember>,
)

data class TripInvite(
    val token: String,
    val joinPath: String,
)

data class JoinedTrip(val tripId: Long)

data class TripArchiveSummary(
    val itineraryItemCount: Long,
    val itineraryChangeCount: Long,
    val visitCount: Long,
    val visitedCount: Long,
    val passedCount: Long,
    val visitedCellCount: Long,
    val photoCount: Long,
    val trail: List<TripArchiveTrailPoint> = emptyList(),
)

internal data class TripRequestPayload(
    val title: String,
    val activityType: String,
    val plannedStartDate: String? = null,
    val plannedEndDate: String? = null,
    val regionCode: String = "JEONBUK",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("activity_type", activityType)
        .apply {
            plannedStartDate?.let { put("planned_start_date", it) }
            plannedEndDate?.let { put("planned_end_date", it) }
            put("region_code", regionCode)
        }

    fun toUpdateJson(): JSONObject = JSONObject()
        .put("title", title)
        .apply {
            plannedStartDate?.let { put("planned_start_date", it) }
            plannedEndDate?.let { put("planned_end_date", it) }
        }
}

internal data class BasketRequestPayload(
    val tripId: Long,
    val clientItemId: String,
    val payloadFingerprint: String,
    val provider: String,
    val externalId: String,
    val name: String,
    val category: String,
    val latitude: Double?,
    val longitude: Double?,
    val canonicalPlaceId: Long? = null,
    val canonicalSourceId: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("trip_id", tripId)
        .put("client_item_id", clientItemId)
        .put("payload_fingerprint", payloadFingerprint)
        .put("provider", provider)
        .put("external_id", externalId)
        .put("name", name)
        .put("category", category)
        .put("latitude", latitude ?: JSONObject.NULL)
        .put("longitude", longitude ?: JSONObject.NULL)
        .put("canonical_place_id", canonicalPlaceId ?: JSONObject.NULL)
        .put("canonical_source_id", canonicalSourceId ?: JSONObject.NULL)
}

internal data class LinkBasketRequestPayload(
    val tripId: Long,
    val clientItemId: String,
    val payloadFingerprint: String,
    val source: String,
    val originalUrl: String,
    val title: String,
    val category: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("trip_id", tripId)
        .put("client_item_id", clientItemId)
        .put("payload_fingerprint", payloadFingerprint)
        .put("source", source)
        .put("original_url", originalUrl)
        .put("title", title)
        .put("category", category ?: JSONObject.NULL)
}

class PlanningApiClient internal constructor(
    private val baseUrl: String,
    private val uploadTransport: PhotoHttpTransport = UrlConnectionPhotoTransport(),
) {
    fun createTrip(
        accessToken: String,
        title: String,
        activityType: String,
        plannedStartDate: String? = null,
        plannedEndDate: String? = null,
        regionCode: String = "JEONBUK",
    ): CreatedTrip {
        val response = post(
            accessToken,
            "/trips",
            tripRequestPayload(
                title,
                activityType,
                plannedStartDate,
                plannedEndDate,
                regionCode,
            ).toJson(),
        )
        return CreatedTrip(
            id = response.getLong("id"),
            title = response.getString("title"),
            plannedStartDate = response.optionalString("planned_start_date"),
            plannedEndDate = response.optionalString("planned_end_date"),
            coverImageUrl = response.optionalString("cover_image_url"),
            regionCode = response.optString("region_code", "JEONBUK"),
        )
    }

    fun uploadTripCover(
        accessToken: String,
        tripId: Long,
        file: File,
    ) {
        require(file.isFile) { "Trip cover file is missing" }
        val uploadId = UUID.randomUUID().toString()
        val sizeBytes = file.length()
        val digest = fileSha256(file)
        val upload = tripCoverUploadStage("ISSUE_URLS") {
            post(
                accessToken,
                "/trips/$tripId/cover/upload-url",
                JSONObject()
                    .put("client_upload_id", uploadId)
                    .put("content_type", "image/jpeg")
                    .put("size_bytes", sizeBytes)
                    .put("sha256", digest),
            )
        }
        tripCoverUploadStage("DIRECT_PUT") {
            PhotoApiClient(baseUrl, uploadTransport).putDirectly(
                signedUrl = upload.getString("upload_url"),
                headers = upload.stringMap("upload_headers"),
                file = file,
            )
        }
        tripCoverUploadStage("FINALIZE") {
            request(
                accessToken,
                "/trips/$tripId/cover/finalize",
                "POST",
                JSONObject()
                    .put("client_upload_id", uploadId)
                    .put("content_type", "image/jpeg")
                    .put("size_bytes", sizeBytes)
                    .put("sha256", digest)
                    .toString(),
            )
        }
    }

    fun updateTrip(
        accessToken: String,
        tripId: Long,
        title: String,
        plannedStartDate: String,
        plannedEndDate: String,
    ): CreatedTrip {
        val response = JSONObject(
            request(
                accessToken,
                "/trips/$tripId",
                "PUT",
                tripRequestPayload(
                    title = title,
                    activityType = "tour",
                    plannedStartDate = plannedStartDate,
                    plannedEndDate = plannedEndDate,
                ).toUpdateJson().toString(),
            ),
        )
        return CreatedTrip(
            id = response.getLong("id"),
            title = response.getString("title"),
            plannedStartDate = response.optionalString("planned_start_date"),
            plannedEndDate = response.optionalString("planned_end_date"),
            coverImageUrl = response.optionalString("cover_image_url"),
        )
    }

    fun trip(accessToken: String, tripId: Long): TripSummary = parseTripSummary(
        JSONObject(request(accessToken, "/trips/$tripId", "GET", null)),
    )

    fun listTrips(accessToken: String): List<TripSummary> {
        val response = getArray(accessToken, "/trips/me")
        return (0 until response.length()).map { index ->
            val trip = response.getJSONObject(index)
            tripSummary(
                id = trip.getLong("id"),
                title = trip.getString("title"),
                activityType = trip.getString("activity_type"),
                mode = trip.getString("mode"),
                visibility = trip.getString("visibility"),
                plannedStartDate = trip.optionalString("planned_start_date"),
                plannedEndDate = trip.optionalString("planned_end_date"),
                coverImageUrl = trip.optionalString("cover_image_url"),
                regionCode = trip.optString("region_code", "JEONBUK"),
            )
        }
    }

    fun home(accessToken: String): HomeSummary {
        return parseHomeSummary(request(accessToken, "/trips/me/home", "GET", null))
    }

    fun tripPhotos(accessToken: String, tripId: Long): List<TripPhotoPreview> =
        parseTripPhotos(request(accessToken, "/trips/$tripId/photos", "GET", null))

    internal fun parseHomeSummary(responseBody: String): HomeSummary {
        val response = JSONObject(responseBody)
        val trips = response.getJSONArray("trips")
        return HomeSummary(
            monthlyTripCount = response.getLong("monthly_trip_count"),
            visitedCellCount = response.getLong("visited_cell_count"),
            savedPlaceCount = response.getLong("saved_place_count"),
            monthlyReceivedLikeCount = response.getLong("monthly_received_like_count"),
            trips = List(trips.length()) { index -> parseTripSummary(trips.getJSONObject(index)) },
        )
    }

    fun addToBasket(
        accessToken: String,
        tripId: Long,
        candidate: PlaceSearchCandidate,
    ): AddedBasketItem {
        val response = post(
            accessToken,
            "/basket-items",
            basketRequestPayload(tripId, candidate, UUID.randomUUID().toString()).toJson(),
        )
        return AddedBasketItem(
            id = response.getLong("id"),
            placeId = response.getLong("place_id"),
            cellId = response.optString("cell_id").takeIf(String::isNotBlank),
            status = response.getString("status"),
        )
    }

    fun addConfirmedShareToBasket(
        accessToken: String,
        tripId: Long,
        clientItemId: String,
        payloadFingerprint: String,
        candidate: PlaceSearchCandidate,
    ): AddedBasketItem {
        val payload = basketRequestPayload(tripId, candidate, clientItemId)
        require(payload.payloadFingerprint == payloadFingerprint) { "Immutable share payload fingerprint mismatch" }
        val response = post(accessToken, "/basket-items", payload.toJson())
        return AddedBasketItem(
            id = response.getLong("id"),
            placeId = response.getLong("place_id"),
            cellId = response.optString("cell_id").takeIf(String::isNotBlank),
            status = response.getString("status"),
        )
    }

    fun addLinkToBasket(
        accessToken: String,
        tripId: Long,
        source: String,
        originalUrl: String,
        title: String,
        category: String?,
    ) {
        request(
            accessToken,
            "/basket-items/link",
            "POST",
            linkBasketRequestPayload(
                tripId, source, originalUrl, title, category, UUID.randomUUID().toString(),
            ).toJson().toString(),
        )
    }

    fun members(accessToken: String, tripId: Long): TripMembers = parseTripMembers(
        request(accessToken, "/trips/$tripId/members", "GET", null),
    )

    fun createInvite(accessToken: String, tripId: Long): TripInvite = parseTripInvite(
        request(accessToken, "/trips/$tripId/invite-links", "POST", null),
    )

    fun joinInvite(accessToken: String, token: String): JoinedTrip {
        require(token.isNotBlank()) { "Invite token is required" }
        return parseJoinedTrip(
            request(accessToken, "/trip-invites/${token.trim()}/join", "POST", null),
        )
    }

    fun removeMember(accessToken: String, tripId: Long, userId: Long) {
        request(accessToken, "/trips/$tripId/members/$userId", "DELETE", null)
    }

    fun leaveTrip(accessToken: String, tripId: Long) {
        request(accessToken, "/trips/$tripId/members/me", "DELETE", null)
    }

    fun deleteTrip(accessToken: String, tripId: Long) {
        request(accessToken, "/trips/$tripId", "DELETE", null)
    }

    fun basket(accessToken: String, tripId: Long): List<BasketItem> = parseBasketItems(
        request(accessToken, "/trips/$tripId/basket", "GET", null),
    )

    fun removeBasketItem(accessToken: String, tripId: Long, basketItemId: Long) {
        request(accessToken, "/trips/$tripId/basket/$basketItemId", "DELETE", null)
    }

    fun itinerary(accessToken: String, tripId: Long): List<ItineraryItem> = parseItineraryItems(
        request(accessToken, "/trips/$tripId/itinerary", "GET", null),
    )

    fun saveItinerary(
        accessToken: String,
        tripId: Long,
        items: List<ItineraryItem>,
    ): List<ItineraryItem> = parseItineraryItems(
        request(
            accessToken,
            "/trips/$tripId/itinerary",
            "PUT",
            itineraryRequestBody(items),
        ),
    )

    fun itineraryChanges(accessToken: String, tripId: Long): List<ItineraryChange> =
        parseItineraryChanges(request(accessToken, "/trips/$tripId/itinerary/changes", "GET", null))

    fun updateTripMode(accessToken: String, tripId: Long, mode: String): TripModeUpdate =
        parseTripModeUpdate(
            request(
                accessToken,
                "/trips/$tripId/mode",
                "PATCH",
                JSONObject().put("mode", mode).toString(),
            ),
        )

    fun archive(accessToken: String, tripId: Long): TripArchiveSummary =
        parseTripArchive(request(accessToken, "/trips/$tripId/archive", "GET", null))

    private fun post(
        accessToken: String,
        path: String,
        body: JSONObject,
    ): JSONObject = JSONObject(request(accessToken, path, "POST", body.toString()))

    private fun getArray(
        accessToken: String,
        path: String,
    ): JSONArray = JSONArray(request(accessToken, path, "GET", null))

    private fun request(
        accessToken: String,
        path: String,
        method: String,
        body: String?,
    ): String {
        val retrier = AuthSessionRuntime.retrier
        return if (retrier == null) {
            requestWithToken(accessToken, path, method, body)
        } else {
            retrier.execute(
                accessToken = accessToken,
                isAuthenticationFailure = {
                    it is PlanningRequestException && it.statusCode == 401
                },
            ) { token ->
                requestWithToken(token, path, method, body)
            }
        }
    }

    private fun requestWithToken(
        accessToken: String,
        path: String,
        method: String,
        body: String?,
    ): String {
        val connection = URL("${baseUrl.trimEnd('/')}$path")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = PLANNING_TIMEOUT_MILLIS
            connection.readTimeout = PLANNING_TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(body)
                }
            }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val code = runCatching {
                    JSONObject(responseBody).optString("code")
                }.getOrNull()?.takeIf(String::isNotBlank)
                    ?: "PLANNING_REQUEST_FAILED"
                throw PlanningRequestException(responseCode, code, path, responseBody)
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <T> tripCoverUploadStage(
        stage: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: Exception) {
        throw TripCoverUploadException(stage, error)
    }
}

internal fun tripRequestPayload(
    title: String,
    activityType: String,
    plannedStartDate: String? = null,
    plannedEndDate: String? = null,
    regionCode: String = "JEONBUK",
): TripRequestPayload = TripRequestPayload(
    title,
    activityType,
    plannedStartDate,
    plannedEndDate,
    regionCode,
)

internal fun tripSummary(
    id: Long,
    title: String,
    activityType: String,
    mode: String,
    visibility: String,
    plannedStartDate: String? = null,
    plannedEndDate: String? = null,
    coverImageUrl: String? = null,
    regionCode: String = "JEONBUK",
): TripSummary = TripSummary(
    id,
    title,
    activityType,
    mode,
    visibility,
    plannedStartDate,
    plannedEndDate,
    coverImageUrl,
    regionCode,
)

internal fun basketRequestPayload(
    tripId: Long,
    candidate: PlaceSearchCandidate,
    clientItemId: String = UUID.randomUUID().toString(),
): BasketRequestPayload {
    val provider = when (candidate.provenance) {
        is PlaceSearchProvenance.Canonical -> "canonical"
        PlaceSearchProvenance.GoogleFallback -> "google"
    }
    val category = normalizedPlaceCategory(candidate.types)
    val canonicalPlaceId = (candidate.provenance as? PlaceSearchProvenance.Canonical)?.placeId
    val canonicalSourceId = (candidate.provenance as? PlaceSearchProvenance.Canonical)?.sourceId
    return BasketRequestPayload(
        tripId = tripId,
        clientItemId = clientItemId,
        payloadFingerprint = basketPayloadFingerprint(
            tripId, provider, candidate.externalId, candidate.name, category,
            candidate.latitude, candidate.longitude, canonicalPlaceId, canonicalSourceId,
        ),
        provider = provider,
        externalId = candidate.externalId,
        name = candidate.name,
        category = category,
        latitude = candidate.latitude,
        longitude = candidate.longitude,
        canonicalPlaceId = canonicalPlaceId,
        canonicalSourceId = canonicalSourceId,
    )
}

internal fun linkBasketRequestPayload(
    tripId: Long,
    source: String,
    originalUrl: String,
    title: String,
    category: String?,
    clientItemId: String = UUID.randomUUID().toString(),
): LinkBasketRequestPayload = LinkBasketRequestPayload(
    tripId,
    clientItemId,
    basketPayloadFingerprint(tripId, source, originalUrl, title, category),
    source,
    originalUrl,
    title,
    category,
)

internal fun itineraryRequestBody(items: List<ItineraryItem>): String = buildString {
    append("{\"items\":[")
    items.forEachIndexed { index, item ->
        if (index > 0) append(',')
        append("{\"basket_item_id\":${item.basketItemId}")
        append(",\"day_number\":${item.dayNumber}")
        append(",\"order_index\":${item.orderIndex}")
        append(",\"planned_arrival\":")
        append(item.plannedArrival?.let(::jsonString) ?: "null")
        append(",\"planned_duration_min\":")
        append(item.plannedDurationMin ?: "null")
        append(",\"is_fixed\":${item.fixed}")
        append('}')
    }
    append("]}")
}

internal data class BasketItemResponse(
    val id: Long,
    val itemType: String,
    val title: String,
    val category: String?,
    val source: String?,
    val status: String,
    val addedAt: String?,
    val imageUrl: String? = null,
)

internal fun basketItemsFromResponses(
    responses: List<BasketItemResponse>,
): List<BasketItem> = responses.map { response ->
    BasketItem(
        response.id,
        response.itemType,
        response.title,
        response.category,
        response.source,
        response.status,
        response.addedAt,
        response.imageUrl,
    )
}

internal data class ItineraryItemResponse(
    val basketItemId: Long,
    val dayNumber: Int,
    val orderIndex: Int,
    val plannedArrival: String?,
    val plannedDurationMin: Int?,
    val fixed: Boolean = false,
    val placeId: Long? = null,
    val title: String? = null,
    val category: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val imageUrl: String? = null,
)

internal fun itineraryItemsFromResponses(
    responses: List<ItineraryItemResponse>,
): List<ItineraryItem> = responses.map { response ->
    ItineraryItem(
        response.basketItemId,
        response.dayNumber,
        response.orderIndex,
        response.plannedArrival,
        response.plannedDurationMin,
        response.fixed,
        response.placeId,
        response.title,
        response.category,
        response.latitude,
        response.longitude,
        response.address,
        response.imageUrl,
    )
}

private fun parseTripSummary(trip: JSONObject): TripSummary = tripSummary(
    id = trip.getLong("id"),
    title = trip.getString("title"),
    activityType = trip.getString("activity_type"),
    mode = trip.getString("mode"),
    visibility = trip.getString("visibility"),
    plannedStartDate = trip.optionalString("planned_start_date"),
    plannedEndDate = trip.optionalString("planned_end_date"),
    coverImageUrl = trip.optionalString("cover_image_url"),
    regionCode = trip.optString("region_code", "JEONBUK"),
)

internal fun parseTripPhotos(responseBody: String): List<TripPhotoPreview> {
    val photos = JSONArray(responseBody)
    return List(photos.length()) { index ->
        val photo = photos.getJSONObject(index)
        TripPhotoPreview(
            id = photo.getLong("id"),
            thumbnailUrl = photo.optString("thumbnail_url").takeIf(String::isNotBlank),
        )
    }
}

internal fun parseBasketItems(responseBody: String): List<BasketItem> {
    val array = JSONArray(responseBody)
    return basketItemsFromResponses(List(array.length()) { index ->
        val item = array.getJSONObject(index)
        BasketItemResponse(
            id = item.getLong("id"),
            itemType = item.getString("item_type"),
            title = item.getString("title"),
            category = item.optString("category").takeIf(String::isNotBlank),
            source = item.optString("source").takeIf(String::isNotBlank),
            status = item.getString("status"),
            addedAt = item.optString("added_at").takeIf(String::isNotBlank),
            imageUrl = item.optionalString("image_url"),
        )
    })
}

internal fun parseItineraryItems(responseBody: String): List<ItineraryItem> {
    val items = JSONObject(responseBody).getJSONArray("items")
    return itineraryItemsFromResponses(List(items.length()) { index ->
        val item = items.getJSONObject(index)
        ItineraryItemResponse(
            basketItemId = item.getLong("basket_item_id"),
            dayNumber = item.getInt("day_number"),
            orderIndex = item.getInt("order_index"),
            plannedArrival = item.optString("planned_arrival").takeIf(String::isNotBlank),
            plannedDurationMin = if (item.has("planned_duration_min") &&
                !item.isNull("planned_duration_min")
            ) {
                item.getInt("planned_duration_min")
            } else {
                null
            },
            fixed = item.optBoolean("is_fixed", false),
            placeId = item.longOrNull("place_id"),
            title = item.optString("title").takeIf(String::isNotBlank),
            category = item.optString("category").takeIf(String::isNotBlank),
            latitude = item.finiteDoubleOrNull("lat"),
            longitude = item.finiteDoubleOrNull("lng"),
            address = item.optString("address").takeIf(String::isNotBlank),
            imageUrl = item.optionalString("image_url"),
        )
    })
}

private fun JSONObject.longOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) getLong(name) else null

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.finiteDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf(Double::isFinite) else null

internal fun parseItineraryChanges(responseBody: String): List<ItineraryChange> {
    val changes = JSONObject(responseBody).getJSONArray("changes")
    return List(changes.length()) { index ->
        val change = changes.getJSONObject(index)
        ItineraryChange(
            id = change.getLong("id"),
            userId = change.getLong("user_id"),
            action = change.getString("action"),
            createdAt = change.getString("created_at"),
        )
    }
}

internal fun parseTripMembers(responseBody: String): TripMembers {
    val response = JSONObject(responseBody)
    val members = response.getJSONArray("members")
    return TripMembers(
        viewerId = response.getLong("viewer_id"),
        ownerId = response.getLong("owner_id"),
        members = List(members.length()) { index ->
            val member = members.getJSONObject(index)
            TripMember(
                userId = member.getLong("user_id"),
                nickname = member.getString("nickname"),
                joinedAt = member.getString("joined_at"),
            )
        },
    )
}

internal fun parseTripInvite(responseBody: String): TripInvite {
    val response = JSONObject(responseBody)
    return TripInvite(response.getString("token"), response.getString("join_path"))
}

internal fun parseJoinedTrip(responseBody: String): JoinedTrip = JoinedTrip(
    JSONObject(responseBody).getLong("trip_id"),
)

internal fun parseTripModeUpdate(responseBody: String): TripModeUpdate {
    val response = JSONObject(responseBody)
    return TripModeUpdate(
        tripId = response.getLong("trip_id"),
        mode = response.getString("mode"),
        startedAt = response.optString("started_at").takeIf(String::isNotBlank),
        endedAt = response.optString("ended_at").takeIf(String::isNotBlank),
    )
}

internal fun parseTripArchive(responseBody: String): TripArchiveSummary {
    val response = JSONObject(responseBody)
    return TripArchiveSummary(
        itineraryItemCount = response.getLong("itinerary_item_count"),
        itineraryChangeCount = response.getLong("itinerary_change_count"),
        visitCount = response.getLong("visit_count"),
        visitedCount = response.getLong("visited_count"),
        passedCount = response.getLong("passed_count"),
        visitedCellCount = response.getLong("visited_cell_count"),
        photoCount = response.getLong("photo_count"),
        trail = response.optJSONArray("trail")?.let { trail ->
            List(trail.length()) { index ->
                val point = trail.getJSONObject(index)
                TripArchiveTrailPoint(
                    visitId = point.getLong("visit_id"),
                    userId = point.getLong("user_id"),
                    cellId = point.getString("cell_id"),
                    latitude = point.getDouble("latitude"),
                    longitude = point.getDouble("longitude"),
                    enteredAt = point.getString("entered_at"),
                    leftAt = point.getString("left_at"),
                    status = point.getString("status"),
                    isInterpolated = point.getBoolean("is_interpolated"),
                )
            }
        }.orEmpty(),
    )
}

internal fun basketPayloadFingerprint(vararg values: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value?.toString()?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

internal fun addBasketItemToDay(
    items: List<ItineraryItem>,
    basketItemId: Long,
    dayNumber: Int,
): List<ItineraryItem> {
    if (items.any { it.basketItemId == basketItemId }) return items
    val nextOrder = items.count { it.dayNumber == dayNumber }
    return items + ItineraryItem(basketItemId, dayNumber, nextOrder, null, null)
}

internal fun addBasketItemToDayOne(
    items: List<ItineraryItem>,
    basketItemId: Long,
): List<ItineraryItem> = addBasketItemToDay(items, basketItemId, 1)

internal fun removeItineraryBasketItem(
    items: List<ItineraryItem>,
    basketItemId: Long,
): List<ItineraryItem> = normalizeItineraryOrder(
    items.filterNot { it.basketItemId == basketItemId }
)

internal fun moveDayItem(
    items: List<ItineraryItem>,
    basketItemId: Long,
    dayNumber: Int,
    direction: Int,
): List<ItineraryItem> {
    val dayItems = items
        .filter { it.dayNumber == dayNumber }
        .sortedBy { it.orderIndex }
        .toMutableList()
    val index = dayItems.indexOfFirst { it.basketItemId == basketItemId }
    val target = index + direction
    if (index == -1 || target !in dayItems.indices) return items
    val item = dayItems.removeAt(index)
    dayItems.add(target, item)
    val otherDays = items.filterNot { it.dayNumber == dayNumber }
    return otherDays + dayItems.mapIndexed { order, itineraryItem ->
        itineraryItem.copy(orderIndex = order)
    }
}

internal fun moveDayOneItem(
    items: List<ItineraryItem>,
    basketItemId: Long,
    direction: Int,
): List<ItineraryItem> = moveDayItem(items, basketItemId, 1, direction)

private fun normalizeItineraryOrder(items: List<ItineraryItem>): List<ItineraryItem> {
    return items
        .groupBy { it.dayNumber }
        .toSortedMap()
        .flatMap { (day, dayItems) ->
            dayItems.sortedBy { it.orderIndex }.mapIndexed { order, item ->
                item.copy(dayNumber = day, orderIndex = order)
            }
        }
}

private fun JSONObject.stringMap(name: String): Map<String, String> {
    val value = getJSONObject(name)
    return value.keys().asSequence().associateWith(value::getString)
}

private fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
