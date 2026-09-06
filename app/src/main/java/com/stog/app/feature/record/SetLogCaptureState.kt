package com.stog.app.feature.record

import com.stog.app.feature.space.TripSummary
import com.uber.h3core.H3Core
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.floor

internal const val SET_LOG_NOTE_MAX_CODE_POINTS = 120
internal const val SET_LOG_H3_RESOLUTION = 10

internal data class SetLogCaptureToken(val value: Long) : Serializable {
    init { require(value > 0) }
}

internal data class SetLogLocalAsset(
    val assetId: String,
    val outputId: String,
    val path: String,
) {
    init { require(assetId.isNotBlank() && outputId.isNotBlank() && path.isNotBlank()) }
}

internal data class SetLogLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val sourceTimeMillis: Long,
    val provenance: SetLogLocationFixProvenance,
) : Serializable {
    val coordinates: PhotoCoordinates get() = PhotoCoordinates(latitude, longitude)

    init {
        require(coordinates.isValidSetLogCoordinate())
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0)
        require(sourceTimeMillis > 0L)
    }
}

internal enum class SetLogLocationFixProvenance { LAST_KNOWN, CURRENT_UPDATE, RECENT_RECORDING }

internal enum class SetLogLocationProvenance { CAMERA_FOREGROUND, GALLERY_EXIF, MISSING }
internal enum class SetLogTakenAtProvenance { CAMERA_SHUTTER, GALLERY_EXIF, MISSING }
internal enum class SetLogVisibilityIntent { PRIVATE, PUBLIC }
internal enum class SetLogPublicationState { NOT_RECORDED, PRIVATE, TRIP_NOT_PUBLIC, MODERATION_PENDING, PUBLIC }

internal sealed interface SetLogPlacePreview {
    data object Pending : SetLogPlacePreview
    data class Matched(val placeId: Long, val placeName: String) : SetLogPlacePreview {
        init { require(placeId > 0 && placeName.isNotBlank()) }
    }
    data object NoMatch : SetLogPlacePreview
    data class Failed(val code: String) : SetLogPlacePreview {
        init { require(code.isNotBlank()) }
    }
}

internal data class SetLogSnapshot(
    val accountId: String,
    val userId: Long,
    val tripId: Long,
    val source: PhotoSource,
    val captureToken: SetLogCaptureToken,
    val takenAt: String?,
    val takenAtProvenance: SetLogTakenAtProvenance,
    val coordinates: PhotoCoordinates?,
    val accuracyMeters: Double?,
    val locationProvenance: SetLogLocationProvenance,
    val provisionalCellId: String?,
    val localAsset: SetLogLocalAsset,
) {
    init {
        require(accountId.isNotBlank() && userId > 0 && tripId > 0)
        require(coordinates == null || coordinates.isValidSetLogCoordinate())
        require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters >= 0.0)
        require(provisionalCellId == null || coordinates != null)
        takenAt?.let { require(Instant.parse(it).toString() == it) }
        if (source == PhotoSource.CAMERA) {
            require(takenAt != null && takenAtProvenance == SetLogTakenAtProvenance.CAMERA_SHUTTER)
            if (coordinates == null) {
                require(accuracyMeters == null)
                require(locationProvenance == SetLogLocationProvenance.MISSING)
            } else {
                require(accuracyMeters != null)
                require(locationProvenance == SetLogLocationProvenance.CAMERA_FOREGROUND)
            }
        } else {
            require(accuracyMeters == null)
            require(locationProvenance != SetLogLocationProvenance.CAMERA_FOREGROUND)
            require(takenAtProvenance != SetLogTakenAtProvenance.CAMERA_SHUTTER)
        }
    }
}

internal enum class SetLogNoteIssue { MULTILINE, TOO_LONG }

