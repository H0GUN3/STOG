package com.stog.app.feature.space

internal data class PlaceMapMarker(
    val externalId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val provenance: PlaceSearchProvenance = PlaceSearchProvenance.GoogleFallback,
)

internal fun PlaceSearchCandidate.hasValidCoordinates(): Boolean =
    latitude != null &&
        longitude != null &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0

internal fun placeMarkersFor(
    candidates: List<PlaceSearchCandidate>,
): List<PlaceMapMarker> = candidates.mapNotNull { candidate ->
    if (!candidate.hasValidCoordinates()) {
        null
    } else {
        PlaceMapMarker(
            externalId = candidate.externalId,
            name = candidate.name,
            latitude = candidate.latitude!!,
            longitude = candidate.longitude!!,
            provenance = candidate.provenance,
        )
    }
}
