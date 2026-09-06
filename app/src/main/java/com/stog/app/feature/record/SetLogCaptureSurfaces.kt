package com.stog.app.feature.record

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stog.app.R
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogDarkCanvas
import com.stog.app.ui.theme.StogDarkSurface
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogWhite
import com.stog.app.ui.theme.StogYellow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SET_LOG_CLOSE_TAG = "set_log_capture_close"
internal const val SET_LOG_FLASH_TAG = "set_log_capture_flash"
internal const val SET_LOG_GALLERY_TAG = "set_log_capture_gallery"
internal const val SET_LOG_SHUTTER_TAG = "set_log_capture_shutter"
internal const val SET_LOG_SWITCH_TAG = "set_log_capture_switch"
internal const val SET_LOG_CONFIRMATION_TAG = "set_log_confirmation"
internal const val SET_LOG_RECORD_TAG = "set_log_record"
internal const val SET_LOG_RETAKE_TAG = "set_log_retake"
internal val SET_LOG_CAPTURE_CONTROL_TAGS = listOf(
    SET_LOG_CLOSE_TAG,
    SET_LOG_FLASH_TAG,
    SET_LOG_GALLERY_TAG,
    SET_LOG_SHUTTER_TAG,
    SET_LOG_SWITCH_TAG,
)

internal enum class SetLogLocationFeedback {
    ACQUIRING,
    AVAILABLE,
    PERMISSION_REQUIRED,
    PROVIDER_DISABLED,
    TIMEOUT,
    STALE_FIX,
    INACCURATE_FIX,
    UNAVAILABLE,
}

@Composable
internal fun SetLogCaptureSurface(
    previewView: PreviewView?,
    stage: SetLogCaptureStage,
    capabilities: SetLogCameraCapabilities?,
    locationFeedback: SetLogLocationFeedback,
    placeLabel: String?,
    onClose: () -> Unit,
    onFlash: () -> Unit,
    onGallery: () -> Unit,
    onShutter: () -> Unit,
    onSwitchLens: () -> Unit,
    onLocationSettings: () -> Unit = {},
    onPermissionSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ready = stage == SetLogCaptureStage.READY
    val shutterEnabled = stage in setOf(
        SetLogCaptureStage.PERMISSION_REQUIRED,
        SetLogCaptureStage.READY,
        SetLogCaptureStage.ERROR,
    ) && locationFeedback != SetLogLocationFeedback.ACQUIRING
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StogDarkCanvas)
            .testTag("set_log_capture_preview")
            .semantics { stateDescription = "capture:${stage.name.lowercase()}" },
    ) {
        if (previewView != null) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize().testTag("set_log_camera_preview_view"),
                update = { it.post(it::requestLayout) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = StogUiContract.BaseSpacingDp.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CaptureIconAction(SET_LOG_CLOSE_TAG, R.drawable.ic_set_log_close, "Close capture", true, onClose)
            CaptureIconAction(
                SET_LOG_FLASH_TAG,
                R.drawable.ic_set_log_flash,
                capabilities?.selectedFlashMode?.label ?: "Flash unavailable",
                ready && capabilities?.flashAvailable == true,
                onFlash,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(StogUiContract.ScreenGutterDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
        ) {
            LocationFeedback(
                feedback = locationFeedback,
                placeLabel = placeLabel,
                onLocationSettings = onLocationSettings,
                onPermissionSettings = onPermissionSettings,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureIconAction(SET_LOG_GALLERY_TAG, R.drawable.ic_set_log_gallery, "Open gallery", true, onGallery)
                IconButton(
                    onClick = onShutter,
                    enabled = shutterEnabled,
                    modifier = Modifier
                        .size(StogUiContract.SetLogShutterSizeDp.dp)
                        .border(3.dp, if (shutterEnabled) StogWhite else StogMuted, CircleShape)
                        .padding(StogUiContract.BaseSpacingDp.dp / 2)
                        .background(if (shutterEnabled) StogWhite else StogDarkSurface, CircleShape)
                        .testTag(SET_LOG_SHUTTER_TAG)
                        .semantics {
                            contentDescription = if (ready) "Capture photo" else "Resolve capture requirements"
                            role = Role.Button
                        },
                ) {}
                val canSwitch = ready && capabilities?.let { value ->
                    value.lensAvailable(SetLogCameraLens.REAR) && value.lensAvailable(SetLogCameraLens.FRONT)
                } == true
                CaptureIconAction(
                    SET_LOG_SWITCH_TAG,
                    R.drawable.ic_set_log_switch_camera,
                    "Switch camera lens",
                    canSwitch,
                    onSwitchLens,
                )
            }
        }
    }
}

@Composable
private fun CaptureIconAction(
    tag: String,
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    contentColor: Color = StogWhite,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(StogUiContract.MinTouchTargetDp.dp)
            .testTag(tag)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = contentColor,
            disabledContentColor = StogMuted,
        ),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(StogUiContract.SetLogIconSizeDp.dp),
        )
    }
}

