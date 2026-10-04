package com.shouzhe.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shouzhe.app.feature.capture.QuickCaptureSheet
import com.shouzhe.app.feature.detail.DetailScreen
import com.shouzhe.app.feature.detail.DetailViewModel
import com.shouzhe.app.feature.inbox.InboxFilter
import com.shouzhe.app.feature.inbox.InboxScreen
import com.shouzhe.app.feature.inbox.InboxViewModel
import com.shouzhe.app.feature.search.SearchScreen
import com.shouzhe.app.feature.search.SearchViewModel
import com.shouzhe.app.feature.settings.KeepAliveScreen
import com.shouzhe.app.feature.settings.SettingsScreen
import com.shouzhe.app.feature.settings.SettingsViewModel
import com.shouzhe.app.domain.model.ItemType
import com.shouzhe.app.domain.parse.RuleParser
import com.shouzhe.app.ui.component.BrandSplash
import com.shouzhe.app.ui.theme.ShouzheTheme
import com.shouzhe.app.ui.theme.szExtras
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * 极简导航：四个目的地，不引入 Navigation 库。
 * 详情页用 itemId 作为 key 重建 ViewModel。
 */
private enum class Screen { INBOX, SETTINGS, KEEP_ALIVE, SEARCH, DETAIL }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedText = extractSharedText(intent)

        // 启动页撑到数据就绪，避免"先闪一下空状态再跳出内容"
        var dataReady = false
        splash.setKeepOnScreenCondition { !dataReady }

        setContent {
            ShouzheTheme {
                val e = szExtras()
                var screen by remember { mutableStateOf(Screen.INBOX) }
                var detailId by remember { mutableStateOf(-1L) }
                var showCapture by remember { mutableStateOf(false) }
                var captureText by remember { mutableStateOf("") }

                val vm: InboxViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()

                // 品牌启动页：固定 5 秒，点击跳过
                var showBrandSplash by remember { mutableStateOf(true) }

                // 首帧数据到手后收起系统启动页（防闪烁，与品牌启动页无关）
                LaunchedEffect(Unit) {
                    val start = System.currentTimeMillis()
                    while (!dataReady && System.currentTimeMillis() - start < 900) {
                        kotlinx.coroutines.delay(60)
                        if (state.items.isNotEmpty() || vm.isLoaded()) {
                            dataReady = true
                        }
                    }
                    dataReady = true
                }

                // 分享进来的内容
                LaunchedEffect(sharedText) {
                    if (!sharedText.isNullOrBlank()) {
                        if (sharedText.startsWith("http")) vm.ingestUrl(sharedText)
                        else vm.ingestText(sharedText)
                    }
                }

                // 系统返回键：从子页面回到收件箱
                androidx.activity.compose.BackHandler(
                    enabled = screen != Screen.INBOX || showCapture
                ) {
                    when {
                        showCapture -> { showCapture = false; captureText = "" }
                        screen == Screen.KEEP_ALIVE -> screen = Screen.SETTINGS
                        else -> screen = Screen.INBOX
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
                ) {
                    // 品牌启动页盖在最上层：5 秒或点击后消失
                    if (showBrandSplash) {
                        BrandSplash(onFinished = { showBrandSplash = false })
                    }

                    when (screen) {
                        Screen.INBOX -> InboxScreen(
                            state = state,
                            onFilterChange = { vm.setFilter(it) },
                            onItemClick = { id -> detailId = id; screen = Screen.DETAIL },
                            onConfirmLedger = { vm.confirmLedger(it) },
                            onQuickAdd = { showCapture = true },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenSearch = { screen = Screen.SEARCH },
                            onOpenPending = {
                                vm.setFilter(InboxFilter.LEDGER)
                            },
                            onScopeChange = vm::setSummaryScope,
                        )

                        Screen.SEARCH -> {
                            val svm: SearchViewModel = hiltViewModel()
                            val sstate by svm.state.collectAsStateWithLifecycle()
                            SearchScreen(
                                state = sstate,
                                onBack = { screen = Screen.INBOX },
                                onQueryChange = svm::onQueryChange,
                                onClear = svm::clear,
                                onItemClick = { id -> detailId = id; screen = Screen.DETAIL },
                            )
                        }

                        Screen.DETAIL -> {
                            DetailRoute(
                                itemId = detailId,
                                onBack = {
                                    screen = Screen.INBOX
                                    vm.refresh()
                                },
                            )
                        }

                        Screen.SETTINGS -> {
                            val svm: SettingsViewModel = hiltViewModel()
                            val sstate by svm.state.collectAsStateWithLifecycle()
                            SettingsScreen(
                                state = sstate,
                                onBack = { screen = Screen.INBOX },
                                onBaseUrlChange = svm::onBaseUrlChange,
                                onApiKeyChange = svm::onApiKeyChange,
                                onModelChange = svm::onModelChange,
                                onApplyPreset = svm::applyPreset,
                                onSave = svm::save,
                                onTest = svm::testConnection,
                                onClearKey = svm::clearKey,
                                onOpenKeepAlive = { screen = Screen.KEEP_ALIVE },
                                onAsrModeChange = svm::onAsrModeChange,
                                onAsrBaseUrlChange = svm::onAsrBaseUrlChange,
                                onAsrApiKeyChange = svm::onAsrApiKeyChange,
                                onAsrModelChange = svm::onAsrModelChange,
                                onApplyAsrPreset = svm::applyAsrPreset,
                                onVisionBaseUrlChange = svm::onVisionBaseUrlChange,
                                onVisionApiKeyChange = svm::onVisionApiKeyChange,
                                onVisionModelChange = svm::onVisionModelChange,
                                onApplyVisionPreset = svm::applyVisionPreset,
                            )
                        }

                        Screen.KEEP_ALIVE -> KeepAliveScreen(
                            onBack = { screen = Screen.SETTINGS },
                        )
                    }

                    // 轻提示
                    state.message?.let { msg ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(16.dp),
                        ) {
                            Snackbar(
                                modifier = Modifier.padding(bottom = 66.dp),
                                action = {
                                    Text(
                                        "知道了",
                                        modifier = Modifier.padding(8.dp),
                                        color = e.brand,
                                    )
                                },
                            ) { Text(msg) }
                        }
                        LaunchedEffect(msg) {
                            kotlinx.coroutines.delay(2600)
                            vm.dismissMessage()
                        }
                    }

                    // 快速录入
                    if (showCapture) {
                        QuickCaptureSheet(
                            value = captureText,
                            onValueChange = { captureText = it },
                            preview = previewOf(captureText),
                            onDismiss = { showCapture = false; captureText = "" },
                            onSubmit = {
                                val text = captureText
                                showCapture = false
                                captureText = ""
                                vm.ingestText(text)
                            },
                            // 截图记账：浮层关掉，识别过程用底部提示条告知
                            onPickImage = { uri ->
                                showCapture = false
                                captureText = ""
                                vm.ingestReceipt(uri)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
    }
}

/**
 * 详情页入口。
 * 用 key 强制按 itemId 重建 ViewModel —— 否则连续看两条会串数据。
 */
@androidx.compose.runtime.Composable
private fun DetailRoute(itemId: Long, onBack: () -> Unit) {
    androidx.compose.runtime.key(itemId) {
        val dvm: DetailViewModel = hiltViewModel()
        val dstate by dvm.state.collectAsStateWithLifecycle()

        androidx.compose.runtime.LaunchedEffect(itemId) { dvm.load(itemId) }

        DetailScreen(
            state = dstate,
            onBack = onBack,
            onStartEdit = dvm::startEdit,
            onCancelEdit = dvm::cancelEdit,
            onTitleChange = dvm::onTitleChange,
            onTimeChange = dvm::onTimeChange,
            onSaveTodo = dvm::saveTodo,
            onMarkDone = { dvm.markDone(); onBack() },
            onSnooze = dvm::snooze,
            onConfirmLedger = dvm::confirmLedger,
            onDeleteLedger = { dvm.deleteLedger(); onBack() },
            onStartEditLedger = dvm::startEditLedger,
            onCancelEditLedger = dvm::cancelEditLedger,
            onLedgerAmountChange = dvm::onLedgerAmountChange,
            onLedgerDirectionChange = dvm::onLedgerDirectionChange,
            onLedgerCategoryChange = dvm::onLedgerCategoryChange,
            onLedgerMerchantChange = dvm::onLedgerMerchantChange,
            onSaveLedger = dvm::saveLedger,
            onRetryExtract = dvm::retryExtract,
            onSummarize = dvm::summarize,
            onDelete = { dvm.delete(); onBack() },
            onDismissMessage = dvm::dismissMessage,
        )
    }
}

// 旧版独立正则的 previewOf 已删除：它和 RuleParser 结果不一致
// （预览说笔记、提交却是账目），被模拟器端到端测试抓出。
// 预览改为直接复用 RuleParser —— 一处逻辑，两处呈现。

/**
 * 本地即时预览 —— 直接调 RuleParser，与提交后的实际解析**完全同源**。
 * 支持多笔（"早饭15打车26" → 显示 2 笔）。
 */
private fun previewOf(text: String): String? {
    if (text.isBlank()) return null
    if (text.startsWith("http")) return "一篇文章"

    val result = RuleParser.parse(text)
    val entries = result.entries
    if (entries.isEmpty()) return null

    return if (entries.size == 1) {
        val e = entries.first()
        when (e.type) {
            ItemType.LEDGER -> {
                val amt = e.ledger?.let { "¥%.2f".format(it.amountCents / 100.0) } ?: ""
                if (amt.isNotBlank()) "一笔账目（$amt · ${e.ledger?.category}）" else "一笔账目"
            }
            ItemType.TODO -> {
                val t = e.timeExpression?.let { " · $it" } ?: ""
                "一件待办$t"
            }
            else -> "一条笔记"
        }
    } else {
        val kinds = entries.groupingBy { it.type }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString("、") { (type, n) ->
                "${n}笔" + when (type) {
                    ItemType.LEDGER -> "账目"
                    ItemType.TODO -> "待办"
                    else -> "笔记"
                }
            }
        "${entries.size} 条：$kinds"
    }
}