package com.shouzhe.app.model.gateway

import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection
import java.time.Instant

/**
 * 模型网关 —— 唯一的外部依赖抽象（见 API.md §1）。
 *
 * 设计约束：
 * - 不抛异常，一律返回 Outcome
 * - 支持多供应商（OpenAI 兼容协议）
 * - 结构化输出三层回退（json_schema → function calling → 容错解析）
 */
interface ModelGateway {

    /** 一句话 → 结构化意图判定 */
    suspend fun classify(input: String, now: Instant): Outcome<ClassifyResult>

    /** 长文本 → 摘要 */
    suspend fun summarize(content: String, maxSentences: Int = 4): Outcome<String>

    /** 内容 → 标签 */
    suspend fun tag(content: String, existing: List<String> = emptyList()): Outcome<List<String>>

    /**
     * 截图记账 —— 图片 → 账单字段（v0.7.0）。
     *
     * 约定：
     * - 传的是**已经压缩好的 base64**（不含 data: 前缀），编码在 platform 层做，接口保持纯字符串好测
     * - 不是账单 / 认不出金额时也要返回 Ok，用 isBill=false / amountYuan=null 表达，
     *   调用方据此降级成"待整理截图"，**绝不因为识别失败就丢掉用户的图片**
     */
    suspend fun recognizeReceipt(
        imageBase64: String,
        mimeType: String,
        now: Instant,
    ): Outcome<ReceiptResult>

    /** 连通性自检（设置页"测试连接"按钮） */
    suspend fun ping(): Outcome<ProviderInfo>
}

/** 解析结果 —— 模型只负责判断类型与抽取字段，时间换算交给本地 */
data class ClassifyResult(
    val type: ItemType,
    /** 0.0–1.0，低于 0.7 时 UI 必须让用户确认 */
    val confidence: Double,
    val title: String,
    /** 时间表达式的原文（如"明天下午三点"），由本地 TimeParser 换算 */
    val timeExpression: String? = null,
    val remindExpression: String? = null,
    val ledger: LedgerDraft? = null,
    val noteText: String? = null,
) {
    val needsConfirmation: Boolean get() = confidence < 0.7
}

data class LedgerDraft(
    /** 单位：元。入库时 ×100 转成分 */
    val amountYuan: Double,
    val direction: LedgerDirection,
    val category: String,
    val merchant: String? = null,
) {
    val amountCents: Long get() = Math.round(amountYuan * 100)
}

data class ProviderInfo(
    val name: String,
    val model: String,
    val supportsJsonSchema: Boolean,
    val supportsFunctionCalling: Boolean,
)

/**
 * 截图识别的结果（v0.7.0）。
 *
 * 关键设计：
 * - `isBill = false` 或 `amountYuan = null` **不是错误**，是一条合法的"没认出来"结论，
 *   调用方据此把图片降级存成"待整理截图"，用户的东西一点不丢
 * - `occurredAt` 由模型给绝对日期（账单上本来就是绝对日期）；解析不了就是 null，
 *   由调用方降级成当前时间，并在界面上如实告知
 */
data class ReceiptResult(
    /** 这张图是不是账单/小票/支付截图 */
    val isBill: Boolean,
    /** 0.0–1.0，低于 0.7 时 UI 必须让用户确认 */
    val confidence: Double,
    val title: String,
    /** 单位：元。null = 没认出来。入库时 ×100 转分（绝不用浮点存） */
    val amountYuan: Double?,
    val direction: LedgerDirection,
    val category: String,
    val merchant: String? = null,
    /** 账单上的发生时间；null = 没认出来或格式不对 */
    val occurredAt: Instant? = null,
    /** 模型读到的关键文字，仅用于排查与人工核对 */
    val excerpt: String? = null,
) {
    /**
     * 能不能生成记账草稿：认出来 + 金额有效。
     *
     * 注意**不拿 confidence 卡门槛**：草稿本来就是"待确认"状态，
     * 用户看见金额不对可以不确认或删掉。让低把握的结果也走到用户眼前，
     * 比我们自己替用户决定"这笔记不了"更符合这个 App 的信任铁律。
     */
    val usableAsLedger: Boolean
        get() = isBill && amountYuan != null && amountYuan > 0.0

    /** 把握够不够高：不够时界面要更醒目地提示"请核对金额" */
    val confident: Boolean
        get() = confidence >= MIN_CONFIDENCE

    /** 入库用金额（分）；认不出时给 0，绝不返回 null 让上层自己算 */
    val amountCents: Long
        get() = amountYuan?.let { Math.round(it * 100) } ?: 0L

    companion object {
        /** 低于此值时界面提示"不太确定，请核对" */
        const val MIN_CONFIDENCE = 0.7
    }
}