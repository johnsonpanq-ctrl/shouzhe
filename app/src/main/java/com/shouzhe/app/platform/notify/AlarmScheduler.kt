package com.shouzhe.app.platform.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.shouzhe.app.data.db.ShouzheDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant

/**
 * 提醒排期 —— 本项目第二难点（见 ARCHITECTURE.md §7）。
 *
 * 关键设计：
 * - setExactAndAllowWhileIdle + RTC_WAKEUP：Doze 下也能唤醒
 * - 触发偏差观测：把「提醒不响」从静默失败变成可见问题
 * - 不允许出错：开机/更新/时区变化后全量重排
 */
@Singleton
class AlarmScheduler @Inject constructor(
    private val context: Context,
    private val db: ShouzheDatabase,
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 是否已获得精确闹钟权限（Android 12+ 需要） */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

    /** 引导用户去授予精确闹钟权限 */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        } else null

    /**
     * 排一个提醒。
     * @return 实际排期时间（用于偏差观测）
     */
    suspend fun schedule(itemId: Long, title: String, body: String?, at: Instant): Long =
        withContext(Dispatchers.IO) {
            val triggerAt = at.toEpochMilli()
            val pi = pendingIntent(itemId, title, body)

            // 无精确权限时降级：setAndAllowWhileIdle（可能被系统推迟几分钟）
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pi
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pi
                )
            }

            db.todoMetaDao().updateRemindState(itemId, "SCHEDULED")
            triggerAt
        }

    suspend fun cancel(itemId: Long) = withContext(Dispatchers.IO) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, itemId.toInt(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
        db.todoMetaDao().updateRemindState(itemId, "NONE")
    }

    /** 通知上的「稍后」：推迟 delayMs 后再响一次 */
    suspend fun snooze(itemId: Long, delayMs: Long) = withContext(Dispatchers.IO) {
        val newAt = System.currentTimeMillis() + delayMs
        db.todoMetaDao().snooze(itemId, newAt)
        val item = db.itemDao().findById(itemId) ?: return@withContext
        schedule(itemId, item.title, item.summary, Instant.ofEpochMilli(newAt))
    }

    /**
     * 全量重排 —— 开机 / 应用更新 / 时区变化后必须调用。
     * 把未来到点的全部重新丢给系统，已过期的标 MISSED。
     */
    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val pending = db.todoMetaDao().findPendingReminders()
        val itemDao = db.itemDao()

        pending.forEach { todo ->
            val at = todo.remindAt ?: return@forEach
            val item = itemDao.findById(todo.itemId) ?: return@forEach
            if (item.status == "DELETED" || item.status == "DONE") {
                db.todoMetaDao().updateRemindState(todo.itemId, "NONE")
                return@forEach
            }
            if (at <= now) {
                // 已过期：标记漏掉，不补发（避免开机后一片通知轰炸）
                db.todoMetaDao().updateRemindState(todo.itemId, "MISSED")
            } else {
                schedule(todo.itemId, item.title, item.summary, Instant.ofEpochMilli(at))
            }
        }
    }

    /**
     * 偏差观测：记录「计划时间」与「实际触发时间」的差。
     * 偏大说明后台被杀，App 内提示用户检查后台配置。
     */
    suspend fun recordDeviation(itemId: Long, plannedAt: Long) = withContext(Dispatchers.IO) {
        val actual = System.currentTimeMillis()
        val delta = actual - plannedAt
        if (delta > DEVIATION_THRESHOLD_MS) {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO model_call (provider, modelName, purpose, itemId, " +
                    "promptTokens, completionTokens, latencyMs, success, error, createdAt) " +
                    "VALUES (?, ?, ?, ?, 0, 0, ?, 1, NULL, ?)",
                arrayOf(
                    "system", "alarm", "deviation", itemId, delta, actual
                ),
            )
        }
    }

    private fun pendingIntent(itemId: Long, title: String, body: String?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_ITEM_ID, itemId)
            putExtra(AlarmReceiver.EXTRA_TITLE, title)
            putExtra(AlarmReceiver.EXTRA_BODY, body)
        }
        return PendingIntent.getBroadcast(
            context,
            itemId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** 偏差超过 60 秒认为后台受限 */
        const val DEVIATION_THRESHOLD_MS = 60_000L
    }
}
