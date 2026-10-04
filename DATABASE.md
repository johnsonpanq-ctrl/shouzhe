# 收着（ShouZhe）· 数据库设计

> 版本：v1.0
> 阶段：阶段二 · 高级架构师
> 上游：`ARCHITECTURE.md`

---

## 0. 设计原则

1. **单一数据库**：`shouzhe.db`（Room/SQLite）。不为四条线各开一个库——它们共享同一条"收进来"的主线。
2. **统一主表 + 分型子表**：所有内容都是 `item`，用 `type` 区分（`article` / `note` / `todo` / `ledger`），避免四套并行模型。
3. **预留 V1.1**：`type` 字段与 `item_embedding` 表现在就留好，后续加语义检索不重构。
4. **本地优先**：无同步字段（不做云同步），但保留 `uuid` 便于未来导出/迁移。
5. **时区安全**：所有时间**以 UTC 毫秒存储**，展示时转本地时区。

---

## 1. ER 总览

```
                        ┌──────────────┐
                        │     item     │  ← 统一主表（四条线共用）
                        │──────────────│
                        │ id (PK)      │
                        │ uuid         │
                        │ type         │  article | note | todo | ledger
                        │ title        │
                        │ raw_text     │  用户输入/抽取正文
                        │ summary      │  AI 摘要
                        │ source_url   │
                        │ status       │  inbox | archived | done
                        │ created_at   │
                        └──────┬───────┘
                               │ 1:1（按 type 存在对应子表）
        ┌──────────────┬───────┴────────┬──────────────┐
        ▼              ▼                ▼              ▼
┌──────────────┐ ┌──────────┐  ┌──────────────┐ ┌──────────────┐
│ article_meta │ │ todo_meta│  │ ledger_entry │ │  extract_job │
│ 抽取元信息    │ │ 待办/提醒 │  │   记账        │ │  抽取任务     │
└──────────────┘ └──────────┘  └──────────────┘ └──────────────┘
                               │
        ┌──────────────┬───────┴────────┬──────────────┐
        ▼              ▼                ▼              ▼
┌──────────────┐ ┌──────────┐  ┌──────────────┐ ┌──────────────┐
│     tag      │ │ item_tag │  │ model_call   │ │ item_fts     │
│  标签字典     │ │  关联表   │  │ 调用记录/成本 │ │  全文检索     │
└──────────────┘ └──────────┘  └──────────────┘ └──────────────┘
```

---

## 2. 表结构

### 2.1 `item` —— 统一主表

所有收进来的东西都先落这张表。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | INTEGER | PK AUTOINCREMENT | 内部主键 |
| `uuid` | TEXT | UNIQUE NOT NULL | 导出/迁移用稳定标识 |
| `type` | TEXT | NOT NULL | `article` / `note` / `todo` / `ledger` |
| `title` | TEXT | | 标题（文章标题 / 待办标题 / 记账摘要） |
| `raw_text` | TEXT | | 用户原话 或 抽取的正文纯文本 |
| `summary` | TEXT | | AI 生成摘要 |
| `source_url` | TEXT | | 来源链接（文章类必有） |
| `source_app` | TEXT | | 来源 App 包名（如 `com.tencent.mm`），便于统计与过滤 |
| `status` | TEXT | NOT NULL DEFAULT 'inbox' | `inbox` / `archived` / `done` / `deleted` |
| `quality` | TEXT | | 抽取质量：`good` / `poor` / `failed`（见 §3 降级） |
| `created_at` | INTEGER | NOT NULL | UTC 毫秒 |
| `updated_at` | INTEGER | NOT NULL | UTC 毫秒 |
| `archived_at` | INTEGER | | 归档时间 |

**索引**：
```sql
CREATE INDEX idx_item_type_status   ON item(type, status);
CREATE INDEX idx_item_created       ON item(created_at DESC);
CREATE INDEX idx_item_source_app    ON item(source_app);
```

**设计说明**：
- `type` 一旦写入**不可变更**（换类型 = 删除重建），避免子表孤儿。
- `status` 用软删除（`deleted`），不物理删除，防误删。

---

### 2.2 `article_meta` —— 文章扩展（type = article）

| 字段 | 类型 | 说明 |
|---|---|---|
| `item_id` | INTEGER | PK, FK → item.id, ON DELETE CASCADE |
| `author` | TEXT | 作者 / 公众号名 |
| `published_at` | INTEGER | 原文发布时间 |
| `site_name` | TEXT | 站点名（微信 / 头条 / 其他） |
| `word_count` | INTEGER | 正文字数（用于质量判断） |
| `cover_image` | TEXT | 封面图 URL |
| `raw_html` | TEXT | 原始 HTML（可选，占用大，默认不存） |

