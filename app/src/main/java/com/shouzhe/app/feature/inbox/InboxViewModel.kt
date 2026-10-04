package com.shouzhe.app.feature.inbox

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.core.time.TimeParser
import com.shouzhe.app.data.db.entity.LedgerEntryEntity
import com.shouzhe.app.data.db.entity.TodoMetaEntity
import com.shouzhe.app.data.db.ShouzheDatabase
import com.shouzhe.app.data.repository.ItemRepository
import com.shouzhe.app.domain.model.*
import com.shouzhe.app.domain.parse.LedgerRange
import com.shouzhe.app.domain.parse.ParsedEntry
import com.shouzhe.app.domain.parse.RuleParser
import com.shouzhe.app.model.gateway.ClassifyResult
import com.shouzhe.app.model.gateway.ModelGateway
import com.shouzhe.app.platform.extract.ExtractOutcome
import com.shouzhe.app.platform.image.ImageStore
import com.shouzhe.app.platform.notify.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** 账本统计口径 */
enum class SummaryScope(val label: String) {
    TODAY("今天"),
    THIS_WEEK("本周"),
    THIS_MONTH("本月"),
}

data class InboxUiState(
    val items: List<Item> = emptyList(),
    val filter: InboxFilter = InboxFilter.ALL,
    val pendingConfirmCount: Int = 0,
    val loading: Boolean = false,
    val message: String? = null,
    /** 账本汇总（v0.9.0）；只统计已确认的数字 */
    val summary: LedgerSummary = LedgerSummary(0L, 0L, emptyList()),
    val summaryScope: SummaryScope = SummaryScope.THIS_MONTH,
)

