package com.stog.app.feature.space

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CellImageOverlayTest {
    @Test
    fun normalizesCellBoundaryForImageClipping() {
        val points = normalizedCellBoundary(
            boundary = listOf(
                CellCoordinate(0.0, 0.0),
                CellCoordinate(2.0, 0.0),
                CellCoordinate(0.0, 2.0),
            ),
            size = 32,
        )

        assertEquals(0f, points[0].x, 0.001f)
        assertEquals(32f, points[0].y, 0.001f)
        assertEquals(0f, points[1].x, 0.001f)
        assertEquals(0f, points[1].y, 0.001f)
        assertEquals(32f, points[2].x, 0.001f)
        assertEquals(32f, points[2].y, 0.001f)
        assertTrue(points.all { it.x in 0f..32f && it.y in 0f..32f })
    }

    @Test
    fun decodesImageFromOneBufferedResponseBody() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val bytes = ByteArrayOutputStream().also { output ->
            check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
        }.toByteArray()

        val decoded = decodeCellImage(bytes)

        assertNotNull(decoded)
        assertEquals(4, decoded!!.width)
        assertEquals(4, decoded.height)
        assertEquals(Color.RED, decoded.getPixel(2, 2))
    }
}
