package com.stog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stog.app.feature.space.PlaceSearchField
import com.stog.app.feature.space.MapSheetAnchors
import com.stog.app.feature.space.MapSheetMeasurements
import com.stog.app.feature.space.SheetLevel
import com.stog.app.feature.space.toAnchors
import com.stog.app.feature.space.visibleSheetHeightPx
import com.stog.app.ui.theme.StogDarkCanvas
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogWhite
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ExpandedSheetMargin = StogUiContract.MapOverlayGapDp.dp
private val BottomSheetShape = RoundedCornerShape(
    topStart = StogUiContract.LargeRadiusDp.dp,
    topEnd = StogUiContract.LargeRadiusDp.dp,
)
internal const val BOTTOM_SHEET_HANDLE_TOUCH_HEIGHT_DP = StogUiContract.MinTouchTargetDp
internal const val STOG_MAP_SHEET_HANDLE_TEST_TAG = "stog_map_sheet_handle"
@Stable
internal class StogMapBottomSheetState internal constructor(
    private val draggableState: AnchoredDraggableState<SheetLevel>,
) {
    private var lastAnchors: MapSheetAnchors? = null
    private var accessibilityRequestInFlight by mutableStateOf(false)
    private var activeAnimationCount by mutableIntStateOf(0)
    private var handleDragging by mutableStateOf(false)
    internal val handleInteractionSource = MutableInteractionSource()

    val settledLevel: SheetLevel
        get() = draggableState.settledValue

    val offsetPx: Float
        get() = draggableState.offset

    val isInTransition: Boolean
        get() = activeAnimationCount > 0 ||
            draggableState.currentValue != draggableState.settledValue ||
            draggableState.targetValue != draggableState.settledValue

    val isHandleDragging: Boolean
        get() = handleDragging

    val canStartAccessibilityAdjustment: Boolean
        get() = lastAnchors != null &&
            !isHandleDragging &&
            !isInTransition &&
            !accessibilityRequestInFlight

    internal fun updateHandleDragging(dragging: Boolean) {
        handleDragging = dragging
    }

    internal fun beginAccessibilityAdjustment(target: SheetLevel): Boolean {
        if (
            !canStartAccessibilityAdjustment ||
            target !in settledLevel.accessibilityAdjustmentTargets()
        ) {
            return false
        }
        accessibilityRequestInFlight = true
        return true
    }

    internal suspend fun completeAccessibilityAdjustment(target: SheetLevel) {
        try {
            animateTo(target)
        } finally {
            accessibilityRequestInFlight = false
        }
    }

    internal fun beginAnimation() {
        activeAnimationCount += 1
    }

    internal fun endAnimation() {
        activeAnimationCount = (activeAnimationCount - 1).coerceAtLeast(0)
    }

    suspend fun animateTo(level: SheetLevel) {
        beginAnimation()
        try {
            draggableState.animateTo(level)
        } finally {
            endAnimation()
        }
    }

    internal fun updateAnchors(anchors: MapSheetAnchors) {
        if (anchors == lastAnchors) return

        draggableState.updateAnchors(
            DraggableAnchors {
                SheetLevel.Expanded at anchors.expandedTopPx
                SheetLevel.HalfExpanded at anchors.halfExpandedTopPx
                SheetLevel.Collapsed at anchors.collapsedTopPx
            },
            draggableState.targetValue,
        )
        lastAnchors = anchors
    }

    internal fun draggableModifier(): Modifier =
        Modifier.anchoredDraggable(
            state = draggableState,
            orientation = Orientation.Vertical,
            interactionSource = handleInteractionSource,
        )
}

@Composable
internal fun rememberStogMapBottomSheetState(
    initialLevel: SheetLevel = SheetLevel.Collapsed,
): StogMapBottomSheetState =
    remember(initialLevel) {
        StogMapBottomSheetState(
            AnchoredDraggableState(initialValue = initialLevel),
        )
    }

