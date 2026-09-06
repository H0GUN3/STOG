package com.stog.app.feature.record

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

internal class PhotoDerivativeStore(
    private val context: Context,
    private val derivativeWriter: (Bitmap, File, Int, Int) -> Unit = ::writeScaledPhoto,
) {
    fun createCameraOutput(): File = File(rootDirectory(), "capture-${UUID.randomUUID()}.jpg")
        .also { file -> file.parentFile?.mkdirs() }

    fun copyGallerySelection(uri: Uri): File = File(rootDirectory(), "gallery-${UUID.randomUUID()}.source")
        .also { target ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: throw IOException("선택한 사진을 읽을 수 없어요.")
        }

    fun galleryProvenance(
        uri: Uri,
        hasMediaLocationPermission: Boolean,
    ): SetLogGalleryProvenance {
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLocationPermission) {
            MediaStore.setRequireOriginal(uri)
        } else {
            uri
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(original, "r")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                setLogGalleryProvenance(
                    SetLogGalleryExif(
                        coordinates = exifCoordinates(exif),
                        dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                        offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                        gpsDateStamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP),
                        gpsTimeStamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP),
                    ),
                    hasMediaLocationAccess = hasMediaLocationPermission,
                )
            }
        }.getOrNull() ?: SetLogGalleryProvenance(null, null)
    }

    fun prepare(
        source: PhotoSource,
        tripId: Long,
        sourceFile: File,
        coordinates: PhotoCoordinates?,
        takenAt: String?,
        caption: String? = null,
    ): PreparedPhoto {
        val id = UUID.randomUUID().toString()
        val directory = File(rootDirectory(), id).also { check(it.mkdirs()) }
        val original = File(directory, "normalized-original.jpg")
        val thumbnail = File(directory, "thumbnail.jpg")
        val decoded = try {
            decodeOriented(sourceFile)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            sourceFile.delete()
            throw error
        }
        try {
            derivativeWriter(decoded, original, NORMALIZED_ORIGINAL_WIDTH, NORMALIZED_ORIGINAL_HEIGHT)
            derivativeWriter(decoded, thumbnail, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            sourceFile.delete()
            throw error
        } finally {
            decoded.recycle()
        }
        return PreparedPhoto(
            id = id,
            source = source,
            tripId = tripId,
            normalizedOriginalPath = original.absolutePath,
            thumbnailPath = thumbnail.absolutePath,
            normalizedOriginalBytes = original.length(),
            thumbnailBytes = thumbnail.length(),
            originalSha256 = original.sha256(),
            thumbnailSha256 = thumbnail.sha256(),
            coordinates = coordinates,
            takenAt = takenAt,
            caption = caption?.trim()?.takeIf(String::isNotBlank),
            sourceFilePath = sourceFile.absolutePath,
        )
    }

    fun delete(photo: PreparedPhoto): Boolean {
        val files = listOfNotNull(
            photo.sourceFilePath?.let(::File),
            File(photo.normalizedOriginalPath),
            File(photo.thumbnailPath),
        ).distinctBy(File::getAbsolutePath)
        files.forEach { file ->
            if (file.isFile) file.delete()
        }
        files.mapNotNull(File::getParentFile)
            .distinctBy(File::getAbsolutePath)
            .sortedByDescending { it.absolutePath.length }
            .forEach(File::delete)
        return files.none(File::exists)
    }

    private fun decodeOriented(source: File): Bitmap {
        val decoded = BitmapFactory.decodeFile(source.absolutePath)
            ?: throw IOException("사진을 열 수 없어요.")
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(orientation)
        if (matrix == null) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun rootDirectory(): File = File(context.filesDir, "record/photos").also { it.mkdirs() }
}

private fun writeScaledPhoto(
    source: Bitmap,
    output: File,
    targetWidth: Int,
    targetHeight: Int,
) {
    val bounds = centerCropBounds(source.width, source.height, targetWidth, targetHeight)
    val crop = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
    val scaled = Bitmap.createScaledBitmap(crop, targetWidth, targetHeight, true)
    try {
        val isThumbnail = targetWidth == THUMBNAIL_WIDTH && targetHeight == THUMBNAIL_HEIGHT
        var quality = PHOTO_JPEG_QUALITY
        while (true) {
            FileOutputStream(output).use { stream ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    "사진을 저장할 수 없어요."
                }
            }
            if (!isThumbnail || output.length() <= THUMBNAIL_MAX_BYTES) return
            if (quality <= 5) {
                throw IOException("썸네일을 업로드 제한 이하로 저장할 수 없어요.")
            }
            quality -= 5
        }
    } finally {
        if (scaled !== crop) scaled.recycle()
        if (crop !== source) crop.recycle()
    }
}

internal fun exifCoordinates(exif: ExifInterface): PhotoCoordinates? {
    val values = FloatArray(2)
    return if (exif.getLatLong(values)) PhotoCoordinates(values[0].toDouble(), values[1].toDouble()) else null
}

private fun orientationMatrix(orientation: Int): Matrix? = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        else -> return null
    }
}

internal class PendingPhotoStore(context: Context) {
    private val atomic = PendingAtomicFile(File(context.filesDir, "record/pending-photo.properties").also {
        it.parentFile?.mkdirs()
    })

