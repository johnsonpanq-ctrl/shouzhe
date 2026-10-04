package com.shouzhe.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shouzhe.app.core.result.Outcome
import com.shouzhe.app.data.prefs.ModelConfig
import com.shouzhe.app.data.prefs.SettingsStore
import com.shouzhe.app.data.db.ShouzheDatabase
import com.shouzhe.app.model.gateway.ModelGateway
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
) : ViewModel() {

    private val draft = MutableStateFlow(Draft())
    private val ping = MutableStateFlow<PingState>(PingState.Idle)
    private val saved = MutableStateFlow(false)
    private val usage = MutableStateFlow(0L to 0)

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
        draft, ping, saved, usage,
    ) { d, p, s, u ->
        SettingsUiState(
            baseUrl = d.baseUrl,
            apiKey = d.apiKey,
            modelName = d.modelName,
            summarySentences = d.summarySentences,
            ping = p,
            saved = s,
            exactAlarmOk = scheduler.canScheduleExact(),
            tokenCount = u.first,
            failureCount = u.second,
            asrMode = d.asrMode,
            asrBaseUrl = d.asrBaseUrl,
            asrApiKey = d.asrApiKey,
            asrModelName = d.asrModelName,
            visionBaseUrl = d.visionBaseUrl,
            visionApiKey = d.visionApiKey,
            visionModelName = d.visionModelName,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

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