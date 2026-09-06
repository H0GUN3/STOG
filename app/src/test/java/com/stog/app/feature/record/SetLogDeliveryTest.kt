package com.stog.app.feature.record

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.PendingSetLogEntity
import com.stog.app.core.database.RetryClass
import com.stog.app.core.database.SetLogDeliveryQueue
import com.stog.app.core.database.SetLogEnqueueResult
import com.stog.app.core.database.SetLogOutboxState
import com.stog.app.core.database.SetLogTransmissionOutcome
import com.stog.app.core.database.SetLogUploadStage
import com.stog.app.core.database.SignedSetLogOutboxTransport
import com.stog.app.core.database.StogDatabase
import com.stog.app.core.database.TransmissionOutcome
import com.stog.app.core.database.TypedOutboxProcessor
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SetLogDeliveryTest {
    private lateinit var context: Context
    private lateinit var database: StogDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = StogDatabase.inMemory(context)
        database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", 1))
        database.accountOwnershipDao().insert(AccountOwnershipEntity("8", "8", 1))
        root = File(context.cacheDir, "set-log-${UUID.randomUUID()}").also(File::mkdirs)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun confirmedRowsAreDurableBeforeTransportAndMultipleRecordsAreFifo() {
        val later = pending("later", account = "7", trip = 9, created = 2)
        val first = pending("first", account = "7", trip = 9, created = 1)
        val other = pending("other", account = "8", trip = 9, created = 0)
        listOf(later, first, other).forEach(database.pendingSetLogDao()::insert)
        val sent = mutableListOf<String>()
        val cleaned = mutableListOf<String>()
        val processor = processor(
            transport = { payload ->
                assertNotNull(database.pendingSetLogDao().find(payload.clientUploadId))
                assertTrue(File(payload.originalPath).isFile)
                sent += payload.clientUploadId
                SetLogTransmissionOutcome.Acknowledged(
                    if (payload.clientUploadId == "first") 11 else 12,
                    "private",
                )
            },
            cleanup = { payload ->
                assertEquals(SetLogOutboxState.ACKNOWLEDGED, database.pendingSetLogDao().find(payload.clientUploadId)?.outboxState)
                cleaned += payload.clientUploadId
                listOfNotNull(payload.sourcePath, payload.originalPath, payload.thumbnailPath).forEach { File(it).delete() }
                true
            },
        )

        assertFalse(processor.processSetLogs("7", 9))

        assertEquals(listOf("first", "later"), sent)
        assertEquals(sent, cleaned)
        assertEquals(listOf("other"), database.pendingSetLogDao().allForAccount("8").map { it.clientUploadId })
        assertTrue(database.pendingSetLogDao().allForAccount("7").all { it.cleanupComplete && it.remotePhotoId != null })
    }

    @Test
    fun retryReusesImmutableIdentityAndHashesAcrossEveryUploadStage() {
        SetLogUploadStage.entries.forEachIndexed { index, failedStage ->
            val item = pending("stage-$index", trip = 20L + index, created = index.toLong())
            database.pendingSetLogDao().insert(item)
            var calls = 0
            val seen = mutableListOf<PendingSetLogEntity>()
            val processor = processor { payload ->
                seen += payload
                calls++
                if (calls == 1) SetLogTransmissionOutcome.Failed(
                    failedStage, TransmissionOutcome.NetworkFailure, "network-$index",
                ) else SetLogTransmissionOutcome.Acknowledged(100L + index, "private")
            }

            assertTrue(processor.processSetLogs("7", item.tripId))
            assertFalse(processor.processSetLogs("7", item.tripId))

            assertEquals(2, seen.size)
            assertEquals(seen[0].clientUploadId, seen[1].clientUploadId)
            assertEquals(seen[0].originalSha256, seen[1].originalSha256)
            assertEquals(seen[0].thumbnailSha256, seen[1].thumbnailSha256)
            assertEquals(SetLogOutboxState.ACKNOWLEDGED, database.pendingSetLogDao().find(item.clientUploadId)?.outboxState)
        }
    }

    @Test
    fun statusClassificationKeepsAuthAndTerminalFailuresVisibleWithoutCrossAccountSend() {
        val cases = listOf(
            401 to SetLogOutboxState.AUTH_REQUIRED,
            403 to SetLogOutboxState.TERMINAL,
            409 to SetLogOutboxState.TERMINAL,
            429 to SetLogOutboxState.RETRY,
            500 to SetLogOutboxState.RETRY,
            503 to SetLogOutboxState.RETRY,
        )
        cases.forEachIndexed { index, (status, expected) ->
            val item = pending("http-$status", trip = 40L + index, created = index.toLong())
            database.pendingSetLogDao().insert(item)
            val shouldRetry = processor {
                SetLogTransmissionOutcome.Failed(
                    SetLogUploadStage.FINALIZE,
                    TransmissionOutcome.HttpFailure(status),
                    "HTTP_$status",
                )
            }.processSetLogs("7", item.tripId)
            assertEquals(expected == SetLogOutboxState.RETRY, shouldRetry)
            val retained = database.pendingSetLogDao().find(item.clientUploadId)!!
            assertEquals(expected, retained.outboxState)
            assertEquals(status, retained.lastResponseCode)
            assertTrue(File(retained.originalPath).exists())
        }
        val accountEight = pending("account-eight", account = "8", trip = 99, created = 1)
        database.pendingSetLogDao().insert(accountEight)
        var sends = 0
        assertFalse(processor { sends++; error("wrong account") }.processSetLogs("7", 99))
        assertEquals(0, sends)
        assertEquals(SetLogOutboxState.PENDING, database.pendingSetLogDao().find("account-eight")?.outboxState)
    }

    @Test
    fun placePreviewSerializesAccuracyAndParsesExpectationStatus() {
        var requestBody: JSONObject? = null
        val client = PhotoApiClient("https://api.test", object : PhotoHttpTransport {
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): PhotoHttpResponse {
                requestBody = JSONObject(requireNotNull(body).decodeToString())
                return PhotoHttpResponse(200, """{"status":"matched","place_id":44,"place_name":"Place"}""")
            }
        })

        val result = client.previewPlace("access", PhotoCoordinates(35.8, 127.1), 7.5)

        assertEquals(PhotoPlacePreviewResult("matched", 44, "Place"), result)
        val sentBody = requireNotNull(requestBody)
        assertEquals(7.5, sentBody.getDouble("accuracy_m"), 0.0)
        assertFalse(sentBody.has("cell_id"))
    }

    @Test
    fun signedTransportUsesExistingIssuePutPutFinalizeContractAndNeverSerializesCellId() {
        val item = pending("signed", trip = 9, created = 1).copy(
            latitude = 35.8,
            longitude = 127.1,
            accuracyMeters = 7.5,
            locationProvenance = "camera_foreground",
            placeResolutionStatus = "matched",
            expectedPlaceId = 44,
            visibility = "public",
            publicConsent = true,
        )
        val transport = PipelineTransport()
        val outcome = SignedSetLogOutboxTransport(PhotoApiClient("https://api.test", transport), "access").send(item)

        assertEquals(SetLogTransmissionOutcome.Acknowledged(71, "public"), outcome)
        assertEquals(
            listOf("POST /photos/upload-url", "PUT original", "PUT thumbnail", "POST /photos"),
            transport.calls,
        )
        val finalize = transport.finalizeBody!!
        assertFalse(finalize.has("cell_id"))
        assertEquals(7.5, finalize.getDouble("accuracy_m"), 0.0)
        assertEquals("camera_foreground", finalize.getString("location_provenance"))
        assertEquals("matched", finalize.getString("place_resolution_status"))
        assertEquals(44, finalize.getLong("expected_place_id"))
        assertEquals("public", finalize.getString("visibility"))
        assertTrue(finalize.getBoolean("public_consent"))
    }

    @Test
    fun malformedSuccessfulIssueOrFinalizeResponseRetriesAndRetainsDurableFiles() {
        listOf(SetLogUploadStage.ISSUE_URLS, SetLogUploadStage.FINALIZE).forEachIndexed { index, malformedStage ->
            val item = pending("malformed-$index", trip = 60L + index, created = index.toLong())
            database.pendingSetLogDao().insert(item)
            val signed = SignedSetLogOutboxTransport(
                PhotoApiClient("https://api.test", MalformedPipelineTransport(malformedStage)),
                "access",
            )

            assertTrue(processor(transport = signed::send).processSetLogs("7", item.tripId))

            val retained = database.pendingSetLogDao().find(item.clientUploadId)!!
            assertEquals(SetLogOutboxState.RETRY, retained.outboxState)
            assertEquals(RetryClass.SERVER, retained.retryClass)
            assertEquals(malformedStage, retained.uploadStage)
            assertEquals(
                if (malformedStage == SetLogUploadStage.ISSUE_URLS) {
                    "PHOTO_INVALID_UPLOAD_URL_RESPONSE"
                } else {
                    "PHOTO_INVALID_FINALIZE_RESPONSE"
                },
                retained.errorCode,
            )
            assertFalse(retained.cleanupComplete)
            assertTrue(File(retained.originalPath).isFile)
            assertTrue(File(retained.thumbnailPath).isFile)
        }
    }

    @Test
    fun schedulingFailureAfterDurableEnqueueKeepsRowPendingAndProtectsEveryReferencedFile() {
        val payload = pending("schedule-failure", trip = 68, created = 1)
        val queue = SetLogDeliveryQueue(
            context = context,
            database = database,
            scheduleSetLogs = { _, _ ->
                assertNotNull(database.pendingSetLogDao().find(payload.clientUploadId))
                throw IllegalStateException("injected scheduler failure")
            },
        )

        val result = queue.enqueue(payload)

        assertTrue(result is SetLogEnqueueResult.SchedulingDeferred)
        assertEquals(payload, database.pendingSetLogDao().find(payload.clientUploadId))
        assertEquals(listOf(com.stog.app.core.database.PendingSetLogScope("7", 68)), database.pendingSetLogDao().recoverableScopes())

        val token = SetLogCaptureToken(1)
        val saving = savingState(payload, token)
        val persisted = reduceSetLogCapture(saving, setLogPersistenceEvent(token, result)).state
        assertEquals(SetLogCaptureStage.PENDING_SYNC, persisted.stage)
        assertTrue(persisted.hasDurablePendingRecord)
        assertEquals("SET_LOG_SCHEDULE_FAILED", persisted.errorCode)

        val prepared = prepared(payload)
        val pendingRetake = reduceSetLogCapture(persisted, SetLogCaptureEvent.RetakePressed)
        val pendingClose = reduceSetLogCapture(persisted, SetLogCaptureEvent.ClosePressed)
        assertNull(preparedPhotoForCleanup(persisted, pendingRetake, prepared))
        assertNull(preparedPhotoForCleanup(persisted, pendingClose, prepared))

        val terminal = reduceSetLogCapture(
            persisted,
            SetLogCaptureEvent.DeliveryFailed(token, "PHOTO_REJECTED", retryable = false),
        ).state
        assertEquals(SetLogCaptureStage.FAILED, terminal.stage)
        assertTrue(terminal.hasDurablePendingRecord)
        assertNull(preparedPhotoForCleanup(terminal, reduceSetLogCapture(terminal, SetLogCaptureEvent.RetakePressed), prepared))
        assertNull(preparedPhotoForCleanup(terminal, reduceSetLogCapture(terminal, SetLogCaptureEvent.ClosePressed), prepared))

        listOfNotNull(payload.sourcePath, payload.originalPath, payload.thumbnailPath).forEach {
            assertTrue(File(it).isFile)
        }
        assertNotNull(database.pendingSetLogDao().find(payload.clientUploadId))
    }

    @Test
    fun enqueueFailureBeforeCommitRemainsTerminalAndAllowsReducerAcceptedCleanup() {
        database.accountOwnershipDao().insert(AccountOwnershipEntity("70", "other-user", 1))
        val payload = pending("pre-commit", account = "70", trip = 69, created = 1)
        var schedules = 0
        val queue = SetLogDeliveryQueue(
            context = context,
            database = database,
            scheduleSetLogs = { _, _ -> schedules++; UUID.randomUUID() },
        )

        assertTrue(runCatching { queue.enqueue(payload) }.isFailure)
        assertEquals(0, schedules)
        assertNull(database.pendingSetLogDao().find(payload.clientUploadId))

        val token = SetLogCaptureToken(1)
        val saving = savingState(payload, token)
        val failed = reduceSetLogCapture(
            saving,
            SetLogCaptureEvent.DeliveryFailed(token, "SET_LOG_PERSIST_FAILED", retryable = false),
        ).state
        assertEquals(SetLogCaptureStage.FAILED, failed.stage)
        assertFalse(failed.hasDurablePendingRecord)
        val prepared = prepared(payload)
        val retake = reduceSetLogCapture(failed, SetLogCaptureEvent.RetakePressed)
        assertEquals(prepared, preparedPhotoForCleanup(failed, retake, prepared))
    }

    @Test
    fun unrelatedPersistedSourceErrorsAreNotClassifiedAsInvalidResponses() {
        val invalid = pending("invalid-source", trip = 70, created = 1).copy(source = "unsupported")
        val signed = SignedSetLogOutboxTransport(
            PhotoApiClient("https://api.test", object : PhotoHttpTransport {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: ByteArray?,
                ): PhotoHttpResponse = error("invalid persisted source must fail before HTTP")
            }),
            "access",
        )

        assertTrue(runCatching { signed.send(invalid) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun cleanupRunsOnlyAfterDurableAcknowledgementAndRecoveryFinishesPartialCleanup() {
        val item = pending("cleanup", trip = 77, created = 1)
        database.pendingSetLogDao().insert(item)
        val failure = processor {
            SetLogTransmissionOutcome.Failed(
                SetLogUploadStage.THUMBNAIL_PUT, TransmissionOutcome.NetworkFailure, "offline",
            )
        }
        assertTrue(failure.processSetLogs("7", 77))
        assertTrue(File(item.originalPath).exists())
        assertFalse(database.pendingSetLogDao().find("cleanup")!!.cleanupComplete)

        database.pendingSetLogDao().acknowledge("cleanup", 91, "private", 10)
        File(item.originalPath).delete()
        val recovered = processor(
            cleanup = { payload ->
                listOfNotNull(payload.sourcePath, payload.originalPath, payload.thumbnailPath).forEach { File(it).delete() }
                true
            },
            transport = { error("acknowledged rows must not upload") },
        )
        assertFalse(recovered.processSetLogs("7", 77))
        assertTrue(database.pendingSetLogDao().find("cleanup")!!.cleanupComplete)
        assertFalse(File(item.thumbnailPath).exists())
    }

    private fun processor(
        cleanup: (PendingSetLogEntity) -> Boolean = { payload ->
            listOfNotNull(payload.sourcePath, payload.originalPath, payload.thumbnailPath).forEach { File(it).delete() }
            true
        },
        transport: (PendingSetLogEntity) -> SetLogTransmissionOutcome,
    ) = TypedOutboxProcessor(
        database = database,
        visitTransport = { error("not used") },
        shareImportTransport = { _, _ -> error("not used") },
        setLogTransport = transport,
        cleanup,
        now = { 1234 },
    )

    private fun pending(
        id: String,
        account: String = "7",
        trip: Long,
        created: Long,
    ): PendingSetLogEntity {
        val directory = File(root, id).also(File::mkdirs)
        val source = File(directory, "source.jpg").also { it.writeText("source-$id") }
        val original = File(directory, "original.jpg").also { it.writeText("original-$id") }
        val thumbnail = File(directory, "thumbnail.jpg").also { it.writeText("thumbnail-$id") }
        return PendingSetLogEntity(
            clientUploadId = id,
            accountId = account,
            userId = account,
            tripId = trip,
            source = "camera",
            originalPath = original.absolutePath,
            thumbnailPath = thumbnail.absolutePath,
            sourcePath = source.absolutePath,
            originalSize = original.length(),
            thumbnailSize = thumbnail.length(),
            originalSha256 = id.padEnd(64, 'a').take(64),
            thumbnailSha256 = id.padEnd(64, 'b').take(64),
            latitude = null,
            longitude = null,
            accuracyMeters = null,
            locationProvenance = null,
            takenAt = "2026-08-25T00:00:00Z",
            caption = "note-$id",
            placeResolutionStatus = "no_match",
            expectedPlaceId = null,
            visibility = "private",
            publicConsent = false,
            createdAt = created,
        )
    }

    private fun savingState(payload: PendingSetLogEntity, token: SetLogCaptureToken) = SetLogCaptureState(
        accountId = payload.accountId,
        userId = payload.userId.toLong(),
        tripId = payload.tripId,
        stage = SetLogCaptureStage.SAVING,
        activeToken = token,
        draft = SetLogDraft(
            SetLogSnapshot(
                accountId = payload.accountId,
                userId = payload.userId.toLong(),
                tripId = payload.tripId,
                source = PhotoSource.GALLERY,
                captureToken = token,
                takenAt = null,
                takenAtProvenance = SetLogTakenAtProvenance.MISSING,
                coordinates = null,
                accuracyMeters = null,
                locationProvenance = SetLogLocationProvenance.MISSING,
                provisionalCellId = null,
                localAsset = SetLogLocalAsset("asset", "output", requireNotNull(payload.sourcePath)),
            ),
        ),
    )

    private fun prepared(payload: PendingSetLogEntity) = PreparedPhoto(
        id = payload.clientUploadId,
        source = PhotoSource.GALLERY,
        tripId = payload.tripId,
        normalizedOriginalPath = payload.originalPath,
        thumbnailPath = payload.thumbnailPath,
        normalizedOriginalBytes = payload.originalSize,
        thumbnailBytes = payload.thumbnailSize,
        originalSha256 = payload.originalSha256,
        thumbnailSha256 = payload.thumbnailSha256,
        coordinates = null,
        takenAt = null,
        caption = null,
        sourceFilePath = payload.sourcePath,
    )

    private class PipelineTransport : PhotoHttpTransport {
        val calls = mutableListOf<String>()
        var finalizeBody: JSONObject? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): PhotoHttpResponse {
            if (method == "POST" && url.endsWith("/photos/upload-url")) {
                calls += "POST /photos/upload-url"
                return PhotoHttpResponse(200, """{
                    "original_object_key":"o","original_upload_url":"https://storage/original",
                    "thumbnail_object_key":"t","thumbnail_upload_url":"https://storage/thumbnail",
                    "original_upload_headers":{},"thumbnail_upload_headers":{}
                }""".trimIndent())
            }
            if (method == "PUT" && url.endsWith("/original")) {
                calls += "PUT original"
                return PhotoHttpResponse(412, "exists")
            }
            if (method == "PUT" && url.endsWith("/thumbnail")) {
                calls += "PUT thumbnail"
                return PhotoHttpResponse(200, "")
            }
            if (method == "POST" && url.endsWith("/photos")) {
                calls += "POST /photos"
                finalizeBody = JSONObject(requireNotNull(body).decodeToString())
                return PhotoHttpResponse(200, """{
                    "id":71,"trip_id":9,"user_id":7,"source":"camera","cell_id":"server-cell",
                    "latitude":35.8,"longitude":127.1,"accuracy_m":7.5,"location_provenance":"camera_foreground",
                    "taken_at":"2026-08-25T00:00:00Z","original_url":null,"thumbnail_url":null,"caption":"note-signed",
                    "place_id":44,"place_name":"Place","place_resolution_status":"matched","visibility":"public",
                    "moderation_status":"pending","public_consent":true,"publication_status":"public",
                    "created_at":"2026-08-25T00:00:01Z"
                }""".trimIndent())
            }
            throw IOException("unexpected $method $url")
        }
    }

    private class MalformedPipelineTransport(
        private val malformedStage: SetLogUploadStage,
    ) : PhotoHttpTransport {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): PhotoHttpResponse = when (method) {
            "PUT" -> PhotoHttpResponse(200, "")
            "POST" -> when {
                url.endsWith("/photos/upload-url") -> PhotoHttpResponse(
                    200,
                    if (malformedStage == SetLogUploadStage.ISSUE_URLS) "{}" else """{
                        "original_object_key":"o","original_upload_url":"https://storage/original",
                        "thumbnail_object_key":"t","thumbnail_upload_url":"https://storage/thumbnail",
                        "original_upload_headers":{},"thumbnail_upload_headers":{}
                    }""".trimIndent(),
                )
                url.endsWith("/photos") -> PhotoHttpResponse(200, "{}")
                else -> throw IOException("unexpected $method $url")
            }
            else -> throw IOException("unexpected $method $url")
        }
    }
}
