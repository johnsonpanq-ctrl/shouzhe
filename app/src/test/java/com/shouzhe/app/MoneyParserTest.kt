package com.shouzhe.app

import com.shouzhe.app.domain.parse.MoneyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 金额解析（v0.8.0 账目编辑）。
 *
 * 这组测试守的是铁律 3：一分钱都不能错。
 * 特别要盯住 Double 的经典翻车 —— 38.5 * 100 在二进制浮点里是 3849.9999…
 */
class MoneyParserTest {

    private fun cents(s: String) = MoneyParser.parseYuanToCents(s)

    @Test
    fun `整数元`() {
        assertEquals(3800L, cents("38").cents)
        assertEquals(100L, cents("1").cents)
        assertEquals(1000000L, cents("10000").cents)
    }

    @Test
    fun `一位小数`() {
        assertEquals(3850L, cents("38.5").cents)
        assertEquals(1010L, cents("10.1").cents)
    }

    @Test
    fun `两位小数`() {
        assertEquals(3850L, cents("38.50").cents)
        assertEquals(3801L, cents("38.01").cents)
        assertEquals(1L, cents("0.01").cents)
    }

    @Test
    fun `没有整数部分的小数`() {
        assertEquals(50L, cents(".5").cents)
        assertEquals(5L, cents(".05").cents)
    }

    @Test
    fun `浮点陷阱 —— 38点5 必须是 3850 分而不是 3849`() {
        // Double 会算成 3849.9999...；这里必须精确
        assertEquals(3850L, cents("38.5").cents)
        assertEquals(7007L, cents("70.07").cents)
        assertEquals(29151L, cents("291.51").cents)
        assertEquals(8L, cents("0.08").cents)
    }

    @Test
    fun `带货币符号和单位`() {
        assertEquals(3850L, cents("¥38.5").cents)
        assertEquals(3850L, cents("￥38.50").cents)
        assertEquals(3850L, cents("38.5元").cents)
        assertEquals(3850L, cents(" 38.5 ").cents)
        assertEquals(3850L, cents("RMB 38.5").cents)
    }

    @Test
    fun `带千分位`() {
        assertEquals(128000L, cents("1,280").cents)
        assertEquals(128050L, cents("1,280.50").cents)
        assertEquals(128050L, cents("1，280.50").cents)
    }

    @Test
    fun `空输入被拒绝`() {
        assertFalse(cents("").ok)
        assertFalse(cents("   ").ok)
        assertFalse(cents(".").ok)
        assertFalse(cents("¥").ok)
    }

    @Test
    fun `零和负数被拒绝`() {
        assertFalse(cents("0").ok)
        assertFalse(cents("0.00").ok)
        assertFalse(cents("-38.5").ok)
    }

    @Test
    fun `非数字被拒绝`() {
        assertFalse(cents("abc").ok)
        assertFalse(cents("38.5.2").ok)
        assertFalse(cents("3 8").ok)
        assertFalse(cents("38a").ok)
    }

    @Test
    fun `超过两位小数被拒绝`() {
        val r = cents("38.555")
        assertFalse(r.ok)
        assertTrue("要告诉用户为什么", r.error!!.contains("两位小数"))
    }

    @Test
    fun `超大金额被拒绝`() {
        assertFalse(cents("999999999999").ok)
    }

    @Test
    fun `错误提示是给用户看的中文`() {
        assertTrue(cents("abc").error!!.contains("数字"))
        assertTrue(cents("-1").error!!.contains("正数"))
        assertTrue(cents("").error!!.contains("空"))
    }

    @Test
    fun `分转元文本 保留两位小数`() {
        assertEquals("38.50", MoneyParser.centsToYuanText(3850))
        assertEquals("0.01", MoneyParser.centsToYuanText(1))
        assertEquals("0.00", MoneyParser.centsToYuanText(0))
        assertEquals("100.00", MoneyParser.centsToYuanText(10000))
    }

    @Test
    fun `元文本转分再转回来 要往返一致`() {
        listOf("0.01", "1.00", "38.50", "1280.50", "99999.99").forEach { y ->
            val c = cents(y).cents
            assertEquals("往返不一致：$y", y, MoneyParser.centsToYuanText(c))
        }
    }
}