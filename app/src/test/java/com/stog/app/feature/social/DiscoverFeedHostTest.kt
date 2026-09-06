package com.stog.app.feature.social

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.stog.app.ui.theme.STOGTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiscoverFeedHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fixtureFeedSupportsBookmarkAndCommentActions() {
        var posts by mutableStateOf(fixtureDiscoverFeedPosts())

        composeRule.setContent {
            STOGTheme {
                SocialFeedScreen(
                    baseUrl = "https://unused.test",
                    accessToken = null,
                    onLoginRequired = {},
                    autoRefresh = false,
                    fixturePosts = posts,
                    onFixturePostChanged = { updated ->
                        posts = posts.map { post ->
                            if (post.item.photoId == updated.item.photoId) updated else post
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("seo._travel").fetchSemanticsNode()
        composeRule.onNodeWithText("셀 기록").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("피드 저장").performClick()
        composeRule.onNodeWithContentDescription("저장 취소").fetchSemanticsNode()
    }
}
