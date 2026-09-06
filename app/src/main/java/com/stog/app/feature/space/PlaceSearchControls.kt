package com.stog.app.feature.space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow

@Composable
internal fun PlaceSearchField(
    query: String,
    searching: Boolean,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    readOnly: Boolean = false,
    onFieldClick: (() -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .shadow(6.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = StogSurface,
        border = BorderStroke(1.dp, StogBorder),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(52.dp)
                    .semantics { contentDescription = "지도 화면으로 돌아가기" },
            ) {
                BackGlyph()
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .stogTouchTarget()
                    .focusRequester(focusRequester)
                    .then(
                        if (readOnly && onFieldClick != null) {
                            Modifier.clickable(onClick = onFieldClick)
                        } else {
                            Modifier
                        },
                    ),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = StogInk),
                cursorBrush = SolidColor(StogYellow),
                singleLine = true,
                readOnly = readOnly,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboardController?.hide()
                        onSearch()
                    },
                ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = "장소·주소 검색",
                                style = MaterialTheme.typography.titleMedium,
                                color = StogMuted,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotBlank()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .size(52.dp)
                        .semantics { contentDescription = "검색어 지우기" },
                ) {
                    ClearGlyph()
                }
            } else if (searching) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = StogInk,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(
            color = StogInk,
            start = androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .28f),
            end = androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = StogInk,
            start = androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .28f),
            end = androidx.compose.ui.geometry.Offset(size.width * .28f, size.height * .72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun BackGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(
            color = StogInk,
            start = androidx.compose.ui.geometry.Offset(size.width * .7f, size.height * .18f),
            end = androidx.compose.ui.geometry.Offset(size.width * .3f, size.height * .5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = StogInk,
            start = androidx.compose.ui.geometry.Offset(size.width * .3f, size.height * .5f),
            end = androidx.compose.ui.geometry.Offset(size.width * .7f, size.height * .82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

