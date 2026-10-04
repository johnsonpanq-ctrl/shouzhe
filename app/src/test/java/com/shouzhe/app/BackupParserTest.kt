package com.shouzhe.app

import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.ItemStatus
import com.shouzhe.app.domain.model.LedgerDirection
import com.shouzhe.app.domain.parse.BackupParser
import com.shouzhe.app.domain.parse.JsonExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份解析（v0.12.0）。
 *
 * 导入是**往用户唯一的数据库里写数据**，比导出危险得多。
 * 这组测试守两条：
 * 1. 正常的备份必须能完整读回来（含换行、引号、中文）
 * 2. 任何可疑的东西一律**拒绝**，绝不"尽力猜一个" —— 猜错就是往库里写垃圾
 */
class BackupParserTest {

    private fun wrap(items: String, version: Int = 1) = """
{
  "formatVersion": $version,
  "app": "收这吧",
  "appVersion": "0.11.0",
  "exportedAt": "2026-10-04T06:22:57.518547Z",
  "timezone": "Asia/Shanghai",
  "itemCount": 1,
  "items": [$items]
}
""".trimIndent()

    private val ledgerItem = """
    {
      "uuid": "u-1", "type": "LEDGER", "title": "午饭",
      "rawText": null, "summary": "报销用", "sourceUrl": null, "sourceApp": null,
      "status": "INBOX", "quality": null,
      "createdAt": "2026-10-04T06:19:54.126Z", "updatedAt": "2026-10-04T06:19:54.217Z",
      "tags": ["餐饮", "报销"],
      "ledger": {
        "amountCents": 3855, "direction": "OUT", "category": "餐饮",
        "merchant": "星巴克", "occurredAt": "2026-10-04T06:19:54.217Z", "confirmed": false
      }
    }""".trimIndent()

    // ---------------- 正常路径 ----------------

    @Test
    fun `能完整读回一条账目`() {
        val r = BackupParser.parse(wrap(ledgerItem))
        assertTrue(r is BackupParser.Result.Ok)
        val b = (r as BackupParser.Result.Ok).backup
        assertEquals(1, b.items.size)
        assertEquals("0.11.0", b.appVersion)

        val it = b.items[0]
        assertEquals("u-1", it.uuid)
        assertEquals(ItemType.LEDGER, it.type)
        assertEquals("午饭", it.title)
        assertEquals("报销用", it.summary)
        assertEquals(ItemStatus.INBOX, it.status)
        assertEquals(listOf("餐饮", "报销"), it.tags)

        val l = it.ledger!!
        // 金额必须是分，无损
        assertEquals(3855L, l.amountCents)
        assertEquals(LedgerDirection.OUT, l.direction)
        assertEquals("星巴克", l.merchant)
        assertEquals(false, l.confirmed)
    }

    @Test
    fun `null 字段读成 null 而不是字符串 null`() {
        val r = BackupParser.parse(wrap(ledgerItem)) as BackupParser.Result.Ok
        assertNull(r.backup.items[0].rawText)
        assertNull(r.backup.items[0].sourceUrl)
    }

    @Test
    fun `中文与换行 往返一致`() {
        val item = """
        {"uuid":"u-2","type":"NOTE","title":"标题里有\"引号\"","rawText":"第一行\n第二行",
         "summary":null,"sourceUrl":null,"sourceApp":null,"status":"INBOX","quality":null,
         "createdAt":"2026-10-04T06:00:00Z","updatedAt":"2026-10-04T06:00:00Z","tags":[]}
        """.trimIndent()
        val r = BackupParser.parse(wrap(item)) as BackupParser.Result.Ok
        assertEquals("标题里有\"引号\"", r.backup.items[0].title)
        assertEquals("第一行\n第二行", r.backup.items[0].rawText)
    }

    @Test
    fun `多条目`() {
        val two = "$ledgerItem,${ledgerItem.replace("u-1", "u-9")}"
        val r = BackupParser.parse(wrap(two)) as BackupParser.Result.Ok
        assertEquals(2, r.backup.items.size)
        assertEquals("u-9", r.backup.items[1].uuid)
    }

    @Test
    fun `待办也能读回`() {
        val todo = """
        {"uuid":"u-3","type":"TODO","title":"开会","rawText":null,"summary":null,
         "sourceUrl":null,"sourceApp":null,"status":"INBOX","quality":null,
         "createdAt":"2026-10-04T06:00:00Z","updatedAt":"2026-10-04T06:00:00Z","tags":[],
         "todo":{"dueAt":"2026-10-05T02:00:00Z","remindAt":"2026-10-05T01:50:00Z",
                 "remindState":"SCHEDULED","priority":2,"completedAt":null}}
        """.trimIndent()
        val r = BackupParser.parse(wrap(todo)) as BackupParser.Result.Ok
        val t = r.backup.items[0].todo!!
        assertEquals("SCHEDULED", t.remindState)
        assertEquals(2, t.priority)
        assertTrue(t.remindAt != null)
    }

