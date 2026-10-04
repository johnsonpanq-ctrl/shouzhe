package com.shouzhe.app.platform.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 导出的结果：拿到文件就能分享，拿不到就如实说原因 */
sealed interface ExportResult {
    data class Ok(val file: File, val uri: Uri, val bytes: Long) : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/**
 * 导出器（v0.11.0）。
 *
 * 落点选 **cacheDir** 而不是 filesDir：
 * 导出文件是"用完即走"的临时产物，让系统在空间紧张时能回收；
 * 用户真正要留存的副本会通过分享（发给自己/网盘/邮件）带出去。
 *
 * 权限：走 FileProvider，**不需要任何存储权限** —— 与项目"最小权限"立场一致。
 */
@Singleton
class Exporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun write(fileName: String, content: String): ExportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
                // 每次导出前清掉旧的：避免 cache 里堆一堆历史导出文件
                dir.listFiles()?.forEach { it.delete() }

                val f = File(dir, fileName)
                f.writeText(content, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", f,
                )
                ExportResult.Ok(f, uri, f.length())
            }.getOrElse { e ->
                ExportResult.Failed(e.message ?: "写入失败")
            }
        }

    /** 调起系统分享面板，让用户自己选存到哪（文件管理器 / 微信 / 邮件 / 网盘） */
    fun shareIntent(uri: Uri, mimeType: String, title: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private companion object {
        const val DIR_NAME = "export"
    }
}