package com.stog.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogYellow

enum class StogMediaPlaceholderKind(val label: String) {
    TOURISM("STOG 여행 사진"),
    FOOD("STOG 음식 사진"),
    FESTIVAL("STOG 행사 사진"),
}

fun stogMediaPlaceholderTag(kind: StogMediaPlaceholderKind): String =
    "stog_media_placeholder_${kind.name.lowercase()}"

@Composable
fun StogMediaPlaceholder(
    kind: StogMediaPlaceholderKind,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(StogBorder)
            .testTag(stogMediaPlaceholderTag(kind))
            .semantics { stateDescription = "placeholder_${kind.name.lowercase()}" },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.stog_travel_record),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.18f,
            colorFilter = ColorFilter.tint(StogYellow),
        )
        Column(
            modifier = Modifier.padding(StogUiContract.BaseSpacingDp.dp * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            Text(kind.label, color = StogInk, style = MaterialTheme.typography.titleSmall)
            Text("사진을 표시할 수 없어요", color = StogMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
