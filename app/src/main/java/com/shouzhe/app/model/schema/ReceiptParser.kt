package com.shouzhe.app.model.schema

import com.shouzhe.app.domain.model.LedgerDirection
import com.shouzhe.app.model.gateway.ReceiptResult
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 截图识别的结构化输出解析（v0.7.0）。
 *
 * 为什么要独立成文件、为什么不用 org.json：
 * - 网关只管发请求，解析必须可单测（AGENTS.md：关键路径 100% 覆盖）
 * - org.json 在 JVM 单测里是 android.jar 的 stub，一调用就抛异常 → 等于没法测。
 *   这里只取**标量字段**，格式由我们的提示词约束，用正则足够，且是纯 Kotlin。
 * - 嵌套结构一律不解析（我们本来也不需要）。
 */
object ReceiptParser {

    private val DATE_ONLY = listOf("yyyy-MM-dd", "yyyy/MM/dd")
    private val DATE_TIME = listOf(
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm", "yyyy/MM/dd HH:mm:ss",
    )

    /**
     * 解析模型输出。
     * 返回 null 表示"连 JSON 都没读出来"，由网关转成 BadOutput。
     *
     * 读出来了但不是账单 / 认不出金额 → 返回 isBill=false 或 amountYuan=null 的对象，
     * **不是 null**：上层据此降级存图，不丢用户的东西。
     */
    fun parse(raw: String): ReceiptResult? {
        val json = extractJsonObject(raw) ?: return null
        return try {
            val isBill = bool(json, "isBill") ?: false
            val confidence = (num(json, "confidence") ?: 0.5).coerceIn(0.0, 1.0)
            val amount = num(json, "amount") ?: 0.0

            ReceiptResult(
                isBill = isBill,
                confidence = confidence,
                title = str(json, "title").orEmpty().ifBlank {
                    if (isBill) "截图记账" else "一张待整理的截图"
                },
                // 认不出金额就是 null，绝不编一个 0 元账目出来
                amountYuan = if (isBill && amount > 0.0) amount else null,
                direction = if (str(json, "direction") == "in") {
                    LedgerDirection.IN
                } else {
                    LedgerDirection.OUT
                },
                category = str(json, "category").orEmpty().ifBlank { "其他" },
                merchant = str(json, "merchant")?.ifBlank { null },
                occurredAt = parseOccurredAt(str(json, "occurredAt")),
                excerpt = str(json, "excerpt")?.ifBlank { null },
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 账单上的日期都是**绝对日期**（不存在"明天"），所以只接受绝对格式，
     * 解析不了就返回 null —— 绝不拿"当前时间"去蒙一个。
     */
    fun parseOccurredAt(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null

        DATE_TIME.forEach { p ->
            runCatching {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p))
                    .atZone(zone).toInstant()
            }
        }
        DATE_ONLY.forEach { p ->
            runCatching {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern(p))
                    .atStartOfDay(zone).toInstant()
            }
        }
        // 认不出来就告诉调用方"没认出来"，由上层降级并如实告知用户
        return null
    }

    // ------------------------------------------------------------------
    // 标量取值 —— 只认字符串 / 数字 / 布尔，不解析嵌套
    // ------------------------------------------------------------------

    fun str(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"")
            .find(json)?.groupValues?.getOrNull(1)

    fun num(json: String, key: String): Double? {
        val raw = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
            .find(json)?.groupValues?.getOrNull(1) ?: return null
        return raw.toDoubleOrNull()
    }

    /** 容忍模型把 true 写成 "true" */
    fun bool(json: String, key: String): Boolean? {
        val v = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false|\"true\"|\"false\")")
            .find(json)?.groupValues?.getOrNull(1) ?: return null
        return v.removeSurrounding("\"").toBoolean()
    }

    /** 剥掉 ```json 围栏，取第一个平衡的 {...} —— 兼容端点爱加围栏 */
    fun extractJsonObject(raw: String): String? {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            s = s.substringBeforeLast("```").trim()
        }
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }
}