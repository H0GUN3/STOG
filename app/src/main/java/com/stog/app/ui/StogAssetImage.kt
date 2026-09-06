package com.stog.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun StogAssetImage(
    assetPath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    maxDimensionPx: Int = 1024,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        context,
        assetPath,
        maxDimensionPx,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeAsset(context, assetPath, maxDimensionPx)
        }
    }
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Box(
        modifier = modifier.then(semanticsModifier),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

private fun decodeAsset(
    context: android.content.Context,
    assetPath: String,
    maxDimensionPx: Int,
): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(assetPath).use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.assets.open(assetPath).use { stream ->
        BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
    }
}.getOrNull()

private fun sampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
    var sample = 1
    while (width / sample > maxDimensionPx || height / sample > maxDimensionPx) {
        sample *= 2
    }
    return sample
}
