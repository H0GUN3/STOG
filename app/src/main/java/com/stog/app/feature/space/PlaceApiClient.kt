package com.stog.app.feature.space

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal const val PLANNING_TIMEOUT_MILLIS = 15_000
private const val CARD_IMAGE_CACHE_BYTES = 8 * 1024 * 1024
private val CARD_IMAGE_CACHE = object : LruCache<String, Bitmap>(CARD_IMAGE_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val CARD_IMAGE_LOAD_LOCKS = ConcurrentHashMap<String, Any>()

sealed interface PlaceSearchProvenance {
    val sourceLabel: String

    data class Canonical(
        val placeId: Long,
        val sourceType: String,
        val sourceId: Long,
        val catalogStatus: String,
    ) : PlaceSearchProvenance {
        override val sourceLabel: String = "STOG 카탈로그"
    }

    data object GoogleFallback : PlaceSearchProvenance {
        override val sourceLabel: String = "Google Places 검색 결과"
    }
}

data class PlaceSearchCandidate(
    val externalId: String,
    val name: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val types: List<String> = emptyList(),
    val regularOpeningHours: List<String> = emptyList(),
    val nationalPhoneNumber: String? = null,
    val websiteUri: String? = null,
    val googleMapsUri: String? = null,
    val photoNames: List<String> = emptyList(),
    val photoUrls: List<String> = emptyList(),
    val rating: Double? = null,
    val userRatingCount: Int? = null,
    val businessStatus: String? = null,
    val openNow: Boolean? = null,
    val nextCloseTime: String? = null,
    val provenance: PlaceSearchProvenance = PlaceSearchProvenance.GoogleFallback,
)

data class PlaceDetails(
    val externalId: String,
    val name: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val types: List<String>,
    val regularOpeningHours: List<String>,
    val nationalPhoneNumber: String?,
    val websiteUri: String?,
    val googleMapsUri: String?,
    val photoNames: List<String>,
    val photoUrls: List<String> = emptyList(),
    val rating: Double? = null,
    val userRatingCount: Int? = null,
    val businessStatus: String? = null,
    val openNow: Boolean? = null,
    val nextCloseTime: String? = null,
    val provenance: PlaceSearchProvenance = PlaceSearchProvenance.GoogleFallback,
)

internal data class PlaceSearchCandidateResponse(
    val provider: String?,
    val externalId: String?,
    val name: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val types: List<String> = emptyList(),
    val regularOpeningHours: List<String> = emptyList(),
    val nationalPhoneNumber: String? = null,
    val websiteUri: String? = null,
    val googleMapsUri: String? = null,
    val photoNames: List<String> = emptyList(),
    val photoUrls: List<String> = emptyList(),
    val rating: Double? = null,
    val userRatingCount: Int? = null,
    val businessStatus: String? = null,
    val openNow: Boolean? = null,
    val nextCloseTime: String? = null,
    val provenance: PlaceSearchProvenanceResponse? = null,
)

internal data class PlaceSearchProvenanceResponse(
    val kind: String?,
    val placeId: Long?,
    val sourceType: String?,
    val sourceId: Long?,
    val catalogStatus: String?,
)

internal fun PlaceSearchCandidate.toPlaceDetails(): PlaceDetails = PlaceDetails(
    externalId = externalId,
    name = name,
    address = address,
    latitude = latitude,
    longitude = longitude,
    types = types,
    regularOpeningHours = regularOpeningHours,
    nationalPhoneNumber = nationalPhoneNumber,
    websiteUri = websiteUri,
    googleMapsUri = googleMapsUri,
    photoNames = photoNames,
    photoUrls = photoUrls,
    rating = rating,
    userRatingCount = userRatingCount,
    businessStatus = businessStatus,
    openNow = openNow,
    nextCloseTime = nextCloseTime,
    provenance = provenance,
)

internal fun PlaceDetails.toSearchCandidate(): PlaceSearchCandidate = PlaceSearchCandidate(
    externalId = externalId,
    name = name,
    address = address,
    latitude = latitude,
    longitude = longitude,
    types = types,
    regularOpeningHours = regularOpeningHours,
    nationalPhoneNumber = nationalPhoneNumber,
    websiteUri = websiteUri,
    googleMapsUri = googleMapsUri,
    photoNames = photoNames,
    photoUrls = photoUrls,
    rating = rating,
    userRatingCount = userRatingCount,
    businessStatus = businessStatus,
    openNow = openNow,
    nextCloseTime = nextCloseTime,
    provenance = provenance,
)

class PlaceApiClient(
    private val baseUrl: String,
) {
    fun search(query: String): List<PlaceSearchCandidate> = request(
        path = "/places/search",
        method = "POST",
        body = JSONObject().put("query", query).toString(),
    ).let(::parsePlaceSearchCandidates)

    fun nearby(latitude: Double, longitude: Double): List<PlaceSearchCandidate> =
        request(
            path = "/places/nearby",
            method = "POST",
            body = JSONObject()
                .put(
                    "center",
                    JSONObject()
                        .put("latitude", latitude)
                        .put("longitude", longitude),
                )
                .put("radius_meters", 5000.0)
                .put(
                    "included_types",
                    JSONArray().apply {
                        put("tourist_attraction")
                        put("museum")
                        put("park")
                        put("art_gallery")
                        put("national_park")
                        put("state_park")
                        put("historical_landmark")
                        put("zoo")
                        put("aquarium")
                        put("amusement_park")
                    },
                )
                .put("max_result_count", 8)
                .toString(),
        ).let(::parsePlaceSearchCandidates)

    fun details(externalId: String): PlaceDetails = request(
        path = "/places/${URLEncoder.encode(externalId, Charsets.UTF_8.name())}",
        method = "GET",
        body = null,
    ).let(::parsePlaceDetails)

    private fun request(
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
                }.getOrNull()?.takeIf(String::isNotBlank) ?: "PLACE_REQUEST_FAILED"
                throw IOException(code)
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }
}

