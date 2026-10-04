# 收着（ShouZhe）· 接口设计

> 版本：v1.0
> 阶段：阶段二 · 高级架构师
> 上游：`ARCHITECTURE.md`、`DATABASE.md`

---

## 0. 本文档范围

本项目**无自建服务端**，因此不存在传统"对外 REST API"。
本文档定义两类**内部契约**：

1. **ModelGateway** —— 与外部大模型供应商的接口（唯一的外部依赖）
2. **域内用例契约** —— 各模块之间的调用约定

---

## 1. ModelGateway（核心接口）

### 1.1 设计目标

| 目标 | 说明 |
|---|---|
| **多供应商** | DeepSeek / 通义 / Kimi / OpenAI / 任意 OpenAI 兼容端点 |
| **BYOK** | 用户填 BaseURL + Key + Model 即可用 |
| **可测试** | 纯接口，便于注入 Fake 实现做单测 |
| **失败可退** | 结构化输出三层回退（见 §3） |

### 1.2 接口定义

```kotlin
interface ModelGateway {
    /** 一句话 → 结构化意图判定 */
    suspend fun classify(input: String, ctx: ClassifyContext): Result<ClassifyResult>

    /** 长文本 → 摘要 */
    suspend fun summarize(content: String, opts: SummarizeOptions): Result<Summary>

    /** 内容 → 标签 */
    suspend fun tag(content: String, existing: List<String>): Result<List<String>>

    /** 文本 → 向量（V1.1） */
    suspend fun embed(text: String): Result<FloatArray>

    /** 连通性自检（设置页"测试连接"按钮） */
    suspend fun ping(): Result<ProviderInfo>
}
```

**约定**：
- 所有方法返回 `Result<T>`（自研密封类，非 Kotlin 标准库），携带 `AppError`。
- **不抛异常**给上层——网络/解析/鉴权错误统一转成 `AppError`。
- 所有方法必须**支持取消**（协程取消透传）。

### 1.3 错误模型

```kotlin
sealed class AppError {
    data class Network(msg: String) : AppError()          // 无网/超时
    data class Auth(msg: String) : AppError()             // Key 无效/余额不足
    data class RateLimit(retryAfter: Long?) : AppError()  // 限流
    data class BadOutput(raw: String) : AppError()        // 模型输出无法解析
    data class Provider(msg: String, code: Int?) : AppError()
    data class Unknown(msg: String) : AppError()
}
```

**UI 映射建议**：

| 错误 | 用户看到 |
|---|---|
| `Auth` | "API Key 无效或余额不足，请到设置里检查" |
| `RateLimit` | "请求太频繁，稍等 X 秒" |
| `Network` | "网络不通，已先存着，联网后自动重试" |
| `BadOutput` | "模型没看懂，可以换个模型试试"（并保留原始输入） |

**关键**：**`Network` 失败时不能丢数据** —— 内容先落本地，标记待处理，联网后重试。

---

## 2. 数据契约（结构化输出）

### 2.1 `ClassifyResult`

```json
{
  "type": "todo",
  "confidence": 0.92,
  "todo": {
    "title": "去老丈人家",
    "dueAt": "2026-10-04T10:00:00+08:00",
    "remind": true,
    "remindAt": "2026-10-03T20:00:00+08:00"
  },
  "ledger": null,
  "note": null
}
```

| 字段 | 约束 |
|---|---|
| `type` | 枚举：`todo` / `ledger` / `note` |
| `confidence` | 0.0–1.0，**< 0.7 时 UI 必须让用户确认** |
| `dueAt` / `remindAt` | ISO 8601 **带时区**；无法确定时为 `null` |
| `ledger.amount` | 数字，**单位元**（入库时 ×100 转为分） |
| `ledger.direction` | `in` / `out` |

### 2.2 必守规则（写进提示词 + 校验）

1. **时间必须解析为绝对时间**。模型输出相对时间（"明天"）一律不合格，
   需由**本地时间解析器**二次校验/补全（见 §4）。
2. **金额必须为数字**，不得含货币符号。
3. **`type` 只能三选一**，不得新增。
4. **不确定就降低 `confidence`**，不要瞎猜。

---

## 3. 结构化输出的三层回退（关键实现）

```
第 1 层：response_format = json_schema（供应商原生支持）
   ↓ 不支持 / 失败
第 2 层：function calling（tools 参数 + 强制 tool_choice）
   ↓ 不支持 / 失败
第 3 层：提示词强约束 + 容错解析
         · 剥掉 ```json 围栏
         · 提取第一个平衡的 {...}
         · JSON 修复（尾逗号、单引号）
         · 失败则整次重试 1 遍（温度调低）
   ↓ 仍失败
