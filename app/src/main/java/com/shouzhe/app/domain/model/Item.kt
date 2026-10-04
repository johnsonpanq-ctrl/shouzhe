package com.shouzhe.app.domain.model

import java.time.Instant

/**
 * 内容类型 —— 四条线共用一张主表，靠这个字段区分。
 * 一旦写入不可变更（换类型 = 删除重建）。
 */
enum class ItemType {
    ARTICLE,   // 收进来的文章 / 链接
    NOTE,      // 随手记的一句话
    TODO,      // 待办 / 提醒
    LEDGER,    // 记账
}

/** 生命周期状态 */
enum class ItemStatus {
    INBOX,     // 在收件箱里（默认）
    ARCHIVED,  // 归档
    DONE,      // 完成（待办专用）
    DELETED,   // 软删除
}

/**
 * 抽取质量 —— 支撑「抓不到也不能丢」的降级策略（见 ADR-001）
 */
enum class ExtractQuality {
    GOOD,      // 正文抽取成功且字数充足
    POOR,      // 抽到了但字数偏少，展示时提示"内容可能不完整"
    FAILED,    // 抽取失败，只剩链接
}

/**
 * 收件箱里的一条内容。
 * 这是四条线的统一视图模型，UI 只认这个。
 */
data class Item(
    val id: Long = 0,
    val uuid: String,
    val type: ItemType,
    val title: String,
    val rawText: String? = null,
    val summary: String? = null,
    val sourceUrl: String? = null,
    val sourceApp: String? = null,
    val status: ItemStatus = ItemStatus.INBOX,
    val quality: ExtractQuality? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    // 分型扩展（按 type 取用）
    val article: ArticleMeta? = null,
    val todo: TodoMeta? = null,
    val ledger: LedgerMeta? = null,
    val tags: List<String> = emptyList(),
    /** 原图绝对路径（截图记账，v0.7.0）：详情页据此显示原始截图 */
    val sourceImagePath: String? = null,
)

/** 文章扩展 */
data class ArticleMeta(
    val author: String? = null,
    val publishedAt: Instant? = null,
    val siteName: String? = null,
    val wordCount: Int = 0,
    val coverImage: String? = null,
) {
    /** 正文是否够长，用于质量判定 */
    val isSubstantial: Boolean get() = wordCount >= 200
}

/** 待办扩展 */
data class TodoMeta(
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val remindState: RemindState = RemindState.NONE,
    val priority: Int = 0,
    val repeatRule: String? = null,
    val completedAt: Instant? = null,
    val snoozeCount: Int = 0,
)

enum class RemindState { NONE, SCHEDULED, FIRED, DONE, MISSED }

/** 记账扩展 */
data class LedgerMeta(
    /** 金额以「分」为单位存储，绝不用浮点（见 DATABASE.md） */
    val amountCents: Long,
    val direction: LedgerDirection,
    val category: String,
    val merchant: String? = null,
    val occurredAt: Instant,
    /** 是否经用户确认 —— 只有 confirmed 的才计入账本 */
    val confirmed: Boolean = false,
) {
    val amountYuan: Double get() = amountCents / 100.0
}

enum class LedgerDirection { IN, OUT }

/**
 * 账本汇总结果（v0.9.0）。
 *
 * 放 domain 层而不是 data/feature —— 它是**领域概念**，不是数据库查询结果，
 * 也不是某个界面的状态。之前在仓储和 feature 各定义了一份同名类，
 * combine 里强转直接 ClassCastException 崩过一次。
 *
 * 口径：**只统计 confirmed = 1**（铁律 2）。金额全程整数分（铁律 3）。
 */
data class LedgerSummary(
    val expenseCents: Long,
    val incomeCents: Long,
    val byCategory: List<CategoryTotal>,
) {
    val netCents: Long get() = incomeCents - expenseCents
    val isEmpty: Boolean get() = expenseCents == 0L && incomeCents == 0L
}

/** 一个分类的合计（支出方向） */
data class CategoryTotal(val category: String, val totalCents: Long)

/** 内容来源 —— 决定走哪条处理链路 */
enum class Origin { SHARE, QUICK_ADD, WIDGET, MANUAL, VOICE }