internal fun placePhotoUrl(baseUrl: String, photoName: String): String =
    "${baseUrl.trimEnd('/')}/places/photo?name=${
        URLEncoder.encode(photoName, Charsets.UTF_8.name())
    }"

internal fun imageRequestUrl(url: String): String {
    if (!url.startsWith("http://", ignoreCase = true)) return url
    val localPrefix = listOf(
        "http://127.0.0.1:",
        "http://localhost:",
        "http://10.0.2.2:",
    )
    if (localPrefix.any { url.startsWith(it, ignoreCase = true) }) return url
    return "https://${url.substring("http://".length)}"
}

internal fun loadPlacePhoto(url: String): Bitmap? {
    val requestUrl = imageRequestUrl(url)
    CARD_IMAGE_CACHE.get(requestUrl)?.let { return it }
    val newLock = Any()
    val lock = CARD_IMAGE_LOAD_LOCKS.putIfAbsent(requestUrl, newLock) ?: newLock
    return try {
        synchronized(lock) {
            CARD_IMAGE_CACHE.get(requestUrl)?.let { return@synchronized it }
            val connection = URL(requestUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = PLANNING_TIMEOUT_MILLIS
                connection.readTimeout = PLANNING_TIMEOUT_MILLIS
                val bitmap = if (connection.responseCode in 200..299) {
                    connection.inputStream.use(BitmapFactory::decodeStream)
                } else {
                    null
                }
                bitmap?.let { CARD_IMAGE_CACHE.put(requestUrl, it) }
                bitmap
            } finally {
                connection.disconnect()
            }
        }
    } finally {
        CARD_IMAGE_LOAD_LOCKS.remove(requestUrl, lock)
    }
}

internal fun parsePlaceSearchCandidates(responseBody: String): List<PlaceSearchCandidate> {
    val candidates = JSONObject(responseBody).optJSONArray("candidates")
        ?: return emptyList()
    return parsePlaceSearchCandidates(
        buildList(candidates.length()) {
            for (index in 0 until candidates.length()) {
                candidates.optJSONObject(index)?.let { candidate ->
                    add(candidate.toPlaceSearchCandidateResponse())
                }
            }
        },
    )
}

internal fun parsePlaceSearchCandidates(
    responses: List<PlaceSearchCandidateResponse>,
): List<PlaceSearchCandidate> = responses.mapNotNull { response ->
    val externalId = response.externalId?.trim().orEmpty()
    val name = response.name?.trim().orEmpty()
    val provenance = response.parsePlaceSearchProvenance() ?: return@mapNotNull null
    if (externalId.isBlank() || name.isBlank()) return@mapNotNull null
    PlaceSearchCandidate(
        externalId = externalId,
        name = name,
        address = response.address?.takeIf(String::isNotBlank),
        latitude = response.latitude?.takeIf(Double::isFinite),
        longitude = response.longitude?.takeIf(Double::isFinite),
        types = response.types,
        regularOpeningHours = response.regularOpeningHours,
        nationalPhoneNumber = response.nationalPhoneNumber?.takeIf(String::isNotBlank),
        websiteUri = response.websiteUri?.takeIf(String::isNotBlank),
        googleMapsUri = response.googleMapsUri?.takeIf(String::isNotBlank),
        photoNames = response.photoNames,
        photoUrls = response.photoUrls.filter {
            it.startsWith("https://", ignoreCase = true)
        },
        rating = response.rating?.takeIf { it.isFinite() && it in 0.0..5.0 },
        userRatingCount = response.userRatingCount?.takeIf { it >= 0 },
        businessStatus = response.businessStatus?.takeIf(String::isNotBlank),
        openNow = response.openNow,
        nextCloseTime = response.nextCloseTime?.takeIf(String::isNotBlank),
        provenance = provenance,
    )
}

