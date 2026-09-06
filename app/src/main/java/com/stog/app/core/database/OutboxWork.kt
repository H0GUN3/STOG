package com.stog.app.core.database

import android.content.Context
import com.stog.app.BuildConfig
import com.stog.app.R
import com.stog.app.feature.auth.AuthTokenStore
import com.stog.app.feature.auth.StoredAuthTokens
import com.stog.app.feature.record.CollectorState
import com.stog.app.feature.record.PermissionState
import com.stog.app.feature.record.RecordingApiClient
import com.stog.app.feature.record.RecordingHttpException
import com.stog.app.feature.record.ConfirmedSetLogUpload
import com.stog.app.feature.record.PhotoApiClient
import com.stog.app.feature.record.PhotoCoordinates
import com.stog.app.feature.record.PhotoRequestException
import com.stog.app.feature.record.PhotoResponseDecodingException
import com.stog.app.feature.record.PhotoSource
import com.stog.app.feature.record.PreparedPhoto
import com.stog.app.feature.record.SetLogLocationProvenance
import com.stog.app.feature.record.SetLogVisibilityIntent
import com.stog.app.feature.plan.share_import.ConfirmedShareHttpTransport
import com.stog.app.feature.plan.share_import.ShareImportFileStore
import com.stog.app.feature.plan.share_import.ShareImportSyncRunner
import com.stog.app.feature.space.PlanningApiClient
import java.io.File
import java.io.IOException
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.UUID

data class VisitReviewReceipt(
    val remoteVisitId: Long,
    val clientVisitId: String,
    val reviewRequired: Boolean,
)

sealed interface TransmissionOutcome {
    data object Acknowledged : TransmissionOutcome
    data class VisitAcknowledged(val receipt: VisitReviewReceipt) : TransmissionOutcome
    data object InvalidResponse : TransmissionOutcome
    data object NetworkFailure : TransmissionOutcome
    data class HttpFailure(val statusCode: Int) : TransmissionOutcome
}

data class ClassifiedOutcome(
    val state: OutboxState,
    val retryClass: RetryClass,
    val responseCode: Int?,
) {
    val shouldRetry: Boolean get() = state == OutboxState.RETRY
}

object OutboxRetryPolicy {
    fun classify(outcome: TransmissionOutcome): ClassifiedOutcome = when (outcome) {
        TransmissionOutcome.Acknowledged,
        is TransmissionOutcome.VisitAcknowledged,
        -> ClassifiedOutcome(OutboxState.ACKNOWLEDGED, RetryClass.NONE, null)
        TransmissionOutcome.InvalidResponse -> ClassifiedOutcome(OutboxState.RETRY, RetryClass.SERVER, null)
        TransmissionOutcome.NetworkFailure -> ClassifiedOutcome(OutboxState.RETRY, RetryClass.NETWORK, null)
        is TransmissionOutcome.HttpFailure -> when (outcome.statusCode) {
            429 -> ClassifiedOutcome(OutboxState.RETRY, RetryClass.RATE_LIMIT, 429)
            in 500..599 -> ClassifiedOutcome(OutboxState.RETRY, RetryClass.SERVER, outcome.statusCode)
            in 400..499 -> ClassifiedOutcome(OutboxState.TERMINAL, RetryClass.TERMINAL_CLIENT, outcome.statusCode)
            else -> ClassifiedOutcome(OutboxState.RETRY, RetryClass.NETWORK, outcome.statusCode)
        }
    }
}

fun interface VisitOutboxTransport {
    fun send(payload: VisitOutboxEntity): TransmissionOutcome
}

fun interface ShareImportOutboxTransport {
    fun send(decision: ShareImportDecisionEntity, candidate: ShareImportCandidateEntity): TransmissionOutcome
}

sealed interface SetLogTransmissionOutcome {
    data class Acknowledged(val remotePhotoId: Long, val publicationStatus: String) : SetLogTransmissionOutcome
    data class Failed(
        val stage: SetLogUploadStage,
        val outcome: TransmissionOutcome,
        val code: String,
    ) : SetLogTransmissionOutcome
}

