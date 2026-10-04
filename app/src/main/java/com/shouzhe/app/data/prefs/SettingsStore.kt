package com.shouzhe.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 模型供应商配置（BYOK 三要素） */
data class ModelConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val modelName: String = DEFAULT_MODEL,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"

        /** 预置常用供应商，设置页做快捷选项 */
        val PRESETS = listOf(
            Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            Preset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        )

        /** 云端语音识别常用预设 */
        val ASR_PRESETS = listOf(
            Preset("SiliconFlow", "https://api.siliconflow.cn/v1", "SenseVoiceSmall"),
            Preset("OpenAI", "https://api.openai.com/v1", "whisper-1"),
        )

        /**
         * 识图（多模态）常用预设。
         * 之所以单独给一组：DeepSeek 这类是纯文本模型，不支持识图 ——
         * 共用主配置会让「截图记账」变成一个点了必然失败的假按钮。
         */
        val VISION_PRESETS = listOf(
            Preset("SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-VL-72B-Instruct"),
            Preset("阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-max"),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-plus"),
            Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        )

        /**
         * 合并"覆盖式"子配置：**没填的字段一律沿用主配置**。
         *
         * 抽成纯函数是因为 SettingsStore 依赖 Android（Context/DataStore/加密存储），
         * JVM 单测跑不起来 —— 而这条合并规则恰恰是最需要测的：
         * 一旦写错，识图就会静默跑去一个不支持 vision 的模型上，
         * 「截图记账」就变成点了必然失败的假按钮。
         */
        fun mergeOverride(
            primary: ModelConfig,
            baseUrl: String?,
            apiKey: String?,
            modelName: String?,
        ): ModelConfig = ModelConfig(
            baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: primary.baseUrl,
            apiKey = apiKey?.takeIf { it.isNotBlank() } ?: primary.apiKey,
            modelName = modelName?.takeIf { it.isNotBlank() } ?: primary.modelName,
        )
    }

    data class Preset(val label: String, val baseUrl: String, val model: String)
}

/** 语音识别模式：离线（内置模型）或云端（用户自己的 Key） */
enum class AsrMode { OFFLINE, CLOUD }

/**
 * 设置存储。
 * API Key 走 EncryptedSharedPreferences 单独加密存放，绝不进 DataStore 明文，
 * 也绝不写入任何日志。
 */
@Singleton
class SettingsStore @Inject constructor(private val context: Context) {

    private val keyPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val modelConfig: Flow<ModelConfig> = context.dataStore.data.map { p ->
        ModelConfig(
            baseUrl = p[BASE_URL] ?: ModelConfig.DEFAULT_BASE_URL,
            apiKey = keyPrefs.getString(KEY_API, "").orEmpty(),
            modelName = p[MODEL_NAME] ?: ModelConfig.DEFAULT_MODEL,
        )
    }

    suspend fun saveModelConfig(cfg: ModelConfig) {
        context.dataStore.edit { p ->
            p[BASE_URL] = cfg.baseUrl
            p[MODEL_NAME] = cfg.modelName
        }
        keyPrefs.edit().putString(KEY_API, cfg.apiKey).apply()
    }

    /** 摘要句数偏好 */
    val summarySentences: Flow<Int> = context.dataStore.data.map { it[SUMMARY_LEN] ?: 4 }
    suspend fun setSummarySentences(n: Int) = context.dataStore.edit { it[SUMMARY_LEN] = n }

    /** 是否已完成后台保活引导 */
    val keepAliveOnboarded: Flow<Boolean> =
        context.dataStore.data.map { it[KEEPALIVE_DONE] ?: false }
    suspend fun setKeepAliveOnboarded(v: Boolean) =
        context.dataStore.edit { it[KEEPALIVE_DONE] = v }

    /** 首次启动完成标记 */
    val firstRunDone: Flow<Boolean> = context.dataStore.data.map { it[FIRST_RUN] ?: false }
    suspend fun setFirstRunDone() = context.dataStore.edit { it[FIRST_RUN] = true }

    /** 清除 Key（重置用） */
    fun clearApiKey() = keyPrefs.edit().remove(KEY_API).apply()

