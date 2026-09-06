package com.stog.app.feature.plan.travel_guide_ai

import android.app.Application
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TravelGuideAiGoldenTraceTest {
    @Test
    fun canonicalFixtureHashesAndStableTraceIdsMatch() {
        val fixtures = canonicalFixtures()

        assertEquals(EXPECTED_FIXTURE_SHA256, fixtures.associate { it.file.name to it.sha256 })
        assertEquals(EXPECTED_TRACE_IDS, fixtures.associate { it.outcome to it.traceId })
        fixtures.forEach { trace ->
            assertEquals(
                setOf(
                    "trace_id",
                    "schema_version",
                    "base_version",
                    "proposal_fingerprint",
                    "client_apply_id",
                    "given",
                    "when",
                    "expected",
                ),
                trace.root.keys().asSequence().toSet(),
            )
            assertEquals("stobee-ai-golden-trace/v1", trace.root.getString("schema_version"))
            assertTrue(trace.fingerprint.matches(FINGERPRINT_PATTERN))
            assertCanonicalUuid(trace.clientApplyId)
        }
    }

    @Test
    fun happyTraceRequiresSelectedPreviewApplyingAppliedSequence() {
        val previewTrace = trace("PROPOSAL_READY")
        val applyTrace = trace("APPLIED")
        assertEquals(previewTrace.fingerprint, applyTrace.fingerprint)
        assertEquals(previewTrace.clientApplyId, applyTrace.clientApplyId)
        val contract = AndroidTraceContract()

        contract.select(previewTrace.tripId)
        val selected = contract.state
        contract.preview(previewTrace.proposal())
        val preview = contract.state
        contract.apply(previewTrace.clientApplyId)
        val applying = contract.state
        contract.receipt(applyTrace.receipt(previewTrace.proposalId))
        val applied = contract.state

        assertTrue(selected is TravelGuideAiUiState.Ready)
        assertTrue(preview is TravelGuideAiUiState.Preview)
        assertTrue(applying is TravelGuideAiUiState.Applying)
        assertTrue(applied is TravelGuideAiUiState.Applied)
        assertEquals(listOf("selected", "preview", "applying", "applied"), contract.sequence)
        assertFalse(selected.itineraryPersisted)
        assertFalse(preview.itineraryPersisted)
        assertFalse(applying.itineraryPersisted)
        assertTrue(applied.itineraryPersisted)
    }

    @Test
    fun dismissalIsLocalAndNeverClaimsPersistence() {
        val previewTrace = trace("PROPOSAL_READY")
        val dismissalTrace = trace("DISMISSED_LOCAL")
        val contract = AndroidTraceContract()
        contract.select(previewTrace.tripId)
        contract.preview(previewTrace.proposal())
        val callsBeforeDismiss = contract.remoteCalls

        contract.dismiss()

        assertTrue(contract.state is TravelGuideAiUiState.Ready)
        assertFalse(contract.state.itineraryPersisted)
        assertEquals(callsBeforeDismiss, contract.remoteCalls)
        assertEquals("none", dismissalTrace.expected.getString("server_write"))
        assertFalse(dismissalTrace.expected.getBoolean("itinerary_persisted"))
    }

    @Test
    fun staleTripAndMalformedFingerprintAreRejectedWithoutPersistence() {
        val readyTrace = trace("PROPOSAL_READY")
        val staleTrace = trace("REJECTED_STALE_VERSION")
        val selected = AndroidTraceContract().apply { select(readyTrace.tripId) }
        val staleTripProposal = readyTrace.proposal().copy(tripId = readyTrace.tripId + 1)

        selected.preview(staleTripProposal)

        assertTrue(selected.state is TravelGuideAiUiState.Ready)
        assertFalse(selected.state.itineraryPersisted)
        assertEquals(readyTrace.tripId, selected.state.tripId)
        assertEquals("rejected", selected.sequence.last())
        assertFalse(staleTrace.expected.getBoolean("itinerary_persisted"))

        val malformed = AndroidTraceContract().apply { select(readyTrace.tripId) }
        malformed.preview(readyTrace.proposal().copy(fingerprint = "not-a-fingerprint"))

        assertTrue(malformed.state is TravelGuideAiUiState.Error)
        assertFalse(malformed.state.itineraryPersisted)
        assertEquals("PROPOSAL_RESPONSE_INVALID", (malformed.state as TravelGuideAiUiState.Error).message)
    }

    @Test
    fun timeoutRetryReusesOriginalClientUuidAndChangedUuidIsRejected() {
        val previewTrace = trace("PROPOSAL_READY")
        val timeoutTrace = trace("AMBIGUOUS_TIMEOUT")
        val changedUuid = UUID.nameUUIDFromBytes(
            "${timeoutTrace.clientApplyId}:changed".toByteArray(Charsets.UTF_8),
        ).toString()
        assertNotEquals(timeoutTrace.clientApplyId, changedUuid)
        val changedRetry = applyingContract(previewTrace, timeoutTrace.clientApplyId)

        changedRetry.fail("NETWORK_UNAVAILABLE")
        val ambiguous = changedRetry.state as TravelGuideAiUiState.Error
        changedRetry.apply(changedUuid)

        assertFalse(ambiguous.itineraryPersisted)
        assertEquals(timeoutTrace.clientApplyId, ambiguous.clientApplyId)
        assertEquals(ambiguous, changedRetry.state)
        assertFalse(changedRetry.state.itineraryPersisted)

        val originalRetry = applyingContract(previewTrace, timeoutTrace.clientApplyId)
        originalRetry.fail("NETWORK_UNAVAILABLE")
        originalRetry.apply(timeoutTrace.clientApplyId)

        assertTrue(originalRetry.state is TravelGuideAiUiState.Applying)
        assertEquals(
            timeoutTrace.clientApplyId,
            (originalRetry.state as TravelGuideAiUiState.Applying).clientApplyId,
        )
        assertFalse(originalRetry.state.itineraryPersisted)
    }

    @Test
    fun networkParseAndMismatchedReceiptNeverExposePersistedState() {
        val readyTrace = trace("PROPOSAL_READY")
        val contractErrors = listOf("NETWORK_UNAVAILABLE", "PROPOSAL_RESPONSE_INVALID")
        contractErrors.forEach { code ->
            val contract = AndroidTraceContract().apply { select(readyTrace.tripId) }
            contract.fail(code)
            assertTrue(contract.state is TravelGuideAiUiState.Error)
            assertFalse(contract.state.itineraryPersisted)
        }

        val applying = applyingContract(readyTrace, readyTrace.clientApplyId)
        applying.receipt(
            TravelGuideAiApplied(
                suggestionId = "${readyTrace.proposalId}-mismatch",
                tripId = readyTrace.tripId,
                itineraryVersion = readyTrace.baseVersion + 1,
                itineraryChangeId = 1,
            ),
        )

        assertTrue(applying.state is TravelGuideAiUiState.Applying)
        assertFalse(applying.state.itineraryPersisted)

        listOf("PROVIDER_FAILURE", "MODEL_FAILURE", "AMBIGUOUS_TIMEOUT").forEach { outcome ->
            val trace = trace(outcome)
            assertFalse(trace.expected.getBoolean("itinerary_persisted"))
            assertEquals("none", trace.expected.getString("server_write"))
        }
    }

    @Test
    fun malformedUuidAndUnknownTraceAreRejectedDeterministically() {
        val readyTrace = trace("PROPOSAL_READY")
        val contract = AndroidTraceContract().apply {
            select(readyTrace.tripId)
            preview(readyTrace.proposal())
        }

        contract.apply("not-a-uuid")
        val unknown = runCatching { trace("NOT_A_CANONICAL_OUTCOME") }.exceptionOrNull()

        assertTrue(contract.state is TravelGuideAiUiState.Error)
        assertFalse(contract.state.itineraryPersisted)
        assertEquals("CLIENT_APPLY_ID_INVALID", (contract.state as TravelGuideAiUiState.Error).message)
        assertTrue(unknown is IllegalArgumentException)
    }

    private fun applyingContract(trace: GoldenTrace, clientApplyId: String) =
        AndroidTraceContract().apply {
            select(trace.tripId)
            preview(trace.proposal())
            apply(clientApplyId)
        }

    private fun trace(outcome: String): GoldenTrace = canonicalFixtures().singleOrNull {
        it.outcome == outcome
    } ?: throw IllegalArgumentException("Unknown canonical outcome: $outcome")

    private fun canonicalFixtures(): List<GoldenTrace> {
        val goldenDirectory = repositoryRoot().resolve("docs/contracts/stobee-ai/golden")
        check(goldenDirectory.isDirectory) {
            "Canonical golden fixtures are missing: ${goldenDirectory.path}"
        }
        return goldenDirectory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy(File::getName)
            .map { file -> GoldenTrace(file, JSONObject(file.readText()), file.sha256()) }
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            if (candidate.resolve("docs/architecture.yaml").isFile &&
                candidate.resolve("app/build.gradle.kts").isFile
            ) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        error("Repository root was not found")
    }

    private fun assertCanonicalUuid(value: String) {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull()
        assertEquals(value, parsed?.toString())
    }

    private data class GoldenTrace(
        val file: File,
        val root: JSONObject,
        val sha256: String,
    ) {
        val traceId: String = root.getString("trace_id")
        val outcome: String = root.getJSONObject("expected").getString("outcome")
        val tripId: Long = root.getJSONObject("given").getLong("trip_id")
        val baseVersion: Long = root.getLong("base_version")
        val fingerprint: String = root.getString("proposal_fingerprint")
        val clientApplyId: String = root.getString("client_apply_id")
        val expected: JSONObject = root.getJSONObject("expected")
        val proposalId: String = UUID.nameUUIDFromBytes(
            "proposal:$fingerprint".toByteArray(Charsets.UTF_8),
        ).toString()

        fun proposal(): TravelGuideAiProposal {
            val schedule = expected.getJSONArray("schedule_after")
            val actions = schedule.objects().mapIndexed { index, item ->
                TravelGuideAiAction(
                    actionOrder = index,
                    basketItemId = item.getLong("basket_item_id"),
                    dayNumber = item.getInt("day_number"),
                    orderIndex = item.getInt("order_index"),
                    plannedArrival = item.getString("planned_arrival"),
                    plannedDurationMin = item.getInt("planned_duration_min"),
                    travelMinutesFromPrevious = 0,
                    fixed = item.getBoolean("is_fixed"),
                )
            }
            return TravelGuideAiProposal(
                suggestionId = proposalId,
                tripId = tripId,
                baseVersion = baseVersion,
                status = "ready",
                fingerprint = fingerprint,
                feasible = true,
                actions = actions,
                fixedBasketItemIds = actions.filter(TravelGuideAiAction::fixed)
                    .map(TravelGuideAiAction::basketItemId),
                excludedUnresolvedBasketItemIds = emptyList(),
                violations = emptyList(),
            )
        }

        fun receipt(expectedProposalId: String): TravelGuideAiApplied {
            val receipt = expected.getJSONObject("apply_receipt")
            assertEquals(fingerprint, receipt.getString("proposal_fingerprint"))
            assertEquals(clientApplyId, receipt.getString("client_apply_id"))
            return TravelGuideAiApplied(
                suggestionId = expectedProposalId,
                tripId = tripId,
                itineraryVersion = receipt.getLong("itinerary_version"),
                itineraryChangeId = receipt.getLong("itinerary_change_id"),
            )
        }
    }

    private class AndroidTraceContract {
        var state: TravelGuideAiUiState = TravelGuideAiUiState.Idle
            private set
        val sequence = mutableListOf<String>()
        var remoteCalls: Int = 0
            private set
        private var originalClientApplyId: String? = null

        fun select(tripId: Long) {
            state = TravelGuideAiState.selectTrip(state, tripId, "canonical-$tripId")
            sequence += "selected"
        }

        fun preview(proposal: TravelGuideAiProposal) {
            remoteCalls += 1
            if (!proposal.fingerprint.matches(FINGERPRINT_PATTERN)) {
                state = TravelGuideAiState.failed(state, false, "PROPOSAL_RESPONSE_INVALID")
                sequence += "rejected"
                return
            }
            val next = TravelGuideAiState.previewSucceeded(state, proposal)
            state = next
            sequence += if (next is TravelGuideAiUiState.Preview) "preview" else "rejected"
        }

        fun dismiss() {
            val current = state
            if (current is TravelGuideAiUiState.Preview) {
                state = TravelGuideAiUiState.Ready(current.tripId, current.tripTitle)
                sequence += "dismissed"
            }
        }

        fun apply(clientApplyId: String) {
            if (!clientApplyId.isCanonicalUuid()) {
                state = TravelGuideAiState.failed(state, false, "CLIENT_APPLY_ID_INVALID")
                sequence += "rejected"
                return
            }
            if (originalClientApplyId != null && originalClientApplyId != clientApplyId) {
                sequence += "rejected"
                return
            }
            val next = TravelGuideAiState.applyStarted(state, clientApplyId)
            if (next is TravelGuideAiUiState.Applying) {
                state = next
                originalClientApplyId = clientApplyId
                remoteCalls += 1
                sequence += "applying"
            }
        }

        fun fail(code: String) {
            state = TravelGuideAiState.failed(state, false, code)
            sequence += "error"
        }

        fun receipt(result: TravelGuideAiApplied) {
            val current = state
            if (current !is TravelGuideAiUiState.Applying ||
                result.tripId != current.tripId ||
                result.suggestionId != current.proposal.suggestionId
            ) {
                sequence += "rejected"
                return
            }
            state = TravelGuideAiState.applySucceeded(current, result)
            sequence += "applied"
        }
    }

    private companion object {
        val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")

        val EXPECTED_FIXTURE_SHA256 = mapOf(
            "01-complete-replacement-preview.json" to
                "369b7fad809e5eba70569bdd975b195d971ea56c12d8c0d8c5a8ab1815bc68a0",
            "02-explicit-apply-receipt.json" to
                "ff36406243af8ee0c093921aae6b273ca93856943bc2e1ade2b88fd495ea277e",
            "03-local-dismissal.json" to
                "451c661e0c40f034118606a6fdc5124e9d9bb64ec40b2d8ee7d2f1c960f112f7",
            "04-stale-version.json" to
                "1dc70cec5cdf167551856c16a302c10c1700a5c318bac7be9ecd89faef366851",
            "05-nonmember.json" to
                "2afe0318f2170fd8969c99ff2e20948737f4d63d313b1f64d8c9c2ec38d4d9db",
            "06-cross-trip-target.json" to
                "aa2020dc5c3a1dad571f57b6e52d72e84f4328ee5a7f97b9963a09e514e150b6",
            "07-cross-trip-candidate.json" to
                "a4c76173294f8bd87c80e64634fc84e339f42c00ac64bd9fd89ae34a44bc689c",
            "08-missing-target.json" to
                "f81a9be61fa5b5874bc0233d7faf10652f25fe5c38f8e26a905fb61cdbef1b6f",
            "09-duplicate-target.json" to
                "c33030888c60861c60139b173b1cb641e215a40c084f75479fbb3061a509d2a2",
            "10-unresolved-candidate.json" to
                "24d6a5bf6b1715fe1a62170e2f78406356913294eeef485eb196d5f8344685be",
            "11-empty-schedule.json" to
                "198374773677b81a3ce0584efdd2bd2f37245d01466fa71224347b80db162bda",
            "12-incomplete-schedule.json" to
                "0792849cfbd8151dfb7b79ee83c7cc796a126f5df8f4f3df9a41d4ed0371429c",
            "13-provider-failure.json" to
                "148b4753fe9c9149f761500e9294a79ef41899e5cfca3a3790fecb044f1ffd29",
            "14-model-failure.json" to
                "4038fa8dda5890ee86b81ce8c9583d2d475c3c94746dabc01a234c906a3b5876",
            "15-ambiguous-timeout.json" to
                "d271d734a9ac155423944a410e41071922b6dec485837f2c5ceb75f485d1831f",
        )

        val EXPECTED_TRACE_IDS = mapOf(
            "PROPOSAL_READY" to "stobee-ai-01-complete-replacement-preview",
            "APPLIED" to "stobee-ai-02-explicit-apply-receipt",
            "DISMISSED_LOCAL" to "stobee-ai-03-local-dismissal",
            "REJECTED_STALE_VERSION" to "stobee-ai-04-stale-version",
            "REJECTED_FORBIDDEN" to "stobee-ai-05-nonmember",
            "REJECTED_CROSS_TRIP_TARGET" to "stobee-ai-06-cross-trip-target",
            "REJECTED_CROSS_TRIP_CANDIDATE" to "stobee-ai-07-cross-trip-candidate",
            "REJECTED_TARGET_MISSING" to "stobee-ai-08-missing-target",
            "REJECTED_TARGET_DUPLICATE" to "stobee-ai-09-duplicate-target",
            "REJECTED_CANDIDATE_UNRESOLVED" to "stobee-ai-10-unresolved-candidate",
            "REJECTED_SCHEDULE_EMPTY" to "stobee-ai-11-empty-schedule",
            "REJECTED_SCHEDULE_INCOMPLETE" to "stobee-ai-12-incomplete-schedule",
            "PROVIDER_FAILURE" to "stobee-ai-13-provider-failure",
            "MODEL_FAILURE" to "stobee-ai-14-model-failure",
            "AMBIGUOUS_TIMEOUT" to "stobee-ai-15-ambiguous-timeout",
        )
    }
}

private fun JSONArray.objects(): List<JSONObject> =
    List(length()) { index -> getJSONObject(index) }

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(readBytes())
    .joinToString("") { byte -> "%02x".format(byte) }
