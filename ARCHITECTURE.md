# 收着（ShouZhe）· 系统架构设计

> 版本：v1.0
> 阶段：阶段二 · 高级架构师
> 状态：待确认
> 上游依据：《产品立项书（里程碑 A）》—— 已通过

---

## 0. 文档定位

本文档回答三个问题：**用什么做**、**怎么分层**、**跑在哪里**。
不含具体表结构（见 `DATABASE.md`）与接口细节（见 `API.md`）。

**架构第一原则**：本项目为 **A（自用）+ D（技术作品）** 性质，
一切设计优先服务 **"能长期跑在自己手机上、代码干净可读、可开源"**，
**不**为"未来可能的高并发/多租户/商业化"预留任何复杂度。

---

## 1. 已锁定的技术决策（不可返工）

| # | 决策 | 值 | 依据 |
|---|---|---|---|
| D1 | 平台 | **Android 优先**（不排除后续 iOS，架构不为它妥协） | 用户主力机为安卓 |
| D2 | 语言 / UI | **Kotlin + Jetpack Compose** | 重度依赖系统能力，原生是一等公民；且便于借鉴参考项目设计 |
| D3 | 服务器 | **无**。数据全本地 | 用户明确要求"不要后台" |
| D4 | 模型接入 | **BYOK**，用户自填 API Key | 用户明确选择；避免 Key 泄漏风险与运营成本 |
| D5 | 正文抽取 | **App 内隐藏 WebView 渲染 + 注入 JS 抽取** | 实测结论，见 §5 与 ADR-001 |
| D6 | MVP 权限范围 | **不做短信、不做通知监听** | 二者均为 Google Play 高危权限，Play Protect 会阻止侧载安装；放 V1.1 |
| D7 | 开源协议 | **Apache-2.0** | 社区友好，与参考项目同源 |
| D8 | 最低支持版本 | Android 8.0（API 26） | `NotificationChannel`、`WebView` 能力起点；覆盖主流机型 |
| D9 | **目标机型** | **红米 K80（小米 HyperOS）** —— 唯一验证机型 | 用户主力机；开发与验收以它为准 |
| D10 | 项目根目录 | `D:\projects\shouzhe` | 原名 `dida` 与竞品域名撞名，已废弃 |
| D11 | 包名 | `com.shouzhe.app` | 简洁、可开源 |
| D12 | DI 框架 | **Hilt** | 官方、生态好；避免手写容器带来的样板代码 |
| D13 | 分支策略 | **GitHub Flow**（主干 + 短命分支） | 最轻，适配单人项目 |

---

## 2. 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                        表现层 (Compose)                       │
│  收件箱流 · 详情页 · 快速录入 · 设置（模型/Key） · 搜索          │
└───────────────────────────┬──────────────────────────────────┘
                            │ UI State / Event
┌───────────────────────────▼──────────────────────────────────┐
│                    ViewModel 层 (StateFlow)                    │
│   InboxVM · CaptureVM · DetailVM · SearchVM · SettingsVM       │
└───────────────────────────┬──────────────────────────────────┘
                            │ 调用领域用例
