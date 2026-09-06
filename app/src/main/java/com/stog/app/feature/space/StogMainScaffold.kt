package com.stog.app.feature.space

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stog.app.ui.StogAppHeader
import com.stog.app.ui.theme.StogCanvas

@Composable
internal fun StogMainScaffold(
    selectedMenu: MapMenu,
    onSearch: () -> Unit,
    onMenuSelected: (MapMenu) -> Unit,
    onOpenStobee: () -> Unit,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = StogCanvas,
        contentWindowInsets = if (showHeader) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        bottomBar = {
            MapShellNavigationBar(
                selectedMenu = selectedMenu,
                aiGuideSelected = false,
                onMenuSelected = onMenuSelected,
                onAiGuide = onOpenStobee,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (showHeader) {
                StogAppHeader(onSearch = onSearch)
            }
            content()
        }
    }
}
