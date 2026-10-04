package com.shouzhe.app.feature.detail

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.core.time.TimeParser
import com.shouzhe.app.data.db.ShouzheDatabase
import com.shouzhe.app.data.db.entity.LedgerEntryEntity
import com.shouzhe.app.data.db.entity.TodoMetaEntity
import com.shouzhe.app.data.repository.ItemRepository
import com.shouzhe.app.domain.model.*
import com.shouzhe.app.domain.parse.MoneyParser
import com.shouzhe.app.model.gateway.ModelGateway
import com.shouzhe.app.platform.image.ImageStore
import com.shouzhe.app.platform.notify.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 编辑中的时间草稿 —— 用户改了但还没保存 */
data class DetailUiState(
    val item: Item? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val editing: Boolean = false,
    /** 待办编辑：时间表达式的文字输入 */
    val timeDraft: String = "",
    val timeDraftError: String? = null,
    val titleDraft: String = "",
    val message: String? = null,
    val summarizing: Boolean = false,
    /** 截图记账的原图（v0.7.0）；文件没了就是 null，界面不显示这一块 */
    val sourceImage: Bitmap? = null,
    // ---- 账目编辑（v0.8.0） ----
    /** 编辑态开关（账目专用，与待办的 editing 分开，避免两种表单打架） */
    val editingLedger: Boolean = false,
    /** 金额草稿（元文本，如 "38.50"） */
    val ledgerAmountDraft: String = "",
    val ledgerAmountError: String? = null,
    val ledgerDirectionDraft: LedgerDirection = LedgerDirection.OUT,
    val ledgerCategoryDraft: String = "",
    val ledgerMerchantDraft: String = "",
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: ItemRepository,
    private val gateway: ModelGateway,
    private val scheduler: AlarmScheduler,
    private val db: ShouzheDatabase,
    private val images: ImageStore,
) : ViewModel() {

    /**
     * 当前查看的条目。
     * 本项目不用 Navigation 库，itemId 由界面层通过 load(id) 传入；
     * 用 key(itemId) 包裹 Composable 保证连续看两条时重建。
     */
    private val itemId = MutableStateFlow(-1L)

    private val item = MutableStateFlow<Item?>(null)
    private val loading = MutableStateFlow(true)
    private val editing = MutableStateFlow(false)
    private val timeDraft = MutableStateFlow("")
    private val timeDraftError = MutableStateFlow<String?>(null)
    private val titleDraft = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)
    private val summarizing = MutableStateFlow(false)
    private val sourceImage = MutableStateFlow<Bitmap?>(null)

    /**
     * 聚合用的小容器 —— combine 的参数上限是 9 个（超过要打包成数组），
     * 与其踩那个坑，不如直接自己聚合，可读性也更好。
     */
    private data class LedgerDraftState(
        val editing: Boolean = false,
        val amount: String = "",
        val amountError: String? = null,
        val direction: LedgerDirection = LedgerDirection.OUT,
        val category: String = "",
        val merchant: String = "",
    )

    private val ledgerDraft = MutableStateFlow(LedgerDraftState())

    /** 中间聚合：把零散字段先合成两个小对象，再合成最终 UI 状态 */
    private data class Core(
        val item: Item?,
        val loading: Boolean,
        val editing: Boolean,
        val timeDraft: String,
        val timeDraftError: String?,
        val titleDraft: String,
        val message: String?,
        val sourceImage: Bitmap?,
        val summarizing: Boolean,
    )

    private val core = combine(
        item, loading, editing, timeDraft, timeDraftError, titleDraft, message,
        sourceImage, summarizing,
    ) { a ->
        Core(
            item = a[0] as Item?,
            loading = a[1] as Boolean,
            editing = a[2] as Boolean,
            timeDraft = a[3] as String,
            timeDraftError = a[4] as String?,
            titleDraft = a[5] as String,
            message = a[6] as String?,
            sourceImage = a[7] as Bitmap?,
            summarizing = a[8] as Boolean,
        )
    }

    val state: StateFlow<DetailUiState> = combine(core, ledgerDraft) { c, l ->
        DetailUiState(
            item = c.item,
            loading = c.loading,
            editing = c.editing,
            timeDraft = c.timeDraft,
            timeDraftError = c.timeDraftError,
            titleDraft = c.titleDraft,
            message = c.message,
            notFound = !c.loading && c.item == null,
            sourceImage = c.sourceImage,
            summarizing = c.summarizing,
            editingLedger = l.editing,
            ledgerAmountDraft = l.amount,
            ledgerAmountError = l.amountError,
            ledgerDirectionDraft = l.direction,
            ledgerCategoryDraft = l.category,
            ledgerMerchantDraft = l.merchant,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    init { /* 由界面层调用 load(id) 触发 */ }

    /** 界面层在进入详情时调用一次 */
    fun load(id: Long) {
        if (itemId.value == id && item.value != null) return
        itemId.value = id
        reload()
    }

    fun reload() {
        val id = itemId.value
        if (id < 0) { loading.value = false; return }
        viewModelScope.launch {
            loading.value = true
            val it = repo.findById(id)
            item.value = it
            titleDraft.value = it?.title.orEmpty()
            timeDraft.value = it?.todo?.remindAt?.let(::formatLocal).orEmpty()
            loading.value = false
            // 原图是异步解码，别卡住界面；文件没了就当没有这一块
            val path = it?.sourceImagePath
            sourceImage.value = path?.takeIf { p -> images.exists(p) }?.let { p -> images.load(p) }
        }
    }

    fun dismissMessage() { message.value = null }

    // ---------------- 待办 ----------------

    fun startEdit() {
        val it = item.value ?: return
        titleDraft.value = it.title
        timeDraft.value = it.todo?.remindAt?.let(::formatLocal).orEmpty()
        timeDraftError.value = null
        editing.value = true
    }

    fun cancelEdit() {
        editing.value = false
        timeDraftError.value = null
    }

    fun onTitleChange(v: String) { titleDraft.value = v }
    fun onTimeChange(v: String) {
        timeDraft.value = v
        timeDraftError.value = null
    }

    /**
     * 保存待办编辑。
     * 时间输入支持自然语言（明天下午三点）也支持 2026-10-05 15:00。
     * 解析不出来就报错让用户改，绝不瞎猜。
     */
    fun saveTodo() {
        val it = item.value ?: return
        val newTitle = titleDraft.value.trim()
        if (newTitle.isBlank()) {
            message.value = "标题不能为空"
            return
        }

        viewModelScope.launch {
            val now = Instant.now()
            var remindAt: Instant? = null
            val raw = timeDraft.value.trim()

            if (raw.isNotBlank()) {
                remindAt = TimeParser.parse(raw, now)
                    ?: parseAbsolute(raw)
                if (remindAt == null) {
                    timeDraftError.value = "没看懂这个时间，试试「明天下午三点」或「2026-10-05 15:00」"
                    return@launch
                }
            }

            db.todoMetaDao().upsert(
                TodoMetaEntity(
                    itemId = it.id,
                    dueAt = remindAt?.toEpochMilli(),
                    remindAt = remindAt?.toEpochMilli(),
                    remindState = if (remindAt != null) "SCHEDULED" else "NONE",
                    priority = it.todo?.priority ?: 0,
                    repeatRule = it.todo?.repeatRule,
                    completedAt = null,
                    snoozeCount = it.todo?.snoozeCount ?: 0,
                )
            )
            db.openHelper.writableDatabase.execSQL(
                "UPDATE item SET title = ?, updatedAt = ? WHERE id = ?",
                arrayOf(newTitle, now.toEpochMilli(), it.id),
            )

            if (remindAt != null) {
                scheduler.schedule(it.id, newTitle, it.summary, remindAt)
                message.value = "已改到 " + formatLocal(remindAt)
            } else {
                scheduler.cancel(it.id)
                message.value = "已取消提醒"
            }
            editing.value = false
            reload()
        }
    }

    /** 支持 2026-10-05 15:00 / 2026-10-05T15:00 这种标准格式 */
    private fun parseAbsolute(raw: String): Instant? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm", "yyyy-MM-dd'T'HH:mm",
        )
        val zone = ZoneId.systemDefault()
        patterns.forEach { p ->
            runCatching {
                val fmt = DateTimeFormatter.ofPattern(p)
                val ldt = java.time.LocalDateTime.parse(raw, fmt)
                return ldt.atZone(zone).toInstant()
            }
        }
        return null
    }

    private fun formatLocal(i: Instant): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(i.atZone(ZoneId.systemDefault()))

    fun markDone() {
        val it = item.value ?: return
        viewModelScope.launch {
            repo.markDone(it.id)
            scheduler.cancel(it.id)
            message.value = "已完成"
            reload()
        }
    }

    fun snooze(minutes: Long) {
        val it = item.value ?: return
        viewModelScope.launch {
            val newAt = Instant.now().plusSeconds(minutes * 60)
            repo.snooze(it.id, newAt)
            scheduler.snooze(it.id, minutes * 60_000)
            message.value = "推迟到 " + formatLocal(newAt)
            reload()
        }
    }

    // ---------------- 账目 ----------------

    fun confirmLedger() {
        val it = item.value ?: return
        viewModelScope.launch {
            repo.confirmLedger(it.id)
            message.value = "已记账"
            reload()
        }
    }

    // ---- 账目编辑（v0.8.0） ----

    /** 进入编辑态：用当前值预填草稿 */
    fun startEditLedger() {
        val l = item.value?.ledger ?: return
        ledgerDraft.value = LedgerDraftState(
            editing = true,
            amount = MoneyParser.centsToYuanText(l.amountCents),
            amountError = null,
            direction = l.direction,
            category = l.category,
            merchant = l.merchant.orEmpty(),
        )
    }

    fun cancelEditLedger() {
        ledgerDraft.value = LedgerDraftState()
    }

    fun onLedgerAmountChange(v: String) {
        ledgerDraft.value = ledgerDraft.value.copy(amount = v, amountError = null)
    }

    fun onLedgerDirectionChange(d: LedgerDirection) {
        ledgerDraft.value = ledgerDraft.value.copy(direction = d)
    }

    fun onLedgerCategoryChange(v: String) {
        ledgerDraft.value = ledgerDraft.value.copy(category = v)
    }

    fun onLedgerMerchantChange(v: String) {
        ledgerDraft.value = ledgerDraft.value.copy(merchant = v)
    }

    /**
     * 保存账目修改。
     *
     * **关键决定：改过的账目一律回退成待确认（confirmed=false）。**
     *
     * 理由是铁律 2 —— 记账必须二次确认。如果改完还留在"已计入账本"，
     * 就等于给用户提供了一条绕过确认的口子：让 AI 先记一笔错的，
     * 再悄悄改金额，账本里就出现了用户从未确认过的数字。
     * 用户改完再点一次"确认入账"，多一次点击，换的是账本可信。
     */
    fun saveLedger() {
        val it = item.value ?: return
        val cur = it.ledger ?: return
        val d = ledgerDraft.value

        val parsed = MoneyParser.parseYuanToCents(d.amount)
        if (!parsed.ok) {
            ledgerDraft.value = d.copy(amountError = parsed.error)
            return
        }

        val category = d.category.trim().ifBlank { "其他" }
        val merchant = d.merchant.trim().ifBlank { null }

        viewModelScope.launch {
            db.ledgerDao().upsert(
                LedgerEntryEntity(
                    itemId = it.id,
                    amountCents = parsed.cents,     // Long 存分，全程没碰浮点
                    direction = d.direction.name,
                    category = category,
                    merchant = merchant,
                    occurredAt = cur.occurredAt.toEpochMilli(),
                    // 铁律 2：改过就得重新确认，不给"改完直接进账本"的口子
                    confirmed = false,
                )
            )
            // 标题跟着金额与商家走，否则账目列表显示的还是旧描述
            val newTitle = buildString {
                append(category)
                merchant?.let { append(" · $it") }
            }
            db.openHelper.writableDatabase.execSQL(
                "UPDATE item SET title = ?, updatedAt = ? WHERE id = ?",
                arrayOf(newTitle.take(40), System.currentTimeMillis(), it.id),
            )
            ledgerDraft.value = LedgerDraftState()
            message.value = "改好了，再确认一下就入账"
            reload()
        }
    }

    fun deleteLedger() {
        val it = item.value ?: return
        viewModelScope.launch {
            db.ledgerDao().deleteByItemId(it.id)
            repo.softDelete(it.id)
            message.value = "已删除"
            reload()
        }
    }

    // ---------------- 文章 ----------------

    /** 重新抓正文（抽取失败时的手动重试） */
    fun retryExtract() {
        val it = item.value ?: return
        val url = it.sourceUrl ?: return
        viewModelScope.launch {
            message.value = "正在重新抓取…"
            repo.retryExtract(it.id, url)
            reload()
            message.value = if (item.value?.quality == ExtractQuality.FAILED)
                "还是没抓到，可能这篇需要登录" else "抓到了"
        }
    }

    /**
     * 手动补摘要与标签（v0.10.0）。
     *
     * 与自动 enrich 的区别：这是用户主动点的，所以**失败要说清楚**，
     * 不能像后台那样静默吞掉 —— 用户点了没反应才是真的糟。
     */
    fun enrichNow() {
        val it = item.value ?: return
        val content = it.rawText?.takeIf { c -> c.isNotBlank() } ?: it.title
        if (content.isBlank()) {
            message.value = "没有内容可以处理"
            return
        }
        viewModelScope.launch {
            summarizing.value = true
            var ok = false

            // 摘要
            when (val s = gateway.summarize(content)) {
                is Outcome.Ok -> if (s.value.isNotBlank()) { repo.setSummary(it.id, s.value); ok = true }
                is Outcome.Err -> Unit
            }
            // 标签
            val existing = runCatching { db.tagDao().all().map { t -> t.name } }
                .getOrDefault(emptyList())
            var tagErr: String? = null
            when (val t = gateway.tag(content, existing)) {
                is Outcome.Ok -> if (t.value.isNotEmpty()) { repo.setTags(it.id, t.value); ok = true }
                is Outcome.Err -> tagErr = t.error.userHint()
            }

            summarizing.value = false
            reload()
            message.value = when {
                ok -> "摘要和标签已更新"
                tagErr != null -> tagErr
                else -> "模型没返回内容，可以换个模型试试"
            }
        }
    }

    /** 手动生成摘要 */
    fun summarize() {
        val it = item.value ?: return
        val content = it.rawText
        if (content.isNullOrBlank()) {
            message.value = "没有正文可以摘要"
            return
        }
        viewModelScope.launch {
            summarizing.value = true
            when (val r = gateway.summarize(content)) {
                is Outcome.Ok -> {
                    repo.setSummary(it.id, r.value)
                    reload()
                }
                is Outcome.Err -> message.value = r.error.userHint()
            }
            summarizing.value = false
        }
    }

    // ---------------- 通用 ----------------

    fun delete() {
        val it = item.value ?: return
        viewModelScope.launch {
            scheduler.cancel(it.id)
            repo.softDelete(it.id)
            message.value = "已删除"
            reload()
        }
    }

    fun openInBrowser() {
        item.value?.sourceUrl?.let { url ->
            runCatching {
                val ctx = com.shouzhe.app.platform.notify.AppScope
                // 用 Intent 打开；这里通过 message 传出，由界面层处理
                message.value = "__OPEN_URL__$url"
            }
        }
    }
}