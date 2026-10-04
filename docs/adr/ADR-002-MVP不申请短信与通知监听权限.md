# ADR-002：MVP 不申请短信权限与通知监听权限

- **状态**：已接受
- **日期**：2026-10-03
- **决策者**：高级架构师（安全/合规角色观点并入）
- **相关角色**：产品经理

---

## 背景

产品设想包含"记账"线。用户提到一个重要场景：

> **"买个烧饼 1 块钱，钱太少就不记了。"**

一个自然的设想是：**自动读取银行/支付宝的短信或通知，自动记账**，彻底免去手动输入。

产品经理在讨论中询问参考项目 [daodian](https://github.com/Dudu0831/daodian) 是否具备短信抓取能力。

### 事实核查（对参考项目 Manifest 的完整解码核对）

参考项目 `daodian` 声明的**全部**权限：

`USE_EXACT_ALARM`、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、
`USE_FULL_SCREEN_INTENT`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、
`VIBRATE`、`WAKE_LOCK`、`INTERNET`、`REQUEST_INSTALL_PACKAGES`、`RECORD_AUDIO`

**结论：不含任何短信权限**（无 `READ_SMS` / `RECEIVE_SMS`）。

它通过 `NotificationListenerService`（通知监听）**旁路**看到短信 App 弹出的通知，
但其设计原则明确为"**只抓、只分，不存、不读**"——短信仅是被路过的通知，并非独立能力。

### 政策与合规核实

| 权限 | 政策要求 | 来源 |
|---|---|---|
| `READ_SMS` / `RECEIVE_SMS` | 应用**必须是指定的短信/电话/助理默认处理程序**才能申请 | [Play 管理中心](https://support.google.com/googleplay/android-developer/answer/9888170?hl=zh-Hans) |
| 通知监听（`NOTIFICATION_LISTENER`） | 与 `RECEIVE_SMS` 等并列为**高风险权限**，常被滥用于**金融欺诈**；侧载来源且带这些权限的应用，**Play Protect 会自动阻止安装** | [Play Protect 警告指南](https://developers.google.com/android/play-protect/warning-dev-guidance?hl=zh-cn) |

---

## 决策

**MVP 阶段不申请短信权限，也不申请通知监听权限。通知监听推迟至 V1.1。**

### 理由

1. **短信权限实际上拿不到**：除非 App 本身是默认短信处理器，否则 Google Play 直接拒绝。
   一个记账/收集类 App 不可能满足该条件。
2. **通知监听会阻断侧载安装**：本项目分发方式为 GitHub Releases 侧载 APK。
   若声明通知监听，**Play Protect 可能直接阻止用户安装**，
   并弹出"高风险应用"警告——**这会摧毁新用户的第一次体验**。
3. **MVP 核心不依赖它**：本产品的核心价值是"收文章 + 提纯 + 找回"，
   自动记账只是"行动"线的增强项，**不是主干**。
4. **可以事后加，不能事后减**：权限一旦声明就吓退用户；
   而 V1.1 再补通知监听，**老用户无感**（只需新授权），代价远小于一开始就上。
5. **合规立场**：涉敏权限应"最小必要"，先证明确有价值再申请。

---

## 后果

### 正面

- MVP 权限集最小，安装顺畅，无 Play Protect 拦截风险。
- 用户不会有"这 App 要读我所有通知"的恐惧。
- 架构上保留扩展位：`platform/` 层预留 `intake/` 包，V1.1 直接加。

### 负面 / 代价

| 代价 | 说明 |
|---|---|
| **自动记账缺席 MVP** | 记账需用户手输或说一句话，不能自动抓支付通知 |
| **"1 块钱烧饼"场景仍需一次输入** | 但已是"说一句话"，成本远低于填表单，核心痛点仍被解决 |
| V1.1 需重新走一遍权限申请 | 可接受，且届时有真实用户反馈支撑 |

### V1.1 的推荐路径

> **用「通知监听」而非「短信权限」实现自动记账。**

理由：**覆盖面更大**——支付宝、微信支付、银行 App 的支付通知都能抓到，
远比只盯短信广泛；且**同样不需要短信权限**。

V1.1 实现时必须遵守：
- 首次启用前**明确告知**用途，并引导用户到系统设置手动开启。
- 遵守参考项目验证过的原则：**只抓支付相关、只分不存**，原始通知不落库。
- README 与隐私说明中**显式披露**该权限用途。

### 对产品的影响（需产品经理知悉）

本决策使 MVP 的记账体验**依赖用户主动输入（说话或打字）**，
而非"全自动"。这与产品的核心洞察一致但不完全等同——
**"1 块钱也值得记"靠的是把输入成本降到一句话，而不是靠全自动。**

如果后续验证发现"必须全自动才有价值"，则应提前 V1.1 排期，
但仍**不建议**使用短信权限。

---

## 被否决的方案

| 方案 | 否决理由 |
|---|---|
| MVP 直接申请短信权限 | 政策上拿不到；劝退用户；分发受阻 |
| MVP 直接上通知监听 | 触发 Play Protect 拦截风险，伤害首次安装体验 |
| 让用户手动导入账单 CSV | 体验割裂，且多数用户拿不到结构化账单 |

---

## 参考

- [Play 管理中心 · 敏感信息访问权限和 API](https://support.google.com/googleplay/android-developer/answer/9888170?hl=zh-Hans)
- [Play Protect 开发者警告指南](https://developers.google.com/android/play-protect/warning-dev-guidance?hl=zh-cn)
- [daodian 项目](https://github.com/Dudu0831/daodian) —— 通知监听"只抓只分不存不读"的设计范例
