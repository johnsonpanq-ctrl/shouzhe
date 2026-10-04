package com.shouzhe.app.platform.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shouzhe.app.core.result.AppError
import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.core.result.err
import com.shouzhe.app.core.result.ok
import com.shouzhe.app.data.prefs.ModelConfig
import com.shouzhe.app.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端语音识别 —— OpenAI 兼容 /audio/transcriptions。
 *
 * 为什么提供云端选项（用户要求）：内置的 14M 离线模型安静环境够用，
 * 但准确率有限；用户自己的多模态账号（SiliconFlow SenseVoice / OpenAI whisper）
 * 识别质量高得多。走用户自己的 Key，语音文件只发给用户自己选的供应商。
 *
 * 流程：录音到内存 → PCM 编码 WAV → multipart 上传 → 返回文本。
 * 说话结束后一次性识别（非流式，云端 API 大多不支持流式转写）。
 */
@Singleton
class CloudVoiceRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    /**
     * 录音并识别。用户点停止（或最长 60 秒）后，整段送去云端识别。
     * 事件流与离线引擎完全一致（VoiceEvent），UI 层无感。
     */
    @SuppressLint("MissingPermission") // 权限由 UI 层在调用前申请
    fun listen(): Flow<VoiceEvent> = callbackFlow {
        // 1) 配置检查
        val cfg = settings.asrConfig.first()
        if (!cfg.isConfigured || cfg.modelName.isBlank()) {
            trySend(VoiceEvent.Error("云端语音没配置模型名，到设置里填一下"))
            close()
            return@callbackFlow
        }

        // 2) 录音准备
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            trySend(VoiceEvent.Error("录音初始化失败"))
            close()
            return@callbackFlow
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            trySend(VoiceEvent.Error("录音初始化失败：${t.message}"))
            close()
            return@callbackFlow
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            trySend(VoiceEvent.Error("录音初始化失败"))
            record.release()
            close()
            return@callbackFlow
        }

        var stopped = false
        val pcmBuffer = ByteArrayOutputStream()
        val readBuf = ShortArray(CHUNK_SIZE)

        record.startRecording()
        trySend(VoiceEvent.Ready)

        val feedJob = launch {
            while (!stopped && isActive) {
                val n = record.read(readBuf, 0, CHUNK_SIZE)
                if (n <= 0) continue
                // Short → little-endian bytes
                for (i in 0 until n) {
                    pcmBuffer.write(readBuf[i].toInt() and 0xFF)
                    pcmBuffer.write((readBuf[i].toInt() shr 8) and 0xFF)
                }
            }
        }

        awaitClose {
            stopped = true
            runCatching { feedJob.cancel() }
            runCatching { record.stop() }
            runCatching { record.release() }
        }

        // 等待用户点停止：这里由外部 collectLatest 的取消驱动 awaitClose。
        // 停止后（本 Flow 被取消）UI 层调用 transcribeSaved() 完成云端识别。
    }

    /**
     * 把已录制的 PCM 送云端识别。
     * 由 UI 层在停止录音后调用（传入录音期间累积的 PCM 数据）。
     */
    suspend fun transcribe(pcmBytes: ByteArray, sampleRate: Int): Outcome<String> =
        withContext(Dispatchers.IO) {
            val cfg = settings.asrConfig.first()
            if (!cfg.isConfigured || cfg.modelName.isBlank()) {
                return@withContext AppError.Auth("云端语音没配置").err()
            }
            if (pcmBytes.size < MIN_PCM_BYTES) {
                return@withContext AppError.BadOutput("没听到声音").err()
            }

            val wav = pcmToWav(pcmBytes, sampleRate, channels = 1)
            uploadForTranscription(cfg, wav)
        }

    // ------------------------------------------------------------------
    // WAV 编码与 multipart 上传
    // ------------------------------------------------------------------

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalLen = pcm.size + 36
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }

        out.write("RIFF".toByteArray()); le32(totalLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16)
        le16(1)                      // PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(channels * bitsPerSample / 8)
        le16(bitsPerSample)
        out.write("data".toByteArray()); le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun uploadForTranscription(cfg: ModelConfig, wav: ByteArray): Outcome<String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(cfg.baseUrl.trimEnd('/') + "/audio/transcriptions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                setRequestProperty("Accept", "application/json")
            }

            val boundary = "----Shouzhe${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            conn.outputStream.use { os ->
                // file 字段
                os.write("--$boundary\r\n".toByteArray())
                os.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                        .toByteArray()
                )
                os.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
                os.write(wav)
                os.write("\r\n".toByteArray())
                // model 字段
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".toByteArray())
                os.write(cfg.modelName.toByteArray())
                os.write("\r\n".toByteArray())
                // language 字段
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n".toByteArray())
                os.write(URLEncoder.encode("zh", "UTF-8").toByteArray())
                os.write("\r\n".toByteArray())
                // 结束
                os.write("--$boundary--\r\n".toByteArray())
                os.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            when {
                code in 200..299 -> {
                    val text = JSONObject(body).optString("text").trim()
                    if (text.isBlank()) AppError.BadOutput("没听清").err() else text.ok()
                }
                code == 401 || code == 403 -> AppError.Auth("鉴权失败（HTTP $code）").err()
                code == 429 -> AppError.RateLimit(null, "请求太频繁").err()
                else -> AppError.Provider(code, body.take(200)).err()
            }
        } catch (e: java.net.SocketTimeoutException) {
            AppError.Network("识别超时").err()
        } catch (e: java.net.UnknownHostException) {
            AppError.Network("网络不可用").err()
        } catch (e: Exception) {
            AppError.Network(e.message ?: "上传失败").err()
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SIZE = 1600
        /** 少于 0.5 秒的录音视为没说话 */
        const val MIN_PCM_BYTES = SAMPLE_RATE / 2 * 2
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}