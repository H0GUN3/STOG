package com.stog.app.feature.record

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.stog.app.feature.space.PlanningRequestException
import com.stog.app.feature.space.TripArchiveTrailPoint
import com.stog.app.feature.space.TripSummary
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoFlowTest {
    @Test
    fun derivativeGeometryAlwaysProducesTheApprovedFourByThreeDerivatives() {
        assertEquals(CropBounds(0, 0, 4000, 3000), centerCropBounds(4000, 3000, NORMALIZED_ORIGINAL_WIDTH, NORMALIZED_ORIGINAL_HEIGHT))
        assertEquals(CropBounds(0, 375, 3000, 2250), centerCropBounds(3000, 3000, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT))
        assertEquals(85, PHOTO_JPEG_QUALITY)
        assertEquals(2048, NORMALIZED_ORIGINAL_WIDTH)
        assertEquals(1536, NORMALIZED_ORIGINAL_HEIGHT)
        assertEquals(640, THUMBNAIL_WIDTH)
        assertEquals(480, THUMBNAIL_HEIGHT)
    }

    @Test
    fun derivativeStorePersistsExactSizesAndPayloadDigestsForBothObjects() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val source = File.createTempFile("task10-source", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        }
        bitmap.recycle()
        val store = PhotoDerivativeStore(context)
        val photo = store.prepare(PhotoSource.CAMERA, 9L, source, null, null)
        try {
            val original = File(photo.normalizedOriginalPath)
            val thumbnail = File(photo.thumbnailPath)
            assertEquals(original.length(), photo.normalizedOriginalBytes)
            assertEquals(thumbnail.length(), photo.thumbnailBytes)
            assertEquals(sha256(original), photo.originalSha256)
            assertEquals(sha256(thumbnail), photo.thumbnailSha256)
        } finally {
            store.delete(photo)
            source.delete()
        }
    }

    @Test
    fun thumbnailStaysWithinTheSignedUploadLimit() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val source = File.createTempFile("thumbnail-limit-source", ".jpg", context.cacheDir)
        val width = 800
        val height = 600
        var seed = 0x12345678
        val pixels = IntArray(width * height) {
            seed = seed * 1_664_525 + 1_013_904_223
            Color.rgb(seed ushr 24, seed ushr 16, seed ushr 8)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        }
        bitmap.recycle()
        val store = PhotoDerivativeStore(context)
        val photo = store.prepare(PhotoSource.CAMERA, 9L, source, null, null)

        try {
            assertTrue(photo.thumbnailBytes <= THUMBNAIL_MAX_BYTES)
        } finally {
            store.delete(photo)
        }
    }

    @Test
    fun cameraUrisUseTheApplicationFileProviderAndExplicitReadWriteGrants() {
        assertEquals("com.stog.app.fileprovider", photoFileProviderAuthority("com.stog.app"))
        val flags = cameraUriGrantFlags()
        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    @Test
    fun failedPutReturnsToRetryWithoutFinalizingMetadataAndFreshRetryStartsAtUrlRequest() {
        val photo = prepared()
        val state = PhotoUploadStateMachine()
        state.prepared(photo)
        state.begin(photo)
        state.urlsReceived(photo)
        assertEquals(UploadStage.ORIGINAL, (state.state as PhotoUploadUiState.Uploading).stage)
        state.failed(photo, UploadStage.THUMBNAIL, "expired")

        val retry = state.retry()

        assertEquals(photo, retry)
        assertEquals(UploadStage.REQUESTING_URLS, (state.state as PhotoUploadUiState.Uploading).stage)
    }

    @Test
    fun directPutOrderingSendsDerivativeBytesOnlyToSignedUrlsThenMetadataToSpring() {
        val transport = RecordingTransport()
        val client = PhotoApiClient("https://api.test", transport)
        val original = temporaryJpeg("original")
        val thumbnail = temporaryJpeg("thumb")
        try {
            val urls = fixedUrls()
            client.putDirectly(urls.originalUploadUrl, urls.originalUploadHeaders, original)
            client.putDirectly(urls.thumbnailUploadUrl, urls.thumbnailUploadHeaders, thumbnail)

            assertEquals(
                listOf(
                    "PUT https://storage.test/original",
                    "PUT https://storage.test/thumb",
                ),
                transport.calls.map { "${it.method} ${it.url}" },
            )
            assertTrue(transport.calls[0].body!!.isNotEmpty())
            assertTrue(transport.calls[1].body!!.isNotEmpty())
            assertTrue(transport.calls.all { it.url.startsWith("https://storage.test/") })
        } finally {
            original.delete()
            thumbnail.delete()
        }
    }

    @Test
    fun uploadIssueAndFinalizeBindTripUploadIdAndBothObjectPolicies() {
        val photo = prepared()
        val transport = ContractTransport()
        val client = PhotoApiClient("https://api.test", transport)

        val urls = client.issueUploadUrls("token", photo)
        val finalized = client.finalize("token", photo, urls)

        val issue = JSONObject(transport.calls[0].body!!.decodeToString())
        assertEquals(photo.tripId, issue.getLong("trip_id"))
        assertEquals(photo.id, issue.getString("client_upload_id"))
        assertEquals(photo.normalizedOriginalBytes, issue.getJSONObject("original").getLong("size_bytes"))
        assertEquals(photo.originalSha256, issue.getJSONObject("original").getString("sha256"))
        assertEquals(photo.thumbnailBytes, issue.getJSONObject("thumbnail").getLong("size_bytes"))
        assertEquals(photo.thumbnailSha256, issue.getJSONObject("thumbnail").getString("sha256"))

        val finalize = JSONObject(transport.calls[1].body!!.decodeToString())
        assertEquals(photo.id, finalize.getString("client_upload_id"))
        assertEquals(photo.originalSha256, finalize.getJSONObject("original").getString("sha256"))
        assertEquals(photo.thumbnailSha256, finalize.getJSONObject("thumbnail").getString("sha256"))
        assertEquals(7L, finalized.id)
    }

    @Test
    fun createOnlyPutReplayContinuesToServerVerification() {
        val client = PhotoApiClient(
            "https://api.test",
            object : PhotoHttpTransport {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: ByteArray?,
                ) = PhotoHttpResponse(412, "already exists")
            },
        )
        val original = temporaryJpeg("existing-original")
        try {
            client.putDirectly("https://storage.test/original", emptyMap(), original)
        } finally {
            original.delete()
        }
    }

    @Test
    fun expiredDirectPutDoesNotCreateOrRetryMetadata() {
        val transport = RecordingTransport(failOriginalPut = true)
        val client = PhotoApiClient("https://api.test", transport)
        val original = temporaryJpeg("original")
        try {
            val urls = fixedUrls()
            val failure = runCatching { client.putDirectly(urls.originalUploadUrl, urls.originalUploadHeaders, original) }
                .exceptionOrNull() as? PhotoRequestException

            assertNotNull(failure)
            assertEquals(403, failure!!.statusCode)
            assertEquals(1, transport.calls.size)
            assertTrue(transport.calls.none { it.url.startsWith("https://api.test/") })
        } finally {
            original.delete()
        }
    }

    @Test
    fun onlyBothSuccessfulPutsReachFinalizationAndCompleteOnce() {
        val photo = prepared()
        val state = PhotoUploadStateMachine()
        state.begin(photo)
        state.urlsReceived(photo)
        state.originalUploaded(photo)
        state.thumbnailUploaded(photo)

        assertEquals(UploadStage.FINALIZING, (state.state as PhotoUploadUiState.Uploading).stage)
        state.completed(
            RemotePhoto(7, 9, 3, PhotoSource.GALLERY, null, null, null, null, null, null, null, PhotoVisibility.PRIVATE, "pending", null),
        )

        assertEquals(7L, (state.state as PhotoUploadUiState.Completed).photo.id)
        assertNull(state.retry())
    }

    @Test
    fun archiveClientRetainsPhotoCoordinatesAndCaptureTimeForMapMarkers() {
        val client = PhotoApiClient(
            "https://api.test",
            object : PhotoHttpTransport {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: ByteArray?,
                ) = PhotoHttpResponse(
                    200,
                    """[{"id":11,"trip_id":9,"user_id":3,"source":"camera","cell_id":"8a2a1072b59ffff","latitude":35.815,"longitude":127.15,"taken_at":"2026-08-25T00:00:00Z","thumbnail_url":"https://storage.test/thumb","caption":null,"visibility":"private","created_at":"2026-08-25T00:01:00Z"}]""",
                )
            },
        )

        val photo = client.archive("token", 9).single()

        assertEquals(35.815, photo.latitude!!, 0.0)
        assertEquals(127.15, photo.longitude!!, 0.0)
        assertEquals("2026-08-25T00:00:00Z", photo.takenAt)
    }

    @Test
    fun archiveParserRetainsLocatedCanonicalMetadataAndNullLegacyFieldsWithoutFallbacks() {
        val client = PhotoApiClient(
            "https://api.test",
            fixedResponseTransport(
                """[
                    {"id":11,"trip_id":9,"user_id":3,"source":"camera","cell_id":"8a2a1072b59ffff","latitude":35.815,"longitude":127.15,"accuracy_m":8.5,"location_provenance":"camera_foreground","taken_at":"2026-08-25T00:00:00Z","thumbnail_url":"https://storage.test/thumb","caption":"snapshot note","place_id":91,"place_name":"snapshot place","place_resolution_status":"matched","visibility":"public","moderation_status":"pending","public_consent":true,"publication_status":"moderation_pending","created_at":"2026-08-25T00:01:00Z"},
                    {"id":12,"trip_id":9,"user_id":3,"source":"gallery","cell_id":null,"latitude":null,"longitude":null,"taken_at":null,"thumbnail_url":null,"caption":null,"created_at":"2026-08-25T00:02:00Z"},
                    {"id":13,"trip_id":9,"user_id":3,"source":"gallery","cell_id":null,"thumbnail_url":null,"caption":null}
                ]""".trimIndent(),
            ),
        )

        val photos = client.archive("token", 9)
        val located = photos[0]
        assertEquals(8.5, located.accuracyMeters!!, 0.0)
        assertEquals("camera_foreground", located.locationProvenance)
        assertEquals(91L, located.placeId)
        assertEquals("snapshot place", located.placeName)
        assertEquals("matched", located.placeResolutionStatus)
        assertEquals(PhotoVisibility.PUBLIC, located.visibility)
        assertEquals("pending", located.moderationStatus)
        assertEquals(true, located.publicConsent)
        assertEquals("moderation_pending", located.publicationStatus)

        photos.drop(1).forEach { legacy ->
            assertNull(legacy.takenAt)
            assertNull(legacy.accuracyMeters)
            assertNull(legacy.placeId)
            assertNull(legacy.placeName)
            assertNull(legacy.placeResolutionStatus)
            assertNull(legacy.visibility)
            assertNull(legacy.moderationStatus)
            assertNull(legacy.publicConsent)
            assertNull(legacy.publicationStatus)
        }
    }

    @Test
    fun detailParserPreservesDeletedPlaceSnapshotAndRejectsMalformedOptionalMetadata() {
        val deletedSnapshot = PhotoApiClient(
            "https://api.test",
            fixedResponseTransport(
                """{"id":21,"trip_id":9,"user_id":3,"source":"camera","place_id":null,"place_name":"immutable old name","place_resolution_status":"matched","taken_at":"2026-08-25T00:00:00Z","visibility":"public","moderation_status":"approved","publication_status":"revoked"}""",
            ),
        ).detail("token", 21)
        val snapshotMetadata = deletedSnapshot.readbackMetadata(ZoneId.of("Asia/Seoul"), Locale.KOREA)
        assertNull(deletedSnapshot.placeId)
        assertEquals("immutable old name", deletedSnapshot.placeName)
        assertEquals(SetLogPlaceState.MATCHED, snapshotMetadata.placeState)
        assertEquals("immutable old name", snapshotMetadata.placeNameSnapshot)
        assertEquals(SetLogReadPublicationState.REVOKED, snapshotMetadata.publicationState)

        val malformed = PhotoApiClient(
            "https://api.test",
            fixedResponseTransport(
                """{"id":22,"trip_id":9,"user_id":3,"source":"camera","latitude":"bad","longitude":181,"accuracy_m":-1,"taken_at":"no-zone","place_id":"91","place_name":42,"place_resolution_status":"guessed","visibility":"friends","moderation_status":"visible","public_consent":"yes","publication_status":"immediate_public"}""",
            ),
        ).detail("token", 22)
        assertNull(malformed.latitude)
        assertNull(malformed.longitude)
        assertNull(malformed.accuracyMeters)
        assertNull(malformed.takenAt)
        assertNull(malformed.placeId)
        assertNull(malformed.placeName)
        assertNull(malformed.placeResolutionStatus)
        assertNull(malformed.visibility)
        assertNull(malformed.moderationStatus)
        assertNull(malformed.publicConsent)
        assertNull(malformed.publicationStatus)
    }

    @Test
    fun readbackMetadataUsesOneFixedInstantFormatterAndRepresentsEveryStatusExplicitly() {
        val known = formatSetLogCaptureInstant(
            "2026-08-25T00:00:00Z",
            ZoneId.of("Asia/Seoul"),
            Locale.US,
        )
        assertEquals(SetLogCaptureTimeState.KNOWN, known.state)
        assertTrue(known.label.contains("2026.08.25 09:00"))
        assertEquals(
            SetLogCaptureTimeState.UNKNOWN,
            formatSetLogCaptureInstant(null, ZoneId.of("Asia/Seoul"), Locale.US).state,
        )

        val publicationStates = mapOf(
            "private" to SetLogReadPublicationState.PRIVATE,
            "group" to SetLogReadPublicationState.GROUP,
            "trip_not_public" to SetLogReadPublicationState.TRIP_NOT_PUBLIC,
            "moderation_pending" to SetLogReadPublicationState.MODERATION_PENDING,
            "moderation_blocked" to SetLogReadPublicationState.MODERATION_BLOCKED,
            "revoked" to SetLogReadPublicationState.REVOKED,
            "public" to SetLogReadPublicationState.PUBLIC,
            null to SetLogReadPublicationState.UNKNOWN,
        )
        publicationStates.forEach { (wire, expected) ->
            val metadata = setLogReadbackMetadata(
                placeId = null,
                placeNameSnapshot = null,
                placeResolutionStatus = "no_match",
                note = null,
                takenAt = null,
                visibility = null,
                moderationStatus = null,
                publicationStatus = wire,
                accuracyMeters = null,
                zoneId = ZoneId.of("UTC"),
                locale = Locale.US,
            )
            assertEquals(expected, metadata.publicationState)
            assertEquals(SetLogPlaceState.NO_MATCH, metadata.placeState)
            assertEquals(SetLogCaptureTimeState.UNKNOWN, metadata.captureTime.state)
        }
        assertEquals("private", PhotoVisibility.fromWire("private")?.wireValue)
        assertEquals("group", PhotoVisibility.fromWire("group")?.wireValue)
        assertEquals("public", PhotoVisibility.fromWire("public")?.wireValue)
        assertNull(PhotoVisibility.fromWire(null))
        assertNull(PhotoVisibility.fromWire("friends"))
    }

    @Test
    fun coordinateLessGalleryPhotosRemainArchiveOnlyAndViewerFilterRespectsOwnership() {
        val archive = listOf(
            ArchivePhoto(1, 9, 3, PhotoSource.GALLERY, null, null, "위치 없음", PhotoVisibility.PRIVATE, null),
            ArchivePhoto(2, 9, 4, PhotoSource.CAMERA, "8a2a1072b59ffff", null, "동행 사진", PhotoVisibility.GROUP, null),
        )

        val mine = archivePhotosForViewer(archive, viewerId = 3, myPhotosOnly = true)

        assertEquals(listOf(1L), mine.map(ArchivePhoto::id))
        assertNull(mine.single().cellId)
        assertEquals(PublicGrantState.UNKNOWN, publicGrantState(null, emptyList(), null))
        assertEquals(PublicGrantState.CONSENT_REQUIRED, publicGrantState(PhotoVisibility.PUBLIC, emptyList(), "pending"))
        assertEquals(PublicGrantState.MODERATION_PENDING, publicGrantState(PhotoVisibility.PUBLIC, listOf(PublicGrant(1, 2, null)), "pending"))
        assertEquals(PublicGrantState.APPROVED, publicGrantState(PhotoVisibility.PUBLIC, listOf(PublicGrant(1, 2, null)), "approved"))
        assertEquals(PublicGrantState.CONSENT_REQUIRED, publicGrantState(PhotoVisibility.PUBLIC, listOf(PublicGrant(1, 2, "revoked")), "pending"))
        assertFalse(galleryLocationGuidance(false).isNullOrBlank())
        assertNull(galleryLocationGuidance(true))
    }

    @Test
    fun endedArchiveSelectionAndMapProjectionAreDeterministicPerMember() {
        val trips = listOf(
            TripSummary(1, "active", "tour", "active", "private"),
            TripSummary(2, "older ended", "tour", "ended", "private"),
            TripSummary(3, "selected ended", "tour", "ended", "private"),
        )
        assertEquals(3L, selectEndedArchiveTrip(trips, 3)?.id)
        assertEquals(2L, selectEndedArchiveTrip(trips, 1)?.id)

        val trail = listOf(
            trailPoint(4, 8, "2026-08-25T00:02:00Z", 35.82, 127.17, true),
            trailPoint(2, 7, "2026-08-25T00:01:00Z", 35.81, 127.15, false),
            trailPoint(1, 7, "2026-08-25T00:00:00Z", 35.80, 127.14, false),
            trailPoint(3, 8, "2026-08-25T00:00:00Z", 35.81, 127.16, false),
        )
        val photos = listOf(
            ArchivePhoto(9, 3, 7, PhotoSource.CAMERA, "cell", null, null, PhotoVisibility.PRIVATE, null, 35.9, 127.2),
            ArchivePhoto(8, 3, 7, PhotoSource.GALLERY, null, null, null, PhotoVisibility.PRIVATE, null),
        )

        val projection = archiveMapProjection(trail, photos)

        assertEquals(listOf(7L, 8L), projection.trailSegments.map(ArchiveTrailSegment::userId))
        assertEquals(listOf(false, true), projection.trailSegments.map(ArchiveTrailSegment::interpolated))
        assertEquals(listOf(9L), projection.photoMarkers.map(ArchivePhotoMarker::photoId))
        assertEquals(4, projection.trailPoints.size)
    }

    @Test
    fun archiveListMapAndDetailUseTheSameMetadataProjection() {
        val zone = ZoneId.of("Asia/Seoul")
        val locale = Locale.US
        val archive = ArchivePhoto(
            id = 9,
            tripId = 3,
            userId = 7,
            source = PhotoSource.CAMERA,
            cellId = "8a2a1072b59ffff",
            thumbnailUrl = null,
            caption = "same note",
            visibility = PhotoVisibility.PUBLIC,
            createdAt = "2026-08-25T00:01:00Z",
            latitude = 35.9,
            longitude = 127.2,
            takenAt = "2026-08-25T00:00:00Z",
            accuracyMeters = 8.5,
            placeId = null,
            placeName = "immutable snapshot",
            placeResolutionStatus = "matched",
            moderationStatus = "pending",
            publicationStatus = "trip_not_public",
        )
        val detail = RemotePhoto(
            id = archive.id,
            tripId = archive.tripId,
            userId = archive.userId,
            source = archive.source,
            cellId = archive.cellId,
            latitude = archive.latitude,
            longitude = archive.longitude,
            takenAt = archive.takenAt,
            originalUrl = null,
            thumbnailUrl = archive.thumbnailUrl,
            caption = archive.caption,
            visibility = archive.visibility,
            moderationStatus = archive.moderationStatus,
            createdAt = archive.createdAt,
            accuracyMeters = archive.accuracyMeters,
            placeId = archive.placeId,
            placeName = archive.placeName,
            placeResolutionStatus = archive.placeResolutionStatus,
            publicationStatus = archive.publicationStatus,
        )

        val listMetadata = archive.readbackMetadata(zone, locale)
        val markerMetadata = archiveMapProjection(emptyList(), listOf(archive), zone, locale)
            .photoMarkers.single().metadata
        val detailMetadata = detail.readbackMetadata(zone, locale)

        assertEquals(listMetadata, markerMetadata)
        assertEquals(listMetadata, detailMetadata)
        assertEquals(SetLogReadPublicationState.TRIP_NOT_PUBLIC, listMetadata.publicationState)
    }

    @Test
    fun membershipForbiddenArchiveFailureDoesNotRequestGlobalAuthentication() {
        assertTrue(archiveFailureRequiresAuthentication(PhotoRequestException(401, "expired")))
        assertTrue(archiveFailureRequiresAuthentication(PlanningRequestException(401, "expired")))
        assertFalse(archiveFailureRequiresAuthentication(PhotoRequestException(403, "membership")))
        assertFalse(archiveFailureRequiresAuthentication(PlanningRequestException(403, "membership")))
    }

    @Test
    fun unknownFinalizationRetriesTheSameDeterministicPairFromUrlIssue() {
        val photo = prepared()
        val state = PhotoUploadStateMachine()
        state.finalizationUnknown(photo)

        val retry = state.retry()

        assertEquals(photo, retry)
        assertEquals(UploadStage.REQUESTING_URLS, (state.state as PhotoUploadUiState.Uploading).stage)
    }

    @Test
    fun retryKeepsFilesAndFinalizedCleanupDeletesThemOnlyAfterDurableCommitMarker() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pending = PendingPhotoStore(context)
        pending.clear()
        val source = temporaryJpeg("cleanup-source")
        val original = temporaryJpeg("cleanup-original")
        val thumbnail = temporaryJpeg("cleanup-thumb")
        val photo = prepared(original, thumbnail).copy(sourceFilePath = source.absolutePath)
        val derivatives = PhotoDerivativeStore(context)

        pending.save(photo)
        val state = PhotoUploadStateMachine().apply {
            failed(photo, UploadStage.THUMBNAIL, "retry")
        }
        assertEquals(photo, state.retry())
        assertNotNull(pending.load())
        assertTrue(source.isFile)
        assertTrue(original.isFile)
        assertTrue(thumbnail.isFile)

        pending.markFinalizationCommitted(photo)
        assertTrue(pending.load()!!.finalizationCommitted)
        assertTrue(derivatives.delete(photo))
        pending.clear()

        assertFalse(source.exists())
        assertFalse(original.exists())
        assertFalse(thumbnail.exists())
        assertNull(pending.load())
    }

    @Test
    fun restartResumesCommittedCleanupWithoutUploadingAndToleratesPartialDeletion() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pending = PendingPhotoStore(context)
        pending.clear()
        val source = temporaryJpeg("restart-source")
        val original = temporaryJpeg("restart-original")
        val thumbnail = temporaryJpeg("restart-thumb")
        val photo = prepared(original, thumbnail).copy(sourceFilePath = source.absolutePath)
        pending.markFinalizationCommitted(photo)
        original.delete()

        val restored = PendingPhotoStore(context).load()

        assertNotNull(restored)
        assertTrue(restored!!.finalizationCommitted)
        assertTrue(PhotoDerivativeStore(context).delete(restored))
        pending.clear()
        assertFalse(source.exists())
        assertFalse(thumbnail.exists())
    }

    @Test
    fun cleanupIsIsolatedToTheFinalizedUpload() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = prepared(temporaryJpeg("isolation-a-original"), temporaryJpeg("isolation-a-thumb"))
            .copy(sourceFilePath = temporaryJpeg("isolation-a-source").absolutePath)
        val second = prepared(temporaryJpeg("isolation-b-original"), temporaryJpeg("isolation-b-thumb"))
            .copy(sourceFilePath = temporaryJpeg("isolation-b-source").absolutePath)

        assertTrue(PhotoDerivativeStore(context).delete(first))

        assertTrue(File(second.sourceFilePath!!).isFile)
        assertTrue(File(second.normalizedOriginalPath).isFile)
        assertTrue(File(second.thumbnailPath).isFile)
        PhotoDerivativeStore(context).delete(second)
    }

    @Test
    fun replacingPendingCaptureDeletesOnlyTheReplacedRealFiles() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pending = PendingPhotoStore(context)
        pending.clear()
        val first = prepared(temporaryJpeg("replace-a-original"), temporaryJpeg("replace-a-thumb"))
            .copy(sourceFilePath = temporaryJpeg("replace-a-source").absolutePath)
        val second = prepared(temporaryJpeg("replace-b-original"), temporaryJpeg("replace-b-thumb"))
            .copy(
                id = "22222222-2222-2222-2222-222222222222",
                sourceFilePath = temporaryJpeg("replace-b-source").absolutePath,
            )
        val derivatives = PhotoDerivativeStore(context)

        pending.save(first)
        pending.replace(second, derivatives::delete)

        assertFalse(File(first.sourceFilePath!!).exists())
        assertFalse(File(first.normalizedOriginalPath).exists())
        assertFalse(File(first.thumbnailPath).exists())
        assertEquals(second.id, pending.load()!!.id)
        assertTrue(File(second.sourceFilePath!!).isFile)
        assertTrue(File(second.normalizedOriginalPath).isFile)
        assertTrue(File(second.thumbnailPath).isFile)
        derivatives.delete(second)
        pending.clear()
    }

    @Test
    fun corruptPendingMetadataDoesNotDeleteFilesOrBlockAReplacement() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pending = PendingPhotoStore(context)
        pending.clear()
        val metadata = File(context.filesDir, "record/pending-photo.properties")
        metadata.parentFile!!.mkdirs()
        metadata.writeText("id=corrupt\\noriginal=missing\\n")
        assertNull(pending.load())

        val replacement = prepared(
            temporaryJpeg("corrupt-replacement-original"),
            temporaryJpeg("corrupt-replacement-thumb"),
        ).copy(
            id = "33333333-3333-3333-3333-333333333333",
            sourceFilePath = temporaryJpeg("corrupt-replacement-source").absolutePath,
        )
        pending.replace(replacement, PhotoDerivativeStore(context)::delete)

        assertEquals(replacement.id, pending.load()!!.id)
        assertTrue(PhotoDerivativeStore(context).delete(replacement))
        pending.clear()
    }

    @Test
    fun partialDerivativeGenerationRemovesOwnedSourceAndGeneratedDirectory() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val source = File.createTempFile("partial-source", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        }
        bitmap.recycle()
        val root = File(context.filesDir, "record/photos")
        val before = root.listFiles()?.map(File::getName)?.toSet().orEmpty()
        var writes = 0
        val store = PhotoDerivativeStore(context) { _, output, _, _ ->
            writes++
            if (writes == 2) throw java.io.IOException("thumbnail generation failed")
            output.writeBytes(byteArrayOf(1, 2, 3))
        }

        val failure = runCatching {
            store.prepare(PhotoSource.CAMERA, 9L, source, null, null)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(source.exists())
        assertEquals(before, root.listFiles()?.map(File::getName)?.toSet().orEmpty())
    }

    @Test
    fun restoredPendingPhotoIsRecoverableAndAuthFailureKeepsItWithoutPhantomCompletion() {
        val photo = prepared()
        val state = PhotoUploadStateMachine()
        state.restored(photo)
        assertEquals(photo, (state.state as PhotoUploadUiState.Ready).photo)

        state.authenticationRequired(photo)

        assertEquals(photo, (state.state as PhotoUploadUiState.AuthenticationRequired).photo)
        assertNull(state.retry())
    }

    private fun trailPoint(
        visitId: Long,
        userId: Long,
        enteredAt: String,
        latitude: Double,
        longitude: Double,
        interpolated: Boolean,
    ) = TripArchiveTrailPoint(
        visitId = visitId,
        userId = userId,
        cellId = "8a2a1072b59ffff",
        latitude = latitude,
        longitude = longitude,
        enteredAt = enteredAt,
        leftAt = enteredAt,
        status = if (interpolated) "passed" else "visited",
        isInterpolated = interpolated,
    )

    private fun prepared(
        original: File = temporaryJpeg("original"),
        thumbnail: File = temporaryJpeg("thumb"),
    ) = PreparedPhoto(
        id = "11111111-1111-1111-1111-111111111111",
        source = PhotoSource.GALLERY,
        tripId = 9,
        normalizedOriginalPath = original.absolutePath,
        thumbnailPath = thumbnail.absolutePath,
        normalizedOriginalBytes = original.length(),
        thumbnailBytes = thumbnail.length(),
        originalSha256 = "a".repeat(64),
        thumbnailSha256 = "b".repeat(64),
        coordinates = null,
        takenAt = "2026-08-20T00:00:00Z",
        caption = null,
    )

    private fun fixedResponseTransport(responseBody: String) = object : PhotoHttpTransport {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ) = PhotoHttpResponse(200, responseBody)
    }

    private fun fixedUrls() = PhotoUploadUrls(
        originalObjectKey = "photos/3/id.jpg",
        originalUploadUrl = "https://storage.test/original",
        thumbnailObjectKey = "photos/3/id-thumb.jpg",
        thumbnailUploadUrl = "https://storage.test/thumb",
        originalUploadHeaders = mapOf(
            "Content-Type" to "image/jpeg",
            "x-goog-content-sha256" to "a".repeat(64),
        ),
        thumbnailUploadHeaders = mapOf(
            "Content-Type" to "image/jpeg",
            "x-goog-content-sha256" to "b".repeat(64),
        ),
    )

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun temporaryJpeg(name: String): File = File.createTempFile(name, ".jpg").apply {
        writeBytes(byteArrayOf(1, 2, 3, 4))
        deleteOnExit()
    }

    private class ContractTransport : PhotoHttpTransport {
        data class Call(val method: String, val url: String, val body: ByteArray?)
        val calls = mutableListOf<Call>()

        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): PhotoHttpResponse {
            calls += Call(method, url, body)
            return when {
                method == "POST" && url.endsWith("/photos/upload-url") -> PhotoHttpResponse(
                    200,
                    JSONObject()
                        .put("original_object_key", "photos/accounts/3/trips/9/members/3/uploads/11111111-1111-1111-1111-111111111111/original.jpg")
                        .put("original_upload_url", "https://storage.test/original")
                        .put("thumbnail_object_key", "photos/accounts/3/trips/9/members/3/uploads/11111111-1111-1111-1111-111111111111/thumbnail.jpg")
                        .put("thumbnail_upload_url", "https://storage.test/thumbnail")
                        .put("original_upload_headers", JSONObject())
                        .put("thumbnail_upload_headers", JSONObject())
                        .toString(),
                )
                method == "POST" && url.endsWith("/photos") -> PhotoHttpResponse(
                    200,
                    JSONObject()
                        .put("id", 7)
                        .put("trip_id", 9)
                        .put("user_id", 3)
                        .put("source", "gallery")
                        .put("visibility", "private")
                        .put("moderation_status", "pending")
                        .toString(),
                )
                else -> error("Unexpected $method $url")
            }
        }
    }

    private class RecordingTransport(
        private val failOriginalPut: Boolean = false,
    ) : PhotoHttpTransport {
        data class Call(val method: String, val url: String, val body: ByteArray?)
        val calls = mutableListOf<Call>()

        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): PhotoHttpResponse {
            calls += Call(method, url, body)
            return when {
                method == "PUT" && url.endsWith("/original") && failOriginalPut -> PhotoHttpResponse(403, "expired")
                method == "PUT" -> PhotoHttpResponse(200, "")
                else -> error("Unexpected $method $url")
            }
        }
    }
}
