package com.shouzhe.app.platform.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 语音识别 —— 用手机原生的 SpeechRecognizer（按规划：不自建语音服务）。
 *
 * 现实情况：各厂商 ROM 的实现不一致，部分机型离线识别能力差。
 * 所以必须做好兜底 —— 识别失败就退回手动输入，绝不卡住用户。
 */
@Singleton
class VoiceRecognizer @Inject constructor(
    private val context: Context,
) {
    /** 设备是否支持语音识别 */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 开始一次识别。
     * 用 callbackFlow 把监听器回调转成事件流，UI 侧收集。
     */
    fun listen(): Flow<VoiceEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(VoiceEvent.Error("这台手机不支持语音识别，直接打字吧"))
            close()
            return@callbackFlow
        }

        // 必须在主线程创建
        val recognizer = android.os.Handler(android.os.Looper.getMainLooper()).let {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 不弹系统 UI，自己在界面上画状态
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceEvent.Ready)
            }

            override fun onBeginningOfSpeech() {
                trySend(VoiceEvent.Speaking)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(VoiceEvent.Level(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                trySend(VoiceEvent.Processing)
            }

            override fun onError(error: Int) {
                trySend(VoiceEvent.Error(describeError(error)))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) {
                    trySend(VoiceEvent.Error("没听清，再说一次"))
                } else {
                    trySend(VoiceEvent.Result(text))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) trySend(VoiceEvent.Partial(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            runCatching { recognizer.startListening(intent) }
                .onFailure { trySend(VoiceEvent.Error("启动语音识别失败")) }
        }

        awaitClose {
            main.post {
                runCatching {
                    recognizer.stopListening()
                    recognizer.cancel()
                    recognizer.destroy()
                }
            }
        }
    }

    /** 主动停止（用户点"说完了"） */
    fun stop() = Unit

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，检查麦克风权限"
        SpeechRecognizer.ERROR_CLIENT -> "识别被中断，再试一次"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络不通，语音识别需要联网"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别忙，稍等一下"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "识别失败，直接打字吧"
    }
}

/** 语音识别事件 */
sealed interface VoiceEvent {
    /** 准备好了，可以说话 */
    data object Ready : VoiceEvent
    /** 正在说话 */
    data object Speaking : VoiceEvent
    /** 音量变化（画波形用） */
    data class Level(val rms: Float) : VoiceEvent
    /** 说完了，正在识别 */
    data object Processing : VoiceEvent
    /** 中间结果（边说边出字） */
    data class Partial(val text: String) : VoiceEvent
    /** 最终结果 */
    data class Result(val text: String) : VoiceEvent
    /** 出错 */
    data class Error(val reason: String) : VoiceEvent
}