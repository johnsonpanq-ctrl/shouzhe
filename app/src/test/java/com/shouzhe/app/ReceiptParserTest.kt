package com.shouzhe.app

import com.shouzhe.app.domain.model.LedgerDirection
import com.shouzhe.app.model.schema.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 截图识别的解析测试（v0.7.0）。
 *
 * 覆盖的都是**真实会翻车**的情况：
 * 模型爱加 ```json 围栏、爱在 JSON 前后说废话、认不出金额时会硬填 0、
 * 日期格式五花八门。不测这些，等于没测。
 */
class ReceiptParserTest {

    private fun parse(raw: String) = ReceiptParser.parse(raw)

    @Test
    fun `正常账单 —— 抽金额商家分类`() {
        val r = parse(
            """
            {"isBill":true,"confidence":0.93,"title":"星巴克拿铁",
             "amount":38.5,"direction":"out","category":"餐饮",
             "merchant":"星巴克","occurredAt":"2026-10-03","excerpt":"订单号 8823"}
            """.trimIndent()
        )
        assertNotNull(r)
        requireNotNull(r)
        assertTrue(r.isBill)
        assertEquals(38.5, r.amountYuan!!, 0.0001)
        // 金额一律走「分」的整数，浮点只活在中间态
        assertEquals(3850L, r.amountCents)
        assertEquals(LedgerDirection.OUT, r.direction)
        assertEquals("餐饮", r.category)
        assertEquals("星巴克", r.merchant)
        assertEquals("订单号 8823", r.excerpt)
        assertTrue(r.usableAsLedger)
        assertTrue(r.confident)
    }

    @Test
    fun `带围栏和废话也能解析`() {
        val r = parse(
            """
            好的，我看了这张图：
            ```json
            {"isBill":true,"confidence":0.88,"title":"便利店","amount":12.0,
             "direction":"out","category":"日用","merchant":"全家","occurredAt":""}
            ```
            以上。
            """.trimIndent()
        )
        assertNotNull(r)
        requireNotNull(r)
        assertEquals(1200L, r.amountCents)
        assertEquals("全家", r.merchant)
    }

    @Test
    fun `收入方向认得出来`() {
        val r = parse(
            """{"isBill":true,"confidence":0.9,"title":"收到转账","amount":500,
               "direction":"in","category":"人情","merchant":"张三","occurredAt":""}"""
        )
        requireNotNull(r)
        assertEquals(LedgerDirection.IN, r.direction)
        assertEquals(50000L, r.amountCents)
    }

    @Test
    fun `不是账单 —— 不生成账目草稿`() {
        val r = parse(
            """{"isBill":false,"confidence":0.2,"title":"风景照","amount":0,
               "direction":"out","category":"其他","merchant":"","occurredAt":""}"""
        )
        requireNotNull(r)
        assertFalse(r.isBill)
        assertNull("认不出金额时必须是 null，绝不能变成 0 元账目", r.amountYuan)
        assertFalse("不是账单就不该能记账", r.usableAsLedger)
    }

    @Test
    fun `是账单但金额没认出来 —— 存图但不记账`() {
        val r = parse(
            """{"isBill":true,"confidence":0.4,"title":"看不清","amount":0,
               "direction":"out","category":"其他","merchant":"","occurredAt":""}"""
        )
        requireNotNull(r)
        assertTrue(r.isBill)
        assertNull(r.amountYuan)
        assertEquals(0L, r.amountCents)
        assertFalse(r.usableAsLedger)
    }

    @Test
    fun `把握不够也照样生成草稿 —— 让用户去核对而不是我们替用户决定`() {
        val r = parse(
            """{"isBill":true,"confidence":0.3,"title":"疑似消费","amount":66.0,
               "direction":"out","category":"购物","merchant":"","occurredAt":""}"""
        )
        requireNotNull(r)
        assertTrue("低把握也要到用户眼前，草稿本身就是待确认状态", r.usableAsLedger)
        assertFalse("但要提示不太确定", r.confident)
    }

    @Test
    fun `日期解析 —— 支持几种常见写法`() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026, 10, 3)

        listOf(
            "2026-10-03",
            "2026-10-03T14:30",
            "2026-10-03T14:30:00",
            "2026-10-03 14:30",
            "2026/10/03",
        ).forEach { raw ->
            val t = ReceiptParser.parseOccurredAt(raw, zone)
            assertNotNull("应该能解析：$raw", t)
            requireNotNull(t)
            val local = t.atZone(zone).toLocalDate()
            assertEquals("解析错日期：$raw", day, local)
        }
    }

    @Test
    fun `日期认不出来就返回 null —— 绝不用当前时间去蒙`() {
        assertNull(ReceiptParser.parseOccurredAt(""))
        assertNull(ReceiptParser.parseOccurredAt(null))
        assertNull(ReceiptParser.parseOccurredAt("昨天"))
        assertNull(ReceiptParser.parseOccurredAt("2026-13-45"))
    }

    @Test
    fun `模型输出不是 JSON 时返回 null 交给上层降级`() {
        assertNull(parse("我看了一下，这是一张风景照片。"))
        assertNull(parse(""))
    }

    @Test
    fun `缺字段的残缺 JSON 也能给出安全结果`() {
        // 只给了 isBill 和 amount，其它全缺
        val r = parse("""{"isBill":true,"amount":9.9}""")
        requireNotNull(r)
        assertEquals(990L, r.amountCents)
        assertEquals("其他", r.category)          // 分类兜底
        assertEquals(LedgerDirection.OUT, r.direction) // 方向兜底（支出）
        assertNull(r.merchant)
        assertTrue(r.title.isNotBlank())         // 标题兜底，不能空
    }
}