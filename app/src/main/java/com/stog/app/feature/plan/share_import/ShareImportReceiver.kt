package com.stog.app.feature.plan.share_import

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

object ShareImportActions {
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
}

object ShareImportReceiver {
    fun payloadFrom(intent: Intent): ShareImportPayload =
        ShareImportPayload(
            action = intent.action,
            mimeType = intent.type,
            texts = listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()) +
                intent.clipDataTexts(),
            htmlText = intent.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT)?.toString(),
            dataUri = intent.data?.toString(),
            streamUris = intent.streamUris(),
            clipDataUris = intent.clipDataUris(),
            claimedSourcePackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                ?: intent.getStringExtra(Intent.EXTRA_REFERRER_NAME),
        )

    private fun Intent.streamUris(): List<String> =
        if (action == ShareImportActions.SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
                .orEmpty()
                .map(Uri::toString)
        } else {
            listOfNotNull(
                IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.toString(),
            )
        }

    private fun Intent.clipDataUris(): List<String> =
        clipData?.let { data ->
            (0 until data.itemCount)
                .mapNotNull { data.getItemAt(it).uri }
                .map(Uri::toString)
        }.orEmpty()

    private fun Intent.clipDataTexts(): List<String> =
        clipData?.let { data ->
            (0 until data.itemCount)
                .mapNotNull { data.getItemAt(it).text?.toString() }
        }.orEmpty()

    private const val EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME"
}
