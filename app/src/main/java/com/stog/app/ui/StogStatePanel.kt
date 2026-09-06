package com.stog.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow

enum class StogSurfaceState(
    val machineName: String,
    val announcesChanges: Boolean,
) {
    LOADING("loading", true),
    EMPTY("empty", false),
    ERROR("error", true),
    OFFLINE("offline", true),
    AUTH_EXPIRED("auth_expired", true),
    PERMISSION_DENIED("permission_denied", true),
    CONTENT("content", false),
    ;

    val isRecoverable: Boolean
        get() = defaultActionFor(this) != StogSurfaceAction.NONE
}

enum class StogSurfaceAction {
    NONE,
    RETRY,
    LOGIN,
    REQUEST_PERMISSION,
}

fun defaultActionFor(state: StogSurfaceState): StogSurfaceAction = when (state) {
    StogSurfaceState.ERROR,
    StogSurfaceState.OFFLINE,
    -> StogSurfaceAction.RETRY
    StogSurfaceState.AUTH_EXPIRED -> StogSurfaceAction.LOGIN
    StogSurfaceState.PERMISSION_DENIED -> StogSurfaceAction.REQUEST_PERMISSION
    StogSurfaceState.LOADING,
    StogSurfaceState.EMPTY,
    StogSurfaceState.CONTENT,
    -> StogSurfaceAction.NONE
}

fun stogStatePanelTag(state: StogSurfaceState): String = "stog_state_${state.machineName}"

@Composable
fun StogStatePanel(
    state: StogSurfaceState,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: StogSurfaceAction = defaultActionFor(state),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val exposesAction = action != StogSurfaceAction.NONE && actionLabel != null && onAction != null
    val stateModifier = modifier
        .fillMaxWidth()
        .testTag(stogStatePanelTag(state))
        .semantics {
            stateDescription = state.machineName
            if (state.announcesChanges) liveRegion = LiveRegionMode.Polite
        }
    if (state == StogSurfaceState.LOADING) {
        Column(
            modifier = stateModifier.padding(
                horizontal = StogUiContract.BaseSpacingDp.dp * 2,
                vertical = StogUiContract.BaseSpacingDp.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            Text(title, color = StogInk, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = StogMuted, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Card(
            modifier = stateModifier,
            colors = CardDefaults.cardColors(containerColor = StogSurface),
            border = BorderStroke(1.dp, StogBorder),
            shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
        ) {
            Column(
                modifier = Modifier.padding(StogUiContract.BaseSpacingDp.dp * 2),
                verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
            ) {
                Text(title, color = StogInk, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = StogMuted, style = MaterialTheme.typography.bodyMedium)
                if (exposesAction) {
                    Button(
                        onClick = checkNotNull(onAction),
                        modifier = Modifier
                            .testTag("${stogStatePanelTag(state)}_action")
                            .fillMaxWidth()
                            .heightIn(min = StogUiContract.MinTouchTargetDp.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StogYellow,
                            contentColor = StogInk,
                        ),
                        shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
                    ) {
                        Text(checkNotNull(actionLabel))
                    }
                }
            }
        }
    }
}
