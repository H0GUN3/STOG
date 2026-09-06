package com.stog.app.feature.space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogTextContract
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted

@Composable
internal fun PlaceCandidateCard(
    candidate: PlaceSearchCandidate,
    baseUrl: String,
    saved: Boolean,
    saving: Boolean,
    onSelect: () -> Unit,
    onSave: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        border = BorderStroke(1.dp, StogBorder.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val photoUrl = candidate.photoUrls.firstOrNull()
                ?: candidate.photoNames.firstOrNull()?.let { photoName ->
                    placePhotoUrl(baseUrl, photoName)
                }
            PlacePhotoTile(
                url = photoUrl,
                modifier = Modifier.size(88.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = StogInk,
                    maxLines = StogTextContract.PlaceTitleMaxLines,
                    softWrap = StogTextContract.PlaceTitleSoftWrap,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = humanizedPlaceCategory(candidate.types),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StogMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                candidate.address?.let { address ->
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StogMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onSave,
                enabled = !saved && !saving,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = if (saved) {
                            "${candidate.name} 담김"
                        } else {
                            "${candidate.name} 담기"
                        }
                    },
            ) {
                BookmarkGlyph(filled = saved)
            }
        }
    }
}

@Composable
private fun BookmarkGlyph(filled: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val path = Path().apply {
            moveTo(size.width * .25f, size.height * .12f)
            lineTo(size.width * .75f, size.height * .12f)
            lineTo(size.width * .75f, size.height * .88f)
            lineTo(size.width * .5f, size.height * .69f)
            lineTo(size.width * .25f, size.height * .88f)
            close()
        }
        if (filled) {
            drawPath(path, StogInk)
        } else {
            drawPath(
                path,
                StogInk,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
