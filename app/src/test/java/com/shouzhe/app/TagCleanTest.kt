package com.shouzhe.app

import com.shouzhe.app.data.repository.ItemRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签清洗（v0.10.0）。
 *
 * 模型输出不可全信 —— 它会加 `#`、加编号、加引号、写超长句子、重复同一个词。
 * 脏标签会污染标签字典（`tag` 表是全库共享的），所以必须收拾干净。
 */
class TagCleanTest {

    private fun clean(vararg s: String) = ItemRepository.cleanTags(s.toList())

    @Test
    fun `正常标签原样保留`() {
        assertEquals(listOf("工作", "效率", "阅读"), clean("工作", "效率", "阅读"))
    }

    @Test
    fun `去掉井号和前缀横线`() {
        assertEquals(listOf("工作", "效率"), clean("#工作", "-效率", "##阅读").take(2))
        assertEquals(listOf("阅读"), clean("#阅读"))
    }

    @Test
    fun `去掉首尾空格`() {
        assertEquals(listOf("工作"), clean("  工作  "))
    }

    @Test
    fun `去掉各种引号与标点`() {
        assertEquals(listOf("工作"), clean("\"工作\""))
        assertEquals(listOf("工作"), clean("'工作'"))
        assertEquals(listOf("工作"), clean("「工作」"))
        assertEquals(listOf("工作"), clean("工作，"))
    }

    @Test
    fun `去重`() {
        assertEquals(listOf("工作", "效率"), clean("工作", "效率", "工作", " 工作 "))
    }

    @Test
    fun `空白标签被丢掉`() {
        assertEquals(listOf("工作"), clean("", "   ", "工作", "\t"))
    }

    @Test
    fun `超过12个字的长句子被丢掉`() {
        // 模型偶尔会把一句话当标签返回
        val long = "这是一句非常长的根本不是标签的话"
        assertTrue(long.length > 12)
        assertEquals(emptyList<String>(), clean(long))
        assertEquals(listOf("短标签"), clean(long, "短标签"))
    }

    @Test
    fun `最多保留5个`() {
        val r = clean("一", "二", "三", "四", "五", "六", "七")
        assertEquals(5, r.size)
        assertEquals(listOf("一", "二", "三", "四", "五"), r)
    }

    @Test
    fun `清洗后变空的要丢掉 但保留顺序`() {
        // "#" 去掉井号后是空的
        assertEquals(listOf("甲", "乙"), clean("甲", "#", "乙"))
    }

    @Test
    fun `全是脏输入时返回空列表而不是崩`() {
        assertEquals(emptyList<String>(), clean())
        assertEquals(emptyList<String>(), clean("", "  ", "#", "，，"))
    }
}