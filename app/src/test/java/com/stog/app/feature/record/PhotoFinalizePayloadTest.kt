package com.stog.app.feature.record

import android.app.Application
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoFinalizePayloadTest {
    @Test
    fun unresolvedPlacePreviewIsOmittedFromFinalizeRequest() {
        val transport = FinalizeTransport()
        val client = PhotoApiClient("https://api.test", transport)
        val confirmed = ConfirmedSetLogUpload(
            photo = PreparedPhoto(
                id = "11111111-1111-1111-1111-111111111111",
                source = PhotoSource.CAMERA,
                tripId = 9,
                normalizedOriginalPath = "original.jpg",
                thumbnailPath = "thumbnail.jpg",
                normalizedOriginalBytes = 100,
                thumbnailBytes = 50,
                originalSha256 = "a".repeat(64),
                thumbnailSha256 = "b".repeat(64),
                coordinates = PhotoCoordinates(35.8, 127.1),
                takenAt = "2026-08-25T00:00:00Z",
                caption = null,
            ),
            accuracyMeters = 7.5,
            locationProvenance = SetLogLocationProvenance.CAMERA_FOREGROUND,
            placeResolutionStatus = "pending",
            expectedPlaceId = null,
            visibility = SetLogVisibilityIntent.PRIVATE,
            publicConsent = false,
        )

        val result = client.finalize(
            "access",
            confirmed,
            PhotoUploadUrls(
                originalObjectKey = "original",
                originalUploadUrl = "https://storage/original",
                thumbnailObjectKey = "thumbnail",
                thumbnailUploadUrl = "https://storage/thumbnail",
                originalUploadHeaders = emptyMap(),
                thumbnailUploadHeaders = emptyMap(),
            ),
        )

        assertEquals(71L, result.id)
        val body = requireNotNull(transport.body)
        assertTrue(body.isNull("place_resolution_status"))
        assertTrue(body.isNull("expected_place_id"))
    }

    private class FinalizeTransport : PhotoHttpTransport {
        var body: JSONObject? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): PhotoHttpResponse {
            if (method != "POST" || url != "https://api.test/photos") {
                throw IOException("unexpected request")
            }
            this.body = JSONObject(requireNotNull(body).decodeToString())
            return PhotoHttpResponse(
                200,
                """{"id":71,"user_id":7,"source":"camera","publication_status":"private"}""",
            )
        }
    }
}