┌───────────────────────────▼──────────────────────────────────┐
│                      领域层 (Domain)                           │
│                                                                │
│   ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐  │
│   │  Capture    │  │   Refine    │  │        Act           │  │
│   │  收集        │→ │   提纯       │→ │       行动            │  │
│   │             │  │             │  │                      │  │
│   │ ·链接/文本   │  │ ·摘要        │  │ ·待办/提醒            │  │
│   │ ·WebView抽取 │  │ ·打标签      │  │ ·记账                 │  │
│   │ ·降级路径    │  │ ·归类        │  │ ·排期                 │  │
│   └─────────────┘  └─────────────┘  └──────────────────────┘  │
│                                                                │
│   ┌──────────────────────┐  ┌──────────────────────────────┐  │
│   │      Recall 找回      │  │      ModelGateway 模型网关    │  │
│   │  关键词 + 语义搜索     │  │  provider 抽象 / BYOK / 路由  │  │
│   └──────────────────────┘  └──────────────────────────────┘  │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                        数据层 (Data)                           │
│   Room/SQLite（单一库 shouzhe.db）· DataStore（偏好/Key）       │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                  平台能力层 (Platform)                         │
│  WebView 抽取器 · 分享接收 · 通知/精确闹钟 · 语音识别 · 日历     │
└──────────────────────────────────────────────────────────────┘
```

**分层规则（强制）**：
1. 上层可依赖下层，**下层不得反向依赖上层**。
2. **领域层不出现 Android 框架类型**（除必要注解），便于单测。
3. 所有系统能力通过 **接口 + 平台实现** 注入，便于测试替身。

---

## 3. 模块划分（单 Gradle 模块，包内分层）

> **为什么不用多模块**：A+D 小项目，多模块带来的构建复杂度与心智负担 **大于** 收益。
> 用**包结构 + 依赖规则**表达边界，必要时再拆。

```
app/src/main/java/com/shouzhe/app/
├── ShouzheApp.kt              Application：DI 容器初始化
├── MainActivity.kt            单 Activity 宿主（Compose Navigation）
│
├── core/                      横切基础设施
│   ├── di/                    手写轻量 DI（或 Hilt，见 §9 待确认）
│   ├── result/                Result / AppError 统一错误模型
│   ├── time/                  时间抽象（可测试，禁止直接用 System.currentTimeMillis）
│   └── log/                   日志（禁止打印敏感字段）
│
├── domain/                    ★ 纯 Kotlin，无 Android 依赖
│   ├── model/                 Item / TodoItem / LedgerEntry / ArticleItem / Tag
│   ├── capture/               收集用例：IngestContent、ClassifyIngest
│   ├── refine/                提纯用例：Summarize、Tag、Categorize
│   ├── act/                   行动用例：CreateTodo、ScheduleReminder、RecordLedger
│   ├── recall/                找回用例：SearchByKeyword、SearchBySemantic
│   └── gateway/               ModelGateway 接口（不含实现）
│
├── data/                      数据实现
│   ├── db/                    Room：Entity / Dao / Database / Migration
│   ├── repository/            Repository 实现（接口定义在 domain）
│   ├── prefs/                 DataStore：设置、模型配置（Key 加密存储）
│   └── extract/               抽取结果仓储（原始 HTML 缓存策略）
│
├── model/                     模型接入层
│   ├── ModelGatewayImpl.kt    统一网关实现
│   ├── provider/              各厂商适配：DeepSeek / 通义 / Kimi / OpenAI 兼容
│   ├── prompt/                提示词模板（版本化，便于回归）
│   └── schema/                结构化输出 Schema（function calling / JSON mode）
│
├── platform/                  平台能力（接口实现）
│   ├── extract/               ★ WebView 正文抽取器（架构难点，见 §5）
│   ├── share/                 分享接收 Activity（ACTION_SEND）
│   ├── notify/                通知渠道 + 精确闹钟调度
│   ├── voice/                 语音识别封装
│   └── calendar/              系统日历写入（V1.1）
│
├── feature/                   各功能界面（VM + Screen）
│   ├── inbox/                 收件箱流（主页）
│   ├── capture/               快速录入（浮层）
│   ├── detail/                详情 / 编辑
│   ├── search/                搜索
│   └── settings/              设置 / 模型配置
│
└── ui/                        设计系统
    ├── theme/                 配色 / 字体 / 间距
    └── component/             可复用组件
```

**关键约束**：
- `domain/` **不得** import `android.*`（用 ArchUnit 或 CI 检查，见 §10）。
- `model/` 只依赖 `domain/gateway`，**不依赖** UI。
- `platform/extract` 是**唯一**允许持有 WebView 的地方。

---

## 4. 核心数据流（三条主链路）

### 4.1 链路一：转发文章进来

```
用户在其他 App 点"分享" → 选中"收着"
   ↓
