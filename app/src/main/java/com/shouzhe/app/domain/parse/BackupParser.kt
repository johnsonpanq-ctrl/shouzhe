package com.shouzhe.app.domain.parse

import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.ItemStatus
import com.shouzhe.app.domain.model.LedgerDirection
import java.time.Instant

/**
 * 备份文件解析（v0.12.0）。
 *
 * 手写 JSON 解析器，理由与 [JsonExport] / [ReceiptParser] 一致：
 * `org.json` 在 JVM 单测里是 android.jar 的 stub，一调用就抛异常 ——
 * 而"能不能正确读回备份"恰恰是最不能出错的逻辑。
 *
 * **设计原则：宁可拒绝，不可猜。**
 * 备份文件是我们自己导出的，格式完全可控。遇到不认识的结构就明确报错，
 * 绝不"尽力而为地猜一个"—— 猜错的后果是把垃圾数据写进用户唯一的数据库。
 */
object BackupParser {

    /** 解析结果 */
    sealed interface Result {
        data class Ok(val backup: Backup) : Result
        data class Bad(val reason: String) : Result
    }

    /** 解析出来的备份 */
    data class Backup(
        val formatVersion: Int,
        val appVersion: String?,
        val exportedAt: String?,
        val items: List<BackupItem>,
    )

    /** 一条备份条目 —— 只保留我们能映射回数据库的字段 */
    data class BackupItem(
        val uuid: String,
        val type: ItemType,
        val title: String,
        val rawText: String?,
        val summary: String?,
        val sourceUrl: String?,
        val sourceApp: String?,
        val status: ItemStatus,
        val quality: String?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
        val tags: List<String>,
        val ledger: BackupLedger?,
        val todo: BackupTodo?,
    )

    data class BackupLedger(
        val amountCents: Long,
        val direction: LedgerDirection,
        val category: String,
        val merchant: String?,
        val occurredAt: Instant?,
        val confirmed: Boolean,
    )

    data class BackupTodo(
        val dueAt: Instant?,
        val remindAt: Instant?,
        val remindState: String,
        val priority: Int,
    )

    /** 当前能处理的最高格式版本 —— 比它新的文件一律拒绝 */
    const val MAX_SUPPORTED_VERSION = 1

    fun parse(text: String): Result {
        val s = text.trim()
        if (s.isBlank()) return Result.Bad("文件是空的")

        val version = intOf(s, "formatVersion")
            ?: return Result.Bad("这不是「收这吧」的备份文件（缺少 formatVersion）")

        if (version > MAX_SUPPORTED_VERSION) {
            return Result.Bad(
                "这份备份来自更新版本的 App（格式 v$version，本机只支持到 v$MAX_SUPPORTED_VERSION）。" +
                    "请先升级 App 再导入。"
            )
        }

        val itemsBlock = arrayBlock(s, "items")
            ?: return Result.Bad("文件里没有找到 items 列表，可能已损坏")

        val items = mutableListOf<BackupItem>()
        var skipped = 0
        for (obj in splitObjects(itemsBlock)) {
            val item = parseItem(obj)
            if (item == null) skipped++ else items.add(item)
        }

        if (items.isEmpty() && skipped > 0) {
            return Result.Bad("文件里的 $skipped 条记录都读不出来，可能已损坏")
        }

        return Result.Ok(
            Backup(
                formatVersion = version,
                appVersion = strOf(s, "appVersion"),
                exportedAt = strOf(s, "exportedAt"),
                items = items,
            )
        )
    }

    // ------------------------------------------------------------------
    // 单条解析
    // ------------------------------------------------------------------

    private fun parseItem(obj: String): BackupItem? {
        val uuid = strOf(obj, "uuid")?.takeIf { it.isNotBlank() } ?: return null
        val typeName = strOf(obj, "type") ?: return null
        val type = runCatching { ItemType.valueOf(typeName) }.getOrNull() ?: return null
        val title = strOf(obj, "title") ?: return null

        val status = strOf(obj, "status")
            ?.let { runCatching { ItemStatus.valueOf(it) }.getOrNull() }
            ?: ItemStatus.INBOX

        return BackupItem(
            uuid = uuid,
            type = type,
            title = title,
            rawText = strOf(obj, "rawText"),
            summary = strOf(obj, "summary"),
            sourceUrl = strOf(obj, "sourceUrl"),
            sourceApp = strOf(obj, "sourceApp"),
            status = status,
            quality = strOf(obj, "quality"),
            createdAt = instantOf(strOf(obj, "createdAt")),
            updatedAt = instantOf(strOf(obj, "updatedAt")),
            tags = stringArray(obj, "tags"),
            ledger = nested(obj, "ledger")?.let { parseLedger(it) },
            todo = nested(obj, "todo")?.let { parseTodo(it) },
        )
    }

