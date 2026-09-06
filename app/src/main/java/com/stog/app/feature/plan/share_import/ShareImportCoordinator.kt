package com.stog.app.feature.plan.share_import

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class ShareImportCoordinator {
    private val fileStore: ShareImportFileStore
    private val notifier: ShareImportNotifier
    private val intakeService: ShareImportIntakeService
    private val executeTask: (() -> Unit) -> Unit
    private val postToMain: (() -> Unit) -> Unit
    private val closeExecutor: () -> Unit

    constructor(context: Context) {
        val appContext = context.applicationContext
        val executor = Executors.newSingleThreadExecutor()
        val mainHandler = Handler(Looper.getMainLooper())
        fileStore = ShareImportFileStore(appContext)
        notifier = AndroidShareImportNotifier(appContext)
        intakeService = ShareImportIntakeService(fileStore, notifier)
        executeTask = { task -> executor.execute(task) }
        postToMain = { task -> mainHandler.post(task) }
        closeExecutor = executor::shutdownNow
    }

    internal constructor(
        fileStore: ShareImportFileStore,
        notifier: ShareImportNotifier,
        executeTask: (() -> Unit) -> Unit,
        postToMain: (() -> Unit) -> Unit,
    ) {
        this.fileStore = fileStore
        this.notifier = notifier
        intakeService = ShareImportIntakeService(fileStore, notifier)
        this.executeTask = executeTask
        this.postToMain = postToMain
        closeExecutor = {}
    }

    fun loadAll(): List<StoredShareImport> = fileStore.loadAll()

    fun load(id: String): StoredShareImport? = fileStore.load(id)

    fun postNotification(id: String): Boolean =
        notifier.post(ShareImportNotificationDestination(id))

    fun saveReview(
        id: String,
        decisions: Map<Int, ShareMentionDecision>,
        manualEntries: List<String>,
    ) {
        fileStore.saveReview(id, decisions, manualEntries)
    }

    fun start(
        intent: Intent,
        onState: (ShareImportUiState) -> Unit,
    ) {
        val payload = ShareImportReceiver.payloadFrom(intent)
        val normalized = ShareImportNormalizer.normalize(payload)
        onState(ShareImportUiState.Saving(normalized))
        executeTask {
            val state = when (val result = intakeService.intake(payload)) {
                is ShareImportIntakeResult.Accepted -> ShareImportUiState.Saved(
                    normalized = result.stored.normalized,
                    local = result.stored.local,
                    restored = result.stored,
                )
                is ShareImportIntakeResult.Rejected -> ShareImportUiState.Failed(
                    normalized = normalized,
                    message = result.message,
                )
            }
            postToMain { onState(state) }
        }
    }

    fun close() {
        closeExecutor()
    }
}
