package com.stog.app.feature.plan.travel_guide_ai

internal data class TravelGuideAiAction(
    val actionOrder: Int,
    val basketItemId: Long,
    val dayNumber: Int,
    val orderIndex: Int,
    val plannedArrival: String,
    val plannedDurationMin: Int,
    val travelMinutesFromPrevious: Int,
    val fixed: Boolean,
)

internal data class TravelGuideAiTrailLeg(
    val fromBasketItemId: Long,
    val toBasketItemId: Long,
    val travelMode: String,
    val durationMinutes: Int,
    val distanceMeters: Long?,
    val encodedPolyline: String?,
)

internal data class TravelGuideAiViolation(
    val code: String,
    val basketItemId: Long?,
)

internal data class TravelGuideAiProposal(
    val suggestionId: String,
    val tripId: Long,
    val baseVersion: Long,
    val status: String,
    val fingerprint: String,
    val feasible: Boolean,
    val actions: List<TravelGuideAiAction>,
    val fixedBasketItemIds: List<Long>,
    val excludedUnresolvedBasketItemIds: List<Long>,
    val violations: List<TravelGuideAiViolation>,
    val trailLegs: List<TravelGuideAiTrailLeg> = emptyList(),
)

internal data class TravelGuideAiApplied(
    val suggestionId: String,
    val tripId: Long,
    val itineraryVersion: Long,
    val itineraryChangeId: Long,
)

internal enum class TravelGuideAiRetry {
    PREVIEW,
    APPLY,
}

internal sealed interface TravelGuideAiUiState {
    val tripId: Long?
    val itineraryPersisted: Boolean
        get() = false

    data object Idle : TravelGuideAiUiState {
        override val tripId: Long? = null
    }

    data class Ready(
        override val tripId: Long,
        val tripTitle: String,
    ) : TravelGuideAiUiState

    data class Loading(
        override val tripId: Long,
        val tripTitle: String,
    ) : TravelGuideAiUiState

    data class Preview(
        override val tripId: Long,
        val tripTitle: String,
        val proposal: TravelGuideAiProposal,
    ) : TravelGuideAiUiState

    data class Applying(
        override val tripId: Long,
        val tripTitle: String,
        val proposal: TravelGuideAiProposal,
        val clientApplyId: String,
    ) : TravelGuideAiUiState

    data class Error(
        override val tripId: Long,
        val tripTitle: String,
        val proposal: TravelGuideAiProposal?,
        val clientApplyId: String?,
        val authExpired: Boolean,
        val message: String,
        val retry: TravelGuideAiRetry,
    ) : TravelGuideAiUiState

    data class Applied(
        override val tripId: Long,
        val tripTitle: String,
        val result: TravelGuideAiApplied,
    ) : TravelGuideAiUiState {
        override val itineraryPersisted: Boolean = true
    }
}

internal object TravelGuideAiState {
    fun selectTrip(
        state: TravelGuideAiUiState,
        tripId: Long?,
        tripTitle: String?,
    ): TravelGuideAiUiState {
        if (tripId == null || tripTitle == null) return TravelGuideAiUiState.Idle
        if (state.tripId == tripId) return state
        return TravelGuideAiUiState.Ready(tripId, tripTitle)
    }

    fun previewStarted(state: TravelGuideAiUiState): TravelGuideAiUiState = when (state) {
        TravelGuideAiUiState.Idle -> state
        is TravelGuideAiUiState.Ready -> TravelGuideAiUiState.Loading(state.tripId, state.tripTitle)
        is TravelGuideAiUiState.Error -> TravelGuideAiUiState.Loading(state.tripId, state.tripTitle)
        is TravelGuideAiUiState.Applied -> TravelGuideAiUiState.Loading(state.tripId, state.tripTitle)
        is TravelGuideAiUiState.Preview -> TravelGuideAiUiState.Loading(state.tripId, state.tripTitle)
        is TravelGuideAiUiState.Applying,
        is TravelGuideAiUiState.Loading,
        -> state
    }

    fun previewSucceeded(
        state: TravelGuideAiUiState,
        proposal: TravelGuideAiProposal,
    ): TravelGuideAiUiState {
        val title = title(state) ?: return state
        if (state.tripId != proposal.tripId) return state
        return TravelGuideAiUiState.Preview(proposal.tripId, title, proposal)
    }

    fun applyStarted(
        state: TravelGuideAiUiState,
        clientApplyId: String,
    ): TravelGuideAiUiState = when (state) {
        is TravelGuideAiUiState.Preview -> TravelGuideAiUiState.Applying(
            state.tripId,
            state.tripTitle,
            state.proposal,
            clientApplyId,
        )
        is TravelGuideAiUiState.Error -> state.proposal?.let { proposal ->
            TravelGuideAiUiState.Applying(
                state.tripId,
                state.tripTitle,
                proposal,
                clientApplyId,
            )
        } ?: state
        else -> state
    }

    fun applySucceeded(
        state: TravelGuideAiUiState,
        result: TravelGuideAiApplied,
    ): TravelGuideAiUiState {
        if (state !is TravelGuideAiUiState.Applying || state.tripId != result.tripId) return state
        return TravelGuideAiUiState.Applied(state.tripId, state.tripTitle, result)
    }

    fun failed(
        state: TravelGuideAiUiState,
        authExpired: Boolean,
        message: String,
    ): TravelGuideAiUiState {
        val tripId = state.tripId ?: return state
        val title = title(state) ?: return state
        val proposal = when (state) {
            is TravelGuideAiUiState.Applying -> state.proposal
            is TravelGuideAiUiState.Preview -> state.proposal
            is TravelGuideAiUiState.Error -> state.proposal
            else -> null
        }
        val clientApplyId = when (state) {
            is TravelGuideAiUiState.Applying -> state.clientApplyId
            is TravelGuideAiUiState.Error -> state.clientApplyId
            else -> null
        }
        val retry = if (proposal == null) TravelGuideAiRetry.PREVIEW else TravelGuideAiRetry.APPLY
        return TravelGuideAiUiState.Error(
            tripId, title, proposal, clientApplyId, authExpired, message, retry,
        )
    }

    fun conversationResponse(
        state: TravelGuideAiUiState,
        response: String,
    ): TravelGuideAiUiState = state

    private fun title(state: TravelGuideAiUiState): String? = when (state) {
        TravelGuideAiUiState.Idle -> null
        is TravelGuideAiUiState.Ready -> state.tripTitle
        is TravelGuideAiUiState.Loading -> state.tripTitle
        is TravelGuideAiUiState.Preview -> state.tripTitle
        is TravelGuideAiUiState.Applying -> state.tripTitle
        is TravelGuideAiUiState.Error -> state.tripTitle
        is TravelGuideAiUiState.Applied -> state.tripTitle
    }
}