    // ---------------- 拒绝路径 ----------------

    @Test
    fun `空文件被拒绝`() {
        assertTrue(BackupParser.parse("") is BackupParser.Result.Bad)
        assertTrue(BackupParser.parse("   ") is BackupParser.Result.Bad)
    }

    @Test
    fun `不是备份文件被拒绝`() {
        val r = BackupParser.parse("""{"hello":"world"}""")
        assertTrue(r is BackupParser.Result.Bad)
        assertTrue((r as BackupParser.Result.Bad).reason.contains("formatVersion"))
    }

    @Test
    fun `版本比自己新 明确拒绝而不是勉强读`() {
        val r = BackupParser.parse(wrap(ledgerItem, version = 99))
        assertTrue(r is BackupParser.Result.Bad)
        val reason = (r as BackupParser.Result.Bad).reason
        assertTrue("要告诉用户怎么办", reason.contains("升级"))
    }

    @Test
    fun `缺少 items 被拒绝`() {
        val r = BackupParser.parse("""{"formatVersion":1,"app":"收这吧"}""")
        assertTrue(r is BackupParser.Result.Bad)
    }

    @Test
    fun `缺 uuid 的条目被跳过 不污染数据库`() {
        val bad = """{"type":"NOTE","title":"没有uuid","status":"INBOX","tags":[]}"""
        val r = BackupParser.parse(wrap("$bad,$ledgerItem")) as BackupParser.Result.Ok
        assertEquals("只应读回合法的那条", 1, r.backup.items.size)
        assertEquals("u-1", r.backup.items[0].uuid)
    }

    @Test
    fun `未知 type 的条目被跳过`() {
        val bad = """{"uuid":"u-x","type":"ALIEN","title":"外星类型","tags":[]}"""
        val r = BackupParser.parse(wrap("$bad,$ledgerItem")) as BackupParser.Result.Ok
        assertEquals(1, r.backup.items.size)
    }

    @Test
    fun `账目缺金额Cents时 整条 ledger 丢弃而不是当成0元`() {
        // 金额缺失 → 宁可没有这笔账，也不能导进一笔 0 元的账
        val noAmount = """
        {"uuid":"u-4","type":"LEDGER","title":"金额缺失","status":"INBOX","tags":[],
         "ledger":{"direction":"OUT","category":"餐饮","confirmed":false}}
        """.trimIndent()
        val r = BackupParser.parse(wrap(noAmount)) as BackupParser.Result.Ok
        assertNull("必须丢弃，不能默认 0", r.backup.items[0].ledger)
    }

    @Test
    fun `全部条目都读不出来时 明确报损坏`() {
        val bad = """{"type":"NOTE","title":"没有uuid"}"""
        val r = BackupParser.parse(wrap(bad))
        assertTrue(r is BackupParser.Result.Bad)
        assertTrue((r as BackupParser.Result.Bad).reason.contains("损坏"))
    }

    // ---------------- 与自己导出的文件对拍 ----------------

    @Test
    fun `与 JsonExport 对拍 —— 自己导出的必须能自己读回`() {
        val item = com.shouzhe.app.domain.model.Item(
            id = 1, uuid = "round-trip-1", type = ItemType.LEDGER,
            title = "往返测试", rawText = null, summary = "含\"引号\"和\n换行",
            createdAt = java.time.Instant.parse("2026-10-04T06:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-10-04T06:00:00Z"),
            tags = listOf("标签一", "标签二"),
            ledger = com.shouzhe.app.domain.model.LedgerMeta(
                amountCents = 12345,
                direction = LedgerDirection.IN,
                category = "人情",
                merchant = "张三",
                occurredAt = java.time.Instant.parse("2026-10-04T05:00:00Z"),
                confirmed = true,
            ),
        )
        val json = JsonExport.backupToJson(listOf(item))
        val r = BackupParser.parse(json)
        assertTrue("自己导出的必须能自己读回", r is BackupParser.Result.Ok)

        val back = (r as BackupParser.Result.Ok).backup.items.single()
        assertEquals("round-trip-1", back.uuid)
        assertEquals("往返测试", back.title)
        assertEquals("含\"引号\"和\n换行", back.summary)
        assertEquals(listOf("标签一", "标签二"), back.tags)
        assertEquals(12345L, back.ledger!!.amountCents)
        assertEquals(LedgerDirection.IN, back.ledger.direction)
        assertEquals(true, back.ledger.confirmed)
    }

    @Test
    fun `小写 u 转义也能还原`() {
        assertEquals("a\u0001b", BackupParser.unescape("a\\u0001b"))
        assertEquals("中文", BackupParser.unescape("\\u4e2d\\u6587"))
    }
}