**索引**：`CREATE INDEX idx_article_site ON article_meta(site_name);`

---

### 2.3 `todo_meta` —— 待办/提醒扩展（type = todo）

| 字段 | 类型 | 说明 |
|---|---|---|
| `item_id` | INTEGER | PK, FK → item.id, ON DELETE CASCADE |
| `due_at` | INTEGER | 截止/提醒时间（UTC 毫秒），NULL = 无时间 |
| `remind_at` | INTEGER | 实际提醒时间（可与 due_at 不同，如提前一天） |
| `remind_state` | TEXT | `none` / `scheduled` / `fired` / `done` / `missed` |
| `priority` | INTEGER | 0=无 1=低 2=中 3=高 |
| `repeat_rule` | TEXT | RRULE 字符串（V1.1，预留） |
| `completed_at` | INTEGER | 完成时间 |
| `snooze_count` | INTEGER | 推迟次数（用于观察用户行为） |

**索引**：
```sql
CREATE INDEX idx_todo_remind  ON todo_meta(remind_state, remind_at);
CREATE INDEX idx_todo_due     ON todo_meta(due_at);
```

**关键**：`idx_todo_remind` 是**开机全量重排**的查询依据——必须高效。

---

### 2.4 `ledger_entry` —— 记账（type = ledger）

| 字段 | 类型 | 说明 |
|---|---|---|
| `item_id` | INTEGER | PK, FK → item.id, ON DELETE CASCADE |
| `amount` | INTEGER | **金额以"分"存储**（避免浮点误差） |
| `direction` | TEXT | `out`（支出）/ `in`（收入） |
| `category` | TEXT | 分类（餐饮 / 交通 / 日用…） |
| `merchant` | TEXT | 商家/对象 |
| `occurred_at` | INTEGER | 发生时间（UTC 毫秒） |
| `confirmed` | INTEGER | 0/1 —— **是否经用户确认**（见 §4） |

**索引**：
```sql
CREATE INDEX idx_ledger_time     ON ledger_entry(occurred_at DESC);
CREATE INDEX idx_ledger_category ON ledger_entry(category);
```

**重要**：`amount` 用 **INTEGER 存分**，**绝不使用 REAL**。
这是记账类应用的铁律——浮点数会导致对账差几分钱，用户会失去信任。

---

### 2.5 `extract_job` —— 正文抽取任务

抽正文是**异步、可能失败、需要重试**的，所以独立成表。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER | PK AUTOINCREMENT |
| `item_id` | INTEGER | FK → item.id, ON DELETE CASCADE |
| `url` | TEXT | 待抽取 URL |
| `state` | TEXT | `pending` / `running` / `success` / `failed` |
| `attempts` | INTEGER | 已尝试次数 |
| `last_error` | TEXT | 最后错误信息（用于诊断） |
| `created_at` | INTEGER | |
| `finished_at` | INTEGER | |

**索引**：`CREATE INDEX idx_extract_state ON extract_job(state, created_at);`

**为什么独立**：抽取失败要能**单独重试**，不影响 item 本身；
抽取中 App 被杀要能**恢复**（`running` 状态在启动时重置为 `pending`）。

---

### 2.6 `tag` / `item_tag` —— 标签

```sql
CREATE TABLE tag (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL UNIQUE,
    color      TEXT,
    use_count  INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE item_tag (
    item_id INTEGER NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tag(id)  ON DELETE CASCADE,
    source  TEXT,          -- 'ai' | 'user'  （区分 AI 打的还是用户打的）
    PRIMARY KEY (item_id, tag_id)
);
```

**索引**：`CREATE INDEX idx_item_tag_tag ON item_tag(tag_id);`

---

### 2.7 `item_fts` —— 全文检索（MVP 的"找回"能力）

```sql
CREATE VIRTUAL TABLE item_fts USING fts4(
    title, raw_text, summary, tags,
    content='item'
);
```

**说明**：
- SQLite **内置** FTS4，零额外依赖、零模型成本。
- 采用 **external content 模式**（`content='item'`），避免数据双份存储。
- 需用触发器保持同步（Room 中通过 `@Fts4` + 手动维护）。
- **中文分词注意**：FTS4 默认可按字切分，中文需用 `tokenize=simple` 或自行分词。
  **MVP 建议先用 unicode61 + 前端额外按关键词 LIKE 兜底**，V1.1 引入 better 分词方案。

