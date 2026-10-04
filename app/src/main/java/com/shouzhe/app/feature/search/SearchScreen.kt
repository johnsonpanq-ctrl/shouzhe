package com.shouzhe.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shouzhe.app.core.time.TimeParser
import com.shouzhe.app.domain.model.Item
import com.shouzhe.app.ui.component.SzCard
import com.shouzhe.app.ui.component.SzTimestamp
import com.shouzhe.app.ui.theme.SzRadius
import com.shouzhe.app.ui.theme.SzSpacing
import com.shouzhe.app.ui.theme.szExtras
import java.time.Instant

/**
 * 搜索页 —— MVP 的「找回」能力。
 *
 * 现在是关键词匹配（标题 / 正文 / 摘要）。
 * V1.1 会加语义检索（item_embedding 表已预留），届时这个界面不用改。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onItemClick: (Long) -> Unit,
) {
    val e = szExtras()
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏 + 搜索框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SzSpacing.headerH.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←", fontSize = 18.sp, color = e.ink2,
                modifier = Modifier.clickable { onBack() }.padding(end = 10.dp),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(SzRadius.pill.dp))
                    .background(e.surfaceAlt)
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔍", fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (state.query.isEmpty()) {
                        Text("搜之前收过的…", fontSize = 13.5.sp, color = e.ink3)
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        textStyle = TextStyle(fontSize = 13.5.sp, color = e.ink),
                        cursorBrush = SolidColor(e.brand),
                        singleLine = true,
                    )
                }
                if (state.query.isNotEmpty()) {
                    Text(
                        "✕", fontSize = 12.sp, color = e.ink3,
                        modifier = Modifier.clickable { onClear() }.padding(start = 6.dp),
                    )
                }
            }
        }

        when {
            state.query.isBlank() -> HintBlock(
                "输入关键词，找回收过的东西",
                "标题、正文、摘要都会被搜到",
            )
            state.searching && state.results.isEmpty() -> HintBlock("搜索中…", null)
            state.searched && state.results.isEmpty() -> HintBlock(
                "没找到「${state.query}」",
                "换个词试试，或者用更短的词",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = SzSpacing.pageH.dp,
                    end = SzSpacing.pageH.dp,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(SzSpacing.cardGap.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                item {
                    Text(
                        "找到 ${state.results.size} 条",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = e.ink3,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                }
                items(state.results, key = { it.id }) { it ->
                    ResultCard(it) { onItemClick(it.id) }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(item: Item, onClick: () -> Unit) {
    val e = szExtras()
    SzCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = e.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val snippet = item.summary?.takeIf { it.isNotBlank() }
                    ?: item.rawText?.take(90)
                if (!snippet.isNullOrBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        snippet,
                        fontSize = 12.5.sp,
                        color = e.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 19.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    typeLabel(item),
                    fontSize = 10.5.sp,
                    color = e.ink3,
                )
            }
            SzTimestamp(TimeParser.humanize(item.createdAt, Instant.now()))
        }
    }
}

@Composable
private fun HintBlock(title: String, sub: String?) {
    val e = szExtras()
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 14.sp, color = e.ink2, fontWeight = FontWeight.Medium)
            if (sub != null) {
                Spacer(Modifier.height(8.dp))
                Text(sub, fontSize = 12.5.sp, color = e.ink3)
            }
        }
    }
}

private fun typeLabel(item: Item): String = when (item.type) {
    com.shouzhe.app.domain.model.ItemType.ARTICLE -> "文章"
    com.shouzhe.app.domain.model.ItemType.TODO -> "待办"
    com.shouzhe.app.domain.model.ItemType.LEDGER -> "账目"
    com.shouzhe.app.domain.model.ItemType.NOTE -> "笔记"
}