@Composable
private fun LocationFeedback(
    feedback: SetLogLocationFeedback,
    placeLabel: String?,
    onLocationSettings: () -> Unit,
    onPermissionSettings: () -> Unit,
) {
    val machineState = feedback.name.lowercase()
    val detail = when (feedback) {
        SetLogLocationFeedback.ACQUIRING -> "Checking the current location"
        SetLogLocationFeedback.AVAILABLE -> placeLabel ?: "Current location is ready"
        SetLogLocationFeedback.PERMISSION_REQUIRED -> "Location permission is required to record a camera photo"
        SetLogLocationFeedback.PROVIDER_DISABLED -> "Turn on a location provider to record a camera photo"
        SetLogLocationFeedback.TIMEOUT -> "The current location was not received in time"
        SetLogLocationFeedback.STALE_FIX -> "The current location is too old to record this photo"
        SetLogLocationFeedback.INACCURATE_FIX -> "The current location is not accurate enough to record this photo"
        SetLogLocationFeedback.UNAVAILABLE -> "A policy-compliant current location is not available"
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(StogUiContract.LargeRadiusDp.dp))
            .background(StogDarkSurface.copy(alpha = 0.9f))
            .padding(horizontal = StogUiContract.BaseSpacingDp.dp * 2, vertical = StogUiContract.BaseSpacingDp.dp)
            .testTag("set_log_location_feedback")
            .semantics {
                stateDescription = machineState
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp / 2),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_set_log_location),
                contentDescription = null,
                tint = StogYellow,
                modifier = Modifier.size(StogUiContract.SetLogIconSizeDp.dp),
            )
            Text(detail, color = StogWhite, style = MaterialTheme.typography.bodySmall)
        }
        if (feedback == SetLogLocationFeedback.PROVIDER_DISABLED) {
            TextButton(
                onClick = onLocationSettings,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("set_log_location_settings"),
            ) {
                Text("위치 설정 열기", color = StogYellow)
            }
        }
        if (feedback == SetLogLocationFeedback.PERMISSION_REQUIRED) {
            TextButton(
                onClick = onPermissionSettings,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("set_log_permission_settings"),
            ) {
                Text("권한 설정 열기", color = StogYellow)
            }
        }
    }
}