ShareReceiverActivity（ACTION_SEND，接收 text/plain）
   ↓
IngestContent 用例
   ↓
判断类型：是 URL 还是纯文本？
   ├─ URL → WebViewExtractor 渲染抽取
   │        ├─ 成功 → 存正文 + 触发 Summarize
   │        └─ 失败 → 【降级】只存 URL + 标题（若可得），标记 needs_manual
   └─ 文本 → 直接存，触发 Classify
   ↓
写入 item 表（type = article | note）
   ↓
异步：ModelGateway.summarize() → 回写 summary/tags
   ↓
收件箱刷新，显示"已收着"
```

**⚠️ 降级是主路径的一部分，不是异常分支。** 见 ADR-001。

### 4.2 链路二：一句话丢进来

```
用户长按浮层/小组件 → 录音（或打字）
   ↓
VoiceRecognizer → 文本
   ↓
ModelGateway.classify() → 结构化判断
   ↓
   ├─ 含金额 → RecordLedger（记账）
   ├─ 含时间/事件 → CreateTodo + ScheduleReminder
   └─ 都不含 → 存为 note
   ↓
【确认】展示解析结果卡片，用户可"确认/改"
   ↓
落库 + 排期
```

**设计要点**：**"不要求用户先分类"** 是本产品核心体验（见立项书）。
但**记账必须二次确认**——记错钱会摧毁信任。

### 4.3 链路三：找回

```
用户输入自然语言 → SearchVM
   ↓
   ├─ V1（MVP）：FTS4 全文检索（标题 + 正文 + 摘要 + 标签）
   └─ V1.1：向量语义检索（本地 embedding 或走模型 API）
   ↓
结果排序（相关度 + 时间衰减）→ 列表
```

**MVP 先做 FTS 全文检索**：SQLite FTS4 是内置能力，零额外依赖、零模型成本、毫秒级。
语义检索放 V1.1（要引入 embedding，成本和复杂度跳一个台阶）。

---

## 5. ★ 架构难点：WebView 正文抽取

**这是本项目技术含量最高、也最容易失败的部分。**

### 5.1 为什么必须用 WebView

实测证据（详见 ADR-001）：

| 方式 | 微信 | 头条 | 手机端可行性 |
|---|---|---|---|
| HTTP 直接抓 | ❌ 空正文 / 参数错误 | ❌ 空内容 | — |
| 真实浏览器渲染 | ✅ | ✅ | ❌ 手机端跑不动 Chromium |
| **App 内 WebView** | ✅ | ✅ | ✅ **等价于真浏览器** |

**原理**：微信有三层防御（Cookie 鉴权、JS 动态渲染、反爬头检测），头条有 JS 签名。
而 WebView **本身就是一个真浏览器**——对服务端而言，你的 WebView 就是"一个真实用户在读文章"。

### 5.2 抽取器设计

```
WebViewExtractor.extract(url): ExtractResult
   │
   ├─ 1. 创建离屏 WebView（不可见、不占布局）
   ├─ 2. 配置：真实 UA、开启 JS、开启 DOM Storage、携带 Cookie
   ├─ 3. loadUrl(url)
   ├─ 4. 轮询等待目标节点出现（超时 15s）
   │      · 微信：.rich_media_content
   │      · 头条：article 或等价容器
   │      · 通用兜底：<article> / 最大文本块启发式
   ├─ 5. 注入 JS 抽取：标题 / 作者 / 时间 / 正文纯文本 / 首图
   ├─ 6. 字数校验（< 200 字视为抽取失败）
   └─ 7. 回收 WebView（必须销毁，否则内存泄漏）