fun interface SetLogOutboxTransport {
    fun send(payload: PendingSetLogEntity): SetLogTransmissionOutcome
}

class TypedOutboxProcessor(
    private val database: StogDatabase,
    private val visitTransport: VisitOutboxTransport,
    private val shareImportTransport: ShareImportOutboxTransport,
    private val setLogTransport: SetLogOutboxTransport = SetLogOutboxTransport { error("Set Log transport not configured") },
    private val cleanupSetLog: (PendingSetLogEntity) -> Boolean = ::deleteSetLogFiles,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun processVisits(accountId: String, tripId: String): Boolean {
        while (true) {
            val item = database.visitOutboxDao().next(accountId, tripId) ?: return false
            val outcome = visitTransport.send(item)
            val classified = OutboxRetryPolicy.classify(outcome)
            val updated = when (outcome) {
                is TransmissionOutcome.VisitAcknowledged -> database.visitOutboxDao().recordOutcomeWithReceipt(
                    item.id, classified.state, classified.retryClass, classified.responseCode, outcome.receipt,
                )
                else -> database.visitOutboxDao().recordOutcome(
                    item.id, classified.state, classified.retryClass, classified.responseCode,
                )
            }
            check(updated == 1)
            if (classified.shouldRetry) return true
        }
    }

    fun processSetLogs(accountId: String, tripId: Long): Boolean {
        val dao = database.pendingSetLogDao()
        dao.awaitingCleanup(accountId, tripId).forEach { acknowledged ->
            if (cleanupSetLog(acknowledged)) check(dao.markCleanupComplete(acknowledged.clientUploadId) == 1)
        }
        while (true) {
            val item = dao.next(accountId, tripId) ?: return false
            when (val outcome = setLogTransport.send(item)) {
                is SetLogTransmissionOutcome.Acknowledged -> {
                    check(dao.acknowledge(
                        item.clientUploadId, outcome.remotePhotoId, outcome.publicationStatus, now(),
                    ) == 1)
                    val acknowledged = checkNotNull(dao.find(item.clientUploadId))
                    if (cleanupSetLog(acknowledged)) check(dao.markCleanupComplete(item.clientUploadId) == 1)
                }
                is SetLogTransmissionOutcome.Failed -> {
                    val classified = classifySetLogFailure(outcome.outcome)
                    check(dao.recordFailure(
                        item.clientUploadId, classified.first, classified.second.retryClass,
                        outcome.stage, classified.second.responseCode, outcome.code,
                    ) == 1)
                    if (classified.first == SetLogOutboxState.RETRY) return true
                    if (classified.first == SetLogOutboxState.AUTH_REQUIRED) return false
                }
            }
        }
    }

    fun processShareImports(accountId: String, tripId: Long? = null): Boolean {
        while (true) {
            val decision = database.shareImportDao().nextConfirmed(accountId, tripId) ?: return false
            val candidate = database.shareImportDao().candidates(decision.importId)
                .single { it.candidateId == decision.candidateId }
            val classified = OutboxRetryPolicy.classify(shareImportTransport.send(decision, candidate))
            database.shareImportDao().recordOutcomeAndComplete(
                decision.candidateId, classified.state, classified.retryClass, classified.responseCode,
            )
            if (classified.shouldRetry) return true
        }
    }
}

internal sealed interface CollectionPublishOutcome {
    data class Acknowledged(val modeVersion: Long) : CollectionPublishOutcome
    data object NetworkFailure : CollectionPublishOutcome
    data class HttpFailure(val statusCode: Int) : CollectionPublishOutcome
}

