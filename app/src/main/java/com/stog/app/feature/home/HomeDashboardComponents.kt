package com.stog.app.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stog.app.feature.plan.trip.TripCardContent
import com.stog.app.feature.plan.trip.TripCardData
import com.stog.app.feature.plan.trip.TripCardImage
import com.stog.app.R
import com.stog.app.feature.space.EventSearchCandidate
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.StogUiContract
import com.stog.app.feature.space.humanizedPlaceCategory
import com.stog.app.feature.space.placePhotoUrl
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow
import java.time.LocalDate

@Composable
internal fun HomeHero(
    nickname: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(226.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.stog_home_hero),
            contentDescription = "한옥과 호수가 있는 여행 일러스트",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp, end = 160.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "안녕하세요, ${nickname ?: "여행자"}님!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = StogInk,
            )
            Text(
                text = "오늘도 새로운 순간을\n기록해보세요.",
                style = MaterialTheme.typography.bodyLarge,
                color = StogMuted,
            )
        }
    }
}

@Composable
internal fun HomeMetricRow(
    monthlyTrips: Long,
    visitedCells: Long,
    savedPlaces: Long,
    receivedLikes: Long,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { HomeMetricCard("이번 달 여행", monthlyTrips, "회", HomeGlyph.GROUP) }
        item { HomeMetricCard("나의 셀", visitedCells, "개", HomeGlyph.CELL) }
        item { HomeMetricCard("저장한 장소", savedPlaces, "개", HomeGlyph.BOOKMARK) }
        item { HomeMetricCard("받은 좋아요", receivedLikes, "개", HomeGlyph.FAVORITE) }
    }
}

@Composable
private fun HomeMetricCard(
    label: String,
    value: Long,
    unit: String,
    icon: HomeGlyph,
) {
    Card(
        modifier = Modifier.width(154.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = StogInk)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = StogInk,
                )
                Text(unit, color = StogInk, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(StogYellow.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeGlyphIcon(icon)
                }
            }
        }
    }
}

@Composable
internal fun HomeSectionTitle(
    title: String,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onMore != null) {
                Row(
                    modifier = Modifier
                        .stogTouchTarget()
                        .clickable(onClick = onMore),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("더보기", color = StogMuted)
                    HomeGlyphIcon(HomeGlyph.CHEVRON, "더보기", StogMuted)
                }
            }
        }
    }
}

@Composable
internal fun HomeRecommendationCard(
    candidate: PlaceSearchCandidate,
    baseUrl: String,
    onSave: () -> Unit,
    onOpen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.width(248.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            TripCardImage(
                url = candidate.photoUrls.firstOrNull()
                    ?: candidate.photoNames.firstOrNull()?.let { placePhotoUrl(baseUrl, it) },
                fallbackRes = R.drawable.stog_travel_hanok,
                contentDescription = candidate.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f),
            )
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = candidate.types
                            .takeIf { it.isNotEmpty() }
                            ?.let(::humanizedPlaceCategory)
                            ?: "관광",
                        color = StogCanvas,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(StogInk)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onSave, modifier = Modifier.size(40.dp)) {
                        HomeGlyphIcon(HomeGlyph.BOOKMARK, "여행에 저장")
                    }
                }
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeGlyphIcon(HomeGlyph.LOCATION, tint = StogMuted)
                    Text(
                        text = candidate.address ?: "주소 정보 없음",
                        color = StogMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeEventCard(
    event: EventSearchCandidate,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.width(248.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column {
            TripCardImage(
                url = event.imageUri,
                fallbackRes = R.drawable.stog_travel_hanok,
                contentDescription = event.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f),
            )
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "행사",
                    color = StogInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(StogYellow)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = homeEventDateRange(event.startsOn, event.endsOn),
                    color = StogInk,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeGlyphIcon(HomeGlyph.LOCATION, tint = StogMuted)
                    Text(
                        text = event.venueName ?: event.address ?: "장소 정보 없음",
                        color = StogMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeTripCard(
    card: TripCardData,
    today: LocalDate = LocalDate.now(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = TripCardContent(
    card = card,
    today = today,
    onClick = onClick,
    modifier = modifier.height(140.dp),
)

@Composable
internal fun HomeCreateTripCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    role = Role.Button,
                    onClickLabel = "새 여행 만들기",
                    onClick = onClick,
                )
                .semantics { contentDescription = "새 여행 만들기" }
                .drawBehind {
                    drawRoundRect(
                        color = StogYellow.copy(alpha = 0.7f),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                            ),
                        ),
                    )
                }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .border(
                        androidx.compose.foundation.BorderStroke(1.dp, StogYellow),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleLarge,
                    color = StogYellow,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("새 여행 만들기", style = MaterialTheme.typography.titleMedium)
                Text(
                    "새로운 여행을 계획해보세요",
                    color = StogMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = "새 여행 만들기",
                tint = StogMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun HomeCaptureAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(StogUiContract.HomeCameraActionSizeDp.dp),
        shape = CircleShape,
        containerColor = StogYellow,
        contentColor = StogInk,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = "사진 기록 촬영",
        )
    }
}

internal enum class HomeGlyph(val resourceId: Int) {
    LOCATION(R.drawable.ic_location),
    BOOKMARK(R.drawable.ic_social_bookmark_outline),
    FAVORITE(R.drawable.ic_social_favorite_filled),
    FLAG(R.drawable.ic_location),
    GROUP(R.drawable.ic_group),
    CELL(R.drawable.ic_cell),
    CHEVRON(R.drawable.ic_chevron_right),
}

@Composable
internal fun HomeGlyphIcon(
    glyph: HomeGlyph,
    contentDescription: String? = null,
    tint: androidx.compose.ui.graphics.Color = StogInk,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(glyph.resourceId),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}

