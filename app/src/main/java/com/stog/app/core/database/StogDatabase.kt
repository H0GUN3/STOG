package com.stog.app.core.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class LocationClassificationState { PENDING, CLASSIFIED }
enum class VisitStatus { PASSED, VISITED }
enum class OutboxState { PENDING, RETRY, ACKNOWLEDGED, TERMINAL }
enum class RetryClass { NONE, NETWORK, RATE_LIMIT, SERVER, TERMINAL_CLIENT }
enum class SetLogOutboxState { PENDING, RETRY, AUTH_REQUIRED, ACKNOWLEDGED, TERMINAL }
enum class SetLogUploadStage { ISSUE_URLS, ORIGINAL_PUT, THUMBNAIL_PUT, FINALIZE }
enum class PendingShareImportStatus { SAVED, CLASSIFIED, FAILED, COMPLETED }
enum class CandidateOrigin { EXTRACTED, CORRECTED, MANUAL }
enum class CandidateDecisionState { PENDING, CONFIRMED, REJECTED }
enum class PersistedCollectorState { INACTIVE, STARTING, ACTIVE, DORMANT, BLOCKED, ENDED }
enum class PersistedPermissionState { UNKNOWN, GRANTED, DENIED, REVOKED }

@Entity(
    tableName = "account_ownership",
    indices = [Index(value = ["account_id", "user_id"], unique = true)],
)
data class AccountOwnershipEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "location_observations",
    foreignKeys = [ForeignKey(
        entity = AccountOwnershipEntity::class,
        parentColumns = ["account_id", "user_id"],
        childColumns = ["account_id", "user_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["account_id", "user_id"]),
        Index(value = ["observation_id", "account_id", "trip_id", "user_id"], unique = true),
        Index(value = ["account_id", "trip_id", "observed_at"]),
    ],
)
data class LocationObservationEntity(
    @PrimaryKey @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "cell_id") val cellId: Long,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "classification_state") val classificationState: LocationClassificationState = LocationClassificationState.PENDING,
)