internal data class SetLogDraft(
    val snapshot: SetLogSnapshot,
    val placePreview: SetLogPlacePreview = if (snapshot.coordinates == null) SetLogPlacePreview.NoMatch else SetLogPlacePreview.Pending,
    val caption: String = "",
    val captionIssue: SetLogNoteIssue? = null,
    val visibilityIntent: SetLogVisibilityIntent = SetLogVisibilityIntent.PRIVATE,
    val publicationState: SetLogPublicationState = SetLogPublicationState.NOT_RECORDED,
) {
    val captionForRecord: String? get() = caption.trim().takeIf(String::isNotEmpty)
    val canRecord: Boolean
        get() = captionIssue == null && (
            snapshot.source != PhotoSource.CAMERA ||
                snapshot.coordinates != null &&
                snapshot.accuracyMeters != null &&
                snapshot.locationProvenance == SetLogLocationProvenance.CAMERA_FOREGROUND
            )
}

internal enum class SetLogCaptureStage {
    PERMISSION_REQUIRED, INITIALIZING, READY, CAPTURING, ERROR, DRAFT_READY, CONFIRMING,
    SAVING, PENDING_SYNC, SAVED, FAILED, CLOSED,
}

internal data class SetLogCaptureState(
    val accountId: String,
    val userId: Long,
    val tripId: Long,
    val stage: SetLogCaptureStage = SetLogCaptureStage.PERMISSION_REQUIRED,
    val activeToken: SetLogCaptureToken? = null,
    val nextToken: Long = 1,
    val pendingSource: PhotoSource? = null,
    val pendingSnapshot: SetLogSnapshot? = null,
    val draft: SetLogDraft? = null,
    val errorCode: String? = null,
    val savedPhotoId: Long? = null,
    val hasDurablePendingRecord: Boolean = false,
) {
    init { require(accountId.isNotBlank() && userId > 0 && tripId > 0) }
}

internal sealed interface SetLogCaptureEvent {
    data class Start(val cameraPermissionGranted: Boolean) : SetLogCaptureEvent
    data object CameraPermissionGranted : SetLogCaptureEvent
    data class CameraReady(val token: SetLogCaptureToken) : SetLogCaptureEvent
    data class ShutterPressed(
        val takenAt: Instant,
        val location: SetLogLocationFix?,
        val provisionalCellId: String?,
        val output: SetLogLocalAsset,
    ) : SetLogCaptureEvent
    data object GalleryPressed : SetLogCaptureEvent
    data class GallerySelected(
        val token: SetLogCaptureToken,
        val asset: SetLogLocalAsset,
        val provenance: SetLogGalleryProvenance,
        val provisionalCellId: String?,
    ) : SetLogCaptureEvent
    data class CaptureCompleted(val token: SetLogCaptureToken, val outputId: String) : SetLogCaptureEvent
    data class LocationUpdated(
        val token: SetLogCaptureToken,
        val acquisition: SetLogLocationAcquisition,
    ) : SetLogCaptureEvent
    data class CallbackFailed(val token: SetLogCaptureToken, val code: String) : SetLogCaptureEvent
    data class PlacePreviewChanged(val token: SetLogCaptureToken, val preview: SetLogPlacePreview) : SetLogCaptureEvent
    data object ConfirmationOpened : SetLogCaptureEvent
    data class CaptionChanged(val value: String) : SetLogCaptureEvent
    data class VisibilityChanged(val intent: SetLogVisibilityIntent) : SetLogCaptureEvent
    data object RecordPressed : SetLogCaptureEvent
    data class RecordPersisted(
        val token: SetLogCaptureToken,
        val schedulingDeferred: Boolean = false,
    ) : SetLogCaptureEvent
    data class DeliverySucceeded(val token: SetLogCaptureToken, val photoId: Long, val publication: SetLogPublicationState) : SetLogCaptureEvent
    data class DeliveryFailed(val token: SetLogCaptureToken, val code: String, val retryable: Boolean) : SetLogCaptureEvent
    data object RetakePressed : SetLogCaptureEvent
    data object RebindRequested : SetLogCaptureEvent
    data object ClosePressed : SetLogCaptureEvent
}

