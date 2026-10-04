package com.shouzhe.app.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// statusBarsPadding / navigationBarsPadding 在 foundation.layout 里
import com.shouzhe.app.core.time.TimeParser
import com.shouzhe.app.domain.model.*
import com.shouzhe.app.domain.parse.MoneyParser
import com.shouzhe.app.ui.component.*
import com.shouzhe.app.ui.theme.SzSpacing
import com.shouzhe.app.ui.theme.szExtras
import java.time.Instant

/** 收件箱筛选页签 */
enum class InboxFilter(val label: String, val type: ItemType?) {
    ALL("全部", null),
    ARTICLE("文章", ItemType.ARTICLE),
    TODO("待办", ItemType.TODO),
    LEDGER("账目", ItemType.LEDGER),
    NOTE("笔记", ItemType.NOTE),
}

@Composable
fun InboxScreen(
    state: InboxUiState,
    onFilterChange: (InboxFilter) -> Unit,
    onItemClick: (Long) -> Unit,
    onConfirmLedger: (Long) -> Unit,
    onQuickAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenPending: () -> Unit = {},
    onScopeChange: (SummaryScope) -> Unit = {},
) {
    val e = szExtras()

    Box(modifier = Modifier.fillMaxSize().background(e.let {
        androidx.compose.material3.MaterialTheme.colorScheme.background
    })) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 关键：给状态栏留出空间，否则标题被时间和信号挡住
                .statusBarsPadding(),
        ) {
            // ---------- 顶栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SzSpacing.headerH.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "收这吧",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = e.ink,
                    modifier = Modifier.clickable { onOpenSettings() },
                )
                Spacer(Modifier.weight(1f))

                // 搜索入口
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(e.surfaceAlt)
                        .clickable { onOpenSearch() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔍", fontSize = 13.sp)
                }

                Spacer(Modifier.width(8.dp))

                // 设置入口
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(e.surfaceAlt)
                        .clickable { onOpenSettings() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚙", fontSize = 14.sp, color = e.ink2)
                }

                if (state.pendingConfirmCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(e.brandSoft)
                            .clickable { onOpenPending() }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "待确认 ${state.pendingConfirmCount}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = e.brand,
                        )
                    }
                }
            }

            // ---------- 页签 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SzSpacing.headerH.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InboxFilter.entries.forEach { f ->
                    SzTab(
                        label = f.label,
                        selected = state.filter == f,
                        onClick = { onFilterChange(f) },
                    )
                }
            }

            // ---------- 账本汇总（仅账目页签；v0.9.0） ----------
            if (state.filter == InboxFilter.LEDGER) {
                LedgerSummaryPanel(
                    summary = state.summary,
                    scope = state.summaryScope,
                    pendingCount = state.pendingConfirmCount,
                    onScopeChange = onScopeChange,
                )
                Spacer(Modifier.height(12.dp))
            }

            // ---------- 列表 / 空状态 ----------
            if (state.items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = SzSpacing.pageH.dp,
                        end = SzSpacing.pageH.dp,
                        bottom = 90.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(SzSpacing.cardGap.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        when (item.type) {
                            ItemType.LEDGER -> LedgerCard(item, onConfirmLedger, onItemClick)
                            ItemType.TODO -> TodoCard(item, onItemClick)
                            ItemType.ARTICLE -> ArticleCard(item, onItemClick)
                            ItemType.NOTE -> NoteCard(item, onItemClick)
                        }
                    }
                }
            }
        }

        // ---------- 浮动录入按钮 ----------
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 20.dp)
                .size(52.dp)
                .shadow(6.dp, CircleShape, spotColor = e.brand.copy(alpha = 0.4f))
                .clip(CircleShape)
                .background(e.brand)
                .clickable { onQuickAdd() },
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", fontSize = 24.sp, color = e.onBrand, fontWeight = FontWeight.Light)
        }
    }
}

// ---------------------------------------------------------------------------
// 账本汇总（v0.9.0）
// ---------------------------------------------------------------------------

/**
 * 账本汇总面板。
 *
 * 口径（与铁律 2 同一标准）：**只统计已确认的数字**。
 * 有待确认草稿时明说"这几笔还没计入"，绝不把草稿混进总额 ——
 * 用户看到的数字必须是他自己认可过的。
 */
