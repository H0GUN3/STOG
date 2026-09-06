package com.stog.app.feature.space

import android.app.Application
import com.stog.app.feature.record.PhotoVisibility
import com.stog.app.feature.record.SetLogCaptureTimeState
import com.stog.app.feature.record.SetLogPlaceState
import com.stog.app.feature.record.SetLogReadPublicationState
import com.stog.app.feature.record.setLogReadbackMetadata
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CellApiClientTest {
    @Test
    fun parsesTypedSummaryAndCapsBadges() {
        val page = parseCellSummaries(
            listOf(
                CellSummaryResponse(
                    cellId = "8a2a1072b59ffff",
                    centroid = coordinate(),
                    boundary = boundary(),
                    background = "hot",
                    badges = listOf("landmark", "honey", "hidden"),
                    landmarkCount = 1,
                    publicPhotoCount = 2,
                    publicPhotoLikeCount = 3,
                    topPhotoId = 9,
                    myVisitCount = 1,
                    myPhotoCount = 1,
                ),
            ),
        )

        assertEquals(0, page.ignoredMalformedCellCount)
        assertNull(page.nextCursor)
        assertEquals(CellBackground.HOT, page.cells.single().background)
        assertEquals(listOf(CellBadge.LANDMARK, CellBadge.HONEY), page.cells.single().badges)
    }

    @Test
    fun preservesLandmarkImageMetadataForCellOverlay() {
        val page = parseCellSummaries(
            listOf(
                CellSummaryResponse(
                    cellId = "8a2a1072b59ffff",
                    centroid = coordinate(),
                    boundary = boundary(),
                    background = "empty",
                    badges = listOf("landmark"),
                    landmarkCount = 1,
                    landmarkName = "전주박물관",
                    landmarkImageUrl = "https://example.com/museum.jpg",
                ),
            ),
        )

        assertEquals("전주박물관", page.cells.single().landmarkName)
        assertEquals("https://example.com/museum.jpg", page.cells.single().landmarkImageUrl)
    }

    @Test
    fun rejectsNonHttpsLandmarkImageUrl() {
        val page = parseCellSummaries(
            listOf(
                CellSummaryResponse(
                    cellId = "8a2a1072b59ffff",
                    centroid = coordinate(),
                    boundary = boundary(),
                    background = "empty",
                    badges = listOf("landmark"),
                    landmarkCount = 1,
                    landmarkImageUrl = "http://example.com/museum.jpg",
                ),
            ),
        )

        assertEquals(null, page.cells.single().landmarkImageUrl)
    }

    @Test
    fun preservesViewerLatestPhotoThumbnailForCellOverlay() {
        val page = parseCellSummaries(
            listOf(
                CellSummaryResponse(
                    cellId = "8a2a1072b59ffff",
                    centroid = coordinate(),
                    boundary = boundary(),
                    background = "visited",
                    myLatestPhotoThumbnailUrl = "https://storage.test/my-latest-thumb",
                    myPhotoCount = 2,
                ),
            ),
        )

        assertEquals(
            "https://storage.test/my-latest-thumb",
            page.cells.single().myLatestPhotoThumbnailUrl,
        )
    }

    @Test
    fun ignoresMalformedAndWrongResolutionH3WithRecoverableCount() {
        val page = parseCellSummaries(
            listOf(
                CellSummaryResponse(
                    cellId = "8A2A1072B59FFFF", centroid = coordinate(), boundary = boundary(), background = "visited",
                ),
                CellSummaryResponse(
                    cellId = "892a1072b59ffff", centroid = coordinate(), boundary = boundary(), background = "visited",
                ),
                CellSummaryResponse(
                    cellId = "8a2a1072b59ffff", centroid = coordinate(), boundary = boundary(), background = "visited",
                ),
            ),
        )

        assertEquals(1, page.cells.size)
        assertEquals(2, page.ignoredMalformedCellCount)
        assertTrue(isCanonicalH3CellId(page.cells.single().cellId))
        assertFalse(isCanonicalH3CellId("8A2A1072B59FFFF"))
    }

    @Test
    fun recognizesCanonicalResolutionTenShapeWithoutNativeH3() {
        assertTrue(isStructurallyCanonicalH3CellId("8a2a1072b59ffff"))
        assertFalse(isStructurallyCanonicalH3CellId("8a0000000000000"))
        assertFalse(isStructurallyCanonicalH3CellId("892a1072b59ffff"))
        assertFalse(isStructurallyCanonicalH3CellId("8A2A1072B59FFFF"))
        assertTrue(isCanonicalH3CellId("8a2a1072b59ffff", nativeH3 = null))
        assertFalse(isCanonicalH3CellId("8a0000000000000", nativeH3 = null))
    }

    @Test
    fun rejectsMalformedDetailInsteadOfLeakingItIntoSheetState() {
        assertNull(
            parseCellDetail(
                CellSummaryResponse(
                    cellId = "not-a-cell", centroid = coordinate(), boundary = boundary(), background = "hot",
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun rejectsInvalidBaseCellAcrossSummariesDetailsAndPhotos() {
        assertRejectedAcrossCellPayloads("8a0000000000000")
    }

    @Test
    fun rejectsReservedBitCellAcrossSummariesDetailsAndPhotos() {
        assertRejectedAcrossCellPayloads("18a2a1072b59ffff")
    }

    @Test
    fun rejectsSummaryWithoutACompleteBackendBoundary() {
        val page = parseCellSummaries(
            listOf(
                CellSummaryResponse(
                    cellId = "8a2a1072b59ffff",
                    centroid = coordinate(),
                    boundary = listOf(coordinate()),
                    background = "hot",
                ),
            ),
        )

        assertTrue(page.cells.isEmpty())
        assertEquals(1, page.ignoredMalformedCellCount)
    }

    @Test
    fun parsesLocatedCellPhotoWithCanonicalReadbackMetadata() {
        val photo = parseCellPhotoPage(
            """{"items":[{"id":31,"cell_id":"8a2a1072b59ffff","lat":35.815,"lng":127.15,"thumbnail_url":"https://storage.test/cell-thumb","taken_at":"2026-08-25T00:00:00Z","caption":"same note","like_count":4,"liked_by_viewer":true,"visibility_scope":"group","accuracy_m":9.5,"place_id":91,"place_name":"immutable snapshot","place_resolution_status":"matched","visibility":"public","moderation_status":"pending","publication_status":"trip_not_public"}],"next_cursor":null}""",
        ).photos.single()

        assertEquals(35.815, photo.latitude, 0.0)
        assertEquals(127.15, photo.longitude, 0.0)
        assertEquals("https://storage.test/cell-thumb", photo.thumbnailUrl)
        assertEquals("2026-08-25T00:00:00Z", photo.takenAt)
        assertEquals(9.5, photo.accuracyMeters!!, 0.0)
        assertEquals(91L, photo.placeId)
        assertEquals("immutable snapshot", photo.placeName)
        assertEquals("matched", photo.placeResolutionStatus)
        assertEquals(PhotoVisibility.PUBLIC, photo.visibility)
        assertEquals("pending", photo.moderationStatus)
        assertEquals("trip_not_public", photo.publicationStatus)

        val metadata = setLogReadbackMetadata(
            placeId = photo.placeId,
            placeNameSnapshot = photo.placeName,
            placeResolutionStatus = photo.placeResolutionStatus,
            note = photo.caption,
            takenAt = photo.takenAt,
            visibility = photo.visibility,
            moderationStatus = photo.moderationStatus,
            publicationStatus = photo.publicationStatus,
            accuracyMeters = photo.accuracyMeters,
            zoneId = ZoneId.of("Asia/Seoul"),
            locale = Locale.US,
        )
        assertEquals(SetLogPlaceState.MATCHED, metadata.placeState)
        assertEquals(SetLogCaptureTimeState.KNOWN, metadata.captureTime.state)
        assertEquals(SetLogReadPublicationState.TRIP_NOT_PUBLIC, metadata.publicationState)
    }

    @Test
    fun parsesNullSummaryCursorAsNoNextPage() {
        val page = parseCellPage("""{"items":[],"next_cursor":null}""")

        assertNull(page.nextCursor)
    }

    @Test
    fun coordinateLessCellRecordsAreExcludedEvenWithAValidCellId() {
        val page = parseCellPhotoPage(
            """{"items":[{"id":31,"cell_id":"8a2a1072b59ffff","caption":null,"like_count":0,"liked_by_viewer":false,"visibility_scope":"private"}]}""",
        )

        assertTrue(page.photos.isEmpty())
    }

    @Test
    fun absentAndMalformedCellMetadataRemainUnknownWithoutInventedStates() {
        val page = parseCellPhotoPage(
            """{"items":[
                {"id":31,"cell_id":"8a2a1072b59ffff","lat":35.815,"lng":127.15,"caption":null,"like_count":0,"liked_by_viewer":false,"visibility_scope":null},
                {"id":32,"cell_id":"8a2a1072b59ffff","lat":35.816,"lng":127.151,"taken_at":"local-time-without-zone","caption":42,"like_count":0,"liked_by_viewer":false,"visibility_scope":"public","accuracy_m":-3,"place_id":"91","place_name":42,"place_resolution_status":"guessed","visibility":"friends","moderation_status":"visible","publication_status":"immediate_public"}
            ]}""",
        )

        assertEquals(2, page.photos.size)
        page.photos.forEach { photo ->
            assertNull(photo.takenAt)
            assertNull(photo.caption)
            assertNull(photo.accuracyMeters)
            assertNull(photo.placeId)
            assertNull(photo.placeName)
            assertNull(photo.placeResolutionStatus)
            assertNull(photo.visibility)
            assertNull(photo.moderationStatus)
            assertNull(photo.publicationStatus)
        }
        assertNull(page.photos.first().visibilityScope)
        assertEquals("public", page.photos.last().visibilityScope)
    }

    @Test
    fun combinesAllViewportPagesWithoutDuplicateCells() {
        val first = CellPage(
            cells = listOf(cellSummary("8a2a1072b59ffff")),
            nextCursor = "next",
            ignoredMalformedCellCount = 1,
        )
        val second = CellPage(
            cells = listOf(
                cellSummary("8a2a1072b59ffff"),
                cellSummary("8a2a1072b58ffff"),
            ),
            nextCursor = null,
            ignoredMalformedCellCount = 2,
        )
        val accumulator = CellPageAccumulator()

        assertEquals("next", accumulator.accept(first))
        assertNull(accumulator.accept(second))
        assertEquals(
            listOf("8a2a1072b59ffff", "8a2a1072b58ffff"),
            accumulator.result().cells.map(CellSummary::cellId),
        )
        assertEquals(3, accumulator.result().ignoredMalformedCellCount)
    }

    @Test
    fun repeatedViewportCursorFailsClosed() {
        val accumulator = CellPageAccumulator()
        accumulator.accept(CellPage(emptyList(), "repeated", 0))

        assertThrows(java.io.IOException::class.java) {
            accumulator.accept(CellPage(emptyList(), "repeated", 0))
        }
    }

    private fun assertRejectedAcrossCellPayloads(cellId: String) {
        assertFalse(isCanonicalH3CellId(cellId))
        val summaries = parseCellSummaries(
            listOf(
                CellSummaryResponse(
                    cellId = cellId,
                    centroid = coordinate(),
                    boundary = boundary(),
                    background = "hot",
                ),
            ),
        )
        assertTrue(summaries.cells.isEmpty())
        assertEquals(1, summaries.ignoredMalformedCellCount)
        assertNull(
            parseCellDetail(
                CellSummaryResponse(
                    cellId = cellId,
                    centroid = coordinate(),
                    boundary = boundary(),
                    background = "hot",
                ),
                emptyList(),
            ),
        )
        val photos = parseCellPhotos(
            listOf(
                CellPhotoResponse(
                    id = 1,
                    cellId = cellId,
                    caption = null,
                    likeCount = 0,
                    likedByViewer = false,
                    visibilityScope = "public",
                ),
            ),
        )
        assertTrue(photos.isEmpty())
    }

    private fun cellSummary(cellId: String) = CellSummary(
        cellId = cellId,
        centroid = CellCoordinate(35.815, 127.15),
        boundary = listOf(
            CellCoordinate(35.815, 127.15),
            CellCoordinate(35.816, 127.15),
            CellCoordinate(35.816, 127.151),
        ),
        background = CellBackground.EMPTY,
        badges = emptyList(),
        landmarkCount = 1,
        publicPhotoCount = 0,
        publicPhotoLikeCount = 0,
        topPhotoId = null,
        myVisitCount = 0,
        myPhotoCount = 0,
    )

    private fun coordinate() = CellCoordinateResponse(35.815, 127.15)

    private fun boundary() = listOf(
        CellCoordinateResponse(35.815, 127.15),
        CellCoordinateResponse(35.816, 127.15),
        CellCoordinateResponse(35.816, 127.151),
        CellCoordinateResponse(35.815, 127.151),
    )
}