@Composable
internal fun StogMapBottomSheet(
    state: StogMapBottomSheetState,
    onSearch: () -> Unit,
    mapContent: @Composable BoxScope.(topPaddingPx: Int) -> Unit,
    sheetContent: @Composable () -> Unit,
    showTopAppBar: Boolean = true,
    showBottomSheet: Boolean = true,
    searchResultHeader: Boolean = false,
    onSearchResultBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    useSharedHeader: Boolean = false,
) {
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var viewportTopInRootPx by remember { mutableFloatStateOf(Float.NaN) }
    var topAppBarBottomInRootPx by remember { mutableFloatStateOf(Float.NaN) }
    val topChromeBottomInRootPx = if (showTopAppBar) {
        topAppBarBottomInRootPx
    } else {
        viewportTopInRootPx
    }

    fun updateAnchors() {
        if (
            viewportHeightPx <= 0 ||
            !viewportTopInRootPx.isFinite() ||
            !topChromeBottomInRootPx.isFinite()
        ) {
            return
        }

        val topChromeBottomPx = topChromeBottomInRootPx - viewportTopInRootPx
        val marginPx = with(density) { ExpandedSheetMargin.toPx() }
        if (topChromeBottomPx + marginPx >= viewportHeightPx) return

        state.updateAnchors(
            MapSheetMeasurements(
                topChromeBottomPx = topChromeBottomPx,
                contentBottomPx = viewportHeightPx.toFloat(),
                expandedMarginPx = marginPx,
                collapsedHeightPx = with(density) {
                    StogUiContract.SheetCollapsedHeightDp.dp.toPx()
                },
            ).toAnchors(),
        )
    }

    val mapTopPaddingPx = if (
        viewportTopInRootPx.isFinite() &&
            topChromeBottomInRootPx.isFinite()
    ) {
        (
            topChromeBottomInRootPx -
                viewportTopInRootPx +
                with(density) { ExpandedSheetMargin.toPx() }
            ).roundToInt()
    } else {
        0
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                viewportHeightPx = coordinates.size.height
                viewportTopInRootPx = coordinates.positionInRoot().y
                updateAnchors()
            },
    ) {
        LaunchedEffect(
            showTopAppBar,
            viewportHeightPx,
            viewportTopInRootPx,
            topAppBarBottomInRootPx,
        ) {
            updateAnchors()
        }
        mapContent(mapTopPaddingPx)
        if (showBottomSheet) {
            AnchoredSheetSurface(
                state = state,
                sheetContent = sheetContent,
            )
        }
        if (showTopAppBar) {
            val onTopAppBarBottomChanged: (Float) -> Unit = { bottomInRootPx ->
                topAppBarBottomInRootPx = bottomInRootPx
                updateAnchors()
            }
            if (searchResultHeader) {
                MapSearchResultTopBar(
                    onBack = onSearchResultBack,
                    onTopAppBarBottomChanged = onTopAppBarBottomChanged,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else if (useSharedHeader) {
                StogAppHeader(
                    onSearch = onSearch,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(StogCanvas)
                        .statusBarsPadding()
                        .onGloballyPositioned { coordinates ->
                            onTopAppBarBottomChanged(coordinates.boundsInRoot().bottom)
                        },
                )
            } else {
                MapTopActionBar(
                    onSearch = onSearch,
                    onNotification = {},
                    onTopAppBarBottomChanged = onTopAppBarBottomChanged,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(StogDarkCanvas)
                        .statusBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun MapSearchResultTopBar(
    onBack: () -> Unit,
    onTopAppBarBottomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(StogSurface)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .onGloballyPositioned { coordinates ->
                onTopAppBarBottomChanged(coordinates.boundsInRoot().bottom)
            },
    ) {
        PlaceSearchField(
            query = "",
            searching = false,
            focusRequester = focusRequester,
            onBack = onBack,
            onQueryChange = {},
            onSearch = onBack,
            readOnly = true,
            onFieldClick = onBack,
        )
    }
}

@Composable
private fun AnchoredSheetSurface(
    state: StogMapBottomSheetState,
    sheetContent: @Composable () -> Unit,
) {
    val offsetPx = state.offsetPx
    if (!offsetPx.isFinite()) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.handleInteractionSource) {
        val activeDrags = mutableSetOf<DragInteraction.Start>()
        state.handleInteractionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> activeDrags += interaction
                is DragInteraction.Stop -> activeDrags -= interaction.start
                is DragInteraction.Cancel -> activeDrags -= interaction.start
            }
            state.updateHandleDragging(activeDrags.isNotEmpty())
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visibleHeight = with(density) {
            visibleSheetHeightPx(constraints.maxHeight, offsetPx).toDp()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(visibleHeight)
                .offset { IntOffset(0, state.offsetPx.roundToInt()) }
                .shadow(
                    elevation = MapSurfaceShadowElevation,
                    shape = BottomSheetShape,
                )
                .clip(BottomSheetShape)
                .background(
                    color = StogSurface,
                    shape = BottomSheetShape,
                ),
        ) {
            MapSurfaceHeaderLine()
            BottomSheetHandleArea(
                level = state.settledLevel,
                adjustmentEnabled = state.canStartAccessibilityAdjustment &&
                    !state.isHandleDragging,
                onLevelRequested = { target ->
                    if (!state.beginAccessibilityAdjustment(target)) {
                        false
                    } else {
                        scope.launch { state.completeAccessibilityAdjustment(target) }
                        true
                    }
                },
                modifier = state.draggableModifier(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                sheetContent()
            }
        }
    }
}

@Composable
private fun MapTopActionBar(
    onSearch: () -> Unit,
    onNotification: () -> Unit,
    onTopAppBarBottomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = StogUiContract.MapOverlayGapDp.dp)
            .onGloballyPositioned { coordinates ->
                onTopAppBarBottomChanged(coordinates.boundsInRoot().bottom)
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StogTopAppBarLogo(
            modifier = Modifier
                .width(144.dp)
                .height(48.dp)
                .offset(x = (-12).dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(StogUiContract.MapOverlayGapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapTopActionButton(
                action = MapTopAction.SEARCH,
                onClick = onSearch,
                contentDescription = "장소 검색",
            )
            MapTopActionButton(
                action = MapTopAction.NOTIFICATION,
                onClick = onNotification,
                contentDescription = "알림",
            )
        }
    }
}

@Composable
private fun MapTopActionButton(
    action: MapTopAction,
    onClick: () -> Unit,
    contentDescription: String,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(StogUiContract.MinTouchTargetDp.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        MapTopActionGlyph(action)
    }
}

private enum class MapTopAction {
    SEARCH,
    NOTIFICATION,
}

@Composable
private fun MapTopActionGlyph(action: MapTopAction) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        when (action) {
            MapTopAction.SEARCH -> {
                drawCircle(StogWhite, radius = size.minDimension * .28f, style = stroke)
                drawLine(
                    StogWhite,
                    androidx.compose.ui.geometry.Offset(size.width * .69f, size.height * .69f),
                    androidx.compose.ui.geometry.Offset(size.width * .9f, size.height * .9f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            MapTopAction.NOTIFICATION -> {
                val bell = Path().apply {
                    moveTo(size.width * .2f, size.height * .72f)
                    cubicTo(size.width * .3f, size.height * .62f, size.width * .28f, size.height * .18f, size.width * .5f, size.height * .18f)
                    cubicTo(size.width * .72f, size.height * .18f, size.width * .7f, size.height * .62f, size.width * .8f, size.height * .72f)
                }
                drawPath(bell, StogWhite, style = stroke)
                drawLine(
                    StogWhite,
                    androidx.compose.ui.geometry.Offset(size.width * .16f, size.height * .72f),
                    androidx.compose.ui.geometry.Offset(size.width * .84f, size.height * .72f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    StogWhite,
                    radius = size.minDimension * .06f,
                    center = androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .86f),
                )
            }

        }
    }
}

@Composable
private fun BottomSheetHandleArea(
    level: SheetLevel,
    adjustmentEnabled: Boolean,
    onLevelRequested: (SheetLevel) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BOTTOM_SHEET_HANDLE_TOUCH_HEIGHT_DP.dp)
            .testTag(STOG_MAP_SHEET_HANDLE_TEST_TAG)
            .semantics {
                applyStogSheetHandleSemantics(
                    level = level,
                    adjustmentEnabled = adjustmentEnabled,
                    onLevelRequested = onLevelRequested,
                )
            }
            .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(36.dp)
                .height(4.dp)
                .background(StogInk, androidx.compose.foundation.shape.CircleShape),
        )
    }
}
