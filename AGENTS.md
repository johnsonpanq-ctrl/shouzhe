# 给 AI Agent 的项目指引

> 本文件面向在此仓库工作的 AI 编码助手（Claude Code / Codex / Cursor 等）。
> 人类贡献者请先读 [README.md](README.md)。

---

## 项目一句话

「收这吧」是一个**安卓本地优先**的记录工具：一句话 / 一篇文章 / 一笔小钱丢进来，
App 分类、存好、到点提醒。**无服务器，无账号，模型走用户自己的 API Key（BYOK）。**

---

## 动代码前必读

| 文档 | 什么时候读 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 任何时候。分层规则、模块边界 |
| [DATABASE.md](DATABASE.md) | 改表结构、加字段、写查询 |
| [API.md](API.md) | 改 ModelGateway、改提示词、改结构化输出 |
| [UI-SPEC.md](UI-SPEC.md) | 改界面、加组件、调颜色 |
| [docs/adr/](docs/adr/) | 想推翻某个既有决策之前 |

---

## 三条不可违背的铁律

### 1. 任何内容进来，至少留下链接

抓取失败、模型出错、网络不通 —— **都不许丢用户数据**。
降级是主路径的一部分，不是异常分支。
新增任何入口时都要问：**如果这一步失败了，用户的东西还在吗？**

### 2. 记账必须二次确认

AI 解析出的记账先以 `confirmed = false` 落库，**必须经用户确认才计入账本**。
**禁止**提供跳过确认的口子。错一次，用户就再也不信这个 App。

### 3. 金额一律用整数存「分」

`Long` 存分，绝不用 `Float` / `Double`。`¥1.00` 存成 `100`。
浮点误差会让对账差几分钱，这是记账类应用的死罪。

---

## 代码约定

### 分层规则（强制）

```
feature/  UI（Compose）      ─┐
domain/   领域模型与用例      │  上层可依赖下层
data/     数据实现（Room）    │  下层不得反向依赖
model/    模型网关            │
platform/ 平台能力（WebView/通知/日历）
core/     横切基础设施
```

- **`domain/` 不得 import `android.*`** —— 保证可单测
- **只有 `platform/extract/` 允许持有 WebView**
- 所有系统能力通过接口 + Hilt 注入，便于测试替身

### 命名

- 数据表字段：`snake_case`（Room `@ColumnInfo`），Kotlin 属性 `camelCase`
- 时间：**一律 UTC 毫秒存 `Long`**，展示时转本地时区
- 布尔存 `0/1`（SQLite 无原生布尔），用 `Converters`

### 提交

Conventional Commits：`feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:`

---

## 禁忌

| 禁止 | 原因 |
|---|---|
| `fallbackToDestructiveMigration()` | 这是用户唯一的本地数据，丢了就没了。必须写 Migration |
| 把 API Key 写进日志 | 隐私。`SettingsStore` 已用 EncryptedSharedPreferences |
| 把模型请求/响应正文写进 `model_call` 表 | 同上。只记 token 数与耗时 |
| 申请 `READ_SMS` / `RECEIVE_SMS` / 通知监听 | Play 高危权限，会阻断侧载安装（见 ADR-002） |
| 并发跑多个 WebView | 内存爆炸。`WebViewExtractor` 已有 Mutex 串行 |
| 忘记 `webView.destroy()` | 内存泄漏 |
| 在前台线程做网络 / 数据库 | ANR |
| 用 `REAL` 存金额 | 见铁律 3 |

---

## 常用命令

```bash
# 编译 debug
./gradlew :app:assembleDebug

# 单测
./gradlew :app:testDebugUnitTest

# 装到连着的手机
./gradlew :app:installDebug

# 看依赖
./gradlew :app:dependencies
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

**本地环境需要**：JDK 17、Android SDK（platform 35 + build-tools 35.0.0）、
`local.properties` 里写 `sdk.dir=...`。

---

## 当前状态与下一步

**当前版本**：v0.8.0（59 个单测全绿，模拟器实测过迁移与账目编辑）

**已完成**：数据层、时间解析、模型网关、正文抽取、提醒调度、收件箱 UI、快速录入、
详情页、搜索、设置、语音录入、截图记账、账目编辑

**下一步优先级**：
1. 真机验证（语音准确率 / 后台提醒 / 截图记账接真模型 / 账目编辑手感）—— 需要用户配合
2. 语义检索（`item_embedding` 表已预留）

---

## 新增的坑

### v0.8.0 账目编辑

| 坑 | 说明 |
|---|---|
| **金额解析别碰浮点** | `38.5 * 100` 在 Double 里是 3849.9999…。正确做法（见 `MoneyParser`）：**先把元文本拼成"分"的字符串再 `toLong()`**，全程不经过浮点。已有 15 个单测钉死 |
| **combine 最多 9 个参数** | 超过要打包成 `Array`。`DetailViewModel` 已经踩过一次——字段变多后**两段合并**（先合成小 data class，再合成 UI 状态），不要硬塞 |
| **查数据库要连 WAL 一起导** | 只导 `shouzhe.db` 读到的是**旧快照**，会误判"保存失败"。用 `tools/check_db_full.py` |
| **改过的账目要退回待确认** | 否则等于开了"绕过二次确认"的口子 |
| **单测方法名不能含 `..`** | Kotlin 反引号标识符里 `..` 非法，会编译失败（`38.5` 这种也不行） |

### v0.7.0 截图记账

| 坑 | 说明 |
|---|---|
| **JVM 单测里 `org.json` 是死的** | `android.jar` 里的 `org.json` 在单元测试中调用即抛异常。要可单测的结构化解析就用**正则取标量**（见 `model/schema/ReceiptParser.kt`） |
| **别用 shell 重定向导出 SQLite** | PowerShell `>` / `Out-File` 会损坏二进制。正确姿势：`adb shell "run-as <pkg> base64 databases/shouzhe.db"` 拿 base64，本地解码 |
| **改表只写 ADD COLUMN 的 Migration** | v1→v2 就是加了一个可空列。**不重建表、不动已有行** —— 这是最安全的变更形式 |
| **识图模型必须独立配置** | DeepSeek 等纯文本模型不支持 vision。共用主配置 = 点了必然失败的假按钮 |
| **图先存、识别后做** | 任何失败都不丢图。顺序不能反 |
| **原图存绝对路径不存 content:// URI** | content 权限过期后图就打不开了 |
| **模拟器别用 `-no-snapshot-load`** | 会重置用户数据，已装的 App 和数据全没 |

---

## 一句话总结给 Agent

**这是个「宁可少做功能，也要不丢数据、不记错账」的项目。**
遇到取舍时，选那个更保守、更不容易让用户失望的方案。
