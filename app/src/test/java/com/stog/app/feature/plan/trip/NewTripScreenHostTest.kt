package com.stog.app.feature.plan.trip

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NewTripScreenHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blankTitleShowsErrorBelowNameField() {
        setContent()

        composeRule.onNodeWithTag("new_trip_scroll")
            .performScrollToNode(hasText("여행 만들기"))
        composeRule.onNodeWithTag("new_trip_create").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_trip_scroll")
            .performScrollToNode(hasText("여행 이름"))

        composeRule.onNodeWithText("여행 이름을 입력해주세요").fetchSemanticsNode()
    }

    @Test
    fun dateCardOpensModalRangePickerWithoutLeavingNewTripScreen() {
        setContent()

        composeRule.onNodeWithTag("new_trip_scroll")
            .performScrollToNode(hasContentDescription("여행 날짜 선택"))
        composeRule.onNodeWithContentDescription("여행 날짜 선택").performClick()

        composeRule.onNodeWithText("여행 날짜 선택").fetchSemanticsNode()
        composeRule.onNodeWithText("선택 완료").fetchSemanticsNode()
    }

    @Test
    fun existingTripIdClassifiesFailureAsCoverUpload() {
        assertEquals(
            TripCreationFailure.COVER_UPLOAD,
            tripCreationFailureFor(7L),
        )
    }

    @Test
    fun exposesEveryJeonbukMunicipalityAndProvinceOption() {
        assertEquals(15, JEONBUK_REGIONS.size)
        assertEquals("전북 전체", JEONBUK_REGIONS.first().label)
        assertTrue(JEONBUK_REGIONS.any { it.code == "BUAN" && it.label == "부안군" })
        assertTrue(JEONBUK_REGIONS.any { it.code == "JEONJU" && it.label == "전주시" })
    }

    private fun setContent() {
        composeRule.setContent {
            STOGTheme {
                NewTripScreen(
                    baseUrl = "http://10.0.2.2:8080",
                    accessToken = "test-token",
                    onBack = {},
                    onLoginRequired = {},
                    onCreated = {},
                )
            }
        }
    }
}
