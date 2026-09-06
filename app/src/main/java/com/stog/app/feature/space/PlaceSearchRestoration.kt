package com.stog.app.feature.space

import org.json.JSONArray
import org.json.JSONObject

internal fun encodePlaceSearchCandidates(
    candidates: List<PlaceSearchCandidate>,
): List<String> = candidates.map { candidate ->
    JSONObject()
        .put(
            "provider",
            when (candidate.provenance) {
                is PlaceSearchProvenance.Canonical -> "canonical"
                PlaceSearchProvenance.GoogleFallback -> "google"
            },
        )
        .put("external_id", candidate.externalId)
        .put("name", candidate.name)
        .putNullable("formatted_address", candidate.address)
        .putNullable("latitude", candidate.latitude)
        .putNullable("longitude", candidate.longitude)
        .put("types", JSONArray(candidate.types))
        .put("regular_opening_hours", JSONArray(candidate.regularOpeningHours))
        .putNullable("national_phone_number", candidate.nationalPhoneNumber)
        .putNullable("website_uri", candidate.websiteUri)
        .putNullable("google_maps_uri", candidate.googleMapsUri)
        .put("photo_names", JSONArray(candidate.photoNames))
        .putNullable("rating", candidate.rating)
        .putNullable("user_rating_count", candidate.userRatingCount)
        .putNullable("business_status", candidate.businessStatus)
        .putNullable("open_now", candidate.openNow)
        .putNullable("next_close_time", candidate.nextCloseTime)
        .put("provenance", candidate.provenance.toStateJson())
        .toString()
}

internal fun decodePlaceSearchCandidates(values: List<String>): List<PlaceSearchCandidate> =
    values.mapNotNull { value ->
        runCatching {
            parsePlaceSearchCandidates(
                JSONObject().put("candidates", JSONArray().put(JSONObject(value))).toString(),
            ).singleOrNull()
        }.getOrNull()
    }

private fun PlaceSearchProvenance.toStateJson(): JSONObject = when (this) {
    is PlaceSearchProvenance.Canonical -> JSONObject()
        .put("kind", "canonical")
        .put("place_id", placeId)
        .put("source_type", sourceType)
        .put("source_id", sourceId)
        .put("catalog_status", catalogStatus)

    PlaceSearchProvenance.GoogleFallback -> JSONObject()
        .put("kind", "provider")
        .put("source_type", "google")
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)
