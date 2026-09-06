package com.stog.app.feature.home

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.stog.app.feature.space.ItineraryItem
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow
import com.stog.app.ui.stogTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTripItinerarySheet(
    baseUrl: String,
    accessToken: String?,
    trip: TripSummary,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit = {},
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
) {
    val client = remember(baseUrl) { PlanningApiClient(baseUrl) }
    var itinerary by remember(trip.id) { mutableStateOf<List<ItineraryItem>>(emptyList()) }
    var loading by remember(trip.id) { mutableStateOf(true) }
    var selectedDay by remember(trip.id) { mutableStateOf(1) }

    LaunchedEffect(accessToken, trip.id) {
        loading = true
        itinerary = if (accessToken == null) {
            emptyList()
        } else {
            runCatching {
                withContext(Dispatchers.IO) { client.itinerary(accessToken, trip.id) }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    val dayCount = tripDayCount(trip, itinerary)
    val selectedItinerary = itinerary
        .filter { it.dayNumber == selectedDay }
        .sortedWith(compareBy(ItineraryItem::orderIndex))

    Box(Modifier.fillMaxSize()) {
        ItineraryMap(
            itinerary = selectedItinerary,
            modifier = Modifier.fillMaxSize(),
        )
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = StogCanvas,
            scrimColor = Color.Transparent,
        ) {
            HomeTripItineraryContent(
                trip = trip,
                itinerary = itinerary,
                selectedDay = selectedDay,
                dayCount = dayCount,
                onDaySelected = { selectedDay = it },
                loading = loading,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTripItineraryContent(
    trip: TripSummary,
    itinerary: List<ItineraryItem>,
    selectedDay: Int = 1,
    dayCount: Int = 1,
    onDaySelected: (Int) -> Unit = {},
    loading: Boolean,
    onOpenDetail: () -> Unit = {},
) {
    val selectedItinerary = itinerary
        .filter { it.dayNumber == selectedDay }
        .sortedWith(compareBy(ItineraryItem::orderIndex))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = trip.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = StogInk,
                    maxLines = 1,
                )
            }
            Text(
                text = "${homeTripStatus(trip.mode)} · ${homeTripDateRange(trip)}",
                style = MaterialTheme.typography.bodySmall,
                color = StogMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
            ) {
                items((1..dayCount).toList()) { day ->
                    val selected = day == selectedDay
                    Card(
                        onClick = { onDaySelected(day) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) StogInk else StogSurface,
                            contentColor = if (selected) StogCanvas else StogInk,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    ) {
                        Text(
                            text = "DAY $day",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                top = 10.dp,
                                end = 16.dp,
                                bottom = 2.dp,
                            ),
                        )
                        tripDayDateText(trip, day)?.let { date ->
                            Text(
                                text = date,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 10.dp,
                                ),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = StogCanvas,
                        shape = RoundedCornerShape(18.dp),
                    )
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SheetStat("DAY $selectedDay", "${selectedItinerary.size}곳", HomeGlyph.LOCATION)
                SheetStat("여행 상태", homeTripStatus(trip.mode), HomeGlyph.FLAG)
            }
            Button(
                onClick = onOpenDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .stogTouchTarget(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StogYellow,
                    contentColor = StogInk,
                ),
            ) {
                Text("상세 더보기")
            }
            Text(
                text = "여행 일정",
                style = MaterialTheme.typography.titleMedium,
                color = StogInk,
            )
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = StogYellow,
                )
                selectedItinerary.isEmpty() -> Text(
                    text = "아직 확정된 일정이 없어요.",
                    color = StogMuted,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = selectedItinerary,
                        key = { "${it.basketItemId}-${it.dayNumber}-${it.orderIndex}" },
                    ) { item ->
                        ItinerarySheetRow(item)
                    }
                }
            }
        }
    }
}

private fun tripDayCount(
    trip: TripSummary,
    itinerary: List<ItineraryItem>,
): Int {
    val itineraryDays = itinerary.maxOfOrNull(ItineraryItem::dayNumber) ?: 0
    val dateDays = runCatching {
        val start = LocalDate.parse(requireNotNull(trip.plannedStartDate))
        val end = LocalDate.parse(requireNotNull(trip.plannedEndDate))
        ChronoUnit.DAYS.between(start, end).toInt() + 1
    }.getOrDefault(0)
    return maxOf(1, itineraryDays, dateDays)
}

private fun tripDayDateText(trip: TripSummary, day: Int): String? =
    runCatching {
        LocalDate.parse(requireNotNull(trip.plannedStartDate))
            .plusDays((day - 1).toLong())
            .format(DateTimeFormatter.ofPattern("M/d"))
    }.getOrNull()

@Composable
private fun SheetStat(
    label: String,
    value: String,
    icon: HomeGlyph,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeGlyphIcon(icon, tint = StogYellow)
        Column {
            Text(label, color = StogMuted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = StogInk, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ItinerarySheetRow(item: ItineraryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(64.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(item.plannedArrival ?: "시간 미정", color = StogMuted)
            Text("DAY ${item.dayNumber}", color = StogMuted, style = MaterialTheme.typography.labelSmall)
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .size(30.dp)
                .background(StogYellow, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            HomeGlyphIcon(
                glyph = when {
                    item.category?.contains("food", ignoreCase = true) == true -> HomeGlyph.LOCATION
                    item.category?.contains("cafe", ignoreCase = true) == true -> HomeGlyph.LOCATION
                    else -> HomeGlyph.LOCATION
                },
                tint = StogInk,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title ?: "장소 정보 없음",
                style = MaterialTheme.typography.titleMedium,
                color = StogInk,
            )
            item.address?.takeIf(String::isNotBlank)?.let { address ->
                Text(
                    text = address,
                    color = StogMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
        HomeGlyphIcon(HomeGlyph.BOOKMARK, "저장된 일정", StogMuted)
    }
}

@Composable
private fun ItineraryMap(
    itinerary: List<ItineraryItem>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { MapView(context) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }

    DisposableEffect(googleMap, itinerary) {
        val map = googleMap
        val markers = mutableListOf<Marker>()
        var route: Polyline? = null
        val locatedItems = itinerary.filter { it.latitude != null && it.longitude != null }
        val points = locatedItems.map { item ->
            LatLng(item.latitude!!, item.longitude!!)
        }
        if (map != null && points.isNotEmpty()) {
            points.forEachIndexed { index, point ->
                map.addMarker(
                    MarkerOptions()
                        .position(point)
                        .title("${index + 1}. ${locatedItems[index].title.orEmpty()}")
                        .icon(numberedMarkerIcon(index + 1)),
                )?.let(markers::add)
            }
            if (points.size > 1) {
                route = map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(StogYellow.toArgb())
                        .width(5f)
                        .pattern(listOf<PatternItem>(Dash(18f), Gap(10f))),
                )
            }
            if (points.size == 1) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.single(), 14f))
            } else {
                val bounds = LatLngBounds.builder().apply {
                    points.forEach(::include)
                }.build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 72))
            }
        }
        onDispose {
            markers.forEach(Marker::remove)
            route?.remove()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                onCreate(Bundle())
                getMapAsync { map ->
                    map.uiSettings.isMapToolbarEnabled = false
                    map.uiSettings.isZoomControlsEnabled = false
                    googleMap = map
                }
            }
        },
    )

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
}

private fun numberedMarkerIcon(number: Int): com.google.android.gms.maps.model.BitmapDescriptor {
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawCircle(
        48f,
        48f,
        44f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StogInk.toArgb() },
    )
    Canvas(bitmap).drawText(
        number.toString(),
        48f,
        63f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        },
    )
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
