package com.shouzhe.app.domain.parse

import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection

/**
 * 解析结果 —— 一条输入可以拆出多笔（"晚饭88打车26.5" → 2 笔账目）。
 */
data class ParseResult(
    val entries: List<ParsedEntry>,
    val source: ParseSource,
)

/** 本次解析走的是哪条路（用于诊断与 UI 提示） */
enum class ParseSource { RULES, MODEL }

/** 单笔解析结果，结构与模型输出对齐 */
data class ParsedEntry(
    val type: ItemType,
    val confidence: Double,
    val title: String,
    val timeExpression: String? = null,
    val remindExpression: String? = null,
    val ledger: LedgerDraftParsed? = null,
    val noteText: String? = null,
) {
    val needsConfirmation: Boolean get() = confidence < 0.7
}

data class LedgerDraftParsed(
    /** 单位：元。入库时 ×100 转成分 */
    val amountYuan: Double,
    val direction: LedgerDirection,
    val category: String,
    val merchant: String? = null,
) {
    val amountCents: Long get() = Math.round(amountYuan * 100)
}