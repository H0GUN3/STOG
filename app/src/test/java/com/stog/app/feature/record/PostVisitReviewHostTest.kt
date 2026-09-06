package com.stog.app.feature.record

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.stog.app.core.database.VisitOutboxEntity
import com.stog.app.core.database.VisitStatus
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PostVisitReviewHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun reviewReceiptShowsMinimalContinuationActions() {
        var continued: Long? = null
        var captured: Long? = null
        composeRule.setContent {
            STOGTheme {
                PostVisitReview(visit(), { continued = it }, { captured = it })
            }
        }

        composeRule.onNodeWithTag("post_visit_review").assertExists()
        composeRule.onNodeWithTag("post_visit_capture").assertHasClickAction().performClick()
        composeRule.onNodeWithTag("post_visit_continue").assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(4L, captured)
            assertEquals(4L, continued)
        }
    }

    @Test
    fun absentReceiptShowsNoReview() {
        composeRule.setContent { STOGTheme { PostVisitReview(null, {}, {}) } }
        composeRule.onNodeWithTag("post_visit_review").assertDoesNotExist()
    }

    private fun visit() = VisitOutboxEntity(
        id = 4,
        observationId = "observation",
        accountId = "account",
        tripId = "trip",
        userId = "user",
        clientVisitId = "client",
        payloadFingerprint = "fingerprint",
        cellId = 10,
        lat = 35.8,
        lng = 127.1,
        enteredAt = 1,
        leftAt = 2,
        status = VisitStatus.VISITED,
        isInterpolated = false,
        reviewRequired = true,
        createdAt = 3,
    )
}
