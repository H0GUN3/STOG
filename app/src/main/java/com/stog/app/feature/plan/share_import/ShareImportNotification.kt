package com.stog.app.feature.plan.share_import

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.stog.app.MainActivity
import com.stog.app.R

object ShareImportNavigation {
    const val REVIEW_ACTION = "com.stog.app.action.REVIEW_SHARE_IMPORT"
    const val IMPORT_ID_EXTRA = "com.stog.app.extra.SHARE_IMPORT_ID"
}

data class ShareImportNotificationDestination(
    val importId: String,
    val action: String = ShareImportNavigation.REVIEW_ACTION,
    val importIdExtra: String = ShareImportNavigation.IMPORT_ID_EXTRA,
)

interface ShareImportNotifier {
    fun post(destination: ShareImportNotificationDestination): Boolean
}

class AndroidShareImportNotifier(context: Context) : ShareImportNotifier {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    override fun post(destination: ShareImportNotificationDestination): Boolean {
        createChannel()
        val reviewIntent = Intent(appContext, MainActivity::class.java).apply {
            action = destination.action
            putExtra(destination.importIdExtra, destination.importId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            destination.importId.hashCode(),
            reviewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.stog_app_icon)
            .setContentTitle("공유 담기 저장 완료")
            .setContentText("장소를 확인해 일정 바구니에 담아보세요.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        return try {
            notificationManager.notify(destination.importId.hashCode(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "공유 담기",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "share_import_review"
    }
}

fun Intent.shareImportReviewId(): String? =
    takeIf { action == ShareImportNavigation.REVIEW_ACTION }
        ?.getStringExtra(ShareImportNavigation.IMPORT_ID_EXTRA)
        ?.takeIf(String::isNotBlank)