```

### 5.3 必须处理的异常（每一个都要有兜底）

| 异常 | 处理 |
|---|---|
| 超时（15s 未出正文） | 降级：存 URL + 页面标题 |
| 抽取到但字数过少 | 降级同上，标记 `extract_quality = poor` |
| 页面要求登录 | 提示用户"在收着内打开一次登录" |
| 纯视频 / 图片页 | 存 URL + 封面，正文留空 |
| WebView 崩溃 | try-catch + 进程隔离（`android:process`，V1.1 优化） |
| **内存泄漏** | WebView 必须 `destroy()`，且不可复用于并发 |

### 5.4 性能与体验约束

- 抽取**必须异步**，绝不阻塞 UI。
- 抽取期间在收件箱显示"正在收着…"状态（乐观 UI）。
- **串行抽取**（同一时刻只跑一个 WebView），避免内存爆炸。
- 慢是可接受的（3–10s），**但必须让用户看到进度**。

---

## 6. 平台能力清单与权限

### 6.1 MVP 权限（最小集）

| 权限 | 用途 | 申请时机 |
|---|---|---|
| `INTERNET` | 调模型 API、加载网页 | 安装时 |
| `POST_NOTIFICATIONS` | 发提醒（Android 13+ 需运行时申请） | 首次创建提醒时 |
| `USE_EXACT_ALARM` | **精确**闹钟（不上架商店，可声明） | 安装时（明确权限） |
| `RECEIVE_BOOT_COMPLETED` | 开机重排闹钟 | 安装时 |
| `SCHEDULE_EXACT_ALARM` | 备选（若走商店路线） | **Android 14+ 默认拒绝，需运行时引导** |
| `VIBRATE` / `WAKE_LOCK` | 提醒震动、唤醒 | 安装时 |
| `RECORD_AUDIO` | 语音录入 | 首次使用语音时 |

### 6.2 明确不申请（MVP）

| 权限 | 状态 | 理由 |
|---|---|---|
| `READ_SMS` / `RECEIVE_SMS` | ❌ 不做 | Google Play 严格限制，Play Protect 阻止侧载安装 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | ❌ 放 V1.1 | 同为高危权限；但 V1.1 用它做支付通知自动记账 |
| `READ_CALENDAR` / `WRITE_CALENDAR` | ⏸ V1.1 | 写系统日历是 V1.1 功能 |

### 6.3 国产 ROM 适配（必须写进 README 与首次启动引导）

**这是架构解决不了的问题，只能引导用户手动配置。**参考项目已验证同样的坑。

引导用户完成：
1. **应用启动管理** → 关闭"自动管理" → 三个开关全开（自启动 / 关联启动 / 后台活动）
2. **电池** → 取消对本 App 的省电策略 / 允许后台高耗电
3. **最近任务** → 下拉本 App 卡片 → 加锁
4. （若有）**通知管理** → 允许通知、设为"重要"

**实现建议**：首次启动做一个**引导页**，逐项带用户跳转到系统设置页，并打勾记录完成状态。

### 6.4 ★ 小米 HyperOS 专项适配（目标机型，最高优先级）

**目标机型为红米 K80（小米 HyperOS）。小米是国产 ROM 中后台管控最激进的之一，
菜单层级与华为/荣耀不同，不能照搬通用引导话术。**

#### 必须引导用户完成的四项配置

| # | 配置项 | 小米 HyperOS 路径 | 默认值 | 目标值 |
|---|---|---|---|---|
| 1 | **自启动** | 设置 → 应用设置 → 应用管理 → 收着 → **自启动** | **关闭** | 开启 |
| 2 | **省电策略** | 同上页面 → **省电策略** | **智能限制后台运行** | **无限制** |
| 3 | **通知权限** | 同上页面 → 通知管理 → 允许通知 + **设为「重要」** | 可能被折叠 | 重要 + 允许 |
| 4 | **锁定后台** | 最近任务 → 下拉"收着"卡片 → 点锁图标 | 未锁 | 已锁 |

> **注**：HyperOS 版本间菜单位置可能有差异，引导页需**容错**——
> 若跳转失败（Intent 被拦截），降级为"图文步骤说明 + 手动跳转设置首页"。

#### 技术实现

```kotlin
// 检测是否为小米/HyperOS
fun isXiaomi(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
    Build.BRAND.equals("Redmi", ignoreCase = true) ||
    Build.BRAND.equals("POCO", ignoreCase = true)

// 尝试跳转自启动管理页（各版本 Action 不同，需逐个 try）
fun autoStartIntents(): List<Intent> = listOf(
    // HyperOS / MIUI 常见入口
    Intent().setComponent(ComponentName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    Intent().setComponent(ComponentName(
        "com.miui.securitycenter",
        "com.miui.powercenter.PowerSettings")),
)
```

- **逐个尝试**，全部失败则打开系统设置首页并给出文字指引。
- **不用 `PackageManager` 的 `QUERY_ALL_PACKAGES`**——用显式 `ComponentName` 探测，
  避免敏感权限（与参考项目做法一致）。

#### 提醒可靠性兜底（小米专项）

小米的省电策略可能推迟 `setExactAndAllowWhileIdle`。三重兜底：

1. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** —— 申请加入电池优化白名单（需引导）。
2. **提醒时间前移冗余** —— 对"重要"提醒，可提前 1–2 分钟触发（用户感知不到，但能吸收系统抖动）。
3. **触发偏差观测** —— 记录"计划时间 vs 实际触发时间"，偏差 > 60s 时，
   在 App 内提示用户检查后台配置。**这是把"提醒不响"从静默失败变成可见问题。**

#### 开源诚实标注（D 路线的专业要求）

> 由于**只有一台红米 K80 可验证**，README 必须显式声明：
>
> **"本项目在一台红米 K80（HyperOS）上开发与验证。其他机型未经测试；
> 若提醒不触发，请参照本机型配置指南自行调整后台策略。"**
>
> 诚实标注不是缺点，是专业。参考项目同样只验证单一机型并如实说明。

---

## 7. 后台提醒方案（第二难点）

```
创建提醒
   ↓
AlarmScheduler.schedule(triggerAt, itemId)
   ↓
AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, triggerAt, pendingIntent)
   ↓ （Android 12+ 需检查 canScheduleExactAlarms()）
到点 → AlarmReceiver（BroadcastReceiver）
   ↓
发通知 + 全屏 Intent（重要事项）
   ↓
用户操作 → NotificationActionReceiver（完成 / 稍后 / 改期）
   ↓
回写数据库 + 重排
```

**关键设计**：
- **`RescheduleReceiver`**：监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `TIMEZONE_CHANGED` / `TIME_SET` → **全量重排所有未完成提醒**。这是"排期不许出错"的保险丝。
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**：引导用户把 App 加入电池优化白名单。
- **失败观测**：记录每次提醒的"计划时间 vs 实际触发时间"，偏差过大时提示用户检查白名单。

---

## 8. 模型接入层（ModelGateway）

### 8.1 抽象设计

```kotlin
interface ModelGateway {
    suspend fun classify(text: String): ClassifyResult     // 一句话 → 结构化
    suspend fun summarize(content: String, maxTokens: Int): Summary  // 正文 → 摘要
    suspend fun tag(content: String): List<String>         // → 标签
    suspend fun embed(text: String): FloatArray           // V1.1 语义检索
}
```

**实现策略**：统一走 **OpenAI 兼容协议**（`POST /chat/completions`），
因为 DeepSeek / 通义 / Kimi / 大多数国产模型都兼容它。
只需让用户填 **BaseURL + Key + 模型名** 三个字段，即可覆盖绝大多数供应商。

### 8.2 结构化输出（关键）

**不要靠正则解析模型的自由文本。** 用 **JSON Schema 约束输出**：

```
classify() 期望输出：
{
  "type": "todo" | "ledger" | "note",
  "confidence": 0.0~1.0,
  "todo":   { "title": str, "dueAt": ISO8601?, "remind": bool },
  "ledger": { "amount": number, "direction": "in"|"out",
              "category": str, "merchant": str? },
  "note":   { "text": str }
}
```

- 优先用 `response_format: json_schema`（若供应商支持）
- 不支持则回退 **function calling**
- 再不支持则回退"提示词强约束 + 容错解析 + 失败重试一次"
- **`confidence` 低时必须走人工确认**，不要自作主张写入

### 8.3 成本可见（BYOK 前提下的必备体验）

- 每次调用记录 `prompt_tokens / completion_tokens`（供应商返回时）
- 设置页展示**累计消耗与预估费用**
- 长文总结前**提示预估消耗**并让用户确认

---

## 9. 待你确认的技术细节

| # | 事项 | 我的建议 |
|---|---|---|
| T1 | **DI 框架** | **Hilt**（官方、生态好）；若嫌重可用手写容器。我倾向 **Hilt** |
| T2 | **项目根目录** | 当前 `D:\projects\dida` 是空目录且名字需改 → 建议 **`D:\projects\shouzhe`** |
| T3 | **包名** | 建议 **`com.shouzhe.app`**（简洁、可开源） |
| T4 | **Git 分支策略** | **GitHub Flow**（主干 + 短命分支），最轻 |
| T5 | **WebView 抽取是否独立进程** | MVP **不做**（先简单），V1.1 若崩溃严重再隔离 |

---

## 10. 质量门禁（本项目裁剪版）

| 门禁 | 标准 |
|---|---|
| 构建 | CI 全绿方可合主干 |
| 单元测试 | `domain/` 与 `model/schema` **必须**有单测；覆盖率建议 ≥ 60%，**排期与解析逻辑 100%** |
| 分层检查 | 禁止 `domain/` import `android.*`（ArchUnit 或自定义 lint） |
| 静态检查 | Android Lint 零 error；ktlint 格式化 |
| 依赖安全 | 无高危漏洞；**Key 绝不入库**（`secrets.properties` 入 `.gitignore`） |
| 文档 | README / CHANGELOG / AGENTS.md 随交付更新 |

---

## 11. 风险登记（架构视角）

| # | 风险 | 等级 | 应对 |
|---|---|---|---|
| R1 | WebView 抽取成功率不稳定 | 🔴 高 | 降级路径 + 质量标记 + 引导手动补充 |
| R2 | 小米 HyperOS 杀后台 → 提醒不响 | 🔴 高 | 小米专项引导（§6.4）+ 白名单 + 触发偏差观测 |
| R2b | **仅一台机型可验证，跨机型兼容未知** | 🟡 中 | README 诚实标注"仅小米验证"；不承诺全机型支持 |
| R3 | 抽取消耗内存/崩溃 | 🟡 中 | 串行 + 强制 destroy + 超时熔断 |
| R4 | 各供应商 JSON 输出格式不一 | 🟡 中 | 三层回退（json_schema → function calling → 容错解析） |
| R5 | 范围蔓延（四条线全想做好） | 🟡 中 | 严守 MVP 边界，V1.1 清单冻结 |
| R6 | 模型费用失控（用户抱怨） | 🟡 中 | 成本可见 + 长文确认 |

---

## 12. 部署与发布形态

本项目**无服务端**，发布形态为：

| 项 | 方案 |
|---|---|
| 分发 | **GitHub Releases 提供 APK**（自用 + 开源用户侧载） |
| 上架 | **暂不上商店**（高危权限 + 自用性质） |
| 签名 | 本地 keystore，**不入库**（`.gitignore`） |
| 更新 | 手动下载新版 APK 覆盖安装（V1.1 可做应用内检查更新） |
| 版本号 | 语义化版本 `主.次.修订` |

**⚠️ 侧载提示**：由于目标用户是侧载安装，需在 README 明确说明
"若 Play Protect 提示风险，是因为 App 需要通知/自启动等能力，请确认后继续安装"。

---

## 13. 下一步

1. 你确认 §9 的 T1–T5
2. 输出 `DATABASE.md`（表结构 + 索引 + 迁移策略）
3. 输出 `API.md`（ModelGateway 契约 + 各 provider 适配差异）
4. 输出 `docs/adr/ADR-001-正文抽取采用WebView渲染.md`

---

**确认门**：请审阅本文档。确认后进入数据库与接口设计。
