package com.shouzhe.app.core.time

import java.time.*
import java.time.temporal.TemporalAdjusters

/**
 * 本地时间解析 —— 排期不许出错的关键（见 API.md §4）。
 *
 * 分工：模型只负责识别时间表达式的原文，换算成绝对时间由这里做。
 * 理由：模型对"明天""下周三"的理解不可靠，且它不知道"现在"。
 */
object TimeParser {

    private val WEEKDAYS = mapOf(
        "一" to DayOfWeek.MONDAY, "二" to DayOfWeek.TUESDAY, "三" to DayOfWeek.WEDNESDAY,
        "四" to DayOfWeek.THURSDAY, "五" to DayOfWeek.FRIDAY, "六" to DayOfWeek.SATURDAY,
        "日" to DayOfWeek.SUNDAY, "天" to DayOfWeek.SUNDAY,
    )

    private val CN_NUM = mapOf(
        "零" to 0, "一" to 1, "两" to 2, "二" to 2, "三" to 3, "四" to 4,
        "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
    )

    /**
     * 下午时段词。
     * 必须包含「今晚」「今夜」「明晚」这类带「今/明」的组合 ——
     * 否则「今晚8点」会被当成早上 8 点（单测抓出过一次）。
     */
    private val PM_WORDS = listOf(
        "下午", "晚上", "傍晚", "夜里", "今晚", "今夜", "明晚", "晚间",
    )

    /** 上午时段词 */
    private val AM_WORDS = listOf(
        "早上", "上午", "凌晨", "今早", "明早", "早晨", "清晨", "一早",
    )

    /**
     * 解析中文时间表达式。
     * @return 绝对时间；无法解析时返回 null（由 UI 让用户补充，绝不瞎猜）
     */
    fun parse(expr: String?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        if (expr.isNullOrBlank()) return null
        val s = expr.trim().replace(" ", "")
        val today = now.atZone(zone).toLocalDate()

        // 1) 日期部分
        var date: LocalDate? = when {
            s.contains("今晚") || s.contains("今夜") || s.contains("今早") ||
                s.contains("今天") || s.contains("今日") -> today
            s.contains("明晚") || s.contains("明早") ||
                s.contains("明天") || s.contains("明日") -> today.plusDays(1)
            s.contains("后天") -> today.plusDays(2)
            s.contains("大后天") -> today.plusDays(3)
            s.contains("下周") -> {
                val d = WEEKDAYS.entries.firstOrNull { s.contains(it.key) }?.value
                if (d != null) today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    .with(TemporalAdjusters.nextOrSame(d))
                else today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            }
            s.contains("这周") || s.contains("本周") || s.contains("周") || s.contains("星期") -> {
                val d = WEEKDAYS.entries.firstOrNull { s.contains(it.key) }?.value
                if (d != null) today.with(TemporalAdjusters.nextOrSame(d)) else null
            }
            s.contains("月底") -> today.with(TemporalAdjusters.lastDayOfMonth())
            s.contains("月初") -> today.withDayOfMonth(1)
            else -> parseAbsoluteDate(s, today.year)
        }

        // 2) 时间部分
        val time = parseTimeOfDay(s)

        if (date == null && time == null) return null
        if (date == null) {
            // 只有时间：取今天；若已过则取明天（"下午三点"在晚上说 = 明天三点）
            val t = time!!
            val candidate = today.atTime(t.hour, t.minute).atZone(zone).toInstant()
            return if (candidate.isBefore(now)) {
                today.plusDays(1).atTime(t.hour, t.minute).atZone(zone).toInstant()
            } else candidate
        }
        if (time == null) {
            // 只有日期：默认早上 9 点
            return date.atTime(9, 0).atZone(zone).toInstant()
        }
        return date.atTime(time.hour, time.minute).atZone(zone).toInstant()
    }

