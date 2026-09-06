package com.stog.app.feature.record

import android.app.Application
import com.stog.app.core.database.TransmissionOutcome
import com.stog.app.core.database.VisitOutboxEntity
import com.stog.app.core.database.VisitStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecordingApiClientTest {
    @Test
    fun activeCollectionPublicationUsesTodoFourOptimisticContract() {
        val sent = JSONObject(collectionStateRequestBody(CollectorState.ACTIVE, PermissionState.GRANTED, 0))
        assertEquals("active", sent.getString("collector_state"))
        assertEquals("granted", sent.getString("permission_state"))
        assertEquals(0, sent.getLong("expected_mode_version"))
        assertTrue(sent.isNull("sync_cursor"))
    }

    @Test
    fun immutableVisitUsesTodoFourWireContractAndTypedHttpOutcome() {
        val body = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/trips/7/visits") { exchange ->
            body.set(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            exchange.sendResponseHeaders(409, -1)
            exchange.close()
        }
        server.start()
        try {
            val visit = visit()
            val outcome = RecordingApiClient("http://127.0.0.1:${server.address.port}", 2_000)
                .sendVisit("token", visit)
            val sent = JSONObject(body.get())
            assertEquals(TransmissionOutcome.HttpFailure(409), outcome)
            assertEquals("8928308280fffff", sent.getString("cell_id"))
            assertEquals("1970-01-01T00:00:01Z", sent.getString("entered_at"))
            assertEquals(visit.payloadFingerprint, sent.getString("payload_fingerprint"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun successfulVisitParsesBackendReviewReceiptAndRejectsMalformedSuccess() {
        val visit = visit()
        val receipt = sendWithBody(
            200,
            """{"id":77,"client_visit_id":"${visit.clientVisitId}","review_required":true}""",
        )
        assertTrue(receipt is TransmissionOutcome.VisitAcknowledged)
        assertEquals(true, (receipt as TransmissionOutcome.VisitAcknowledged).receipt.reviewRequired)
        assertEquals(TransmissionOutcome.InvalidResponse, sendWithBody(200, "{}"))
    }

    @Test
    fun visitTransportPreservesNetworkRateLimitServerAndCoordinateMismatchClasses() {
        assertEquals(TransmissionOutcome.HttpFailure(429), sendWithStatus(429))
        assertEquals(TransmissionOutcome.HttpFailure(503), sendWithStatus(503))
        assertEquals(TransmissionOutcome.HttpFailure(400), sendWithStatus(400))

        val unavailable = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(
            TransmissionOutcome.NetworkFailure,
            RecordingApiClient("http://127.0.0.1:$unavailable", 200).sendVisit("token", visit()),
        )
    }

    @Test
    fun fingerprintMatchesBackendCanonicalFieldOrder() {
        val fingerprint = visitPayloadFingerprint(
            0x8928308280fffff,
            35.8,
            127.1,
            1_000,
            2_000,
            VisitStatus.VISITED,
            false,
        )
        assertEquals("f5166bc8a6f00aae921c2235a2db7c93eb22c334ae18107e84694087865f125d", fingerprint)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    private fun sendWithStatus(status: Int): TransmissionOutcome = sendWithBody(status, "")

    private fun sendWithBody(status: Int, body: String): TransmissionOutcome {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/trips/7/visits") { exchange ->
            exchange.requestBody.close()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }
        server.start()
        return try {
            RecordingApiClient("http://127.0.0.1:${server.address.port}", 2_000).sendVisit("token", visit())
        } finally {
            server.stop(0)
        }
    }

    private fun visit(): VisitOutboxEntity {
        val fingerprint = visitPayloadFingerprint(
            0x8928308280fffff,
            35.8,
            127.1,
            1_000,
            2_000,
            VisitStatus.VISITED,
            false,
        )
        return VisitOutboxEntity(
            observationId = "observation",
            accountId = "3",
            tripId = "7",
            userId = "3",
            clientVisitId = "45dc7db6-333a-49c7-ae5e-edf75605ca31",
            payloadFingerprint = fingerprint,
            cellId = 0x8928308280fffff,
            lat = 35.8,
            lng = 127.1,
            enteredAt = 1_000,
            leftAt = 2_000,
            status = VisitStatus.VISITED,
            isInterpolated = false,
            createdAt = 2_000,
        )
    }
}