private fun classifySetLogFailure(outcome: TransmissionOutcome): Pair<SetLogOutboxState, ClassifiedOutcome> {
    val classified = OutboxRetryPolicy.classify(outcome)
    val state = when (outcome) {
        is TransmissionOutcome.HttpFailure -> when (outcome.statusCode) {
            401 -> SetLogOutboxState.AUTH_REQUIRED
            429 -> SetLogOutboxState.RETRY
            in 500..599 -> SetLogOutboxState.RETRY
            else -> SetLogOutboxState.TERMINAL
        }
        TransmissionOutcome.NetworkFailure, TransmissionOutcome.InvalidResponse -> SetLogOutboxState.RETRY
        else -> SetLogOutboxState.ACKNOWLEDGED
    }
    return state to classified
}

private fun deleteSetLogFiles(payload: PendingSetLogEntity): Boolean {
    val files = listOfNotNull(payload.sourcePath, payload.originalPath, payload.thumbnailPath)
        .map(::File).distinctBy(File::getAbsolutePath)
    files.forEach { if (it.isFile) it.delete() }
    files.mapNotNull(File::getParentFile).distinctBy(File::getAbsolutePath)
        .sortedByDescending { it.absolutePath.length }.forEach(File::delete)
    return files.none(File::exists)
}

internal object OutboxRuntime {
    var loadTokens: (Context) -> StoredAuthTokens? = { context ->
        runCatching { AuthTokenStore(context).load() }.getOrNull()
    }
    var publishCollection: (Context, StoredAuthTokens, MemberCollectionStateEntity) -> CollectionPublishOutcome =
        ::publishCollectionState
    var apiBaseUrl: (Context) -> String = { BuildConfig.STOG_API_BASE_URL }
    var shareTransport: (Context, StoredAuthTokens) -> ShareImportOutboxTransport = { context, tokens ->
        ConfirmedShareHttpTransport(PlanningApiClient(apiBaseUrl(context)), tokens.accessToken)
    }
    var setLogTransport: (Context, StoredAuthTokens) -> SetLogOutboxTransport = { context, tokens ->
        SignedSetLogOutboxTransport(PhotoApiClient(apiBaseUrl(context)), tokens.accessToken)
    }

    fun reset() {
        loadTokens = { context -> runCatching { AuthTokenStore(context).load() }.getOrNull() }
        publishCollection = ::publishCollectionState
        apiBaseUrl = { BuildConfig.STOG_API_BASE_URL }
        shareTransport = { context, tokens ->
            ConfirmedShareHttpTransport(PlanningApiClient(apiBaseUrl(context)), tokens.accessToken)
        }
        setLogTransport = { context, tokens ->
            SignedSetLogOutboxTransport(PhotoApiClient(apiBaseUrl(context)), tokens.accessToken)
        }
    }
}

internal class SignedSetLogOutboxTransport(
    private val api: PhotoApiClient,
    private val accessToken: String,
) : SetLogOutboxTransport {
    override fun send(payload: PendingSetLogEntity): SetLogTransmissionOutcome {
        var stage = SetLogUploadStage.ISSUE_URLS
        return try {
            val photo = payload.toPreparedPhoto()
            val urls = api.issueUploadUrls(accessToken, photo)
            stage = SetLogUploadStage.ORIGINAL_PUT
            api.putDirectly(urls.originalUploadUrl, urls.originalUploadHeaders, File(payload.originalPath))
            stage = SetLogUploadStage.THUMBNAIL_PUT
            api.putDirectly(urls.thumbnailUploadUrl, urls.thumbnailUploadHeaders, File(payload.thumbnailPath))
            stage = SetLogUploadStage.FINALIZE
            val remote = api.finalize(accessToken, payload.toConfirmedUpload(photo), urls)
            val publication = remote.publicationStatus
                ?: return SetLogTransmissionOutcome.Failed(stage, TransmissionOutcome.InvalidResponse, "PHOTO_INVALID_FINALIZE_RESPONSE")
            SetLogTransmissionOutcome.Acknowledged(remote.id, publication)
        } catch (_: PhotoResponseDecodingException) {
            val code = if (stage == SetLogUploadStage.ISSUE_URLS) {
                "PHOTO_INVALID_UPLOAD_URL_RESPONSE"
            } else {
                "PHOTO_INVALID_FINALIZE_RESPONSE"
            }
            SetLogTransmissionOutcome.Failed(stage, TransmissionOutcome.InvalidResponse, code)
        } catch (failure: PhotoRequestException) {
            SetLogTransmissionOutcome.Failed(
                stage, TransmissionOutcome.HttpFailure(failure.statusCode), failure.code,
            )
        } catch (_: IOException) {
            SetLogTransmissionOutcome.Failed(stage, TransmissionOutcome.NetworkFailure, "PHOTO_NETWORK_UNAVAILABLE")
        }
    }
}

