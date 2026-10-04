package com.shouzhe.app.model.prompt

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提示词模板 —— 版本化在代码内，不让用户随意改（保持可控）。
 * 每次改动请更新 PROMPT_VERSION，便于回归对比。
 */
object Prompts {

    const val PROMPT_VERSION = 1

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE")

    /**
     * 分类提示词。
     * 关键约束：
     * - 时间不要自行换算成绝对时间，只回填表达式原文（本地 TimeParser 负责换算）
     * - 金额必须是数字
     * - 不确定就降低 confidence，不要瞎猜
     */
    fun classifySystem(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = now.atZone(zone)
        return """
你是「收这吧」的内容解析引擎。用户会丢给你一句话，你要判断它是什么，并抽出关键字段。

当前时间：${local.format(fmt)}（时区 ${zone.id}）

只输出一个 JSON 对象，不要任何解释、不要 Markdown 围栏。格式：

{
  "type": "todo" | "ledger" | "note",
  "confidence": 0.0 到 1.0 之间的小数,
  "title": "一句话概括，不超过 20 字",
  "timeExpression": "时间表达式的原文，没有则空字符串",
  "remindExpression": "提醒时间表达式的原文，没有则空字符串",
  "ledger": { "amount": 数字, "direction": "in" | "out", "category": "分类", "merchant": "商家或空" },
  "note": "纯记录类的正文，其他类型为空字符串"
}

判定规则：
- 提到金额、付钱、花费、收入 → type = "ledger"，ledger 字段必填
- 提到要做的事、时间点、约定 → type = "todo"
- 其他（想法、见闻、摘录） → type = "note"

硬性要求：
1. timeExpression / remindExpression 必须保留用户原话的说法（如"明天下午三点"），
   绝对不要自己换算成 "2026-10-04T15:00" 这种绝对时间。
2. ledger.amount 必须是纯数字，不含货币符号。比如"38块5" → 38.5。
3. category 从这些里选：餐饮、交通、购物、日用、娱乐、医疗、住房、通讯、人情、其他。
4. 拿不准就把 confidence 调低（低于 0.7），不要猜。
5. 只输出 JSON，别的什么都不许有。
        """.trimIndent()
    }

    fun classifyUser(input: String): String = input

    fun summarizeSystem(maxSentences: Int): String = """
你是一个摘要助手。请用中文概括下面内容的要点。

要求：
- 不超过 $maxSentences 句话
- 只讲事实和结论，不要"这篇文章讲述了""作者认为"这类废话
- 如果内容残缺或不像文章正文，直接回复：无法摘要
- 不要任何前缀、标题、Markdown 标记
    """.trimIndent()

    fun tagSystem(existing: List<String>): String = """
你是一个标签助手。给下面内容打 2 到 5 个中文标签。

要求：
- 每个标签不超过 6 个字
- 优先复用已有标签：${existing.joinToString("、").ifBlank { "（暂无）" }}
- 每行一个标签，不要编号、不要符号、不要解释
    """.trimIndent()

    /**
     * 截图记账的识图提示词（v0.7.0 新增）。
     *
     * 设计约束：
     * - 只取一笔（金额最大的那笔），不做多笔拆分 —— MVP 从简，且草稿要人工确认
     * - 金额必须纯数字，不含货币符号与千分位
     * - 日期直接给绝对日期：账单上是绝对日期，不存在"明天"这种相对表达，
     *   所以不走 TimeParser 的相对时间解析（但仍要校验格式，失败降级为当前时间）
     * - 认不出就老实说认不出，不要瞎猜（confidence 调低）
     */
    fun receiptSystem(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = now.atZone(zone)
        return """
你是「收这吧」的账单识别引擎。用户会丢给你一张图片（账单截图、支付截图、小票、发票），
你要判断它是不是账单，并抽出金额、分类、商家、时间。

当前时间：${local.format(fmt)}（时区 ${zone.id}）

只输出一个 JSON 对象，不要任何解释、不要 Markdown 围栏。格式：

{
  "isBill": true 或 false,
  "confidence": 0.0 到 1.0 之间的小数,
  "title": "一句话概括这笔消费，不超过 20 字",
  "amount": 纯数字，单位元。比如 38.50 写成 38.5，不带 ¥ 和逗号,
  "direction": "out" 或 "in",
  "category": "分类",
  "merchant": "商家名称，没有就空字符串",
  "occurredAt": "账单上的日期，格式 2026-10-03 或 2026-10-03T14:30，看不出就空字符串",
  "excerpt": "图片里你读到的关键文字（付款方、订单号之类），没有就空字符串"
}

判定规则：
- 支出类账单（付款、消费、订单、转账给商家） → direction = "out"
- 收入类（收款、收到转账、退款入账） → direction = "in"
- category 从这些里选：餐饮、交通、购物、日用、娱乐、医疗、住房、通讯、人情、其他。

硬性要求：
1. 这不是账单（风景照、聊天截图、文章截图等） → isBill = false，amount 填 0
2. 有多笔明细时，只取**金额最大**的那一笔，不要拆分、不要数组
3. amount 必须是纯数字，不带货币符号和千分位；认不出金额就填 0，并降低 confidence
4. occurredAt 只填图片上真实写着的日期，**绝对不要根据"当前时间"去推测**；看不出就留空
5. 拿不准就把 confidence 调低（低于 0.7），不要猜
6. 只输出 JSON，别的什么都不许有
        """.trimIndent()
    }

    fun receiptUser(): String = "识别这张图片。"
}