package com.shouzhe.app

import com.shouzhe.app.core.time.TimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 时间解析测试 —— 排期不许出错，这里必须 100% 覆盖。
 */
class TimeParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 固定"现在"：2026-10-03 10:00 周五 */
    private val now: Instant =
        ZonedDateTime.of(LocalDate.of(2026, 10, 3), LocalTime.of(10, 0), zone).toInstant()

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Instant =
        ZonedDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, min), zone).toInstant()

    @Test
    fun `明天下午三点 解析正确`() {
        val r = TimeParser.parse("明天下午三点", now, zone)
        assertEquals(at(2026, 10, 4, 15, 0), r)
    }

    @Test
    fun `明天 默认早上九点`() {
        val r = TimeParser.parse("明天去老丈人家", now, zone)
        assertEquals(at(2026, 10, 4, 9, 0), r)
    }

    @Test
    fun `后天`() {
        assertEquals(at(2026, 10, 5, 9, 0), TimeParser.parse("后天", now, zone))
    }

    @Test
    fun `晚上八点 转 20 点`() {
        assertEquals(at(2026, 10, 3, 20, 0), TimeParser.parse("今晚8点", now, zone))
    }

    /**
     * 回归测试：'今晚' 曾被当成早上 8 点，导致顺延到次日。
     * 根因是 PM_WORDS 里没有 '今晚'。这个 case 保证不再犯。
     */
    @Test
    fun `今晚 必须识别为下午时段`() {
        val r = TimeParser.parse("今晚8点", now, zone)
        assertEquals(at(2026, 10, 3, 20, 0), r)
        // 绝不能顺延到明天
        assertEquals(false, r == at(2026, 10, 4, 8, 0))
    }

    @Test
    fun `今夜 与 明晚`() {
        assertEquals(at(2026, 10, 3, 21, 0), TimeParser.parse("今夜9点", now, zone))
        assertEquals(at(2026, 10, 4, 19, 0), TimeParser.parse("明晚7点", now, zone))
    }

    @Test
    fun `今早 识别为上午`() {
        // 现在 10:00，'今早7点' 已过，但因为明确了日期，不应顺延
        assertEquals(at(2026, 10, 3, 7, 0), TimeParser.parse("今早7点", now, zone))
    }

    @Test
    fun `24小时制时间`() {
        assertEquals(at(2026, 10, 3, 15, 30), TimeParser.parse("今天15:30", now, zone))
    }

    @Test
    fun `点半`() {
        assertEquals(at(2026, 10, 4, 15, 30), TimeParser.parse("明天下午三点半", now, zone))
    }

    @Test
    fun `十点 中文数字`() {
        assertEquals(at(2026, 10, 3, 22, 0), TimeParser.parse("晚上十点", now, zone))
    }

    @Test
    fun `十二点 中文数字`() {
        assertEquals(at(2026, 10, 3, 12, 0), TimeParser.parse("中午十二点", now, zone))
    }

    @Test
    fun `下周三`() {
        // 2026-10-03 是周五，下周三 = 10-07
        assertEquals(at(2026, 10, 7, 9, 0), TimeParser.parse("下周三", now, zone))
    }

    @Test
    fun `绝对日期 月日`() {
        assertEquals(at(2026, 10, 5, 9, 0), TimeParser.parse("10月5日", now, zone))
    }

    @Test
    fun `绝对日期 短横线`() {
        assertEquals(at(2026, 11, 20, 9, 0), TimeParser.parse("2026-11-20", now, zone))
    }

    @Test
    fun `只有时间且已过 顺延到明天`() {
        // 现在是 10:00，说"早上8点" → 明天 8 点
        assertEquals(at(2026, 10, 4, 8, 0), TimeParser.parse("早上8点", now, zone))
    }

    @Test
    fun `无法解析返回 null 不瞎猜`() {
        assertNull(TimeParser.parse("随便说点什么", now, zone))
        assertNull(TimeParser.parse(null, now, zone))
        assertNull(TimeParser.parse("", now, zone))
    }

    @Test
    fun `相对时间描述`() {
        assertEquals("刚刚", TimeParser.humanize(now.minusSeconds(30), now, zone))
        assertEquals("2 分钟前", TimeParser.humanize(now.minusSeconds(120), now, zone))
        assertEquals("3 小时前", TimeParser.humanize(now.minusSeconds(3 * 3600), now, zone))
        assertEquals("昨天", TimeParser.humanize(now.minusSeconds(26 * 3600), now, zone))
    }
}