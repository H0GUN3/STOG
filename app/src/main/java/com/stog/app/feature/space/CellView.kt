package com.stog.app.feature.space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogYellow

@Composable
internal fun CellView(
    background: CellBackground,
    badges: List<CellBadge>,
    modifier: Modifier = Modifier,
) {
    val shownBadges = badges.take(2)
    val backgroundColor = when (background) {
        CellBackground.EMPTY -> StogCanvas
        CellBackground.VISITED -> StogYellow.copy(alpha = 0.28f)
        CellBackground.HOT -> StogYellow
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "셀 바탕 ${cellBackgroundLabel(background)}${
                    shownBadges.takeIf { it.isNotEmpty() }?.joinToString(", ") { cellBadgeLabel(it) }
                        ?.let { ", $it" }.orEmpty()
                }"
            },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, StogBorder.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = cellBackgroundLabel(background),
                    style = MaterialTheme.typography.titleMedium,
                    color = StogInk,
                )
                if (shownBadges.isNotEmpty()) {
                    Text(
                        text = shownBadges.joinToString(" · ") { cellBadgeLabel(it) }, 
                        style = MaterialTheme.typography.bodyMedium,
                        color = StogMuted,
                    )
                }
            }
        }
    }
}

internal fun cellBackgroundLabel(background: CellBackground): String = when (background) {
    CellBackground.EMPTY -> "탐색 가능한 지역"
    CellBackground.VISITED -> "방문한 지역"
    CellBackground.HOT -> "인기 지역"
}

internal fun cellBadgeLabel(badge: CellBadge): String = when (badge) {
    CellBadge.HONEY -> "꿀"
    CellBadge.HIDDEN -> "숨은 장소"
    CellBadge.LANDMARK -> "명소"
}