private fun PendingSetLogEntity.toPreparedPhoto() = PreparedPhoto(
    id = clientUploadId,
    source = PhotoSource.valueOf(source.uppercase()),
    tripId = tripId,
    normalizedOriginalPath = originalPath,
    thumbnailPath = thumbnailPath,
    normalizedOriginalBytes = originalSize,
    thumbnailBytes = thumbnailSize,
    originalSha256 = originalSha256,
    thumbnailSha256 = thumbnailSha256,
    coordinates = latitude?.let { PhotoCoordinates(it, requireNotNull(longitude)) },
    takenAt = takenAt,
    caption = caption,
    sourceFilePath = sourcePath,
)

private fun PendingSetLogEntity.toConfirmedUpload(photo: PreparedPhoto) = ConfirmedSetLogUpload(
    photo = photo,
    accuracyMeters = accuracyMeters,
    locationProvenance = locationProvenance?.let {
        when (it) {
            "camera_foreground" -> SetLogLocationProvenance.CAMERA_FOREGROUND
            "gallery_exif" -> SetLogLocationProvenance.GALLERY_EXIF
            else -> error("Unsupported persisted location provenance")
        }
    } ?: SetLogLocationProvenance.MISSING,
    placeResolutionStatus = placeResolutionStatus,
    expectedPlaceId = expectedPlaceId,
    visibility = SetLogVisibilityIntent.valueOf(visibility.uppercase()),
    publicConsent = publicConsent,
)

private fun publishCollectionState(
    context: Context,
    tokens: StoredAuthTokens,
    current: MemberCollectionStateEntity,
): CollectionPublishOutcome = try {
    val remote = RecordingApiClient(
        BuildConfig.STOG_API_BASE_URL,
        context.resources.getInteger(R.integer.recording_upload_timeout_seconds) * 1_000,
    ).updateCollectionState(
        tokens.accessToken,
        current.tripId,
        CollectorState.valueOf(current.collectorState.name),
        PermissionState.valueOf(current.permissionState.name),
        current.modeVersion,
    )
    CollectionPublishOutcome.Acknowledged(remote.modeVersion)
} catch (failure: RecordingHttpException) {
    CollectionPublishOutcome.HttpFailure(failure.statusCode)
} catch (_: IOException) {
    CollectionPublishOutcome.NetworkFailure
}

class TypedOutboxWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : Worker(appContext, parameters) {
    override fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val tokens = OutboxRuntime.loadTokens(applicationContext)
        if (tokens != null && tokens.userId.toString() != accountId) return Result.success()
        val recordingClient = RecordingApiClient(
            BuildConfig.STOG_API_BASE_URL,
            applicationContext.resources.getInteger(R.integer.recording_upload_timeout_seconds) * 1_000,
        )
        val database = StogDatabase.get(applicationContext)
        val shareTransport = tokens?.let { OutboxRuntime.shareTransport(applicationContext, it) }
            ?: ShareImportOutboxTransport { _, _ -> TransmissionOutcome.NetworkFailure }
        val processor = TypedOutboxProcessor(
            database = database,
            visitTransport = { visit ->
                tokens?.let { recordingClient.sendVisit(it.accessToken, visit) }
                    ?: TransmissionOutcome.NetworkFailure
            },
            shareImportTransport = shareTransport,
            setLogTransport = tokens?.let { OutboxRuntime.setLogTransport(applicationContext, it) }
                ?: SetLogOutboxTransport {
                    SetLogTransmissionOutcome.Failed(
                        SetLogUploadStage.ISSUE_URLS, TransmissionOutcome.NetworkFailure, "AUTH_REQUIRED",
                    )
                },
        )
        val shouldRetry = when (inputData.getString(KEY_KIND)) {
            KIND_VISIT -> {
                val tripId = inputData.getString(KEY_TRIP_ID)?.takeIf(String::isNotBlank)
                    ?: return Result.failure()
                processor.processVisits(accountId, tripId)
            }
            KIND_SHARE_IMPORT -> {
                if (tokens == null) return Result.retry()
                val tripId = inputData.getLong(KEY_TRIP_ID, -1L).takeIf { it > 0L }
                    ?: return Result.failure()
                ShareImportSyncRunner(
                    database,
                    File(applicationContext.noBackupFilesDir, ShareImportFileStore.ROOT_DIRECTORY_NAME),
                    shareTransport,
                ).run(accountId, tripId)
            }
            KIND_SET_LOG -> {
                if (tokens == null) return Result.retry()
                val tripId = inputData.getLong(KEY_TRIP_ID, -1L).takeIf { it > 0L }
                    ?: return Result.failure()
                processor.processSetLogs(accountId, tripId)
            }
            KIND_COLLECTION_STATE -> {
                val tripId = inputData.getString(KEY_TRIP_ID)?.takeIf(String::isNotBlank)
                    ?: return Result.failure()
                val current = StogDatabase.get(applicationContext).memberCollectionStateDao()
                    .find(accountId, tripId, accountId) ?: return Result.success()
                val token = tokens ?: return Result.retry()
                when (val outcome = OutboxRuntime.publishCollection(applicationContext, token, current)) {
                    is CollectionPublishOutcome.Acknowledged -> {
                        StogDatabase.get(applicationContext).memberCollectionStateDao().save(
                            current.copy(modeVersion = outcome.modeVersion, updatedAt = System.currentTimeMillis()),
                        )
                        false
                    }
                    CollectionPublishOutcome.NetworkFailure -> true
                    is CollectionPublishOutcome.HttpFailure ->
                        outcome.statusCode == 429 || outcome.statusCode in 500..599
                }
            }
            else -> return Result.failure()
        }
        return if (shouldRetry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_KIND = "outbox_kind"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_TRIP_ID = "trip_id"
        const val KIND_VISIT = "visit"
        const val KIND_SHARE_IMPORT = "share_import"
        const val KIND_COLLECTION_STATE = "collection_state"
        const val KIND_SET_LOG = "set_log"
    }
}

sealed interface SetLogEnqueueResult {
    data class Scheduled(val workId: UUID) : SetLogEnqueueResult
    data class SchedulingDeferred(val cause: Exception) : SetLogEnqueueResult
}

class SetLogDeliveryQueue(
    context: Context,
    private val database: StogDatabase = StogDatabase.get(context.applicationContext),
    private val scheduleSetLogs: (String, Long) -> UUID =
        OutboxWorkScheduler(context.applicationContext)::enqueueSetLogs,
) {
    internal constructor(
        context: Context,
        database: StogDatabase,
        scheduler: OutboxWorkScheduler,
    ) : this(context, database, scheduler::enqueueSetLogs)

    fun enqueue(payload: PendingSetLogEntity): SetLogEnqueueResult {
        database.pendingSetLogDao().enqueue(payload)
        return try {
            SetLogEnqueueResult.Scheduled(scheduleSetLogs(payload.accountId, payload.tripId))
        } catch (failure: Exception) {
            SetLogEnqueueResult.SchedulingDeferred(failure)
        }
    }
}

object PendingSetLogWorkInitializer {
    fun schedule(context: Context, activeAccountId: String): List<UUID> {
        val appContext = context.applicationContext
        return StogDatabase.get(appContext).pendingSetLogDao().recoverableScopes()
            .asSequence()
            .filter { it.accountId == activeAccountId }
            .map { OutboxWorkScheduler(appContext).enqueueSetLogs(it.accountId, it.tripId) }
            .toList()
    }
}

