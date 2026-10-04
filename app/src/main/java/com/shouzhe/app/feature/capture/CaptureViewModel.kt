package com.shouzhe.app.feature.capture

import androidx.lifecycle.ViewModel
import com.shouzhe.app.platform.voice.VoiceRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 录入浮层的桥接 ViewModel。
 * VoiceRouter 需要 Hilt 注入（离线引擎 + 云端识别 + 设置存储），
 * Composable 里不能直接构造，经由这里获取。
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    val voiceRouter: VoiceRouter,
) : ViewModel()