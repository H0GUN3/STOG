package com.stog.app.feature.record

import java.io.File
import org.json.JSONObject
import org.json.JSONArray

internal data class PhotoUploadUrls(
    val originalObjectKey: String,
    val originalUploadUrl: String,
    val thumbnailObjectKey: String,
    val thumbnailUploadUrl: String,
    val originalUploadHeaders: Map<String, String>,
    val thumbnailUploadHeaders: Map<String, String>,
)

internal class PhotoApiClient(
    private val baseUrl: String,
    private val transport: PhotoHttpTransport = UrlConnectionPhotoTransport(),
) {
    fun previewPlace(
        accessToken: String,
        coordinates: PhotoCoordinates,
        accuracyMeters: Double?,
    ): PhotoPlacePreviewResult {
        val payload = JSONObject()
            .put("latitude", coordinates.latitude)
            .put("longitude", coordinates.longitude)
            .put("accuracy_m", accuracyMeters ?: JSONObject.NULL)
        val json = JSONObject(requestApi(
            accessToken, "/photos/place-preview", "POST", payload.toString().encodeToByteArray(),
        ))
        return PhotoPlacePreviewResult(
            status = json.getString("status"),
            placeId = json.optionalLong("place_id"),
            placeName = json.optionalString("place_name"),
        )
    }

    fun issueUploadUrls(accessToken: String, photo: PreparedPhoto): PhotoUploadUrls {
        val response = requestApi(
            accessToken = accessToken,
            path = "/photos/upload-url",
            method = "POST",
            body = JSONObject()
                .put("trip_id", photo.tripId)
                .put("client_upload_id", photo.id)
                .put(
                    "original",
                    uploadObject(photo.normalizedOriginalBytes, photo.originalSha256),
                )
                .put(
                    "thumbnail",
                    uploadObject(photo.thumbnailBytes, photo.thumbnailSha256),
                )
                .toString().encodeToByteArray(),
        )
        return decodePhotoResponse { response.toUploadUrls() }
    }

    /** Bytes are sent only to the signed storage URL supplied by Spring. */
    fun putDirectly(signedUrl: String, headers: Map<String, String>, file: File) {
        val response = transport.request("PUT", signedUrl, headers, file.readBytes())
        if (response.statusCode !in 200..299 && response.statusCode != 412) {
            throw PhotoRequestException(
                response.statusCode,
                "PHOTO_DIRECT_PUT_FAILED",
                response.body,
            )
        }
    }

    fun finalize(
        accessToken: String,
        photo: PreparedPhoto,
        urls: PhotoUploadUrls,
    ): RemotePhoto = finalize(
        accessToken,
        ConfirmedSetLogUpload(
            photo = photo,
            accuracyMeters = null,
            locationProvenance = if (photo.coordinates == null) SetLogLocationProvenance.MISSING else SetLogLocationProvenance.GALLERY_EXIF,
            placeResolutionStatus = if (photo.coordinates == null) "no_match" else "pending",
            expectedPlaceId = null,
            visibility = SetLogVisibilityIntent.PRIVATE,
            publicConsent = false,
        ),
        urls,
    )

    fun finalize(
        accessToken: String,
        confirmed: ConfirmedSetLogUpload,
        urls: PhotoUploadUrls,
    ): RemotePhoto {
        val photo = confirmed.photo
        val payload = JSONObject()
            .put("trip_id", photo.tripId)
            .put("source", photo.source.name.lowercase())
            .put("client_upload_id", photo.id)
            .put("original_key", urls.originalObjectKey)
            .put("thumb_key", urls.thumbnailObjectKey)
            .put("original", uploadObject(photo.normalizedOriginalBytes, photo.originalSha256))
            .put("thumbnail", uploadObject(photo.thumbnailBytes, photo.thumbnailSha256))
            .put("latitude", photo.coordinates?.latitude ?: JSONObject.NULL)
            .put("longitude", photo.coordinates?.longitude ?: JSONObject.NULL)
            .put("accuracy_m", confirmed.accuracyMeters ?: JSONObject.NULL)
            .put(
                "location_provenance",
                when (confirmed.locationProvenance) {
                    SetLogLocationProvenance.CAMERA_FOREGROUND -> "camera_foreground"
                    SetLogLocationProvenance.GALLERY_EXIF -> "gallery_exif"
                    SetLogLocationProvenance.MISSING -> JSONObject.NULL
                },
            )
            .put("taken_at", photo.takenAt ?: JSONObject.NULL)
            .put("caption", photo.caption ?: JSONObject.NULL)
            .put(
                "place_resolution_status",
                confirmed.placeResolutionStatus.takeUnless { it == "pending" } ?: JSONObject.NULL,
            )
            .put("expected_place_id", confirmed.expectedPlaceId ?: JSONObject.NULL)
            .put("visibility", confirmed.visibility.name.lowercase())
            .put("public_consent", confirmed.publicConsent)
        val response = requestApi(accessToken, "/photos", "POST", payload.toString().encodeToByteArray())
        return decodePhotoResponse { response.toRemotePhoto() }
    }

    fun archive(accessToken: String, tripId: Long): List<ArchivePhoto> {
        val response = requestApi(accessToken, "/trips/$tripId/photos", "GET", null)
        val array = JSONArray(response)
        return List(array.length()) { index -> array.getJSONObject(index).toArchivePhoto() }
    }

    fun mine(accessToken: String): List<ArchivePhoto> {
        val response = requestApi(accessToken, "/photos/mine", "GET", null)
        val array = JSONArray(response)
        return List(array.length()) { index -> array.getJSONObject(index).toArchivePhoto() }
    }

    fun detail(accessToken: String, photoId: Long): RemotePhoto =
        requestApi(accessToken, "/photos/$photoId", "GET", null).toRemotePhoto()

    fun changeVisibility(
        accessToken: String,
        photoId: Long,
        visibility: PhotoVisibility,
    ): RemotePhoto = requestApi(
        accessToken,
        "/photos/$photoId/visibility",
        "PATCH",
        JSONObject().put("visibility", visibility.wireValue).toString().encodeToByteArray(),
    ).toRemotePhoto()

    fun grants(accessToken: String, photoId: Long): List<PublicGrant> {
        val response = JSONArray(requestApi(accessToken, "/photos/$photoId/public-grants", "GET", null))
        return List(response.length()) { index -> response.getJSONObject(index).toPublicGrant() }
    }

    fun acceptPublicGrant(accessToken: String, photoId: Long): PublicGrant =
        JSONObject(requestApi(accessToken, "/photos/$photoId/public-grants", "POST", null)).toPublicGrant()

    fun revokePublicGrant(accessToken: String, photoId: Long, version: Int): PublicGrant =
        JSONObject(requestApi(accessToken, "/photos/$photoId/public-grants/$version/revoke", "POST", null))
            .toPublicGrant()

    private fun requestApi(
        accessToken: String,
        path: String,
        method: String,
        body: ByteArray?,
    ): String {
        val headers = buildMap {
            put("Authorization", "Bearer $accessToken")
            if (body != null) put("Content-Type", "application/json")
        }
        val response = transport.request(method, "${baseUrl.trimEnd('/')}$path", headers, body)
        if (response.statusCode !in 200..299) {
            throw PhotoRequestException(
                response.statusCode,
                photoErrorCode(response.body),
                response.body,
            )
        }
        return response.body
    }
}


