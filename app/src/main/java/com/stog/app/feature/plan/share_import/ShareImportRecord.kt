package com.stog.app.feature.plan.share_import

enum class ShareImportStatus {
    SAVED,
    NEEDS_REVIEW,
    COMPLETED,
    FAILED,
}

enum class ShareMentionDecision {
    CONFIRMED,
    REJECTED,
    DELETED,
    MANUAL,
}

data class StoredShareImport(
    val local: LocalShareImport,
    val normalized: NormalizedShareImport,
    val status: ShareImportStatus,
    val decisions: Map<Int, ShareMentionDecision>,
    val manualEntries: List<String>,
)
