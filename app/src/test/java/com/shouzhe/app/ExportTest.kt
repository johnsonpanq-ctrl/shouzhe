package com.shouzhe.app

import com.shouzhe.app.domain.model.Item
import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection
import com.shouzhe.app.domain.model.LedgerMeta
import com.shouzhe.app.domain.parse.CsvExport
import com.shouzhe.app.domain.parse.JsonExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 导出（v0.11.0）。
 *
 * 这是"数据不丢"的最后一道保险 —— 导出的文件坏了，
 * 比没有导出更糟：用户以为备份好了，真出事时才发现打不开。
 */
class ExportTest {

    private val sh = ZoneId.of("Asia/Shanghai")

    private fun ledgerItem(
        title: String,
        cents: Long,
        category: String,
        merchant: String? = null,
        confirmed: Boolean = true,
        summary: String? = null,
        at: Instant = LocalDate.of(2026, 10, 4).atTime(12, 30).atZone(sh).toInstant(),
    ) = Item(
        id = 1, uuid = "u-$title", type = ItemType.LEDGER, title = title,
        summary = summary,
        createdAt = at, updatedAt = at,
        ledger = LedgerMeta(
            amountCents = cents,
            direction = LedgerDirection.OUT,
            category = category,
            merchant = merchant,
            occurredAt = at,
            confirmed = confirmed,
        ),
    )

    // ---------------- CSV 转义 ----------------

    @Test
    fun `普通字段不加引号`() {
        assertEquals("餐饮", CsvExport.escape("餐饮"))
        assertEquals("星巴克", CsvExport.escape("星巴克"))
    }

    @Test
    fun `含逗号的字段要加引号`() {
        assertEquals("\"咖啡, 拿铁\"", CsvExport.escape("咖啡, 拿铁"))
    }

    @Test
    fun `含双引号的字段 引号要翻倍并整体加引号`() {
        assertEquals("\"他说\"\"你好\"\"\"", CsvExport.escape("他说\"你好\""))
    }

    @Test
    fun `含换行的字段要加引号`() {
        assertEquals("\"第一行\n第二行\"", CsvExport.escape("第一行\n第二行"))
    }

    @Test
    fun `空字段保持空`() {
        assertEquals("", CsvExport.escape(""))
    }

    // ---------------- CSV 内容 ----------------

    @Test
    fun `CSV 带 BOM 否则 Excel 打开中文乱码`() {
        val csv = CsvExport.ledgerToCsv(listOf(ledgerItem("午饭", 3855, "餐饮")), sh)
        assertTrue("必须以 UTF-8 BOM 开头", csv.startsWith("\uFEFF"))
    }

    @Test
    fun `CSV 表头正确`() {
        val csv = CsvExport.ledgerToCsv(emptyList(), sh)
        val firstLine = csv.removePrefix("\uFEFF").substringBefore("\r\n")
        assertEquals("日期,类型,金额,方向,分类,商家,状态,备注", firstLine)
    }

    @Test
    fun `金额导出为元 两位小数`() {
        val csv = CsvExport.ledgerToCsv(listOf(ledgerItem("午饭", 3855, "餐饮")), sh)
        assertTrue("3855 分应导出 38.55", csv.contains("38.55"))
        assertFalse("不该出现分", csv.contains("3855"))
    }

    @Test
    fun `待确认与已确认状态区分开`() {
        val csv1 = CsvExport.ledgerToCsv(listOf(ledgerItem("a", 100, "其他", confirmed = true)), sh)
        val csv2 = CsvExport.ledgerToCsv(listOf(ledgerItem("b", 100, "其他", confirmed = false)), sh)
        assertTrue(csv1.contains("已确认"))
        assertTrue(csv2.contains("待确认"))
    }

    @Test
    fun `非账目类型不出现在账本 CSV 里`() {
        val note = ledgerItem("午饭", 100, "餐饮").copy(type = ItemType.NOTE, ledger = null)
        val csv = CsvExport.ledgerToCsv(listOf(note), sh)
        val lines = csv.removePrefix("\uFEFF").trim().split("\r\n")
        assertEquals("只有表头", 1, lines.size)
    }

