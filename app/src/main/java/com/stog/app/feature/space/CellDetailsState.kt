package com.stog.app.feature.space

internal sealed interface CellDetailsState {
    data object Idle : CellDetailsState
    data object Loading : CellDetailsState
    data class Failed(val message: String) : CellDetailsState
    data class Loaded(
        val detail: CellDetail,
        val photos: List<CellPhoto>,
    ) : CellDetailsState
}
