package com.shouzhe.app.platform.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shouzhe.app.MainActivity
import com.shouzhe.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知渠道与发送 —— 所有提醒走这里。
 */
object Notifications {

    const val CHANNEL_ID = "reminder"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_reminder_name),
            NotificationManager.IMPORTANCE_HIGH,   // 高重要性：横幅 + 声音
        ).apply {
            description = context.getString(R.string.channel_reminder_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        mgr.createNotificationChannel(channel)
    }

    fun showReminder(context: Context, itemId: Long, title: String, body: String?) {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            itemId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("itemId", itemId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val done = PendingIntent.getBroadcast(
            context,
            (itemId + 10_000).toInt(),
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_DONE
                putExtra(NotificationActionReceiver.EXTRA_ITEM_ID, itemId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snooze = PendingIntent.getBroadcast(
            context,
            (itemId + 20_000).toInt(),
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_SNOOZE
                putExtra(NotificationActionReceiver.EXTRA_ITEM_ID, itemId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.action_done), done)
            .addAction(0, context.getString(R.string.action_snooze), snooze)

        runCatching {
            NotificationManagerCompat.from(context).notify(itemId.toInt(), builder.build())
        }
    }
}

/** 提醒到点广播 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        if (id < 0) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "提醒"
        val body = intent.getStringExtra(EXTRA_BODY)
        Notifications.showReminder(context, id, title, body)
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }
}

/** 通知按钮动作 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        if (id < 0) return
        // 真实状态回写由 ReminderScheduler 完成（需要数据库访问）
        when (intent.action) {
            ACTION_DONE -> ReminderDispatcher.markDone(context, id)
            ACTION_SNOOZE -> ReminderDispatcher.snooze(context, id, 10 * 60_000L)
        }
        NotificationManagerCompat.from(context).cancel(id.toInt())
    }

    companion object {
        const val ACTION_DONE = "com.shouzhe.app.action.DONE"
        const val ACTION_SNOOZE = "com.shouzhe.app.action.SNOOZE"
        const val EXTRA_ITEM_ID = "item_id"
    }
}

/**
 * 开机 / 更新 / 时区变化 → 全量重排所有未完成提醒。
 * 这是「排期不许出错」的保险丝。
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> ReminderDispatcher.rescheduleAll(context)
        }
    }
}

/**
 * 提醒动作的桥接层 —— 让 BroadcastReceiver 能触发需要数据库的业务逻辑。
 * 由 Application 在启动时注入实现。
 */
object ReminderDispatcher {
    var onMarkDone: (suspend (Long) -> Unit)? = null
    var onSnooze: (suspend (Long, Long) -> Unit)? = null
    var onRescheduleAll: (suspend () -> Unit)? = null

    fun markDone(context: Context, itemId: Long) {
        val handler = onMarkDone ?: return
        AppScope.launch { handler(itemId) }
    }

    fun snooze(context: Context, itemId: Long, delayMs: Long) {
        val handler = onSnooze ?: return
        AppScope.launch { handler(itemId, delayMs) }
    }

    fun rescheduleAll(context: Context) {
        val handler = onRescheduleAll ?: return
        AppScope.launch { handler() }
    }
}

/** 轻量应用级协程作用域 —— 供广播接收器使用 */
object AppScope {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }
}