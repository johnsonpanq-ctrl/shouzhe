package com.shouzhe.app.platform.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 存好的原图 */
data class StoredImage(
    /** App 内部目录下的绝对路径 —— 存路径不存 content:// URI（权限过期就打不开） */
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/**
 * 图片存取（截图记账，v0.7.0）。
 *
 * 只做三件事：读图 → 压小 → 存进 App 内部目录。
 *
 * 为什么要压：手机原图动辄 3~12MB，转 base64 后体积再涨 1/3，
 * 直接发给模型会超请求上限、烧掉一堆 token，长边 1280 的 JPEG 对读账单足够清晰。
 *
 * 已知限制（诚实标注）：不做 EXIF 方向校正 —— 相册截图与微信/支付宝截图本身方向正常；
 * 若用户直接拍纸质小票，个别机型可能显示歪 90°。
 * 修它要引入 androidx.exifinterface 依赖，按项目纪律（新增依赖须确认）暂不做。
 */
@Singleton
class ImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 从相册选中的 Uri 读图、压缩、存到内部目录。
     * 失败返回 null（调用方据此如实提示，**但不会丢用户选的那张图**，因为压缩失败才走 null）。
     */
    suspend fun importAndStore(uri: Uri): StoredImage? = withContext(Dispatchers.IO) {
        runCatching {
            val src = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@runCatching null

            val scaled = scaleDown(src, MAX_DIM)
            val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")

            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }

            if (scaled !== src) scaled.recycle()
            src.recycle()

            StoredImage(file.absolutePath, MIME_JPEG, file.length())
        }.getOrNull()
    }

    /** 读回来给界面显示；文件没了（用户清过数据）就返回 null，界面不崩 */
    suspend fun load(path: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(path)
            if (!f.exists()) return@runCatching null
            BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull()
    }

    /** 发给模型用：纯 base64，不带 data: 前缀（URL 前缀由网关拼） */
    suspend fun encodeBase64(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(path)
            if (!f.exists()) return@runCatching null
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 图片没了就清掉引用，避免详情页反复解码一个不存在的文件 */
    fun exists(path: String?): Boolean = path != null && File(path).exists()

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private companion object {
        const val DIR_NAME = "receipts"
        const val MAX_DIM = 1280
        const val JPEG_QUALITY = 80
        const val MIME_JPEG = "image/jpeg"
    }
}