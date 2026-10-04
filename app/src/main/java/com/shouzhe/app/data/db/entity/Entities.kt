package com.shouzhe.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 统一主表 —— 四条线都先落这张表（见 DATABASE.md §2.1）
 */
@Entity(
    tableName = "item",
    indices = [
        Index(value = ["type", "status"]),
        Index(value = ["createdAt"]),
        Index(value = ["sourceApp"]),
        Index(value = ["uuid"], unique = true),
    ]
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val type: String,
    val title: String,
    val rawText: String?,
    val summary: String?,
    val sourceUrl: String?,
    val sourceApp: String?,
    val status: String,
    val quality: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
    /**
     * 原图在 App 内部目录里的绝对路径（截图记账，v0.7.0 新增）。
     * 存绝对路径而不是 content:// URI：content 权限一旦过期图就打不开了。
     */
    val sourceImagePath: String? = null,
)

/** 文章扩展 */
@Entity(
    tableName = "article_meta",
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("siteName")]
)
data class ArticleMetaEntity(
    @PrimaryKey val itemId: Long,
    val author: String?,
    val publishedAt: Long?,
    val siteName: String?,
    val wordCount: Int,
    val coverImage: String?,
)

/** 待办扩展 */
@Entity(
    tableName = "todo_meta",
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("remindState", "remindAt"), Index("dueAt")]
)
data class TodoMetaEntity(
    @PrimaryKey val itemId: Long,
    val dueAt: Long?,
    val remindAt: Long?,
    val remindState: String,
    val priority: Int,
    val repeatRule: String?,
    val completedAt: Long?,
    val snoozeCount: Int,
)

/** 记账扩展 —— amount 以「分」存整数，绝不用实数 */
@Entity(
    tableName = "ledger_entry",
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("occurredAt"), Index("category")]
)
data class LedgerEntryEntity(
    @PrimaryKey val itemId: Long,
    val amountCents: Long,
    val direction: String,
    val category: String,
    val merchant: String?,
    val occurredAt: Long,
    val confirmed: Boolean,
)

/** 正文抽取任务 —— 独立成表，可单独重试 */
@Entity(
    tableName = "extract_job",
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("state", "createdAt")]
)
data class ExtractJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val url: String,
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

/** 标签字典 */
@Entity(tableName = "tag", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String? = null,
    val useCount: Int = 0,
    val createdAt: Long,
)

/** 内容-标签关联 */
@Entity(
    tableName = "item_tag",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"],
            childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"],
            childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")]
)
data class ItemTagEntity(
    val itemId: Long,
    val tagId: Long,
    val source: String,  // ai | user
)

/** 模型调用记录 —— 只记 token 与耗时，绝不记正文（隐私） */
@Entity(tableName = "model_call", indices = [Index("createdAt")])
data class ModelCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val modelName: String,
    val purpose: String,
    val itemId: Long?,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
    val success: Boolean,
    val error: String?,
    val createdAt: Long,
)

/** 语义检索预留（V1.1 写入，现在只建表） */
@Entity(
    tableName = "item_embedding",
    foreignKeys = [ForeignKey(
        entity = ItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("itemId")]
)
data class ItemEmbeddingEntity(
    @PrimaryKey val itemId: Long,
    val vector: ByteArray,
    val dim: Int,
    val model: String,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItemEmbeddingEntity) return false
        return itemId == other.itemId && dim == other.dim &&
            model == other.model && createdAt == other.createdAt &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + dim
        result = 31 * result + model.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}