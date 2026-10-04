package com.shouzhe.app.platform.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.shouzhe.app.core.result.AppError
import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.core.result.err
import com.shouzhe.app.data.prefs.AsrMode
import com.shouzhe.app.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音识别路由 —— 按用户配置在「离线引擎」与「云端 API」之间切换。
 *
 * 两种模式的事件流完全一致（VoiceEvent），UI 层无感切换：
 * - OFFLINE（默认）：sherpa-onnx 内置模型，不联网，端侧推理
 * - CLOUD：OpenAI 兼容 /audio/transcriptions，走用户自己的 Key，准确率更高
 */
@Singleton
class VoiceRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val offline: OfflineVoiceRecognizer,
    private val cloud: CloudVoiceRecognizer,
) {
    /** 当前配置的模式（UI 显示用） */
    suspend fun currentMode(): AsrMode = settings.asrMode.first()

    fun isAvailable(): Boolean = true   // 两种模式都内置，恒可用

    /**
     * 开始识别。
     * - 离线：边说边出字，端点检测自动结束 —— 与旧版完全一致
     * - 云端：录音 → 用户点停止 → 整段送云端识别
     *
     * 返回的事件流自带结束（Result / Error），UI 层 collectLatest 驱动。
     */
    fun listen(): Flow<VoiceEvent> = callbackFlow {
        val mode = settings.asrMode.first()

        when (mode) {
            AsrMode.OFFLINE -> {
                // 透传离线引擎的事件流
                offline.listen().collect { ev -> trySend(ev) }
            }
            AsrMode.CLOUD -> {
                // 云端模式：录音在 flow 内累积，stop 被调用后整段识别
                cloudListen().collect { ev -> trySend(ev) }
            }
        }

        awaitClose { }
    }

    /**
     * 云端模式的完整流程（录音 + 识别在一个 Flow 里）：
     * Ready → 说话（累积 PCM）→ 停止信号 → 识别 → Result/Error
     */
    private fun cloudListen(): Flow<VoiceEvent> = callbackFlow {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            trySend(VoiceEvent.Error("没有麦克风权限"))
            close()
            return@callbackFlow
        }

        val cfg = settings.asrConfig.first()
        if (cfg.modelName.isBlank()) {
            trySend(VoiceEvent.Error("云端语音没配模型名，去设置里填"))
            close()
            return@callbackFlow
        }

        trySend(VoiceEvent.Ready)

        // 录音累积（在 IO 线程）
        val pcm = withContext(Dispatchers.IO) { recordPcm() }
        // recordPcm 返回时即用户已停止（由外部取消触发）

        trySend(VoiceEvent.Processing)
        when (val r = cloud.transcribe(pcm.first, SAMPLE_RATE)) {
            is Outcome.Ok -> trySend(VoiceEvent.Result(r.value))
            is Outcome.Err -> trySend(VoiceEvent.Error(r.error.userHint()))
        }
        close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}

/**
 * 录音直到外部取消。返回 (pcmBytes, frames)。
 * 简化实现：录满 60 秒自动停，或协程取消时停。
 */
@SuppressLint("MissingPermission")
private suspend fun recordPcm(): Pair<ByteArray, Int> =
    withContext(Dispatchers.IO) {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            VoiceRouter.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return@withContext Pair(ByteArray(0), 0)

        val record = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                VoiceRouter.SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            return@withContext Pair(ByteArray(0), 0)
        }
        if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext Pair(ByteArray(0), 0)
        }

        val out = java.io.ByteArrayOutputStream()
        val buf = ShortArray(VoiceRouter.SAMPLE_RATE / 10)   // 100ms
        val maxFrames = 60 * 10   // 最多 60 秒
        var frames = 0

        try {
            record.startRecording()
            while (frames < maxFrames && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) {
                    out.write(buf[i].toInt() and 0xFF)
                    out.write((buf[i].toInt() shr 8) and 0xFF)
                }
                frames++
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        Pair(out.toByteArray(), frames)
    }