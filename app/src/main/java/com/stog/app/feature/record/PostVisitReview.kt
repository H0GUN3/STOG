package com.stog.app.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.stog.app.core.database.VisitOutboxEntity
import com.stog.app.ui.stogTouchTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostVisitReview(
    visit: VisitOutboxEntity?,
    onContinue: (Long) -> Unit,
    onCapture: (Long) -> Unit,
) {
    if (visit == null) return
    ModalBottomSheet(
        onDismissRequest = { onContinue(visit.id) },
        modifier = Modifier.testTag("post_visit_review"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("방문은 어떠셨나요?")
            Button(
                onClick = { onCapture(visit.id) },
                modifier = Modifier.fillMaxWidth().stogTouchTarget().testTag("post_visit_capture"),
            ) {
                Text("사진으로 남기기")
            }
            OutlinedButton(
                onClick = { onContinue(visit.id) },
                modifier = Modifier.fillMaxWidth().stogTouchTarget().testTag("post_visit_continue"),
            ) {
                Text("계속 여행하기")
            }
        }
    }
}
