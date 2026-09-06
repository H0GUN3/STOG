package com.stog.app.feature.plan.share_import

sealed interface ShareImportUiState {
    data object Idle : ShareImportUiState

    data class Saving(
        val normalized: NormalizedShareImport,
    ) : ShareImportUiState

    data class Saved(
        val normalized: NormalizedShareImport,
        val local: LocalShareImport,
        val restored: StoredShareImport? = null,
    ) : ShareImportUiState

    data class Failed(
        val normalized: NormalizedShareImport,
        val message: String,
    ) : ShareImportUiState
}
