package com.shouzhe.app

import com.shouzhe.app.data.prefs.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识图配置的"留空沿用主配置"规则（v0.7.0）。
 *
 * 这条规则错了的后果很具体：识图会静默跑到一个不支持 vision 的模型上，
 * 用户点了「🖼」必然失败 —— 正是项目最忌讳的假按钮。
 */
class VisionConfigMergeTest {

    private val primary = ModelConfig(
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "sk-main",
        modelName = "deepseek-chat",
    )

    @Test
    fun `全留空时完全沿用主配置`() {
        val merged = ModelConfig.mergeOverride(primary, null, null, null)
        assertEquals(primary, merged)
    }

    @Test
    fun `空白字符串也当没填`() {
        // 用户把输入框清空保存 —— 不能变成空 BaseURL
        val merged = ModelConfig.mergeOverride(primary, "", "  ", "")
        assertEquals(primary, merged)
        assertTrue(merged.isConfigured)
    }

    @Test
    fun `只填模型名时只覆盖模型名`() {
        val merged = ModelConfig.mergeOverride(
            primary, null, null, "Qwen/Qwen2.5-VL-72B-Instruct",
        )
        assertEquals("Qwen/Qwen2.5-VL-72B-Instruct", merged.modelName)
        assertEquals(primary.baseUrl, merged.baseUrl)
        assertEquals(primary.apiKey, merged.apiKey)
    }

    @Test
    fun `三项都覆盖时用覆盖的`() {
        val merged = ModelConfig.mergeOverride(
            primary,
            "https://api.siliconflow.cn/v1",
            "sk-vision",
            "glm-4v-plus",
        )
        assertEquals("https://api.siliconflow.cn/v1", merged.baseUrl)
        assertEquals("sk-vision", merged.apiKey)
        assertEquals("glm-4v-plus", merged.modelName)
        assertTrue(merged.isConfigured)
    }

    @Test
    fun `主配置没填 Key 时不能假装已配置`() {
        val empty = ModelConfig(apiKey = "")
        val merged = ModelConfig.mergeOverride(empty, "https://x/v1", null, "m")
        assertFalse(merged.isConfigured)
    }
}