package com.stog.app.feature.space

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.ui.MapSurfaceHeaderLine
import com.stog.app.ui.MapSurfaceShadowElevation
import com.stog.app.ui.SemanticElement
import com.stog.app.ui.STOG_SEMANTICS
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.theme.StogDarkCanvas
import com.stog.app.ui.theme.StogWhite
import com.stog.app.ui.theme.StogYellow

internal val MapShellNavigationContentHeight = StogUiContract.MapNavigationHeightDp.dp

@Composable
internal fun MapShellNavigationBar(
    selectedMenu: MapMenu,
    aiGuideSelected: Boolean,
    onMenuSelected: (MapMenu) -> Unit,
    onAiGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showMenuSelection = !aiGuideSelected
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = MapSurfaceShadowElevation,
            )
            .background(StogDarkCanvas)
            .navigationBarsPadding()
            .height(MapShellNavigationContentHeight),
    ) {
        MapSurfaceHeaderLine(
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MapShellNavigationContentHeight)
                .align(Alignment.TopCenter)
                .padding(horizontal = StogUiContract.MapOverlayGapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationItem(
                contentDescription = STOG_SEMANTICS.getValue(
                    SemanticElement.HomeNavigation,
                ).accessibleName,
                selected = showMenuSelection && selectedMenu == MapMenu.HOME,
                onClick = { onMenuSelected(MapMenu.HOME) },
            ) {
                NavigationGlyph(
                    R.drawable.ic_navigation_home,
                    showMenuSelection && selectedMenu == MapMenu.HOME,
                )
            }
            NavigationItem(
                contentDescription = STOG_SEMANTICS.getValue(
                    SemanticElement.TripsNavigation,
                ).accessibleName,
                selected = showMenuSelection && selectedMenu == MapMenu.TRAVEL,
                onClick = { onMenuSelected(MapMenu.TRAVEL) },
            ) {
                NavigationGlyph(
                    R.drawable.ic_navigation_trip,
                    showMenuSelection && selectedMenu == MapMenu.TRAVEL,
                )
            }
            NavigationItem(
                contentDescription = STOG_SEMANTICS.getValue(
                    SemanticElement.StobeeNavigation,
                ).accessibleName,
                selected = aiGuideSelected,
                onClick = onAiGuide,
            ) {
                Image(
                    painter = painterResource(R.drawable.stog_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    colorFilter = ColorFilter.tint(
                        if (aiGuideSelected) StogYellow else StogWhite,
                    ),
                )
            }
            NavigationItem(
                contentDescription = STOG_SEMANTICS.getValue(
                    SemanticElement.DiscoverNavigation,
                ).accessibleName,
                selected = showMenuSelection && selectedMenu == MapMenu.SOCIAL,
                onClick = { onMenuSelected(MapMenu.SOCIAL) },
            ) {
                NavigationGlyph(
                    R.drawable.ic_navigation_discover,
                    showMenuSelection && selectedMenu == MapMenu.SOCIAL,
                )
            }
            NavigationItem(
                contentDescription = STOG_SEMANTICS.getValue(
                    SemanticElement.UserNavigation,
                ).accessibleName,
                selected = showMenuSelection && selectedMenu == MapMenu.RECOMMENDATIONS,
                onClick = { onMenuSelected(MapMenu.RECOMMENDATIONS) },
            ) {
                NavigationGlyph(
                    R.drawable.ic_navigation_profile,
                    showMenuSelection && selectedMenu == MapMenu.RECOMMENDATIONS,
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavigationItem(
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = contentDescription,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .height(64.dp)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
                role = Role.Tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        if (label != null) {
            Text(
                text = label,
                color = if (selected) StogYellow else StogWhite,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NavigationGlyph(iconResId: Int, selected: Boolean) {
    Icon(
        painter = painterResource(iconResId),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = if (selected) StogYellow else StogWhite,
    )
}
