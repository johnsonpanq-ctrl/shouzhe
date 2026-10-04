package com.shouzhe.app.feature.detail

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shouzhe.app.core.time.TimeParser
import com.shouzhe.app.domain.model.*
import com.shouzhe.app.ui.component.SzCard
import com.shouzhe.app.ui.component.SzGhostButton
import com.shouzhe.app.ui.component.SzPrimaryButton
import com.shouzhe.app.ui.component.SzTag
import com.shouzhe.app.ui.theme.SzRadius
import com.shouzhe.app.ui.theme.SzSpacing
import com.shouzhe.app.ui.theme.szExtras
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 详情页 —— 四种类型各自的呈现与操作。
 *
 * 设计要点（UI-SPEC §3.3）：
 * - 摘要在上、正文在下：先看提纯结果，需要细节再往下读
 * - 长文阅读行高 1.85，两端对齐
 * - 抓取失败必须给「重新抓取」的出口，不能是死路
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    onSaveTodo: () -> Unit,
    onMarkDone: () -> Unit,
    onSnooze: (Long) -> Unit,
    onConfirmLedger: () -> Unit,
    onDeleteLedger: () -> Unit,
    // 账目编辑（v0.8.0）
    onStartEditLedger: () -> Unit,
    onCancelEditLedger: () -> Unit,
    onLedgerAmountChange: (String) -> Unit,
    onLedgerDirectionChange: (LedgerDirection) -> Unit,
    onLedgerCategoryChange: (String) -> Unit,
    onLedgerMerchantChange: (String) -> Unit,
    onSaveLedger: () -> Unit,
    onRetryExtract: () -> Unit,
    onSummarize: () -> Unit,
    onDelete: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val e = szExtras()
    val ctx = LocalContext.current
    val item = state.item

    // 打开原文：ViewModel 通过 message 传出特殊前缀
    LaunchedEffect(state.message) {
        val msg = state.message
        if (msg != null && msg.startsWith("__OPEN_URL__")) {
            val url = msg.removePrefix("__OPEN_URL__")
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            onDismissMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SzSpacing.headerH.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←", fontSize = 18.sp, color = e.ink2,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            if (item != null) {
                Text(
                    "删除", fontSize = 12.5.sp, color = e.ink3,
                    modifier = Modifier
                        .clickable { onDelete() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        when {
            state.loading -> LoadingBox()
            item == null -> NotFoundBox(onBack)
            else -> when (item.type) {
                ItemType.TODO -> TodoDetail(
                    state, item, onStartEdit, onCancelEdit, onTitleChange, onTimeChange,
                    onSaveTodo, onMarkDone, onSnooze,
                )
                ItemType.LEDGER -> LedgerDetail(
                    state, item, onConfirmLedger, onDeleteLedger,
                    onStartEdit = onStartEditLedger,
                    onCancelEdit = onCancelEditLedger,
                    onAmountChange = onLedgerAmountChange,
                    onDirectionChange = onLedgerDirectionChange,
                    onCategoryChange = onLedgerCategoryChange,
                    onMerchantChange = onLedgerMerchantChange,
                    onSave = onSaveLedger,
                )
                ItemType.ARTICLE -> ArticleDetail(state, item, onRetryExtract, onSummarize)
                ItemType.NOTE -> NoteDetail(item, state.sourceImage)
            }
        }
    }
}

// ===========================================================================
// 待办
// ===========================================================================

@Composable
private fun TodoDetail(
    state: DetailUiState,
    item: Item,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    onSaveTodo: () -> Unit,
    onMarkDone: () -> Unit,
    onSnooze: (Long) -> Unit,
) {
    val e = szExtras()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SzSpacing.headerH.dp)
            .padding(bottom = 40.dp),
    ) {
        if (state.editing) {
            // ---------- 编辑态 ----------
            SzCard {
                Text("标题", fontSize = 11.5.sp, color = e.ink3)
                Spacer(Modifier.height(6.dp))
                EditField(
                    value = state.titleDraft,
                    onValueChange = onTitleChange,
                    placeholder = "要做什么",
                    singleLine = true,
                )

                Spacer(Modifier.height(14.dp))
                Text("提醒时间", fontSize = 11.5.sp, color = e.ink3)
                Spacer(Modifier.height(6.dp))
                EditField(
                    value = state.timeDraft,
                    onValueChange = onTimeChange,
                    placeholder = "明天下午三点 / 2026-10-05 15:00",
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "留空表示不提醒",
                    fontSize = 11.sp,
                    color = e.ink3,
                )
                state.timeDraftError?.let {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(SzRadius.input.dp))
                            .background(Color(0xFFFBE9E7))
                            .padding(11.dp)
                    ) {
                        Text(it, fontSize = 12.sp, color = Color(0xFFB3261E), lineHeight = 18.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row {
                    SzPrimaryButton(text = "保存", onClick = onSaveTodo)
                    Spacer(Modifier.width(10.dp))
                    SzGhostButton(text = "取消", onClick = onCancelEdit)
                }
            }
        } else {
            // ---------- 阅读态 ----------
            Text(
                item.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = if (item.status == ItemStatus.DONE) e.ink3 else e.ink,
                lineHeight = 32.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "创建于 " + fmt(item.createdAt),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = e.ink3,
                )
                if (item.status == ItemStatus.DONE) {
                    Spacer(Modifier.width(10.dp))
                    SzTag("已完成")
                }
            }

            Spacer(Modifier.height(18.dp))

            item.todo?.let { todo ->
                SzCard {
                    DetailRow(
                        "提醒时间",
                        todo.remindAt?.let { fmt(it) } ?: "未设置",
                    )
                    if (todo.snoozeCount > 0) {
                        Spacer(Modifier.height(10.dp))
                        DetailRow("推迟次数", "${todo.snoozeCount} 次")
                    }
                    todo.remindAt?.let { at ->
                        Spacer(Modifier.height(10.dp))
                        val left = java.time.Duration.between(Instant.now(), at).toMinutes()
                        DetailRow(
                            "距离现在",
                            when {
                                left < 0 -> "已过期"
                                left < 60 -> "$left 分钟"
                                left < 1440 -> "${left / 60} 小时"
                                else -> "${left / 1440} 天"
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            if (item.status != ItemStatus.DONE) {
                Row {
                    SzPrimaryButton(text = "完成了", onClick = onMarkDone)
                    Spacer(Modifier.width(10.dp))
                    SzGhostButton(text = "改时间", onClick = onStartEdit)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SzGhostButton(text = "+10 分钟", onClick = { onSnooze(10) })
                    SzGhostButton(text = "+1 小时", onClick = { onSnooze(60) })
                }
            } else {
                SzGhostButton(text = "改时间", onClick = onStartEdit)
            }
        }
    }
}

// ===========================================================================
// 账目
// ===========================================================================

@Composable
private fun LedgerDetail(
    state: DetailUiState,
    item: Item,
    onConfirm: () -> Unit,
    onDeleteLedger: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAmountChange: (String) -> Unit,
    onDirectionChange: (LedgerDirection) -> Unit,
    onCategoryChange: (String) -> Unit,
    onMerchantChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val e = szExtras()
    val ledger = item.ledger
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SzSpacing.headerH.dp)
            .padding(bottom = 40.dp),
    ) {
        if (state.editingLedger && ledger != null) {
            // ---------- 编辑态（v0.8.0） ----------
            LedgerEditForm(
                state = state,
                onAmountChange = onAmountChange,
                onDirectionChange = onDirectionChange,
                onCategoryChange = onCategoryChange,
                onMerchantChange = onMerchantChange,
                onSave = onSave,
                onCancel = onCancelEdit,
            )
            return@Column
        }

        Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = e.ink)
        Spacer(Modifier.height(16.dp))

        ledger?.let { l ->
            // 金额大字
            Text(
                text = (if (l.direction == LedgerDirection.OUT) "-" else "+") +
                    "¥%.2f".format(l.amountCents / 100.0),
                fontFamily = FontFamily.Monospace,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = if (l.confirmed) e.ink else e.brand,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (l.confirmed) "已计入账本" else "待确认 —— 确认后才计入账本",
                fontSize = 12.5.sp,
                color = if (l.confirmed) e.ink3 else e.brand,
            )

            Spacer(Modifier.height(20.dp))

            SzCard {
                DetailRow("分类", l.category)
                Spacer(Modifier.height(10.dp))
                DetailRow("方向", if (l.direction == LedgerDirection.OUT) "支出" else "收入")
                l.merchant?.let {
                    Spacer(Modifier.height(10.dp))
                    DetailRow("商家", it)
                }
                Spacer(Modifier.height(10.dp))
                DetailRow("发生时间", fmt(l.occurredAt))
            }

            Spacer(Modifier.height(18.dp))

            // 截图记账的原始截图（v0.7.0）—— 让人能拿原图核对 AI 读出的金额
            state.sourceImage?.let {
                SourceImageBlock(it)
                Spacer(Modifier.height(18.dp))
            }

            if (!l.confirmed) {
                SzPrimaryButton(text = "确认入账", onClick = onConfirm)
                Spacer(Modifier.height(12.dp))
                Row {
                    SzGhostButton(text = "改一下", onClick = onStartEdit)
                    Spacer(Modifier.width(10.dp))
                    SzGhostButton(text = "删除这笔", onClick = onDeleteLedger)
                }
            } else {
                Row {
                    SzGhostButton(text = "改一下", onClick = onStartEdit)
                    Spacer(Modifier.width(10.dp))
                    SzGhostButton(text = "删除这笔", onClick = onDeleteLedger)
                }
            }
        } ?: Text("账目数据缺失", fontSize = 13.sp, color = e.ink3)
    }
}

/**
 * 账目编辑表单（v0.8.0）。
 *
 * 为什么改完要重新确认 —— 见 DetailViewModel.saveLedger 的注释：
 * 改过的账目一律回退成待确认，不给"绕过二次确认"的口子。
 */
@Composable
private fun LedgerEditForm(
    state: DetailUiState,
    onAmountChange: (String) -> Unit,
    onDirectionChange: (LedgerDirection) -> Unit,
    onCategoryChange: (String) -> Unit,
    onMerchantChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val e = szExtras()

    Column {
        Text("改一下", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = e.ink)
        Spacer(Modifier.height(18.dp))

        // 金额
        Text("金额（元）", fontSize = 11.5.sp, color = e.ink3)
        Spacer(Modifier.height(6.dp))
        EditField(
            value = state.ledgerAmountDraft,
            onValueChange = onAmountChange,
            placeholder = "38.50",
            singleLine = true,
        )
        state.ledgerAmountError?.let {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SzRadius.input.dp))
                    .background(Color(0xFFFBE9E7))
                    .padding(11.dp)
            ) {
                Text(it, fontSize = 12.sp, color = Color(0xFFB3261E), lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 支出 / 收入
        Text("方向", fontSize = 11.5.sp, color = e.ink3)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DirectionChip(
                label = "支出",
                selected = state.ledgerDirectionDraft == LedgerDirection.OUT,
            ) { onDirectionChange(LedgerDirection.OUT) }
            DirectionChip(
                label = "收入",
                selected = state.ledgerDirectionDraft == LedgerDirection.IN,
            ) { onDirectionChange(LedgerDirection.IN) }
        }

        Spacer(Modifier.height(16.dp))

        // 分类：预设 chip + 可手输
        Text("分类", fontSize = 11.5.sp, color = e.ink3)
        Spacer(Modifier.height(8.dp))
        FlowRowChips(
            items = LEDGER_CATEGORIES,
            selected = state.ledgerCategoryDraft,
            onSelect = onCategoryChange,
        )
        Spacer(Modifier.height(8.dp))
        EditField(
            value = state.ledgerCategoryDraft,
            onValueChange = onCategoryChange,
            placeholder = "也可以自己写",
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        // 商家
        Text("商家 / 对象（可留空）", fontSize = 11.5.sp, color = e.ink3)
        Spacer(Modifier.height(6.dp))
        EditField(
            value = state.ledgerMerchantDraft,
            onValueChange = onMerchantChange,
            placeholder = "星巴克",
            singleLine = true,
        )

        Spacer(Modifier.height(18.dp))

        Text(
            "改完会退回「待确认」，确认后才计入账本。",
            fontSize = 11.sp,
            color = e.ink3,
            lineHeight = 17.sp,
        )

        Spacer(Modifier.height(16.dp))
        Row {
            SzPrimaryButton(text = "保存", onClick = onSave)
            Spacer(Modifier.width(10.dp))
            SzGhostButton(text = "取消", onClick = onCancel)
        }
    }
}

/** 记账分类预设 —— 与识图提示词里的分类列表保持一致 */
private val LEDGER_CATEGORIES = listOf(
    "餐饮", "交通", "购物", "日用", "娱乐",
    "医疗", "住房", "通讯", "人情", "其他",
)

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val e = szExtras()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(SzRadius.pill.dp))
            .background(if (selected) e.brand else e.surfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) e.onBrand else e.ink2,
        )
    }
}