internal sealed interface SetLogCaptureEffect {
    data class BindCamera(val token: SetLogCaptureToken) : SetLogCaptureEffect
    data class TakePhoto(val token: SetLogCaptureToken, val output: SetLogLocalAsset) : SetLogCaptureEffect
    data class OpenGallery(val token: SetLogCaptureToken) : SetLogCaptureEffect
    data class PreviewPlace(val token: SetLogCaptureToken, val coordinates: PhotoCoordinates, val accuracyMeters: Double?) : SetLogCaptureEffect
    data class CleanupAsset(val asset: SetLogLocalAsset) : SetLogCaptureEffect
    data class CommitRecord(val draft: SetLogDraft) : SetLogCaptureEffect
    data object Close : SetLogCaptureEffect
}

internal data class SetLogCaptureReduction(
    val state: SetLogCaptureState,
    val effects: List<SetLogCaptureEffect> = emptyList(),
)

internal fun reduceSetLogCapture(state: SetLogCaptureState, event: SetLogCaptureEvent): SetLogCaptureReduction {
    if (state.stage == SetLogCaptureStage.CLOSED) return state.unchanged()
    return when (event) {
        is SetLogCaptureEvent.Start -> if (
            state.stage == SetLogCaptureStage.PERMISSION_REQUIRED && event.cameraPermissionGranted
        ) state.bind() else state.unchanged()
        SetLogCaptureEvent.CameraPermissionGranted -> if (state.stage == SetLogCaptureStage.PERMISSION_REQUIRED) {
            state.bind()
        } else state.unchanged()
        is SetLogCaptureEvent.CameraReady -> if (
            state.stage == SetLogCaptureStage.INITIALIZING && state.activeToken == event.token
        ) state.copy(stage = SetLogCaptureStage.READY).result() else state.unchanged()
        is SetLogCaptureEvent.ShutterPressed -> state.shutter(event)
        SetLogCaptureEvent.GalleryPressed -> state.activeToken
            ?.takeIf { state.stage == SetLogCaptureStage.READY }
            ?.let { token ->
                state.copy(stage = SetLogCaptureStage.CAPTURING, pendingSource = PhotoSource.GALLERY)
                    .result(SetLogCaptureEffect.OpenGallery(token))
        } ?: state.unchanged()
        is SetLogCaptureEvent.GallerySelected -> state.gallery(event)
        is SetLogCaptureEvent.CaptureCompleted -> state.captureCompleted(event)
        is SetLogCaptureEvent.LocationUpdated -> state.locationUpdated(event)
        is SetLogCaptureEvent.CallbackFailed -> if (
            state.stage in callbackStages && state.activeToken == event.token
        ) state.copy(stage = SetLogCaptureStage.ERROR, errorCode = event.code).result() else state.unchanged()
        is SetLogCaptureEvent.PlacePreviewChanged -> state.updateDraft(event.token) {
            copy(placePreview = event.preview)
        }
        SetLogCaptureEvent.ConfirmationOpened -> if (state.stage == SetLogCaptureStage.DRAFT_READY) {
            state.copy(stage = SetLogCaptureStage.CONFIRMING).result()
        } else state.unchanged()
        is SetLogCaptureEvent.CaptionChanged -> state.editDraft {
            copy(caption = event.value, captionIssue = setLogNoteIssue(event.value))
        }
        is SetLogCaptureEvent.VisibilityChanged -> state.editDraft { copy(visibilityIntent = event.intent) }
        SetLogCaptureEvent.RecordPressed -> state.record()
        is SetLogCaptureEvent.RecordPersisted -> if (
            state.stage == SetLogCaptureStage.SAVING && state.activeToken == event.token
        ) state.copy(
            stage = SetLogCaptureStage.PENDING_SYNC,
            errorCode = if (event.schedulingDeferred) "SET_LOG_SCHEDULE_FAILED" else null,
            hasDurablePendingRecord = true,
        ).result() else state.unchanged()
        is SetLogCaptureEvent.DeliverySucceeded -> state.delivered(event)
        is SetLogCaptureEvent.DeliveryFailed -> if (
            state.stage in deliveryStages && state.activeToken == event.token
        ) {
            state.copy(
                stage = if (event.retryable) SetLogCaptureStage.PENDING_SYNC else SetLogCaptureStage.FAILED,
                errorCode = event.code,
            ).result()
        } else state.unchanged()
        SetLogCaptureEvent.RetakePressed -> state.retake()
        SetLogCaptureEvent.RebindRequested -> state.rebind()
        SetLogCaptureEvent.ClosePressed -> state.close()
    }
}

