package com.stog.app.feature.plan.share_import

import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.CandidateDecisionState
import com.stog.app.core.database.CandidateOrigin
import com.stog.app.core.database.ShareImportCandidateEntity
import com.stog.app.core.database.ShareImportDecisionEntity
import com.stog.app.core.database.ShareImportOutboxTransport
import com.stog.app.core.database.StogDatabase
import com.stog.app.core.database.TransmissionOutcome
import com.stog.app.core.database.TypedOutboxProcessor
import com.stog.app.core.database.VisitOutboxTransport
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.PlaceSearchProvenance
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.PlanningRequestException
import com.stog.app.feature.space.basketRequestPayload
import com.stog.app.feature.space.normalizedPlaceCategory
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

const val SHARE_REVIEW_PROMPT = "이 장소가 맞나요?"

enum class CandidateReviewDecision { PENDING, SELECTED, REJECTED }
enum class ProviderSearchStatus { IDLE, LOADING, EMPTY, LOADED, ERROR }

data class ShareReviewCandidate(
    val candidateId: String,
    val title: String,
    val address: String? = null,
    val origin: CandidateOrigin = CandidateOrigin.EXTRACTED,
    val decision: CandidateReviewDecision = CandidateReviewDecision.PENDING,
    val providerSearchStatus: ProviderSearchStatus = ProviderSearchStatus.IDLE,
    val providerMatches: List<PlaceSearchCandidate> = emptyList(),
    val selectedPlace: PlaceSearchCandidate? = null,
)

data class ShareReviewState(
    val importId: String,
    val intakePersisted: Boolean,
    val selectedTripId: Long? = null,
    val candidates: List<ShareReviewCandidate> = emptyList(),
    val saving: Boolean = false,
    private val providerGeneration: Long = 0,
) {
    fun selectTrip(tripId: Long): ShareReviewState {
        require(tripId > 0)
        if (selectedTripId == tripId) return this
        return copy(
            selectedTripId = tripId,
            saving = false,
            providerGeneration = providerGeneration + 1,
            candidates = candidates.map {
                it.copy(
                    decision = CandidateReviewDecision.PENDING,
                    providerSearchStatus = ProviderSearchStatus.IDLE,
                    providerMatches = emptyList(),
                    selectedPlace = null,
                )
            },
        )
    }

    fun beginProviderSearch(candidateId: String): ProviderSearchStart {
        check(intakePersisted) { "Provider search cannot run before share intake is durable" }
        val tripId = requireNotNull(selectedTripId) { "Trip selection is required before provider search" }
        val candidate = candidates.single { it.candidateId == candidateId }
        val query = candidate.title.trim().takeIf(String::isNotBlank)
        val generation = providerGeneration + 1
        val next = copyCandidate(candidateId) {
            it.copy(providerSearchStatus = if (query == null) ProviderSearchStatus.EMPTY else ProviderSearchStatus.LOADING)
        }.copy(providerGeneration = generation)
        return ProviderSearchStart(
            next,
            query?.let { ProviderSearchRequest(generation, importId, tripId, candidateId, it) },
        )
    }

    fun completeProviderSearch(request: ProviderSearchRequest, result: Result<List<PlaceSearchCandidate>>): ShareReviewState {
        if (request.generation != providerGeneration || request.importId != importId ||
            request.tripId != selectedTripId || candidates.none {
                it.candidateId == request.candidateId && it.title.trim() == request.query
            }
        ) return this
        return copyCandidate(request.candidateId) { candidate ->
            result.fold(
                onSuccess = { matches -> candidate.copy(
                    providerSearchStatus = if (matches.isEmpty()) ProviderSearchStatus.EMPTY else ProviderSearchStatus.LOADED,
                    providerMatches = matches,
                    selectedPlace = null,
                ) },
                onFailure = { candidate.copy(
                    providerSearchStatus = ProviderSearchStatus.ERROR,
                    providerMatches = emptyList(),
                    selectedPlace = null,
                ) },
            )
        }
    }

    fun cancelProviderSearch(): ShareReviewState = copy(
        providerGeneration = providerGeneration + 1,
        candidates = candidates.map { candidate ->
            if (candidate.providerSearchStatus == ProviderSearchStatus.LOADING) {
                candidate.copy(providerSearchStatus = ProviderSearchStatus.IDLE, providerMatches = emptyList())
            } else candidate
        },
    )

    fun select(candidateId: String, place: PlaceSearchCandidate): ShareReviewState = copyCandidate(candidateId) {
        require(place in it.providerMatches)
        it.copy(
            title = place.name,
            address = place.address,
            decision = CandidateReviewDecision.SELECTED,
            selectedPlace = place,
        )
    }

    fun reject(candidateId: String): ShareReviewState = copyCandidate(candidateId) {
        it.copy(decision = CandidateReviewDecision.REJECTED, selectedPlace = null)
    }.copy(providerGeneration = providerGeneration + 1)

    fun correct(candidateId: String, correctedName: String): ShareReviewState =
        correct(candidateId, correctedName, candidates.single { it.candidateId == candidateId }.address)

    fun correct(candidateId: String, correctedName: String, correctedAddress: String?): ShareReviewState =
        copyCandidate(candidateId) { candidate ->
            val name = correctedName.trim().also { require(it.isNotBlank()) }
            val address = correctedAddress?.trim()?.takeIf(String::isNotBlank)
            val correctedPlace = candidate.selectedPlace?.copy(name = name, address = address)
            if (correctedPlace == null) {
                candidate.copy(
                    title = name,
                    address = address,
                    origin = CandidateOrigin.CORRECTED,
                    decision = CandidateReviewDecision.PENDING,
                    providerSearchStatus = ProviderSearchStatus.IDLE,
                    providerMatches = emptyList(),
                    selectedPlace = null,
                )
            } else {
                candidate.copy(
                    title = name,
                    address = address,
                    origin = CandidateOrigin.CORRECTED,
                    providerMatches = candidate.providerMatches.map {
                        if (it == candidate.selectedPlace) correctedPlace else it
                    },
                    selectedPlace = correctedPlace,
                )
            }
        }.copy(providerGeneration = providerGeneration + 1)

    fun addManual(name: String): ShareReviewState {
        val title = name.trim().also { require(it.isNotBlank()) }
        return copy(candidates = candidates + ShareReviewCandidate(
            candidateId = "$importId:manual:${candidates.size}",
            title = title,
            origin = CandidateOrigin.MANUAL,
        ))
    }

    fun canSave(): Boolean = selectedTripId != null && candidates.isNotEmpty() &&
        candidates.all { it.decision != CandidateReviewDecision.PENDING } &&
        candidates.filter { it.decision == CandidateReviewDecision.SELECTED }
            .all { it.selectedPlace != null }

    private fun copyCandidate(
        candidateId: String,
        transform: (ShareReviewCandidate) -> ShareReviewCandidate,
    ): ShareReviewState = copy(candidates = candidates.map {
        if (it.candidateId == candidateId) transform(it) else it
    })
}

