package com.stog.app.feature.social

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal data class SocialFeedItem(
    val photoId: Long,
    val tripId: Long,
    val ownerId: Long,
    val thumbnailKey: String,
    val thumbnailMedia: SocialThumbnailMedia,
    val caption: String?,
    val cellId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val takenAt: String?,
    val likeCount: Long,
    val likedByViewer: Boolean,
    val createdAt: String?,
    val savedByViewer: Boolean = false,
    val commentCount: Long = 0,
    val ownerNickname: String? = null,
    val ownerProfileImageUrl: String? = null,
)

internal data class SocialComment(
    val id: Long,
    val authorName: String,
    val body: String,
    val createdAt: String?,
)

internal data class SocialThumbnailMedia(
    val status: SocialThumbnailMediaStatus,
    val signedUrl: String?,
) {
    init {
        require((status == SocialThumbnailMediaStatus.AVAILABLE) == (signedUrl != null)) {
            "Signed thumbnail URL does not match media status"
        }
    }
}

internal enum class SocialThumbnailMediaStatus {
    AVAILABLE,
    UNAVAILABLE,
    FAILED,
}

internal data class SocialFeedPage(
    val items: List<SocialFeedItem>,
    val nextCursor: String?,
)

internal data class SocialLikeState(
    val photoId: Long,
    val likeCount: Long,
    val likedByViewer: Boolean,
)

internal data class SocialSaveState(
    val photoId: Long,
    val savedByViewer: Boolean,
)

internal data class PublicTripVisit(
    val visitId: Long,
    val cellId: String,
    val latitude: Double,
    val longitude: Double,
    val enteredAt: String,
    val leftAt: String,
    val status: String,
    val interpolated: Boolean,
)

internal data class PublicTripMemberTrail(
    val userId: Long,
    val visits: List<PublicTripVisit>,
)

internal data class PublicTripFeedItem(
    val tripId: Long,
    val ownerId: Long,
    val title: String,
    val endedAt: String,
    val likeCount: Long,
    val likedByViewer: Boolean,
    val itineraryItemCount: Long,
    val memberTrails: List<PublicTripMemberTrail>,
)

internal data class PublicTripFeedPage(
    val items: List<PublicTripFeedItem>,
    val nextCursor: String?,
)

internal data class PublicTripLikeState(
    val tripId: Long,
    val likeCount: Long,
    val likedByViewer: Boolean,
)

internal data class PublicTripCopyResult(
    val sourceTripId: Long,
    val destinationTripId: Long,
    val destinationDay: Int,
    val copiedItemCount: Int,
)