internal fun freezeSetLogShutter(
    state: SetLogCaptureState,
    acquisition: SetLogLocationAcquisition,
    takenAt: Instant,
    output: SetLogLocalAsset,
): SetLogCaptureReduction {
    val fix = (acquisition as? SetLogLocationAcquisition.Accepted)?.fix
    val reduction = reduceSetLogCapture(
        state,
        SetLogCaptureEvent.ShutterPressed(
            takenAt = takenAt,
            location = fix,
            provisionalCellId = provisionalSetLogCellId(fix?.coordinates),
            output = output,
        ),
    )
    return if (acquisition is SetLogLocationAcquisition.Rejected) {
        reduction.copy(state = reduction.state.copy(errorCode = "LOCATION_${acquisition.reason.name}"))
    } else {
        reduction
    }
}

private val callbackStages = setOf(SetLogCaptureStage.INITIALIZING, SetLogCaptureStage.CAPTURING)
private val deliveryStages = setOf(SetLogCaptureStage.SAVING, SetLogCaptureStage.PENDING_SYNC)

private fun SetLogCaptureState.shutter(event: SetLogCaptureEvent.ShutterPressed): SetLogCaptureReduction {
    val token = activeToken
    if (stage != SetLogCaptureStage.READY || token == null) return unchanged()
    val fix = event.location
    val snapshot = sourceSnapshot(
        source = PhotoSource.CAMERA,
        token = token,
        asset = event.output,
        takenAt = event.takenAt,
        coordinates = fix?.coordinates,
        accuracy = fix?.accuracyMeters,
        provisionalCellId = event.provisionalCellId?.takeIf { fix != null },
    )
    return copy(
        stage = SetLogCaptureStage.CAPTURING,
        pendingSource = PhotoSource.CAMERA,
        pendingSnapshot = snapshot,
        errorCode = "LOCATION_REQUIRED".takeIf { fix == null },
    ).result(SetLogCaptureEffect.TakePhoto(token, event.output))
}

private fun SetLogCaptureState.locationUpdated(
    event: SetLogCaptureEvent.LocationUpdated,
): SetLogCaptureReduction {
    if (activeToken != event.token) return unchanged()
    val current = when (stage) {
        SetLogCaptureStage.CAPTURING -> pendingSnapshot
        SetLogCaptureStage.DRAFT_READY,
        SetLogCaptureStage.CONFIRMING,
        -> draft?.snapshot
        else -> null
    } ?: return unchanged()
    if (current.source != PhotoSource.CAMERA || current.coordinates != null) return unchanged()
    return when (val acquisition = event.acquisition) {
        is SetLogLocationAcquisition.Accepted -> {
            val fix = acquisition.fix
            val updated = current.copy(
                coordinates = fix.coordinates,
                accuracyMeters = fix.accuracyMeters,
                locationProvenance = SetLogLocationProvenance.CAMERA_FOREGROUND,
                provisionalCellId = provisionalSetLogCellId(fix.coordinates),
            )
            if (stage == SetLogCaptureStage.CAPTURING) {
                copy(pendingSnapshot = updated, errorCode = null).result()
            } else {
                copy(
                    draft = requireNotNull(draft).copy(
                        snapshot = updated,
                        placePreview = SetLogPlacePreview.Pending,
                    ),
                    errorCode = null,
                ).result()
            }
        }
        is SetLogLocationAcquisition.Rejected ->
            copy(errorCode = "LOCATION_${acquisition.reason.name}").result()
    }
}

