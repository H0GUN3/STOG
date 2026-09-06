package com.stog.app.feature.space

internal const val HALF_EXPANDED_VISIBLE_RATIO = 0.46f

internal enum class SheetLevel {
    Collapsed,
    HalfExpanded,
    Expanded,
}

internal data class MapSheetMeasurements(
    val topChromeBottomPx: Float,
    val contentBottomPx: Float,
    val expandedMarginPx: Float,
    val collapsedHeightPx: Float,
)

internal data class MapSheetAnchors(
    val expandedTopPx: Float,
    val halfExpandedTopPx: Float,
    val collapsedTopPx: Float,
) {
    val availableRangePx: Float
        get() = collapsedTopPx - expandedTopPx
}

internal fun MapSheetMeasurements.toAnchors(): MapSheetAnchors {
    require(
        topChromeBottomPx.isFinite() &&
            contentBottomPx.isFinite() &&
            expandedMarginPx.isFinite() &&
            collapsedHeightPx.isFinite() &&
            expandedMarginPx >= 0f &&
            collapsedHeightPx > 0f,
    )

    val expandedTopPx = topChromeBottomPx + expandedMarginPx
    val collapsedTopPx = contentBottomPx - collapsedHeightPx
    val availableRangePx = collapsedTopPx - expandedTopPx
    require(availableRangePx > 0f) { "Sheet anchors must not overlap." }

    return MapSheetAnchors(
        expandedTopPx = expandedTopPx,
        halfExpandedTopPx = collapsedTopPx - availableRangePx * HALF_EXPANDED_VISIBLE_RATIO,
        collapsedTopPx = collapsedTopPx,
    )
}

internal fun SheetLevel.backTarget(): SheetLevel? = when (this) {
    SheetLevel.Expanded -> SheetLevel.HalfExpanded
    SheetLevel.HalfExpanded -> SheetLevel.Collapsed
    SheetLevel.Collapsed -> null
}

internal enum class GestureOwner {
    Sheet,
    InnerContent,
}

internal fun resolveVerticalGestureOwner(
    startedOnHandle: Boolean,
    innerContentCanScroll: Boolean,
): GestureOwner = if (startedOnHandle) {
    GestureOwner.Sheet
} else {
    // Content-origin gestures stay with content even at its boundary. This prevents
    // an inner list from unexpectedly collapsing the persistent map sheet.
    GestureOwner.InnerContent
}

internal enum class BackAction {
    DismissIme,
    SettleSheet,
    CollapseSheet,
    CloseDetail,
    NavigateToParent,
    System,
}

internal data class BackContractState(
    val imeVisible: Boolean,
    val sheetInTransition: Boolean,
    val sheetLevel: SheetLevel,
    val detailOpen: Boolean,
    val hasDestinationParent: Boolean,
)

internal fun resolveBackAction(state: BackContractState): BackAction = when {
    state.imeVisible -> BackAction.DismissIme
    state.sheetInTransition -> BackAction.SettleSheet
    state.sheetLevel == SheetLevel.Expanded -> BackAction.CollapseSheet
    state.detailOpen -> BackAction.CloseDetail
    state.sheetLevel == SheetLevel.HalfExpanded -> BackAction.CollapseSheet
    state.hasDestinationParent -> BackAction.NavigateToParent
    else -> BackAction.System
}

internal fun visibleSheetHeightPx(
    viewportHeightPx: Int,
    offsetPx: Float,
): Float = (viewportHeightPx - offsetPx).coerceAtLeast(0f)