data class ProviderSearchRequest(
    val generation: Long,
    val importId: String,
    val tripId: Long,
    val candidateId: String,
    val query: String,
)

data class ProviderSearchStart(
    val state: ShareReviewState,
    val request: ProviderSearchRequest?,
)

class ShareReviewPersistence(
    private val database: StogDatabase,
    private val scheduleSync: (String, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun save(accountId: String, state: ShareReviewState) {
        check(state.canSave()) { "A trip and a final decision for every candidate are required" }
        val tripId = requireNotNull(state.selectedTripId)
        database.runInTransaction {
            if (database.accountOwnershipDao().find(accountId) == null) {
                database.accountOwnershipDao().insert(AccountOwnershipEntity(accountId, accountId, now()))
            }
            val dao = database.shareImportDao()
            check(dao.assignAccount(state.importId, accountId) in 0..1)
            val candidates = dao.candidates(state.importId)
                .associateBy { it.candidateId }
                .toMutableMap()
            val decisions = dao.decisions(state.importId)
                .associateBy { it.candidateId }
                .toMutableMap()
            var nextOrder = (candidates.values.maxOfOrNull { it.candidateOrder } ?: -1) + 1

            fun ensureCandidate(entity: ShareImportCandidateEntity) {
                val existing = candidates[entity.candidateId]
                if (existing == null) {
                    dao.insertCandidate(entity)
                    candidates[entity.candidateId] = entity
                } else {
                    check(existing.copy(candidateOrder = entity.candidateOrder) == entity) {
                        "Share candidate replay changed persisted content"
                    }
                }
            }

            fun ensureDecision(entity: ShareImportDecisionEntity) {
                val existing = decisions[entity.candidateId]
                if (existing == null) {
                    dao.insertDecision(entity)
                    decisions[entity.candidateId] = entity
                } else {
                    check(
                        existing.importId == entity.importId &&
                            existing.decision == entity.decision &&
                            existing.tripId == entity.tripId &&
                            existing.clientItemId == entity.clientItemId &&
                            existing.payloadFingerprint == entity.payloadFingerprint,
                    ) {
                        "Share decision replay changed persisted intent"
                    }
                }
            }

            state.candidates.forEach { review ->
                val existing = candidates[review.candidateId]
                if (review.decision == CandidateReviewDecision.REJECTED) {
                    val rejected = existing ?: review.toEntity(state.importId, nextOrder++, null)
                    ensureCandidate(rejected)
                    ensureDecision(
                        ShareImportDecisionEntity(
                            candidateId = rejected.candidateId,
                            importId = state.importId,
                            decision = CandidateDecisionState.REJECTED,
                            decidedAt = now(),
                        ),
                    )
                    return@forEach
                }

                val place = requireNotNull(review.selectedPlace)
                val existingDecision = decisions[review.candidateId]
                val resolvedId = if (
                    existing == null ||
                    existingDecision?.decision == CandidateDecisionState.CONFIRMED
                ) {
                    review.candidateId
                } else {
                    "${review.candidateId}:resolved"
                }
                if (existing != null && existingDecision == null) {
                    ensureDecision(
                        ShareImportDecisionEntity(
                            candidateId = existing.candidateId,
                            importId = state.importId,
                            decision = CandidateDecisionState.REJECTED,
                            decidedAt = now(),
                        ),
                    )
                }
                val resolved = review.toEntity(state.importId, nextOrder++, place, resolvedId)
                ensureCandidate(resolved)
                val clientItemId = UUID.nameUUIDFromBytes(
                    "${state.importId}:$resolvedId".toByteArray(StandardCharsets.UTF_8),
                ).toString()
                val payload = basketRequestPayload(tripId, place, clientItemId)
                ensureDecision(
                    ShareImportDecisionEntity(
                        candidateId = resolvedId,
                        importId = state.importId,
                        decision = CandidateDecisionState.CONFIRMED,
                        tripId = tripId,
                        clientItemId = clientItemId,
                        payloadFingerprint = payload.payloadFingerprint,
                        decidedAt = now(),
                    ),
                )
            }
            if (state.candidates.none { it.decision == CandidateReviewDecision.SELECTED }) {
                dao.completeAllRejected(state.importId)
            }
        }
        scheduleSync(accountId, tripId)
    }
}

class ConfirmedShareHttpTransport(
    private val client: PlanningApiClient,
    private val accessToken: String,
) : ShareImportOutboxTransport {
    override fun send(
        decision: ShareImportDecisionEntity,
        candidate: ShareImportCandidateEntity,
    ): TransmissionOutcome = try {
        val tripId = requireNotNull(decision.tripId)
        val clientItemId = requireNotNull(decision.clientItemId)
        val fingerprint = requireNotNull(decision.payloadFingerprint)
        client.addConfirmedShareToBasket(
            accessToken,
            tripId,
            clientItemId,
            fingerprint,
            candidate.toPlaceSearchCandidate(),
        )
        TransmissionOutcome.Acknowledged
    } catch (error: PlanningRequestException) {
        TransmissionOutcome.HttpFailure(error.statusCode)
    } catch (_: IOException) {
        TransmissionOutcome.NetworkFailure
    } catch (_: IllegalArgumentException) {
        TransmissionOutcome.HttpFailure(400)
    }
}

class ShareImportSyncRunner(
    private val database: StogDatabase,
    private val rawRoot: File,
    transport: ShareImportOutboxTransport,
) {
    private val processor = TypedOutboxProcessor(
        database,
        VisitOutboxTransport { error("Visit transport is not used by share sync") },
        transport,
    )

    fun run(accountId: String, tripId: Long? = null): Boolean {
        val retry = processor.processShareImports(accountId, tripId)
        cleanupAcknowledged(accountId)
        return retry
    }

    fun cleanupAcknowledged(accountId: String) {
        database.shareImportDao().completed(accountId).forEach { completed ->
            val directory = File(rawRoot, completed.rawDirectoryName)
            if ((!directory.exists() || directory.deleteRecursively()) &&
                database.shareImportDao().deleteCompleted(completed.importId) != 1
            ) {
                error("Completed share import cleanup lost its durable acknowledgement")
            }
        }
    }
}

private fun ShareReviewCandidate.toEntity(
    importId: String,
    order: Int,
    place: PlaceSearchCandidate?,
    id: String = candidateId,
): ShareImportCandidateEntity = ShareImportCandidateEntity(
    candidateId = id,
    importId = importId,
    origin = origin,
    title = place?.name ?: title,
    address = place?.address ?: address,
    originalUrl = null,
    candidateOrder = order,
    provider = place?.providerName(),
    externalId = place?.externalId,
    latitude = place?.latitude,
    longitude = place?.longitude,
    category = place?.let { normalizedPlaceCategory(it.types) },
    canonicalPlaceId = (place?.provenance as? PlaceSearchProvenance.Canonical)?.placeId,
    canonicalSourceId = (place?.provenance as? PlaceSearchProvenance.Canonical)?.sourceId,
)

private fun PlaceSearchCandidate.providerName(): String = when (provenance) {
    is PlaceSearchProvenance.Canonical -> "canonical"
    PlaceSearchProvenance.GoogleFallback -> "google"
}

private fun ShareImportCandidateEntity.toPlaceSearchCandidate(): PlaceSearchCandidate {
    val provenance = if (provider == "canonical") {
        PlaceSearchProvenance.Canonical(
            placeId = requireNotNull(canonicalPlaceId),
            sourceType = "persisted",
            sourceId = requireNotNull(canonicalSourceId),
            catalogStatus = "public",
        )
    } else {
        PlaceSearchProvenance.GoogleFallback
    }
    return PlaceSearchCandidate(
        externalId = requireNotNull(externalId),
        name = title,
        address = address,
        latitude = requireNotNull(latitude),
        longitude = requireNotNull(longitude),
        types = listOf(category ?: "place"),
        provenance = provenance,
    )
}