internal fun parsePlaceDetails(responseBody: String): PlaceDetails {
    val details = JSONObject(responseBody)
    return PlaceDetails(
        externalId = details.getString("external_id"),
        name = details.getString("name"),
        address = details.optNullableString("formatted_address"),
        latitude = details.optFiniteDoubleOrNull("latitude"),
        longitude = details.optFiniteDoubleOrNull("longitude"),
        types = details.optJSONArray("types").toStringList(),
        regularOpeningHours = details.optJSONArray("regular_opening_hours").toStringList(),
        nationalPhoneNumber = details.optNullableString("national_phone_number"),
        websiteUri = details.optNullableString("website_uri"),
        googleMapsUri = details.optNullableString("google_maps_uri"),
        photoNames = details.optJSONArray("photo_names").toStringList(),
        photoUrls = details.optJSONArray("photo_urls").toStringList().filter {
            it.startsWith("https://", ignoreCase = true)
        },
        rating = details.optFiniteDoubleOrNull("rating")
            ?.takeIf { it in 0.0..5.0 },
        userRatingCount = details.optNonNegativeIntOrNull("user_rating_count"),
        businessStatus = details.optNullableString("business_status"),
        openNow = details.optBooleanOrNull("open_now"),
        nextCloseTime = details.optNullableString("next_close_time"),
        provenance = details.parsePlaceSearchProvenance()
            ?: PlaceSearchProvenance.GoogleFallback,
    )
}

internal fun normalizedPlaceCategory(types: List<String>): String = when {
    types.any { it == "cafe" || it == "coffee_shop" } -> "cafe"
    types.any { it == "restaurant" || it == "food" } -> "restaurant"
    types.any { it == "lodging" || it == "hotel" } -> "lodging"
    types.any { it == "shopping" || it == "shopping_mall" || it == "store" } -> "shopping"
    types.any { it == "tourist_attraction" || it == "museum" } -> "attraction"
    else -> "place"
}

internal fun humanizedPlaceCategory(types: List<String>): String = when (normalizedPlaceCategory(types)) {
    "cafe" -> "카페"
    "restaurant" -> "음식점"
    "lodging" -> "숙소"
    "shopping" -> "쇼핑"
    "attraction" -> "관광지"
    else -> "장소"
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            if (!isNull(index)) {
                optString(index)
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    ?.let(::add)
            }
        }
    }
}

private fun JSONObject.toPlaceSearchCandidateResponse(): PlaceSearchCandidateResponse {
    val provenance = optJSONObject("provenance")
    return PlaceSearchCandidateResponse(
        provider = optNullableString("provider"),
        externalId = optNullableString("external_id"),
        name = optNullableString("name"),
        address = optNullableString("formatted_address"),
        latitude = optFiniteDoubleOrNull("latitude"),
        longitude = optFiniteDoubleOrNull("longitude"),
        types = optJSONArray("types").toStringList(),
        regularOpeningHours = optJSONArray("regular_opening_hours").toStringList(),
        nationalPhoneNumber = optNullableString("national_phone_number"),
        websiteUri = optNullableString("website_uri"),
        googleMapsUri = optNullableString("google_maps_uri"),
        photoNames = optJSONArray("photo_names").toStringList(),
        photoUrls = optJSONArray("photo_urls").toStringList(),
        rating = optFiniteDoubleOrNull("rating")?.takeIf { it in 0.0..5.0 },
        userRatingCount = optNonNegativeIntOrNull("user_rating_count"),
        businessStatus = optNullableString("business_status"),
        openNow = optBooleanOrNull("open_now"),
        nextCloseTime = optNullableString("next_close_time"),
        provenance = provenance?.let {
            PlaceSearchProvenanceResponse(
                kind = it.optNullableString("kind"),
                placeId = it.optPositiveLong("place_id"),
                sourceType = it.optNullableString("source_type"),
                sourceId = it.optPositiveLong("source_id"),
                catalogStatus = it.optNullableString("catalog_status"),
            )
        },
    )
}

private fun JSONObject.parsePlaceSearchProvenance(): PlaceSearchProvenance? =
    toPlaceSearchCandidateResponse().parsePlaceSearchProvenance()

private fun PlaceSearchCandidateResponse.parsePlaceSearchProvenance(): PlaceSearchProvenance? {
    val provenance = provenance ?: return null
    return when {
        provider == "canonical" && provenance.kind == "canonical" -> {
            val placeId = provenance.placeId ?: return null
            val sourceType = provenance.sourceType?.trim().orEmpty()
            val sourceId = provenance.sourceId ?: return null
            val catalogStatus = provenance.catalogStatus?.trim().orEmpty()
            if (sourceType.isBlank() || catalogStatus.isBlank()) {
                null
            } else {
                PlaceSearchProvenance.Canonical(
                    placeId = placeId,
                    sourceType = sourceType,
                    sourceId = sourceId,
                    catalogStatus = catalogStatus,
                )
            }
        }

        provider == "google" &&
            provenance.kind == "provider" &&
            provenance.sourceType == "google" -> PlaceSearchProvenance.GoogleFallback

        else -> null
    }
}

private fun JSONObject.optPositiveLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0L } else null

private fun JSONObject.optFiniteDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf(Double::isFinite) else null

private fun JSONObject.optNonNegativeIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key).takeIf { it >= 0 } else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) {
        optString(key)
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    } else {
        null
    }
