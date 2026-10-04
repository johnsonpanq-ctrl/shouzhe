package com.shouzhe.app.domain.parse

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 账本统计区间（v0.9.0）。
 *
 * 为什么单独抽出来：时间边界最容易出"差一天"的错，而账本差一天
 * 就是"这个月花了 3800 块"和"上个月花了 3800 块"的区别。
 * 纯函数、无 Android 依赖 → 可以直接单测钉死。
 */
object LedgerRange {

    /** 一个统计区间：起止都是 UTC 毫秒，闭区间（SQL 用 BETWEEN） */
    data class Range(val from: Long, val to: Long) {
        val isEmpty: Boolean get() = to < from
    }

    /**
     * 本月 1 日 00:00:00.000 到本月最后一天 23:59:59.999（本地时区换算成 UTC 毫秒）。
     *
     * 用"月末 +1 天起点 -1 毫秒"收尾，而不是"30 天"这种魔法数 ——
     * 2 月、平年闰年都自动正确。
     */
    fun thisMonth(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Range {
        val ym = YearMonth.from(now.atZone(zone))
        return monthOf(ym, zone)
    }

    fun monthOf(ym: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Range {
        val start = ym.atDay(1).atStartOfDay(zone).toInstant()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
            .minusMillis(1)
        return Range(start.toEpochMilli(), end.toEpochMilli())
    }

    /** 某年某月（比如查历史月份） */
    fun monthOf(year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): Range =
        monthOf(YearMonth.of(year, month), zone)

    /** 本周（周一 00:00 起算，符合国内习惯） */
    fun thisWeek(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Range {
        val d = LocalDate.ofInstant(now, zone)
        val monday = d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val start = monday.atStartOfDay(zone).toInstant()
        val end = monday.plusDays(7).atStartOfDay(zone).toInstant().minusMillis(1)
        return Range(start.toEpochMilli(), end.toEpochMilli())
    }

    /** 今天 */
    fun today(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Range {
        val d = LocalDate.ofInstant(now, zone)
        val start = d.atStartOfDay(zone).toInstant()
        val end = d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
        return Range(start.toEpochMilli(), end.toEpochMilli())
    }

    /** 最近 N 天（含今天） */
    fun lastDays(n: Int, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Range {
        require(n > 0) { "天数要大于 0" }
        val d = LocalDate.ofInstant(now, zone)
        val start = d.minusDays((n - 1).toLong()).atStartOfDay(zone).toInstant()
        val end = d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
        return Range(start.toEpochMilli(), end.toEpochMilli())
    }

    /**
     * 把毫秒显示成"10月4日 14:30"这种本地时间（v0.9.0 账本区间标题用）。
     * 放在这里是为了让区间与展示用同一套时区换算，不会两边不一致。
     */
    fun formatLocal(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))
}