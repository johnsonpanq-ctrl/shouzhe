package com.shouzhe.app.core.di

import android.content.Context
import androidx.room.Room
import com.shouzhe.app.data.db.ShouzheDatabase
import com.shouzhe.app.data.db.dao.*
import com.shouzhe.app.model.OpenAiCompatGateway
import com.shouzhe.app.model.gateway.ModelGateway
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext ctx: Context): Context = ctx

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ShouzheDatabase =
        Room.databaseBuilder(ctx, ShouzheDatabase::class.java, ShouzheDatabase.NAME)
            // 注意：绝不使用 fallbackToDestructiveMigration —— 本地数据丢了就没了
            .addMigrations(ShouzheDatabase.MIGRATION_1_2)
            .build()

    @Provides fun itemDao(db: ShouzheDatabase): ItemDao = db.itemDao()
    @Provides fun articleMetaDao(db: ShouzheDatabase): ArticleMetaDao = db.articleMetaDao()
    @Provides fun todoMetaDao(db: ShouzheDatabase): TodoMetaDao = db.todoMetaDao()
    @Provides fun ledgerDao(db: ShouzheDatabase): LedgerDao = db.ledgerDao()
    @Provides fun extractJobDao(db: ShouzheDatabase): ExtractJobDao = db.extractJobDao()
    @Provides fun tagDao(db: ShouzheDatabase): TagDao = db.tagDao()
    @Provides fun modelCallDao(db: ShouzheDatabase): ModelCallDao = db.modelCallDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayModule {
    @Binds
    @Singleton
    abstract fun bindModelGateway(impl: OpenAiCompatGateway): ModelGateway
}