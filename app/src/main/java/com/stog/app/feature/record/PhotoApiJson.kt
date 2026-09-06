package com.stog.app.feature.record

import org.json.JSONException
import org.json.JSONObject

internal fun uploadObject(sizeBytes: Long, sha256: String): JSONObject = JSONObject()
    .put("content_type", PHOTO_CONTENT_TYPE)
    .put("size_bytes", sizeBytes)
    .put("sha256", sha256)

internal inline fun <T> decodePhotoResponse(block: () -> T): T = try {
    block()
} catch (failure: JSONException) {
    throw PhotoResponseDecodingException(failure)
} catch (failure: IllegalArgumentException) {
    throw PhotoResponseDecodingException(failure)
}

internal fun String.toUploadUrls(): PhotoUploadUrls {
    val json = JSONObject(this)
    return PhotoUploadUrls(
        originalObjectKey = json.getString("original_object_key"),
        originalUploadUrl = json.getString("original_upload_url"),
        thumbnailObjectKey = json.getString("thumbnail_object_key"),
        thumbnailUploadUrl = json.getString("thumbnail_upload_url"),
        originalUploadHeaders = json.optJSONObject("original_upload_headers").toStringMap(),
        thumbnailUploadHeaders = json.optJSONObject("thumbnail_upload_headers").toStringMap(),
    )
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { getString(it) }
}

internal fun String.toRemotePhoto(): RemotePhoto {
    val json = JSONObject(this)
    return RemotePhoto(
        id = json.getLong("id"),
        tripId = json.optionalLong("trip_id"),
        userId = json.getLong("user_id"),
        source = PhotoSource.valueOf(json.getString("source").uppercase()),
        cellId = json.optionalString("cell_id"),
        latitude = json.optionalDouble("latitude")?.takeIf { it in -90.0..90.0 },
        longitude = json.optionalDouble("longitude")?.takeIf { it in -180.0..180.0 },
        takenAt = setLogInstant(json.optionalString("taken_at")),
        originalUrl = json.optionalString("original_url"),
        thumbnailUrl = json.optionalString("thumbnail_url"),
        caption = json.optionalString("caption"),
        visibility = PhotoVisibility.fromWire(json.optionalString("visibility")),
        moderationStatus = setLogModerationStatus(json.optionalString("moderation_status")),
        createdAt = setLogInstant(json.optionalString("created_at")),
        accuracyMeters = setLogAccuracyMeters(json.optionalDouble("accuracy_m")),
        locationProvenance = setLogLocationProvenance(json.optionalString("location_provenance")),
        placeId = json.optionalLong("place_id")?.takeIf { it > 0L },
        placeName = json.optionalString("place_name"),
        placeResolutionStatus = setLogPlaceResolutionStatus(json.optionalString("place_resolution_status")),
        publicConsent = json.optionalBoolean("public_consent"),
        publicationStatus = setLogPublicationStatus(json.optionalString("publication_status")),
    )
}

internal fun JSONObject.toArchivePhoto(): ArchivePhoto = ArchivePhoto(
    id = getLong("id"),
    tripId = optionalLong("trip_id"),
    userId = getLong("user_id"),
    source = PhotoSource.valueOf(getString("source").uppercase()),
    cellId = optionalString("cell_id"),
    thumbnailUrl = optionalString("thumbnail_url"),
    caption = optionalString("caption"),
    visibility = PhotoVisibility.fromWire(optionalString("visibility")),
    createdAt = setLogInstant(optionalString("created_at")),
    latitude = optionalDouble("latitude")?.takeIf { it in -90.0..90.0 },
    longitude = optionalDouble("longitude")?.takeIf { it in -180.0..180.0 },
    takenAt = setLogInstant(optionalString("taken_at")),
    accuracyMeters = setLogAccuracyMeters(optionalDouble("accuracy_m")),
    locationProvenance = setLogLocationProvenance(optionalString("location_provenance")),
    placeId = optionalLong("place_id")?.takeIf { it > 0L },
    placeName = optionalString("place_name"),
    placeResolutionStatus = setLogPlaceResolutionStatus(optionalString("place_resolution_status")),
    moderationStatus = setLogModerationStatus(optionalString("moderation_status")),
    publicConsent = optionalBoolean("public_consent"),
    publicationStatus = setLogPublicationStatus(optionalString("publication_status")),
)

internal fun JSONObject.toPublicGrant(): PublicGrant = PublicGrant(
    photoId = getLong("photo_id"),
    version = getInt("version"),
    revokedAt = optionalString("revoked_at"),
)

internal fun JSONObject.optionalString(name: String): String? =
    takeIf { has(name) && !isNull(name) }
        ?.opt(name)
        ?.let { it as? String }
        ?.takeIf(String::isNotBlank)

internal fun JSONObject.optionalLong(name: String): Long? =
    takeIf { has(name) && !isNull(name) }
        ?.opt(name)
        ?.let { it as? Number }
        ?.toLong()

private fun JSONObject.optionalDouble(name: String): Double? =
    takeIf { has(name) && !isNull(name) }
        ?.opt(name)
        ?.let { it as? Number }
        ?.toDouble()
        ?.takeIf(Double::isFinite)

private fun JSONObject.optionalBoolean(name: String): Boolean? =
    takeIf { has(name) && !isNull(name) }
        ?.opt(name)
        ?.let { it as? Boolean }

internal fun photoErrorCode(body: String): String = runCatching {
    JSONObject(body).optJSONObject("error")?.optString("code")
        ?.takeIf(String::isNotBlank)
        ?: JSONObject(body).optString("code").takeIf(String::isNotBlank)
        ?: "PHOTO_REQUEST_FAILED"
}.getOrDefault("PHOTO_REQUEST_FAILED")
