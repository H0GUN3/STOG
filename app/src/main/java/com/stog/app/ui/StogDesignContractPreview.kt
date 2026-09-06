package com.stog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.stog.app.feature.auth.LoginScreen
import com.stog.app.feature.plan.share_import.ShareImportScreen
import com.stog.app.feature.plan.share_import.ShareImportUiState
import com.stog.app.feature.plan.trip.TripExperienceScreen
import com.stog.app.feature.record.PhotoArchiveScreen
import com.stog.app.feature.record.PhotoCaptureScreen
import com.stog.app.feature.social.SocialFeedScreen
import com.stog.app.feature.social.SocialFeedUiState
import com.stog.app.feature.space.PlaceSearchScreen
import com.stog.app.ui.theme.STOGTheme
import com.stog.app.ui.theme.StogBorder

@Preview(name = "FE production - login loading", widthDp = 360, heightDp = 720)
@Composable
private fun LoginProductionPreview() {
    STOGTheme {
        LoginScreen(
            message = null,
            isLoading = true,
            onKakaoLogin = {},
            onGoogleLogin = {},
            onNaverLogin = {},
            onGuest = {},
        )
    }
}

@Preview(name = "FE production - map sheet empty", widthDp = 360, heightDp = 720)
@Composable
private fun MapSheetProductionPreview() {
    STOGTheme {
        StogMapBottomSheet(
            state = rememberStogMapBottomSheetState(),
            onSearch = {},
            mapContent = {
                Box(Modifier.matchParentSize().background(StogBorder))
            },
            showBottomSheet = false,
            sheetContent = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "FE production - search empty", widthDp = 360, heightDp = 720)
@Composable
private fun SearchProductionPreview() {
    STOGTheme {
        PlaceSearchScreen(
            baseUrl = "https://unused.invalid",
            accessToken = null,
            onBack = {},
            onLoginRequired = { _ -> },
        )
    }
}

@Preview(name = "FE production - share empty", widthDp = 360, heightDp = 720)
@Composable
private fun ShareProductionPreview() {
    STOGTheme {
        ShareImportScreen(
            state = ShareImportUiState.Idle,
            onReviewSaved = { _, _, _ -> },
        )
    }
}

@Preview(name = "FE production - my trips auth", widthDp = 360, heightDp = 720)
@Composable
private fun TripsProductionPreview() {
    STOGTheme {
        TripExperienceScreen(
            baseUrl = "https://unused.invalid",
            accessToken = null,
            confirmedShareImports = emptyList(),
            onLoginRequired = {},
            onCapture = {},
            onArchive = {},
            onSearch = {},
            onMenuSelected = {},
            onOpenStobee = {},
        )
    }
}

@Preview(name = "FE production - discover empty", widthDp = 360, heightDp = 720)
@Composable
private fun DiscoverProductionPreview() {
    STOGTheme {
        SocialFeedScreen(
            baseUrl = "https://unused.invalid",
            accessToken = null,
            onLoginRequired = {},
            initialState = SocialFeedUiState.Empty,
            autoRefresh = false,
        )
    }
}

@Preview(name = "FE production - capture empty", widthDp = 360, heightDp = 720)
@Composable
private fun CaptureProductionPreview() {
    STOGTheme {
        PhotoCaptureScreen(
            baseUrl = "https://unused.invalid",
            accessToken = null,
            userId = null,
            onBack = {},
            onAuthenticationRequired = {},
        )
    }
}

@Preview(name = "FE production - archive auth", widthDp = 360, heightDp = 720)
@Composable
private fun ArchiveProductionPreview() {
    STOGTheme {
        PhotoArchiveScreen(
            baseUrl = "https://unused.invalid",
            accessToken = null,
            viewerId = null,
            onBack = {},
            onAuthenticationRequired = {},
        )
    }
}