object PendingShareWorkInitializer {
    fun schedule(context: Context, activeAccountId: String): List<UUID> {
        val appContext = context.applicationContext
        return StogDatabase.get(appContext).shareImportDao().pendingConfirmedScopes()
            .asSequence()
            .filter { it.accountId == activeAccountId }
            .map { OutboxWorkScheduler(appContext).enqueueShareImports(it.accountId, it.tripId) }
            .toList()
    }
}

class OutboxWorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueVisits(accountId: String, tripId: String): UUID =
        enqueue(
            uniqueName = visitWorkName(accountId, tripId),
            data = Data.Builder()
                .putString(TypedOutboxWorker.KEY_KIND, TypedOutboxWorker.KIND_VISIT)
                .putString(TypedOutboxWorker.KEY_ACCOUNT_ID, accountId)
                .putString(TypedOutboxWorker.KEY_TRIP_ID, tripId)
                .build(),
            tags = setOf("outbox-kind:visit", "account:$accountId", "trip:$tripId"),
        )

    fun enqueueShareImports(accountId: String, tripId: Long): UUID =
        enqueue(
            uniqueName = shareImportWorkName(accountId, tripId),
            data = Data.Builder()
                .putString(TypedOutboxWorker.KEY_KIND, TypedOutboxWorker.KIND_SHARE_IMPORT)
                .putString(TypedOutboxWorker.KEY_ACCOUNT_ID, accountId)
                .putLong(TypedOutboxWorker.KEY_TRIP_ID, tripId)
                .build(),
            tags = setOf("outbox-kind:share_import", "account:$accountId", "trip:$tripId"),
        )

    fun enqueueSetLogs(accountId: String, tripId: Long): UUID =
        enqueue(
            uniqueName = setLogWorkName(accountId, tripId),
            data = Data.Builder()
                .putString(TypedOutboxWorker.KEY_KIND, TypedOutboxWorker.KIND_SET_LOG)
                .putString(TypedOutboxWorker.KEY_ACCOUNT_ID, accountId)
                .putLong(TypedOutboxWorker.KEY_TRIP_ID, tripId)
                .build(),
            tags = setOf("outbox-kind:set_log", "account:$accountId", "trip:$tripId"),
        )

    fun enqueueCollectionState(accountId: String, tripId: String): UUID =
        enqueue(
            uniqueName = collectionStateWorkName(accountId, tripId),
            data = Data.Builder()
                .putString(TypedOutboxWorker.KEY_KIND, TypedOutboxWorker.KIND_COLLECTION_STATE)
                .putString(TypedOutboxWorker.KEY_ACCOUNT_ID, accountId)
                .putString(TypedOutboxWorker.KEY_TRIP_ID, tripId)
                .build(),
            tags = setOf("outbox-kind:collection_state", "account:$accountId", "trip:$tripId"),
            policy = ExistingWorkPolicy.REPLACE,
        )

    private fun enqueue(
        uniqueName: String,
        data: Data,
        tags: Set<String>,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
    ): UUID {
        val builder = OneTimeWorkRequestBuilder<TypedOutboxWorker>()
            .setInputData(data)
            .setConstraints(networkConstraints)
        tags.forEach(builder::addTag)
        val request = builder.build()
        workManager.enqueueUniqueWork(uniqueName, policy, request)
        return request.id
    }

    companion object {
        internal fun visitWorkName(accountId: String, tripId: String) = "visit-outbox:$accountId:$tripId"
        internal fun shareImportWorkName(accountId: String, tripId: Long) = "share-import-outbox:$accountId:$tripId"
        internal fun collectionStateWorkName(accountId: String, tripId: String) = "collection-state-outbox:$accountId:$tripId"
        internal fun setLogWorkName(accountId: String, tripId: Long) = "set-log-outbox:$accountId:$tripId"
    }
}