@Entity(
    tableName = "visit_outbox",
    foreignKeys = [
        ForeignKey(
            entity = AccountOwnershipEntity::class,
            parentColumns = ["account_id", "user_id"],
            childColumns = ["account_id", "user_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LocationObservationEntity::class,
            parentColumns = ["observation_id", "account_id", "trip_id", "user_id"],
            childColumns = ["observation_id", "account_id", "trip_id", "user_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["account_id", "user_id"]),
        Index(value = ["observation_id", "account_id", "trip_id", "user_id"], unique = true),
        Index(value = ["account_id", "trip_id", "client_visit_id"], unique = true),
        Index(value = ["account_id", "trip_id", "outbox_state", "created_at"]),
    ],
)
data class VisitOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "client_visit_id") val clientVisitId: String,
    @ColumnInfo(name = "payload_fingerprint") val payloadFingerprint: String,
    @ColumnInfo(name = "cell_id") val cellId: Long,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "entered_at") val enteredAt: Long,
    @ColumnInfo(name = "left_at") val leftAt: Long,
    val status: VisitStatus,
    @ColumnInfo(name = "is_interpolated") val isInterpolated: Boolean,
    @ColumnInfo(name = "outbox_state") val outboxState: OutboxState = OutboxState.PENDING,
    @ColumnInfo(name = "retry_class") val retryClass: RetryClass = RetryClass.NONE,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_response_code") val lastResponseCode: Int? = null,
    @ColumnInfo(name = "remote_visit_id") val remoteVisitId: Long? = null,
    @ColumnInfo(name = "review_required") val reviewRequired: Boolean? = null,
    @ColumnInfo(name = "review_consumed_at") val reviewConsumedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "pending_set_logs",
    foreignKeys = [ForeignKey(
        entity = AccountOwnershipEntity::class,
        parentColumns = ["account_id", "user_id"],
        childColumns = ["account_id", "user_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["account_id", "user_id"]),
        Index(value = ["account_id", "trip_id", "client_upload_id"], unique = true),
        Index(value = ["account_id", "trip_id", "outbox_state", "created_at"]),
        Index(value = ["account_id", "outbox_state", "created_at"]),
    ],
)
data class PendingSetLogEntity(
    @PrimaryKey @ColumnInfo(name = "client_upload_id") val clientUploadId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "trip_id") val tripId: Long,
    val source: String,
    @ColumnInfo(name = "original_path") val originalPath: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String,
    @ColumnInfo(name = "source_path") val sourcePath: String?,
    @ColumnInfo(name = "original_size") val originalSize: Long,
    @ColumnInfo(name = "thumbnail_size") val thumbnailSize: Long,
    @ColumnInfo(name = "original_sha256") val originalSha256: String,
    @ColumnInfo(name = "thumbnail_sha256") val thumbnailSha256: String,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyMeters: Double?,
    @ColumnInfo(name = "location_provenance") val locationProvenance: String?,
    @ColumnInfo(name = "taken_at") val takenAt: String?,
    val caption: String?,
    @ColumnInfo(name = "place_resolution_status") val placeResolutionStatus: String,
    @ColumnInfo(name = "expected_place_id") val expectedPlaceId: Long?,
    val visibility: String,
    @ColumnInfo(name = "public_consent") val publicConsent: Boolean,
    @ColumnInfo(name = "outbox_state") val outboxState: SetLogOutboxState = SetLogOutboxState.PENDING,
    @ColumnInfo(name = "retry_class") val retryClass: RetryClass = RetryClass.NONE,
    @ColumnInfo(name = "upload_stage") val uploadStage: SetLogUploadStage = SetLogUploadStage.ISSUE_URLS,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_response_code") val lastResponseCode: Int? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "remote_photo_id") val remotePhotoId: Long? = null,
    @ColumnInfo(name = "publication_status") val publicationStatus: String? = null,
    @ColumnInfo(name = "acknowledged_at") val acknowledgedAt: Long? = null,
    @ColumnInfo(name = "cleanup_complete") val cleanupComplete: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

data class PendingSetLogScope(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "trip_id") val tripId: Long,
)

@Entity(
    tableName = "member_collection_states",
    primaryKeys = ["account_id", "trip_id", "user_id"],
    foreignKeys = [ForeignKey(
        entity = AccountOwnershipEntity::class,
        parentColumns = ["account_id", "user_id"],
        childColumns = ["account_id", "user_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["account_id", "user_id"]),
        Index(value = ["account_id", "collector_state"]),
    ],
)
data class MemberCollectionStateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "collector_state") val collectorState: PersistedCollectorState,
    @ColumnInfo(name = "permission_state") val permissionState: PersistedPermissionState,
    @ColumnInfo(name = "mode_version") val modeVersion: Long,
    @ColumnInfo(name = "trip_end_at") val tripEndAt: Long?,
    @ColumnInfo(name = "battery_low") val batteryLow: Boolean,
    @ColumnInfo(name = "active_cell") val activeCell: Long?,
    @ColumnInfo(name = "active_lat") val activeLat: Double?,
    @ColumnInfo(name = "active_lng") val activeLng: Double?,
    @ColumnInfo(name = "entered_at") val enteredAt: Long?,
    @ColumnInfo(name = "candidate_cell") val candidateCell: Long?,
    @ColumnInfo(name = "candidate_count") val candidateCount: Int,
    @ColumnInfo(name = "last_accepted_lat") val lastAcceptedLat: Double?,
    @ColumnInfo(name = "last_accepted_lng") val lastAcceptedLng: Double?,
    @ColumnInfo(name = "last_accepted_at") val lastAcceptedAt: Long?,
    @ColumnInfo(name = "last_transition_at") val lastTransitionAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "pending_share_imports",
    foreignKeys = [ForeignKey(
        entity = AccountOwnershipEntity::class,
        parentColumns = ["account_id"],
        childColumns = ["account_id"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("account_id"), Index(value = ["account_id", "created_at"])],
)
data class PendingShareImportEntity(
    @PrimaryKey @ColumnInfo(name = "import_id") val importId: String,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "raw_directory_name") val rawDirectoryName: String,
    @ColumnInfo(name = "raw_text") val rawText: String?,
    @ColumnInfo(name = "raw_html") val rawHtml: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val source: String,
    val status: PendingShareImportStatus = PendingShareImportStatus.SAVED,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "share_import_candidates",
    foreignKeys = [ForeignKey(
        entity = PendingShareImportEntity::class,
        parentColumns = ["import_id"],
        childColumns = ["import_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("import_id"),
        Index(value = ["candidate_id", "import_id"], unique = true),
        Index(value = ["import_id", "candidate_order"], unique = true),
    ],
)
data class ShareImportCandidateEntity(
    @PrimaryKey @ColumnInfo(name = "candidate_id") val candidateId: String,
    @ColumnInfo(name = "import_id") val importId: String,
    val origin: CandidateOrigin,
    val title: String,
    val address: String?,
    @ColumnInfo(name = "original_url") val originalUrl: String?,
    @ColumnInfo(name = "candidate_order") val candidateOrder: Int,
    val provider: String? = null,
    @ColumnInfo(name = "external_id") val externalId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val category: String? = null,
    @ColumnInfo(name = "canonical_place_id") val canonicalPlaceId: Long? = null,
    @ColumnInfo(name = "canonical_source_id") val canonicalSourceId: Long? = null,
)

data class PendingShareScope(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "trip_id") val tripId: Long,
)

@Entity(
    tableName = "share_import_decisions",
    foreignKeys = [
        ForeignKey(
            entity = PendingShareImportEntity::class,
            parentColumns = ["import_id"],
            childColumns = ["import_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ShareImportCandidateEntity::class,
            parentColumns = ["candidate_id", "import_id"],
            childColumns = ["candidate_id", "import_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("import_id"),
        Index(value = ["candidate_id", "import_id"]),
        Index(value = ["import_id", "sync_state", "decided_at"]),
    ],
)
data class ShareImportDecisionEntity(
    @PrimaryKey @ColumnInfo(name = "candidate_id") val candidateId: String,
    @ColumnInfo(name = "import_id") val importId: String,
    val decision: CandidateDecisionState,
    @ColumnInfo(name = "trip_id") val tripId: Long? = null,
    @ColumnInfo(name = "client_item_id") val clientItemId: String? = null,
    @ColumnInfo(name = "payload_fingerprint") val payloadFingerprint: String? = null,
    @ColumnInfo(name = "sync_state") val syncState: OutboxState = OutboxState.PENDING,
    @ColumnInfo(name = "retry_class") val retryClass: RetryClass = RetryClass.NONE,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_response_code") val lastResponseCode: Int? = null,
    @ColumnInfo(name = "decided_at") val decidedAt: Long,
)

@Dao
interface AccountOwnershipDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: AccountOwnershipEntity)

    @Query("SELECT * FROM account_ownership WHERE account_id = :accountId")
    fun find(accountId: String): AccountOwnershipEntity?
}

@Dao
abstract class LocationObservationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insert(entity: LocationObservationEntity)

    @Query("SELECT * FROM location_observations WHERE observation_id = :observationId")
    abstract fun find(observationId: String): LocationObservationEntity?

    @Query("UPDATE location_observations SET classification_state = 'CLASSIFIED' WHERE observation_id = :observationId AND classification_state = 'PENDING'")
    abstract fun markClassified(observationId: String): Int
}

@Dao
abstract class RecordingPersistenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertObservation(entity: LocationObservationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun saveState(entity: MemberCollectionStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertVisit(entity: VisitOutboxEntity): Long

    @Query("UPDATE location_observations SET classification_state = 'CLASSIFIED' WHERE observation_id = :observationId AND classification_state = 'PENDING'")
    protected abstract fun classify(observationId: String): Int

    @Transaction
    open fun persistObservationResult(
        observation: LocationObservationEntity,
        state: MemberCollectionStateEntity,
        visit: VisitOutboxEntity?,
    ): Long? {
        insertObservation(observation)
        saveState(state)
        val visitId = visit?.let(::insertVisit)
        check(classify(observation.observationId) == 1)
        return visitId
    }
}

@Dao
abstract class PendingSetLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertOwnershipIfAbsent(entity: AccountOwnershipEntity): Long

    @Query("SELECT * FROM account_ownership WHERE account_id = :accountId")
    protected abstract fun ownership(accountId: String): AccountOwnershipEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insert(entity: PendingSetLogEntity)

    @Transaction
    open fun enqueue(entity: PendingSetLogEntity) {
        insertOwnershipIfAbsent(AccountOwnershipEntity(entity.accountId, entity.userId, entity.createdAt))
        require(ownership(entity.accountId)?.userId == entity.userId) {
            "Set Log account ownership does not match"
        }
        insert(entity)
    }

    @Query("SELECT * FROM pending_set_logs WHERE client_upload_id = :clientUploadId")
    abstract fun find(clientUploadId: String): PendingSetLogEntity?

    @Query("SELECT * FROM pending_set_logs WHERE client_upload_id = :clientUploadId")
    abstract fun observe(clientUploadId: String): Flow<PendingSetLogEntity?>

    @Query("SELECT * FROM pending_set_logs WHERE account_id = :accountId ORDER BY created_at, client_upload_id")
    abstract fun allForAccount(accountId: String): List<PendingSetLogEntity>

    @Query("SELECT * FROM pending_set_logs WHERE account_id = :accountId AND trip_id = :tripId AND outbox_state IN ('PENDING','RETRY','AUTH_REQUIRED') ORDER BY created_at, client_upload_id LIMIT 1")
    abstract fun next(accountId: String, tripId: Long): PendingSetLogEntity?

    @Query("SELECT DISTINCT account_id, trip_id FROM pending_set_logs WHERE outbox_state IN ('PENDING','RETRY','AUTH_REQUIRED') OR (outbox_state = 'ACKNOWLEDGED' AND cleanup_complete = 0) ORDER BY account_id, trip_id")
    abstract fun recoverableScopes(): List<PendingSetLogScope>

    @Query("SELECT * FROM pending_set_logs WHERE account_id = :accountId AND trip_id = :tripId AND outbox_state = 'ACKNOWLEDGED' AND cleanup_complete = 0 ORDER BY created_at, client_upload_id")
    abstract fun awaitingCleanup(accountId: String, tripId: Long): List<PendingSetLogEntity>

    @Query("UPDATE pending_set_logs SET outbox_state = :state, retry_class = :retryClass, upload_stage = :stage, attempt_count = attempt_count + 1, last_response_code = :responseCode, error_code = :errorCode WHERE client_upload_id = :clientUploadId AND outbox_state != 'ACKNOWLEDGED'")
    abstract fun recordFailure(clientUploadId: String, state: SetLogOutboxState, retryClass: RetryClass, stage: SetLogUploadStage, responseCode: Int?, errorCode: String): Int

    @Query("UPDATE pending_set_logs SET outbox_state = 'ACKNOWLEDGED', retry_class = 'NONE', upload_stage = 'FINALIZE', attempt_count = attempt_count + 1, last_response_code = 200, error_code = NULL, remote_photo_id = :remotePhotoId, publication_status = :publicationStatus, acknowledged_at = :acknowledgedAt WHERE client_upload_id = :clientUploadId AND outbox_state != 'ACKNOWLEDGED'")
    abstract fun acknowledge(clientUploadId: String, remotePhotoId: Long, publicationStatus: String, acknowledgedAt: Long): Int

    @Query("UPDATE pending_set_logs SET cleanup_complete = 1 WHERE client_upload_id = :clientUploadId AND outbox_state = 'ACKNOWLEDGED'")
    abstract fun markCleanupComplete(clientUploadId: String): Int
}

@Dao
interface MemberCollectionStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(entity: MemberCollectionStateEntity)

    @Query("SELECT * FROM member_collection_states WHERE account_id = :accountId AND trip_id = :tripId AND user_id = :userId")
    fun find(accountId: String, tripId: String, userId: String): MemberCollectionStateEntity?

    @Query("SELECT * FROM member_collection_states WHERE account_id = :accountId ORDER BY trip_id, user_id")
    fun allForAccount(accountId: String): List<MemberCollectionStateEntity>
}

@Dao
abstract class VisitOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertInternal(entity: VisitOutboxEntity): Long

    @Query("SELECT * FROM location_observations WHERE observation_id = :observationId")
    protected abstract fun observation(observationId: String): LocationObservationEntity?

    @Query("UPDATE location_observations SET classification_state = 'CLASSIFIED' WHERE observation_id = :observationId AND classification_state = 'PENDING'")
    protected abstract fun markObservationClassified(observationId: String): Int

    @Transaction
    open fun enqueue(entity: VisitOutboxEntity): Long {
        val source = requireNotNull(observation(entity.observationId)) { "Observation must be persisted before classification" }
        require(source.accountId == entity.accountId && source.tripId == entity.tripId && source.userId == entity.userId) {
            "Observation ownership does not match visit outbox"
        }
        val id = insertInternal(entity)
        check(markObservationClassified(entity.observationId) == 1) { "Observation was already classified" }
        return id
    }

    @Query("SELECT * FROM visit_outbox WHERE account_id = :accountId AND trip_id = :tripId AND outbox_state IN ('PENDING', 'RETRY') ORDER BY created_at ASC, id ASC LIMIT 1")
    abstract fun next(accountId: String, tripId: String): VisitOutboxEntity?

    @Query("SELECT * FROM visit_outbox WHERE account_id = :accountId ORDER BY created_at ASC, id ASC")
    abstract fun allForAccount(accountId: String): List<VisitOutboxEntity>

    @Query("SELECT DISTINCT trip_id FROM visit_outbox WHERE account_id = :accountId AND outbox_state IN ('PENDING', 'RETRY') ORDER BY trip_id")
    abstract fun pendingTripIds(accountId: String): List<String>

    @Query("SELECT * FROM visit_outbox WHERE id = :id")
    abstract fun find(id: Long): VisitOutboxEntity?

    @Query("UPDATE visit_outbox SET outbox_state = :state, retry_class = :retryClass, attempt_count = attempt_count + 1, last_response_code = :responseCode WHERE id = :id")
    abstract fun recordOutcome(id: Long, state: OutboxState, retryClass: RetryClass, responseCode: Int?): Int

    @Query("UPDATE visit_outbox SET outbox_state = :state, retry_class = :retryClass, attempt_count = attempt_count + 1, last_response_code = :responseCode, remote_visit_id = :remoteVisitId, review_required = :reviewRequired WHERE id = :id AND client_visit_id = :clientVisitId")
    protected abstract fun recordOutcomeWithReceiptInternal(
        id: Long,
        state: OutboxState,
        retryClass: RetryClass,
        responseCode: Int?,
        remoteVisitId: Long,
        clientVisitId: String,
        reviewRequired: Boolean,
    ): Int

    @Transaction
    open fun recordOutcomeWithReceipt(
        id: Long,
        state: OutboxState,
        retryClass: RetryClass,
        responseCode: Int?,
        receipt: VisitReviewReceipt,
    ): Int = recordOutcomeWithReceiptInternal(
        id, state, retryClass, responseCode,
        receipt.remoteVisitId, receipt.clientVisitId, receipt.reviewRequired,
    )

    @Query("SELECT * FROM visit_outbox WHERE account_id = :accountId AND outbox_state = 'ACKNOWLEDGED' AND review_required = 1 AND review_consumed_at IS NULL ORDER BY created_at, id LIMIT 1")
    abstract fun observePendingReview(accountId: String): Flow<VisitOutboxEntity?>

    @Query("UPDATE visit_outbox SET review_consumed_at = :consumedAt WHERE id = :id AND review_required = 1 AND review_consumed_at IS NULL")
    abstract fun consumeReview(id: Long, consumedAt: Long): Int
}

@Dao
abstract class ShareImportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertPending(entity: PendingShareImportEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertCandidatesInternal(entities: List<ShareImportCandidateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertCandidate(entity: ShareImportCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertDecision(entity: ShareImportDecisionEntity)

    @Query("SELECT * FROM pending_share_imports WHERE import_id = :importId")
    abstract fun pending(importId: String): PendingShareImportEntity?

    @Query("SELECT * FROM pending_share_imports ORDER BY created_at ASC, import_id ASC")
    abstract fun allPending(): List<PendingShareImportEntity>

    @Query("SELECT * FROM pending_share_imports WHERE account_id = :accountId AND status = 'COMPLETED' ORDER BY created_at, import_id")
    abstract fun completed(accountId: String): List<PendingShareImportEntity>

    @Query("DELETE FROM pending_share_imports WHERE import_id = :importId AND status = 'COMPLETED'")
    abstract fun deleteCompleted(importId: String): Int

    @Query("UPDATE pending_share_imports SET account_id = :accountId WHERE import_id = :importId AND account_id IS NULL")
    abstract fun assignAccount(importId: String, accountId: String): Int

    @Query("SELECT * FROM share_import_candidates WHERE import_id = :importId ORDER BY candidate_order ASC")
    abstract fun candidates(importId: String): List<ShareImportCandidateEntity>

    @Query("SELECT * FROM share_import_decisions WHERE import_id = :importId ORDER BY decided_at, candidate_id")
    abstract fun decisions(importId: String): List<ShareImportDecisionEntity>

    @Query("UPDATE pending_share_imports SET status = 'CLASSIFIED' WHERE import_id = :importId AND status = 'SAVED'")
    protected abstract fun markClassified(importId: String): Int

    @Transaction
    open fun persistCandidates(importId: String, entities: List<ShareImportCandidateEntity>) {
        check(pending(importId)?.status == PendingShareImportStatus.SAVED) { "Raw share import must be persisted before classification" }
        require(entities.all { it.importId == importId }) { "Candidate belongs to another share import" }
        if (entities.isNotEmpty()) insertCandidatesInternal(entities)
        check(markClassified(importId) == 1)
    }

    @Query("SELECT DISTINCT p.account_id, d.trip_id FROM share_import_decisions d JOIN pending_share_imports p ON p.import_id = d.import_id WHERE p.account_id IS NOT NULL AND d.trip_id IS NOT NULL AND d.decision = 'CONFIRMED' AND d.sync_state IN ('PENDING', 'RETRY') ORDER BY p.account_id, d.trip_id")
    abstract fun pendingConfirmedScopes(): List<PendingShareScope>

    @Query("SELECT d.* FROM share_import_decisions d JOIN pending_share_imports p ON p.import_id = d.import_id WHERE p.account_id = :accountId AND (:tripId IS NULL OR d.trip_id = :tripId) AND d.decision = 'CONFIRMED' AND d.sync_state IN ('PENDING', 'RETRY') ORDER BY p.created_at ASC, d.decided_at ASC, d.candidate_id ASC LIMIT 1")
    abstract fun nextConfirmed(accountId: String, tripId: Long? = null): ShareImportDecisionEntity?

    @Query("UPDATE share_import_decisions SET sync_state = :state, retry_class = :retryClass, attempt_count = attempt_count + 1, last_response_code = :responseCode WHERE candidate_id = :candidateId")
    abstract fun recordOutcome(candidateId: String, state: OutboxState, retryClass: RetryClass, responseCode: Int?): Int

    @Query("UPDATE pending_share_imports SET status = 'FAILED' WHERE import_id = :importId AND status != 'COMPLETED'")
    abstract fun markFailed(importId: String): Int

    @Query("UPDATE pending_share_imports SET status = 'COMPLETED' WHERE import_id = :importId AND status = 'CLASSIFIED' AND (SELECT COUNT(*) FROM share_import_candidates WHERE import_id = :importId) = (SELECT COUNT(*) FROM share_import_decisions WHERE import_id = :importId) AND NOT EXISTS (SELECT 1 FROM share_import_decisions WHERE import_id = :importId AND decision = 'CONFIRMED' AND sync_state != 'ACKNOWLEDGED')")
    protected abstract fun markCompletedIfFullyAcknowledged(importId: String): Int

    @Query("SELECT * FROM share_import_decisions WHERE candidate_id = :candidateId")
    protected abstract fun decisionForCandidate(candidateId: String): ShareImportDecisionEntity?

    @Transaction
    open fun recordOutcomeAndComplete(candidateId: String, state: OutboxState, retryClass: RetryClass, responseCode: Int?): Boolean {
        val importId = decisionForCandidate(candidateId)?.importId ?: return false
        check(recordOutcome(candidateId, state, retryClass, responseCode) == 1)
        return markCompletedIfFullyAcknowledged(importId) == 1
    }

    @Transaction
    open fun completeAllRejected(importId: String): Boolean = markCompletedIfFullyAcknowledged(importId) == 1
}

@Database(
    entities = [
        AccountOwnershipEntity::class,
        LocationObservationEntity::class,
        VisitOutboxEntity::class,
        PendingSetLogEntity::class,
        MemberCollectionStateEntity::class,
        PendingShareImportEntity::class,
        ShareImportCandidateEntity::class,
        ShareImportDecisionEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class StogDatabase : RoomDatabase() {
    abstract fun accountOwnershipDao(): AccountOwnershipDao
    abstract fun locationObservationDao(): LocationObservationDao
    abstract fun visitOutboxDao(): VisitOutboxDao
    abstract fun pendingSetLogDao(): PendingSetLogDao
    abstract fun recordingPersistenceDao(): RecordingPersistenceDao
    abstract fun memberCollectionStateDao(): MemberCollectionStateDao
    abstract fun shareImportDao(): ShareImportDao

    companion object {
        const val DATABASE_NAME = "stog.db"

        @Volatile private var instance: StogDatabase? = null

        fun get(context: Context): StogDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        fun build(context: Context, name: String = DATABASE_NAME): StogDatabase =
            Room.databaseBuilder(context, StogDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(ImmutablePayloadTriggers)
                .build()

        fun inMemory(context: Context): StogDatabase =
            Room.inMemoryDatabaseBuilder(context, StogDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(ImmutablePayloadTriggers)
                .build()

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PENDING_SET_LOG_TABLE_SQL)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_set_logs_account_id_user_id ON pending_set_logs (account_id, user_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_set_logs_account_id_trip_id_client_upload_id ON pending_set_logs (account_id, trip_id, client_upload_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_set_logs_account_id_trip_id_outbox_state_created_at ON pending_set_logs (account_id, trip_id, outbox_state, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_set_logs_account_id_outbox_state_created_at ON pending_set_logs (account_id, outbox_state, created_at)")
                db.execSQL(PENDING_SET_LOG_IMMUTABLE_TRIGGER_SQL)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visit_outbox ADD COLUMN remote_visit_id INTEGER")
                db.execSQL("ALTER TABLE visit_outbox ADD COLUMN review_required INTEGER")
                db.execSQL("ALTER TABLE visit_outbox ADD COLUMN review_consumed_at INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE share_import_candidates ADD COLUMN provider TEXT")
                db.execSQL("ALTER TABLE share_import_candidates ADD COLUMN external_id TEXT")
                db.execSQL("ALTER TABLE share_import_candidates ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE share_import_candidates ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE share_import_candidates ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE share_import_candidates ADD COLUMN canonical_place_id INTEGER")
                db.execSQL("ALTER TABLE share_import_candidates ADD COLUMN canonical_source_id INTEGER")
                db.execSQL("ALTER TABLE share_import_decisions ADD COLUMN trip_id INTEGER")
                db.execSQL("ALTER TABLE share_import_decisions ADD COLUMN client_item_id TEXT")
                db.execSQL("ALTER TABLE share_import_decisions ADD COLUMN payload_fingerprint TEXT")
                db.execSQL("DROP TRIGGER IF EXISTS share_import_candidate_immutable_payload")
                db.execSQL("""CREATE TRIGGER share_import_candidate_immutable_payload BEFORE UPDATE OF candidate_id, import_id, origin, title, address, original_url, candidate_order, provider, external_id, latitude, longitude, category, canonical_place_id, canonical_source_id ON share_import_candidates BEGIN SELECT RAISE(ABORT, 'share import candidate payload is immutable'); END""")
                db.execSQL("DROP TRIGGER IF EXISTS share_import_decision_immutable_payload")
                db.execSQL("""CREATE TRIGGER share_import_decision_immutable_payload BEFORE UPDATE OF candidate_id, import_id, decision, trip_id, client_item_id, payload_fingerprint, decided_at ON share_import_decisions BEGIN SELECT RAISE(ABORT, 'share import decision payload is immutable'); END""")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MEMBER_COLLECTION_TABLE_SQL)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_member_collection_states_account_id_user_id ON member_collection_states (account_id, user_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_member_collection_states_account_id_collector_state ON member_collection_states (account_id, collector_state)")
                db.execSQL(MEMBER_COLLECTION_IDENTITY_TRIGGER_SQL)
            }
        }

        internal fun resetForHostTest() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}

private const val PENDING_SET_LOG_TABLE_SQL = """CREATE TABLE IF NOT EXISTS `pending_set_logs` (`client_upload_id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `trip_id` INTEGER NOT NULL, `source` TEXT NOT NULL, `original_path` TEXT NOT NULL, `thumbnail_path` TEXT NOT NULL, `source_path` TEXT, `original_size` INTEGER NOT NULL, `thumbnail_size` INTEGER NOT NULL, `original_sha256` TEXT NOT NULL, `thumbnail_sha256` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `accuracy_m` REAL, `location_provenance` TEXT, `taken_at` TEXT, `caption` TEXT, `place_resolution_status` TEXT NOT NULL, `expected_place_id` INTEGER, `visibility` TEXT NOT NULL, `public_consent` INTEGER NOT NULL, `outbox_state` TEXT NOT NULL, `retry_class` TEXT NOT NULL, `upload_stage` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_response_code` INTEGER, `error_code` TEXT, `remote_photo_id` INTEGER, `publication_status` TEXT, `acknowledged_at` INTEGER, `cleanup_complete` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`client_upload_id`), FOREIGN KEY(`account_id`, `user_id`) REFERENCES `account_ownership`(`account_id`, `user_id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"""
private const val PENDING_SET_LOG_IMMUTABLE_TRIGGER_SQL = """CREATE TRIGGER IF NOT EXISTS pending_set_log_immutable_payload BEFORE UPDATE OF client_upload_id, account_id, user_id, trip_id, source, original_path, thumbnail_path, source_path, original_size, thumbnail_size, original_sha256, thumbnail_sha256, latitude, longitude, accuracy_m, location_provenance, taken_at, caption, place_resolution_status, expected_place_id, visibility, public_consent, created_at ON pending_set_logs BEGIN SELECT RAISE(ABORT, 'pending set log payload is immutable'); END"""

private const val MEMBER_COLLECTION_TABLE_SQL = """CREATE TABLE IF NOT EXISTS `member_collection_states` (`account_id` TEXT NOT NULL, `trip_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `collector_state` TEXT NOT NULL, `permission_state` TEXT NOT NULL, `mode_version` INTEGER NOT NULL, `trip_end_at` INTEGER, `battery_low` INTEGER NOT NULL, `active_cell` INTEGER, `active_lat` REAL, `active_lng` REAL, `entered_at` INTEGER, `candidate_cell` INTEGER, `candidate_count` INTEGER NOT NULL, `last_accepted_lat` REAL, `last_accepted_lng` REAL, `last_accepted_at` INTEGER, `last_transition_at` INTEGER, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `trip_id`, `user_id`), FOREIGN KEY(`account_id`, `user_id`) REFERENCES `account_ownership`(`account_id`, `user_id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"""
private const val MEMBER_COLLECTION_IDENTITY_TRIGGER_SQL = """CREATE TRIGGER IF NOT EXISTS member_collection_state_immutable_identity BEFORE UPDATE OF account_id, trip_id, user_id ON member_collection_states BEGIN SELECT RAISE(ABORT, 'member collection identity is immutable'); END"""

private object ImmutablePayloadTriggers : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        db.execSQL(MEMBER_COLLECTION_IDENTITY_TRIGGER_SQL)
        db.execSQL(PENDING_SET_LOG_IMMUTABLE_TRIGGER_SQL)
        db.execSQL(
            """CREATE TRIGGER account_ownership_immutable_identity
               BEFORE UPDATE OF account_id, user_id, created_at
               ON account_ownership BEGIN SELECT RAISE(ABORT, 'account ownership identity is immutable'); END""".trimIndent(),
        )
        db.execSQL(
            """CREATE TRIGGER visit_outbox_immutable_payload
               BEFORE UPDATE OF id, observation_id, account_id, trip_id, user_id, client_visit_id,
                 payload_fingerprint, cell_id, lat, lng, entered_at, left_at, status, is_interpolated, created_at
               ON visit_outbox BEGIN SELECT RAISE(ABORT, 'visit outbox payload is immutable'); END""".trimIndent(),
        )
        db.execSQL(
            """CREATE TRIGGER location_observation_immutable_payload
               BEFORE UPDATE OF observation_id, account_id, trip_id, user_id, cell_id, lat, lng, observed_at
               ON location_observations BEGIN SELECT RAISE(ABORT, 'location observation payload is immutable'); END""".trimIndent(),
        )
        db.execSQL(
            """CREATE TRIGGER pending_share_import_immutable_raw
               BEFORE UPDATE OF import_id, raw_directory_name, raw_text, raw_html, mime_type, source, created_at
               ON pending_share_imports BEGIN SELECT RAISE(ABORT, 'pending share import raw payload is immutable'); END""".trimIndent(),
        )
        db.execSQL(
            """CREATE TRIGGER pending_share_import_account_assignment_once
               BEFORE UPDATE OF account_id ON pending_share_imports
               WHEN OLD.account_id IS NOT NULL OR NEW.account_id IS NULL
               BEGIN SELECT RAISE(ABORT, 'pending share import account ownership is immutable after assignment'); END""".trimIndent(),
        )
        db.execSQL(
            """CREATE TRIGGER share_import_candidate_immutable_payload
               BEFORE UPDATE OF candidate_id, import_id, origin, title, address, original_url, candidate_order,
                 provider, external_id, latitude, longitude, category, canonical_place_id, canonical_source_id
               ON share_import_candidates BEGIN SELECT RAISE(ABORT, 'share import candidate payload is immutable'); END""".trimIndent(),
        )
        db.execSQL(
            """CREATE TRIGGER share_import_decision_immutable_payload
               BEFORE UPDATE OF candidate_id, import_id, decision, trip_id, client_item_id,
                 payload_fingerprint, decided_at
               ON share_import_decisions BEGIN SELECT RAISE(ABORT, 'share import decision payload is immutable'); END""".trimIndent(),
        )
    }
}
