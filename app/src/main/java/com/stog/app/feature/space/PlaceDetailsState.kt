package com.stog.app.feature.space

internal sealed interface PlaceDetailsState {
    data object Idle : PlaceDetailsState

    data object Loading : PlaceDetailsState

    data class Loaded(
        val details: PlaceDetails,
        val distanceMeters: Float? = null,
    ) : PlaceDetailsState

    data class Failed(
        val message: String,
    ) : PlaceDetailsState
}
