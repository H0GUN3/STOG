package com.stog.app.feature.plan.trip

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.feature.space.loadPlacePhoto
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogYellow
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

@Composable
internal fun TripCardContent(
    card: TripCardData,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .stogTouchTarget()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TripCardImage(
                url = card.imageUrl,
                fallbackRes = card.imageRes,
                contentDescription = "${card.trip.title} 대표 이미지",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.trip.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TripStatusChip(tripCategoryFor(card.trip, today))
                }
                Text(
                    card.dateLabel,
                    color = StogMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                card.memberLabel?.let { members ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_group),
                            contentDescription = "참여자",
                            tint = StogMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            " $members",
                            color = StogMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_location),
                        contentDescription = null,
                        tint = StogMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        " 장소 ${card.placeCount}개",
                        color = StogMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = "여행 일정 열기",
                tint = StogMuted,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
internal fun TripStatusChip(category: TripListCategory) {
    val label = when (category) {
        TripListCategory.ACTIVE -> "진행 중"
        TripListCategory.UPCOMING -> "예정"
        TripListCategory.COMPLETED -> "완료"
        TripListCategory.ALL -> return
    }
    Surface(
        color = if (category == TripListCategory.ACTIVE) {
            StogYellow.copy(alpha = 0.28f)
        } else {
            StogBorder.copy(alpha = 0.35f)
        },
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            color = StogMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun TripCardImage(
    url: String?,
    fallbackRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(url != null) }

    LaunchedEffect(url) {
        bitmap = url?.let {
            withContext(Dispatchers.IO) {
                runCatching { loadPlacePhoto(it) }
                    .onFailure { error ->
                        Log.w("STOG.Image", "card image load failed", error)
                    }
                    .getOrNull()
            }
        }
        loading = false
    }

    Box(
        modifier = modifier
            .background(StogCanvas, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                painter = painterResource(fallbackRes),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = StogYellow)
        }
    }
}