private fun SetLogCaptureState.gallery(event: SetLogCaptureEvent.GallerySelected): SetLogCaptureReduction {
    if (
        stage != SetLogCaptureStage.CAPTURING || pendingSource != PhotoSource.GALLERY ||
        activeToken != event.token
    ) return unchanged()
    return draftReady(
        sourceSnapshot(
            source = PhotoSource.GALLERY,
            token = event.token,
            asset = event.asset,
            takenAt = event.provenance.takenAt,
            coordinates = event.provenance.coordinates,
            accuracy = null,
            provisionalCellId = event.provisionalCellId,
        ),
    )
}

private fun SetLogCaptureState.captureCompleted(
    event: SetLogCaptureEvent.CaptureCompleted,
): SetLogCaptureReduction {
    val snapshot = pendingSnapshot
    return if (
        stage == SetLogCaptureStage.CAPTURING && pendingSource == PhotoSource.CAMERA &&
        activeToken == event.token && snapshot?.captureToken == event.token &&
        snapshot.localAsset.outputId == event.outputId
    ) draftReady(snapshot) else unchanged()
}

private fun SetLogCaptureState.draftReady(snapshot: SetLogSnapshot): SetLogCaptureReduction {
    val preview = snapshot.coordinates?.let {
        SetLogCaptureEffect.PreviewPlace(snapshot.captureToken, it, snapshot.accuracyMeters)
    }
    return copy(
        stage = SetLogCaptureStage.DRAFT_READY,
        pendingSource = null,
        pendingSnapshot = null,
        draft = SetLogDraft(snapshot),
        errorCode = null,
    ).result(*listOfNotNull(preview).toTypedArray())
}

private fun SetLogCaptureState.updateDraft(
    token: SetLogCaptureToken,
    change: SetLogDraft.() -> SetLogDraft,
): SetLogCaptureReduction {
    val current = draft
    return if (
        stage in setOf(SetLogCaptureStage.DRAFT_READY, SetLogCaptureStage.CONFIRMING) &&
        activeToken == token && current?.snapshot?.captureToken == token
    ) copy(draft = current.change()).result() else unchanged()
}

private fun SetLogCaptureState.editDraft(
    change: SetLogDraft.() -> SetLogDraft,
): SetLogCaptureReduction = draft
    ?.takeIf { stage == SetLogCaptureStage.CONFIRMING }
    ?.let { copy(draft = it.change()).result() }
    ?: unchanged()

private fun SetLogCaptureState.record(): SetLogCaptureReduction {
    val current = draft
    return if (stage == SetLogCaptureStage.CONFIRMING && current?.canRecord == true) {
        copy(stage = SetLogCaptureStage.SAVING).result(SetLogCaptureEffect.CommitRecord(current))
    } else unchanged()
}

private fun SetLogCaptureState.delivered(
    event: SetLogCaptureEvent.DeliverySucceeded,
): SetLogCaptureReduction {
    val current = draft
    return if (
        stage in deliveryStages && activeToken == event.token && current != null && event.photoId > 0
    ) {
        copy(
            stage = SetLogCaptureStage.SAVED,
            draft = current.copy(publicationState = event.publication),
            savedPhotoId = event.photoId,
            errorCode = null,
        ).result()
    } else unchanged()
}

private fun SetLogCaptureState.retake(): SetLogCaptureReduction {
    val acceptsRetake = stage in setOf(
            SetLogCaptureStage.CAPTURING,
            SetLogCaptureStage.ERROR,
            SetLogCaptureStage.DRAFT_READY,
            SetLogCaptureStage.CONFIRMING,
        ) || stage == SetLogCaptureStage.FAILED && !hasDurablePendingRecord
    if (!acceptsRetake) return unchanged()
    val asset = draft?.snapshot?.localAsset ?: pendingSnapshot?.localAsset
    val rebound = nextBinding()
    return rebound.first.result(
        *listOfNotNull(asset?.let(SetLogCaptureEffect::CleanupAsset), rebound.second).toTypedArray(),
    )
}

