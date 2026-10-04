package com.shouzhe.app.domain.parse

/**
 * 金额解析 —— 元文本 → 分（v0.8.0 账目编辑用）。
 *
 * 为什么单独一个类、为什么用 BigDecimal 而不是 Double：
 * - 铁律 3：金额一律 `Long` 存「分」，绝不用 Float/Double。
 *   `Double` 算 `38.5 * 100` 会得到 3849.9999…，四舍五入才凑对；
 *   循环累加更是灾难。BigDecimal 是十进制的，"38.5" 精确表示。
 * - 用户手输的是**字符串**（"38"、"38.5"、"38.50"、"1,280"、"¥38.5"），
 *   必须能容忍各种写法，同时拒绝含糊的东西。
 */
object MoneyParser {

    /** 解析结果：成功给 cents，失败给一句给用户看的话 */
    data class Result(val cents: Long, val error: String? = null) {
        val ok: Boolean get() = error == null
    }

    /**
     * 元文本 → 分。
     *
     * 支持："38"、"38.5"、"38.50"、".5"、"1,280"、"¥38.5"、"￥38.5"、" 38.5 元"
     * 拒绝：空、负数、非数字、超过两位小数（分以下没法表示）
     */
    fun parseYuanToCents(input: String): Result {
        var s = input.trim()
        if (s.isBlank()) return Result(0, "金额不能为空")

        // 去掉货币符号、单位、常见修饰
        s = s.replace("¥", "")
            .replace("￥", "")
            .replace("元", "")
            .replace("RMB", "", ignoreCase = true)
            .replace("CNY", "", ignoreCase = true)
            .replace(",", "")
            .replace("，", "")
            .trim()
        // 开头负号：记账方向是另一个字段，这里不猜用户意图
        if (s.startsWith("-")) return Result(0, "金额填正数，支出还是收入用下面的选项选")
        if (s.isBlank()) return Result(0, "金额不能为空")

        // 只允许数字和一个小数点
        if (!s.matches(Regex("\\d*\\.?\\d*"))) {
            return Result(0, "金额只能是数字，比如 38 或 38.5")
        }
        if (s == "." ) return Result(0, "金额填个数字，比如 38.5")

        // 归一化：.5 → 0.5
        if (s.startsWith(".")) s = "0$s"

        val parts = s.split(".")
        val yuan = parts[0].ifBlank { "0" }
        val fraction = parts.getOrNull(1) ?: ""

        if (fraction.length > 2) {
            return Result(0, "金额最多两位小数（分），比如 38.50")
        }

        return try {
            // 直接拼成"分"再解析，全程不经过浮点
            val centsText = yuan + fraction.padEnd(2, '0')
            val cents = centsText.toLong()
            if (cents <= 0L) Result(0, "金额要大于 0")
            else if (cents > MAX_CENTS) Result(0, "金额太大了")
            else Result(cents)
        } catch (e: NumberFormatException) {
            Result(0, "金额只能是数字，比如 38 或 38.5")
        }
    }

    /** 分 → 展示用的元文本（两位小数，无货币符号） */
    fun centsToYuanText(cents: Long): String =
        "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    /** 单笔上限：1 亿元。防止手滑输入一串 9 把 Long 撑爆 */
    private const val MAX_CENTS = 100_000_000L * 100
}