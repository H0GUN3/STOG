package com.stog.app.feature.profile

import com.stog.app.feature.record.PhotoApiClient
import com.stog.app.feature.record.PhotoHttpTransport
import com.stog.app.feature.record.UrlConnectionPhotoTransport
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

internal data class ProfileSummary(
    val userId: Long,
    val nickname: String,
    val profileImageUrl: String?,
    val tripCount: Long,
    val visitedCellCount: Long,
    val photoCount: Long,
    val honeyBalance: Long,
)

internal data class ProfileScores(
    val preferenceScores: Map<String, Double>,
    val travelStyleScores: Map<String, Double>,
)

internal data class ProfileSurveyResult(
    val surveyVersion: String,
    val surveyType: String,
    val canonical: Boolean,
    val preferenceScores: Map<String, Double>,
    val travelStyleScores: Map<String, Double>,
)

internal data class ProfileAvatarUpload(
    val clientUploadId: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val objectKey: String,
    val uploadUrl: String,
    val uploadHeaders: Map<String, String>,
)

internal class ProfileRequestException(
    val statusCode: Int,
    val code: String,
    val path: String,
    val responseBody: String,
) : IOException("$code ($statusCode) $path")

internal class ProfileUploadException(
    val stage: String,
    cause: Throwable,
) : IOException("PROFILE_AVATAR_$stage failed", cause)

internal class ProfileApiClient(
    private val baseUrl: String,
    private val transport: PhotoHttpTransport = UrlConnectionPhotoTransport(),
) {
    fun summary(accessToken: String): ProfileSummary =
        JSONObject(request(accessToken, "/profile/summary", "GET"))
            .let { value ->
                ProfileSummary(
                    userId = value.getLong("user_id"),
                    nickname = value.getString("nickname"),
                    profileImageUrl = value.optionalString("profile_image_url"),
                    tripCount = value.getLong("trip_count"),
                    visitedCellCount = value.getLong("visited_cell_count"),
                    photoCount = value.getLong("photo_count"),
                    honeyBalance = value.getLong("honey_balance"),
                )
            }

    fun profile(accessToken: String): ProfileScores =
        JSONObject(request(accessToken, "/profile", "GET")).let { value ->
            ProfileScores(
                preferenceScores = value.doubleMap("preference_scores"),
                travelStyleScores = value.doubleMap("travel_style_scores"),
            )
        }

    fun submitPrecisionSurvey(
        accessToken: String,
        answers: Map<String, Int>,
    ): ProfileSurveyResult {
        val answerPayload = JSONObject()
        answers.forEach { (key, value) -> answerPayload.put(key, value) }
        return JSONObject(
            request(
                accessToken,
                "/profile/survey",
                "PUT",
                JSONObject()
                    .put("survey_version", "v1")
                    .put("survey_type", "precision")
                    .put("answers", answerPayload)
                    .toString(),
            ),
        ).let { value ->
            ProfileSurveyResult(
                surveyVersion = value.getString("survey_version"),
                surveyType = value.getString("survey_type"),
                canonical = value.getBoolean("canonical"),
                preferenceScores = value.doubleMap("preference_scores"),
                travelStyleScores = value.doubleMap("travel_style_scores"),
            )
        }
    }

    fun uploadAvatar(accessToken: String, file: File): ProfileSummary {
        val uploadId = UUID.randomUUID().toString()
        val sizeBytes = file.length()
        val sha256 = file.sha256()
        val upload = profileUploadStage("ISSUE_URLS") {
            issueAvatarUpload(accessToken, uploadId, sizeBytes, sha256)
        }
        profileUploadStage("DIRECT_PUT") {
            PhotoApiClient(baseUrl, transport).putDirectly(
                upload.uploadUrl,
                upload.uploadHeaders,
                file,
            )
        }
        profileUploadStage("FINALIZE") {
            request(
                accessToken,
                "/profile/avatar/finalize",
                "POST",
                avatarPayload(upload),
            )
        }
        return profileUploadStage("READBACK") { summary(accessToken) }
    }

    private fun issueAvatarUpload(
        accessToken: String,
        uploadId: String,
        sizeBytes: Long,
        sha256: String,
    ): ProfileAvatarUpload {
        val response = JSONObject(
            request(
                accessToken,
                "/profile/avatar/upload-url",
                "POST",
                JSONObject()
                    .put("client_upload_id", uploadId)
                    .put("content_type", "image/jpeg")
                    .put("size_bytes", sizeBytes)
                    .put("sha256", sha256)
                    .toString(),
            ),
        )
        return ProfileAvatarUpload(
            clientUploadId = uploadId,
            contentType = "image/jpeg",
            sizeBytes = sizeBytes,
            sha256 = sha256,
            objectKey = response.getString("object_key"),
            uploadUrl = response.getString("upload_url"),
            uploadHeaders = response.stringMap("upload_headers"),
        )
    }

    private fun avatarPayload(upload: ProfileAvatarUpload): String = JSONObject()
        .put("client_upload_id", upload.clientUploadId)
        .put("content_type", upload.contentType)
        .put("size_bytes", upload.sizeBytes)
        .put("sha256", upload.sha256)
        .toString()

    private fun request(
        accessToken: String,
        path: String,
        method: String,
        body: String? = null,
    ): String {
        val headers = buildMap {
            put("Authorization", "Bearer $accessToken")
            if (body != null) put("Content-Type", "application/json")
        }
        val response = transport.request(
            method,
            "${baseUrl.trimEnd('/')}$path",
            headers,
            body?.toByteArray(),
        )
        if (response.statusCode !in 200..299) {
            val code = runCatching {
                JSONObject(response.body).optString("code")
            }.getOrNull()?.takeIf(String::isNotBlank)
                ?: "PROFILE_REQUEST_FAILED"
            throw ProfileRequestException(response.statusCode, code, path, response.body)
        }
        return response.body
    }

    private inline fun <T> profileUploadStage(
        stage: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: Exception) {
        throw ProfileUploadException(stage, error)
    }
}

private fun JSONObject.doubleMap(name: String): Map<String, Double> {
    val value = getJSONObject(name)
    return value.keys().asSequence().associateWith { key -> value.getDouble(key) }
}

private fun File.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.stringMap(name: String): Map<String, String> {
    val value = getJSONObject(name)
    return value.keys().asSequence().associateWith { key -> value.getString(key) }
}
