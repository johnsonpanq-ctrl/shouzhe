package com.shouzhe.app.domain.parse

import com.shouzhe.app.domain.model.Item
import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CSV 导出（v0.11.0）。
 *
 * 为什么抽成纯函数：CSV 看着简单，但转义规则错了会把列错位、
 * 甚至在 Excel 里把一行的内容拆成好几行 —— 那是**导出数据损坏**，
 * 比没有导出更糟糕（用户以为备份好了）。
 *
 * 规则（RFC 4180）：
 * - 字段里有逗号、双引号、换行 → 整个字段用双引号包起来
 * - 字段里的双引号 → 写成两个双引号
 * - 行尾用 CRLF（Excel 兼容性最好）
 */
object CsvExport {

    /** 账本 CSV 的表头 */
    private val LEDGER_HEADER = listOf(
        "日期", "类型", "金额", "方向", "分类", "商家", "状态", "备注",
    )

    /**
     * 把账目导成 CSV 文本。
     *
     * 只导**账目**不导全部内容：账本是用户最在意、最可能有外部用途的数据
     * （报销、对账、记账 App 迁移）。文章和笔记的体积大、格式杂，
     * 走 JSON 全量备份那条路更合适。
     *
     * 金额输出**元**（两位小数），因为导出是给人看/给别的软件读的；
     * 库内仍然是整数分（铁律 3 不受影响）。
     */
    fun ledgerToCsv(
        items: List<Item>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sb = StringBuilder()
        // BOM：不加的话 Excel 打开中文会乱码（GBK 猜编码）
        sb.append('\uFEFF')
        sb.append(LEDGER_HEADER.joinToString(",") { escape(it) }).append("\r\n")

        items.filter { it.type == ItemType.LEDGER && it.ledger != null }
            .sortedBy { it.ledger!!.occurredAt }
            .forEach { item ->
                val l = item.ledger!!
                val row = listOf(
                    formatDate(l.occurredAt, zone),
                    item.title,
                    MoneyParser.centsToYuanText(l.amountCents),
                    if (l.direction == LedgerDirection.OUT) "支出" else "收入",
                    l.category,
                    l.merchant.orEmpty(),
                    // 口径与界面一致：只有已确认的才算真账
                    if (l.confirmed) "已确认" else "待确认",
                    item.summary.orEmpty(),
                )
                sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
            }
        return sb.toString()
    }

    /**
     * CSV 字段转义（RFC 4180）。
     *
     * 注意：**前导的 `=` `+` `-` `@` 不在这里处理** —— 那是 CSV 注入问题，
     * 需要在更外层决定要不要加前缀（本项目导出的是用户自己的数据，
     * 且不导入回系统，暂不加）。
     */
    fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

    private fun formatDate(at: Instant, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(at.atZone(zone))
}