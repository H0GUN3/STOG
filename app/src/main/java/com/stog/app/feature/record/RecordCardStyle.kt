package com.stog.app.feature.record

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface

internal val RecordCardShape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp)
internal val RecordCardBorder = BorderStroke(1.dp, StogBorder.copy(alpha = 0.72f))
internal val RecordCardContainerColor = StogSurface.copy(alpha = 0.92f)
internal val RecordCardElevation = 2.dp

@Composable
internal fun recordCardColors() = CardDefaults.cardColors(
    containerColor = RecordCardContainerColor,
    contentColor = StogInk,
    disabledContainerColor = RecordCardContainerColor,
    disabledContentColor = StogMuted,
)
