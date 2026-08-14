package com.fileforge.optimizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Collections

enum class ChannelImportance { LOW }

class ChannelSpec(
    val id: String,
    val name: String,
    val importance: ChannelImportance,
    val hasSound: Boolean
)

class CancelActionSpec(
    val title: String,
    val serviceAction: String,
    val isImmutable: Boolean,
    val updateCurrent: Boolean
)

class NotificationSpec(
    val title: String,
    val text: String,
    val channelId: String,
    val notificationId: Int,
    val isIndeterminate: Boolean,
    val progressMax: Int?,
    val progressCurrent: Int?,
    actions: List<CancelActionSpec>
) {
    val actions: List<CancelActionSpec> =
        Collections.unmodifiableList(ArrayList(actions))
}

object OptimizationServiceActions {
    const val ACTION_CANCEL = "com.fileforge.optimizer.action.CANCEL"
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

class NotificationUpdateThrottle(
    private val clock: MonotonicClock,
    private val minimumRunningIntervalMillis: Long = 250L
) {
    private var deliveredRunning = false
    private var lastRunningDeliveryMillis = 0L

    @Synchronized
    fun shouldDeliver(state: RunState): Boolean {
        if (state !is RunState.Running) return true
        val now = clock.nowMillis()
        if (!deliveredRunning) {
            deliveredRunning = true
            lastRunningDeliveryMillis = now
            return true
        }
        if (now < lastRunningDeliveryMillis) {
            lastRunningDeliveryMillis = now
            return true
        }
        if (now - lastRunningDeliveryMillis < minimumRunningIntervalMillis) return false
        lastRunningDeliveryMillis = now
        return true
    }
}

object OptimizationNotification {
    const val CHANNEL_ID = "fileforge_optimization"
    const val CHANNEL_NAME = "Optimization progress"
    const val NOTIFICATION_ID = 1001

    val channelSpec = ChannelSpec(
        id = CHANNEL_ID,
        name = CHANNEL_NAME,
        importance = ChannelImportance.LOW,
        hasSound = false
    )

    private val cancelAction = CancelActionSpec(
        title = "Cancel",
        serviceAction = OptimizationServiceActions.ACTION_CANCEL,
        isImmutable = true,
        updateCurrent = true
    )

    fun render(state: RunState): NotificationSpec = when (state) {
        RunState.Idle -> NotificationSpec(
            title = "FileForge Optimizer",
            text = "Ready",
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            isIndeterminate = false,
            progressMax = null,
            progressCurrent = null,
            actions = emptyList()
        )
        is RunState.Running -> renderRunning(state)
        is RunState.Terminal -> renderTerminal(state)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            channelSpec.id,
            channelSpec.name,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildAndroidNotification(context: Context, state: RunState): Notification {
        val spec = render(state)
        val builder = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(state is RunState.Running)

        when {
            spec.isIndeterminate -> builder.setProgress(0, 0, true)
            spec.progressMax != null && spec.progressCurrent != null ->
                builder.setProgress(spec.progressMax, spec.progressCurrent, false)
            else -> builder.setProgress(0, 0, false)
        }
        spec.actions.forEach { action ->
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    action.title,
                    cancelPendingIntent(context, action)
                ).build()
            )
        }
        return builder.build()
    }

    private fun renderRunning(state: RunState.Running): NotificationSpec {
        val snapshot = state.snapshot
        val total = snapshot.totalWork?.takeIf { it > 0 }
        val text = if (state.dryRun) {
            "${snapshot.filesProcessed} files analyzed • " +
                "${formatBytes(snapshot.potentialSavingsBytes)} potential savings"
        } else {
            "${snapshot.filesProcessed} files processed • ${formatBytes(snapshot.savedBytes)} saved"
        }
        return NotificationSpec(
            title = if (state.dryRun) "Analyzing files" else "Optimizing files",
            text = text,
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            isIndeterminate = total == null,
            progressMax = total,
            progressCurrent = total?.let { snapshot.filesProcessed.coerceIn(0, it) },
            actions = listOf(cancelAction)
        )
    }

    private fun renderTerminal(state: RunState.Terminal): NotificationSpec {
        val report = state.report
        val title = when (report.status) {
            RunStatus.COMPLETED -> "Optimization complete"
            RunStatus.COMPLETED_WITH_ERRORS -> "Completed with errors"
            RunStatus.CANCELLED -> "Optimization cancelled"
            RunStatus.FAILED -> "Optimization failed"
            RunStatus.RUNNING -> error("Terminal state cannot contain a running report")
        }
        val text = if (state.dryRun) {
            "${report.scanned} files analyzed • " +
                "${formatBytes(report.potentialSavingsBytes)} potential savings"
        } else {
            "${report.scanned} files scanned • ${formatBytes(report.savedBytes)} saved"
        }
        return NotificationSpec(
            title = title,
            text = text,
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            isIndeterminate = false,
            progressMax = null,
            progressCurrent = null,
            actions = emptyList()
        )
    }

    private fun cancelPendingIntent(context: Context, action: CancelActionSpec): PendingIntent {
        val serviceComponent = ComponentName(
            context.packageName,
            "${context.packageName}.OptimizationService"
        )
        val intent = Intent(action.serviceAction).setComponent(serviceComponent)
        var flags = 0
        if (action.isImmutable) flags = flags or PendingIntent.FLAG_IMMUTABLE
        if (action.updateCurrent) flags = flags or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(context, CANCEL_REQUEST_CODE, intent, flags)
    }

    private fun formatBytes(bytes: Long): String = "$bytes B"

    private const val CANCEL_REQUEST_CODE = 1002
}