    fun save(photo: PreparedPhoto) {
        val properties = Properties().apply {
            setProperty("id", photo.id)
            setProperty("source", photo.source.name)
            setProperty("trip_id", photo.tripId.toString())
            setProperty("original", photo.normalizedOriginalPath)
            setProperty("thumbnail", photo.thumbnailPath)
            setProperty("original_size", photo.normalizedOriginalBytes.toString())
            setProperty("thumbnail_size", photo.thumbnailBytes.toString())
            setProperty("original_sha256", photo.originalSha256)
            setProperty("thumbnail_sha256", photo.thumbnailSha256)
            photo.coordinates?.let {
                setProperty("latitude", it.latitude.toString())
                setProperty("longitude", it.longitude.toString())
            }
            photo.takenAt?.let { setProperty("taken_at", it) }
            photo.caption?.let { setProperty("caption", it) }
            photo.sourceFilePath?.let { setProperty("source_file", it) }
            setProperty("finalization_committed", photo.finalizationCommitted.toString())
        }
        val output = atomic.startWrite()
        try {
            properties.store(output, null)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun replace(photo: PreparedPhoto, deleteReplaced: (PreparedPhoto) -> Boolean) {
        val replaced = load()
        save(photo)
        if (replaced != null && replaced.id != photo.id) deleteReplaced(replaced)
    }

    fun load(): PreparedPhoto? {
        val properties = runCatching {
            Properties().also { values -> atomic.openRead().use(values::load) }
        }.getOrElse { return null }
        return runCatching {
            val original = properties.getProperty("original")
            val thumbnail = properties.getProperty("thumbnail")
            val committed = properties.getProperty("finalization_committed").toBoolean()
            if (!committed && (!File(original).isFile || !File(thumbnail).isFile)) {
                return@runCatching null
            }
            PreparedPhoto(
                id = properties.getProperty("id"),
                source = PhotoSource.valueOf(properties.getProperty("source")),
                tripId = properties.getProperty("trip_id").toLong(),
                normalizedOriginalPath = original,
                thumbnailPath = thumbnail,
                normalizedOriginalBytes = properties.getProperty("original_size")
                    ?.toLong() ?: properties.getProperty("size")?.toLong() ?: File(original).length(),
                thumbnailBytes = properties.getProperty("thumbnail_size")
                    ?.toLong() ?: File(thumbnail).length(),
                originalSha256 = properties.getProperty("original_sha256")
                    ?.requireSha256() ?: File(original).sha256(),
                thumbnailSha256 = properties.getProperty("thumbnail_sha256")
                    ?.requireSha256() ?: File(thumbnail).sha256(),
                coordinates = properties.getProperty("latitude")?.let { latitude ->
                    PhotoCoordinates(latitude.toDouble(), properties.getProperty("longitude").toDouble())
                },
                takenAt = properties.getProperty("taken_at"),
                caption = properties.getProperty("caption"),
                sourceFilePath = properties.getProperty("source_file"),
                finalizationCommitted = committed,
            )
        }.getOrNull()
    }

    fun markFinalizationCommitted(photo: PreparedPhoto) {
        save(photo.copy(finalizationCommitted = true))
    }

    fun clear() {
        atomic.delete()
    }
}

private class PendingAtomicFile(private val base: File) {
    private val pending = File(base.path + ".new")
    private val backup = File(base.path + ".bak")

    fun startWrite(): FileOutputStream {
        restoreBackup()
        if (base.exists() && !base.renameTo(backup)) {
            throw IOException("기존 사진 복구 정보를 보존할 수 없어요.")
        }
        return FileOutputStream(pending)
    }

    fun finishWrite(output: FileOutputStream) {
        output.fd.sync()
        output.close()
        if (!pending.renameTo(base)) {
            restoreBackup()
            throw IOException("사진 복구 정보를 저장할 수 없어요.")
        }
        backup.delete()
    }

    fun failWrite(output: FileOutputStream) {
        runCatching { output.close() }
        pending.delete()
        restoreBackup()
    }

    fun openRead() = run {
        restoreBackup()
        pending.delete()
        base.inputStream()
    }

    fun delete() {
        base.delete()
        pending.delete()
        backup.delete()
    }

    private fun restoreBackup() {
        if (!backup.exists()) return
        base.delete()
        if (!backup.renameTo(base)) {
            throw IOException("사진 복구 정보를 복원할 수 없어요.")
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun String?.requireSha256(): String = requireNotNull(this).also { value ->
    require(value.matches(Regex("[0-9a-f]{64}")))
}

internal fun photoFileProviderAuthority(packageName: String): String = "$packageName.fileprovider"

internal fun cameraUriGrantFlags(): Int =
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION

private const val PHOTO_DOWNLOAD_TIMEOUT_MILLIS = 15_000

internal class PhotoDownloadStore(private val context: Context) {
    fun download(originalUrl: String, displayName: String): Uri {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, PHOTO_CONTENT_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/STOG")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("기기 저장 위치를 만들 수 없어요.")
        try {
            val connection = URL(originalUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = PHOTO_DOWNLOAD_TIMEOUT_MILLIS
            connection.readTimeout = PHOTO_DOWNLOAD_TIMEOUT_MILLIS
            try {
                if (connection.responseCode !in 200..299) throw IOException("사진을 내려받지 못했어요.")
                connection.inputStream.use { input ->
                    resolver.openOutputStream(outputUri)?.use { output -> input.copyTo(output) }
                        ?: throw IOException("기기 저장소에 쓸 수 없어요.")
                }
            } finally {
                connection.disconnect()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    outputUri,
                    android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null,
                )
            }
            return outputUri
        } catch (error: Throwable) {
            resolver.delete(outputUri, null, null)
            throw error
        }
    }
}
