package com.stog.app.feature.record

import com.stog.app.core.database.TransmissionOutcome
import com.stog.app.core.database.VisitOutboxEntity
import com.stog.app.core.database.VisitReviewReceipt
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class RemoteCollectionState(
    val collectorState: CollectorState,
    val permissionState: PermissionState,
    val modeVersion: Long,
)

class RecordingApiClient(
    private val baseUrl: String,
    private val timeoutMillis: Int,
) {
    fun updateCollectionState(
        accessToken: String,
        tripId: String,
        state: CollectorState,
        permissionState: PermissionState,
        modeVersion: Long,
    ): RemoteCollectionState {
        val response = request(
            accessToken = accessToken,
            endpoint = "/trips/$tripId/collection-state",
            method = "PATCH",
            body = collectionStateRequestBody(state, permissionState, modeVersion),
        )
        val json = JSONObject(response.body)
        return RemoteCollectionState(
            collectorState = CollectorState.valueOf(json.getString("collector_state").uppercase()),
            permissionState = PermissionState.valueOf(json.getString("permission_state").uppercase()),
            modeVersion = json.getLong("mode_version"),
        )
    }

    fun sendVisit(accessToken: String, visit: VisitOutboxEntity): TransmissionOutcome = try {
        val response = request(
            accessToken = accessToken,
            endpoint = "/trips/${visit.tripId}/visits",
            method = "POST",
            body = visitRequestBody(visit),
            throwOnHttpFailure = false,
        )
        if (response.statusCode in 200..299) {
            parseVisitReceipt(response.body, visit)
        } else {
            TransmissionOutcome.HttpFailure(response.statusCode)
        }
    } catch (_: IOException) {
        TransmissionOutcome.NetworkFailure
    }

    private fun request(
        accessToken: String,
        endpoint: String,
        method: String,
        body: String,
        throwOnHttpFailure: Boolean = true,
    ): HttpResponse {
        val connection = URL("${baseUrl.trimEnd('/')}$endpoint").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (throwOnHttpFailure && status !in 200..299) throw RecordingHttpException(status)
            return HttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResponse(val statusCode: Int, val body: String)

    private fun parseVisitReceipt(body: String, visit: VisitOutboxEntity): TransmissionOutcome = try {
        val json = JSONObject(body)
        val clientVisitId = json.getString("client_visit_id")
        if (clientVisitId != visit.clientVisitId) {
            TransmissionOutcome.InvalidResponse
        } else {
            TransmissionOutcome.VisitAcknowledged(
                VisitReviewReceipt(
                    remoteVisitId = json.getLong("id"),
                    clientVisitId = clientVisitId,
                    reviewRequired = json.getBoolean("review_required"),
                ),
            )
        }
    } catch (_: org.json.JSONException) {
        TransmissionOutcome.InvalidResponse
    }

}

class RecordingHttpException(val statusCode: Int) : IOException("Recording request failed: $statusCode")

internal fun collectionStateRequestBody(
    state: CollectorState,
    permissionState: PermissionState,
    modeVersion: Long,
): String = JSONObject()
    .put("collector_state", state.name.lowercase())
    .put("permission_state", permissionState.name.lowercase())
    .put("sync_cursor", JSONObject.NULL)
    .put("expected_mode_version", modeVersion)
    .toString()

internal fun visitRequestBody(visit: VisitOutboxEntity): String = JSONObject()
    .put("client_visit_id", visit.clientVisitId)
    .put("payload_fingerprint", visit.payloadFingerprint)
    .put("cell_id", visit.cellId.toString(16))
    .put("lat", visit.lat)
    .put("lng", visit.lng)
    .put("entered_at", recordingInstant(visit.enteredAt))
    .put("left_at", recordingInstant(visit.leftAt))
    .put("status", visit.status.name.lowercase())
    .put("is_interpolated", visit.isInterpolated)
    .toString()
