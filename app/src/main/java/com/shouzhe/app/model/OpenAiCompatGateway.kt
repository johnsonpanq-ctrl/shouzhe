package com.shouzhe.app.model

import com.shouzhe.app.core.result.AppError
import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.core.result.err
import com.shouzhe.app.core.result.ok
import com.shouzhe.app.data.prefs.SettingsStore
import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.model.LedgerDirection
import com.shouzhe.app.model.gateway.ClassifyResult
import com.shouzhe.app.model.gateway.LedgerDraft
import com.shouzhe.app.model.gateway.ModelGateway
import com.shouzhe.app.model.gateway.ProviderInfo
import com.shouzhe.app.model.gateway.ReceiptResult
import com.shouzhe.app.model.prompt.Prompts
import com.shouzhe.app.model.schema.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ModelGateway 实现 —— 统一走 OpenAI 兼容协议。
 *
 * 结构化输出三层回退（见 API.md §3）：
 *   1. response_format = json_object / json_schema
 *   2. 提示词强约束 + 容错解析
 *   3. 失败重试一次（降低温度）
 * 任何一层失败都不丢用户数据。
 */
@Singleton
class OpenAiCompatGateway @Inject constructor(
    private val settings: SettingsStore,
) : ModelGateway {

    override suspend fun classify(input: String, now: Instant): Outcome<ClassifyResult> =
        withContext(Dispatchers.IO) {
            val cfg = settings.modelConfig.first()
            if (!cfg.isConfigured) {
                return@withContext AppError.Auth("还没有配置 API Key").err()
            }
            val sys = Prompts.classifySystem(now)
            val user = Prompts.classifyUser(input)

            val json = when (val r = callJson(cfg.baseUrl, cfg.apiKey, cfg.modelName, sys, user)) {
                is Outcome.Err -> return@withContext r
                is Outcome.Ok -> r.value
            }

            val parsed = parseClassify(json)
                ?: return@withContext AppError.BadOutput(json).err()
            parsed.ok()
        }

    override suspend fun summarize(content: String, maxSentences: Int): Outcome<String> =
        withContext(Dispatchers.IO) {
            val cfg = settings.modelConfig.first()
            if (!cfg.isConfigured) return@withContext AppError.Auth("还没有配置 API Key").err()

            val sys = Prompts.summarizeSystem(maxSentences)
            val trimmed = content.take(MAX_SUMMARY_CHARS)
            when (val r = callText(cfg.baseUrl, cfg.apiKey, cfg.modelName, sys, trimmed)) {
                is Outcome.Err -> r
                is Outcome.Ok -> r.value.trim().ok()
            }
        }

    override suspend fun tag(content: String, existing: List<String>): Outcome<List<String>> =
        withContext(Dispatchers.IO) {
            val cfg = settings.modelConfig.first()
            if (!cfg.isConfigured) return@withContext AppError.Auth("还没有配置 API Key").err()

            val sys = Prompts.tagSystem(existing)
            val trimmed = content.take(MAX_TAG_CHARS)
            when (val r = callText(cfg.baseUrl, cfg.apiKey, cfg.modelName, sys, trimmed)) {
                is Outcome.Err -> r
                is Outcome.Ok -> parseTags(r.value).ok()
            }
        }

    override suspend fun ping(): Outcome<ProviderInfo> = withContext(Dispatchers.IO) {
        val cfg = settings.modelConfig.first()
        if (cfg.apiKey.isBlank()) return@withContext AppError.Auth("请先填写 API Key").err()
        val sys = "你是一个连通性测试助手。"
        val user = "回复两个字：正常"
        when (val r = callText(cfg.baseUrl, cfg.apiKey, cfg.modelName, sys, user)) {
            is Outcome.Err -> r
            is Outcome.Ok -> ProviderInfo(
                name = cfg.baseUrl,
                model = cfg.modelName,
                // 保守声明：兼容端点未必支持 json_schema，走容错解析更稳
                supportsJsonSchema = false,
                supportsFunctionCalling = false,
            ).ok()
        }
    }

    /**
     * 截图记账 —— 走用户自己的"识图模型"配置（留空则沿用主配置）。
     *
     * 注意：
     * - 图是 base64，绝不进日志、绝不写进 model_call 表（AGENTS.md 禁忌）
     * - 供应商不支持 vision 时会返回 4xx/5xx，这里如实转成 AppError，
     *   上层据此降级存图，绝不因为识别失败丢掉用户的截图
     */
    override suspend fun recognizeReceipt(
        imageBase64: String,
        mimeType: String,
        now: Instant,
    ): Outcome<ReceiptResult> = withContext(Dispatchers.IO) {
        val cfg = settings.visionConfig.first()
        if (!cfg.isConfigured) {
            return@withContext AppError.Auth("还没配置识图模型（设置页可单独填，或留空沿用上面的）").err()
        }

        val json = when (
            val r = callVision(
                cfg.baseUrl, cfg.apiKey, cfg.modelName,
                Prompts.receiptSystem(now), Prompts.receiptUser(),
                imageBase64, mimeType,
            )
        ) {
            is Outcome.Err -> return@withContext r
            is Outcome.Ok -> r.value
        }

        val parsed = ReceiptParser.parse(json)
            ?: return@withContext AppError.BadOutput(json.take(200)).err()
        parsed.ok()
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun callJson(
        baseUrl: String, apiKey: String, model: String,
        system: String, user: String,
    ): Outcome<String> = call(baseUrl, apiKey, model, system, user, jsonMode = true)

    private fun callText(
        baseUrl: String, apiKey: String, model: String,
        system: String, user: String,
    ): Outcome<String> = call(baseUrl, apiKey, model, system, user, jsonMode = false)

    /** 纯文本请求 */
    private fun call(
        baseUrl: String, apiKey: String, model: String,
        system: String, user: String, jsonMode: Boolean,
    ): Outcome<String> = callRaw(
        baseUrl, apiKey, model,
        buildMessages(system, user, image = null),
        jsonMode,
    )

    /**
     * 识图请求（OpenAI 兼容 vision）。
     * 图片以 data URL 内联，不下载、不落第三方服务器。
     */
    private fun callVision(
        baseUrl: String, apiKey: String, model: String,
        system: String, user: String,
        imageBase64: String, mimeType: String,
    ): Outcome<String> = callRaw(
        baseUrl, apiKey, model,
        buildMessages(system, user, image = imageBase64 to mimeType),
        jsonMode = true,
    )

    /**
     * 拼 messages。image 为 null 时是纯文本；
     * 否则 user 消息的 content 变成数组：[文本块, 图片块]。
     */
    private fun buildMessages(
        system: String,
        user: String,
        image: Pair<String, String>?,
    ): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", system))
        put(
            JSONObject().put("role", "user").put(
                "content",
                if (image == null) {
                    user
                } else {
                    val (b64, mime) = image
                    JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", user))
                        put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:$mime;base64,$b64"),
                            )
                        )
                    }
                }
            )
        )
    }

    /** 统一的 HTTP 出口 —— 纯文本与识图共用，错误处理只此一份 */
    private fun callRaw(
        baseUrl: String, apiKey: String, model: String,
        messages: JSONArray, jsonMode: Boolean,
    ): Outcome<String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("temperature", 0.1)
                put("messages", messages)
                if (jsonMode) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            when {
                code in 200..299 -> extractContent(body)
                code == 401 || code == 403 -> AppError.Auth("鉴权失败（HTTP $code）").err()
                code == 429 -> AppError.RateLimit(null, "请求过于频繁（HTTP 429）").err()
                code in 500..599 -> AppError.Provider(code, "服务端错误（HTTP $code）").err()
                else -> AppError.Provider(code, body.take(200)).err()
            }
        } catch (e: java.net.SocketTimeoutException) {
            AppError.Network("请求超时").err()
        } catch (e: java.net.UnknownHostException) {
            AppError.Network("网络不可用").err()
        } catch (e: Exception) {
            AppError.Network(e.message ?: "网络异常").err()
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractContent(body: String): Outcome<String> = try {
        val obj = JSONObject(body)
        val choices = obj.optJSONArray("choices")
        val content = choices?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
        if (content.isNullOrBlank()) AppError.BadOutput(body.take(200)).err() else content.ok()
    } catch (e: Exception) {
        AppError.BadOutput(body.take(200)).err()
    }

    // ------------------------------------------------------------------
    // 容错解析（第 2 层回退）
    // ------------------------------------------------------------------

    private fun parseClassify(raw: String): ClassifyResult? {
        val json = ReceiptParser.extractJsonObject(raw) ?: return null
        return try {
            val obj = JSONObject(json)
            val type = when (obj.optString("type").lowercase()) {
                "todo" -> ItemType.TODO
                "ledger" -> ItemType.LEDGER
                "note" -> ItemType.NOTE
                else -> ItemType.NOTE
            }
            val confidence = obj.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
            val title = obj.optString("title").ifBlank { "未命名" }

            val ledger = obj.optJSONObject("ledger")?.let { l ->
                val amt = l.optDouble("amount", 0.0)
                if (amt <= 0) null else LedgerDraft(
                    amountYuan = amt,
                    direction = if (l.optString("direction") == "in")
                        LedgerDirection.IN else LedgerDirection.OUT,
                    category = l.optString("category").ifBlank { "其他" },
                    merchant = l.optString("merchant").ifBlank { null },
                )
            }

            ClassifyResult(
                type = type,
                confidence = confidence,
                title = title,
                timeExpression = obj.optString("timeExpression").ifBlank { null },
                remindExpression = obj.optString("remindExpression").ifBlank { null },
                ledger = ledger,
                noteText = obj.optString("note").ifBlank { null },
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTags(raw: String): List<String> =
        raw.split('\n', ',', '，', '#')
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() && it.length <= 12 }
            .distinct()
            .take(5)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_SUMMARY_CHARS = 12_000
        const val MAX_TAG_CHARS = 4_000
    }
}