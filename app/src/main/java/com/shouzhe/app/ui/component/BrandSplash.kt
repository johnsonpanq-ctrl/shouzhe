package com.shouzhe.app.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shouzhe.app.R
import com.shouzhe.app.ui.theme.SzColors
import com.shouzhe.app.ui.theme.szExtras
import kotlinx.coroutines.delay

/**
 * 品牌启动页 —— 用户要求：固定显示 5 秒，点一下跳过。
 *
 * 为什么不用系统启动页做这件事：
 * 系统启动页（SplashScreen API）是系统窗口，App 内的点击事件到不了那里，
 * 无法实现"点击跳过"。所以系统层只负责"数据就绪即收起"（防闪烁），
 * 品牌层在这里用 Compose 实现，完全可控。
 *
 * 展示内容：品牌绿符号 + 「收这吧」+ 倒计时提示，点击任意处立即进入。
 *
 * @param onFinished 5 秒到（或用户点击）后回调
 */
@Composable
fun BrandSplash(
    onFinished: () -> Unit,
) {
    val e = szExtras()
    var secondsLeft by remember { mutableIntStateOf(5) }

    // 倒计时：每秒 -1，到 0 结束
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            // 点击任意位置立即跳过；ralph 让点击不产生涟漪
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onFinished() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 品牌符号：浅色用品牌绿，深色用荧光绿 —— 深浅两版资源已备好
            Image(
                painter = painterResource(id = R.drawable.ic_splash_logo_green),
                contentDescription = null,
                modifier = Modifier.size(120.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "收这吧",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = e.ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "什么都往里丢，它替你收着",
                fontSize = 13.sp,
                color = e.ink2,
            )
        }

        // 底部跳过提示
        Text(
            "点击任意位置进入（${secondsLeft}s）",
            fontSize = 11.sp,
            color = e.ink3,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
        )
    }
}