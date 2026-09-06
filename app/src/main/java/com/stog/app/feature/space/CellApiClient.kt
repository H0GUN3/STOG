package com.stog.app.feature.space

import android.util.Log
import com.stog.app.feature.record.PhotoVisibility
import com.stog.app.feature.record.setLogAccuracyMeters
import com.stog.app.feature.record.setLogInstant
import com.stog.app.feature.record.setLogModerationStatus
import com.stog.app.feature.record.setLogPlaceResolutionStatus
import com.stog.app.feature.record.setLogPublicationStatus
import com.uber.h3core.H3Core
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal const val CELL_VIEWPORT_DEBOUNCE_MILLIS = 300L
internal const val CELL_POLYGON_MINIMUM_ZOOM = 12f
internal const val CELL_VIEWPORT_CACHE_CAPACITY = 20
private const val CELL_LOG_TAG = "STOG.Cell"

internal enum class CellBackground {
    EMPTY,
    VISITED,
    HOT,
    ;

    companion object {
        fun fromWire(value: String?): CellBackground? = when (value) {
            "empty" -> EMPTY
            "visited" -> VISITED
            "hot" -> HOT
            else -> null
        }
    }
}

internal enum class CellBadge {
    HONEY,
    HIDDEN,
    LANDMARK,
    ;

    companion object {
        fun fromWire(value: String?): CellBadge? = when (value) {
            "honey" -> HONEY
            "hidden" -> HIDDEN
            "landmark" -> LANDMARK
            else -> null
        }
    }
}

internal data class CellCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal data class CellSummary(
    val cellId: String,
    val centroid: CellCoordinate,
    val boundary: List<CellCoordinate>,
    val background: CellBackground,
    val badges: List<CellBadge>,
    val landmarkName: String? = null,
    val landmarkImageUrl: String? = null,
    val landmarkCount: Long,
    val publicPhotoCount: Long,
    val publicPhotoLikeCount: Long,
    val topPhotoId: Long?,
    val myVisitCount: Long,
    val myPhotoCount: Long,
    val myLatestPhotoThumbnailUrl: String? = null,
)

internal data class CellDetail(
    val summary: CellSummary,
    val visibilityReasons: List<String>,
)

internal data class CellPhoto(
    val id: Long,
    val cellId: String,
    val latitude: Double,
    val longitude: Double,
    val caption: String?,
    val likeCount: Long,
    val likedByViewer: Boolean,
    val visibilityScope: String?,
    val takenAt: String? = null,
    val accuracyMeters: Double? = null,
    val placeId: Long? = null,
    val placeName: String? = null,
    val placeResolutionStatus: String? = null,
    val visibility: PhotoVisibility? = null,
    val moderationStatus: String? = null,
    val publicationStatus: String? = null,
    val thumbnailUrl: String? = null,
)

internal data class CellPage(
    val cells: List<CellSummary>,
    val nextCursor: String?,
    val ignoredMalformedCellCount: Int,
)

internal data class CellSummaryResponse(
    val cellId: String?,
    val centroid: CellCoordinateResponse?,
    val boundary: List<CellCoordinateResponse> = emptyList(),
    val background: String?,
    val badges: List<String> = emptyList(),
    val landmarkName: String? = null,
    val landmarkImageUrl: String? = null,
    val landmarkCount: Long = 0L,
    val publicPhotoCount: Long = 0L,
    val publicPhotoLikeCount: Long = 0L,
    val topPhotoId: Long? = null,
    val myVisitCount: Long = 0L,
    val myPhotoCount: Long = 0L,
    val myLatestPhotoThumbnailUrl: String? = null,
)

internal data class CellCoordinateResponse(
    val latitude: Double?,
    val longitude: Double?,
)

internal data class CellPhotoPage(
    val photos: List<CellPhoto>,
    val nextCursor: String?,
)

internal data class CellPhotoResponse(
    val id: Long?,
    val cellId: String?,
    val thumbnailUrl: String? = null,
    val caption: String?,
    val likeCount: Long,
    val likedByViewer: Boolean,
    val visibilityScope: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val takenAt: String? = null,
    val accuracyMeters: Double? = null,
    val placeId: Long? = null,
    val placeName: String? = null,
    val placeResolutionStatus: String? = null,
    val visibility: String? = null,
    val moderationStatus: String? = null,
    val publicationStatus: String? = null,
)

internal class CellAuthenticationException : IOException()