private fun SetLogCaptureState.rebind(): SetLogCaptureReduction {
    if (stage !in callbackStages + SetLogCaptureStage.READY + SetLogCaptureStage.ERROR) return unchanged()
    val asset = pendingSnapshot?.localAsset
    val rebound = nextBinding()
    return rebound.first.result(
        *listOfNotNull(asset?.let(SetLogCaptureEffect::CleanupAsset), rebound.second).toTypedArray(),
    )
}

private fun SetLogCaptureState.close(): SetLogCaptureReduction {
    val uncommitted = stage in setOf(
        SetLogCaptureStage.CAPTURING,
        SetLogCaptureStage.ERROR,
        SetLogCaptureStage.DRAFT_READY,
        SetLogCaptureStage.CONFIRMING,
    ) || stage == SetLogCaptureStage.FAILED && !hasDurablePendingRecord
    val asset = if (uncommitted) {
        draft?.snapshot?.localAsset ?: pendingSnapshot?.localAsset
    } else null
    return copy(
        stage = SetLogCaptureStage.CLOSED,
        activeToken = null,
        pendingSource = null,
        pendingSnapshot = null,
        draft = if (uncommitted) null else draft,
    ).result(
        *listOfNotNull(asset?.let(SetLogCaptureEffect::CleanupAsset), SetLogCaptureEffect.Close).toTypedArray(),
    )
}

private fun SetLogCaptureState.bind(): SetLogCaptureReduction {
    val binding = nextBinding()
    return binding.first.result(binding.second)
}

private fun SetLogCaptureState.nextBinding(): Pair<SetLogCaptureState, SetLogCaptureEffect.BindCamera> {
    val token = SetLogCaptureToken(nextToken)
    return copy(
        stage = SetLogCaptureStage.INITIALIZING,
        activeToken = token,
        nextToken = nextToken + 1,
        pendingSource = null,
        pendingSnapshot = null,
        draft = null,
        errorCode = null,
        savedPhotoId = null,
        hasDurablePendingRecord = false,
    ) to SetLogCaptureEffect.BindCamera(token)
}

private fun SetLogCaptureState.sourceSnapshot(
    source: PhotoSource,
    token: SetLogCaptureToken,
    asset: SetLogLocalAsset,
    takenAt: Instant?,
    coordinates: PhotoCoordinates?,
    accuracy: Double?,
    provisionalCellId: String?,
) = SetLogSnapshot(
    accountId = accountId,
    userId = userId,
    tripId = tripId,
    source = source,
    captureToken = token,
    takenAt = takenAt?.toString(),
    takenAtProvenance = when {
        source == PhotoSource.CAMERA -> SetLogTakenAtProvenance.CAMERA_SHUTTER
        takenAt != null -> SetLogTakenAtProvenance.GALLERY_EXIF
        else -> SetLogTakenAtProvenance.MISSING
    },
    coordinates = coordinates,
    accuracyMeters = accuracy,
    locationProvenance = when {
        source == PhotoSource.CAMERA && coordinates != null -> SetLogLocationProvenance.CAMERA_FOREGROUND
        coordinates != null -> SetLogLocationProvenance.GALLERY_EXIF
        else -> SetLogLocationProvenance.MISSING
    },
    provisionalCellId = provisionalCellId,
    localAsset = asset,
)

private fun SetLogCaptureState.result(
    vararg effects: SetLogCaptureEffect,
) = SetLogCaptureReduction(this, effects.toList())

private fun SetLogCaptureState.unchanged() = SetLogCaptureReduction(this)

internal data class SetLogGalleryExif(
    val coordinates: PhotoCoordinates?,
    val dateTimeOriginal: String?,
    val offsetTimeOriginal: String?,
    val gpsDateStamp: String?,
    val gpsTimeStamp: String?,
)

internal data class SetLogGalleryProvenance(
    val coordinates: PhotoCoordinates?,
    val takenAt: Instant?,
)

