package com.shouzhe.app.domain.parse

import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection

/**
 * 规则解析引擎 —— 不依赖任何模型，纯本地正则 + 关键词。
 *
 * 为什么要有它（参考家账簿 HomeLedger 的设计）：
 * 1. 没配 API Key 时 App 也能用（BYOK 转化漏斗的"先尝到甜头"）
 * 2. 模型失败时的兜底（网络不通 / 余额不足 / 输出解析失败）
 * 3. 纯离线、零成本、毫秒级
 *
 * 解析优先级：拆句 → 每句独立判定（金额→账目 / 时间+动词→待办 / 其他→笔记）
 */
object RuleParser {

    /** 常见消费类目关键词 → 分类 */
    private val CATEGORY_WORDS: List<Pair<String, String>> = listOf(
        "奶茶" to "餐饮", "咖啡" to "餐饮", "烧饼" to "餐饮", "外卖" to "餐饮",
        "宵夜" to "餐饮", "烧烤" to "餐饮", "火锅" to "餐饮", "早餐" to "餐饮",
        "晚饭" to "餐饮", "午饭" to "餐饮", "早饭" to "餐饮", "午餐" to "餐饮",
        "打车" to "交通", "出租" to "交通", "地铁" to "交通", "公交" to "交通",
        "加油" to "交通", "停车" to "交通", "高铁" to "交通", "火车" to "交通",
        "机票" to "交通", "车费" to "交通",
        "话费" to "通讯", "流量" to "通讯",
        "药" to "医疗", "看病" to "医疗", "挂号" to "医疗",
        "房租" to "住房", "水费" to "住房", "电费" to "住房", "燃气" to "住房", "物业" to "住房",
        "电影" to "娱乐", "游戏" to "娱乐", "KTV" to "娱乐", "酒吧" to "娱乐",
        "超市" to "日用", "买纸" to "日用", "洗衣" to "日用", "快递" to "日用",
        "书" to "学习", "课" to "学习", "培训" to "学习",
    )

    /** 收入关键词 */
    private val INCOME_WORDS = listOf(
        "工资", "收到", "进账", "报销", "退款", "红包", "收入", "奖金", "利息", "返现",
    )

    /** 待办动词 —— 只用双字以上或语义明确的词，单字"去/买"误伤率太高（已由单测抓出） */
    private val TODO_WORDS = listOf(
        "提醒我", "记得", "别忘", "开会", "交房租", "缴费", "检查", "买", "还", "交",
        "取", "送", "接", "约", "见",
    )

    /** 时间词 —— 判定待办用 */
    private val TIME_HINTS = listOf(
        "今天", "明天", "后天", "下周", "这周", "本周", "周一", "周二", "周三", "周四",
        "周五", "周六", "周日", "礼拜", "早上", "上午", "中午", "下午", "晚上", "今晚",
        "分钟后", "小时后", "天后", "点",
    )

    /**
     * 金额正则（优先级从高到低）：
     * 1. ¥38.5   —— 货币符号前缀
     * 2. 38块5   —— 块X角式（必须先于"38块"匹配，否则丢角位）
     * 3. 38元    —— 元后缀
     * 4. 88      —— 裸数字（前后不能是数字/小数点；时间上下文在判定阶段排除）
     */
    private val AMOUNT_REGEX = Regex(
        """(?:[¥￥]\s*(\d+(?:\.\d+)?))""" +
            """|((?:\d+(?:\.\d+)?)\s*块\s*(?:[一两二三四五六七八九\d]+)?(?:钱)?)""" +
            """|((?:\d+(?:\.\d+)?)\s*元(?:钱)?)""" +
            """|(?<![.\d])(\d+(?:\.\d+)?)(?![.\d])"""
    )

    /** 裸数字命中如果紧跟时间单位，视为时间表达而非金额 */
    private val TIME_CONTEXT_AFTER = Regex("""^\s*(?:月|日|号|点|分|时|:|：|年)""")

