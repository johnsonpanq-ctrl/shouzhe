# HANDOFF · 交接文档

> 交接时间：2026-10-03（第二棒：已完成 v0.7.0 截图记账）
> 上一棒：XUP 开发五人组（产品经理 → 架构师 → UI → 全栈，阶段四已完成）
> 下一棒：新会话（继续阶段四收尾 / 阶段五真机测试 / V1.1 功能）
> 本文档目的：**新会话读完即可无缝接续，不重走已定的路，不重踩已踩的坑**

---

## 一、项目一句话

「收这吧」——安卓本地优先记录工具：一句话 / 一篇文章 / 一笔小钱丢进来，
App 分类、存好、到点提醒。**无服务器、无账号、模型走用户自己的 Key（BYOK）。**

- 代码：`D:\projects\shouzhe\`
- 对话记录（旧）：`D:\projects\dida\`（旧目录保留，**新会话工作目录用 shouzhe**）

---

## 二、当前状态快照

| 项 | 值 |
|---|---|
| 最新版本 | **v0.9.0**（versionCode 11） |
| APK | `收这吧-v0.9.0-debug.apk`（102.2MB，含 24MB 离线语音模型） |
| 测试 | **71 个单测全绿**（17 时间 + 12 规则 + 10 识图解析 + 5 识图配置 + 15 金额解析 + 12 账本区间） |
| 构建 | 已验证 `assembleDebug` 通过 |
| 构建环境 | JDK 17（`C:\tools\jdk-17.0.2`）、Android SDK（`D:\android-sdk`，platform 35 / build-tools 35.0.0）、Gradle 8.9（`D:\gradle\gradle-8.9`）、`local.properties` 已配 |
| 验证方式 | 模拟器 AVD `shouzhe_test`（android-34）；**v1→v2 迁移、v0.8.0 账目编辑、v0.9.0 账本汇总均已实测** |

**常用命令**：

```bash
$env:ANDROID_HOME="D:\android-sdk"
cd D:\projects\shouzhe
D:\gradle\gradle-8.9\bin\gradle.bat :app:assembleDebug :app:testDebugUnitTest --console=plain --offline
# 产物 → 导出
Copy-Item app\build\outputs\apk\debug\app-debug.apk 收这吧-v0.9.0-debug.apk
```

**⚠️ 导出数据库查数据（v0.8.0 新踩的坑）**：
只 `base64 databases/shouzhe.db` 会**读到未回放的旧快照**（WAL 里才是最新数据），
会误判成"保存失败"。正确姿势：**db / -wal / -shm 三个文件一起导出到同一目录**，
本地用 `tools/check_db_full.py` 打开（它会自动回放 WAL）。已为这个坑踩过一次并白查一轮。

**⚠️ 模拟器启动**：带 `-no-snapshot-load` 会重置用户数据（已装 App 与数据全没）。
要保数据就别加这个参数，或每次重装后重跑验证。

**⚠️ 模拟器输入中文**：`adb shell input text` **不支持中文**（会抛 NullPointerException）。
装 [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard) 后用
`adb shell am broadcast -a ADB_INPUT_TEXT --es msg "午饭38.55餐饮"`，测完记得卸载并切回原输入法。
**盲点坐标会误触**：截图是 1080×2400 但预览缩放过，按预览坐标点会打到别的 App（踩过一次，误入 Google Lens）。

---

## 三、需求由来与已定决策（不可返工）

**需求链**：用户（忙人，忘性大）要一个"什么都往里丢"的 App。
项目性质 **A（自用）+ D（技术作品）**，不背商业化 KPI，计划开源（Apache-2.0）。

**已定决策**（详见 `docs/adr/` 与各文档）：

| # | 决策 | 依据 |
|---|---|---|
| 1 | Android 原生 Kotlin + Compose，**不做 Flutter** | 重度依赖系统能力 |
| 2 | **无服务器**，数据全本地，单库 Room（10 表，统一主表+分型子表） | 用户明确要求 |
| 3 | **BYOK**（OpenAI 兼容协议），用户自填 Key | 用户明确选择 |
| 4 | 正文抽取 = **离屏 WebView 渲染 + 注入 JS**，抓不到就存链接（**降级是主路径**） | ADR-001，实测微信三层防御 |
| 5 | WebView UA **按域名选**：微信用带 MicroMessenger 的客户端 UA | 2026-10-03 实测放行（普通 UA 被「环境异常」拦截） |
| 6 | MVP 不申请短信/通知监听权限 | ADR-002，Play 高危权限阻断侧载 |
| 7 | 记账**必须二次确认**（confirmed=false 草稿 → 用户确认）；金额 **Long 存分** | 信任铁律 |
| 8 | 解析链路：**本地规则引擎优先 → 没把握才调模型 → 模型失败回退规则 → 全败存笔记** | 参考《家账簿》启发 |
| 9 | 语音**双模式**：离线（sherpa-onnx 内置 24MB）/ 云端（OpenAI 兼容 /audio/transcriptions） | 国行 ROM 无 Google 语音服务（真机实测"不支持"） |
| 10 | 产品名 **收这吧**（ShouZheBa），包名 `com.shouzhe.app` | 用户定名 |
| 11 | 设计：v5「净亮+暗夜冷」白卡片+湖蓝/荧光绿 | 用户选定；**印章/楷体/纸墨永久禁区（与 daodian 撞车教训）** |
| 12 | 启动页：品牌页 5 秒 + 点击跳过；系统页只防闪烁 | 用户明确要求 |

---

## 四、已完成清单（v0.1.0 → v0.6.0）

**数据层**：Room 单库 10 表、DAO、ItemRepository（降级/软删除/恢复）
**核心**：TimeParser（中文时间，29 测试覆盖）、AppError/Outcome、Hilt
**模型**：OpenAiCompatGateway（三层结构化输出回退）、Prompts（版本化）
**规则引擎**：RuleParser（金额/分类/方向/待办/多笔拆分/兜底，12 测试覆盖）
**正文抽取**：WebViewExtractor（微信→头条→article→启发式，串行+destroy+15s 超时）
**语音**：OfflineVoiceRecognizer（sherpa-onnx）、CloudVoiceRecognizer（WAV+multipart）、
VoiceRouter（双模式路由）、VoiceRecognizer（系统识别，已弃用但保留）
**提醒**：AlarmScheduler（精确闹钟+开机重排+偏差观测）、通知渠道+动作按钮
**界面**：收件箱（五页签+卡片+角标）、快速录入（实时预览+语音）、详情页（四型各自呈现）、
搜索（防抖）、设置（模型+语音+用量+保活引导）、启动页、浅深双主题、组件库
**图标**：自适应图标（用户原图像素级提取）、多密度 PNG
**截图记账（v0.7.0）**：ImageStore（压长边1280/JPEG80 存内部目录）、ReceiptParser（正则容错解析，可单测）、
ModelGateway.recognizeReceipt（OpenAI 兼容 vision）、设置页「识图模型」独立三要素（留空沿用主配置）

**模拟器端到端已验证**：全新安装→录入→落库→确认→详情→搜索，全链路无崩溃；
规则引擎无 Key 记账生效；预览与实际同源；语音链路（录音→端点→回调）走通。

**v0.7.0 截图记账实测（2026-10-03）**：
- v1→v2 迁移实测：老数据（milk 笔记 + milk15 ¥15.00 账目）一条没丢，`user_version=2`，新列已加
- 选图走系统 Photo Picker（**零权限**，不申请存储权限）
- 未配识图 Key 时：**图照样存下来了**，降级成"一张待整理的截图"（不丢数据铁律的现场验证）
- 详情页原图正常显示 + "拿不准金额就对着原图核对"
- 设置页识图配置真实可填、可保存、留空正确沿用主配置

---

## 五、下一步任务（按优先级）

| 优先级 | 任务 | 说明 |
|---|---|---|
| **1** | **真机验证**（需要用户配合） | 用户已装 v0.9.0。验证：①离线语音准确率 ②云端语音（SiliconFlow SenseVoice）③后台提醒是否到点响 ④**截图记账真机识图**（需在设置页填一个多模态模型）⑤账目编辑与账本汇总手感 |
| **2** | ~~截图记账~~ ✅ **v0.7.0 已完成** | 代码 + 降级链路 + 单测 + 模拟器实测全部完成。**剩下真机接真模型验证识图准确率** |
| **3** | ~~账目编辑~~ ✅ **v0.8.0 已完成** | 详情页改金额/分类/方向/商家；金额全程整数分；改完退回待确认。**剩下真机手感验证** |
| **4** | ~~账本汇总~~ ✅ **v0.9.0 已完成** | 账目页签顶部：本月支出大字 + 分类排行占比条 + 今天/本周/本月切换。**只统计 confirmed=1**，有草稿时明说没计入 |
| **5** | **标签与摘要接线** | ⚠️ HANDOFF 旧版写"逻辑已就绪"**不准确**：UI 有 `SzTag` 展示位，但**全项目没有任何地方调用 `gateway.tag()`**，标签区永远空。要补：录入后调 tag() + 文章摘要补齐 |
| **6** | **语义搜索** | `item_embedding` 表已预留；embedding 走用户模型 API 或本地小模型 |
| 7 | 开源发布 | GitHub 仓库 + Releases + README 完善；AGENTS.md 已备好 |

---

## 五之二、v0.9.0 账本汇总实测（2026-10-04）

- 录 3 笔账（餐饮 38.55 / 交通 12 / 购物 299.99），**全部待确认**时：汇总显示
  "这个本月还没有已确认的账目" + "另有 3 笔待确认，没计入上面的数" → **口径正确，一条草稿都没混进去**
- 确认交通 12 → 汇总 **-¥12.00**，分类条"交通 ¥12.00"满条
- 再确认餐饮 38.55 → 汇总 **-¥50.55**（手算 12 + 38.55 = 50.55 ✓），
  两个分类按金额降序（餐饮满条、交通约 1/3 长度）
- 时间区间用 `LedgerRange` 纯函数算（12 个单测钉死月末/闰年/周一边界）

---

## 六、沟通纪律（用户定下的铁律，**必须遵守**）

1. **全程中文思考与回复** —— 用户明确要求过两次（包括思考过程）
2. **一问一答逐轮推进** —— 每次只抛一个问题；多信息拆多轮
3. **选择题必须带推荐** —— 给默认推荐项+理由，不让用户盲选
4. **如实报告不糊弄** —— 没做完就明说"没做完"；"编译通过"≠"能跑"（要实测）
5. **不做假按钮** —— 语音按钮曾经是假的被用户抓住骂过；配置了的功能必须真实现
6. **回复别太长** —— 用户说过"你累死我"；能一张表说清就不写长文
7. **被质疑先查证据再回应** —— 用户对设计的质疑曾是对的（与竞品撞车），用实测数据回应，不嘴硬

---

## 七、已知问题与风险

| # | 问题 | 状态 |
|---|---|---|
| 1 | 离线语音 14M 模型准确率有限（嘈杂环境差） | 已提供云端模式兜底；真机实测后评估是否换 150MB SenseVoice int8（APK 会到 ~230MB） |
| 2 | 仅一台红米 K80 可验证，跨机型兼容未知 | README 已诚实标注"仅小米验证" |
| 3 | 国产 ROM 后台限制 → 提醒可能不响 | 代码层已做三重兜底（白名单引导/前移冗余/偏差观测）；需真机验证 |
| 4 | 微信文章抓取有「环境异常」风险 | 微信客户端 UA 已实测放行；仍有不确定页面，降级路径保底 |
| 5 | APK 102MB | 离线语音的代价，用户已知情接受；abiFilters 已裁掉 2 个架构 |
| 6 | 设置页"测试连接"只测主模型 | 语音/视觉的连通性测试未做（可后续加） |
| 7 | `pidof`/`sqlite3` 在 adb shell 里验证数据时注意 WAL 模式 | 直接 SELECT 主库文件可能看不到未 checkpoint 的写入；用 UI 验证更稳。**导出数据库要用 `run-as ... base64` 再本地解码**，`>` 重定向会损坏二进制 |
| 8 | 截图记账不做 EXIF 方向校正（v0.7.0） | 相册截图/微信支付宝截图方向正常；直接拍纸质小票个别机型可能歪 90°。修它要引入 exifinterface 依赖，按纪律待用户确认 |
| 9 | 识图模型未配置时不识图（v0.7.0） | 图仍会存下来降级成笔记，**不会假装成功**；设置页有明确文案提示，不会出现假按钮 |
| 10 | 改过的账目一律退回待确认（v0.8.0） | 有意为之：若改完仍留在"已计入账本"，就等于开了个绕过二次确认的口子（先记错→再改金额→账本出现用户没确认过的数字）。代价是多点一次，收益是账本可信 |
| 11 | `LedgerSummary` 同名类踩过 ClassCastException（v0.9.0） | 我在 data/ 和 feature/ 各定义了一份同名 `LedgerSummary`，combine 里强转直接**启动即崩**。教训：**领域模型只能有一份**，放 `domain/model/`，两边都 import 它 |
| 12 | 标签功能是空壳（v0.9.0 查证） | UI 有 `SzTag` 展示位，但**没有任何地方调 `gateway.tag()`** → 标签永远为空。HANDOFF 旧版"逻辑已就绪"的说法**不准确**，已在第五节标注 |

---

## 八、开源发布状态（2026-10-04）

**仓库已上线：https://github.com/johnsonpanq-ctrl/shouzhe**（public，Apache-2.0）

| 项 | 值 |
|---|---|
| 账号 | `johnsonpanq-ctrl`（凭据在 `~/.git-credentials`，`credential.helper=store` **明文存储**） |
| 分支 | `main`（本地 `master` 已改名对齐） |
| 已推内容 | 源码 130 个文件 + 全部文档 + LICENSE + 24MB 语音模型 |
| topics | android / kotlin / jetpack-compose / byok / local-first / note-taking / expense-tracker / room / offline-first / openai-compatible |
| 未做 | **GitHub Releases（APK 还没有发布入口）** |

**已知遗留**：
1. **APK 还没发 Releases** —— README 里写的"去 Releases 下载"目前是空的
2. **仓库描述第一次推时中文变乱码**（`???`），已用 UTF-8 字节显式构造 JSON 修正。
   教训：PowerShell 调 GitHub API 传中文，必须 `[System.Text.Encoding]::UTF8.GetBytes(json)` 再发，
   不能直接传字符串
3. `tools/__pycache__/make_splash_logo.cpython-312.pyc` 被误提交了，
   应加进 `.gitignore` 并移除（小问题，不影响使用）

**推送时的坑**：用 API 建仓库时带了 `license_template=apache-2.0`，
GitHub 会自动生成一个 "Initial commit"（含 LICENSE），导致首次 push 被拒。
处理方式：`git pull --no-rebase --allow-unrelated-histories` 合并，**不要强推**。

---

## 九、v0.7.0 / v0.8.0 / v0.9.0 新增文件清单

| 文件 | 作用 |
|---|---|
| `platform/image/ImageStore.kt` | 选图→压缩（长边1280/JPEG80）→存内部目录→base64 |
| `model/schema/ReceiptParser.kt` | 识图结果容错解析（**用正则不用 org.json**：后者在 JVM 单测里是死代码） |
| `domain/parse/MoneyParser.kt` | 金额文本→分（v0.8.0）。**全程不碰浮点**，直接拼"分"字符串再 toLong |
| `domain/parse/LedgerRange.kt` | 账本统计区间（v0.9.0）：今天/本周/本月。用"月末+1天起点-1毫秒"收尾，闰年平年自动正确 |
| `test/ReceiptParserTest.kt` | 10 个用例：围栏/废话/非账单/认不出金额/低把握/日期格式 |
| `test/VisionConfigMergeTest.kt` | 5 个用例：留空沿用主配置的合并规则 |
| `test/MoneyParserTest.kt` | 15 个用例：**浮点陷阱专项**（38.5→3850 而不是 3849）+ 非法输入 + 往返一致 |
| `test/LedgerRangeTest.kt` | 12 个用例（v0.9.0）：月末边界 / 闰年2月 / 周一开头 / 闭区间两端 |
| `tools/check_db.py` | 查主库（**注意 WAL 坑，可能读到旧快照**） |
| `tools/check_db_full.py` | db+wal+shm 一起打开，自动回放 WAL —— 查真实数据用这个 |

---

## 十、给新会话的一句话

**这是个"宁可少做功能，也要不丢数据、不记错账、不放假按钮"的项目。**
遇到取舍选更保守的方案；被质疑先实测再回应；文档已齐（AGENTS.md 有全部禁忌），别重新发明轮子。