/** 解析预览 —— 快速录入浮层用 */
data class ParsePreview(
    val typeLabel: String,
    val detail: String,
    val confirmed: Boolean,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repo: ItemRepository,
    private val gateway: ModelGateway,
    private val scheduler: AlarmScheduler,
    private val db: ShouzheDatabase,
    private val images: ImageStore,
) : ViewModel() {

    private val filter = MutableStateFlow(InboxFilter.ALL)
    private val message = MutableStateFlow<String?>(null)
    private val summaryScope = MutableStateFlow(SummaryScope.THIS_MONTH)

    /**
     * 汇总刷新信号。
     *
     * 用"信号 + flatMapLatest"而不是在 combine 里现算 —— combine 的回调是
     * 挂起函数也能调，但那样每次列表变化都会重跑 SQL；而且确认/编辑账目后
     * **列表可能没变**（比如改的金额不影响条数），光靠监听 items 会漏刷新。
     * 所以账目相关的写操作一律显式 bump 这个信号。
     */
    private val summaryTrigger = MutableStateFlow(0)

    private val summaryFlow = combine(summaryScope, summaryTrigger) { s, _ -> s }
        .flatMapLatest { scope ->
            flow { emit(repo.ledgerSummary(rangeOf(scope))) }
        }

    private fun rangeOf(scope: SummaryScope) = when (scope) {
        SummaryScope.TODAY -> LedgerRange.today(Instant.now())
        SummaryScope.THIS_WEEK -> LedgerRange.thisWeek(Instant.now())
        SummaryScope.THIS_MONTH -> LedgerRange.thisMonth(Instant.now())
    }

    private fun bumpSummary() { summaryTrigger.value += 1 }

    val state: StateFlow<InboxUiState> = combine(
        filter.flatMapLatest { f ->
            if (f.type == null) repo.observeInbox() else repo.observeByType(f.type)
        },
        filter,
        message,
        repo.observeInboxCount(),
        summaryFlow,
        summaryScope,
    ) { arr ->
        InboxUiState(
            items = arr[0] as List<Item>,
            filter = arr[1] as InboxFilter,
            pendingConfirmCount = (arr[0] as List<Item>).count {
                it.type == ItemType.LEDGER && it.ledger?.confirmed == false
            },
            message = arr[2] as String?,
            summary = (arr[4] as LedgerSummary),
            summaryScope = arr[5] as SummaryScope,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InboxUiState())

    /** 切换账本统计口径（今天 / 本周 / 本月） */
    fun setSummaryScope(scope: SummaryScope) {
        summaryScope.value = scope
    }

    init {
        // 启动后把积压的抽取任务跑掉
        viewModelScope.launch {
            repeat(5) { if (!repo.processNextExtractJob()) return@repeat }
        }
    }

    fun setFilter(f: InboxFilter) { filter.value = f }

    /** 从详情页返回时刷新（可能改过数据） */
    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.processNextExtractJob() }
        }
        // 从详情页返回时可能确认或改过账目，汇总要跟上
        bumpSummary()
    }

    /** 首次数据是否已到（供启动页判断何时收起） */
    fun isLoaded(): Boolean = loadedOnce
    private var loadedOnce = false

    init {
        viewModelScope.launch {
            state.collect { s ->
                if (!loadedOnce && !s.loading) loadedOnce = true
            }
        }
    }

    fun dismissMessage() { message.value = null }

    /** 收一句话：先走本地规则引擎，没把握才调模型 —— 没配 Key 也能记 */
    fun ingestText(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // 1) 先落库（网络不通也不能丢）
            val idResult = repo.ingestText(text, Origin.QUICK_ADD)
            val id = idResult.getOrNull() ?: run {
                message.value = "保存失败"
                return@launch
            }

            // 2) 本地规则引擎优先：离线、零成本、毫秒级
            val ruleResult = RuleParser.parse(text)
            val highConfidence = ruleResult.entries.all { it.confidence >= 0.7 }

            if (highConfidence && ruleResult.entries.isNotEmpty()) {
                // 规则解析足够可信 → 直接应用，不消耗模型 token
                ruleResult.entries.forEachIndexed { index, entry ->
                    val entryId = if (index == 0) id else {
                        // 多笔：第一笔用已落的库，其余新建
                        repo.ingestText(entry.noteText ?: entry.title, Origin.QUICK_ADD)
                            .getOrNull() ?: id
                    }
                    applyParsedEntry(entryId, entry)
                }
                if (ruleResult.entries.size > 1) {
                    message.value = "识别出 ${ruleResult.entries.size} 笔，待确认"
                }
                // 笔记类内容补摘要与标签（v0.10.0）。
                // 账目/待办不补：它们有结构化字段，标签是噪音。
                if (ruleResult.entries.size == 1 &&
                    ruleResult.entries.first().type == ItemType.NOTE
                ) {
                    enrich(id, text)
                }
                onDone()
                return@launch
            }

            // 3) 规则没把握 → 调模型
            when (val r = gateway.classify(text, Instant.now())) {
                is Outcome.Ok -> {
                    applyClassify(id, r.value)
                }
                is Outcome.Err -> {
                    // 4) 模型也失败 → 回退到规则结果（哪怕低置信度），内容绝不丢
                    applyParsedEntry(id, ruleResult.entries.first())
                    message.value = r.error.userHint()
                }
            }
            onDone()
        }
    }

    /** 收一个链接：立刻落库 + 建抽取任务 */
    fun ingestUrl(url: String, sourceApp: String? = null, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.ingestUrl(url, Origin.SHARE, sourceApp).getOrNull()
            if (id == null) {
                message.value = "保存失败"
            } else {
                message.value = "收下了，正在抓正文…"
                val ok = repo.processNextExtractJob()
                // 抓到了正文就补摘要与标签（正文比开头那行标题信息量大得多）；
                // 没抓到也不影响：链接已经存下，用户东西没丢
                if (ok) enrichAfterExtract(id)
            }
            onDone()
        }
    }

    /**
     * 截图记账（v0.7.0）。
     *
     * 顺序就是铁律：**先存图 → 再识别 → 认出来才转记账草稿**。
     * 没配识图模型、供应商不支持识图、网络断了、模型没看懂 ——
     * 任何一种情况下图和条目都已经在库里，一条不丢，只是没帮你记上账。
     */
    fun ingestReceipt(uri: Uri, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            message.value = "正在收下这张图…"

            val stored = images.importAndStore(uri)
            if (stored == null) {
                message.value = "这张图读不出来，换一张试试"
                onDone()
                return@launch
            }

            // 落库先于识别：这一步之后，用户的图就再也不会丢
            val id = repo.ingestImage(stored.path, "一张待整理的截图", Origin.MANUAL).getOrNull()
            if (id == null) {
                message.value = "保存失败"
                onDone()
                return@launch
            }

            val b64 = images.encodeBase64(stored.path)
            if (b64 == null) {
                message.value = "图已经存下了，但读不出来"
                onDone()
                return@launch
            }

            message.value = "正在看图…"
            when (val r = gateway.recognizeReceipt(b64, stored.mimeType, Instant.now())) {
                is Outcome.Err -> {
                    // 降级：图留着，条目留着，如实说明为什么没记账
                    message.value = "图存下了，没记账：${r.error.userHint()}"
                }
                is Outcome.Ok -> {
                    val res = r.value
                    if (!res.usableAsLedger) {
                        // 不是账单 / 认不出金额 → 仍是笔记，把读到的文字存进摘要便于日后人工整理
                        repo.setSummary(id, res.excerpt ?: res.title)
                        message.value = when {
                            !res.isBill -> "看着不像账单，图给你存着了"
                            else -> "图存下了，但金额没认出来"
                        }
                    } else {
                        val occurred = res.occurredAt ?: Instant.now()
                        repo.applyReceiptDraft(
                            itemId = id,
                            title = res.title,
                            amountCents = res.amountCents,
                            direction = res.direction,
                            category = res.category,
                            merchant = res.merchant,
                            occurredAt = occurred,
                            summary = res.excerpt,
                        )
                        // 一律 confirmed=false 的草稿，必须用户点确认才计入账本（铁律 2）
                        message.value = when {
                            !res.confident -> "记了但不太确定，请核对金额"
                            res.occurredAt == null -> "记好了（日期没认出来，按今天记的），待确认"
                            else -> "记好了，待确认"
                        }
                    }
                }
            }
            onDone()
        }
    }

    fun confirmLedger(itemId: Long) {
        viewModelScope.launch {
            repo.confirmLedger(itemId)
            // 确认后才计入账本 → 汇总数字要跟着变
            bumpSummary()
        }
    }

    fun markDone(itemId: Long) {
        viewModelScope.launch {
            repo.markDone(itemId)
            scheduler.cancel(itemId)
        }
    }

    // ------------------------------------------------------------------

    /**
     * 给一条内容补摘要与标签（v0.10.0 接线）。
     *
     * 设计约束：
     * - **安静降级**：没配 Key、模型挂了、网络不通 —— 一律吞掉，绝不影响录入。
     *   用户已经在界面上看到"收下了"，不能因为 AI 环节失败就弹个报错吓他。
     * - **不覆盖已有摘要**：用户手动生成过的摘要比自动的更可信。
     * - 摘要与标签各自独立：一个失败不影响另一个。
     */
    private suspend fun enrich(id: Long, text: String) {
        if (text.isBlank()) return

        // 摘要：只在还没有摘要时补
        val existing = repo.findById(id)?.summary
        if (existing.isNullOrBlank()) {
            when (val s = gateway.summarize(text)) {
                is Outcome.Ok -> if (s.value.isNotBlank()) repo.setSummary(id, s.value)
                is Outcome.Err -> Unit
            }
        }

        // 标签：复用已有标签名，让模型倾向续用而不是每篇造新词
        val existingNames = runCatching { db.tagDao().all().map { it.name } }.getOrDefault(emptyList())
        when (val t = gateway.tag(text, existingNames)) {
            is Outcome.Ok -> if (t.value.isNotEmpty()) repo.setTags(id, t.value)
            is Outcome.Err -> Unit
        }
    }

    /**
     * 文章抓完正文后调用（正文比标题信息量大得多，值得重新补一次）。
     */
    fun enrichAfterExtract(itemId: Long) {
        viewModelScope.launch {
            val it = repo.findById(itemId) ?: return@launch
            val content = it.rawText?.takeIf { c -> c.isNotBlank() } ?: return@launch
            enrich(itemId, content)
        }
    }

    /** 给一条内容补摘要与标签（供手动触发；失败静默） */
    fun enrichNow(itemId: Long) {
        viewModelScope.launch {
            val it = repo.findById(itemId) ?: return@launch
            enrich(itemId, it.summary ?: it.rawText ?: it.title)
        }
    }

    private suspend fun applyClassify(id: Long, r: ClassifyResult) {
        val now = Instant.now()
        val itemDao = db.itemDao()

        when (r.type) {
            ItemType.TODO -> {
                val due = TimeParser.parse(r.timeExpression, now)
                val remind = TimeParser.parse(r.remindExpression, now) ?: due
                db.todoMetaDao().upsert(
                    TodoMetaEntity(
                        itemId = id,
                        dueAt = due?.toEpochMilli(),
                        remindAt = remind?.toEpochMilli(),
                        remindState = if (remind != null) "SCHEDULED" else "NONE",
                        priority = 0,
                        repeatRule = null,
                        completedAt = null,
                        snoozeCount = 0,
                    )
                )
                itemDao.updateStatus(id, ItemStatus.INBOX.name, now.toEpochMilli())
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE item SET type = ?, title = ? WHERE id = ?",
                    arrayOf(ItemType.TODO.name, r.title, id),
                )
                if (remind != null) {
                    scheduler.schedule(id, r.title, null, remind)
                }
            }

            ItemType.LEDGER -> {
                val draft = r.ledger
                if (draft == null) {
                    // 模型说是记账但没给金额 → 退回笔记，让用户手改
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE item SET title = ? WHERE id = ?",
                        arrayOf(r.title, id),
                    )
                } else {
                    db.ledgerDao().upsert(
                        LedgerEntryEntity(
                            itemId = id,
                            amountCents = draft.amountCents,
                            direction = draft.direction.name,
                            category = draft.category,
                            merchant = draft.merchant,
                            occurredAt = now.toEpochMilli(),
                            // 关键：先落草稿，必须经用户确认才计入账本
                            confirmed = false,
                        )
                    )
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE item SET type = ?, title = ? WHERE id = ?",
                        arrayOf(ItemType.LEDGER.name, r.title, id),
                    )
                }
            }

            ItemType.NOTE -> {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE item SET title = ? WHERE id = ?",
                    arrayOf(r.title, id),
                )
            }

            else -> Unit
        }

        // 摘要 + 标签异步补（统一入口，见 enrich）
        if (r.type == ItemType.ARTICLE || r.type == ItemType.NOTE) {
            enrich(id, r.noteText ?: r.title)
        }
    }

    /**
     * 应用规则解析结果（结构比模型输出多笔，逐条落库）。
     * 账目仍走 confirmed=false 草稿流程；待办排期与模型路径完全一致。
     */
    private suspend fun applyParsedEntry(id: Long, entry: ParsedEntry) {
        val now = Instant.now()
        val itemDao = db.itemDao()

        when (entry.type) {
            ItemType.LEDGER -> {
                val draft = entry.ledger
                if (draft != null) {
                    db.ledgerDao().upsert(
                        LedgerEntryEntity(
                            itemId = id,
                            amountCents = draft.amountCents,
                            direction = draft.direction.name,
                            category = draft.category,
                            merchant = draft.merchant,
                            occurredAt = now.toEpochMilli(),
                            confirmed = false,   // 铁律：规则解析也必须经确认
                        )
                    )
                    itemDao.updateSummary(id, "", now.toEpochMilli())
                }
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE item SET type = ?, title = ? WHERE id = ?",
                    arrayOf(ItemType.LEDGER.name, entry.title, id),
                )
            }

            ItemType.TODO -> {
                val remind = TimeParser.parse(entry.remindExpression, now)
                db.todoMetaDao().upsert(
                    TodoMetaEntity(
                        itemId = id,
                        dueAt = remind?.toEpochMilli(),
                        remindAt = remind?.toEpochMilli(),
                        remindState = if (remind != null) "SCHEDULED" else "NONE",
                        priority = 0,
                        repeatRule = null,
                        completedAt = null,
                        snoozeCount = 0,
                    )
                )
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE item SET type = ?, title = ? WHERE id = ?",
                    arrayOf(ItemType.TODO.name, entry.title, id),
                )
                if (remind != null) {
                    scheduler.schedule(id, entry.title, null, remind)
                }
            }

            ItemType.NOTE -> {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE item SET title = ? WHERE id = ?",
                    arrayOf(entry.title, id),
                )
            }

            else -> Unit
        }
    }
}
