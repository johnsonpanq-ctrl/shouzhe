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

    // ------------------------------------------------------------------
    // 导入（v0.12.0）
    // ------------------------------------------------------------------

    /** 读取用户选中的备份文件（走 SAF，不需要存储权限） */
    suspend fun read(uri: Uri, maxBytes: Long = MAX_IMPORT_BYTES): ReadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    // 限制大小：备份是文本，正常几 MB 顶天；
                    // 万一用户选了个几 GB 的文件，不能直接把内存撑爆
                    val buf = ByteArray(64 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) {
                            return@runCatching ReadResult.TooLarge(maxBytes)
                        }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: return@runCatching ReadResult.Failed("打不开这个文件")

                // 备份一定是 UTF-8 文本；BOM 要剥掉（有些编辑器会加）
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                ReadResult.Ok(text)
            }.getOrElse { e ->
                ReadResult.Failed(e.message ?: "读取失败")
            }
        }

    sealed interface ReadResult {
        data class Ok(val text: String) : ReadResult
        data class TooLarge(val limit: Long) : ReadResult
        data class Failed(val reason: String) : ReadResult
    }

    private companion object {
        const val DIR_NAME = "export"

        /** 导入文件上限 32MB —— 纯文本备份不可能这么大，超了必有问题 */
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    }
}