/** 分类快捷 chip：一行放不下会自动换行，不截断 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    val e = szExtras()
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { c ->
            val on = selected == c
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(SzRadius.pill.dp))
                    .background(if (on) e.brand else e.surfaceAlt)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    c,
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) e.onBrand else e.ink2,
                )
            }
        }
    }
}

/**
 * 原始截图（v0.7.0）。
 * 没图就整块不渲染，不留空白。
 */
@Composable
private fun SourceImageBlock(bmp: Bitmap) {
    val e = szExtras()

    Column {
        Text("原始截图", fontSize = 11.5.sp, color = e.ink3)
        Spacer(Modifier.height(6.dp))
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "原始截图",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(SzRadius.input.dp)),
            contentScale = ContentScale.FillWidth,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "拿不准金额就对着原图核对",
            fontSize = 11.sp,
            color = e.ink3,
        )
    }
}

// ===========================================================================
// 文章
// ===========================================================================

@Composable
private fun ArticleDetail(
    state: DetailUiState,
    item: Item,
    onRetryExtract: () -> Unit,
    onSummarize: () -> Unit,
) {
    val e = szExtras()
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SzSpacing.headerH.dp)
            .padding(bottom = 40.dp),
    ) {
        Text(item.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = e.ink, lineHeight = 30.sp)
        Spacer(Modifier.height(8.dp))

        // 元信息
        val meta = buildList {
            item.article?.siteName?.let { add(it) }
            item.article?.author?.let { add(it) }
            add(fmt(item.createdAt))
            item.article?.wordCount?.takeIf { it > 0 }?.let { add("$it 字") }
        }.joinToString(" / ")
        Text(meta, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = e.ink3)

        Spacer(Modifier.height(16.dp))

        // 抽取失败 → 给出口，不是死路
        if (item.quality == ExtractQuality.FAILED) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SzRadius.input.dp))
                    .background(Color(0xFFFBE9E7))
                    .padding(13.dp)
            ) {
                Column {
                    Text(
                        "这篇没抓到正文",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB3261E),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "可能需要在「收这吧」里登录一次，或者这篇本身反爬较严。链接已经存下了。",
                        fontSize = 12.sp,
                        color = Color(0xFFB3261E),
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text(
                            "重新抓取",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFB3261E),
                            modifier = Modifier.clickable { onRetryExtract() },
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "去原文",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFB3261E),
                            modifier = Modifier.clickable {
                                item.sourceUrl?.let { url ->
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 摘要块
        if (!item.summary.isNullOrBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SzRadius.input.dp))
                    .background(e.brandSoft)
                    .padding(14.dp)
            ) {
                Text("AI 摘要", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = e.brand)
                Spacer(Modifier.height(8.dp))
                Text(item.summary!!, fontSize = 13.sp, lineHeight = 22.sp, color = e.ink)
            }
            Spacer(Modifier.height(14.dp))
        } else if (!item.rawText.isNullOrBlank()) {
            Text(
                "生成摘要",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = e.brand,
                modifier = Modifier
                    .clickable(enabled = !state.summarizing) { onSummarize() }
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        // 标签
        if (item.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item.tags.forEach { SzTag(it) }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 正文
        val body = item.rawText
        if (!body.isNullOrBlank()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(e.line))
            Spacer(Modifier.height(16.dp))
            Text(
                text = body,
                fontSize = 15.sp,
                lineHeight = 28.sp,
                color = e.ink,
                textAlign = TextAlign.Justify,
            )
        } else if (item.quality != ExtractQuality.FAILED) {
            Text("正在收着正文…", fontSize = 13.sp, color = e.ink3)
        }

        // 原文链接
        item.sourceUrl?.let { url ->
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(e.line))
            Spacer(Modifier.height(14.dp))
            Text(
                "查看原文",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = e.brand,
                modifier = Modifier.clickable {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                url,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = e.ink3,
                maxLines = 2,
            )
        }
    }
}

