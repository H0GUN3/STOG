package com.stog.app.feature.space

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PlaceSummaryLines(
    details: PlaceDetails,
    distanceMeters: Float?,
    showAddress: Boolean = true,
) {
    val rating = placeRatingSummary(details.rating, details.userRatingCount)
    val opening = placeOpeningSummary(
        details.businessStatus,
        details.openNow,
        details.nextCloseTime,
    )
    if (rating != null || opening != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            rating?.let {
                Text(
                    text = "★ $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StogYellow,
                )
            }
            opening?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StogInk,
                )
            }
        }
    }
    if (showAddress) {
        listOfNotNull(
            formatPlaceDistance(distanceMeters),
            details.address,
        ).takeIf(List<String>::isNotEmpty)?.let { values ->
            Text(
                text = values.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = StogMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PlacePhotoRow(
    details: PlaceDetails,
    baseUrl: String,
) {
    val photos = buildList {
        addAll(details.photoUrls.take(2))
        addAll(
            details.photoNames
                .take((2 - size).coerceAtLeast(0))
                .map { placePhotoUrl(baseUrl, it) },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tileModifier = Modifier.weight(1f)
        if (photos.isEmpty()) {
            PlacePhotoTile(url = null, modifier = tileModifier)
        } else {
            photos.forEach { photoUrl ->
                PlacePhotoTile(
                    url = photoUrl,
                    modifier = tileModifier,
                )
            }
        }
    }
}

@Composable
internal fun PlacePhotoTile(
    url: String?,
    modifier: Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(url != null) }

    LaunchedEffect(url) {
        bitmap = if (url == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { loadPlacePhoto(url) }.getOrNull()
            }
        }
        loading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(StogCanvas),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "장소 사진",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                painter = painterResource(R.drawable.stog_travel_alley),
                contentDescription = "STOG 기본 장소 이미지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = StogYellow,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
internal fun PlaceInformationRow(
    label: String,
    value: String,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(.34f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasized) StogInk else StogMuted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(.66f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasized) StogInk else StogMuted,
        )
    }
}

@Composable
internal fun PlaceActionBar(
    details: PlaceDetails,
    expanded: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceBookmarkButton(onClick = onSave, modifier = modifier)
}

@Composable
internal fun PlaceBookmarkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(StogCanvas)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "장소 저장"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val bookmark = Path().apply {
                moveTo(size.width * .28f, size.height * .15f)
                lineTo(size.width * .72f, size.height * .15f)
                lineTo(size.width * .72f, size.height * .85f)
                lineTo(size.width * .5f, size.height * .68f)
                lineTo(size.width * .28f, size.height * .85f)
                close()
            }
            drawPath(
                path = bookmark,
                color = StogInk,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

internal enum class PlaceSheetControlKind {
    Close,
    Collapse,
}

@Composable
internal fun PlaceSheetControl(
    kind: PlaceSheetControlKind,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(StogSurface.copy(alpha = .92f))
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            when (kind) {
                PlaceSheetControlKind.Close -> {
                    drawLine(
                        StogInk,
                        start = androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .25f),
                        end = androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .75f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        StogInk,
                        start = androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .25f),
                        end = androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .75f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                PlaceSheetControlKind.Collapse -> {
                    drawLine(
                        StogInk,
                        start = androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .4f),
                        end = androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .65f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        StogInk,
                        start = androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .65f),
                        end = androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .4f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private enum class PlaceAction(val label: String) {
    Save("저장"),
    Share("공유"),
    Call("전화"),
    Directions("길찾기"),
    Origin("출발"),
    Destination("도착"),
    ;

    fun isAvailable(details: PlaceDetails): Boolean = when (this) {
        Save -> true
        Share -> details.address != null || details.googleMapsUri != null
        Call -> details.nationalPhoneNumber != null
        Directions -> details.googleMapsUri != null || details.hasCoordinates()
        Origin, Destination -> details.hasCoordinates()
    }
}

@Composable
private fun PlaceActionGlyph(action: PlaceAction) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        when (action) {
            PlaceAction.Save -> {
                val bookmark = Path().apply {
                    moveTo(size.width * .28f, size.height * .15f)
                    lineTo(size.width * .72f, size.height * .15f)
                    lineTo(size.width * .72f, size.height * .85f)
                    lineTo(size.width * .5f, size.height * .68f)
                    lineTo(size.width * .28f, size.height * .85f)
                    close()
                }
                drawPath(bookmark, StogInk, style = stroke)
            }
            PlaceAction.Share -> {
                drawLine(StogInk, center, androidx.compose.ui.geometry.Offset(center.x, size.height * .12f), stroke.width, StrokeCap.Round)
                drawLine(StogInk, androidx.compose.ui.geometry.Offset(center.x, size.height * .12f), androidx.compose.ui.geometry.Offset(size.width * .34f, size.height * .3f), stroke.width, StrokeCap.Round)
                drawLine(StogInk, androidx.compose.ui.geometry.Offset(center.x, size.height * .12f), androidx.compose.ui.geometry.Offset(size.width * .66f, size.height * .3f), stroke.width, StrokeCap.Round)
                drawRect(StogInk, androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .42f), androidx.compose.ui.geometry.Size(size.width * .56f, size.height * .42f), style = stroke)
            }
            PlaceAction.Call -> {
                val phone = Path().apply {
                    moveTo(size.width * .28f, size.height * .16f)
                    cubicTo(size.width * .16f, size.height * .35f, size.width * .35f, size.height * .72f, size.width * .7f, size.height * .84f)
                    lineTo(size.width * .84f, size.height * .64f)
                    lineTo(size.width * .65f, size.height * .52f)
                    lineTo(size.width * .52f, size.height * .64f)
                    cubicTo(size.width * .42f, size.height * .59f, size.width * .36f, size.height * .5f, size.width * .33f, size.height * .4f)
                    lineTo(size.width * .46f, size.height * .28f)
                    close()
                }
                drawPath(phone, StogInk, style = stroke)
            }
            PlaceAction.Directions -> {
                val arrow = Path().apply {
                    moveTo(size.width * .15f, size.height * .5f)
                    lineTo(size.width * .78f, size.height * .18f)
                    lineTo(size.width * .58f, size.height * .82f)
                    lineTo(size.width * .48f, size.height * .56f)
                    close()
                }
                drawPath(arrow, StogInk, style = stroke)
            }
            PlaceAction.Origin -> {
                drawCircle(StogInk, radius = size.minDimension * .13f, center = center, style = stroke)
                drawLine(StogInk, androidx.compose.ui.geometry.Offset(center.x, size.height * .12f), androidx.compose.ui.geometry.Offset(center.x, size.height * .34f), stroke.width, StrokeCap.Round)
                drawLine(StogInk, androidx.compose.ui.geometry.Offset(center.x, size.height * .66f), androidx.compose.ui.geometry.Offset(center.x, size.height * .88f), stroke.width, StrokeCap.Round)
            }
            PlaceAction.Destination -> {
                drawCircle(StogInk, radius = size.minDimension * .22f, center = androidx.compose.ui.geometry.Offset(center.x, size.height * .38f), style = stroke)
                drawLine(StogInk, androidx.compose.ui.geometry.Offset(center.x, size.height * .6f), androidx.compose.ui.geometry.Offset(center.x, size.height * .88f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

private fun Context.performPlaceAction(action: PlaceAction, details: PlaceDetails) {
    val intent = when (action) {
        PlaceAction.Share -> Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                listOfNotNull(details.name, details.address, details.googleMapsUri)
                    .joinToString("\n"),
            )
        }.let { Intent.createChooser(it, "장소 공유") }
        PlaceAction.Call -> Intent(
            Intent.ACTION_DIAL,
            Uri.parse("tel:${Uri.encode(details.nationalPhoneNumber)}"),
        )
        PlaceAction.Directions -> Intent(
            Intent.ACTION_VIEW,
            Uri.parse(details.googleMapsUri ?: details.mapsDirectionsUri("destination")),
        )
        PlaceAction.Origin -> Intent(
            Intent.ACTION_VIEW,
            Uri.parse(details.mapsDirectionsUri("origin")),
        )
        PlaceAction.Destination -> Intent(
            Intent.ACTION_VIEW,
            Uri.parse(details.mapsDirectionsUri("destination")),
        )
        PlaceAction.Save -> return
    }
    runCatching { startActivity(intent) }
}

private fun PlaceDetails.hasCoordinates(): Boolean =
    latitude?.isFinite() == true && longitude?.isFinite() == true

private fun PlaceDetails.mapsDirectionsUri(parameter: String): String =
    "https://www.google.com/maps/dir/?api=1&$parameter=$latitude,$longitude"