    // ------------------------------------------------------------------
    // 语音识别（离线 / 云端）
    // ------------------------------------------------------------------

    /** 合并后的云端 ASR 配置：未填字段沿用主配置 */
    val asrConfig: Flow<ModelConfig> = context.dataStore.data.map { p ->
        val mainBase = p[BASE_URL] ?: ModelConfig.DEFAULT_BASE_URL
        val mainKey = keyPrefs.getString(KEY_API, "").orEmpty()
        ModelConfig(
            baseUrl = p[A_BASE_URL] ?: mainBase,
            apiKey = keyPrefs.getString(KEY_A_API, "").takeUnless { it.isNullOrBlank() } ?: mainKey,
            modelName = p[A_MODEL] ?: "",
        )
    }

    val asrMode: Flow<AsrMode> = context.dataStore.data.map { p ->
        when (p[ASR_MODE]) {
            "cloud" -> AsrMode.CLOUD
            else -> AsrMode.OFFLINE
        }
    }

    suspend fun saveAsrMode(mode: AsrMode) {
        context.dataStore.edit { it[ASR_MODE] = if (mode == AsrMode.CLOUD) "cloud" else "offline" }
    }

    suspend fun saveAsrConfig(cfg: ModelConfig) {
        context.dataStore.edit { p ->
            if (cfg.baseUrl.isBlank()) p.remove(A_BASE_URL) else p[A_BASE_URL] = cfg.baseUrl
            p[A_MODEL] = cfg.modelName
        }
        keyPrefs.edit().putString(KEY_A_API, cfg.apiKey).apply()
    }

    // ------------------------------------------------------------------
    // 识图模型（截图记账）
    // ------------------------------------------------------------------

    /**
     * 合并后的识图配置：**没填的字段一律沿用主配置**。
     *
     * 与 asrConfig 的差别：asrConfig 的模型名留空就是"没填"（云端 ASR 必须指定模型）；
     * 而识图模型留空的含义是"就跟我上面填的那个模型名"——这样主模型本身是多模态时
     * 完全不用再填一遍，符合"能少填就少填"。
     */
    val visionConfig: Flow<ModelConfig> = context.dataStore.data.map { p ->
        val primary = ModelConfig(
            baseUrl = p[BASE_URL] ?: ModelConfig.DEFAULT_BASE_URL,
            apiKey = keyPrefs.getString(KEY_API, "").orEmpty(),
            modelName = p[MODEL_NAME] ?: ModelConfig.DEFAULT_MODEL,
        )
        ModelConfig.mergeOverride(
            primary = primary,
            baseUrl = p[V_BASE_URL],
            apiKey = keyPrefs.getString(KEY_V_API, null),
            modelName = p[V_MODEL],
        )
    }

    suspend fun saveVisionConfig(cfg: ModelConfig) {
        context.dataStore.edit { p ->
            if (cfg.baseUrl.isBlank()) p.remove(V_BASE_URL) else p[V_BASE_URL] = cfg.baseUrl
            if (cfg.modelName.isBlank()) p.remove(V_MODEL) else p[V_MODEL] = cfg.modelName
        }
        keyPrefs.edit().apply {
            if (cfg.apiKey.isBlank()) remove(KEY_V_API) else putString(KEY_V_API, cfg.apiKey)
        }.apply()
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("model_base_url")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val SUMMARY_LEN = intPreferencesKey("summary_sentences")
        val KEEPALIVE_DONE = booleanPreferencesKey("keepalive_onboarded")
        val FIRST_RUN = booleanPreferencesKey("first_run_done")
        const val KEY_API = "api_key"
        // 云端语音
        val A_BASE_URL = stringPreferencesKey("asr_base_url")
        val A_MODEL = stringPreferencesKey("asr_model")
        val ASR_MODE = stringPreferencesKey("asr_mode")
        const val KEY_A_API = "asr_api_key"
        // 识图模型
        val V_BASE_URL = stringPreferencesKey("vision_base_url")
        val V_MODEL = stringPreferencesKey("vision_model")
        const val KEY_V_API = "vision_api_key"
    }
}