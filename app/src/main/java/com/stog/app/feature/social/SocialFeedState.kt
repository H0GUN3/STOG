package com.stog.app.feature.social

import java.io.IOException

internal sealed interface SocialFeedUiState {
    data object Loading : SocialFeedUiState

    data object Empty : SocialFeedUiState

    data class Failure(
        val reason: SocialFeedFailure,
    ) : SocialFeedUiState

    data class Content(
        val items: List<SocialFeedItem>,
        val nextCursor: String?,
        val loadingNext: Boolean = false,
        val notice: SocialFeedNotice? = null,
    ) : SocialFeedUiState {
        init {
            require(items.isNotEmpty()) { "Content requires at least one feed item" }
        }
    }
}

internal enum class SocialFeedFailure {
    AUTH_EXPIRED,
    OFFLINE,
    UNKNOWN,
}

internal enum class SocialFeedNotice {
    NEXT_PAGE_FAILED,
    LIKE_FAILED,
    SAVE_FAILED,
}

internal fun initialSocialFeedState(): SocialFeedUiState = SocialFeedUiState.Loading

internal fun socialRefreshSucceeded(page: SocialFeedPage): SocialFeedUiState =
    if (page.items.isEmpty()) {
        SocialFeedUiState.Empty
    } else {
        SocialFeedUiState.Content(page.items, page.nextCursor)
    }

internal fun socialRefreshFailed(error: Throwable): SocialFeedUiState = SocialFeedUiState.Failure(
    when {
        error is SocialRequestException && error.statusCode == 401 -> SocialFeedFailure.AUTH_EXPIRED
        error is IOException -> SocialFeedFailure.OFFLINE
        else -> SocialFeedFailure.UNKNOWN
    },
)

internal fun socialNextPageStarted(
    state: SocialFeedUiState.Content,
): SocialFeedUiState.Content = state.copy(loadingNext = true, notice = null)

internal fun socialNextPageSucceeded(
    state: SocialFeedUiState.Content,
    page: SocialFeedPage,
): SocialFeedUiState.Content = state.copy(
    items = mergeSocialFeed(state.items, page),
    nextCursor = page.nextCursor,
    loadingNext = false,
    notice = null,
)

internal fun socialNextPageFailed(
    state: SocialFeedUiState.Content,
): SocialFeedUiState.Content = state.copy(
    loadingNext = false,
    notice = SocialFeedNotice.NEXT_PAGE_FAILED,
)

internal fun socialLikeSucceeded(
    state: SocialFeedUiState.Content,
    likeState: SocialLikeState,
): SocialFeedUiState.Content = state.copy(
    items = state.items.map { item ->
        if (item.photoId == likeState.photoId) applySocialLikeState(item, likeState) else item
    },
    notice = null,
)

internal fun socialLikeFailed(
    state: SocialFeedUiState.Content,
): SocialFeedUiState.Content = state.copy(notice = SocialFeedNotice.LIKE_FAILED)

internal fun socialSaveSucceeded(
    state: SocialFeedUiState.Content,
    saveState: SocialSaveState,
): SocialFeedUiState.Content = state.copy(
    items = state.items.map { item ->
        if (item.photoId == saveState.photoId) applySocialSaveState(item, saveState) else item
    },
    notice = null,
)

internal fun socialSaveFailed(
    state: SocialFeedUiState.Content,
): SocialFeedUiState.Content = state.copy(notice = SocialFeedNotice.SAVE_FAILED)