// ===========================================================================
// 笔记
// ===========================================================================

@Composable
private fun NoteDetail(item: Item, sourceImage: Bitmap?) {
    val e = szExtras()
    // 笔记的 title 是 rawText 的前 40 字，内容短时两者相同 —— 避免重复显示
    val body = item.rawText?.takeIf { it.isNotBlank() && it != item.title }
    val showTitle = body != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SzSpacing.headerH.dp)
            .padding(bottom = 40.dp),
    ) {
        if (showTitle) {
            Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = e.ink)
            Spacer(Modifier.height(10.dp))
        }
        Text(
            fmt(item.createdAt),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = e.ink3,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = body ?: item.title,
            fontSize = if (showTitle) 15.sp else 22.sp,
            fontWeight = if (showTitle) FontWeight.Normal else FontWeight.Bold,
            lineHeight = if (showTitle) 26.sp else 32.sp,
            color = e.ink,
        )

        // 没认出金额时，截图就是全部内容 —— 必须看得见（v0.7.0）
        if (sourceImage != null) {
            Spacer(Modifier.height(18.dp))
            SourceImageBlock(sourceImage)
        }
    }
}

// ===========================================================================
// 小组件
// ===========================================================================

@Composable
private fun DetailRow(label: String, value: String) {
    val e = szExtras()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = e.ink2)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = e.ink,
        )
    }
}

@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
) {
    val e = szExtras()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SzRadius.input.dp))
            .background(e.surfaceAlt)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = e.ink3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 14.sp, color = e.ink),
            cursorBrush = SolidColor(e.brand),
            singleLine = singleLine,
        )
    }
}

@Composable
private fun LoadingBox() {
    val e = szExtras()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("载入中…", fontSize = 13.sp, color = e.ink3)
    }
}

@Composable
private fun NotFoundBox(onBack: () -> Unit) {
    val e = szExtras()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("这条内容不在了", fontSize = 15.sp, color = e.ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SzGhostButton(text = "返回", onClick = onBack)
        }
    }
}

private fun fmt(i: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(i.atZone(ZoneId.systemDefault()))