internal class SocialApiClient(
    private val baseUrl: String,
) {
    fun feed(
        accessToken: String?,
        cursor: String?,
        limit: Int? = null,
    ): SocialFeedPage {
        val query = buildList {
            cursor?.let { add("cursor=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}") }
            limit?.let { add("limit=$it") }
        }.joinToString("&")
        val path = if (query.isEmpty()) "/feed" else "/feed?$query"
        return parseSocialFeedPage(request(accessToken, path, "GET"))
    }

    fun savedFeed(
        accessToken: String,
        cursor: String?,
        limit: Int? = null,
    ): SocialFeedPage {
        val query = buildList {
            cursor?.let { add("cursor=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}") }
            limit?.let { add("limit=$it") }
        }.joinToString("&")
        val path = if (query.isEmpty()) "/feed/saved" else "/feed/saved?$query"
        return parseSocialFeedPage(request(accessToken, path, "GET"))
    }

    fun publicTrips(accessToken: String?): PublicTripFeedPage =
        parsePublicTripFeedPage(request(accessToken, "/public-trips", "GET"))

    fun likeTrip(accessToken: String, tripId: Long): PublicTripLikeState =
        parsePublicTripLikeState(request(accessToken, "/trips/$tripId/like", "POST"))

    fun unlikeTrip(accessToken: String, tripId: Long): PublicTripLikeState =
        parsePublicTripLikeState(request(accessToken, "/trips/$tripId/like", "DELETE"))

    fun copyTrip(
        accessToken: String,
        sourceTripId: Long,
        destinationTripId: Long,
        destinationDay: Int,
        idempotencyKey: String,
    ): PublicTripCopyResult {
        val body = JSONObject()
            .put("destination_trip_id", destinationTripId)
            .put("destination_day", destinationDay)
            .put("idempotency_key", idempotencyKey)
            .toString()
        return parsePublicTripCopyResult(
            request(accessToken, "/trips/$sourceTripId/copy", "POST", body),
        )
    }

    fun like(accessToken: String, photoId: Long): SocialLikeState =
        parseSocialLikeState(request(accessToken, "/photos/$photoId/like", "POST"))

    fun unlike(accessToken: String, photoId: Long): SocialLikeState =
        parseSocialLikeState(request(accessToken, "/photos/$photoId/like", "DELETE"))

    fun save(accessToken: String, photoId: Long): SocialSaveState =
        parseSocialSaveState(request(accessToken, "/photos/$photoId/save", "POST"))

    fun unsave(accessToken: String, photoId: Long): SocialSaveState =
        parseSocialSaveState(request(accessToken, "/photos/$photoId/save", "DELETE"))

    fun comments(accessToken: String?, photoId: Long): List<SocialComment> {
        val response = JSONObject(request(accessToken, "/photos/$photoId/comments", "GET"))
        val items = response.getJSONArray("items")
        return List(items.length()) { index ->
            parseSocialComment(items.getJSONObject(index))
        }
    }

    fun addComment(accessToken: String, photoId: Long, body: String): SocialComment =
        parseSocialComment(
            JSONObject(
                request(
                    accessToken,
                    "/photos/$photoId/comments",
                    "POST",
                    JSONObject().put("body", body).toString(),
                ),
            ),
        )

    private fun request(
        accessToken: String?,
        path: String,
        method: String,
        body: String? = null,
    ): String {
        val connection = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = SOCIAL_NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = SOCIAL_NETWORK_TIMEOUT_MILLIS
            accessToken?.takeIf(String::isNotBlank)?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output ->
                    output.write(it.toByteArray(StandardCharsets.UTF_8))
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
                }.getOrNull()?.takeIf(String::isNotBlank) ?: "SOCIAL_REQUEST_FAILED"
                throw SocialRequestException(responseCode, code)
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }
}

internal class SocialRequestException(
    val statusCode: Int,
    val errorCode: String,
) : IllegalStateException("$errorCode ($statusCode)")

internal fun parseSocialFeedPage(response: String): SocialFeedPage =
    parseSocialFeedPage(JSONObject(response))

internal fun parseSocialFeedPage(response: JSONObject): SocialFeedPage {
    val items = response.getJSONArray("items")
    return SocialFeedPage(
        items = List(items.length()) { index -> parseSocialFeedItem(items.getJSONObject(index)) },
        nextCursor = response.optionalSocialString("next_cursor"),
    )
}

internal fun parseSocialFeedItem(item: JSONObject): SocialFeedItem = SocialFeedItem(
    photoId = item.getLong("photo_id"),
    tripId = item.getLong("trip_id"),
    ownerId = item.getLong("owner_id"),
    ownerNickname = item.optionalSocialString("owner_nickname"),
    ownerProfileImageUrl = item.optionalSocialString("owner_profile_image_url"),
    thumbnailKey = item.getString("thumbnail_key"),
    thumbnailMedia = parseSocialThumbnailMedia(item.getJSONObject("thumbnail_media")),
    caption = item.optionalSocialString("caption"),
    cellId = item.optionalSocialString("cell_id"),
    latitude = item.optionalSocialDouble("lat"),
    longitude = item.optionalSocialDouble("lng"),
    takenAt = item.optionalSocialString("taken_at"),
    likeCount = item.getLong("like_count"),
    likedByViewer = item.getBoolean("liked_by_viewer"),
    createdAt = item.optionalSocialString("created_at"),
    savedByViewer = item.optBoolean("saved_by_viewer", false),
    commentCount = item.optLong("comment_count", 0),
)

private fun parseSocialComment(item: JSONObject): SocialComment = SocialComment(
    id = item.getLong("id"),
    authorName = item.getString("author_name"),
    body = item.getString("body"),
    createdAt = item.optionalSocialString("created_at"),
)

internal fun parseSocialThumbnailMedia(media: JSONObject): SocialThumbnailMedia {
    val status = when (media.getString("state")) {
        "available" -> SocialThumbnailMediaStatus.AVAILABLE
        "unavailable" -> SocialThumbnailMediaStatus.UNAVAILABLE
        "failed" -> SocialThumbnailMediaStatus.FAILED
        else -> throw IllegalArgumentException("Unknown thumbnail media state")
    }
    return SocialThumbnailMedia(status, media.optionalSocialString("signed_url"))
}