@Composable
internal fun SetLogConfirmationSurface(
    draft: SetLogDraft,
    previewPath: String?,
    recordTitle: String = "여행 기록",
    modifier: Modifier = Modifier,
    stage: SetLogCaptureStage,
    onCaptionChanged: (String) -> Unit,
    onVisibilityChanged: (SetLogVisibilityIntent) -> Unit,
    onRetake: () -> Unit,
    onRecord: () -> Unit,
    onClose: () -> Unit,
    onDownload: () -> Unit = {},
    hasDurablePendingRecord: Boolean = false,
) {
    val busy = stage in setOf(SetLogCaptureStage.SAVING, SetLogCaptureStage.PENDING_SYNC, SetLogCaptureStage.SAVED)
    val canEdit = stage == SetLogCaptureStage.CONFIRMING
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StogCanvas)
            .testTag(SET_LOG_CONFIRMATION_TAG)
            .semantics { stateDescription = "confirmation:${stage.name.lowercase()}" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = StogUiContract.BaseSpacingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CaptureIconAction(
                SET_LOG_CLOSE_TAG,
                R.drawable.ic_chevron_left,
                "Close confirmation",
                true,
                onClose,
                contentColor = StogInk,
            )
            Text(
                "기록 확인",
                modifier = Modifier.padding(start = StogUiContract.BaseSpacingDp.dp),
                color = StogInk,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("set_log_confirmation_body"),
            contentPadding = PaddingValues(
                start = StogUiContract.ScreenGutterDp.dp,
                end = StogUiContract.ScreenGutterDp.dp,
                bottom = StogUiContract.BaseSpacingDp.dp * 2,
            ),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("set_log_record_context"),
                    colors = recordCardColors(),
                    shape = RecordCardShape,
                    border = RecordCardBorder,
                    elevation = CardDefaults.cardElevation(defaultElevation = RecordCardElevation),
                ) {
                    Row(
                        modifier = Modifier.padding(StogUiContract.BaseSpacingDp.dp * 2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 1.5f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera),
                            contentDescription = null,
                            tint = StogInk,
                            modifier = Modifier.size(32.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                if (recordTitle == "일상") "일상 기록" else recordTitle,
                                color = StogInk,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (recordTitle == "일상") "오늘의 순간을 남겨요" else "여행의 순간을 남겨요",
                                color = StogMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(StogUiContract.BaseSpacingDp.dp * 2))
                LocalDerivativePreview(previewPath)
                Spacer(Modifier.height(StogUiContract.BaseSpacingDp.dp * 2))
                Text(
                    "사진 정보",
                    color = StogInk,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(StogUiContract.BaseSpacingDp.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = recordCardColors(),
                    shape = RecordCardShape,
                    border = RecordCardBorder,
                    elevation = CardDefaults.cardElevation(defaultElevation = RecordCardElevation),
                ) {
                    Column(
                        modifier = Modifier.padding(StogUiContract.BaseSpacingDp.dp * 2),
                        verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
                    ) {
                        LocationMetadataRow(draft)
                        HorizontalDivider(color = StogBorder)
                        MetadataRow(
                            R.drawable.ic_set_log_clock,
                            "capture_time",
                            formatSetLogTime(draft.snapshot.takenAt),
                        )
                        HorizontalDivider(color = StogBorder)
                        MetadataRow(
                            R.drawable.ic_set_log_gallery,
                            "source_provenance",
                            sourceProvenanceValue(draft.snapshot),
                            sourceProvenanceState(draft.snapshot),
                        )
                    }
                }
                Spacer(Modifier.height(StogUiContract.BaseSpacingDp.dp * 2))
                Text(
                    "기록 내용",
                    color = StogInk,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(StogUiContract.BaseSpacingDp.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = recordCardColors(),
                    shape = RecordCardShape,
                    border = RecordCardBorder,
                    elevation = CardDefaults.cardElevation(defaultElevation = RecordCardElevation),
                ) {
                    Column(
                        modifier = Modifier.padding(StogUiContract.BaseSpacingDp.dp * 2),
                        verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
                    ) {
                        Text("한 줄 기록", color = StogInk, style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = draft.caption,
                            onValueChange = onCaptionChanged,
                            enabled = canEdit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("set_log_caption")
                                .semantics {
                                    stateDescription = draft.captionIssue?.name?.lowercase() ?: "valid"
                                },
                            singleLine = true,
                            isError = draft.captionIssue != null,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = StogInk,
                                unfocusedIndicatorColor = StogBorder,
                                errorIndicatorColor = MaterialTheme.colorScheme.error,
                                cursorColor = StogInk,
                            ),
                            placeholder = { Text("이 순간을 짧게 남겨보세요", color = StogMuted) },
                            supportingText = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    draft.captionIssue?.let { issue ->
                                        Text(
                                            if (issue == SetLogNoteIssue.MULTILINE) {
                                                "한 줄로 입력해주세요."
                                            } else {
                                                "120자 이내로 입력해주세요."
                                            },
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Text(
                                        "${draft.caption.codePointCount(0, draft.caption.length)}/$SET_LOG_NOTE_MAX_CODE_POINTS",
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.End,
                                    )
                                }
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp)) {
                            Text("공개 여부", color = StogInk, style = MaterialTheme.typography.titleSmall)
                            SetLogVisibilitySelector(
                                selected = draft.visibilityIntent,
                                enabled = canEdit,
                                onSelected = onVisibilityChanged,
                            )
                            Text(
                                if (draft.visibilityIntent == SetLogVisibilityIntent.PUBLIC) {
                                    "공개 여행에 올리면 발견 게시물로 바로 노출돼요."
                                } else {
                                    "나만 보기로 저장되며 공개 여부는 나중에도 변경할 수 있어요."
                                },
                                color = StogMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .testTag("set_log_publication_explanation")
                                    .semantics {
                                        stateDescription = if (draft.visibilityIntent == SetLogVisibilityIntent.PUBLIC) {
                                            "public_immediate_discovery"
                                        } else {
                                            "private"
                                        }
                                    },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(StogUiContract.BaseSpacingDp.dp * 2))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("set_log_location_notice")
                        .semantics { stateDescription = setLogLocationNoticeState(draft) },
                    colors = recordCardColors(),
                    shape = RecordCardShape,
                    border = RecordCardBorder,
                    elevation = CardDefaults.cardElevation(defaultElevation = RecordCardElevation),
                ) {
                    Row(
                        modifier = Modifier.padding(StogUiContract.BaseSpacingDp.dp * 1.5f),
                        horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ⓘ", color = StogYellow, style = MaterialTheme.typography.titleMedium)
                        Text(
                            setLogLocationNotice(draft),
                            color = StogMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                    if (stage in setOf(SetLogCaptureStage.SAVING, SetLogCaptureStage.PENDING_SYNC, SetLogCaptureStage.SAVED, SetLogCaptureStage.FAILED)) {
                        Spacer(Modifier.height(StogUiContract.BaseSpacingDp.dp * 2))
                        Text(
                            deliveryStateValue(stage, draft.publicationState),
                            color = StogMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .testTag("set_log_delivery_status")
                                .semantics {
                                    stateDescription = stage.name.lowercase()
                                    liveRegion = LiveRegionMode.Polite
                                },
                        )
                }
            }
        }
        HorizontalDivider(color = StogBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StogSurface)
                .navigationBarsPadding()
                .padding(StogUiContract.BaseSpacingDp.dp * 2),
            horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            if (stage == SetLogCaptureStage.SAVED) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).height(StogUiContract.HomeCameraActionSizeDp.dp),
                ) { Text("닫기") }
                Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .weight(1f)
                        .height(StogUiContract.HomeCameraActionSizeDp.dp)
                        .testTag("capture_download_action"),
                    colors = ButtonDefaults.buttonColors(containerColor = StogYellow, contentColor = StogInk),
                ) { Text("기기에 다운로드") }
            } else {
                OutlinedButton(
                    onClick = onRetake,
                    enabled = !busy && (stage != SetLogCaptureStage.FAILED || !hasDurablePendingRecord),
                    modifier = Modifier.weight(1f).height(StogUiContract.HomeCameraActionSizeDp.dp).testTag(SET_LOG_RETAKE_TAG),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(StogUiContract.BaseSpacingDp.dp))
                    Text("다시 촬영")
                }
                Button(
                    onClick = onRecord,
                    enabled = stage == SetLogCaptureStage.CONFIRMING && draft.canRecord && !busy,
                    modifier = Modifier.weight(1f).height(StogUiContract.HomeCameraActionSizeDp.dp).testTag(SET_LOG_RECORD_TAG),
                    colors = ButtonDefaults.buttonColors(containerColor = StogYellow, contentColor = StogInk),
                ) { Text("이 사진 기록하기") }
            }
        }
    }
}