@Composable
private fun LedgerSummaryPanel(
    summary: LedgerSummary,
    scope: SummaryScope,
    pendingCount: Int,
    onScopeChange: (SummaryScope) -> Unit,
) {
    val e = szExtras()

    SzCard {
        // 口径切换
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "账本",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = e.ink3,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryScope.entries.forEach { s ->
                    ScopeChip(s.label, selected = s == scope) { onScopeChange(s) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (summary.isEmpty) {
            Text(
                "这个${scope.label}还没有已确认的账目",
                fontSize = 13.5.sp,
                color = e.ink3,
            )
        } else {
            // 支出大字（支出才是大多数人记账的目的）
            Text(
                text = "-¥" + MoneyParser.centsToYuanText(summary.expenseCents),
                fontFamily = FontFamily.Monospace,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = e.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${scope.label}支出" +
                    if (summary.incomeCents > 0) {
                        " · 收入 ¥" + MoneyParser.centsToYuanText(summary.incomeCents)
                    } else "",
                fontSize = 12.sp,
                color = e.ink3,
            )

            // 分类排行
            if (summary.byCategory.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("花在哪", fontSize = 11.5.sp, color = e.ink3)
                Spacer(Modifier.height(8.dp))
                val max = summary.byCategory.maxOf { it.totalCents }
                summary.byCategory.take(5).forEach { c ->
                    CategoryBar(
                        category = c.category,
                        totalCents = c.totalCents,
                        ratio = if (max > 0) c.totalCents.toFloat() / max else 0f,
                    )
                }
                if (summary.byCategory.size > 5) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "另有 ${summary.byCategory.size - 5} 个分类",
                        fontSize = 11.sp,
                        color = e.ink3,
                    )
                }
            }
        }

        // 待确认提醒：说清楚为什么不计入
        if (pendingCount > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "另有 $pendingCount 笔待确认，没计入上面的数",
                fontSize = 11.sp,
                color = e.brand,
            )
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val e = szExtras()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) e.brand else e.surfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) e.onBrand else e.ink3,
        )
    }
}

/** 分类占比条：名字 + 金额 + 一条按比例的横条 */
@Composable
private fun CategoryBar(category: String, totalCents: Long, ratio: Float) {
    val e = szExtras()
    Column(Modifier.padding(bottom = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(category, fontSize = 12.5.sp, color = e.ink)
            Spacer(Modifier.weight(1f))
            Text(
                "¥" + MoneyParser.centsToYuanText(totalCents),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                color = e.ink2,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(e.surfaceDeep)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(e.brand)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 四种卡片
// ---------------------------------------------------------------------------

@Composable
private fun LedgerCard(item: Item, onConfirm: (Long) -> Unit, onClick: (Long) -> Unit) {
    val e = szExtras()
    val ledger = item.ledger
    SzCard(onClick = { onClick(item.id) }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = e.ink)
                Spacer(Modifier.height(4.dp))
                if (ledger != null) SzAmount(ledger.amountCents)
            }
            SzTimestamp(TimeParser.humanize(item.createdAt, Instant.now()))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ledger != null) {
                SzTag(ledger.category)
                Spacer(Modifier.width(8.dp))
            }
            if (ledger != null && !ledger.confirmed) {
                SzPrimaryButton(text = "确认", onClick = { onConfirm(item.id) })
                Spacer(Modifier.width(8.dp))
                SzGhostButton(text = "改一下", onClick = { onClick(item.id) })
            }
        }
    }
}

@Composable
private fun TodoCard(item: Item, onClick: (Long) -> Unit) {
    val e = szExtras()
    SzCard(onClick = { onClick(item.id) }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = e.ink)
                item.todo?.let { todo ->
                    val whenText = todo.remindAt?.let {
                        "提醒 " + TimeParser.humanize(it, Instant.now()).replace("前", "后")
                    }
                    if (!whenText.isNullOrBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(whenText, fontSize = 12.5.sp, color = e.ink2)
                    }
                }
            }
            SzTimestamp(TimeParser.humanize(item.createdAt, Instant.now()))
        }
    }
}

@Composable
private fun ArticleCard(item: Item, onClick: (Long) -> Unit) {
    val e = szExtras()
    SzCard(onClick = { onClick(item.id) }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = e.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val body = item.summary?.takeIf { it.isNotBlank() }
                    ?: item.rawText?.take(120)
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = body,
                        fontSize = 12.5.sp,
                        color = e.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 19.sp,
                    )
                }
            }
            SzTimestamp(TimeParser.humanize(item.createdAt, Instant.now()))
        }
        if (item.quality == ExtractQuality.FAILED) {
            Spacer(Modifier.height(6.dp))
            Text("只存了链接，正文没抓到", fontSize = 11.sp, color = e.ink3)
        }
        if (item.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.tags.take(3).forEach { SzTag(it) }
            }
        }
    }
}

@Composable
private fun NoteCard(item: Item, onClick: (Long) -> Unit) {
    val e = szExtras()
    SzCard(onClick = { onClick(item.id) }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = e.ink)
                item.rawText?.takeIf { it != item.title }?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(it, fontSize = 12.5.sp, color = e.ink2, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            SzTimestamp(TimeParser.humanize(item.createdAt, Instant.now()))
        }
    }
}

@Composable
private fun EmptyState() {
    val e = szExtras()
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(e.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    id = com.shouzhe.app.R.drawable.ic_empty_tray
                ),
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text("还没收着东西", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = e.ink)
        Spacer(Modifier.height(12.dp))
        Text(
            "看到好文章，分享给「收这吧」\n想到一件事，说一句话",
            fontSize = 13.sp,
            color = e.ink2,
            lineHeight = 26.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}