    private fun parseLedger(o: String): BackupLedger? {
        // 金额必须是整数分。缺了或非法就整条 ledger 不要 —— 宁可不导入这笔账，
        // 也不能导进一个金额是 0 或错的账目（记账铁律）
        val cents = longOf(o, "amountCents") ?: return null
        if (cents < 0) return null
        return BackupLedger(
            amountCents = cents,
            direction = if (strOf(o, "direction") == "IN") LedgerDirection.IN
            else LedgerDirection.OUT,
            category = strOf(o, "category")?.ifBlank { "其他" } ?: "其他",
            merchant = strOf(o, "merchant"),
            occurredAt = instantOf(strOf(o, "occurredAt")),
            confirmed = boolOf(o, "confirmed") ?: false,
        )
    }

    private fun parseTodo(o: String): BackupTodo = BackupTodo(
        dueAt = instantOf(strOf(o, "dueAt")),
        remindAt = instantOf(strOf(o, "remindAt")),
        remindState = strOf(o, "remindState") ?: "NONE",
        priority = intOf(o, "priority") ?: 0,
    )

    // ------------------------------------------------------------------
    // 极简 JSON 读取（只处理我们自己导出的结构）
    // ------------------------------------------------------------------

    /** 取字符串值；JSON null 与缺失都返回 null */
    fun strOf(json: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|null)")
            .find(json) ?: return null
        val quoted = m.groupValues[2]
        // null 分支：group2 为空且原文是 null
        if (m.value.endsWith("null")) return null
        return unescape(quoted)
    }

    fun longOf(json: String, key: String): Long? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+)")
            .find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun intOf(json: String, key: String): Int? = longOf(json, key)?.toInt()

    fun boolOf(json: String, key: String): Boolean? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)")
            .find(json)?.groupValues?.getOrNull(1)?.toBoolean()

    /** 取嵌套对象 `"key": { ... }` 的原文（含括号） */
    fun nested(json: String, key: String): String? {
        val openIdx = indexOfDelimiter(json, key, '{') ?: return null
        return balancedFrom(json, openIdx)
    }

    /** 取数组 `"key": [ ... ]` 的内部原文（不含方括号） */
    fun arrayBlock(json: String, key: String): String? {
        val openIdx = indexOfDelimiter(json, key, '[') ?: return null
        val whole = balancedFrom(json, openIdx, open = '[', close = ']') ?: return null
        return whole.substring(1, whole.length - 1)
    }

    /**
     * 找到 `"key"` 之后第一个指定的定界符位置。
     *
     * 关键点：**不能用 `range.last`** —— 正则里的 `\s*` 会贪婪吞掉空白，
     * 导致算出来的位置偏移（踩过：arrayBlock 一直返回 null）。
     * 直接 `indexOf` 找字符位置最稳。
     */
    private fun indexOfDelimiter(json: String, key: String, delimiter: Char): Int? {
        val keyMatch = Regex("\"" + Regex.escape(key) + "\"\\s*:").find(json) ?: return null
        var i = keyMatch.range.last + 1
        while (i < json.length && json[i].isWhitespace()) i++
        return if (i < json.length && json[i] == delimiter) i else null
    }

    /** 从 open 位置起取平衡的一段（正确跳过字符串内的括号） */
    private fun balancedFrom(
        s: String,
        openIdx: Int,
        open: Char = '{',
        close: Char = '}',
    ): String? {
        if (openIdx < 0 || openIdx >= s.length || s[openIdx] != open) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in openIdx until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            when {
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == open -> depth++
                !inStr && c == close -> {
                    depth--
                    if (depth == 0) return s.substring(openIdx, i + 1)
                }
            }
        }
        return null
    }

    /** 把数组内部切成一个个平衡的 {...} */
    fun splitObjects(arrayInner: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < arrayInner.length) {
            if (arrayInner[i] == '{') {
                val obj = balancedFrom(arrayInner, i) ?: break
                out.add(obj)
                i += obj.length
            } else i++
        }
        return out
    }

    /** 取字符串数组 `"key": ["a","b"]` */
    fun stringArray(json: String, key: String): List<String> {
        val inner = arrayBlock(json, key) ?: return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(inner).map { unescape(it.groupValues[1]) }.toList()
    }

    /** 反转义 JSON 字符串 */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) {
                        sb.append(code.toChar()); i += 6
                    } else { sb.append(n); i += 2 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    /** ISO 时间串 → Instant；解析不了返回 null（由调用方决定降级） */
    fun instantOf(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s) }.getOrNull()
    }
}