    /** 解析 2026-10-05 / 10月5日 / 10/5 这类绝对日期 */
    private fun parseAbsoluteDate(s: String, defaultYear: Int): LocalDate? {
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(s)?.let {
            val (y, m, d) = it.destructured
            return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }
        Regex("""(\d{1,2})月(\d{1,2})[日号]""").find(s)?.let {
            val (m, d) = it.destructured
            return runCatching { LocalDate.of(defaultYear, m.toInt(), d.toInt()) }.getOrNull()
        }
        Regex("""(\d{1,2})[/](\d{1,2})""").find(s)?.let {
            val (m, d) = it.destructured
            return runCatching { LocalDate.of(defaultYear, m.toInt(), d.toInt()) }.getOrNull()
        }
        return null
    }

    /** 解析时刻：15:30 / 下午三点 / 晚上8点 / 早上9点半 */
    private fun parseTimeOfDay(s: String): LocalTime? {
        // 24 小时制
        Regex("""(\d{1,2})[:：](\d{1,2})""").find(s)?.let {
            val (h, m) = it.destructured
            return runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull()
        }

        val isPm = PM_WORDS.any { s.contains(it) }
        val isNoon = s.contains("中午")
        val isAm = AM_WORDS.any { s.contains(it) }

        // 中文数字：三点 / 三点半 / 十点
        Regex("""([零一两二三四五六七八九十]+)点(半|[零一两二三四五六七八九十]+分)?""").find(s)?.let {
            val hourCn = it.groupValues[1]
            val minutePart = it.groupValues[2]
            var hour = cnToInt(hourCn) ?: return@let
            val minute = when {
                minutePart == "半" -> 30
                minutePart.isBlank() -> 0
                else -> cnToInt(minutePart.removeSuffix("分")) ?: 0
            }
            if (isPm && hour < 12) hour += 12
            if (isNoon && hour < 12) hour += 12
            if (!isPm && !isAm && !isNoon && hour in 1..7) hour += 12 // "三点"默认下午
            return runCatching { LocalTime.of(hour % 24, minute) }.getOrNull()
        }

        // 阿拉伯数字：8点 / 15点
        Regex("""(\d{1,2})点(半|(\d{1,2})分)?""").find(s)?.let {
            var hour = it.groupValues[1].toIntOrNull() ?: return@let
            val minute = when {
                it.groupValues[2] == "半" -> 30
                it.groupValues[3].isNotBlank() -> it.groupValues[3].toIntOrNull() ?: 0
                else -> 0
            }
            if (isPm && hour < 12) hour += 12
            if (isNoon && hour < 12) hour += 12
            return runCatching { LocalTime.of(hour % 24, minute) }.getOrNull()
        }

        if (isNoon) return LocalTime.of(12, 0)
        return null
    }

    private fun cnToInt(cn: String): Int? {
        if (cn.isEmpty()) return null
        if (cn == "十") return 10
        if (cn.length == 1) return CN_NUM[cn]
        // 十X / X十 / X十Y
        val idx = cn.indexOf("十")
        if (idx == 0) {
            val unit = CN_NUM[cn.substring(1)] ?: return null
            return 10 + unit
        }
        if (idx == cn.length - 1) {
            val tens = CN_NUM[cn.substring(0, idx)] ?: return null
            return tens * 10
        }
        if (idx > 0) {
            val tens = CN_NUM[cn.substring(0, idx)] ?: return null
            val unit = CN_NUM[cn.substring(idx + 1)] ?: return null
            return tens * 10 + unit
        }
        return null
    }

    /** 相对时间描述，用于列表展示 */
    fun humanize(at: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = Duration.between(at, now).seconds
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60} 分钟前"
            diff < 86400 -> "${diff / 3600} 小时前"
            diff < 172800 -> "昨天"
            diff < 604800 -> "${diff / 86400} 天前"
            else -> at.atZone(zone).let { "${it.monthValue}月${it.dayOfMonth}日" }
        }
    }
}