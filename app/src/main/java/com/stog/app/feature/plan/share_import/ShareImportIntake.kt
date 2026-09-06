package com.stog.app.feature.plan.share_import

enum class ShareImportRejection {
    UNSUPPORTED_ACTION,
    UNSUPPORTED_MIME_TYPE,
    MIXED_CONTENT,
    MISSING_CONTENT,
    MALFORMED_INTENT,
    TOO_MANY_ATTACHMENTS,
    UNAVAILABLE_ATTACHMENT,
    UNSUPPORTED_ATTACHMENT,
    ATTACHMENT_TOO_LARGE,
    STORAGE_FAILURE,
}

sealed interface ShareImportIntakeResult {
    data class Accepted(
        val stored: StoredShareImport,
        val notificationDestination: ShareImportNotificationDestination,
        val notificationPosted: Boolean,
    ) : ShareImportIntakeResult

    data class Rejected(
        val reason: ShareImportRejection,
        val message: String,
    ) : ShareImportIntakeResult
}

class ShareImportIntakeService(
    private val fileStore: ShareImportFileStore,
    private val notifier: ShareImportNotifier,
) {
    fun intake(payload: ShareImportPayload): ShareImportIntakeResult {
        val normalized = ShareImportNormalizer.normalize(payload)
        validate(payload, normalized)?.let { return it }

        val stored = try {
            fileStore.save(payload, normalized)
        } catch (error: ShareImportStoreException) {
            return ShareImportIntakeResult.Rejected(error.rejection, error.message.orEmpty())
        }
        val destination = ShareImportNotificationDestination(stored.local.id)
        val notificationPosted = notifier.post(destination)
        return ShareImportIntakeResult.Accepted(stored, destination, notificationPosted)
    }

    private fun validate(
        payload: ShareImportPayload,
        normalized: NormalizedShareImport,
    ): ShareImportIntakeResult.Rejected? {
        if (payload.action != ShareImportActions.SEND &&
            payload.action != ShareImportActions.SEND_MULTIPLE
        ) {
            return rejected(ShareImportRejection.UNSUPPORTED_ACTION, "지원하지 않는 공유 action입니다.")
        }

        val mimeType = payload.mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        val isText = mimeType == MIME_TEXT_PLAIN || mimeType == MIME_TEXT_HTML
        val isImage = mimeType.startsWith(MIME_IMAGE_PREFIX) && mimeType.length > MIME_IMAGE_PREFIX.length
        if (!isText && !isImage) {
            return rejected(ShareImportRejection.UNSUPPORTED_MIME_TYPE, "지원하지 않는 공유 형식입니다.")
        }

        if (isText && normalized.attachmentUris.isNotEmpty()) {
            return rejected(ShareImportRejection.MIXED_CONTENT, "텍스트 형식에 첨부 파일이 섞여 있습니다.")
        }
        if (isText && normalized.title == null && normalized.originalUrl == null) {
            return rejected(ShareImportRejection.MISSING_CONTENT, "저장할 텍스트 또는 URL이 없습니다.")
        }
        if (isImage && normalized.attachmentUris.isEmpty()) {
            return rejected(ShareImportRejection.MISSING_CONTENT, "저장할 첨부 파일이 없습니다.")
        }
        if (payload.action == ShareImportActions.SEND && normalized.attachmentUris.size > 1) {
            return rejected(ShareImportRejection.MALFORMED_INTENT, "단일 공유에 여러 첨부 파일이 있습니다.")
        }
        return null
    }

    private fun rejected(
        reason: ShareImportRejection,
        message: String,
    ) = ShareImportIntakeResult.Rejected(reason, message)

    private companion object {
        const val MIME_TEXT_PLAIN = "text/plain"
        const val MIME_TEXT_HTML = "text/html"
        const val MIME_IMAGE_PREFIX = "image/"
    }
}
