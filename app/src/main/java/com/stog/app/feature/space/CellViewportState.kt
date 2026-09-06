package com.stog.app.feature.space

import java.util.LinkedHashMap

internal data class CellViewport(
    val southWestLatitude: Double,
    val southWestLongitude: Double,
    val northEastLatitude: Double,
    val northEastLongitude: Double,
    val zoom: Float,
) {
    init {
        require(southWestLatitude < northEastLatitude)
        require(southWestLongitude < northEastLongitude)
    }

    val rendersPolygons: Boolean
        get() = zoom > CELL_POLYGON_MINIMUM_ZOOM
}

internal fun CellSummary.rendersPolygonAt(zoom: Float): Boolean =
    zoom > CELL_POLYGON_MINIMUM_ZOOM && boundary.size >= 3

internal fun CellSummary.rendersPolygonAt(viewport: CellViewport): Boolean =
    rendersPolygonAt(viewport.zoom)

internal const val CELL_CLUSTER_RESOLUTION = 7

internal data class CellCluster(
    val parentCellId: String,
    val centroid: CellCoordinate,
    val cellCount: Int,
    val landmarkCount: Long,
)

internal fun clusterCells(cells: List<CellSummary>): List<CellCluster> {
    val h3 = cellH3 ?: return emptyList()
    return cells.mapNotNull { cell ->
        runCatching {
            val parent = h3.cellToParent(h3.stringToH3(cell.cellId), CELL_CLUSTER_RESOLUTION)
            parent to cell
        }.getOrNull()
    }.groupBy({ it.first }, { it.second }).map { (parent, members) ->
        val centroid = h3.cellToLatLng(parent)
        CellCluster(
            parentCellId = h3.h3ToString(parent),
            centroid = CellCoordinate(centroid.lat, centroid.lng),
            cellCount = members.size,
            landmarkCount = members.sumOf { it.landmarkCount },
        )
    }
}

internal data class CellViewportRequest(
    val id: Long,
    val viewport: CellViewport,
    val authorizationScope: String,
    val dueAtMillis: Long,
)

/**
 * Main-thread state for a map's sparse Cell layer. Time is supplied by the caller so
 * cancellation and debounce behavior remain deterministic in JVM tests.
 */
internal class CellViewportCoordinator(
    private val debounceMillis: Long = CELL_VIEWPORT_DEBOUNCE_MILLIS,
    private val cacheCapacity: Int = CELL_VIEWPORT_CACHE_CAPACITY,
) {
    private val cache = object : LinkedHashMap<String, List<CellSummary>>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CellSummary>>?): Boolean =
            size > cacheCapacity
    }

    private var nextRequestId = 0L
    private var authorizationScope = "guest"
    private var pendingRequest: CellViewportRequest? = null

    var visibleCells: List<CellSummary> = emptyList()
        private set

    fun onAuthorizationChanged(accessToken: String?) {
        val nextScope = accessToken?.takeIf(String::isNotBlank) ?: "guest"
        if (nextScope == authorizationScope) return
        authorizationScope = nextScope
        pendingRequest = null
        visibleCells = emptyList()
        cache.clear()
    }

    fun onCameraIdle(viewport: CellViewport, nowMillis: Long): CellViewportRequest? {
        val cacheKey = cacheKey(viewport)
        cache[cacheKey]?.let { cached ->
            pendingRequest = null
            visibleCells = cached
            return null
        }
        return CellViewportRequest(
            id = ++nextRequestId,
            viewport = viewport,
            authorizationScope = authorizationScope,
            dueAtMillis = nowMillis + debounceMillis,
        ).also { pendingRequest = it }
    }

    fun isDue(request: CellViewportRequest, nowMillis: Long): Boolean =
        pendingRequest == request && nowMillis >= request.dueAtMillis

    fun applyResult(request: CellViewportRequest, cells: List<CellSummary>): Boolean {
        if (pendingRequest != request || request.authorizationScope != authorizationScope) return false
        pendingRequest = null
        val aggregated = cells.distinctBy(CellSummary::cellId)
        cache[cacheKey(request.viewport)] = aggregated
        visibleCells = aggregated
        return true
    }

    fun clearPendingRequest(request: CellViewportRequest): Boolean {
        if (pendingRequest != request) return false
        pendingRequest = null
        return true
    }

    fun pendingRequest(): CellViewportRequest? = pendingRequest

    fun cacheSize(): Int = cache.size

    private fun cacheKey(viewport: CellViewport): String = listOf(
        authorizationScope,
        viewport.southWestLatitude,
        viewport.southWestLongitude,
        viewport.northEastLatitude,
        viewport.northEastLongitude,
    ).joinToString("|")
}
