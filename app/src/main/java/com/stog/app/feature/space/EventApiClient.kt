package com.stog.app.feature.space

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class EventSearchCandidate(
    val externalId: String,
    val title: String,
    val venueName: String?,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val startsOn: String,
    val endsOn: String,
    val detailUri: String?,
    val imageUri: String?,
    val provenance: EventSearchProvenance,
)

internal data class EventSearchCandidateResponse(
    val provider: String?,
    val externalId: String?,
    val title: String?,
    val venueName: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val startsOn: String?,
    val endsOn: String?,
    val detailUri: String?,
    val imageUri: String?,
    val provenanceKind: String?,
    val provenanceSource: String?,
)

sealed interface EventSearchProvenance {
    val sourceLabel: String

    data object TourApi : EventSearchProvenance {
        override val sourceLabel: String = "한국관광공사 행사"
    }
}

class EventApiClient(
    private val baseUrl: String,
) {
    fun nearby(latitude: Double, longitude: Double): List<EventSearchCandidate> =
        request(
            body = JSONObject()
                .put(
                    "center",
                    JSONObject()
                        .put("latitude", latitude)
                        .put("longitude", longitude),
                )
                .toString(),
        ).let(::parseNearbyEvents)

    private fun request(body: String): String {
        val connection = URL("${baseUrl.trimEnd('/')}/events/nearby")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = PLANNING_TIMEOUT_MILLIS
            connection.readTimeout = PLANNING_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(body)
            }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("EVENT_REQUEST_FAILED")
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseNearbyEvents(responseBody: String): List<EventSearchCandidate> {
    val events = JSONObject(responseBody).optJSONArray("events") ?: return emptyList()
    val responses = buildList(events.length()) {
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val provenance = event.optJSONObject("provenance") ?: continue
            add(
                EventSearchCandidateResponse(
                    provider = event.optNullableString("provider"),
                    externalId = event.optNullableString("external_id"),
                    title = event.optNullableString("title"),
                    venueName = event.optNullableString("venue_name"),
                    address = event.optNullableString("formatted_address"),
                    latitude = event.optFiniteDoubleOrNull("latitude"),
                    longitude = event.optFiniteDoubleOrNull("longitude"),
                    startsOn = event.optNullableString("starts_on"),
                    endsOn = event.optNullableString("ends_on"),
                    detailUri = event.optNullableString("detail_uri"),
                    imageUri = event.optNullableString("image_uri"),
                    provenanceKind = provenance.optNullableString("kind"),
                    provenanceSource = provenance.optNullableString("source"),
                ),
            )
        }
    }
    return parseNearbyEvents(responses)
}

internal fun parseNearbyEvents(
    responses: List<EventSearchCandidateResponse>,
): List<EventSearchCandidate> = responses.mapNotNull { response ->
    val externalId = response.externalId?.trim().orEmpty()
    val title = response.title?.trim().orEmpty()
    val startsOn = response.startsOn?.trim().orEmpty()
    val endsOn = response.endsOn?.trim().orEmpty()
    val latitude = response.latitude
    val longitude = response.longitude
    if (response.provider != "tour_api"
        || response.provenanceKind != "canonical"
        || response.provenanceSource != "tour_api"
        || externalId.isBlank()
        || title.isBlank()
        || startsOn.isBlank()
        || endsOn.isBlank()
        || latitude == null
        || longitude == null
        || !latitude.isFinite()
        || !longitude.isFinite()
    ) {
        return@mapNotNull null
    }
    EventSearchCandidate(
        externalId = externalId,
        title = title,
        venueName = response.venueName?.trim()?.takeIf(String::isNotBlank),
        address = response.address?.trim()?.takeIf(String::isNotBlank),
        latitude = latitude,
        longitude = longitude,
        startsOn = startsOn,
        endsOn = endsOn,
        detailUri = response.detailUri?.trim()?.takeIf(String::isNotBlank),
        imageUri = response.imageUri?.trim()?.takeIf(String::isNotBlank),
        provenance = EventSearchProvenance.TourApi,
    )
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) {
        optString(key)
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    } else {
        null
    }

private fun JSONObject.optFiniteDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf(Double::isFinite) else null
