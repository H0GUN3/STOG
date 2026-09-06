package com.stog.app.feature.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID

internal class ProfileImageStore(private val context: Context) {
    fun prepare(uri: Uri): File {
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IOException("선택한 사진을 읽을 수 없어요.")
        if (bounds.outWidth < 1 || bounds.outHeight < 1) {
            throw IOException("선택한 사진을 읽을 수 없어요.")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("선택한 사진을 읽을 수 없어요.")
        val oriented = orient(bitmap, orientation(uri))
        val target = File(context.cacheDir, "profile-avatar-${UUID.randomUUID()}.jpg")
        try {
            target.outputStream().use { output ->
                check(oriented.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
            return target
        } finally {
            oriented.recycle()
        }
    }

    private fun orientation(uri: Uri): Int = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 1600 || height / sample > 1600) sample *= 2
        return sample
    }
}
