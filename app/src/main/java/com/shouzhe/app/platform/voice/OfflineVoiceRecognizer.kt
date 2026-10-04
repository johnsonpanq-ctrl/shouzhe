package com.shouzhe.app.platform.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线（端侧）语音识别 —— sherpa-onnx + 中文流式 zipformer（int8）。
 *
 * 为什么放弃系统 SpeechRecognizer：
 * 国行 ROM（红米 K80 / HyperOS）无 Google 服务框架，系统识别服务不存在，
 * 真机上直接报"这台手机不支持语音识别"。参考项目（叨点）在荣耀上踩过同一个坑，
 * 它的解法就是打包 sherpa-onnx 离线模型 —— 已验证可行。
 *
 * 模型：streaming-zipformer-zh-14M int8，约 24MB，纯端侧推理：
 * - 不联网、不依赖任何系统服务
 * - 语音数据不出手机（与产品隐私立场一致）
 *
 * 接口与原 SpeechRecognizer 版本完全一致（VoiceEvent 流），UI 层无感切换。
 */
@Singleton
class OfflineVoiceRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: OnlineRecognizer? = null

    /** 模型是否已随 APK 打包（永远 true，除非 assets 被裁剪） */
    fun isAvailable(): Boolean = runCatching {
        context.assets.list("")?.contains("asr") == true ||
            context.assets.list("asr")?.isNotEmpty() == true
    }.getOrDefault(false)

    /** 懒加载识别器（首次使用时初始化，约需 1-2 秒） */
    @Synchronized
    private fun obtainRecognizer(): OnlineRecognizer? {
        if (recognizer != null) return recognizer
        return runCatching {
            // 注意：tokens / numThreads 挂在 OnlineModelConfig 上，
            // OnlineTransducerModelConfig 只有 encoder/decoder/joiner（v1.13.5 实测）
            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "asr/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "asr/decoder-epoch-99-avg-1.onnx",
                    joiner = "asr/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "asr/tokens.txt",
                numThreads = 2,
            )
            val cfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
                enableEndpoint = true,
            )
            OnlineRecognizer(assetManager = context.assets, config = cfg)
        }.onFailure {
            android.util.Log.e("OfflineVoice", "识别器初始化失败", it)
        }.getOrNull().also { recognizer = it }
    }

    /**
     * 开始一次识别：录音 + 流式解码，边说边出字。
     * 停止条件：用户调用 stop()（UI 上的停止按钮）或发生错误。
     */
    @SuppressLint("MissingPermission") // 权限由 UI 层在调用前申请
    fun listen(): Flow<VoiceEvent> = callbackFlow {
        val rec = obtainRecognizer()
        if (rec == null) {
            trySend(VoiceEvent.Error("语音引擎初始化失败"))
            close()
            return@callbackFlow
        }

        var stopped = false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            trySend(VoiceEvent.Error("录音初始化失败"))
            close()
            return@callbackFlow
        }

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
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

        val stream = rec.createStream()
        stream.acceptWaveform(FloatArray(0), sampleRate = SAMPLE_RATE)

        record.startRecording()
        trySend(VoiceEvent.Ready)

        val feedJob = launch {
            val buf = ShortArray(CHUNK_SIZE)
            var lastEmitted = ""
            var tailPadded = false
            var silenceFrames = 0

            while (!stopped && isActive) {
                val n = record.read(buf, 0, CHUNK_SIZE)
                if (n <= 0) continue

                val samples = FloatArray(n) { buf[it] / 32768f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                val isEndpoint = rec.isEndpoint(stream)
                if (isEndpoint && !tailPadded) {
                    stream.inputFinished()
                    tailPadded = true
                }
                val text = rec.getResult(stream).text.orEmpty().trim()

                if (text != lastEmitted) {
                    lastEmitted = text
                    if (text.isNotEmpty()) trySend(VoiceEvent.Partial(text))
                }

                if (isEndpoint) {
                    // 端点检测：一句话说完
                    if (text.isNotEmpty()) {
                        trySend(VoiceEvent.Result(text))
                    } else {
                        trySend(VoiceEvent.Error("没听清，再说一次"))
                    }
                    stopped = true
                }
            }
        }

        awaitClose {
            stopped = true
            runCatching {
                feedJob.cancel()
                record.stop()
            }
            runCatching { record.release() }
            runCatching { stream.release() }
        }
    }

    fun stop() = Unit // 停止由 awaitClose / UI 状态控制

    companion object {
        const val SAMPLE_RATE = 16000
        /** 100ms 一帧（1600 采样），流式解码的节奏 */
        const val CHUNK_SIZE = 1600
    }
}