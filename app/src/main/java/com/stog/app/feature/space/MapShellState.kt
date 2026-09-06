package com.stog.app.feature.space

enum class MapMenu {
    HOME,
    TRAVEL,
    SOCIAL,
    RECOMMENDATIONS,
}

enum class MapContext {
    RECORDS,
    DISCOVERY,
}

data class MapCellTarget(
    val cellId: String,
    val latitude: Double,
    val longitude: Double,
)

enum class MapSheetKind {
    MENU,
    AI_GUIDE,
    PLACE_DETAILS,
    CELL_DETAILS,
}

data class MapShellState(
    val selectedMenu: MapMenu = MapMenu.HOME,
    val sheetKind: MapSheetKind = MapSheetKind.MENU,
) {
    val isAiGuideOpen: Boolean
        get() = sheetKind == MapSheetKind.AI_GUIDE

    val isPlaceDetailsOpen: Boolean
        get() = sheetKind == MapSheetKind.PLACE_DETAILS

    fun selectMenu(menu: MapMenu): MapShellState = copy(
        selectedMenu = menu,
        sheetKind = MapSheetKind.MENU,
    )

    fun openAiGuide(): MapShellState = copy(sheetKind = MapSheetKind.AI_GUIDE)

    fun openPlaceDetails(): MapShellState = copy(sheetKind = MapSheetKind.PLACE_DETAILS)

    fun openCellDetails(): MapShellState = copy(sheetKind = MapSheetKind.CELL_DETAILS)
}

internal data class MapScreenState(
    val shellState: MapShellState = MapShellState(),
    val mapContext: MapContext = MapContext.RECORDS,
    val discoveryCellTarget: MapCellTarget? = null,
    val sheetLevel: SheetLevel = SheetLevel.Collapsed,
    val placeDetailsState: PlaceDetailsState = PlaceDetailsState.Idle,
    val cellDetailsState: CellDetailsState = CellDetailsState.Idle,
    val returnToHomeOnPlaceDetailsClose: Boolean = false,
    val returnToDiscoverOnCellClose: Boolean = false,
)

internal fun mapScreenStateForPlace(
    candidate: PlaceSearchCandidate,
    returnToHomeOnPlaceDetailsClose: Boolean = false,
): MapScreenState = MapScreenState(
    shellState = MapShellState().openPlaceDetails(),
    mapContext = MapContext.DISCOVERY,
    sheetLevel = SheetLevel.HalfExpanded,
    placeDetailsState = PlaceDetailsState.Loaded(candidate.toPlaceDetails()),
    returnToHomeOnPlaceDetailsClose = returnToHomeOnPlaceDetailsClose,
)

internal fun mapScreenStateForCell(
    target: MapCellTarget,
    returnToDiscoverOnClose: Boolean = false,
): MapScreenState = MapScreenState(
    shellState = MapShellState().openCellDetails(),
    mapContext = MapContext.DISCOVERY,
    discoveryCellTarget = target,
    sheetLevel = SheetLevel.HalfExpanded,
    returnToDiscoverOnCellClose = returnToDiscoverOnClose,
)

internal fun shouldResumeBookmark(
    candidate: PlaceSearchCandidate?,
    accessToken: String?,
): Boolean = candidate != null && !accessToken.isNullOrBlank()

internal fun shouldShowCellLayer(searchOverlayOpen: Boolean): Boolean = !searchOverlayOpen

internal fun shouldShowCellLayer(
    mapContext: MapContext,
    searchOverlayOpen: Boolean,
): Boolean = !searchOverlayOpen

internal fun shouldRenderCellInMap(mapContext: MapContext, cell: CellSummary): Boolean =
    if (mapContext == MapContext.RECORDS) {
        cell.landmarkCount > 0L ||
            cell.myVisitCount > 0L ||
            cell.myPhotoCount > 0L
    } else {
        cell.landmarkCount > 0L || cell.publicPhotoCount > 0L
    }

internal fun cellBackgroundForMap(mapContext: MapContext, cell: CellSummary): CellBackground =
    if (mapContext == MapContext.RECORDS) {
        if (cell.myVisitCount > 0L || cell.myPhotoCount > 0L) {
            CellBackground.VISITED
        } else {
            CellBackground.EMPTY
        }
    } else {
        cell.background
    }

internal fun cellImageUrl(mapContext: MapContext, cell: CellSummary): String? =
    if (mapContext == MapContext.RECORDS) {
        cell.myLatestPhotoThumbnailUrl ?: cell.landmarkImageUrl
    } else {
        cell.landmarkImageUrl
    }