internal class CellApiClient(
    private val baseUrl: String,
) {
    fun summaries(
        viewport: CellViewport,
        accessToken: String?,
    ): CellPage {
        val accumulator = CellPageAccumulator()
        var cursor: String? = null
        do {
            cursor = accumulator.accept(summaryPage(viewport, accessToken, cursor))
        } while (cursor != null)
        return accumulator.result()
    }

    private fun summaryPage(
        viewport: CellViewport,
        accessToken: String?,
        cursor: String?,
    ): CellPage = request(
        path = buildString {
            append("/cells?swLat=${viewport.southWestLatitude}")
            append("&swLng=${viewport.southWestLongitude}")
            append("&neLat=${viewport.northEastLatitude}")
            append("&neLng=${viewport.northEastLongitude}")
            cursor?.let {
                append("&cursor=${URLEncoder.encode(it, Charsets.UTF_8.name())}")
            }
        },
        accessToken = accessToken,
    ).let { response ->
        runCatching { parseCellPage(response) }.getOrElse { error ->
            Log.w(CELL_LOG_TAG, "cell summary response parsing failed", error)
            throw error
        }
    }

    fun detail(cellId: String, accessToken: String?): CellDetail = request(
        path = "/cells/$cellId",
        accessToken = accessToken,
    ).let(::parseCellDetail) ?: throw IOException("CELL_RESPONSE_INVALID")

    fun photos(
        cellId: String,
        accessToken: String?,
        cursor: String? = null,
        mineOnly: Boolean = false,
    ): CellPhotoPage = request(
        path = buildString {
            append("/cells/$cellId/photos")
            var separator = "?"
            if (mineOnly) {
                append("${separator}scope=mine")
                separator = "&"
            }
            cursor?.let {
                append("${separator}cursor=${URLEncoder.encode(it, Charsets.UTF_8.name())}")
            }
        },
        accessToken = accessToken,
    ).let { response ->
        runCatching { parseCellPhotoPage(response) }.getOrElse { error ->
            Log.w(CELL_LOG_TAG, "cell photo response parsing failed", error)
            throw error
        }
    }

    fun allPhotos(
        cellId: String,
        accessToken: String?,
        mineOnly: Boolean = false,
    ): List<CellPhoto> {
        val photos = buildList {
            var cursor: String? = null
            val seenCursors = mutableSetOf<String>()
            do {
                val page = photos(cellId, accessToken, cursor, mineOnly)
                addAll(page.photos)
                cursor = page.nextCursor
                if (cursor != null && !seenCursors.add(cursor)) {
                    throw IOException("CELL_PHOTO_CURSOR_REPEATED")
                }
            } while (cursor != null)
        }
        return photos
    }

    private fun request(path: String, accessToken: String?): String {
        val connection = URL("${baseUrl.trimEnd('/')}$path")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = PLANNING_TIMEOUT_MILLIS
            connection.readTimeout = PLANNING_TIMEOUT_MILLIS
            accessToken?.takeIf(String::isNotBlank)?.let { token ->
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                responseCode == HttpURLConnection.HTTP_FORBIDDEN
            ) {
                throw CellAuthenticationException()
            }
            if (responseCode !in 200..299) {
                Log.w(
                    CELL_LOG_TAG,
                    "cell request failed path=$path status=$responseCode",
                )
                throw IOException(
                    runCatching { JSONObject(responseBody).optString("message") }.getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: "CELL_REQUEST_FAILED",
                )
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }
}

internal class CellPageAccumulator {
    private val cells = linkedMapOf<String, CellSummary>()
    private val seenCursors = mutableSetOf<String>()
    private var ignoredMalformedCellCount = 0

    fun accept(page: CellPage): String? {
        page.cells.forEach { cell -> cells.putIfAbsent(cell.cellId, cell) }
        ignoredMalformedCellCount += page.ignoredMalformedCellCount
        return page.nextCursor?.also { cursor ->
            if (!seenCursors.add(cursor)) {
                throw IOException("CELL_CURSOR_REPEATED")
            }
        }
    }

    fun result(): CellPage = CellPage(
        cells = cells.values.toList(),
        nextCursor = null,
        ignoredMalformedCellCount = ignoredMalformedCellCount,
    )
}

internal fun parseCellPage(responseBody: String): CellPage {
    val response = JSONObject(responseBody)
    val summaries = buildList {
        val items = response.optJSONArray("items")
        for (index in 0 until (items?.length() ?: 0)) {
            items?.optJSONObject(index)?.toCellSummaryResponse()?.let(::add)
        }
    }
    return parseCellSummaries(summaries).copy(
        nextCursor = response.optionalString("next_cursor"),
    )
}

internal fun parseCellSummaries(responses: List<CellSummaryResponse>): CellPage {
    var malformed = 0
    val cells = responses.mapNotNull { response ->
        response.toCellSummary().also { if (it == null) malformed++ }
    }.distinctBy(CellSummary::cellId)
    return CellPage(cells = cells, nextCursor = null, ignoredMalformedCellCount = malformed)
}

internal fun parseCellDetail(responseBody: String): CellDetail? {
    val response = JSONObject(responseBody)
    return parseCellDetail(
        response = response.toCellSummaryResponse(),
        visibilityReasons = response.optJSONArray("visibility_reasons").toStringList(),
    )
}

internal fun parseCellDetail(
    response: CellSummaryResponse,
    visibilityReasons: List<String>,
): CellDetail? = response.toCellSummary()?.let { CellDetail(it, visibilityReasons) }

internal fun parseCellPhotoPage(responseBody: String): CellPhotoPage {
    val response = JSONObject(responseBody)
    val items = response.optJSONArray("items")
    return CellPhotoPage(
        photos = parseCellPhotos(
            buildList {
                for (index in 0 until (items?.length() ?: 0)) {
                    items?.optJSONObject(index)?.toCellPhotoResponse()?.let(::add)
                }
            },
        ),
        nextCursor = response.optionalString("next_cursor"),
    )
}

internal fun parseCellPhotos(responses: List<CellPhotoResponse>): List<CellPhoto> =
    responses.mapNotNull(CellPhotoResponse::toCellPhoto)

private const val CELL_H3_RESOLUTION = 10
internal val cellH3 by lazy { runCatching { H3Core.newInstance() }.getOrNull() }

internal fun isStructurallyCanonicalH3CellId(value: String?): Boolean {
    if (value == null || !value.matches(Regex("[0-9a-f]{15}"))) return false
    val index = value.toLongOrNull(16) ?: return false
    val hasCanonicalHeader = (index ushr 59 and 0xFL) == 1L &&
        (index ushr 52 and 0xFL) == CELL_H3_RESOLUTION.toLong() &&
        (index ushr 45 and 0x7FL) < 122L
    return hasCanonicalHeader && (1..15).all { resolution ->
        val digit = index ushr ((15 - resolution) * 3) and 0x7L
        (resolution <= CELL_H3_RESOLUTION && digit != 0x7L) ||
            (resolution > CELL_H3_RESOLUTION && digit == 0x7L)
    }
}

internal fun isCanonicalH3CellId(value: String?): Boolean =
    isCanonicalH3CellId(value, cellH3)

internal fun isCanonicalH3CellId(value: String?, nativeH3: H3Core?): Boolean {
    if (!isStructurallyCanonicalH3CellId(value)) return false
    val h3 = nativeH3 ?: return true
    return runCatching {
        val parsed = h3.stringToH3(checkNotNull(value))
        h3.isValidCell(parsed) &&
            h3.getResolution(parsed) == CELL_H3_RESOLUTION &&
            h3.h3ToString(parsed) == value
    }.getOrDefault(false)
}

private fun JSONObject.toCellSummaryResponse(): CellSummaryResponse = CellSummaryResponse(
    cellId = optString("cell_id").takeIf(String::isNotBlank),
    centroid = optJSONObject("centroid")?.toCellCoordinateResponse(),
    boundary = optJSONArray("boundary").toCellCoordinateResponses(),
    background = optString("background").takeIf(String::isNotBlank),
    badges = optJSONArray("badges").toStringList(),
    landmarkName = optString("landmark_name").takeIf(String::isNotBlank),
        landmarkImageUrl = optString("landmark_image_url").takeIf(::isSecureImageUrl),
    landmarkCount = optNonNegativeLong("landmark_count"),
    publicPhotoCount = optNonNegativeLong("public_photo_count"),
    publicPhotoLikeCount = optNonNegativeLong("public_photo_like_count"),
    topPhotoId = optPositiveLong("top_photo_id"),
    myVisitCount = optNonNegativeLong("my_visit_count"),
    myPhotoCount = optNonNegativeLong("my_photo_count"),
    myLatestPhotoThumbnailUrl = optHttpUrl("my_latest_photo_thumbnail_url"),
)

private fun CellSummaryResponse.toCellSummary(): CellSummary? {
    val canonicalCellId = cellId?.takeIf(::isCanonicalH3CellId) ?: return null
    val cellCentroid = centroid?.toCellCoordinate() ?: return null
    val cellBoundary = boundary.mapNotNull(CellCoordinateResponse::toCellCoordinate)
    if (cellBoundary.size < 3) return null
    val cellBackground = CellBackground.fromWire(background) ?: return null
    return CellSummary(
        cellId = canonicalCellId,
        centroid = cellCentroid,
        boundary = cellBoundary,
        background = cellBackground,
        badges = badges.mapNotNull(CellBadge::fromWire).distinct().take(2),
        landmarkName = landmarkName?.takeIf(String::isNotBlank),
        landmarkImageUrl = landmarkImageUrl?.takeIf(::isSecureImageUrl),
        landmarkCount = landmarkCount.coerceAtLeast(0L),
        publicPhotoCount = publicPhotoCount.coerceAtLeast(0L),
        publicPhotoLikeCount = publicPhotoLikeCount.coerceAtLeast(0L),
        topPhotoId = topPhotoId?.takeIf { it > 0L },
        myVisitCount = myVisitCount.coerceAtLeast(0L),
        myPhotoCount = myPhotoCount.coerceAtLeast(0L),
        myLatestPhotoThumbnailUrl = myLatestPhotoThumbnailUrl,
    )
}

private fun JSONObject.toCellCoordinateResponse(): CellCoordinateResponse = CellCoordinateResponse(
    latitude = optFiniteDoubleOrNull("lat"),
    longitude = optFiniteDoubleOrNull("lng"),
)

private fun JSONArray?.toCellCoordinateResponses(): List<CellCoordinateResponse> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.toCellCoordinateResponse()?.let(::add)
        }
    }
}

