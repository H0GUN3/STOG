package com.stog.app.feature.profile

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.stog.app.ui.theme.STOGTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UserProfileHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun profileUsesSimplifiedCardWithoutLegacyEditForm() {
        composeRule.setContent {
            STOGTheme {
                UserProfileScreen(
                    nickname = "윤건호",
                    authenticated = true,
                    onLogin = {},
                    onSearch = {},
                    onMenuSelected = {},
                    onOpenStobee = {},
                )
            }
        }

        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("장소 검색", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("알림", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        listOf(
            "저장한 피드",
            "저장한 발견 사진",
            "좋아요 한 발견 사진",
            "여행 성향 분석",
            "알림 설정",
            "고객 센터",
            "버전 정보",
        ).forEach { removedLabel ->
            assertEquals(
                0,
                composeRule.onAllNodesWithText(removedLabel, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
        }
        composeRule.onNodeWithText("로그아웃").fetchSemanticsNode()
        composeRule.onNodeWithTag("profile_scroll")
            .performScrollToNode(hasText("내 사진"))
        composeRule.onNodeWithText("내 사진").fetchSemanticsNode()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("정보 수정", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("사용자 정보 수정", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }
}