---

### 2.8 `item_embedding` —— 语义检索（V1.1，现在预留）

```sql
CREATE TABLE item_embedding (
    item_id    INTEGER PRIMARY KEY REFERENCES item(id) ON DELETE CASCADE,
    vector     BLOB NOT NULL,      -- FloatArray 序列化
    dim        INTEGER NOT NULL,
    model      TEXT NOT NULL,      -- 记录用的哪个 embedding 模型
    created_at INTEGER NOT NULL
);
```

**现在只建表不写入**，等 V1.1 做语义检索时不需迁移。

---

### 2.9 `model_call` —— 模型调用记录（成本可见）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER | PK |
| `provider` | TEXT | deepseek / qwen / kimi / openai |
| `model_name` | TEXT | |
| `purpose` | TEXT | `classify` / `summarize` / `tag` / `embed` |
| `item_id` | INTEGER | 可为空 |
| `prompt_tokens` | INTEGER | |
| `completion_tokens` | INTEGER | |
| `latency_ms` | INTEGER | |
| `success` | INTEGER | 0/1 |
| `error` | TEXT | |
| `created_at` | INTEGER | |

**⚠️ 绝不记录请求/响应正文**——可能含用户隐私内容。
只记 token 数与耗时，用于成本统计与性能诊断。

---

## 3. 抽取质量与降级策略（数据层落地）

`item.quality` 与 `extract_job` 配合，实现立项书要求的"必须带降级"：

| 场景 | `item.quality` | `extract_job.state` | 收件箱展示 |
|---|---|---|---|
| 抽取成功且字数充足 | `good` | `success` | 正常显示摘要 |
| 抽取成功但字数偏少 | `poor` | `success` | 显示"内容可能不完整"+ 去原文 |
| 抽取失败 | `failed` | `failed` | 显示"仅存了链接"+ 重试按钮 |
| 尚未抽取 | NULL | `pending`/`running` | 显示"正在收着…" |

**兜底原则**：**任何一条内容进来，都必须至少留下 `source_url`。**
哪怕抽取全败，用户也不会"东西丢了"。

---

## 4. 记账的二次确认（数据层强制）

`ledger_entry.confirmed` 的设计意图：

- AI 解析出的记账，**先以 `confirmed = 0` 落库**（草稿态）。
- 收件箱里显示确认卡片："记一笔：烧饼 **¥1.00**（餐饮）" → 用户点确认 → `confirmed = 1`。
- **统计口径**：账本/汇总**只统计 `confirmed = 1`** 的记录。
- `confirmed = 0` 超过 7 天未确认 → 自动标记 `deleted`（避免堆积垃圾）。

**为什么这样做**：记账错一次，用户就再也不信了。
宁可多一次点击，也不能让错误数字进账本。

---

## 5. 迁移策略

- 使用 **Room 的 `Migration`**，**禁止** `fallbackToDestructiveMigration()`。
  理由：这是用户唯一的本地数据，**丢了就没了**（没有云端可恢复）。
- 每次 schema 变更导出 JSON 到 `app/schemas/` 并入库（便于审阅 diff）。
- 迁移必须有**测试**（Room 提供 `MigrationTestHelper`）。
- **迁移前自动备份**：`shouzhe.db` 复制为 `shouzhe.db.bak-<version>`，保留最近 3 份。

---

## 6. 数据安全

| 项 | 做法 |
|---|---|
| API Key 存储 | **EncryptedSharedPreferences**（或 DataStore + 手动加密），**绝不明文** |
| Key 日志 | 全局禁止打印 Key，日志脱敏 |
| 数据库 | 明文 SQLite（自用项目可接受）；V1.1 可评估 SQLCipher |
| 备份 | `android:allowBackup="false"`（避免用户数据被云备份到未知位置） |
| 导出 | 提供手动导出（JSON），用户自行保管 |

---

## 7. 待确认

| # | 事项 | 建议 |
|---|---|---|
| D1 | 中文全文检索方案 | MVP 用 FTS4 + LIKE 兜底；V1.1 评估分词 |
| D2 | 是否加密数据库 | MVP **不加密**（自用、性能优先） |
| D3 | 原始 HTML 是否保存 | **默认不存**（体积大），失败时可选保留供诊断 |

---

**确认门**：请审阅。确认后输出 `API.md` 与 ADR-001。