private fun CellCoordinateResponse.toCellCoordinate(): CellCoordinate? {
    val validLatitude = latitude?.takeIf { it in -90.0..90.0 } ?: return null
    val validLongitude = longitude?.takeIf { it in -180.0..180.0 } ?: return null
    return CellCoordinate(validLatitude, validLongitude)
}

private fun JSONObject.toCellPhotoResponse(): CellPhotoResponse = CellPhotoResponse(
    id = optPositiveLong("id"),
    cellId = optionalString("cell_id"),
    thumbnailUrl = optHttpUrl("thumbnail_url"),
    caption = optionalString("caption"),
    likeCount = optNonNegativeLong("like_count"),
    likedByViewer = optBoolean("liked_by_viewer"),
    visibilityScope = optionalString("visibility_scope"),
    latitude = optFiniteDoubleOrNull("lat"),
    longitude = optFiniteDoubleOrNull("lng"),
    takenAt = setLogInstant(optionalString("taken_at")),
    accuracyMeters = setLogAccuracyMeters(optFiniteDoubleOrNull("accuracy_m")),
    placeId = optPositiveLong("place_id"),
    placeName = optionalString("place_name"),
    placeResolutionStatus = setLogPlaceResolutionStatus(optionalString("place_resolution_status")),
    visibility = optionalString("visibility"),
    moderationStatus = setLogModerationStatus(optionalString("moderation_status")),
    publicationStatus = setLogPublicationStatus(optionalString("publication_status")),
)

