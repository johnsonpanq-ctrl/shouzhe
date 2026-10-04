package com.shouzhe.app

import android.app.Application
import com.shouzhe.app.data.repository.ItemRepository
import com.shouzhe.app.platform.notify.AppScope
import com.shouzhe.app.platform.notify.Notifications
import com.shouzhe.app.platform.notify.ReminderDispatcher
import com.shouzhe.app.platform.notify.AlarmScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ShouzheApp : Application() {

    @Inject lateinit var repository: ItemRepository
    @Inject lateinit var scheduler: AlarmScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Notifications.ensureChannel(this)

        // 把需要数据库的业务逻辑桥接给广播接收器
        ReminderDispatcher.onMarkDone = { itemId ->
            repository.markDone(itemId)
            scheduler.cancel(itemId)
        }
        ReminderDispatcher.onSnooze = { itemId, delayMs ->
            scheduler.snooze(itemId, delayMs)
        }
        ReminderDispatcher.onRescheduleAll = {
            scheduler.rescheduleAll()
        }

        // 启动时恢复被中断的抽取任务 + 重排提醒
        scope.launch {
            runCatching {
                repository.recoverInterruptedJobs()
                scheduler.rescheduleAll()
            }
        }
    }
}