internal fun setLogGalleryProvenance(
    exif: SetLogGalleryExif,
    hasMediaLocationAccess: Boolean,
) = SetLogGalleryProvenance(
    coordinates = exif.coordinates?.takeIf {
        hasMediaLocationAccess && it.isValidSetLogCoordinate()
    },
    takenAt = exif.absoluteInstant(),
)

private val exifDateTime = DateTimeFormatter
    .ofPattern("uuuu:MM:dd HH:mm:ss")
    .withResolverStyle(ResolverStyle.STRICT)
private val exifDate = DateTimeFormatter
    .ofPattern("uuuu:MM:dd")
    .withResolverStyle(ResolverStyle.STRICT)

private fun SetLogGalleryExif.absoluteInstant(): Instant? {
    val original = dateTimeOriginal
    val offset = offsetTimeOriginal
    if (!original.isNullOrBlank() && !offset.isNullOrBlank()) {
        runCatching {
            return LocalDateTime.parse(original.trim(), exifDateTime)
                .atOffset(ZoneOffset.of(offset.trim()))
                .toInstant()
        }
    }
    val date = runCatching {
        LocalDate.parse(gpsDateStamp?.trim().orEmpty(), exifDate)
    }.getOrNull() ?: return null
    val seconds = gpsTimeStamp.exifGpsSeconds() ?: return null
    val whole = floor(seconds).toLong()
    return date.atStartOfDay().toInstant(ZoneOffset.UTC)
        .plusSeconds(whole)
        .plusNanos(((seconds - whole) * 1_000_000_000.0).toLong())
}

private fun String?.exifGpsSeconds(): Double? {
    val parts = this?.trim()?.removePrefix("[")?.removeSuffix("]")
        ?.split(',')?.map(String::trim) ?: return null
    if (parts.size != 3) return null
    val values = parts.map { it.exifNumber() }
    val hour = values[0] ?: return null
    val minute = values[1] ?: return null
    val second = values[2] ?: return null
    return if (hour in 0.0..<24.0 && minute in 0.0..<60.0 && second in 0.0..<60.0) {
        hour * 3_600 + minute * 60 + second
    } else null
}

private fun String.exifNumber(): Double? {
    val fraction = split('/')
    return when (fraction.size) {
        1 -> fraction[0].toDoubleOrNull()
        2 -> fraction[0].toDoubleOrNull()?.let { numerator ->
            fraction[1].toDoubleOrNull()?.takeIf { it != 0.0 }?.let { numerator / it }
        }
        else -> null
    }
}

internal fun setLogNoteIssue(value: String): SetLogNoteIssue? = when {
    value.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' } -> SetLogNoteIssue.MULTILINE
    value.codePointCount(0, value.length) > SET_LOG_NOTE_MAX_CODE_POINTS -> SetLogNoteIssue.TOO_LONG
    else -> null
}

private val setLogH3 by lazy { H3Core.newInstance() }

internal fun provisionalSetLogCellId(coordinates: PhotoCoordinates?): String? = coordinates
    ?.takeIf(PhotoCoordinates::isValidSetLogCoordinate)
    ?.let {
        runCatching {
            setLogH3.h3ToString(
                setLogH3.latLngToCell(it.latitude, it.longitude, SET_LOG_H3_RESOLUTION),
            )
        }.getOrNull()
    }

private fun PhotoCoordinates.isValidSetLogCoordinate() =
    latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0

internal sealed interface SetLogTripPreflight {
    data object NoActiveTrip : SetLogTripPreflight
    data class Selected(val trip: TripSummary) : SetLogTripPreflight
    data class Choose(val trips: List<TripSummary>) : SetLogTripPreflight
}

internal fun setLogTripPreflight(trips: List<TripSummary>): SetLogTripPreflight {
    val eligible = trips
        .filter { it.mode == "active" || it.mode == "dormant" }
        .sortedBy(TripSummary::id)
    return when (eligible.size) {
        0 -> SetLogTripPreflight.NoActiveTrip
        1 -> SetLogTripPreflight.Selected(eligible.single())
        else -> SetLogTripPreflight.Choose(eligible)
    }
}
