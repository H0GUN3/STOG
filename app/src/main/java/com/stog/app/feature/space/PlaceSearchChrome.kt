package com.stog.app.feature.space

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stog.app.R
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogGoogleSurface
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow

@Composable
internal fun PlaceSearchCategoryRow(
    onCategorySelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        listOf("AI추천", "주유소", "음식점", "카페", "편의점").forEachIndexed { index, label ->
            Surface(
                modifier = Modifier.stogTouchTarget(),
                onClick = { onCategorySelected(label) },
                color = StogSurface,
                contentColor = StogInk,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    CategoryGlyph(index)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryGlyph(index: Int) {
    if (index == 0) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.stog_search_ai_icon),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(StogYellow),
            )
        }
        return
    }

    if (index == 4) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(StogInk, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "24",
                color = StogSurface,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }

    Canvas(Modifier.size(22.dp)) {
        val strokeWidth = 2.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        when (index) {
            1 -> {
                drawRect(
                    color = StogInk,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * .08f),
                    size = androidx.compose.ui.geometry.Size(size.width * .52f, size.height * .84f),
                    style = stroke,
                )
                drawLine(
                    color = StogInk,
                    start = androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .3f),
                    end = androidx.compose.ui.geometry.Offset(size.width * .54f, size.height * .3f),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = StogInk,
                    start = androidx.compose.ui.geometry.Offset(size.width * .64f, size.height * .28f),
                    end = androidx.compose.ui.geometry.Offset(size.width * .86f, size.height * .46f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            2 -> {
                listOf(.16f, .28f, .4f).forEach { x ->
                    drawLine(
                        StogInk,
                        androidx.compose.ui.geometry.Offset(size.width * x, size.height * .08f),
                        androidx.compose.ui.geometry.Offset(size.width * x, size.height * .42f),
                        strokeWidth,
                    )
                }
                drawLine(
                    StogInk,
                    androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .42f),
                    androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .92f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    StogInk,
                    androidx.compose.ui.geometry.Offset(size.width * .74f, size.height * .08f),
                    androidx.compose.ui.geometry.Offset(size.width * .74f, size.height * .92f),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
            else -> {
                drawRoundRect(
                    color = StogInk,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .22f),
                    size = androidx.compose.ui.geometry.Size(size.width * .68f, size.height * .56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                )
                drawArc(
                    color = StogInk,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * .62f, size.height * .3f),
                    size = androidx.compose.ui.geometry.Size(size.width * .3f, size.height * .38f),
                    style = stroke,
                )
            }
        }
    }
}

@Composable
internal fun RecentSearchRow(query: String) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(StogGoogleSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = 2.dp.toPx()
                    drawCircle(
                        color = StogMuted,
                        radius = size.minDimension * .3f,
                        style = Stroke(width = stroke),
                    )
                    drawLine(
                        color = StogMuted,
                        start = androidx.compose.ui.geometry.Offset(size.width * .7f, size.height * .7f),
                        end = androidx.compose.ui.geometry.Offset(size.width * .9f, size.height * .9f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            Text(
                text = query,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = StogInk,
            )
            Text("오늘", style = MaterialTheme.typography.bodyMedium, color = StogMuted)
        }
        HorizontalDivider(color = StogBorder.copy(alpha = 0.55f))
    }
}
