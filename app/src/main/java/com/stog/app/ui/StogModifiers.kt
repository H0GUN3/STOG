package com.stog.app.ui

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

fun Modifier.stogTouchTarget(): Modifier = sizeIn(
    minWidth = StogUiContract.MinTouchTargetDp.dp,
    minHeight = StogUiContract.MinTouchTargetDp.dp,
)
