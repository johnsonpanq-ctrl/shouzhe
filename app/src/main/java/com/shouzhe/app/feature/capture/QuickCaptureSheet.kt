package com.shouzhe.app.feature.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.shouzhe.app.platform.voice.VoiceEvent
import com.shouzhe.app.platform.voice.VoiceRouter
import com.shouzhe.app.ui.theme.SzRadius
import com.shouzhe.app.ui.theme.SzSpacing
import com.shouzhe.app.ui.theme.szExtras
import kotlinx.coroutines.flow.collectLatest

/**
 * 快速录入浮层 —— 语音 + 文字 + 截图三条路（按规划）。
 *
 * 核心体验：边打字边显示「收这吧理解为」，用户看得见 AI 怎么理解的，错了立刻改。
 *
 * 布局关键：
 * - 整层用 imePadding()，键盘弹出时上移而不是被顶没
 * - 输入框固定最小高度，不会塌
 * - 语音按钮真实可用，失败可退回打字
 */
@Composable
fun QuickCaptureSheet(
    value: String,
    onValueChange: (String) -> Unit,
    preview: String?,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    /** 截图记账（v0.7.0）：选中的图片 Uri */
    onPickImage: (android.net.Uri) -> Unit,
) {
    val e = szExtras()
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()

    // 语音状态
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var voiceHint by remember { mutableStateOf<String?>(null) }

    val capVm: CaptureViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val recognizer = remember { capVm.voiceRouter }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) voiceHint = "没有麦克风权限，去系统设置里打开"
    }

    // 截图记账：系统相册选择器（Photo Picker）—— 不申请任何存储权限
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickImage(uri)
    }

    // 收集语音事件（离线引擎：边说边出字，端点检测到停顿自动结束）
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        recognizer.listen().collectLatest { ev ->
            when (ev) {
                is VoiceEvent.Ready -> voiceHint = "请说话…"
                is VoiceEvent.Speaking -> voiceHint = "听着呢…"
                is VoiceEvent.Processing -> voiceHint = "识别中…"
                is VoiceEvent.Partial -> {
                    partial = ev.text
                    onValueChange(ev.text)
                }
                is VoiceEvent.Result -> {
                    onValueChange(ev.text)
                    partial = ""
                    listening = false
                    voiceHint = null
                }
                is VoiceEvent.Error -> {
                    // 已有部分结果时不算失败（端点误判），保留文字
                    voiceHint = if (partial.isNotBlank()) null else ev.reason
                    listening = false
                }
                is VoiceEvent.Level -> Unit
            }
        }
    }

    fun startVoice() {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceHint = "正在启动语音引擎…"
        listening = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x8A0F141A))
            .clickable { onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 键盘弹出时整体上移，而不是被顶掉
                .imePadding()
                .navigationBarsPadding()
                .shadow(12.dp, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                // 阻止点击穿透到底层遮罩（用 consumeClick 的替代：吃掉手势）
                .clickable(enabled = true, onClick = {})
                .padding(horizontal = SzSpacing.headerH.dp)
                .padding(top = 10.dp, bottom = 18.dp),
        ) {
            // 把手
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(e.surfaceDeep),
            )
            Spacer(Modifier.height(14.dp))

            // 语音提示条
            if (voiceHint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SzRadius.input.dp))
                        .background(e.brandSoft)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(e.brand)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        voiceHint!!,
                        fontSize = 13.sp,
                        color = e.brand,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    if (listening) {
                        Text(
                            "停止",
                            fontSize = 12.sp,
                            color = e.brand,
                            modifier = Modifier.clickable {
                                listening = false
                                voiceHint = null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 输入区：固定最小高度，绝不塌
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .clip(RoundedCornerShape(SzRadius.input.dp))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
                    .padding(13.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        "说一句话，或粘贴一个链接",
                        fontSize = 16.sp,
                        color = e.ink3,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .focusRequester(focus),
                    textStyle = TextStyle(fontSize = 16.sp, color = e.ink, lineHeight = 24.sp),
                    cursorBrush = SolidColor(e.brand),
                )
            }

            // 解析预览
            if (preview != null) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SzRadius.input.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
                        .padding(13.dp),
                ) {
                    Text("收这吧理解为", fontSize = 11.sp, color = e.ink3)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).clip(CircleShape).background(e.brand)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            preview,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = e.ink,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 动作行：语音 + 截图 + 收着
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (listening) e.brand else e.surfaceAlt)
                        .clickable { if (listening) listening = false else startVoice() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (listening) "■" else "🎤",
                        fontSize = if (listening) 14.sp else 18.sp,
                        color = if (listening) e.onBrand else e.ink,
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 截图记账：系统相册选择器，免存储权限
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(e.surfaceAlt)
                        .clickable {
                            pickImage.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🖼", fontSize = 18.sp, color = e.ink)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(SzRadius.input.dp))
                        .background(if (value.isNotBlank()) e.brand else e.surfaceDeep)
                        .clickable(enabled = value.isNotBlank()) { onSubmit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "收着",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (value.isNotBlank()) e.onBrand else e.ink3,
                    )
                }
            }
        }
    }
}