package com.shouzhe.app.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shouzhe.app.ui.component.SzCard
import com.shouzhe.app.ui.theme.SzRadius
import com.shouzhe.app.ui.theme.SzSpacing
import com.shouzhe.app.ui.theme.szExtras

/**
 * 后台保活引导 —— 国产 ROM 专项。
 *
 * 这是架构解决不了的问题，只能引导用户手动配一次。
 * 参考项目在荣耀上验证过同样的坑；小米 HyperOS 菜单层级不同，单独写。
 */
@Composable
fun KeepAliveScreen(onBack: () -> Unit) {
    val e = szExtras()
    val ctx = LocalContext.current
    val isXiaomi = isXiaomiDevice()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SzSpacing.headerH.dp)
            .padding(bottom = 40.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←", fontSize = 18.sp, color = e.ink2,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
            )
            Text("后台运行", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = e.ink)
        }

        Text(
            "提醒要准时，需要下面 4 项都设好。",
            fontSize = 13.sp,
            color = e.ink2,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        val steps = if (isXiaomi) XIAOMI_STEPS else GENERIC_STEPS

        steps.forEachIndexed { index, step ->
            SzCard {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(e.brandSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = e.brand,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            step.title,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = e.ink,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            step.path,
                            fontSize = 12.sp,
                            color = e.ink2,
                            lineHeight = 19.sp,
                        )
                        if (step.target.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "目标：${step.target}",
                                fontSize = 12.sp,
                                color = e.brand,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                if (step.action != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "去设置 →",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = e.brand,
                        modifier = Modifier.clickable {
                            step.action.invoke(ctx)
                        },
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
        }

        Spacer(Modifier.height(10.dp))

        // 机型提示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SzRadius.input.dp))
                .background(if (isXiaomi) e.brandSoft else e.surfaceAlt)
                .padding(13.dp),
        ) {
            Column {
                Text(
                    if (isXiaomi) "检测到 ${Build.MANUFACTURER} ${Build.MODEL}（HyperOS）"
                    else "检测到 ${Build.MANUFACTURER} ${Build.MODEL}",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isXiaomi) e.brand else e.ink,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    if (isXiaomi)
                        "以上步骤按小米 HyperOS 写。菜单层级各版本可能有差异，跳转失败就按文字路径手动找。"
                    else
                        "本项目只在红米 K80（HyperOS）上验证过。其他机型请参考上面的思路，在「设置 → 应用管理」里找对应项。",
                    fontSize = 12.sp,
                    color = e.ink2,
                    lineHeight = 19.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SzRadius.input.dp))
                .background(e.surfaceAlt)
                .padding(13.dp),
        ) {
            Column {
                Text("怎么验证生效了", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = e.ink)
                Spacer(Modifier.height(5.dp))
                Text(
                    "随便记一条 5 分钟后提醒的事，锁屏等着。到点响了就是配好了；" +
                        "没响说明还有一项没设对。\n\n" +
                        "设置页会记录每次提醒的偏差 —— 偏差大就回来检查这一页。",
                    fontSize = 12.sp,
                    color = e.ink2,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

private data class Step(
    val title: String,
    val path: String,
    val target: String,
    val action: ((Context) -> Unit)? = null,
)

private val XIAOMI_STEPS = listOf(
    Step(
        title = "自启动",
        path = "设置 → 应用设置 → 应用管理 → 收这吧 → 自启动",
        target = "开启",
        action = { ctx -> openAppDetailSettings(ctx) },
    ),
    Step(
        title = "省电策略",
        path = "同一页面 → 省电策略",
        target = "无限制（不要选「智能限制后台运行」）",
        action = { ctx -> openAppDetailSettings(ctx) },
    ),
    Step(
        title = "通知设为重要",
        path = "同一页面 → 通知管理 → 允许通知 + 类别设为「重要」",
        target = "允许 + 重要",
        action = { ctx -> openNotificationSettings(ctx) },
    ),
    Step(
        title = "锁定最近任务",
        path = "打开最近任务列表 → 下拉「收这吧」的卡片 → 点锁形图标",
        target = "已锁定",
        action = null,
    ),
)

private val GENERIC_STEPS = listOf(
    Step(
        title = "允许自启动",
        path = "设置 → 应用管理 → 收这吧 → 自启动 / 后台运行",
        target = "允许",
        action = { ctx -> openAppDetailSettings(ctx) },
    ),
    Step(
        title = "取消省电限制",
        path = "设置 → 电池 → 应用耗电管理 → 收这吧",
        target = "允许后台运行",
        action = { ctx -> openBatterySettings(ctx) },
    ),
    Step(
        title = "允许通知",
        path = "设置 → 通知 → 收这吧",
        target = "允许",
        action = { ctx -> openNotificationSettings(ctx) },
    ),
    Step(
        title = "锁定最近任务",
        path = "最近任务列表 → 下拉本应用卡片 → 点锁形图标",
        target = "已锁定",
        action = null,
    ),
)

private fun isXiaomiDevice(): Boolean {
    val m = Build.MANUFACTURER.lowercase()
    val b = Build.BRAND.lowercase()
    return m.contains("xiaomi") || m.contains("redmi") || b.contains("redmi") || b.contains("poco")
}

// --------------------------------------------------------------------------
// 跳转：能跳就跳，跳不了就什么都不做（用户按文字路径自己找）
// --------------------------------------------------------------------------

private fun openAppDetailSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", ctx.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    safeStart(ctx, intent)
}

private fun openNotificationSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    safeStart(ctx, intent)
}

private fun openBatterySettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    safeStart(ctx, intent)
}

/** 逐个尝试厂商私有入口；全部失败则打开系统设置首页 */
private fun safeStart(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent) }
        .onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}