    /**
     * 解析入口。返回多笔结果（哪怕只有一条）。
     */
    fun parse(input: String): ParseResult {
        val segments = splitSegments(input.trim())
        val entries = segments.mapNotNull { parseSegment(it) }
        return ParseResult(
            entries = entries.ifEmpty {
                // 什么都没识别出来 → 一条笔记，绝不丢内容
                listOf(
                    ParsedEntry(
                        type = ItemType.NOTE,
                        confidence = 0.6,
                        title = input.take(40),
                        noteText = input,
                    )
                )
            },
            source = ParseSource.RULES,
        )
    }

    /**
     * 拆句：显式分隔符优先；无分隔符但含多个金额时按金额边界切。
     */
    private fun splitSegments(input: String): List<String> {
        val s = input.trim()
        if (s.isEmpty()) return emptyList()

        // 1) 显式分隔符
        val byDelim = s.split(Regex("[，,。；;]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (byDelim.size > 1) return byDelim

        // 2) 无分隔符但含多个金额 → 按金额边界切
        val amounts = validAmountMatches(s)
        if (amounts.size > 1) {
            val out = mutableListOf<String>()
            var cursor = 0
            for (m in amounts) {
                // 段落起点：金额起点往前最多 6 字（把"晚饭""打车"等前缀包进来）
                val segStart = maxOf(cursor, (m.range.first - 6).coerceAtLeast(0))
                val segEnd = m.range.last + 1
                if (segEnd > segStart) {
                    out.add(s.substring(segStart, segEnd).trim())
                    cursor = segEnd
                }
            }
            // 收尾文字并入最后一段
            if (cursor < s.length) {
                val tail = s.substring(cursor).trim()
                if (tail.isNotBlank() && out.isNotEmpty()) {
                    out[out.size - 1] = out.last() + tail
                }
            }
            if (out.size > 1) return out
        }

        return listOf(s)
    }

    /** 找出所有"有效"金额命中（剔除时间上下文的裸数字） */
    private fun validAmountMatches(s: String): List<MatchResult> =
        AMOUNT_REGEX.findAll(s).filter { m ->
            if (m.groupValues[4].isNotBlank()) {
                // 裸数字：看后面是否紧跟时间单位
                val after = s.substring(m.range.last + 1)
                !TIME_CONTEXT_AFTER.containsMatchIn(after.take(2))
            } else true
        }.toList()

    private fun parseSegment(seg: String): ParsedEntry? {
        if (seg.isBlank()) return null

        // ---- 1) 金额 → 账目 ----
        val amountMatch = validAmountMatches(seg).firstOrNull()
        if (amountMatch != null) {
            val amount = normalizeAmount(amountMatch)
            if (amount != null && amount > 0) {
                val isIn = INCOME_WORDS.any { seg.contains(it) }
                return ParsedEntry(
                    type = ItemType.LEDGER,
                    confidence = 0.75,   // 规则解析给中等置信度，走确认流程
                    title = seg.take(20),
                    ledger = LedgerDraftParsed(
                        amountYuan = amount,
                        direction = if (isIn) LedgerDirection.IN else LedgerDirection.OUT,
                        category = guessCategory(seg),
                        merchant = guessMerchant(seg),
                    ),
                    noteText = seg,
                )
            }
        }

        // ---- 2) 时间 + 动词 → 待办 ----
        val hasTime = TIME_HINTS.any { seg.contains(it) }
        val hasTodoVerb = TODO_WORDS.any { seg.contains(it) }
        if (hasTime && hasTodoVerb) {
            val timeExpr = extractTimeExpression(seg)
            return ParsedEntry(
                type = ItemType.TODO,
                confidence = 0.8,
                title = cleanTodoTitle(seg),
                timeExpression = timeExpr,
                remindExpression = timeExpr,
                noteText = seg,
            )
        }

        // ---- 3) 独立时间短语（很短且就是时间）→ 待办 ----
        //     "明天下午三点" 12 字以内，且去掉时间词后没有实质内容
        if (hasTime) {
            val expr = extractTimeExpression(seg)
            val residue = seg.replace(expr.orEmpty(), "").trim()
            if (residue.length <= 2) {
                return ParsedEntry(
                    type = ItemType.TODO,
                    confidence = 0.7,
                    title = seg.take(30),
                    timeExpression = expr,
                    remindExpression = expr,
                    noteText = seg,
                )
            }
        }

        // ---- 4) 其他 → 笔记 ----
        return ParsedEntry(
            type = ItemType.NOTE,
            confidence = 0.6,
            title = seg.take(40),
            noteText = seg,
        )
    }

    // ------------------------------------------------------------------
    // 金额归一化
    // ------------------------------------------------------------------

    private fun normalizeAmount(m: MatchResult): Double? {
        // group 顺序与 AMOUNT_REGEX 一致
        val g1 = m.groupValues[1]   // ¥ 前缀
        if (g1.isNotBlank()) return g1.toDoubleOrNull()

        val g2 = m.groupValues[2]   // 38块5 / 38块
        if (g2.isNotBlank()) {
            val mm = Regex("""(\d+(?:\.\d+)?)\s*块\s*([一两二三四五六七八九\d]*)""").find(g2)
                ?: return null
            val yuan = mm.groupValues[1].toDoubleOrNull() ?: return null
            val jiao = mm.groupValues[2]
            if (jiao.isBlank()) return yuan
            // 口语约定："1块5"=1.5元、"38块5"=38.5元 —— 数字角位除以 10
            val jiaoVal = when (jiao) {
                "一" -> 0.1; "两", "二" -> 0.2; "三" -> 0.3; "四" -> 0.4; "五" -> 0.5
                "六" -> 0.6; "七" -> 0.7; "八" -> 0.8; "九" -> 0.9
                else -> (jiao.toDoubleOrNull() ?: 0.0) / 10.0
            }
            return yuan + jiaoVal
        }

        val g3 = m.groupValues[3]   // 38元
        if (g3.isNotBlank()) {
            return Regex("""\d+(?:\.\d+)?""").find(g3)?.value?.toDoubleOrNull()
        }

        val g4 = m.groupValues[4]   // 裸数字
        if (g4.isNotBlank()) return g4.toDoubleOrNull()

        return null
    }

    private fun guessCategory(seg: String): String {
        CATEGORY_WORDS.forEach { (word, cat) ->
            if (seg.contains(word)) return cat
        }
        return "其他"
    }

    /** 商家猜测：去掉金额后剩余核心词，太短不猜 */
    private fun guessMerchant(seg: String): String? {
        val cleaned = seg
            .replace(AMOUNT_REGEX, "")
            .replace(Regex("""[记一笔花了花买付给在打]"""), "")
            .trim()
        return cleaned.takeIf { it.length in 2..10 }
    }

    // ------------------------------------------------------------------
    // 时间
    // ------------------------------------------------------------------

    /** 从句子里抽出时间表达（供 TimeParser 二次换算） */
    private fun extractTimeExpression(seg: String): String? {
        val patterns = listOf(
            Regex("""[明今]\天?[早中晚]?[上下]?[午晚]?[一两二三四五六七八九十\d]*[点:：时]\s*[半一二三四五六七八九十\d]*分?"""),
            Regex("""\d+[分钟小时天]后"""),
            Regex("""(下|本|这)?+(周|星期|礼拜)[一二三四五六日天]"""),
            Regex("""\d{1,2}[:：]\d{1,2}"""),
            Regex("""\d{1,2}月\d{1,2}[日号]?"""),
            Regex("""[明今后]天|[明今]早|[明今]晚|今天|明天|后天"""),
        )
        patterns.forEach { p ->
            p.find(seg)?.let { return it.value }
        }
        return TIME_HINTS.firstOrNull { seg.contains(it) }
    }

    /** 待办标题清洗：去掉"提醒我""记得"这类前缀动词 */
    private fun cleanTodoTitle(seg: String): String {
        var s = seg
        listOf("提醒我", "记得", "别忘记", "别忘了", "帮我").forEach {
            s = s.replace(it, "")
        }
        return s.trim().take(30).ifBlank { seg.take(30) }
    }
}