package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapShellStateTest {
    @Test
    fun menuSelectionChangesTheDisplayedMenu() {
        val state = MapShellState().selectMenu(MapMenu.SOCIAL)

        assertEquals(MapMenu.SOCIAL, state.selectedMenu)
        assertEquals(MapSheetKind.MENU, state.sheetKind)
    }

    @Test
    fun aiGuideUsesTheMapBottomSheetWithoutChangingSelectedMenu() {
        val state = MapShellState()
            .selectMenu(MapMenu.RECOMMENDATIONS)
            .openAiGuide()

        assertEquals(MapMenu.RECOMMENDATIONS, state.selectedMenu)
        assertEquals(MapSheetKind.AI_GUIDE, state.sheetKind)
        assertTrue(state.isAiGuideOpen)
    }

    @Test
    fun aiGuideHidesNavigationOnlyWhileImeIsVisible() {
        assertTrue(
            shouldShowMapNavigationBar(
                sheetLevel = SheetLevel.HalfExpanded,
                sheetKind = MapSheetKind.AI_GUIDE,
                imeVisible = false,
            ),
        )
        assertFalse(
            shouldShowMapNavigationBar(
                sheetLevel = SheetLevel.Expanded,
                sheetKind = MapSheetKind.AI_GUIDE,
                imeVisible = true,
            ),
        )
        assertTrue(
            shouldShowMapNavigationBar(
                sheetLevel = SheetLevel.HalfExpanded,
                sheetKind = MapSheetKind.MENU,
                imeVisible = true,
            ),
        )
    }

    @Test
    fun stobeeImeExpandsThenRestoresThePreviousSheetHeight() {
        val opened = resolveStobeeImeSheetTransition(
            aiGuideOpen = true,
            imeVisible = true,
            currentLevel = SheetLevel.HalfExpanded,
            rememberedLevel = null,
        )
        val closed = resolveStobeeImeSheetTransition(
            aiGuideOpen = true,
            imeVisible = false,
            currentLevel = SheetLevel.Expanded,
            rememberedLevel = opened.rememberedLevel,
        )

        assertEquals(
            StobeeImeSheetTransition(
                rememberedLevel = SheetLevel.HalfExpanded,
                targetLevel = SheetLevel.Expanded,
            ),
            opened,
        )
        assertEquals(
            StobeeImeSheetTransition(
                rememberedLevel = null,
                targetLevel = SheetLevel.HalfExpanded,
            ),
            closed,
        )
    }

    @Test
    fun placeDetailsCanOpenWithoutChangingTheSelectedMenu() {
        val state = MapShellState()
            .selectMenu(MapMenu.TRAVEL)
            .openPlaceDetails()

        assertEquals(MapMenu.TRAVEL, state.selectedMenu)
        assertEquals(MapSheetKind.PLACE_DETAILS, state.sheetKind)
    }

    @Test
    fun cellDetailsReuseTheMapSheetWithoutChangingNavigationSelection() {
        val state = MapShellState()
            .selectMenu(MapMenu.SOCIAL)
            .openCellDetails()

        assertEquals(MapMenu.SOCIAL, state.selectedMenu)
        assertEquals(MapSheetKind.CELL_DETAILS, state.sheetKind)
    }

    @Test
    fun searchOverlayControlsTheCellLayer() {
        assertTrue(shouldShowCellLayer(searchOverlayOpen = false))
        assertEquals(false, shouldShowCellLayer(searchOverlayOpen = true))
    }

    @Test
    fun discoveryContextKeepsTheCellLayerVisible() {
        assertTrue(shouldShowCellLayer(MapContext.DISCOVERY, searchOverlayOpen = false))
        assertTrue(shouldShowCellLayer(MapContext.RECORDS, searchOverlayOpen = false))
    }

    @Test
    fun recordsContextShowsOnlyOwnRecordsOrLandmarks() {
        assertTrue(shouldRenderCellInMap(MapContext.RECORDS, cellSummary().copy(myPhotoCount = 1)))
        assertTrue(shouldRenderCellInMap(MapContext.RECORDS, cellSummary().copy(landmarkCount = 1)))
        assertEquals(
            false,
            shouldRenderCellInMap(
                MapContext.RECORDS,
                cellSummary().copy(publicPhotoCount = 1),
            ),
        )
    }

    @Test
    fun discoveryContextShowsPublicPhotoCells() {
        assertTrue(
            shouldRenderCellInMap(
                MapContext.DISCOVERY,
                cellSummary().copy(landmarkCount = 0, publicPhotoCount = 1),
            ),
        )
    }

    @Test
    fun ownThumbnailWinsOverLandmarkImageForRecordsCell() {
        val cell = cellSummary().copy(
            myLatestPhotoThumbnailUrl = "https://storage.test/my-photo",
            landmarkImageUrl = "https://example.com/landmark",
        )

        assertEquals("https://storage.test/my-photo", cellImageUrl(MapContext.RECORDS, cell))
        assertEquals("https://example.com/landmark", cellImageUrl(MapContext.DISCOVERY, cell))
    }

    @Test
    fun recordsBackgroundIgnoresPublicPopularity() {
        val cell = cellSummary().copy(publicPhotoLikeCount = 20)

        assertEquals(CellBackground.EMPTY, cellBackgroundForMap(MapContext.RECORDS, cell))
        assertEquals(
            CellBackground.VISITED,
            cellBackgroundForMap(MapContext.RECORDS, cell.copy(myPhotoCount = 1)),
        )
    }

    @Test
    fun placeCandidateStateOpensDetailsAndReturnsHomeWhenRequested() {
        val candidate = PlaceSearchCandidate(
            externalId = "tour-home",
            name = "전주 한옥마을",
            address = "전북 전주시 완산구",
            latitude = 35.815,
            longitude = 127.15,
            photoUrls = listOf("https://images.example.test/hanok.jpg"),
            provenance = PlaceSearchProvenance.Canonical(
                placeId = 11L,
                sourceType = "TOUR_API",
                sourceId = 22L,
                catalogStatus = "public",
            ),
        )

        val state = mapScreenStateForPlace(
            candidate = candidate,
            returnToHomeOnPlaceDetailsClose = true,
        )

        assertEquals(MapSheetKind.PLACE_DETAILS, state.shellState.sheetKind)
        assertEquals(SheetLevel.HalfExpanded, state.sheetLevel)
        assertTrue(state.returnToHomeOnPlaceDetailsClose)
        assertEquals(MapContext.DISCOVERY, state.mapContext)
        val details = (state.placeDetailsState as PlaceDetailsState.Loaded).details
        assertEquals(candidate.photoUrls, details.photoUrls)
    }

    @Test
    fun publicCellTargetOpensDiscoveryCellDetails() {
        val target = MapCellTarget("8a2a1072b59ffff", 35.815, 127.15)

        val state = mapScreenStateForCell(target, returnToDiscoverOnClose = true)

        assertEquals(MapContext.DISCOVERY, state.mapContext)
        assertEquals(MapSheetKind.CELL_DETAILS, state.shellState.sheetKind)
        assertEquals(target, state.discoveryCellTarget)
        assertTrue(state.returnToDiscoverOnCellClose)
    }

    private fun cellSummary() = CellSummary(
        cellId = "8a2a1072b59ffff",
        centroid = CellCoordinate(35.815, 127.15),
        boundary = listOf(
            CellCoordinate(35.815, 127.15),
            CellCoordinate(35.816, 127.15),
            CellCoordinate(35.816, 127.151),
        ),
        background = CellBackground.EMPTY,
        badges = emptyList(),
        landmarkCount = 0,
        publicPhotoCount = 0,
        publicPhotoLikeCount = 0,
        topPhotoId = null,
        myVisitCount = 0,
        myPhotoCount = 0,
    )
}
