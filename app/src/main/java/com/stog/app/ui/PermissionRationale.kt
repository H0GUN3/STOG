package com.stog.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.app.ActivityCompat

internal enum class PermissionRequestDecision {
    ALREADY_GRANTED,
    SHOW_RATIONALE,
    OPEN_SETTINGS,
}

internal fun decidePermissionRequest(
    granted: Boolean,
    shouldShowRationale: Boolean,
    requestAttempted: Boolean,
): PermissionRequestDecision = when {
    granted -> PermissionRequestDecision.ALREADY_GRANTED
    shouldShowRationale || !requestAttempted -> PermissionRequestDecision.SHOW_RATIONALE
    else -> PermissionRequestDecision.OPEN_SETTINGS
}

internal fun Context.shouldShowStogPermissionRationale(permission: String): Boolean {
    var current: Context = this
    while (true) {
        if (current is Activity) {
            return ActivityCompat.shouldShowRequestPermissionRationale(current, permission)
        }
        val next = (current as? ContextWrapper)?.baseContext ?: return false
        if (next === current) return false
        current = next
    }
}

internal fun Context.openStogPermissionSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal enum class StogPermissionPromptKind {
    RATIONALE,
    SETTINGS,
}

internal data class StogPermissionPrompt(
    val kind: StogPermissionPromptKind,
    val title: String,
    val detail: String,
)

@Composable
internal fun StogPermissionDialog(
    prompt: StogPermissionPrompt,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = { Text(prompt.detail) },
        confirmButton = {
            TextButton(onClick = onPrimary) {
                Text(if (prompt.kind == StogPermissionPromptKind.SETTINGS) "앱 설정 열기" else "계속")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("나중에") }
        },
    )
}