    @Test
    fun `商家含逗号不会撑破列`() {
        val csv = CsvExport.ledgerToCsv(
            listOf(ledgerItem("咖啡", 3000, "餐饮", merchant = "星巴克, 国贸店")), sh,
        )
        val dataLine = csv.removePrefix("\uFEFF").trim().split("\r\n")[1]
        // 逗号被引号包住，所以按顶层逗号切应该是 8 列
        assertEquals(8, splitCsvLine(dataLine).size)
        assertTrue(dataLine.contains("\"星巴克, 国贸店\""))
    }

    @Test
    fun `行尾是CRLF`() {
        val csv = CsvExport.ledgerToCsv(listOf(ledgerItem("午饭", 100, "餐饮")), sh)
        assertTrue(csv.contains("\r\n"))
    }

    // ---------------- JSON 转义 ----------------

    @Test
    fun `JSON 普通字符串`() {
        assertEquals("\"午饭\"", JsonExport.str("午饭"))
    }

    @Test
    fun `JSON 转义引号与反斜杠`() {
        assertEquals("\"他说\\\"你好\\\"\"", JsonExport.str("他说\"你好\""))
        assertEquals("\"路径C:\\\\x\"", JsonExport.str("路径C:\\x"))
    }

    @Test
    fun `JSON 转义换行与制表符`() {
        assertEquals("\"a\\nb\"", JsonExport.str("a\nb"))
        assertEquals("\"a\\tb\"", JsonExport.str("a\tb"))
        assertEquals("\"a\\rb\"", JsonExport.str("a\rb"))
    }

    @Test
    fun `JSON 控制字符转成 u 转义 否则产出非法 JSON`() {
        val s = JsonExport.str("a\u0001b")
        assertEquals("\"a\\u0001b\"", s)
    }

    @Test
    fun `null 输出为 JSON null 而不是字符串 null`() {
        assertEquals("null", JsonExport.strOrNull(null))
        assertEquals("\"null\"", JsonExport.strOrNull("null"))
    }

    // ---------------- JSON 备份结构 ----------------

    @Test
    fun `备份含格式版本与条目数`() {
        val json = JsonExport.backupToJson(listOf(ledgerItem("午饭", 3855, "餐饮")), appVersion = "0.11.0", zone = sh)
        assertTrue(json.contains("\"formatVersion\": 1"))
        assertTrue(json.contains("\"itemCount\": 1"))
        assertTrue(json.contains("\"appVersion\": \"0.11.0\""))
    }

    @Test
    fun `备份里的金额是分 不是元`() {
        val json = JsonExport.backupToJson(listOf(ledgerItem("午饭", 3855, "餐饮")), zone = sh)
        assertTrue("备份必须无损，存分", json.contains("\"amountCents\": 3855"))
    }

    @Test
    fun `空列表也能产出合法结构`() {
        val json = JsonExport.backupToJson(emptyList(), zone = sh)
        assertTrue(json.contains("\"items\": []"))
        assertTrue(json.contains("\"itemCount\": 0"))
    }

    @Test
    fun `确认状态被完整保留`() {
        val confirmed = JsonExport.backupToJson(listOf(ledgerItem("a", 100, "其他", confirmed = true)), zone = sh)
        val pending = JsonExport.backupToJson(listOf(ledgerItem("b", 100, "其他", confirmed = false)), zone = sh)
        assertTrue(confirmed.contains("\"confirmed\": true"))
        assertTrue(pending.contains("\"confirmed\": false"))
    }

    @Test
    fun `标题里的引号不会破坏 JSON 结构`() {
        val item = ledgerItem("他说\"这是午饭\"", 100, "餐饮")
        val json = JsonExport.backupToJson(listOf(item), zone = sh)
        assertTrue(json.contains("\\\"这是午饭\\\""))
        // 大括号配对检查：转义后不应多出未转义的引号
        val quotes = json.count { it == '"' }
        assertTrue("引号数应为偶数", quotes % 2 == 0)
    }

    // ---------------- 工具 ----------------

    /** 按 RFC4180 切一行（考虑引号内逗号），用于验证导出没撑破列 */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuote && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                c == '"' -> inQuote = !inQuote
                c == ',' && !inQuote -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}