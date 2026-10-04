package com.shouzhe.app

import com.shouzhe.app.domain.parse.LedgerRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 账本统计区间（v0.9.0）。
 *
 * 这组测试守的是"差一天就是错账"：
 * 本月边界、闰年 2 月、周一开头、闭区间两端。
 */
class LedgerRangeTest {

    private val sh = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, m: Int, d: Int, hh: Int = 12, mm: Int = 0) =
        LocalDate.of(y, m, d).atTime(hh, mm).atZone(sh).toInstant()

    @Test
    fun `本月从1号零点开始`() {
        val r = LedgerRange.thisMonth(at(2026, 10, 15), sh)
        assertEquals(at(2026, 10, 1, 0, 0).toEpochMilli(), r.from)
    }

    @Test
    fun `本月到月末最后一毫秒结束`() {
        val r = LedgerRange.thisMonth(at(2026, 10, 15), sh)
        // 10 月 31 天：结束于 11月1日零点前 1 毫秒
        assertEquals(at(2026, 11, 1, 0, 0).toEpochMilli() - 1, r.to)
    }

    @Test
    fun `区间是闭区间 月末当天最后一毫秒要包含`() {
        val r = LedgerRange.monthOf(2026, 10, sh)
        val lastMs = at(2026, 10, 31, 23, 59).toEpochMilli()
        assertTrue("月末最后一秒必须落在区间内", lastMs in r.from..r.to)
        val nextMonthFirst = at(2026, 11, 1, 0, 0).toEpochMilli()
        assertTrue("下月一号必须落在区间外", nextMonthFirst > r.to)
    }

    @Test
    fun `2月按实际天数收尾 平年28天`() {
        val r = LedgerRange.monthOf(2026, 2, sh)   // 2026 不是闰年
        assertEquals(at(2026, 3, 1, 0, 0).toEpochMilli() - 1, r.to)
        assertTrue(at(2026, 2, 28, 23, 59).toEpochMilli() in r.from..r.to)
        assertTrue(at(2026, 3, 1, 0, 0).toEpochMilli() > r.to)
    }

    @Test
    fun `闰年2月29日要被包含`() {
        val r = LedgerRange.monthOf(2028, 2, sh)   // 闰年
        assertTrue("2028-02-29 必须在区间内", at(2028, 2, 29, 23, 59).toEpochMilli() in r.from..r.to)
        assertEquals(at(2028, 3, 1, 0, 0).toEpochMilli() - 1, r.to)
    }

    @Test
    fun `本周从周一开始`() {
        // 2026-10-04 是周日 → 本周一应是 2026-10-05?? 不对，先确认：
        // 用 2026-10-07（周三）来测更直观
        val r = LedgerRange.thisWeek(at(2026, 10, 7, 10, 0), sh)
        assertEquals(at(2026, 10, 5, 0, 0).toEpochMilli(), r.from)   // 周一
        assertEquals(at(2026, 10, 12, 0, 0).toEpochMilli() - 1, r.to)  // 周日结束
    }

    @Test
    fun `周日归属上一个周一开始的周`() {
        // 2026-10-04 若为周日，则本周起点应是 2026-09-28
        val sunday = at(2026, 10, 4, 22, 0)
        val r = LedgerRange.thisWeek(sunday, sh)
        assertEquals(at(2026, 9, 28, 0, 0).toEpochMilli(), r.from)
    }

    @Test
    fun `今天区间只覆盖当天`() {
        val r = LedgerRange.today(at(2026, 10, 4, 15, 30), sh)
        assertEquals(at(2026, 10, 4, 0, 0).toEpochMilli(), r.from)
        assertEquals(at(2026, 10, 5, 0, 0).toEpochMilli() - 1, r.to)
        assertTrue(at(2026, 10, 3, 23, 0).toEpochMilli() < r.from)
        assertTrue(at(2026, 10, 5, 1, 0).toEpochMilli() > r.to)
    }

    @Test
    fun `最近N天含今天`() {
        val r = LedgerRange.lastDays(7, at(2026, 10, 4, 12, 0), sh)
        assertEquals("7 天窗口应从 6 天前开始", at(2026, 9, 28, 0, 0).toEpochMilli(), r.from)
        assertEquals(at(2026, 10, 5, 0, 0).toEpochMilli() - 1, r.to)
    }

    @Test
    fun `跨月边界 本月不包含上月任何一秒`() {
        val r = LedgerRange.thisMonth(at(2026, 3, 1, 0, 30), sh)
        assertTrue(at(2026, 2, 28, 23, 59).toEpochMilli() < r.from)
        assertTrue(at(2026, 3, 1, 0, 0).toEpochMilli() in r.from..r.to)
    }

    @Test
    fun `跨年边界`() {
        val r = LedgerRange.thisMonth(at(2026, 1, 1, 8, 0), sh)
        assertEquals(at(2026, 1, 1, 0, 0).toEpochMilli(), r.from)
        assertEquals(at(2026, 2, 1, 0, 0).toEpochMilli() - 1, r.to)
    }

    @Test
    fun `区间永远非空且from不大于to`() {
        listOf(
            at(2026, 10, 15), at(2026, 1, 1), at(2026, 2, 28), at(2026, 12, 31),
        ).forEach { now ->
            listOf(
                LedgerRange.thisMonth(now, sh),
                LedgerRange.thisWeek(now, sh),
                LedgerRange.today(now, sh),
                LedgerRange.lastDays(30, now, sh),
            ).forEach { r ->
                assertTrue("区间不能倒挂", r.from <= r.to)
                assertTrue("区间不能为空", !r.isEmpty)
            }
        }
    }
}