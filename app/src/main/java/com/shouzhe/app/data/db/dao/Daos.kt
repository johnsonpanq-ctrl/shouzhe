package com.shouzhe.app.data.db.dao

import androidx.room.*
import com.shouzhe.app.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM item WHERE id = :id")
    suspend fun findById(id: Long): ItemEntity?

    @Query("SELECT * FROM item WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): ItemEntity?

    /** 收件箱流：未删除的按时间倒序 */
    @Query("""
        SELECT * FROM item
        WHERE status != 'DELETED'
        ORDER BY createdAt DESC
    """)
    fun observeInbox(): Flow<List<ItemEntity>>

    /** 按类型筛选 */
    @Query("""
        SELECT * FROM item
        WHERE status != 'DELETED' AND type = :type
        ORDER BY createdAt DESC
    """)
    fun observeByType(type: String): Flow<List<ItemEntity>>

    /** 软删除 */
    @Query("UPDATE item SET status = 'DELETED', updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE item SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, now: Long)

    @Query("UPDATE item SET quality = :quality, updatedAt = :now WHERE id = :id")
    suspend fun updateQuality(id: Long, quality: String, now: Long)

    @Query("UPDATE item SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, now: Long)

    /** 关键词搜索（MVP 的"找回"能力，先用 LIKE 兜底） */
    @Query("""
        SELECT * FROM item
        WHERE status != 'DELETED'
          AND (title LIKE '%' || :q || '%'
               OR rawText LIKE '%' || :q || '%'
               OR summary LIKE '%' || :q || '%')
        ORDER BY createdAt DESC
        LIMIT 100
    """)
    suspend fun search(q: String): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM item WHERE status = 'INBOX'")
    fun observeInboxCount(): Flow<Int>
}

@Dao
interface ArticleMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: ArticleMetaEntity)

    @Query("SELECT * FROM article_meta WHERE itemId = :itemId")
    suspend fun findByItemId(itemId: Long): ArticleMetaEntity?
}

@Dao
interface TodoMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: TodoMetaEntity)

    @Query("SELECT * FROM todo_meta WHERE itemId = :itemId")
    suspend fun findByItemId(itemId: Long): TodoMetaEntity?

    /**
     * 开机重排用：所有待重排的提醒。
     * 这是"排期不许出错"的查询依据，必须走索引。
     */
    @Query("""
        SELECT * FROM todo_meta
        WHERE remindState IN ('SCHEDULED', 'MISSED')
          AND remindAt IS NOT NULL
        ORDER BY remindAt ASC
    """)
    suspend fun findPendingReminders(): List<TodoMetaEntity>

    @Query("""
        SELECT * FROM todo_meta
        WHERE remindState = 'SCHEDULED' AND remindAt IS NOT NULL AND remindAt <= :until
        ORDER BY remindAt ASC
    """)
    suspend fun findDueBefore(until: Long): List<TodoMetaEntity>

    @Query("UPDATE todo_meta SET remindState = :state WHERE itemId = :itemId")
    suspend fun updateRemindState(itemId: Long, state: String)

    @Query("UPDATE todo_meta SET remindState = :state, completedAt = :at WHERE itemId = :itemId")
    suspend fun markDone(itemId: Long, state: String, at: Long)

    @Query("UPDATE todo_meta SET snoozeCount = snoozeCount + 1, remindAt = :newAt, " +
           "remindState = 'SCHEDULED' WHERE itemId = :itemId")
    suspend fun snooze(itemId: Long, newAt: Long)
}

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LedgerEntryEntity)

    @Query("SELECT * FROM ledger_entry WHERE itemId = :itemId")
    suspend fun findByItemId(itemId: Long): LedgerEntryEntity?

    /** 只有 confirmed 的才计入账本 */
    @Query("""
        SELECT COALESCE(SUM(amountCents), 0) FROM ledger_entry
        WHERE confirmed = 1 AND direction = :direction
          AND occurredAt BETWEEN :from AND :to
    """)
    suspend fun sumByDirection(direction: String, from: Long, to: Long): Long

    /**
     * 按分类汇总（v0.9.0 账本）。
     *
     * **只统计 confirmed = 1** —— 与铁律 2 同一口径：
     * 待确认的草稿绝不能出现在"这个月花了多少"里，否则用户会看到一个
     * 自己还没认可的数字，而那正是记账 App 最不能出错的地方。
     *
     * 返回 CategorySum 列表，按金额从大到小。
     */
    @Query("""
        SELECT category, COALESCE(SUM(amountCents), 0) AS total
        FROM ledger_entry
        WHERE confirmed = 1 AND direction = :direction
          AND occurredAt BETWEEN :from AND :to
        GROUP BY category
        ORDER BY total DESC
    """)
    suspend fun sumByCategory(direction: String, from: Long, to: Long): List<CategorySum>

    /** 待确认的草稿（超过 N 天要清理） */
    @Query("SELECT * FROM ledger_entry WHERE confirmed = 0 AND occurredAt < :before")
    suspend fun findUnconfirmedBefore(before: Long): List<LedgerEntryEntity>

    @Query("UPDATE ledger_entry SET confirmed = 1 WHERE itemId = :itemId")
    suspend fun confirm(itemId: Long)

    @Query("DELETE FROM ledger_entry WHERE itemId = :itemId")
    suspend fun deleteByItemId(itemId: Long)

    /** 账本汇总的一行：分类名 + 金额分（金额一律 Long 分，不碰浮点） */
    data class CategorySum(val category: String, val total: Long)
}

@Dao
interface ExtractJobDao {
    @Insert
    suspend fun insert(job: ExtractJobEntity): Long

    @Query("""
        SELECT * FROM extract_job
        WHERE state = 'PENDING'
        ORDER BY createdAt ASC
        LIMIT 1
    """)
    suspend fun nextPending(): ExtractJobEntity?

    @Query("SELECT * FROM extract_job WHERE state = :state")
    suspend fun findByState(state: String): List<ExtractJobEntity>

    @Query("""
        UPDATE extract_job
        SET state = :state, attempts = attempts + 1,
            lastError = :error, finishedAt = :finishedAt
        WHERE id = :id
    """)
    suspend fun markFinished(id: Long, state: String, error: String?, finishedAt: Long)

    /** App 被杀后恢复：把 running 的重置为 pending */
    @Query("UPDATE extract_job SET state = 'PENDING' WHERE state = 'RUNNING'")
    suspend fun resetRunning()

    @Query("SELECT COUNT(*) FROM extract_job WHERE state IN ('PENDING','RUNNING')")
    fun observeActiveCount(): Flow<Int>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tag WHERE name = :name")
    suspend fun findByName(name: String): TagEntity?

    @Query("SELECT * FROM tag ORDER BY useCount DESC")
    suspend fun all(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(itemTag: ItemTagEntity)

    @Query("""
        SELECT t.name FROM tag t
        INNER JOIN item_tag it ON it.tagId = t.id
        WHERE it.itemId = :itemId
    """)
    suspend fun tagsOfItem(itemId: Long): List<String>
}

@Dao
interface ModelCallDao {
    @Insert
    suspend fun insert(call: ModelCallEntity)

    @Query("""
        SELECT COALESCE(SUM(promptTokens + completionTokens), 0) FROM model_call
        WHERE createdAt >= :since
    """)
    suspend fun tokensSince(since: Long): Long

    @Query("SELECT COUNT(*) FROM model_call WHERE createdAt >= :since AND success = 0")
    suspend fun failuresSince(since: Long): Int
}