internal fun parsePublicTripFeedPage(response: String): PublicTripFeedPage {
    val value = JSONObject(response)
    val items = value.getJSONArray("items")
    return PublicTripFeedPage(
        items = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val trails = item.getJSONArray("member_trails")
            PublicTripFeedItem(
                tripId = item.getLong("trip_id"),
                ownerId = item.getLong("owner_id"),
                title = item.getString("title"),
                endedAt = item.getString("ended_at"),
                likeCount = item.getLong("like_count"),
                likedByViewer = item.getBoolean("liked_by_viewer"),
                itineraryItemCount = item.getLong("itinerary_item_count"),
                memberTrails = List(trails.length()) { trailIndex ->
                    val trail = trails.getJSONObject(trailIndex)
                    val visits = trail.getJSONArray("visits")
                    PublicTripMemberTrail(
                        userId = trail.getLong("user_id"),
                        visits = List(visits.length()) { visitIndex ->
                            val visit = visits.getJSONObject(visitIndex)
                            PublicTripVisit(
                                visitId = visit.getLong("visit_id"),
                                cellId = visit.getString("cell_id"),
                                latitude = visit.getDouble("lat"),
                                longitude = visit.getDouble("lng"),
                                enteredAt = visit.getString("entered_at"),
                                leftAt = visit.getString("left_at"),
                                status = visit.getString("status"),
                                interpolated = visit.getBoolean("is_interpolated"),
                            )
                        },
                    )
                },
            )
        },
        nextCursor = value.optionalSocialString("next_cursor"),
    )
}

internal fun parsePublicTripLikeState(response: String): PublicTripLikeState {
    val value = JSONObject(response)
    return PublicTripLikeState(
        tripId = value.getLong("trip_id"),
        likeCount = value.getLong("like_count"),
        likedByViewer = value.getBoolean("liked_by_viewer"),
    )
}

internal fun parsePublicTripCopyResult(response: String): PublicTripCopyResult {
    val value = JSONObject(response)
    return PublicTripCopyResult(
        sourceTripId = value.getLong("source_trip_id"),
        destinationTripId = value.getLong("destination_trip_id"),
        destinationDay = value.getInt("destination_day"),
        copiedItemCount = value.getInt("copied_item_count"),
    )
}

internal fun parseSocialLikeState(response: String): SocialLikeState =
    parseSocialLikeState(JSONObject(response))

internal fun parseSocialLikeState(response: JSONObject): SocialLikeState = SocialLikeState(
    photoId = response.getLong("photo_id"),
    likeCount = response.getLong("like_count"),
    likedByViewer = response.getBoolean("liked_by_viewer"),
)

internal fun parseSocialSaveState(response: String): SocialSaveState =
    parseSocialSaveState(JSONObject(response))

internal fun parseSocialSaveState(response: JSONObject): SocialSaveState = SocialSaveState(
    photoId = response.getLong("photo_id"),
    savedByViewer = response.getBoolean("saved_by_viewer"),
)

internal fun mergeSocialFeed(
    existing: List<SocialFeedItem>,
    page: SocialFeedPage,
): List<SocialFeedItem> {
    val merged = LinkedHashMap<Long, SocialFeedItem>()
    existing.forEach { merged[it.photoId] = it }
    page.items.forEach { merged.putIfAbsent(it.photoId, it) }
    return merged.values.toList()
}

internal fun applySocialLikeState(
    item: SocialFeedItem,
    state: SocialLikeState,
): SocialFeedItem {
    require(item.photoId == state.photoId) { "Like state belongs to another photo" }
    return item.copy(
        likeCount = state.likeCount,
        likedByViewer = state.likedByViewer,
    )
}

internal fun applySocialSaveState(
    item: SocialFeedItem,
    state: SocialSaveState,
): SocialFeedItem {
    require(item.photoId == state.photoId) { "Save state belongs to another photo" }
    return item.copy(savedByViewer = state.savedByViewer)
}

private fun JSONObject.optionalSocialString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.optionalSocialDouble(name: String): Double? =
    if (isNull(name)) null else optDouble(name)

internal const val SOCIAL_NETWORK_TIMEOUT_MILLIS = 15_000
