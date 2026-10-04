package com.shouzhe.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.data.prefs.ModelConfig
import com.shouzhe.app.data.prefs.SettingsStore
import com.shouzhe.app.data.db.ShouzheDatabase
import com.shouzhe.app.data.repository.ItemRepository
import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.parse.CsvExport
import com.shouzhe.app.domain.parse.JsonExport
import com.shouzhe.app.model.gateway.ModelGateway
import com.shouzhe.app.platform.export.ExportResult
import com.shouzhe.app.platform.export.Exporter
import com.shouzhe.app.platform.notify.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 连接测试状态 */
sealed interface PingState {
    data object Idle : PingState
    data object Testing : PingState
    data class Ok(val model: String) : PingState
    data class Fail(val reason: String) : PingState
}

data class SettingsUiState(
    val baseUrl: String = ModelConfig.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val modelName: String = ModelConfig.DEFAULT_MODEL,
    val summarySentences: Int = 4,
    val ping: PingState = PingState.Idle,
    val saved: Boolean = false,
    val exactAlarmOk: Boolean = true,
    val tokenCount: Long = 0,
    val failureCount: Int = 0,
    // 语音识别
    val asrMode: com.shouzhe.app.data.prefs.AsrMode =
        com.shouzhe.app.data.prefs.AsrMode.OFFLINE,
    val asrBaseUrl: String = "",
    val asrApiKey: String = "",
    val asrModelName: String = "",
    // 截图记账（识图模型，v0.7.0）
    val visionBaseUrl: String = "",
    val visionApiKey: String = "",
    val visionModelName: String = "",
    // 导出（v0.11.0）
    val exporting: Boolean = false,
    val itemCount: Int = 0,
    val message: String? = null,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && modelName.isNotBlank()

    /** Key 脱敏展示 */
    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> ""
            apiKey.length <= 8 -> "••••"
            else -> apiKey.take(4) + "••••" + apiKey.takeLast(4)
        }

    /** 云端语音是否可用（三要素齐 + 模式为云端） */
    val cloudAsrReady: Boolean
        get() = asrMode == com.shouzhe.app.data.prefs.AsrMode.CLOUD &&
            asrBaseUrl.isNotBlank() && asrApiKey.isNotBlank() && asrModelName.isNotBlank()

    /** 识图三要素是否齐全（齐全才可能识别成功，否则只是把图存下来） */
    val visionReady: Boolean
        get() = visionBaseUrl.isNotBlank() && visionApiKey.isNotBlank() &&
            visionModelName.isNotBlank()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val gateway: ModelGateway,
    private val scheduler: AlarmScheduler,
    private val db: ShouzheDatabase,
    private val repo: ItemRepository,
    private val exporter: Exporter,
) : ViewModel() {

    private val draft = MutableStateFlow(Draft())
    private val ping = MutableStateFlow<PingState>(PingState.Idle)
    private val saved = MutableStateFlow(false)
    private val usage = MutableStateFlow(0L to 0)
    private val exporting = MutableStateFlow(false)
    private val itemCount = MutableStateFlow(0)
    private val message = MutableStateFlow<String?>(null)

    fun dismissMessage() { message.value = null }

    /**
     * 待分享的导出文件（v0.11.0）。
     *
     * Intent 在这里构造好，界面层只管 startActivity ——
     * 这样"怎么分享"的知识留在 ViewModel，UI 不需要知道 FileProvider 之类的细节。
     * 界面层消费后调 [consumeShareRequest] 清掉，避免重组时反复弹面板。
     */
    private val shareRequest = MutableStateFlow<android.content.Intent?>(null)

    /** 界面层订阅它来弹分享面板 */
    val shareTo: StateFlow<android.content.Intent?> = shareRequest.asStateFlow()

    fun consumeShareRequest() { shareRequest.value = null }

    fun onShareFailed() {
        shareRequest.value = null
        message.value = "没有可用的分享目标，文件已生成在应用缓存里"
    }

    private data class Draft(
        val baseUrl: String = ModelConfig.DEFAULT_BASE_URL,
        val apiKey: String = "",
        val modelName: String = ModelConfig.DEFAULT_MODEL,
        val summarySentences: Int = 4,
        // 语音识别
        val asrMode: com.shouzhe.app.data.prefs.AsrMode =
            com.shouzhe.app.data.prefs.AsrMode.OFFLINE,
        val asrBaseUrl: String = "",
        val asrApiKey: String = "",
        val asrModelName: String = "",
        // 截图记账
        val visionBaseUrl: String = "",
        val visionApiKey: String = "",
        val visionModelName: String = "",
    )

    val state: StateFlow<SettingsUiState> = combine(
        draft, ping, saved, usage, exporting, itemCount, message,
    ) { a ->
        val d = a[0] as Draft
        SettingsUiState(
            baseUrl = d.baseUrl,
            apiKey = d.apiKey,
            modelName = d.modelName,
            summarySentences = d.summarySentences,
            ping = a[1] as PingState,
            saved = a[2] as Boolean,
            exactAlarmOk = scheduler.canScheduleExact(),
            tokenCount = (a[3] as Pair<*, *>).first as Long,
            failureCount = (a[3] as Pair<*, *>).second as Int,
            asrMode = d.asrMode,
            asrBaseUrl = d.asrBaseUrl,
            asrApiKey = d.asrApiKey,
            asrModelName = d.asrModelName,
            visionBaseUrl = d.visionBaseUrl,
            visionApiKey = d.visionApiKey,
            visionModelName = d.visionModelName,
            exporting = a[4] as Boolean,
            itemCount = a[5] as Int,
            message = a[6] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    /**
     * 导出账本 CSV（给人看 / 给别的记账软件读）。
     */
    fun exportLedgerCsv() {
        viewModelScope.launch {
            exporting.value = true
            val items = runCatching { repo.findAllForExport() }.getOrDefault(emptyList())
            val csv = CsvExport.ledgerToCsv(items)
            val ledgerCount = items.count { it.type == ItemType.LEDGER }
            if (ledgerCount == 0) {
                message.value = "还没有账目可以导出"
                exporting.value = false
                return@launch
            }
            when (val r = exporter.write(fileName("账本", "csv"), csv)) {
                is ExportResult.Ok -> {
                    message.value = "已导出 $ledgerCount 笔账目"
                    shareRequest.value = exporter.shareIntent(
                        r.uri, "text/csv", "收这吧 · 账本导出",
                    )
                }
                is ExportResult.Failed -> message.value = "导出失败：${r.reason}"
            }
            exporting.value = false
        }
    }

    /**
     * 导出全量 JSON 备份（给程序读 / 将来能导回来）。
     */
    fun exportFullBackup() {
        viewModelScope.launch {
            exporting.value = true
            val items = runCatching { repo.findAllForExport() }.getOrDefault(emptyList())
            val json = JsonExport.backupToJson(
                items = items,
                appVersion = appVersion(),
            )
            when (val r = exporter.write(fileName("全量备份", "json"), json)) {
                is ExportResult.Ok -> {
                    message.value = "已备份 ${items.size} 条内容"
                    shareRequest.value = exporter.shareIntent(
                        r.uri, "application/json", "收这吧 · 全量备份",
                    )
                }
                is ExportResult.Failed -> message.value = "导出失败：${r.reason}"
            }
            exporting.value = false
        }
    }

    /** 导出文件名带时间戳，方便用户区分不同时间的备份 */
    private fun fileName(prefix: String, ext: String): String {
        val ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        return "shouzhe-$prefix-$ts.$ext"
    }

    private fun appVersion(): String =
        runCatching { com.shouzhe.app.BuildConfig.VERSION_NAME }.getOrDefault("")

    init {
        // 载入已保存的配置
        viewModelScope.launch {
            val cfg = settings.modelConfig.first()
            val len = settings.summarySentences.first()
            val asr = settings.asrConfig.first()
            val mode = settings.asrMode.first()
            val vision = settings.visionConfig.first()
            draft.value = Draft(
                baseUrl = cfg.baseUrl, apiKey = cfg.apiKey, modelName = cfg.modelName,
                summarySentences = len,
                asrMode = mode,
                asrBaseUrl = asr.baseUrl, asrApiKey = asr.apiKey, asrModelName = asr.modelName,
                visionBaseUrl = vision.baseUrl, visionApiKey = vision.apiKey,
                visionModelName = vision.modelName,
            )
        }
        // 内容条数（导出界面显示"将导出 N 条"）
        viewModelScope.launch {
            runCatching { itemCount.value = repo.findAllForExport().size }
        }
        // 载入用量统计（近 7 天）
        viewModelScope.launch {
            runCatching {
                val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                val tokens = db.modelCallDao().tokensSince(since)
                val fails = db.modelCallDao().failuresSince(since)
                usage.value = tokens to fails
            }
        }
    }

    fun onBaseUrlChange(v: String) { draft.value = draft.value.copy(baseUrl = v.trim()); saved.value = false }
    fun onApiKeyChange(v: String) { draft.value = draft.value.copy(apiKey = v.trim()); saved.value = false }
    fun onModelChange(v: String) { draft.value = draft.value.copy(modelName = v.trim()); saved.value = false }
    fun onSummarySentencesChange(n: Int) { draft.value = draft.value.copy(summarySentences = n) }

    // 语音识别
    fun onAsrModeChange(mode: com.shouzhe.app.data.prefs.AsrMode) {
        draft.value = draft.value.copy(asrMode = mode); saved.value = false
    }
    fun onAsrBaseUrlChange(v: String) { draft.value = draft.value.copy(asrBaseUrl = v.trim()); saved.value = false }
    fun onAsrApiKeyChange(v: String) { draft.value = draft.value.copy(asrApiKey = v.trim()); saved.value = false }
    fun onAsrModelChange(v: String) { draft.value = draft.value.copy(asrModelName = v.trim()); saved.value = false }
    fun applyAsrPreset(p: ModelConfig.Preset) {
        draft.value = draft.value.copy(asrBaseUrl = p.baseUrl, asrModelName = p.model)
        saved.value = false
    }

    // 截图记账（识图模型）
    fun onVisionBaseUrlChange(v: String) { draft.value = draft.value.copy(visionBaseUrl = v.trim()); saved.value = false }
    fun onVisionApiKeyChange(v: String) { draft.value = draft.value.copy(visionApiKey = v.trim()); saved.value = false }
    fun onVisionModelChange(v: String) { draft.value = draft.value.copy(visionModelName = v.trim()); saved.value = false }
    fun applyVisionPreset(p: ModelConfig.Preset) {
        draft.value = draft.value.copy(visionBaseUrl = p.baseUrl, visionModelName = p.model)
        saved.value = false
    }

    fun applyPreset(p: ModelConfig.Preset) {
        draft.value = draft.value.copy(baseUrl = p.baseUrl, modelName = p.model)
        saved.value = false
    }

    fun save() {
        viewModelScope.launch {
            val d = draft.value
            settings.saveModelConfig(
                ModelConfig(d.baseUrl, d.apiKey, d.modelName)
            )
            settings.setSummarySentences(d.summarySentences)
            // 语音识别配置一并保存
            settings.saveAsrMode(d.asrMode)
            settings.saveAsrConfig(
                ModelConfig(d.asrBaseUrl, d.asrApiKey, d.asrModelName)
            )
            // 识图模型一并保存
            settings.saveVisionConfig(
                ModelConfig(d.visionBaseUrl, d.visionApiKey, d.visionModelName)
            )
            saved.value = true
            ping.value = PingState.Idle
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            // 先把当前填写的内容存下来，否则测的是旧配置
            val d = draft.value
            settings.saveModelConfig(ModelConfig(d.baseUrl, d.apiKey, d.modelName))
            saved.value = true

            ping.value = PingState.Testing
            ping.value = when (val r = gateway.ping()) {
                is Outcome.Ok -> PingState.Ok(r.value.model)
                is Outcome.Err -> PingState.Fail(r.error.userHint())
            }
        }
    }

    fun resetPing() { ping.value = PingState.Idle }

    fun clearKey() {
        settings.clearApiKey()
        draft.value = draft.value.copy(apiKey = "")
        saved.value = false
    }

    /** 后台保活：跳转所需信息 */
    fun isXiaomi(): Boolean {
        val m = android.os.Build.MANUFACTURER.lowercase()
        val b = android.os.Build.BRAND.lowercase()
        return m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") ||
            m.contains("redmi")
    }
}