package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.uber.h3core.H3Core

class CellViewportStateTest {
    @Test
    fun rapidViewportChangesIgnoreTheStaleDebouncedResultWithoutSleeping() {
        val coordinator = CellViewportCoordinator()
        val first = coordinator.onCameraIdle(viewport(), nowMillis = 1_000)!!
        val second = coordinator.onCameraIdle(viewport(offset = 0.01), nowMillis = 1_100)!!

        assertFalse(coordinator.isDue(first, nowMillis = 1_300))
        assertFalse(coordinator.applyResult(first, listOf(summary())))
        assertTrue(coordinator.isDue(second, nowMillis = 1_400))
        assertTrue(coordinator.applyResult(second, listOf(summary())))
        assertEquals(listOf(summary()), coordinator.visibleCells)
    }

    @Test
    fun lowZoomKeepsCellsAvailableForClusterMarkers() {
        val coordinator = CellViewportCoordinator()
        val request = coordinator.onCameraIdle(viewport(), nowMillis = 0)!!
        assertTrue(coordinator.applyResult(request, listOf(summary())))

        assertFalse(summary().rendersPolygonAt(viewport(zoom = CELL_POLYGON_MINIMUM_ZOOM)))
        assertTrue(summary().rendersPolygonAt(viewport()))
        assertNull(
            coordinator.onCameraIdle(
                viewport(zoom = CELL_POLYGON_MINIMUM_ZOOM),
                nowMillis = 1_000,
            ),
        )
        assertEquals(listOf(summary()), coordinator.visibleCells)
    }

    @Test
    fun clustersResolutionTenCellsByAResolutionSevenParent() {
        val h3 = H3Core.newInstance()
        val parent = h3.latLngToCell(35.815, 127.15, CELL_CLUSTER_RESOLUTION)
        val children = h3.cellToChildren(parent, 10).take(2)

        val clusters = clusterCells(children.map { summary(h3.h3ToString(it)) })

        assertEquals(1, clusters.size)
        assertEquals(2, clusters.single().cellCount)
        assertEquals(2L, clusters.single().landmarkCount)
        assertEquals(h3.h3ToString(parent), clusters.single().parentCellId)
    }

    @Test
    fun authLossClearsPrivateOverlayAndViewportCache() {
        val coordinator = CellViewportCoordinator()
        coordinator.onAuthorizationChanged("viewer-token")
        val request = coordinator.onCameraIdle(viewport(), nowMillis = 0)!!
        assertTrue(coordinator.applyResult(request, listOf(summary())))
        assertEquals(1, coordinator.cacheSize())

        coordinator.onAuthorizationChanged(null)

        assertTrue(coordinator.visibleCells.isEmpty())
        assertEquals(0, coordinator.cacheSize())
    }

    @Test
    fun viewportCacheIsBoundedAndReusesTheCurrentViewport() {
        val coordinator = CellViewportCoordinator(cacheCapacity = 2)
        repeat(3) { index ->
            val request = coordinator.onCameraIdle(viewport(offset = index * 0.01), index.toLong())!!
            assertTrue(coordinator.applyResult(request, listOf(summary("8a2a1072b5${index}ffff"))))
        }

        assertEquals(2, coordinator.cacheSize())
        assertNull(coordinator.onCameraIdle(viewport(offset = 0.02), nowMillis = 99))
        assertEquals("8a2a1072b52ffff", coordinator.visibleCells.single().cellId)
    }

    private fun viewport(offset: Double = 0.0, zoom: Float = 13f) = CellViewport(
        southWestLatitude = 35.80 + offset,
        southWestLongitude = 127.10 + offset,
        northEastLatitude = 35.83 + offset,
        northEastLongitude = 127.20 + offset,
        zoom = zoom,
    )

    private fun summary(cellId: String = "8a2a1072b59ffff") = CellSummary(
        cellId = cellId,
        centroid = CellCoordinate(35.815, 127.15),
        boundary = listOf(
            CellCoordinate(35.815, 127.15),
            CellCoordinate(35.816, 127.15),
            CellCoordinate(35.816, 127.151),
        ),
        background = CellBackground.HOT,
        badges = listOf(CellBadge.LANDMARK),
        landmarkCount = 1,
        publicPhotoCount = 1,
        publicPhotoLikeCount = 1,
        topPhotoId = 1,
        myVisitCount = 0,
        myPhotoCount = 0,
    )
}
