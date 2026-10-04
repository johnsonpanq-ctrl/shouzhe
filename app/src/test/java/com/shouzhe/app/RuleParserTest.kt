package com.shouzhe.app

import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection
import com.shouzhe.app.domain.parse.ParseSource
import com.shouzhe.app.domain.parse.RuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则解析引擎测试 —— 没配 API Key 时的主链路，必须稳。
 */
class RuleParserTest {

    @Test
    fun `简单金额 烧饼1块5`() {
        val r = RuleParser.parse("烧饼1块5")
        assertEquals(ParseSource.RULES, r.source)
        val e = r.entries.single()
        assertEquals(ItemType.LEDGER, e.type)
        assertNotNull(e.ledger)
        assertEquals(1.5, e.ledger!!.amountYuan, 0.001)
        assertEquals("餐饮", e.ledger!!.category)
        assertEquals(LedgerDirection.OUT, e.ledger!!.direction)
    }

    @Test
    fun `金额 奶茶22元`() {
        val r = RuleParser.parse("奶茶22元")
        val e = r.entries.single()
        assertEquals(ItemType.LEDGER, e.type)
        assertEquals(22.0, e.ledger!!.amountYuan, 0.001)
        assertEquals("餐饮", e.ledger!!.category)
    }

    @Test
    fun `金额 符号前缀`() {
        val r = RuleParser.parse("午饭 ¥38.5")
        val e = r.entries.single()
        assertEquals(ItemType.LEDGER, e.type)
        assertEquals(38.5, e.ledger!!.amountYuan, 0.001)
    }

    @Test
    fun `收入 报销350`() {
        val r = RuleParser.parse("报销350元")
        val e = r.entries.single()
        assertEquals(ItemType.LEDGER, e.type)
        assertEquals(350.0, e.ledger!!.amountYuan, 0.001)
        assertEquals(LedgerDirection.IN, e.ledger!!.direction)
    }

    @Test
    fun `一句话拆多笔 晚饭88打车26块5`() {
        val r = RuleParser.parse("晚饭88打车26.5")
        assertEquals(2, r.entries.size)
        assertTrue(r.entries.all { it.type == ItemType.LEDGER })
        assertEquals("餐饮", r.entries[0].ledger!!.category)
        assertEquals("交通", r.entries[1].ledger!!.category)
        assertEquals(88.0, r.entries[0].ledger!!.amountYuan, 0.001)
        assertEquals(26.5, r.entries[1].ledger!!.amountYuan, 0.001)
    }

    @Test
    fun `逗号分隔拆两笔`() {
        val r = RuleParser.parse("买纸15，奶茶18")
        assertEquals(2, r.entries.size)
        assertEquals("日用", r.entries[0].ledger!!.category)
        assertEquals("餐饮", r.entries[1].ledger!!.category)
    }

    @Test
    fun `待办 明天下午三点开会`() {
        val r = RuleParser.parse("明天下午三点开会")
        val e = r.entries.single()
        assertEquals(ItemType.TODO, e.type)
        assertNotNull(e.timeExpression)
        assertTrue(e.timeExpression!!.contains("明天"))
    }

    @Test
    fun `待办 记得买牛奶`() {
        val r = RuleParser.parse("记得明天买牛奶")
        val e = r.entries.single()
        assertEquals(ItemType.TODO, e.type)
        // 标题应清洗掉"记得"
        assertTrue(!e.title.startsWith("记得"))
    }

    @Test
    fun `纯文本成笔记`() {
        val r = RuleParser.parse("今天天气不错心情很好")
        val e = r.entries.single()
        assertEquals(ItemType.NOTE, e.type)
        assertEquals("今天天气不错心情很好", e.noteText)
    }

    @Test
    fun `无法解析也不丢内容`() {
        val r = RuleParser.parse("随便记点啥")
        assertEquals(1, r.entries.size)
        assertEquals(ItemType.NOTE, r.entries[0].type)
    }

    @Test
    fun `空输入给空结果兜底成笔记`() {
        val r = RuleParser.parse("")
        assertEquals(1, r.entries.size)
        assertEquals(ItemType.NOTE, r.entries[0].type)
    }

    @Test
    fun `规则解析置信度走确认流程`() {
        // 规则解析的账目 confidence < 0.85，必须走人工确认
        val r = RuleParser.parse("打车45")
        val e = r.entries.single()
        assertTrue(e.confidence < 0.85)
    }
}