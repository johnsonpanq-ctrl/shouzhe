package com.shouzhe.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shouzhe.app.data.db.dao.*
import com.shouzhe.app.data.db.entity.*

/**
 * 单一数据库：四条线共享同一条「收进来」的主线。
 * 注意：迁移用 Migration，禁止 fallbackToDestructiveMigration
 * —— 这是用户唯一的本地数据，丢了就没了。
 */
@Database(
    entities = [
        ItemEntity::class,
        ArticleMetaEntity::class,
        TodoMetaEntity::class,
        LedgerEntryEntity::class,
        ExtractJobEntity::class,
        TagEntity::class,
        ItemTagEntity::class,
        ModelCallEntity::class,
        ItemEmbeddingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ShouzheDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun articleMetaDao(): ArticleMetaDao
    abstract fun todoMetaDao(): TodoMetaDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun extractJobDao(): ExtractJobDao
    abstract fun tagDao(): TagDao
    abstract fun modelCallDao(): ModelCallDao

    companion object {
        const val NAME = "shouzhe.db"

        /**
         * v1 → v2：截图记账加一列存原图路径。
         *
         * 只做 ADD COLUMN（可空、无默认值），**不重建表、不动任何已有行** ——
         * 这是这类变更最安全的形式，老账目一条都不会丢。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE item ADD COLUMN sourceImagePath TEXT")
            }
        }
    }
}

class Converters {
    /** 布尔存 0/1 —— SQLite 没有原生布尔 */
    @TypeConverter fun boolToInt(v: Boolean): Int = if (v) 1 else 0
    @TypeConverter fun intToBool(v: Int): Boolean = v != 0
}