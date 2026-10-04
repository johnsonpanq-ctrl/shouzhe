package com.shouzhe.app.domain.parse

import com.shouzhe.app.domain.model.Item
import com.shouzhe.app.domain.model.ItemStatus
import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection
import java.time.Instant
import java.time.ZoneId

/**
 * JSON 全量备份（v0.11.0）。
 *
 * **这是"数据不丢"的最后一道保险**：本项目无服务器、`allowBackup=false`，
 * 手机丢了/刷机了/App 卸载了，数据就没了。导出是唯一的自救手段。
 *
 * 为什么手写 JSON 而不用 org.json：与 ReceiptParser 同样理由 ——
 * `org.json` 在 JVM 单测里是 android.jar 的 stub，一调用就抛异常，
 * 等于这部分逻辑无法测试。而备份格式恰恰是最不能出错的。
 *
 * 金额一律输出**分**（整数），与库内一致 —— 备份是给程序读的，
 * 不需要好看，需要无损。要人看的用 CSV。
 */
object JsonExport {

    /** 备份格式版本 —— 将来导入时据此判断兼容性 */
    const val FORMAT_VERSION = 1

    fun backupToJson(
        items: List<Item>,
        exportedAt: Instant = Instant.now(),
        appVersion: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"formatVersion\": ").append(FORMAT_VERSION).append(",\n")
        sb.append("  \"app\": \"收这吧\"").append(",\n")
        sb.append("  \"appVersion\": ").append(str(appVersion)).append(",\n")
        sb.append("  \"exportedAt\": ").append(str(exportedAt.toString())).append(",\n")
        sb.append("  \"timezone\": ").append(str(zone.id)).append(",\n")
        sb.append("  \"itemCount\": ").append(items.size).append(",\n")
        sb.append("  \"items\": [")

        items.forEachIndexed { i, item ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append(itemToJson(item))
        }
        if (items.isNotEmpty()) sb.append("\n  ")
        sb.append("]\n}\n")
        return sb.toString()
    }

    private fun itemToJson(item: Item): String {
        val sb = StringBuilder()
        sb.append("    {\n")
        sb.append("      \"uuid\": ").append(str(item.uuid)).append(",\n")
        sb.append("      \"type\": ").append(str(item.type.name)).append(",\n")
        sb.append("      \"title\": ").append(str(item.title)).append(",\n")
        sb.append("      \"rawText\": ").append(strOrNull(item.rawText)).append(",\n")
        sb.append("      \"summary\": ").append(strOrNull(item.summary)).append(",\n")
        sb.append("      \"sourceUrl\": ").append(strOrNull(item.sourceUrl)).append(",\n")
        sb.append("      \"sourceApp\": ").append(strOrNull(item.sourceApp)).append(",\n")
        sb.append("      \"status\": ").append(str(item.status.name)).append(",\n")
        sb.append("      \"quality\": ").append(strOrNull(item.quality?.name)).append(",\n")
        sb.append("      \"createdAt\": ").append(str(item.createdAt.toString())).append(",\n")
        sb.append("      \"updatedAt\": ").append(str(item.updatedAt.toString())).append(",\n")
        sb.append("      \"tags\": [")
        sb.append(item.tags.joinToString(", ") { str(it) })
        sb.append("]")

        item.ledger?.let { l ->
            sb.append(",\n      \"ledger\": {\n")
            // 分，整数，无损
            sb.append("        \"amountCents\": ").append(l.amountCents).append(",\n")
            sb.append("        \"direction\": ").append(str(l.direction.name)).append(",\n")
            sb.append("        \"category\": ").append(str(l.category)).append(",\n")
            sb.append("        \"merchant\": ").append(strOrNull(l.merchant)).append(",\n")
            sb.append("        \"occurredAt\": ").append(str(l.occurredAt.toString())).append(",\n")
            sb.append("        \"confirmed\": ").append(l.confirmed).append("\n")
            sb.append("      }")
        }
        item.todo?.let { t ->
            sb.append(",\n      \"todo\": {\n")
            sb.append("        \"dueAt\": ").append(strOrNull(t.dueAt?.toString())).append(",\n")
            sb.append("        \"remindAt\": ").append(strOrNull(t.remindAt?.toString())).append(",\n")
            sb.append("        \"remindState\": ").append(str(t.remindState.name)).append(",\n")
            sb.append("        \"priority\": ").append(t.priority).append(",\n")
            sb.append("        \"completedAt\": ").append(strOrNull(t.completedAt?.toString())).append("\n")
            sb.append("      }")
        }
        item.article?.let { a ->
            sb.append(",\n      \"article\": {\n")
            sb.append("        \"author\": ").append(strOrNull(a.author)).append(",\n")
            sb.append("        \"siteName\": ").append(strOrNull(a.siteName)).append(",\n")
            sb.append("        \"wordCount\": ").append(a.wordCount).append("\n")
            sb.append("      }")
        }
        sb.append("\n    }")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // JSON 字符串转义（手写，可单测）
    // ------------------------------------------------------------------

    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    // 控制字符必须转义，否则产出的是非法 JSON
                    if (c < ' ') sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun strOrNull(s: String?): String = if (s == null) "null" else str(s)
}