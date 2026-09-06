package com.stog.app.ui

import com.stog.app.feature.space.SheetLevel

internal object StogUiContract {
    const val BaseSpacingDp = 8
    const val ScreenGutterDp = 24
    const val SmallRadiusDp = 10
    const val MediumRadiusDp = 16
    const val LargeRadiusDp = 24
    const val MinTouchTargetDp = 48
    const val MapNavigationHeightDp = 70
    const val MapNavigationItemCount = 5
    const val MapOverlayGapDp = 8
    const val MapSurfaceElevationDp = 12
    const val MapControlElevationDp = 6
    const val MapSurfaceBorderDp = 1
    const val SheetCollapsedHeightDp = 96
    const val HomeCameraActionSizeDp = 56
    const val SetLogShutterSizeDp = 72
    const val SetLogIconSizeDp = 24
}

internal enum class InsetOwner {
    MapTopBar,
    MapNavigation,
    SearchRoot,
}

internal enum class InsetEdge(val owners: Set<InsetOwner>) {
    MapStatusBar(setOf(InsetOwner.MapTopBar)),
    MapNavigationBar(setOf(InsetOwner.MapNavigation)),
    SearchStatusBar(setOf(InsetOwner.SearchRoot)),
    SearchIme(setOf(InsetOwner.SearchRoot));

    val owner: InsetOwner
        get() = owners.single()
}

internal enum class SemanticRole {
    Button,
    Adjustable,
    NavigationItem,
    Status,
    None,
}

internal enum class SemanticElement {
    MapSearch,
    MapNotification,
    SheetHandle,
    HomeNavigation,
    TripsNavigation,
    StobeeNavigation,
    DiscoverNavigation,
    UserNavigation,
    PreviewStatus,
}

internal data class SemanticContract(
    val accessibleName: String,
    val role: SemanticRole,
)

internal val STOG_SEMANTICS: Map<SemanticElement, SemanticContract> = mapOf(
    SemanticElement.MapSearch to SemanticContract("장소 검색", SemanticRole.Button),
    SemanticElement.MapNotification to SemanticContract("알림", SemanticRole.Button),
    SemanticElement.SheetHandle to SemanticContract("지도 정보 시트 높이 조절", SemanticRole.Adjustable),
    SemanticElement.HomeNavigation to SemanticContract("홈", SemanticRole.NavigationItem),
    SemanticElement.TripsNavigation to SemanticContract("내 여행", SemanticRole.NavigationItem),
    SemanticElement.StobeeNavigation to SemanticContract("STOBEE", SemanticRole.NavigationItem),
    SemanticElement.DiscoverNavigation to SemanticContract("발견", SemanticRole.NavigationItem),
    SemanticElement.UserNavigation to SemanticContract("사용자", SemanticRole.NavigationItem),
    SemanticElement.PreviewStatus to SemanticContract("FE 미리보기", SemanticRole.Status),
)

internal object StogTextContract {
    const val PlaceTitleMaxLines = 2
    const val PlaceTitleSoftWrap = true
    const val PlaceTitleUsesOverflowFallback = true
    const val AllowsHardCodedLineBreaks = false
}
