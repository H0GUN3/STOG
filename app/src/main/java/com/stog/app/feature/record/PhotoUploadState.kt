package com.stog.app.feature.record

internal const val PHOTO_CONTENT_TYPE = "image/jpeg"
internal const val NORMALIZED_ORIGINAL_WIDTH = 2048
internal const val NORMALIZED_ORIGINAL_HEIGHT = 1536
internal const val THUMBNAIL_WIDTH = 640
internal const val THUMBNAIL_HEIGHT = 480
internal const val THUMBNAIL_MAX_BYTES = 50 * 1024
internal const val PHOTO_JPEG_QUALITY = 85

data class PhotoCoordinates(
    val latitude: Double,
    val longitude: Double,
)

enum class PhotoSource { CAMERA, GALLERY }

enum class PhotoVisibility(val wireValue: String, val label: String) {
    PRIVATE("private", "나만 보기"),
    GROUP("group", "그룹 여행에서 보기"),
    PUBLIC("public", "전체 공개"),
    ;

    companion object {
        fun fromWire(value: String?): PhotoVisibility? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class UploadStage {
    REQUESTING_URLS,
    ORIGINAL,
    THUMBNAIL,
    FINALIZING,
}

data class PreparedPhoto(
    val id: String,
    val source: PhotoSource,
    val tripId: Long,
    val normalizedOriginalPath: String,
    val thumbnailPath: String,
    val normalizedOriginalBytes: Long,
    val thumbnailBytes: Long,
    val originalSha256: String,
    val thumbnailSha256: String,
    val coordinates: PhotoCoordinates?,
    val takenAt: String?,
    val caption: String?,
    val sourceFilePath: String? = null,
    val finalizationCommitted: Boolean = false,
)

internal data class ConfirmedSetLogUpload(
    val photo: PreparedPhoto,
    val accuracyMeters: Double?,
    val locationProvenance: SetLogLocationProvenance,
    val placeResolutionStatus: String,
    val expectedPlaceId: Long?,
    val visibility: SetLogVisibilityIntent,
    val publicConsent: Boolean,
)

internal data class PhotoPlacePreviewResult(
    val status: String,
    val placeId: Long?,
    val placeName: String?,
)

internal sealed interface PhotoUploadUiState {
    data object Idle : PhotoUploadUiState
    data object Preparing : PhotoUploadUiState
    data class Ready(val photo: PreparedPhoto) : PhotoUploadUiState
    data class Uploading(val photo: PreparedPhoto, val stage: UploadStage) : PhotoUploadUiState
    data class RetryableFailure(
        val photo: PreparedPhoto,
        val stage: UploadStage,
        val message: String,
    ) : PhotoUploadUiState

    data class TerminalFailure(
        val photo: PreparedPhoto,
        val stage: UploadStage,
        val code: String,
    ) : PhotoUploadUiState

    data class AuthenticationRequired(val photo: PreparedPhoto?) : PhotoUploadUiState
    data class Completed(val photo: RemotePhoto) : PhotoUploadUiState
    data class FinalizationUnknown(val photo: PreparedPhoto) : PhotoUploadUiState
}

/**
 * A deterministic state machine: only a completed pair of direct PUTs may transition to
 * finalization, so a failed PUT can never leave a metadata row behind.
 */
internal class PhotoUploadStateMachine {
    var state: PhotoUploadUiState = PhotoUploadUiState.Idle
        private set

    fun prepared(photo: PreparedPhoto) {
        state = PhotoUploadUiState.Ready(photo)
    }

    fun begin(photo: PreparedPhoto) {
        state = PhotoUploadUiState.Uploading(photo, UploadStage.REQUESTING_URLS)
    }

    fun urlsReceived(photo: PreparedPhoto) {
        state = PhotoUploadUiState.Uploading(photo, UploadStage.ORIGINAL)
    }

    fun originalUploaded(photo: PreparedPhoto) {
        state = PhotoUploadUiState.Uploading(photo, UploadStage.THUMBNAIL)
    }

    fun thumbnailUploaded(photo: PreparedPhoto) {
        state = PhotoUploadUiState.Uploading(photo, UploadStage.FINALIZING)
    }

    fun failed(photo: PreparedPhoto, stage: UploadStage, message: String): PhotoUploadUiState {
        state = PhotoUploadUiState.RetryableFailure(photo, stage, message)
        return state
    }

    fun failed(photo: PreparedPhoto, stage: UploadStage, error: Throwable): PhotoUploadUiState {
        state = photoFailureState(photo, stage, error)
        return state
    }

    fun authenticationRequired(photo: PreparedPhoto?) {
        state = PhotoUploadUiState.AuthenticationRequired(photo)
    }

    fun finalizationUnknown(photo: PreparedPhoto) {
        state = PhotoUploadUiState.FinalizationUnknown(photo)
    }

    fun completed(photo: RemotePhoto) {
        state = PhotoUploadUiState.Completed(photo)
    }

    fun retry(): PreparedPhoto? = when (val current = state) {
        is PhotoUploadUiState.RetryableFailure -> current.photo.also(::begin)
        is PhotoUploadUiState.Ready -> current.photo.also(::begin)
        is PhotoUploadUiState.FinalizationUnknown -> current.photo.also(::begin)
        else -> null
    }

    fun restored(photo: PreparedPhoto) {
        state = PhotoUploadUiState.Ready(photo)
    }
}

data class CropBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun centerCropBounds(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): CropBounds {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(targetWidth > 0 && targetHeight > 0)
    val sourceRatio = sourceWidth.toDouble() / sourceHeight
    val targetRatio = targetWidth.toDouble() / targetHeight
    return if (sourceRatio > targetRatio) {
        val width = (sourceHeight * targetRatio).toInt()
        CropBounds((sourceWidth - width) / 2, 0, width, sourceHeight)
    } else {
        val height = (sourceWidth / targetRatio).toInt()
        CropBounds(0, (sourceHeight - height) / 2, sourceWidth, height)
    }
}

internal fun photoFailureState(
    photo: PreparedPhoto,
    stage: UploadStage,
    error: Throwable,
): PhotoUploadUiState {
    if (error is PhotoRequestException) {
        if (error.isAuthenticationFailure && stage !in setOf(UploadStage.ORIGINAL, UploadStage.THUMBNAIL)) {
            return PhotoUploadUiState.AuthenticationRequired(photo)
        }
        val directUrlExpired = stage in setOf(UploadStage.ORIGINAL, UploadStage.THUMBNAIL) &&
            error.statusCode in setOf(401, 403, 408, 429)
        val retryableServer = error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
        if (directUrlExpired || retryableServer) {
            return PhotoUploadUiState.RetryableFailure(photo, stage, error.code)
        }
        return PhotoUploadUiState.TerminalFailure(photo, stage, error.code)
    }
    if (stage == UploadStage.FINALIZING && error is java.io.IOException) {
        return PhotoUploadUiState.FinalizationUnknown(photo)
    }
    if (error is java.io.IOException) {
        return PhotoUploadUiState.RetryableFailure(photo, stage, "PHOTO_NETWORK_UNAVAILABLE")
    }
    return PhotoUploadUiState.TerminalFailure(photo, stage, "PHOTO_UNEXPECTED_FAILURE")
}

internal data class RemotePhoto(
    val id: Long,
    val tripId: Long?,
    val userId: Long,
    val source: PhotoSource,
    val cellId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val takenAt: String?,
    val originalUrl: String?,
    val thumbnailUrl: String?,
    val caption: String?,
    val visibility: PhotoVisibility?,
    val moderationStatus: String?,
    val createdAt: String?,
    val accuracyMeters: Double? = null,
    val locationProvenance: String? = null,
    val placeId: Long? = null,
    val placeName: String? = null,
    val placeResolutionStatus: String? = null,
    val publicConsent: Boolean? = null,
    val publicationStatus: String? = null,
)

internal data class ArchivePhoto(
    val id: Long,
    val tripId: Long?,
    val userId: Long,
    val source: PhotoSource,
    val cellId: String?,
    val thumbnailUrl: String?,
    val caption: String?,
    val visibility: PhotoVisibility?,
    val createdAt: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val takenAt: String? = null,
    val accuracyMeters: Double? = null,
    val locationProvenance: String? = null,
    val placeId: Long? = null,
    val placeName: String? = null,
    val placeResolutionStatus: String? = null,
    val moderationStatus: String? = null,
    val publicConsent: Boolean? = null,
    val publicationStatus: String? = null,
)

internal data class ArchiveMapPoint(
    val latitude: Double,
    val longitude: Double,
)

internal data class ArchiveTrailSegment(
    val userId: Long,
    val from: ArchiveMapPoint,
    val to: ArchiveMapPoint,
    val interpolated: Boolean,
)

internal data class ArchivePhotoMarker(
    val photoId: Long,
    val point: ArchiveMapPoint,
    val metadata: SetLogReadbackMetadata,
)

internal data class ArchiveMapProjection(
    val trailPoints: List<ArchiveMapPoint>,
    val trailSegments: List<ArchiveTrailSegment>,
    val photoMarkers: List<ArchivePhotoMarker>,
)

internal fun archiveMapProjection(
    trail: List<com.stog.app.feature.space.TripArchiveTrailPoint>,
    photos: List<ArchivePhoto>,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.getDefault(),
): ArchiveMapProjection {
    val orderedByMember = trail
        .filter { it.latitude.isFinite() && it.longitude.isFinite() }
        .groupBy { it.userId }
        .toSortedMap()
        .mapValues { (_, points) -> points.sortedWith(compareBy({ it.enteredAt }, { it.visitId })) }
    val trailPoints = orderedByMember.values.flatten().map { ArchiveMapPoint(it.latitude, it.longitude) }
    val segments = orderedByMember.flatMap { (userId, points) ->
        points.zipWithNext { from, to ->
            ArchiveTrailSegment(
                userId = userId,
                from = ArchiveMapPoint(from.latitude, from.longitude),
                to = ArchiveMapPoint(to.latitude, to.longitude),
                interpolated = to.isInterpolated,
            )
        }
    }
    val markers = photos.mapNotNull { photo ->
        val latitude = photo.latitude
        val longitude = photo.longitude
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) null
        else ArchivePhotoMarker(
            photoId = photo.id,
            point = ArchiveMapPoint(latitude, longitude),
            metadata = photo.readbackMetadata(zoneId, locale),
        )
    }.sortedBy(ArchivePhotoMarker::photoId)
    return ArchiveMapProjection(trailPoints, segments, markers)
}

internal fun selectEndedArchiveTrip(
    trips: List<com.stog.app.feature.space.TripSummary>,
    selectedTripId: Long?,
): com.stog.app.feature.space.TripSummary? {
    val ended = trips.filter { it.mode == "ended" }
    return ended.firstOrNull { it.id == selectedTripId } ?: ended.firstOrNull()
}

internal data class PublicGrant(
    val photoId: Long,
    val version: Int,
    val revokedAt: String?,
)

internal fun galleryLocationGuidance(hasMediaLocationPermission: Boolean): String? =
    if (hasMediaLocationPermission) null else "사진 위치 접근을 허용하지 않아 보관함 전용으로 저장해요."

internal fun archivePhotosForViewer(
    photos: List<ArchivePhoto>,
    viewerId: Long,
    myPhotosOnly: Boolean,
): List<ArchivePhoto> = if (myPhotosOnly) photos.filter { it.userId == viewerId } else photos

internal enum class PublicGrantState {
    UNKNOWN,
    NOT_PUBLIC,
    CONSENT_REQUIRED,
    MODERATION_PENDING,
    APPROVED,
    REVIEW_REQUIRED,
}

internal fun publicGrantState(
    visibility: PhotoVisibility?,
    grants: List<PublicGrant>,
    moderationStatus: String?,
): PublicGrantState = when {
    visibility == null -> PublicGrantState.UNKNOWN
    visibility != PhotoVisibility.PUBLIC -> PublicGrantState.NOT_PUBLIC
    grants.lastOrNull { it.revokedAt == null } == null -> PublicGrantState.CONSENT_REQUIRED
    moderationStatus == "pending" -> PublicGrantState.MODERATION_PENDING
    moderationStatus == "approved" -> PublicGrantState.APPROVED
    else -> PublicGrantState.REVIEW_REQUIRED
}

internal fun publicGrantMessage(
    visibility: PhotoVisibility?,
    grants: List<PublicGrant>,
    moderationStatus: String?,
): String = when (publicGrantState(visibility, grants, moderationStatus)) {
    PublicGrantState.UNKNOWN -> "공개 범위와 동의 상태를 확인할 수 없어요."
    PublicGrantState.NOT_PUBLIC -> "공개 범위로 바꾼 뒤 별도 공개 동의를 선택할 수 있어요."
    PublicGrantState.CONSENT_REQUIRED -> "공개 동의가 필요해요. 공개 범위만으로는 게시되지 않아요."
    PublicGrantState.MODERATION_PENDING -> "공개 동의가 접수되었어요. 검토 대기 중입니다."
    PublicGrantState.APPROVED -> "공개 동의와 검토가 완료되었어요."
    PublicGrantState.REVIEW_REQUIRED -> "공개 상태를 다시 확인해주세요."
}
