package com.shouzhe.app.data.repository

import com.shouzhe.app.core.result.*
import com.shouzhe.app.data.db.ShouzheDatabase
import com.shouzhe.app.data.db.entity.*
import com.shouzhe.app.domain.model.*
import com.shouzhe.app.domain.parse.BackupParser
import com.shouzhe.app.domain.parse.LedgerRange
import com.shouzhe.app.platform.extract.ExtractOutcome
import com.shouzhe.app.platform.extract.WebViewExtractor
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 收件箱仓储 —— 四条线的统一入口。
 *
 * 核心保证（立项书要求）：
 * 任何一条内容进来，都必须至少留下 source_url。
 * 抓取失败也必须落库，绝不能丢。
 */
@Singleton
class ItemRepository @Inject constructor(
    private val db: ShouzheDatabase,
    private val extractor: WebViewExtractor,
) {
    private val itemDao get() = db.itemDao()
    private val articleDao get() = db.articleMetaDao()
    private val todoDao get() = db.todoMetaDao()
    private val ledgerDao get() = db.ledgerDao()
    private val extractDao get() = db.extractJobDao()

    /** 收件箱流 */
    fun observeInbox(): Flow<List<Item>> =
        itemDao.observeInbox().map { list -> list.map { it.toDomain(includeExtras = true) } }

    fun observeByType(type: ItemType): Flow<List<Item>> =
        itemDao.observeByType(type.name).map { list -> list.map { it.toDomain(includeExtras = true) } }

    fun observeInboxCount(): Flow<Int> = itemDao.observeInboxCount()

    suspend fun findById(id: Long): Item? = withContext(Dispatchers.IO) {
        itemDao.findById(id)?.toDomain(includeExtras = true)
    }

    /**
     * 收下一条纯文本（一句话 / 笔记）。
     */
    suspend fun ingestText(
        text: String,
        origin: Origin,
        sourceApp: String? = null,
    ): Outcome<Long> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val id = itemDao.insert(
            ItemEntity(
                uuid = UUID.randomUUID().toString(),
                type = ItemType.NOTE.name,
                title = text.take(40),
                rawText = text,
                summary = null,
                sourceUrl = null,
                sourceApp = sourceApp,
                status = ItemStatus.INBOX.name,
                quality = null,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            )
        )
        id.ok()
    }

    /**
     * 收下一条链接：立刻落库 + 建抽取任务，抽取异步进行。
     * 这是「降级是主路径」的落地 —— 哪怕抽取全败，链接也留下了。
     */
    suspend fun ingestUrl(
        url: String,
        origin: Origin,
        sourceApp: String? = null,
    ): Outcome<Long> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val id = itemDao.insert(
            ItemEntity(
                uuid = UUID.randomUUID().toString(),
                type = ItemType.ARTICLE.name,
                title = url,                       // 先用 URL 占位，抽到再覆盖
                rawText = null,
                summary = null,
                sourceUrl = url,
                sourceApp = sourceApp,
                status = ItemStatus.INBOX.name,
                quality = null,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            )
        )
        extractDao.insert(
            ExtractJobEntity(
                itemId = id,
                url = url,
                state = ExtractState.PENDING.name,
                attempts = 0,
                lastError = null,
                createdAt = now.toEpochMilli(),
            )
        )
        id.ok()
    }

    /**
     * 收下一张截图（v0.7.0）：**先把图和条目落库，再谈识别**。
     *
     * 这是铁律 1「任何内容进来，至少留下链接」在图片上的落地：
     * 识别超时、供应商不支持识图、模型抽风 —— 图和条目都已经在库里了，一条不丢。
     *
     * 先按 NOTE 落，识别成功后由调用方改成 LEDGER。
     */
    suspend fun ingestImage(
        imagePath: String,
        title: String,
        origin: Origin,
    ): Outcome<Long> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val id = itemDao.insert(
            ItemEntity(
                uuid = UUID.randomUUID().toString(),
                type = ItemType.NOTE.name,
                title = title.take(40),
                rawText = null,
                summary = null,
                sourceUrl = null,
                sourceApp = null,
                status = ItemStatus.INBOX.name,
                quality = null,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
                sourceImagePath = imagePath,
            )
        )
        id.ok()
    }

    /** 把已经落库的截图条目转成记账草稿（confirmed=false，铁律 2） */
    suspend fun applyReceiptDraft(
        itemId: Long,
        title: String,
        amountCents: Long,
        direction: LedgerDirection,
        category: String,
        merchant: String?,
        occurredAt: Instant,
        summary: String?,
    ) = withContext(Dispatchers.IO) {
        ledgerDao.upsert(
            LedgerEntryEntity(
                itemId = itemId,
                amountCents = amountCents,
                direction = direction.name,
                category = category,
                merchant = merchant,
                occurredAt = occurredAt.toEpochMilli(),
                confirmed = false,
            )
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE item SET type = ?, title = ?, summary = ?, updatedAt = ? WHERE id = ?",
            arrayOf(
                ItemType.LEDGER.name,
                title.take(40),
                summary,
                Instant.now().toEpochMilli(),
                itemId,
            ),
        )
    }

    /**
     * 处理一个待抽取任务。返回是否成功。
     * 失败时把质量标成 FAILED，链接仍在。
     */
    suspend fun processNextExtractJob(): Boolean = withContext(Dispatchers.IO) {
        val job = extractDao.nextPending() ?: return@withContext false
        extractDao.markFinished(job.id, ExtractState.RUNNING.name, null, 0)

        when (val result = extractor.extract(job.url)) {
            is ExtractOutcome.Success -> {
                val now = Instant.now().toEpochMilli()
                itemDao.updateSummary(job.itemId, "", now) // 占位，摘要另行生成
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE item SET title = ?, rawText = ? WHERE id = ?",
                    arrayOf(result.title, result.text, job.itemId),
                )
                articleDao.upsert(
                    ArticleMetaEntity(
                        itemId = job.itemId,
                        author = result.author,
                        publishedAt = null,
                        siteName = result.siteName,
                        wordCount = result.wordCount,
                        coverImage = null,
                    )
                )
                itemDao.updateQuality(job.itemId, ExtractQuality.GOOD.name, now)
                extractDao.markFinished(job.id, ExtractState.SUCCESS.name, null, now)
                true
            }
            is ExtractOutcome.Failed -> {
                val now = Instant.now().toEpochMilli()
                itemDao.updateQuality(job.itemId, ExtractQuality.FAILED.name, now)
                extractDao.markFinished(job.id, ExtractState.FAILED.name, result.reason, now)
                false
            }
        }
    }

    /** App 被杀后恢复：running 的重置为 pending */
    suspend fun recoverInterruptedJobs() = withContext(Dispatchers.IO) {
        extractDao.resetRunning()
    }

    /** 关键词搜索 —— MVP 的「找回」能力 */
    suspend fun search(query: String): List<Item> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        itemDao.search(query).map { it.toDomain(includeExtras = true) }
    }

    suspend fun softDelete(id: Long) = withContext(Dispatchers.IO) {
        itemDao.softDelete(id, Instant.now().toEpochMilli())
    }

    /** 标记待办完成 */
    suspend fun markDone(id: Long) = withContext(Dispatchers.IO) {
        val now = Instant.now().toEpochMilli()
        itemDao.updateStatus(id, ItemStatus.DONE.name, now)
        todoDao.markDone(id, RemindState.DONE.name, now)
    }

    /** 推迟提醒 */
    suspend fun snooze(id: Long, newAt: Instant) = withContext(Dispatchers.IO) {
        todoDao.snooze(id, newAt.toEpochMilli())
    }

    /** 确认记账 —— 只有确认后才计入账本 */
    suspend fun confirmLedger(id: Long) = withContext(Dispatchers.IO) {
        ledgerDao.confirm(id)
    }

    /**
     * 账本汇总（v0.9.0）。
     *
     * **口径只有一条：只算 confirmed = 1。**
     * 待确认的草稿不进任何汇总 —— 用户没点确认的数字，
     * 就不该出现在"这个月花了多少"里。
     *
     * 金额全程 Long 分，不碰浮点（铁律 3）。
     */
    suspend fun ledgerSummary(range: LedgerRange.Range): LedgerSummary = withContext(Dispatchers.IO) {
        val outName = LedgerDirection.OUT.name
        val inName = LedgerDirection.IN.name

        LedgerSummary(
            expenseCents = ledgerDao.sumByDirection(outName, range.from, range.to),
            incomeCents = ledgerDao.sumByDirection(inName, range.from, range.to),
            byCategory = ledgerDao.sumByCategory(outName, range.from, range.to)
                .map { CategoryTotal(it.category, it.total) },
        )
    }

    /**
     * 手动重试抽取（详情页的「重新抓取」按钮）。
     * 清掉旧的失败任务，重新跑一次。
     */
    suspend fun retryExtract(itemId: Long, url: String): Boolean = withContext(Dispatchers.IO) {
        val now = Instant.now().toEpochMilli()
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM extract_job WHERE itemId = ?",
            arrayOf(itemId),
        )
        extractDao.insert(
            ExtractJobEntity(
                itemId = itemId,
                url = url,
                state = ExtractState.PENDING.name,
                attempts = 0,
                lastError = null,
                createdAt = now,
            )
        )
        processNextExtractJob()
    }

    suspend fun setSummary(id: Long, summary: String) = withContext(Dispatchers.IO) {
        itemDao.updateSummary(id, summary, Instant.now().toEpochMilli())
    }

    /**
     * 给条目写 AI 标签（幂等）。
     *
     * 幂等性很关键：内容会被重复处理（重新抓取、手动重试摘要），
     * 不能每次调用都往 item_tag 里堆一批重复关联。
     *
     * 做法：先清掉该条目上来源为 ai 的旧关联，再写新的。
     * **只清 ai 的，用户手动打的标签（source=user）不动** —— 别替用户删东西。
     */
    suspend fun setTags(itemId: Long, tags: List<String>) = withContext(Dispatchers.IO) {
        val now = Instant.now().toEpochMilli()
        val clean = cleanTags(tags)

        // 清掉旧的 AI 关联（保留用户手动打的）
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM item_tag WHERE itemId = ? AND source = 'ai'",
            arrayOf(itemId),
        )
        if (clean.isEmpty()) return@withContext

        clean.forEach { name ->
            db.tagDao().insert(TagEntity(name = name, createdAt = now))
            val tag = db.tagDao().findByName(name) ?: return@forEach
            db.tagDao().link(ItemTagEntity(itemId = itemId, tagId = tag.id, source = "ai"))
        }
        // 重算使用次数，避免长期累积成错数
        db.openHelper.writableDatabase.execSQL(
            "UPDATE tag SET useCount = (SELECT COUNT(*) FROM item_tag WHERE tagId = tag.id)"
        )
    }

    // ------------------------------------------------------------------

    /**
     * 取全部条目用于导出（v0.11.0）。
     * 含已归档与已软删除的 —— 备份要完整，不能因为用户误删就漏掉。
     */
    suspend fun findAllForExport(): List<Item> = withContext(Dispatchers.IO) {
        itemDao.findAllForExport().map { it.toDomain(includeExtras = true) }
    }

    // ------------------------------------------------------------------
    // 备份导入（v0.12.0）
    // ------------------------------------------------------------------

    /** 导入结果统计 */
    data class ImportStats(
        val inserted: Int,
        val skippedExisting: Int,
        val skippedInvalid: Int,
    ) {
        val total: Int get() = inserted + skippedExisting + skippedInvalid
    }

    /**
     * **合并式**导入（不是覆盖）。
     *
     * 为什么必须合并而不是清空重来：
     * 用户手机上有 100 条、导入一份 3 条的老备份，覆盖式的结果是**只剩 3 条** ——
     * "恢复数据"把数据恢复没了。这个项目不能接受这种事故。
     *
     * 规则：
     * - `uuid` 已存在 → 跳过（重复导入同一份文件不产生重复数据）
     * - `uuid` 不存在 → 插入
     * - 整批操作放在**一个事务**里：中途任何异常都会回滚，不会留下半截数据
     */
    suspend fun importBackup(items: List<BackupParser.BackupItem>): ImportStats =
        withContext(Dispatchers.IO) {
            var inserted = 0
            var skippedExisting = 0
            var skippedInvalid = 0

            db.withTransaction {
                items.forEach { b ->
                    if (itemDao.findByUuid(b.uuid) != null) {
                        skippedExisting++
                        return@forEach
                    }
                    val newId = runCatching { insertBackupItem(b) }.getOrNull()
                    if (newId == null || newId <= 0) skippedInvalid++ else inserted++
                }

                // 标签使用次数重算一次就够，不用每条都刷
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE tag SET useCount = (SELECT COUNT(*) FROM item_tag WHERE tagId = tag.id)"
                )
            }

            ImportStats(inserted, skippedExisting, skippedInvalid)
        }

    /** 插入一条备份条目（含分型子表）；返回新 id，失败返回 null */
    private suspend fun insertBackupItem(b: BackupParser.BackupItem): Long? {
        val now = Instant.now().toEpochMilli()
        val createdAt = b.createdAt?.toEpochMilli() ?: now
        val updatedAt = b.updatedAt?.toEpochMilli() ?: createdAt

        // 账目类型但没读出 ledger → 降级成笔记，总比造一笔 0 元的假账好
        val effectiveType = if (b.type == ItemType.LEDGER && b.ledger == null) {
            ItemType.NOTE
        } else {
            b.type
        }

        val id = itemDao.insert(
            ItemEntity(
                uuid = b.uuid,
                type = effectiveType.name,
                title = b.title.take(200),
                rawText = b.rawText,
                summary = b.summary,
                sourceUrl = b.sourceUrl,
                sourceApp = b.sourceApp,
                status = b.status.name,
                quality = b.quality,
                createdAt = createdAt,
                updatedAt = updatedAt,
                // 原图路径不进备份（图片文件不在备份里），留空避免指向不存在的文件
                sourceImagePath = null,
            )
        )
        if (id <= 0) return null

        if (effectiveType == ItemType.LEDGER) {
            val l = b.ledger!!
            ledgerDao.upsert(
                LedgerEntryEntity(
                    itemId = id,
                    amountCents = l.amountCents,
                    direction = l.direction.name,
                    category = l.category,
                    merchant = l.merchant,
                    occurredAt = l.occurredAt?.toEpochMilli() ?: createdAt,
                    confirmed = l.confirmed,
                )
            )
        }

        if (effectiveType == ItemType.TODO && b.todo != null) {
            todoDao.upsert(
                TodoMetaEntity(
                    itemId = id,
                    dueAt = b.todo.dueAt?.toEpochMilli(),
                    remindAt = b.todo.remindAt?.toEpochMilli(),
                    remindState = b.todo.remindState,
                    priority = b.todo.priority,
                    repeatRule = null,
                    completedAt = null,
                    snoozeCount = 0,
                )
            )
        }

        if (b.tags.isNotEmpty()) {
            val nowMs = Instant.now().toEpochMilli()
            cleanTags(b.tags).forEach { name ->
                db.tagDao().insert(TagEntity(name = name, createdAt = nowMs))
                val tag = db.tagDao().findByName(name) ?: return@forEach
                // 标成 user 来源：导入的标签不该被后续 AI 打标签操作清掉
                db.tagDao().link(ItemTagEntity(itemId = id, tagId = tag.id, source = "user"))
            }
        }

        return id
    }

    private suspend fun ItemEntity.toDomain(includeExtras: Boolean = false): Item {
        val type = runCatching { ItemType.valueOf(this.type) }.getOrDefault(ItemType.NOTE)
        val status = runCatching { ItemStatus.valueOf(this.status) }.getOrDefault(ItemStatus.INBOX)
        val quality = this.quality?.let { runCatching { ExtractQuality.valueOf(it) }.getOrNull() }

        var article: ArticleMeta? = null
        var todo: TodoMeta? = null
        var ledger: LedgerMeta? = null
        var tags: List<String> = emptyList()

        if (includeExtras) {
            when (type) {
                ItemType.ARTICLE -> articleDao.findByItemId(id)?.let {
                    article = ArticleMeta(
                        author = it.author,
                        publishedAt = it.publishedAt?.let(Instant::ofEpochMilli),
                        siteName = it.siteName,
                        wordCount = it.wordCount,
                        coverImage = it.coverImage,
                    )
                }
                ItemType.TODO -> todoDao.findByItemId(id)?.let {
                    todo = TodoMeta(
                        dueAt = it.dueAt?.let(Instant::ofEpochMilli),
                        remindAt = it.remindAt?.let(Instant::ofEpochMilli),
                        remindState = runCatching { RemindState.valueOf(it.remindState) }
                            .getOrDefault(RemindState.NONE),
                        priority = it.priority,
                        repeatRule = it.repeatRule,
                        completedAt = it.completedAt?.let(Instant::ofEpochMilli),
                        snoozeCount = it.snoozeCount,
                    )
                }
                ItemType.LEDGER -> ledgerDao.findByItemId(id)?.let {
                    ledger = LedgerMeta(
                        amountCents = it.amountCents,
                        direction = runCatching { LedgerDirection.valueOf(it.direction) }
                            .getOrDefault(LedgerDirection.OUT),
                        category = it.category,
                        merchant = it.merchant,
                        occurredAt = Instant.ofEpochMilli(it.occurredAt),
                        confirmed = it.confirmed,
                    )
                }
                else -> Unit
            }
            tags = db.tagDao().tagsOfItem(id)
        }

        return Item(
            id = id,
            uuid = uuid,
            type = type,
            title = title,
            rawText = rawText,
            summary = summary,
            sourceUrl = sourceUrl,
            sourceApp = sourceApp,
            status = status,
            quality = quality,
            createdAt = Instant.ofEpochMilli(createdAt),
            updatedAt = Instant.ofEpochMilli(updatedAt),
            article = article,
            todo = todo,
            ledger = ledger,
            tags = tags,
            sourceImagePath = sourceImagePath,
        )
    }

    enum class ExtractState { PENDING, RUNNING, SUCCESS, FAILED }

    companion object {
        /**
         * 标签清洗（纯函数，便于单测）。
         *
         * 模型输出不可全信：可能带 `#`、带编号、带引号、写超长句子、
         * 或者把同一个标签写两遍。这里统一收拾干净 —— 脏标签会污染标签字典。
         */
        fun cleanTags(raw: List<String>): List<String> = raw
            .asSequence()
            .map { it.trim() }
            .map { it.removePrefix("#").removePrefix("-").trim() }
            .map { it.trim('"', '\'', '「', '」', '“', '”', '，', ',', '。') }
            .filter { it.isNotBlank() }
            // 标签是"词"，不是句子：超过 12 个字的多半是模型没按格式返回
            .filter { it.length <= 12 }
            .distinct()
            .take(MAX_TAGS)
            .toList()

        /** 一条内容最多挂 5 个 AI 标签 —— 与 Prompts.tagSystem 的要求一致 */
        private const val MAX_TAGS = 5
    }
}
