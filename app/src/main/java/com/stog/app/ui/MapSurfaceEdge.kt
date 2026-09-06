package com.stog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stog.app.ui.theme.StogBorder

internal val MapSurfaceShadowElevation = StogUiContract.MapSurfaceElevationDp.dp
internal val MapControlShadowElevation = StogUiContract.MapControlElevationDp.dp

@Composable
internal fun MapSurfaceHeaderLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(StogUiContract.MapSurfaceBorderDp.dp)
            .background(StogBorder),
    )
}
