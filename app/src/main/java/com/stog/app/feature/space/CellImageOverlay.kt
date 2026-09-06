package com.stog.app.feature.space

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.max

internal const val CELL_IMAGE_BITMAP_SIZE = 256
private const val CELL_IMAGE_MAX_SOURCE_BYTES = 12L * 1024L * 1024L
private const val CELL_IMAGE_MAX_SOURCE_DIMENSION = 2048

internal data class CellImagePoint(
    val x: Float,
    val y: Float,
)

internal fun cellImageBounds(cell: CellSummary): LatLngBounds =
    LatLngBounds.builder().apply {
        cell.boundary.forEach { point ->
            include(LatLng(point.latitude, point.longitude))
        }
    }.build()

internal fun normalizedCellBoundary(
    boundary: List<CellCoordinate>,
    size: Int = CELL_IMAGE_BITMAP_SIZE,
): List<CellImagePoint> {
    require(boundary.size >= 3)
    require(size > 0)

    val minLatitude = boundary.minOf(CellCoordinate::latitude)
    val maxLatitude = boundary.maxOf(CellCoordinate::latitude)
    val minLongitude = boundary.minOf(CellCoordinate::longitude)
    val maxLongitude = boundary.maxOf(CellCoordinate::longitude)
    val latitudeSpan = (maxLatitude - minLatitude).takeIf { it > 0.0 } ?: error("cell latitude span is empty")
    val longitudeSpan = (maxLongitude - minLongitude).takeIf { it > 0.0 } ?: error("cell longitude span is empty")

    return boundary.map { point ->
        CellImagePoint(
            x = ((point.longitude - minLongitude) / longitudeSpan * size).toFloat(),
            y = ((maxLatitude - point.latitude) / latitudeSpan * size).toFloat(),
        )
    }
}

internal fun clipCellImage(
    source: Bitmap,
    boundary: List<CellCoordinate>,
    size: Int = CELL_IMAGE_BITMAP_SIZE,
): Bitmap {
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val normalizedBoundary = normalizedCellBoundary(boundary, size)
    val path = Path().apply {
        normalizedBoundary.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
    val imageScale = max(
        size.toFloat() / source.width,
        size.toFloat() / source.height,
    )
    val imageWidth = source.width * imageScale
    val imageHeight = source.height * imageScale
    val imageRect = RectF(
        (size - imageWidth) / 2f,
        (size - imageHeight) / 2f,
        (size + imageWidth) / 2f,
        (size + imageHeight) / 2f,
    )

    canvas.save()
    canvas.clipPath(path)
    canvas.drawBitmap(
        source,
        null,
        imageRect,
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )
    canvas.restore()
    return output
}

internal fun decodeCellImage(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sampleSize = 1
    while (
        maxOf(bounds.outWidth, bounds.outHeight) / sampleSize >
            CELL_IMAGE_MAX_SOURCE_DIMENSION
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun downloadCellImage(url: String): Bitmap? {
    if (!isSecureImageUrl(url)) return null
    val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        if (connection.responseCode !in 200..299) {
            null
        } else if (connection.contentLengthLong > CELL_IMAGE_MAX_SOURCE_BYTES) {
            null
        } else {
            val bytes = connection.inputStream.use { stream -> stream.readBytes() }
            decodeCellImage(bytes)
        }
    } catch (_: IOException) {
        null
    } finally {
        connection.disconnect()
    }
}

internal fun isSecureImageUrl(value: String?): Boolean =
    value?.let {
        runCatching {
            val uri = URI(it)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    } == true
