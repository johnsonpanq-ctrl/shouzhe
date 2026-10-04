package com.shouzhe.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shouzhe.app.data.prefs.ModelConfig
import com.shouzhe.app.ui.component.SzCard
import com.shouzhe.app.ui.component.SzPrimaryButton
import com.shouzhe.app.ui.theme.SzRadius
import com.shouzhe.app.ui.theme.SzSpacing
import com.shouzhe.app.ui.theme.szExtras

/**
 * 设置页 —— App 的入口。
 * 没有它，用户连 API Key 都填不了，AI 功能全部用不了。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApplyPreset: (ModelConfig.Preset) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClearKey: () -> Unit,
    onOpenKeepAlive: () -> Unit,
    onAsrModeChange: (com.shouzhe.app.data.prefs.AsrMode) -> Unit,
    onAsrBaseUrlChange: (String) -> Unit,
    onAsrApiKeyChange: (String) -> Unit,
    onAsrModelChange: (String) -> Unit,
    onApplyAsrPreset: (ModelConfig.Preset) -> Unit,
    // 截图记账
    onVisionBaseUrlChange: (String) -> Unit,
    onVisionApiKeyChange: (String) -> Unit,
    onVisionModelChange: (String) -> Unit,
    onApplyVisionPreset: (ModelConfig.Preset) -> Unit,
    // 数据备份（v0.11.0 / v0.12.0）
    onExportBackup: () -> Unit,
    onExportLedger: () -> Unit,
    onPickImportFile: () -> Unit,
    onDismissMessage: () -> Unit = {},
    /** 导入预览（非 null 时弹确认框） */
    importPreview: SettingsViewModel.PendingImport? = null,
    onConfirmImport: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    importing: Boolean = false,
) {
    val e = szExtras()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SzSpacing.headerH.dp)
            .padding(bottom = 40.dp),
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                fontSize = 18.sp,
                color = e.ink2,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
            )
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = e.ink)
        }

        Spacer(Modifier.height(8.dp))

        // ---------------- 模型配置 ----------------
        SectionTitle("模型（你自己的 Key）")

        SzCard {
            FieldLabel("服务商快捷选择")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelConfig.PRESETS.take(2).forEach { p ->
                    PresetChip(p.label) { onApplyPreset(p) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelConfig.PRESETS.drop(2).forEach { p ->
                    PresetChip(p.label) { onApplyPreset(p) }
                }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel("接口地址 BaseURL")
            Spacer(Modifier.height(6.dp))
            SzTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                placeholder = "https://api.deepseek.com/v1",
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("API Key")
            Spacer(Modifier.height(6.dp))
            SzTextField(
                value = state.apiKey,
                onValueChange = onApiKeyChange,
                placeholder = "sk-...",
                isPassword = true,
            )
            if (state.apiKey.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已保存：${state.maskedKey}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = e.ink3,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "清除",
                        fontSize = 11.sp,
                        color = e.brand,
                        modifier = Modifier.clickable { onClearKey() },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            FieldLabel("模型名")
            Spacer(Modifier.height(6.dp))
            SzTextField(
                value = state.modelName,
                onValueChange = onModelChange,
                placeholder = "deepseek-chat",
            )

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SzPrimaryButton(
                    text = if (state.saved) "已保存" else "保存",
                    onClick = onSave,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(SzRadius.button.dp))
                        .background(e.surfaceAlt)
                        .clickable { onTest() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = when (state.ping) {
                            is PingState.Testing -> "测试中…"
                            else -> "测试连接"
                        },
                        fontSize = 12.5.sp,
                        color = if (state.ping is PingState.Testing) e.ink3 else e.ink2,
                    )
                }
            }

            // 测试结果
            when (val p = state.ping) {
                is PingState.Ok -> {
                    Spacer(Modifier.height(12.dp))
                    ResultBanner(
                        text = "连接正常 · ${p.model}",
                        bg = e.brandSoft,
                        fg = e.brand,
                    )
                }
                is PingState.Fail -> {
                    Spacer(Modifier.height(12.dp))
                    ResultBanner(text = p.reason, bg = Color(0xFFFBE9E7), fg = Color(0xFFB3261E))
                }
                else -> Unit
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------------- 语音识别 ----------------
        SectionTitle("语音识别")

        SzCard {
            // 模式切换：离线 / 云端
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("离线（内置模型，不联网）",
                    selected = state.asrMode == com.shouzhe.app.data.prefs.AsrMode.OFFLINE) {
                    onAsrModeChange(com.shouzhe.app.data.prefs.AsrMode.OFFLINE)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("云端（准确率更高，走你的 Key）",
                    selected = state.asrMode == com.shouzhe.app.data.prefs.AsrMode.CLOUD) {
                    onAsrModeChange(com.shouzhe.app.data.prefs.AsrMode.CLOUD)
                }
            }

            // 云端模式才显示三要素
            if (state.asrMode == com.shouzhe.app.data.prefs.AsrMode.CLOUD) {
                Spacer(Modifier.height(16.dp))
                FieldLabel("服务商快捷选择")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelConfig.ASR_PRESETS.forEach { p ->
                        PresetChip(p.label) { onApplyAsrPreset(p) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                FieldLabel("接口地址（留空沿用上面的）")
                Spacer(Modifier.height(6.dp))
                SzTextField(
                    value = state.asrBaseUrl,
                    onValueChange = onAsrBaseUrlChange,
                    placeholder = "https://api.siliconflow.cn/v1",
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("API Key（留空沿用上面的）")
                Spacer(Modifier.height(6.dp))
                SzTextField(
                    value = state.asrApiKey,
                    onValueChange = onAsrApiKeyChange,
                    placeholder = "sk-...",
                    isPassword = true,
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("模型名")
                Spacer(Modifier.height(6.dp))
                SzTextField(
                    value = state.asrModelName,
                    onValueChange = onAsrModelChange,
                    placeholder = "SenseVoiceSmall",
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (state.asrMode == com.shouzhe.app.data.prefs.AsrMode.OFFLINE)
                    "离线模式：24MB 内置模型，安静环境下短句可用，语音不出手机。"
                else
                    "云端模式：录音整段发给你的供应商，准确率更高，消耗你的额度。",
                fontSize = 11.sp,
                color = e.ink3,
                lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------------- 截图记账（识图模型） ----------------
        SectionTitle("截图记账（识图模型）")

        SzCard {
            FieldLabel("服务商快捷选择（都是能看图的多模态模型）")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelConfig.VISION_PRESETS.forEach { p ->
                    PresetChip(p.label) { onApplyVisionPreset(p) }
                }
            }

            Spacer(Modifier.height(14.dp))
            FieldLabel("接口地址（留空沿用上面的）")
            Spacer(Modifier.height(6.dp))
            SzTextField(
                value = state.visionBaseUrl,
                onValueChange = onVisionBaseUrlChange,
                placeholder = "https://api.siliconflow.cn/v1",
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("API Key（留空沿用上面的）")
            Spacer(Modifier.height(6.dp))
            SzTextField(
                value = state.visionApiKey,
                onValueChange = onVisionApiKeyChange,
                placeholder = "sk-...",
                isPassword = true,
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("模型名（留空沿用上面的）")
            Spacer(Modifier.height(6.dp))
            SzTextField(
                value = state.visionModelName,
                onValueChange = onVisionModelChange,
                placeholder = "Qwen/Qwen2.5-VL-72B-Instruct",
            )

            Spacer(Modifier.height(14.dp))
            Text(
                if (state.visionReady)
                    "录入时点「🖼」选一张账单截图，App 会读出金额、商家和分类，" +
                        "生成一笔待确认的账目 —— 不点确认就不进账本。原图存在手机里，随时可查。"
                else
                    "三项没填全：截图仍会存下来，但不会被识图。按上面填的主模型如果本身就支持看图，" +
                        "这里三项可以留空。",
                fontSize = 11.sp,
                color = e.ink3,
                lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------------- 后台运行 ----------------
        SectionTitle("后台运行")

        SzCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "提醒要准时，需要 4 个开关",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = e.ink,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (state.exactAlarmOk) "精确闹钟权限：已获得"
                        else "精确闹钟权限：未获得，提醒可能延迟",
                        fontSize = 12.sp,
                        color = if (state.exactAlarmOk) e.ink2 else Color(0xFFB3261E),
                    )
                }
                Text(
                    "去设置",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = e.brand,
                    modifier = Modifier.clickable { onOpenKeepAlive() },
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------------- 数据备份（v0.11.0） ----------------
        SectionTitle("数据备份")

        SzCard {
            Text(
                "你的数据只在这台手机里，没有云端。",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = e.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "换手机、刷机、误卸载之前，先导出一份。" +
                    "导出会调起系统分享面板，你可以存到文件管理器、发给自己或传网盘。",
                fontSize = 11.5.sp,
                color = e.ink3,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(SzRadius.button.dp))
                        .background(if (state.exporting) e.surfaceDeep else e.brand)
                        .clickable(enabled = !state.exporting) { onExportBackup() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        if (state.exporting) "导出中…" else "导出全量备份",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.exporting) e.ink3 else e.onBrand,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(SzRadius.button.dp))
                        .background(e.surfaceAlt)
                        .clickable(enabled = !state.exporting) { onExportLedger() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text("导出账本 CSV", fontSize = 12.5.sp, color = e.ink2)
                }
            }

            Spacer(Modifier.height(10.dp))

            // 导入（v0.12.0）—— 与导出并列，但语义是"合并"不是"覆盖"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(SzRadius.button.dp))
                    .background(e.surfaceAlt)
                    .clickable(enabled = !state.exporting) { onPickImportFile() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text("导入备份", fontSize = 12.5.sp, color = e.ink2)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "全量备份 = JSON，含所有内容和金额；账本 CSV 只含账目，可以直接用 Excel 打开。\n" +
                    "导入是合并式：重复的条目会自动跳过，不会覆盖或清空你现在的数据。",
                fontSize = 11.sp,
                color = e.ink3,
                lineHeight = 17.sp,
            )

            // 导出结果提示（成功/失败都内联显示，不弹窗打断）
            state.message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SzRadius.input.dp))
                        .background(e.brandSoft)
                        .padding(11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            msg,
                            fontSize = 12.sp,
                            color = e.brand,
                            modifier = Modifier.weight(1f),
                            lineHeight = 18.sp,
                        )
                        Text(
                            "知道了",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = e.brand,
                            modifier = Modifier
                                .clickable { onDismissMessage() }
                                .padding(start = 10.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------------- 用量 ----------------
        SectionTitle("用量")

        SzCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("近 7 天消耗", fontSize = 12.5.sp, color = e.ink2)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.tokenCount} tokens",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = e.ink,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("失败次数", fontSize = 12.5.sp, color = e.ink2)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.failureCount}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.failureCount > 0) e.brand else e.ink,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "只记录 token 数与耗时，不记录你的内容。",
                fontSize = 11.sp,
                color = e.ink3,
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------------- 关于 ----------------
        SectionTitle("关于")

        SzCard {
            Text(
                // 读 BuildConfig 而不是硬编码 —— 之前写死 "v0.1.0"，
                // 版本涨到 0.11 还挂着旧号，属于文档谎言
                "收这吧 v" + com.shouzhe.app.BuildConfig.VERSION_NAME,
                fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = e.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "什么都往里丢，它替你存好、提纯、找回。\n数据只在你的手机里，模型用你自己的 Key。",
                fontSize = 12.5.sp,
                color = e.ink2,
                lineHeight = 20.sp,
            )
        }
    }

    // 导入确认框（v0.12.0）——
    // 必须让用户看清"要新增多少条"，再决定是否写库
    importPreview?.let { p ->
        ImportConfirmDialog(
            preview = p,
            importing = importing,
            onConfirm = onConfirmImport,
            onCancel = onCancelImport,
        )
    }
}

/**
 * 导入确认对话框。
 *
 * 为什么一定要这一步：导入是往用户唯一的数据库里写数据。
 * 如果"选完文件立刻导"，用户选错文件时连反悔的机会都没有。
 * 这里把"新增 N 条 / 跳过 M 条"摆明，用户点头才动手。
 */
@Composable
private fun ImportConfirmDialog(
    preview: SettingsViewModel.PendingImport,
    importing: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val e = szExtras()
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!importing) onCancel() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SzRadius.card.dp))
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text("导入备份", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = e.ink)
            Spacer(Modifier.height(12.dp))

            Text(
                preview.fileName,
                fontSize = 11.5.sp,
                color = e.ink3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            preview.exportedAt?.let {
                Spacer(Modifier.height(3.dp))
                Text("导出时间：${it.take(19).replace("T", " ")}", fontSize = 11.sp, color = e.ink3)
            }

            Spacer(Modifier.height(16.dp))

            // 把数字摆明白，用户才知道自己在确认什么
            Row {
                Column(Modifier.weight(1f)) {
                    Text("新增", fontSize = 12.sp, color = e.ink3)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${preview.willInsert}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = e.brand,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("已存在跳过", fontSize = 12.sp, color = e.ink3)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${preview.willSkip}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = e.ink2,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "导入是合并式的：不会清空或覆盖你已有的数据。" +
                    "导入前 App 会自动把当前数据另存一份作为保险。",
                fontSize = 11.5.sp,
                color = e.ink3,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(18.dp))
            Row {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(SzRadius.button.dp))
                        .background(if (importing) e.surfaceDeep else e.brand)
                        .clickable(enabled = !importing) { onConfirm() }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        if (importing) "导入中…" else "确认导入",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (importing) e.ink3 else e.onBrand,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(SzRadius.button.dp))
                        .background(e.surfaceAlt)
                        .clickable(enabled = !importing) { onCancel() }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("取消", fontSize = 13.sp, color = e.ink2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    val e = szExtras()
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = e.ink3,
        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    val e = szExtras()
    Text(text = text, fontSize = 11.5.sp, color = e.ink3)
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    val e = szExtras()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(SzRadius.pill.dp))
            .background(e.brandSoft)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, color = e.brand, fontWeight = FontWeight.Medium)
    }
}

/** 模式切换 Chip：选中=品牌色底 */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val e = szExtras()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(SzRadius.pill.dp))
            .background(if (selected) e.brand else e.surfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) e.onBrand else e.ink2,
        )
    }
}

@Composable
private fun SzTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
) {
    val e = szExtras()
    var revealed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SzRadius.input.dp))
            .background(e.surfaceAlt)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = e.ink3)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = e.ink,
                    fontFamily = if (isPassword) FontFamily.Monospace else FontFamily.Default,
                ),
                cursorBrush = SolidColor(e.brand),
                singleLine = true,
                visualTransformation = if (isPassword && !revealed)
                    PasswordVisualTransformation() else VisualTransformation.None,
            )
            if (isPassword && value.isNotEmpty()) {
                Text(
                    if (revealed) "隐藏" else "显示",
                    fontSize = 11.sp,
                    color = e.ink3,
                    modifier = Modifier
                        .clickable { revealed = !revealed }
                        .padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultBanner(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SzRadius.input.dp))
            .background(bg)
            .padding(12.dp),
    ) {
        Text(text, fontSize = 12.5.sp, color = fg, lineHeight = 19.sp)
    }
}