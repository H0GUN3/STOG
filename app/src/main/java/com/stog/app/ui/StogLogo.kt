package com.stog.app.ui

import com.stog.app.R
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

@Composable
fun StogLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.stog_logo_line),
        contentDescription = "STOG",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun StogTopAppBarLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.stog_logo_name),
        contentDescription = "STOG",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
