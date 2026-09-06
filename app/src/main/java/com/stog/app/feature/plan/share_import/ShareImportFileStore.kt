package com.stog.app.feature.plan.share_import

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Properties
import java.util.UUID

interface ShareImportContentSource {
    fun mimeType(uri: String): String?
    fun open(uri: String): InputStream?
}

data class ShareImportLimits(
    val maxAttachmentCount: Int = 20,
    val maxAttachmentBytes: Long = 25L * 1024L * 1024L,
)

data class LocalShareImport(
    val id: String,
    val directory: File,
    val copiedAttachments: List<File>,
    val copiedAttachmentMimeTypes: List<String>,
    val failedAttachments: List<String> = emptyList(),
)

class ShareImportStoreException(
    val rejection: ShareImportRejection,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class ShareImportFileStore internal constructor(
    private val rootDirectory: File,
    private val contentSource: ShareImportContentSource,
    private val limits: ShareImportLimits = ShareImportLimits(),
    private val persistence: ShareImportPersistence? = null,
) {
    constructor(context: Context) : this(
        rootDirectory = File(context.noBackupFilesDir, ROOT_DIRECTORY_NAME),
        contentSource = AndroidShareImportContentSource(context),
        persistence = RoomShareImportPersistence(
            com.stog.app.core.database.StogDatabase.get(context).shareImportDao(),
        ),
    )

    fun save(
        payload: ShareImportPayload,
        normalized: NormalizedShareImport,
    ): StoredShareImport {
        if (normalized.attachmentUris.size > limits.maxAttachmentCount) {
            throw ShareImportStoreException(
                ShareImportRejection.TOO_MANY_ATTACHMENTS,
                "첨부 파일 수가 저장 한도를 초과했습니다.",
            )
        }
        ensureRootDirectory()
        val id = UUID.randomUUID().toString()
        val stagingDirectory = File(rootDirectory, ".staging-$id")
        val destinationDirectory = File(rootDirectory, id)
        if (!stagingDirectory.mkdir()) {
            throw ShareImportStoreException(
                ShareImportRejection.STORAGE_FAILURE,
                "공유 원본 저장 폴더를 만들 수 없습니다.",
            )
        }

        var rawCommitted = false
        var rawStatePersisted = false
        try {
            val copied = normalized.attachmentUris.mapIndexed { index, uri ->
                copyVerifiedImage(uri, stagingDirectory, index)
            }
            writeMetadata(
                directory = stagingDirectory,
                payload = payload,
                normalized = normalized,
                copied = copied,
            )
            if (!stagingDirectory.renameTo(destinationDirectory)) {
                throw ShareImportStoreException(
                    ShareImportRejection.STORAGE_FAILURE,
                    "공유 원본 저장을 완료할 수 없습니다.",
                )
            }
            rawCommitted = true
            persistence?.persistRaw(id, payload, normalized)
            rawStatePersisted = persistence != null
            persistence?.persistCandidates(id, normalized)
            return load(destinationDirectory.name)
                ?: throw ShareImportStoreException(
                    ShareImportRejection.STORAGE_FAILURE,
                    "저장한 공유 원본을 다시 읽을 수 없습니다.",
                )
        } catch (error: ShareImportStoreException) {
            stagingDirectory.deleteRecursively()
            if (!rawCommitted) destinationDirectory.deleteRecursively()
            throw error
        } catch (error: IOException) {
            stagingDirectory.deleteRecursively()
            if (!rawCommitted) destinationDirectory.deleteRecursively()
            throw ShareImportStoreException(
                ShareImportRejection.STORAGE_FAILURE,
                "공유 원본을 저장하지 못했습니다.",
                error,
            )
        } catch (error: android.database.sqlite.SQLiteException) {
            stagingDirectory.deleteRecursively()
            if (!rawStatePersisted) destinationDirectory.deleteRecursively()
            throw ShareImportStoreException(
                ShareImportRejection.STORAGE_FAILURE,
                "공유 원본의 로컬 상태를 저장하지 못했습니다.",
                error,
            )
        } catch (error: RuntimeException) {
            stagingDirectory.deleteRecursively()
            if (!rawStatePersisted) destinationDirectory.deleteRecursively()
            throw error
        }
    }

    fun loadAll(): List<StoredShareImport> {
        cleanupInterruptedIntakes()
        return ensureRootDirectory()
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(STAGING_PREFIX) }
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.local.directory.lastModified() }
    }

    fun load(id: String): StoredShareImport? {
        if (id.isBlank() || id.contains('/') || id.contains('\\')) return null
        val directory = File(ensureRootDirectory(), id)
        return read(directory)
    }

    fun saveReview(
        id: String,
        decisions: Map<Int, ShareMentionDecision>,
        manualEntries: List<String>,
    ) {
        val directory = File(ensureRootDirectory(), id)
        val metadataFile = File(directory, METADATA_FILE_NAME)
        if (!metadataFile.isFile) return

        val properties = readProperties(metadataFile)
        decisions.forEach { (index, decision) ->
            properties.setProperty("decision_$index", decision.name)
        }
        properties.setProperty("decision_count", decisions.size.toString())
        manualEntries.forEachIndexed { index, title ->
            properties.setProperty("manual_$index", title)
        }
        properties.setProperty("manual_count", manualEntries.size.toString())
        val mentionCount = properties.getProperty("mention_count", "0").toIntOrNull() ?: 0
        val complete = (mentionCount == decisions.size && manualEntries.isNotEmpty()) ||
            (mentionCount == decisions.size && mentionCount > 0)
        properties.setProperty(
            "status",
            if (complete) ShareImportStatus.COMPLETED.name else ShareImportStatus.NEEDS_REVIEW.name,
        )
        storeProperties(metadataFile, properties)
        directory.setLastModified(System.currentTimeMillis())
    }

    private fun copyVerifiedImage(
        uri: String,
        directory: File,
        index: Int,
    ): CopiedAttachment {
        if (!uri.startsWith("content:", ignoreCase = true)) {
            throw ShareImportStoreException(
                ShareImportRejection.UNSUPPORTED_ATTACHMENT,
                "content URI 형식의 첨부 파일만 저장할 수 있습니다.",
            )
        }
        val partial = File(directory, "attachment-$index.partial")
        try {
            val input = try {
                contentSource.open(uri)
            } catch (error: SecurityException) {
                throw ShareImportStoreException(
                    ShareImportRejection.UNAVAILABLE_ATTACHMENT,
                    "공유 첨부 파일 접근 권한이 없어졌습니다.",
                    error,
                )
            } catch (error: IOException) {
                throw ShareImportStoreException(
                    ShareImportRejection.UNAVAILABLE_ATTACHMENT,
                    "공유 첨부 파일을 읽을 수 없습니다.",
                    error,
                )
            } ?: throw ShareImportStoreException(
                ShareImportRejection.UNAVAILABLE_ATTACHMENT,
                "공유 첨부 파일을 읽을 수 없습니다.",
            )
            val header = ByteArray(12)
            var headerSize = 0
            var total = 0L
            input.use { source ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("공유 첨부 파일 저장이 중단되었습니다.")
                        }
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > limits.maxAttachmentBytes) {
                            throw ShareImportStoreException(
                                ShareImportRejection.ATTACHMENT_TOO_LARGE,
                                "공유 첨부 파일이 저장 한도를 초과했습니다.",
                            )
                        }
                        if (headerSize < header.size) {
                            val headerCount = minOf(count, header.size - headerSize)
                            buffer.copyInto(header, headerSize, 0, headerCount)
                            headerSize += headerCount
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val detectedMimeType = detectImageMimeType(header, headerSize)
                ?: throw ShareImportStoreException(
                    ShareImportRejection.UNSUPPORTED_ATTACHMENT,
                    "첨부 파일의 실제 이미지 형식을 확인할 수 없습니다.",
                )
            val extension = extensionFor(detectedMimeType)
            val destination = File(directory, "attachment-$index.$extension")
            if (!partial.renameTo(destination)) {
                throw ShareImportStoreException(
                    ShareImportRejection.STORAGE_FAILURE,
                    "공유 첨부 파일 저장을 완료할 수 없습니다.",
                )
            }
            return CopiedAttachment(destination, detectedMimeType)
        } catch (error: ShareImportStoreException) {
            partial.delete()
            throw error
        } catch (error: InterruptedIOException) {
            partial.delete()
            Thread.currentThread().interrupt()
            throw ShareImportStoreException(
                ShareImportRejection.UNAVAILABLE_ATTACHMENT,
                "공유 첨부 파일 저장이 중단되었습니다.",
                error,
            )
        } catch (error: SecurityException) {
            partial.delete()
            throw ShareImportStoreException(
                ShareImportRejection.UNAVAILABLE_ATTACHMENT,
                "공유 첨부 파일 접근 권한이 없어졌습니다.",
                error,
            )
        } catch (error: IOException) {
            partial.delete()
            throw ShareImportStoreException(
                ShareImportRejection.UNAVAILABLE_ATTACHMENT,
                "공유 첨부 파일을 읽을 수 없습니다.",
                error,
            )
        }
    }

    private fun writeMetadata(
        directory: File,
        payload: ShareImportPayload,
        normalized: NormalizedShareImport,
        copied: List<CopiedAttachment>,
    ) {
        val properties = Properties().apply {
            setProperty("source", normalized.source.name)
            normalized.title?.let { setProperty("title", it) }
            normalized.address?.let { setProperty("address", it) }
            normalized.originalUrl?.let { setProperty("original_url", it) }
            setProperty("raw_text", payload.texts.joinToString("\n"))
            payload.htmlText?.let { setProperty("raw_html", it) }
            payload.mimeType?.let { setProperty("mime_type", it) }
            setProperty("status", ShareImportStatus.SAVED.name)
            setProperty("attachment_count", copied.size.toString())
            copied.forEachIndexed { index, attachment ->
                setProperty("attachment_${index}_name", attachment.file.name)
                setProperty("attachment_${index}_mime", attachment.mimeType)
            }
            setProperty("mention_count", normalized.mentions.size.toString())
            normalized.mentions.forEachIndexed { index, mention ->
                setProperty("mention_${index}_title", mention.title)
                mention.address?.let { setProperty("mention_${index}_address", it) }
            }
            setProperty("decision_count", "0")
            setProperty("manual_count", "0")
        }
        storeProperties(File(directory, METADATA_FILE_NAME), properties)
    }

    private fun read(directory: File): StoredShareImport? {
        val metadataFile = File(directory, METADATA_FILE_NAME)
        if (!directory.isDirectory || !metadataFile.isFile) return null
        val properties = runCatching { readProperties(metadataFile) }.getOrNull() ?: return null
        val attachmentCount = properties.getProperty("attachment_count", "0").toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        val attachments = (0 until attachmentCount).map { index ->
            val name = properties.getProperty("attachment_${index}_name")
                ?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it }
                ?: return null
            val mimeType = properties.getProperty("attachment_${index}_mime")
                ?.takeIf { it in setOf("image/jpeg", "image/png", "image/gif", "image/webp") }
                ?: return null
            val file = File(directory, name)
            if (!file.isFile || file.parentFile != directory || file.length() <= 0L) return null
            CopiedAttachment(file, mimeType)
        }
        val mentionCount = properties.getProperty("mention_count", "0").toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        val mentions = (0 until mentionCount).mapNotNull { index ->
            properties.getProperty("mention_${index}_title")?.let { title ->
                PlaceMention(title, properties.getProperty("mention_${index}_address"))
            }
        }
        if (mentions.size != mentionCount) return null
        if (attachmentCount == 0 && properties.getProperty("raw_text").isNullOrBlank() &&
            properties.getProperty("raw_html").isNullOrBlank()
        ) return null
        val normalized = NormalizedShareImport(
            source = properties.getProperty("source")
                ?.let { runCatching { ShareSource.valueOf(it) }.getOrNull() }
                ?: return null,
            title = properties.getProperty("title"),
            address = properties.getProperty("address"),
            originalUrl = properties.getProperty("original_url"),
            mentions = mentions,
            attachmentUris = emptyList(),
        )
        val decisions = properties.stringPropertyNames()
            .filter { it.startsWith("decision_") }
            .mapNotNull { key ->
                val index = key.removePrefix("decision_").toIntOrNull() ?: return@mapNotNull null
                val decision = properties.getProperty(key)
                    ?.let { runCatching { ShareMentionDecision.valueOf(it) }.getOrNull() }
                    ?: return@mapNotNull null
                index to decision
            }
            .toMap()
        val manualCount = properties.getProperty("manual_count", "0").toIntOrNull() ?: 0
        val manualEntries = (0 until manualCount)
            .mapNotNull { properties.getProperty("manual_$it") }
        val status = properties.getProperty("status")
            ?.let { runCatching { ShareImportStatus.valueOf(it.uppercase()) }.getOrNull() }
            ?: return null
        return StoredShareImport(
            local = LocalShareImport(
                id = directory.name,
                directory = directory,
                copiedAttachments = attachments.map(CopiedAttachment::file),
                copiedAttachmentMimeTypes = attachments.map(CopiedAttachment::mimeType),
            ),
            normalized = normalized,
            status = status,
            decisions = decisions,
            manualEntries = manualEntries,
        )
    }

    private fun cleanupInterruptedIntakes() {
        ensureRootDirectory().listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) }
            .forEach(File::deleteRecursively)
    }

    private fun ensureRootDirectory(): File = rootDirectory.also { directory ->
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw ShareImportStoreException(
                ShareImportRejection.STORAGE_FAILURE,
                "공유 원본 저장 폴더를 만들 수 없습니다.",
            )
        }
    }

    private fun storeProperties(file: File, properties: Properties) {
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            temporary.outputStream().use { properties.store(it, null) }
            if (file.exists() && !file.delete()) {
                throw IOException("기존 metadata를 교체할 수 없습니다.")
            }
            if (!temporary.renameTo(file)) {
                throw IOException("metadata 저장을 완료할 수 없습니다.")
            }
        } finally {
            temporary.delete()
        }
    }

    private fun readProperties(file: File): Properties =
        Properties().also { properties ->
            file.inputStream().use(properties::load)
        }

    private fun detectImageMimeType(header: ByteArray, size: Int): String? = when {
        size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() &&
            header[2] == 0xff.toByte() -> "image/jpeg"
        size >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
        size >= 6 && String(header, 0, 6, Charsets.US_ASCII) in GIF_SIGNATURES -> "image/gif"
        size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "bin"
    }

    private data class CopiedAttachment(
        val file: File,
        val mimeType: String,
    )

    companion object {
        internal const val ROOT_DIRECTORY_NAME = "share_imports"
        const val STAGING_PREFIX = ".staging-"
        const val METADATA_FILE_NAME = "metadata.properties"
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val GIF_SIGNATURES = setOf("GIF87a", "GIF89a")
    }
}

private class AndroidShareImportContentSource(context: Context) : ShareImportContentSource {
    private val resolver = context.applicationContext.contentResolver

    override fun mimeType(uri: String): String? =
        runCatching { resolver.getType(Uri.parse(uri)) }.getOrNull()

    override fun open(uri: String): InputStream? =
        resolver.openInputStream(Uri.parse(uri))
}
