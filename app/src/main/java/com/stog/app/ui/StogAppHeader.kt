package com.stog.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.stog.app.R

@Composable
internal fun StogAppHeader(
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 12.dp, end = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StogTopAppBarLogo(
            Modifier
                .width(144.dp)
                .height(48.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch, modifier = Modifier.stogTouchTarget()) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = "장소 검색",
            )
        }
        IconButton(onClick = {}, modifier = Modifier.stogTouchTarget()) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = "알림",
            )
        }
    }
}