private fun CellPhotoResponse.toCellPhoto(): CellPhoto? {
    val validId = id?.takeIf { it > 0L } ?: return null
    val validCellId = cellId?.takeIf(::isCanonicalH3CellId) ?: return null
    val validLatitude = latitude?.takeIf { it in -90.0..90.0 } ?: return null
    val validLongitude = longitude?.takeIf { it in -180.0..180.0 } ?: return null
    return CellPhoto(
        id = validId,
        cellId = validCellId,
        latitude = validLatitude,
        longitude = validLongitude,
        thumbnailUrl = thumbnailUrl,
        caption = caption?.takeIf(String::isNotBlank),
        likeCount = likeCount.coerceAtLeast(0L),
        likedByViewer = likedByViewer,
        visibilityScope = visibilityScope?.takeIf(String::isNotBlank),
        takenAt = setLogInstant(takenAt),
        accuracyMeters = setLogAccuracyMeters(accuracyMeters),
        placeId = placeId?.takeIf { it > 0L },
        placeName = placeName?.takeIf(String::isNotBlank),
        placeResolutionStatus = setLogPlaceResolutionStatus(placeResolutionStatus),
        visibility = PhotoVisibility.fromWire(visibility),
        moderationStatus = setLogModerationStatus(moderationStatus),
        publicationStatus = setLogPublicationStatus(publicationStatus),
    )
}

private fun JSONObject.optionalString(key: String): String? =
    opt(key)
        ?.takeUnless { it == JSONObject.NULL }
        ?.let { it as? String }
        ?.takeIf(String::isNotBlank)

private fun JSONObject.optHttpUrl(key: String): String? =
    optionalString(key)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

private fun JSONObject.optFiniteDoubleOrNull(key: String): Double? =
    takeIf { has(key) && !isNull(key) }
        ?.opt(key)
        ?.let { it as? Number }
        ?.toDouble()
        ?.takeIf(Double::isFinite)

private fun JSONObject.optNonNegativeLong(key: String): Long =
    if (has(key) && !isNull(key)) optLong(key).coerceAtLeast(0L) else 0L

private fun JSONObject.optPositiveLong(key: String): Long? =
    takeIf { has(key) && !isNull(key) }
        ?.opt(key)
        ?.let { it as? Number }
        ?.toLong()
        ?.takeIf { it > 0L }

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