@Composable
private fun LocalDerivativePreview(path: String?) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { path?.let(BitmapFactory::decodeFile) }
    }
    Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
            .clip(
                RoundedCornerShape(
                    topStart = StogUiContract.LargeRadiusDp.dp,
                    topEnd = StogUiContract.LargeRadiusDp.dp,
                ),
            )
            .background(StogDarkSurface)
            .testTag("set_log_local_preview")
            .semantics {
                contentDescription = "촬영한 사진 미리보기"
                role = Role.Image
                stateDescription = if (bitmap == null) "loading_or_unavailable" else "available"
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
                            
@Composable
private fun LocationMetadataRow(draft: SetLogDraft) {
    val snapshot = draft.snapshot
    val coordinates = snapshot.coordinates
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("set_log_metadata:place")
            .semantics { stateDescription = "place:${placeValue(draft.placePreview)}" },
        horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetadataIcon(R.drawable.ic_set_log_location)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                locationTitle(draft),
                color = StogInk,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                coordinates?.let(::formatSetLogCoordinates) ?: "위치 정보 없음",
                color = StogMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        snapshot.provisionalCellId?.let { cellId ->
            Text(
                "H3 셀 $cellId",
                modifier = Modifier
                    .background(
                        StogYellow.copy(alpha = 0.18f),
                        RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = StogUiContract.BaseSpacingDp.dp, vertical = 6.dp)
                    .testTag("set_log_location_cell"),
                color = StogInk,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MetadataIcon(@DrawableRes icon: Int) {
    Surface(
        color = StogYellow.copy(alpha = 0.18f),
        shape = CircleShape,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = StogInk,
                modifier = Modifier.size(StogUiContract.SetLogIconSizeDp.dp),
            )
        }
    }
}

@Composable
private fun SetLogVisibilitySelector(
    selected: SetLogVisibilityIntent,
    enabled: Boolean,
    onSelected: (SetLogVisibilityIntent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
    ) {
        SetLogVisibilityIntent.entries.forEach { intent ->
            OutlinedButton(
                onClick = { onSelected(intent) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(StogUiContract.MinTouchTargetDp.dp)
                    .semantics {
                        this.selected = selected == intent
                        role = Role.RadioButton
                    }
                    .testTag("set_log_visibility:${intent.name.lowercase()}"),
                shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected == intent) StogInk else StogBorder,
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected == intent) {
                        StogInk.copy(alpha = 0.08f)
                    } else {
                        StogSurface
                    },
                    contentColor = StogInk,
                    disabledContainerColor = StogSurface,
                    disabledContentColor = StogMuted,
                ),
                contentPadding = PaddingValues(horizontal = StogUiContract.BaseSpacingDp.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(
                            if (intent == SetLogVisibilityIntent.PRIVATE) {
                                R.drawable.ic_set_log_visibility
                            } else {
                                R.drawable.ic_set_log_public
                            },
                        ),
                        contentDescription = null,
                        tint = StogInk,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        if (intent == SetLogVisibilityIntent.PRIVATE) "나만 보기" else "공개",
                        color = StogInk,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected == intent) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(
    @DrawableRes icon: Int,
    key: String,
    value: String,
    stateValue: String = value,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("set_log_metadata:$key")
            .semantics { stateDescription = "$key:$stateValue" },
        horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetadataIcon(icon)
        Text(value, color = StogInk, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun locationTitle(draft: SetLogDraft): String = when (val place = draft.placePreview) {
    is SetLogPlacePreview.Matched -> place.placeName
    SetLogPlacePreview.Pending -> if (draft.snapshot.coordinates == null) "위치 정보 없음" else "위치 확인 중"
    SetLogPlacePreview.NoMatch -> if (draft.snapshot.coordinates == null) "위치 정보 없음" else "위치 기록됨"
    is SetLogPlacePreview.Failed -> if (draft.snapshot.coordinates == null) "위치 확인 실패" else "위치 기록됨"
}

internal fun formatSetLogCoordinates(coordinates: PhotoCoordinates): String =
    String.format(java.util.Locale.US, "%.4f, %.4f", coordinates.latitude, coordinates.longitude)

private fun sourceProvenanceValue(snapshot: SetLogSnapshot): String = when {
    snapshot.source == PhotoSource.CAMERA -> "STOG 카메라 촬영"
    snapshot.locationProvenance == SetLogLocationProvenance.GALLERY_EXIF &&
        snapshot.takenAtProvenance == SetLogTakenAtProvenance.GALLERY_EXIF -> "갤러리 사진 · 위치/촬영 시간"
    snapshot.locationProvenance == SetLogLocationProvenance.GALLERY_EXIF -> "갤러리 사진 · 위치만 확인"
    snapshot.takenAtProvenance == SetLogTakenAtProvenance.GALLERY_EXIF -> "갤러리 사진 · 촬영 시간만 확인"
    else -> "갤러리 사진 · 위치/촬영 시간 없음"
}

private fun sourceProvenanceState(snapshot: SetLogSnapshot): String = when {
    snapshot.source == PhotoSource.CAMERA -> "camera_foreground"
    snapshot.locationProvenance == SetLogLocationProvenance.GALLERY_EXIF &&
        snapshot.takenAtProvenance == SetLogTakenAtProvenance.GALLERY_EXIF -> "gallery_exif_location_time"
    snapshot.locationProvenance == SetLogLocationProvenance.GALLERY_EXIF -> "gallery_exif_location_time_unknown"
    snapshot.takenAtProvenance == SetLogTakenAtProvenance.GALLERY_EXIF -> "gallery_archive_only_exif_time"
    else -> "gallery_archive_only_time_unknown"
}

internal fun setLogLocationNotice(draft: SetLogDraft): String = when {
    draft.snapshot.source == PhotoSource.GALLERY && draft.snapshot.coordinates == null ->
        "위치 정보가 없는 사진은 지도에 표시되지 않고 보관함에만 저장됩니다."
    draft.snapshot.coordinates == null ->
        "카메라 위치가 확보되면 사진과 기록이 지도에 반영됩니다."
    else -> "저장 시 사진과 기록이 위치 정보와 함께 지도에 반영됩니다."
}

internal fun setLogLocationNoticeState(draft: SetLogDraft): String = when {
    draft.snapshot.source == PhotoSource.GALLERY && draft.snapshot.coordinates == null -> "gallery_archive_only"
    draft.snapshot.coordinates == null -> "camera_location_required"
    else -> "location_recorded"
}

internal fun placeValue(place: SetLogPlacePreview): String = when (place) {
    SetLogPlacePreview.Pending -> "pending"
    is SetLogPlacePreview.Matched -> "matched:${place.placeId}:${place.placeName}"
    SetLogPlacePreview.NoMatch -> "no_match"
    is SetLogPlacePreview.Failed -> "pending:${place.code}"
}

internal fun galleryProvenanceValue(snapshot: SetLogSnapshot): String = when {
    snapshot.locationProvenance == SetLogLocationProvenance.GALLERY_EXIF &&
        snapshot.takenAtProvenance == SetLogTakenAtProvenance.GALLERY_EXIF -> "gallery_exif_location_time"
    snapshot.locationProvenance == SetLogLocationProvenance.GALLERY_EXIF -> "gallery_exif_location_time_unknown"
    snapshot.takenAtProvenance == SetLogTakenAtProvenance.GALLERY_EXIF -> "gallery_archive_only_exif_time"
    else -> "gallery_archive_only_time_unknown"
}

internal fun formatSetLogTime(value: String?, zoneId: ZoneId = ZoneId.systemDefault()): String = value
    ?.let { runCatching { SET_LOG_TIME_FORMAT.format(Instant.parse(it).atZone(zoneId)) }.getOrNull() }
    ?: "날짜 정보 없음"

private fun deliveryStateValue(stage: SetLogCaptureStage, publication: SetLogPublicationState): String = when (stage) {
    SetLogCaptureStage.SAVING -> "저장 중"
    SetLogCaptureStage.PENDING_SYNC -> "동기화 대기 중"
    SetLogCaptureStage.SAVED -> when (publication) {
        SetLogPublicationState.PRIVATE -> "저장 완료 · 나만 보기"
        SetLogPublicationState.TRIP_NOT_PUBLIC -> "저장 완료 · 여행 공개 전"
        SetLogPublicationState.MODERATION_PENDING -> "저장 완료 · 공개 검토 중"
        SetLogPublicationState.PUBLIC -> "저장 완료 · 공개"
        SetLogPublicationState.NOT_RECORDED -> "저장 완료"
    }
    SetLogCaptureStage.FAILED -> "저장 실패"
    else -> stage.name.lowercase()
}

private val SET_LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
