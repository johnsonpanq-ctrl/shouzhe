package com.shouzhe.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shouzhe.app.ui.theme.SzRadius
import com.shouzhe.app.ui.theme.SzSpacing
import com.shouzhe.app.ui.theme.szExtras

/**
 * 卡片容器 —— 浅色带投影，深色带描边（UI-SPEC §4.1）
 */
@Composable
fun SzCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val e = szExtras()
    val shape = RoundedCornerShape(SzRadius.card.dp)
    val base = modifier
        .fillMaxWidth()
        .then(
            if (e.isDark) {
                Modifier.clip(shape)
                    .background(e.surfaceAlt.copy(alpha = 0f))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
            } else {
                Modifier.shadow(
                    elevation = 2.dp,
                    shape = shape,
                    ambientColor = Color(0x1417202A),
                    spotColor = Color(0x1F17202A),
                ).clip(shape).background(Color.White)
            }
        )
    val clickable = if (onClick != null) base.clickable { onClick() } else base

    Column(
        modifier = clickable.padding(SzSpacing.cardPad.dp),
        content = content,
    )
}

/** 胶囊页签 */
@Composable
fun SzTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val e = szExtras()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(SzRadius.pill.dp))
            .background(if (selected) e.brand else e.surfaceDeep)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .heightIn(min = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) e.onBrand else e.ink2,
        )
    }
}

/** 品牌色主按钮 */
@Composable
fun SzPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val e = szExtras()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(SzRadius.button.dp))
            .background(e.brand)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = e.onBrand,
        )
    }
}

/** 次按钮：浅灰底 */
@Composable
fun SzGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val e = szExtras()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(SzRadius.button.dp))
            .background(e.surfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 12.5.sp, color = e.ink2)
    }
}

/** 小标签 */
@Composable
fun SzTag(text: String) {
    val e = szExtras()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(SzRadius.chip.dp))
            .background(e.surfaceAlt)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, fontSize = 10.5.sp, color = e.ink2)
    }
}

/** 金额（等宽，品牌色） */
@Composable
fun SzAmount(cents: Long, highlight: Boolean = true) {
    val e = szExtras()
    Text(
        text = "¥%.2f".format(cents / 100.0),
        fontFamily = FontFamily.Monospace,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = if (highlight) e.brand else e.ink,
    )
}

/** 时间戳（等宽，弱色） */
@Composable
fun SzTimestamp(text: String) {
    val e = szExtras()
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = e.ink3,
    )
}