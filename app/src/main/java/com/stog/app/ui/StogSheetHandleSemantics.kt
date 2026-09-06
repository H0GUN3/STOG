package com.stog.app.ui

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import com.stog.app.feature.space.SheetLevel
import kotlin.math.roundToInt

internal fun SheetLevel.accessibilityAdjustmentTargets(): List<SheetLevel> = when (this) {
    SheetLevel.Collapsed -> listOf(SheetLevel.HalfExpanded)
    SheetLevel.HalfExpanded -> listOf(SheetLevel.Expanded, SheetLevel.Collapsed)
    SheetLevel.Expanded -> listOf(SheetLevel.HalfExpanded)
}

private fun SheetLevel.accessibilityStateDescription(): String = when (this) {
    SheetLevel.Collapsed -> "접힘"
    SheetLevel.HalfExpanded -> "중간 펼침"
    SheetLevel.Expanded -> "전체 펼침"
}

internal fun SemanticsPropertyReceiver.applyStogSheetHandleSemantics(
    level: SheetLevel,
    adjustmentEnabled: Boolean,
    onLevelRequested: (SheetLevel) -> Boolean,
) {
    contentDescription = STOG_SEMANTICS.getValue(
        SemanticElement.SheetHandle,
    ).accessibleName
    stateDescription = level.accessibilityStateDescription()
    progressBarRangeInfo = ProgressBarRangeInfo(
        current = level.ordinal.toFloat(),
        range = 0f..SheetLevel.entries.lastIndex.toFloat(),
        steps = SheetLevel.entries.size - 2,
    )
    if (!adjustmentEnabled) {
        customActions = emptyList()
        return
    }

    customActions = level.accessibilityAdjustmentTargets().map { target ->
        CustomAccessibilityAction(
            label = if (target.ordinal > level.ordinal) "시트 펼치기" else "시트 접기",
            action = { onLevelRequested(target) },
        )
    }
    setProgress { requestedValue ->
        if (!requestedValue.isFinite()) return@setProgress false
        val target = SheetLevel.entries[
            requestedValue
                .roundToInt()
                .coerceIn(0, SheetLevel.entries.lastIndex),
        ]
        target == level || onLevelRequested(target)
    }
}