返回 AppError.BadOutput（保留原始输入，绝不丢）
```

**每次调用记录走的是哪一层**（`model_call` 表可加 `strategy` 字段），便于诊断。

---

## 4. 本地时间解析（不能全信模型）

**问题**：模型对"明天""下周三""月底"的理解**不可靠**，且它不知道"现在"。

**方案**：模型只负责**识别时间表达式的原文**，本地负责**换算成绝对时间**。

```kotlin
interface TimeParser {
    /** "明天下午三点" + 当前时间 → 绝对 UTC 毫秒 */
    fun parse(expression: String, now: Instant, tz: ZoneId): Instant?
}
```

- 提示词里明确注入 **当前时间 + 时区**（模型辅助判断）。
- 但**最终换算以本地解析器为准**（可测、可控）。
- 无法解析 → `dueAt = null` + UI 让用户补。

**这条是"排期不许出错"的架构落地**——提醒类应用，时间解析错了就是致命伤。

---

## 5. 域内用例契约

### 5.1 Capture（收集）

```kotlin
interface IngestContent {
    /** 从分享/输入进来的一切内容 */
    suspend operator fun invoke(input: IngestInput): Result<Long>  // 返回 itemId
}

data class IngestInput(
    val rawText: String?,
    val url: String?,
    val sourceApp: String?,
    val origin: Origin       // SHARE | QUICK_ADD | WIDGET | MANUAL
)
```

**行为保证**：
- **必定**在 `item` 表留下一条记录（哪怕后续全失败）。
- URL 类内容**必定**创建 `extract_job`。
- 立即返回 `itemId`（不等抽取完成），抽取异步进行。

### 5.2 Act（行动）

```kotlin
interface CreateTodo { suspend operator fun invoke(draft: TodoDraft): Result<Long> }
interface RecordLedger { suspend operator fun invoke(draft: LedgerDraft): Result<Long> }
interface ScheduleReminder { suspend operator fun invoke(itemId: Long, at: Instant): Result<Unit> }
```

**`RecordLedger` 的硬约束**：
- 落库时 `confirmed = 0`（草稿），**必须**经 `ConfirmLedger` 才计入账本。
- **禁止**提供"跳过确认直接记账"的口子。

### 5.3 Recall（找回）

```kotlin
interface SearchContent {
    suspend operator fun invoke(query: String, filter: SearchFilter): Result<List<ItemSummary>>
}

data class SearchFilter(
    val types: Set<ItemType>? = null,
    val from: Instant? = null,
    val to: Instant? = null
)
```

- MVP 实现走 FTS4；V1.1 加语义检索时**保持接口不变**（内部路由）。
- 空查询返回最近 N 条（收件箱行为）。

---

## 6. 与各供应商的适配差异

| 供应商 | BaseURL | 结构化输出 | 备注 |
|---|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | ✅ json_object | 性价比高，推荐默认 |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | ⚠️ 部分支持 | OpenAI 兼容模式 |
| Kimi | `https://api.moonshot.cn/v1` | ✅ | |
| OpenAI | `https://api.openai.com/v1` | ✅ json_schema | 通用回退 |

**设置页只需三个字段**：`BaseURL` / `API Key` / `模型名`。
外加一个**"测试连接"**按钮（调 `ping()`），避免用户填错后猜半天。

**预置常用供应商快捷选项**，同时保留"自定义"入口。

---

## 7. 超时与重试策略

| 场景 | 超时 | 重试 |
|---|---|---|
| `classify` | 15s | 失败重试 1 次（指数退避） |
| `summarize` | 60s | 失败重试 1 次 |
| `tag` | 20s | 失败重试 1 次 |
| `ping` | 10s | 不重试 |
| **WebView 抽取** | 15s | 最多 2 次（间隔 3s） |

**禁止无限重试**。失败后落 `AppError`，由 UI 提供手动重试入口。

---

## 8. 待确认

| # | 事项 | 建议 |
|---|---|---|
| A1 | 是否支持"多个模型配置并存"（如便宜模型记账、强模型总结） | V1.1；MVP 单配置 |
| A2 | 摘要长度偏好 | 默认 3–5 句；设置页可调 |
| A3 | 是否把提示词做成可编辑 | MVP **不做**（保持可控），提示词版本化在代码内 |

---

**确认门**：请审阅。确认后输出 ADR-001，架构阶段即可收口。
