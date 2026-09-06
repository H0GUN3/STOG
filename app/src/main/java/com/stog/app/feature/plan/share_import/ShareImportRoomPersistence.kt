package com.stog.app.feature.plan.share_import

import android.content.Context
import com.stog.app.core.database.CandidateOrigin
import com.stog.app.core.database.PendingShareImportEntity
import com.stog.app.core.database.PendingShareImportStatus
import com.stog.app.core.database.ShareImportCandidateEntity
import com.stog.app.core.database.ShareImportDao
import com.stog.app.core.database.StogDatabase
import java.io.File
import java.io.InputStream
import java.util.Properties

internal interface ShareImportPersistence {
    fun persistRaw(importId: String, payload: ShareImportPayload, normalized: NormalizedShareImport)
    fun persistCandidates(importId: String, normalized: NormalizedShareImport)
}

internal class RoomShareImportPersistence(
    private val dao: ShareImportDao,
    private val now: () -> Long = System::currentTimeMillis,
) : ShareImportPersistence {
    override fun persistRaw(importId: String, payload: ShareImportPayload, normalized: NormalizedShareImport) {
        dao.insertPending(
            PendingShareImportEntity(
                importId = importId,
                accountId = null,
                rawDirectoryName = importId,
                rawText = payload.texts.joinToString("\n").ifBlank { null },
                rawHtml = payload.htmlText,
                mimeType = payload.mimeType,
                source = normalized.source.name,
                createdAt = now(),
            ),
        )
    }

    override fun persistCandidates(importId: String, normalized: NormalizedShareImport) {
        dao.persistCandidates(importId, candidates(importId, normalized))
    }

    companion object {
        internal fun candidates(importId: String, normalized: NormalizedShareImport): List<ShareImportCandidateEntity> =
            normalized.mentions.mapIndexed { index, mention ->
                ShareImportCandidateEntity(
                    candidateId = "$importId:$index",
                    importId = importId,
                    origin = CandidateOrigin.EXTRACTED,
                    title = mention.title,
                    address = mention.address,
                    originalUrl = normalized.originalUrl,
                    candidateOrder = index,
                )
            }
    }
}

internal class ShareImportRecovery(
    private val rawRoot: File,
    private val dao: ShareImportDao,
) {
    fun recover(): List<String> {
        val failed = linkedSetOf<String>()
        val stagingNames = rawRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) }
            .map(File::getName)
            .toSet()
        rawRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name in stagingNames }
            .forEach(File::deleteRecursively)

        dao.allPending()
            .filter { it.status == PendingShareImportStatus.COMPLETED }
            .forEach { completed ->
                val directory = File(rawRoot, completed.rawDirectoryName)
                if ((!directory.exists() || directory.deleteRecursively()) &&
                    dao.deleteCompleted(completed.importId) != 1
                ) {
                    error("Completed share import cleanup lost its durable acknowledgement")
                }
            }

        val parser = ShareImportFileStore(rawRoot, RecoveryContentSource)
        rawRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(STAGING_PREFIX) }
            .sortedBy(File::getName)
            .forEach { directory ->
                val importId = directory.name
                val pending = dao.pending(importId)
                val hasPartial = directory.listFiles().orEmpty().any { it.name.endsWith(PARTIAL_SUFFIX) }
                val stored = if (hasPartial) null else parser.load(importId)
                if (stored == null) {
                    failTyped(importId, directory, pending, failed)
                    return@forEach
                }

                val current = pending ?: insertRecoveredRaw(directory, stored)
                if (current.status == PendingShareImportStatus.SAVED) {
                    val existingCandidates = dao.candidates(importId)
                    if (existingCandidates.isNotEmpty()) {
                        dao.markFailed(importId)
                        failed += importId
                    } else {
                        try {
                            dao.persistCandidates(
                                importId,
                                RoomShareImportPersistence.candidates(importId, stored.normalized),
                            )
                        } catch (error: android.database.sqlite.SQLiteException) {
                            dao.markFailed(importId)
                            failed += importId
                        }
                    }
                }
            }

        dao.allPending().forEach { pending ->
            val directory = File(rawRoot, pending.rawDirectoryName)
            if (!directory.isDirectory || pending.rawDirectoryName in stagingNames) {
                dao.markFailed(pending.importId)
                failed += pending.importId
            }
        }
        return failed.toList()
    }

    private fun insertRecoveredRaw(directory: File, stored: StoredShareImport): PendingShareImportEntity {
        val properties = Properties().also { values ->
            File(directory, METADATA_FILE_NAME).inputStream().use(values::load)
        }
        val entity = PendingShareImportEntity(
            importId = directory.name,
            accountId = null,
            rawDirectoryName = directory.name,
            rawText = properties.getProperty("raw_text")?.takeIf(String::isNotBlank),
            rawHtml = properties.getProperty("raw_html")?.takeIf(String::isNotBlank),
            mimeType = properties.getProperty("mime_type")?.takeIf(String::isNotBlank),
            source = stored.normalized.source.name,
            status = PendingShareImportStatus.SAVED,
            createdAt = directory.lastModified(),
        )
        dao.insertPending(entity)
        return entity
    }

    private fun failTyped(
        importId: String,
        directory: File,
        pending: PendingShareImportEntity?,
        failed: MutableSet<String>,
    ) {
        if (pending == null) {
            dao.insertPending(
                PendingShareImportEntity(
                    importId = importId,
                    accountId = null,
                    rawDirectoryName = importId,
                    rawText = null,
                    rawHtml = null,
                    mimeType = null,
                    source = ShareSource.UNKNOWN.name,
                    status = PendingShareImportStatus.FAILED,
                    createdAt = directory.lastModified(),
                ),
            )
        } else {
            dao.markFailed(importId)
        }
        failed += importId
    }

    companion object {
        private const val STAGING_PREFIX = ".staging-"
        private const val PARTIAL_SUFFIX = ".partial"
        private const val METADATA_FILE_NAME = "metadata.properties"

        fun forContext(context: Context): ShareImportRecovery = ShareImportRecovery(
            File(context.noBackupFilesDir, ShareImportFileStore.ROOT_DIRECTORY_NAME),
            StogDatabase.get(context).shareImportDao(),
        )
    }
}

private object RecoveryContentSource : ShareImportContentSource {
    override fun mimeType(uri: String): String? = null
    override fun open(uri: String): InputStream? = null
}
