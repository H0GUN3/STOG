package com.stog.app.feature.profile

import android.app.Application
import com.stog.app.feature.record.PhotoHttpResponse
import com.stog.app.feature.record.PhotoHttpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileSurveyApiClientTest {
    @Test
    fun profileReadsBothScoreLayers() {
        val transport = RecordingTransport(
            response = """
                {
                  "preference_scores": {"food": 1.0},
                  "travel_style_scores": {"pace": 0.75}
                }
            """.trimIndent(),
        )

        val profile = ProfileApiClient("https://api.example", transport).profile("token")

        assertEquals(mapOf("food" to 1.0), profile.preferenceScores)
        assertEquals(mapOf("pace" to 0.75), profile.travelStyleScores)
        assertEquals("GET", transport.method)
        assertEquals("https://api.example/profile", transport.url)
    }

    @Test
    fun precisionSurveySendsVersionTypeAndAllAnswers() {
        val transport = RecordingTransport(
            response = """
                {
                  "survey_version": "v1",
                  "survey_type": "precision",
                  "canonical": true,
                  "preference_scores": {},
                  "travel_style_scores": {}
                }
            """.trimIndent(),
        )
        val answers = (1..13).associate { "q$it" to ((it - 1) % 5 + 1) }

        ProfileApiClient("https://api.example", transport)
            .submitPrecisionSurvey("token", answers)

        val body = JSONObject(checkNotNull(transport.body).toString(Charsets.UTF_8))
        assertEquals("PUT", transport.method)
        assertEquals("https://api.example/profile/survey", transport.url)
        assertEquals("v1", body.getString("survey_version"))
        assertEquals("precision", body.getString("survey_type"))
        val sentAnswers = body.getJSONObject("answers")
        assertEquals(13, sentAnswers.length())
        answers.forEach { (key, value) -> assertEquals(value, sentAnswers.getInt(key)) }
    }

    private class RecordingTransport(
        private val response: String,
    ) : PhotoHttpTransport {
        var method: String? = null
        var url: String? = null
        var body: ByteArray? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): PhotoHttpResponse {
            this.method = method
            this.url = url
            this.body = body
            return PhotoHttpResponse(200, response)
        }
    }
}
