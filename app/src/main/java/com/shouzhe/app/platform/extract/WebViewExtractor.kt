package com.shouzhe.app.platform.extract

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 正文抽取器 —— 离屏 WebView 渲染 + 注入 JS 抽取（见 ADR-001）。
 *
 * 微信有 Cookie 鉴权 + JS 动态渲染 + 反爬头三层防御；头条有 JS 签名。
 * HTTP 直抓拿不到正文，而 WebView 本身就是真浏览器 —— 对服务端而言
 * 就是「一个真实用户在读文章」。
 *
 * 铁律：
 * - 串行执行（同一时刻只跑一个 WebView），防内存爆炸
 * - 必须 destroy()，否则内存泄漏
 * - 超时 15s 即降级
 * - 正文字数 < 200 视为失败
 */
@Singleton
class WebViewExtractor @Inject constructor(
    private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    suspend fun extract(url: String): ExtractOutcome = mutex.withLock {
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MS) { renderAndExtract(url) }
                ?: ExtractOutcome.Failed("超时（${TIMEOUT_MS / 1000} 秒未出正文）")
        }
    }

    /**
     * 渲染并抽取。用回调风格的 Session 持有状态，避免 continuation 悬空。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderAndExtract(url: String): ExtractOutcome =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val session = Session(
                context = context,
                onDone = { outcome, wv ->
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.removeAllViews()
                        wv.destroy()
                    } catch (_: Throwable) {
                    }
                    if (cont.isActive) cont.resume(outcome) { _, _, _ -> }
                },
            )
            cont.invokeOnCancellation { session.cancel() }
            session.start(url)
        }

    /** 单次抽取会话：管理 WebView 生命周期、重试与超时收尾 */
    private inner class Session(
        private val context: Context,
        private val onDone: (ExtractOutcome, WebView) -> Unit,
    ) {
        private var webView: WebView? = null
        private var attempts = 0
        private var done = false

        @SuppressLint("SetJavaScriptEnabled")
        fun start(url: String) {
            val wv = try {
                WebView(context)
            } catch (t: Throwable) {
                finish(ExtractOutcome.Failed("WebView 初始化失败：${t.message}"), null)
                return
            }
            webView = wv

            wv.settings.apply {
                javaScriptEnabled = true        // 必须：正文靠 JS 渲染
                domStorageEnabled = true        // 必须：微信登录态
                userAgentString = pickUserAgent(url)   // 微信域名用微信客户端 UA（实测放行）
                blockNetworkImage = true        // 只要文字，省流量
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    main.postDelayed({ attempt(view) }, RENDER_WAIT_MS)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        finish(
                            ExtractOutcome.Failed("页面加载失败：${error?.description ?: "未知"}"),
                            view,
                        )
                    }
                }
            }

            try {
                wv.loadUrl(url)
            } catch (t: Throwable) {
                finish(ExtractOutcome.Failed("加载异常：${t.message}"), wv)
            }
        }

        private fun attempt(view: WebView?) {
            if (done || view == null) return
            view.evaluateJavascript(EXTRACT_JS) { raw ->
                if (done) return@evaluateJavascript
                val outcome = parseJsResult(raw)
                if (outcome is ExtractOutcome.Failed && attempts < MAX_ATTEMPTS) {
                    attempts++
                    main.postDelayed({ attempt(view) }, RETRY_WAIT_MS)
                } else {
                    finish(outcome, view)
                }
            }
        }

        fun cancel() = finish(ExtractOutcome.Failed("已取消"), webView)

        private fun finish(outcome: ExtractOutcome, wv: WebView?) {
            if (done) return
            done = true
            onDone(outcome, wv ?: return)
        }
    }

    private fun parseJsResult(raw: String?): ExtractOutcome {
        if (raw.isNullOrBlank() || raw == "null") return ExtractOutcome.Failed("未返回内容")
        return try {
            val unescaped = org.json.JSONTokener(raw).nextValue() as? String ?: raw
            val obj = JSONObject(unescaped)
            val title = obj.optString("title").trim()
            val text = obj.optString("text").trim()
            if (text.length < MIN_WORD_COUNT) {
                ExtractOutcome.Failed("正文过短（${text.length} 字）")
            } else {
                ExtractOutcome.Success(
                    title = title,
                    text = text,
                    author = obj.optString("author").ifBlank { null },
                    siteName = obj.optString("site").ifBlank { null },
                    wordCount = text.length,
                )
            }
        } catch (e: Exception) {
            ExtractOutcome.Failed("解析结果失败：${e.message}")
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val RENDER_WAIT_MS = 1_200L
        private const val RETRY_WAIT_MS = 1_800L
        private const val MAX_ATTEMPTS = 2
        private const val MIN_WORD_COUNT = 200

        /** 桌面 UA：移动 UA 常被重定向到精简页，正文不全 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * 微信客户端 UA —— 2026-10-03 实测：
         * 普通 UA 抓微信文章会被「环境异常」验证页拦截；
         * 带 MicroMessenger 标识的请求直接返回完整正文（3.5MB HTML 含全文）。
         * 微信对自家客户端 UA 放行 —— 这是实测得出的最高成功率路线。
         */
        private const val WECHAT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.49"

        /** 按域名选 UA：微信文章用微信 UA，其他站点用桌面 UA */
        fun pickUserAgent(url: String): String =
            if (url.contains("weixin.qq.com") || url.contains("mp.weixin")) WECHAT_UA
            else DESKTOP_UA

        /** 依次尝试：微信 → 头条 → 通用 article → 最大文本块启发式 */
        private val EXTRACT_JS = """
        (function() {
          function pick(sel) {
            var el = document.querySelector(sel);
            return el ? (el.innerText || el.textContent || '') : '';
          }
          function meta(name) {
            var m = document.querySelector('meta[property="' + name + '"]')
                 || document.querySelector('meta[name="' + name + '"]');
            return m ? (m.getAttribute('content') || '') : '';
          }
          var site = '';
          var text = pick('.rich_media_content');
          var title = pick('.rich_media_title') || pick('#activity-name');
          var author = pick('.profile_nickname') || pick('#js_name');
          if (text.length >= 200) { site = '微信公众号'; }
          if (text.length < 200) {
            text = pick('article') || pick('.article-content') || pick('.syl-article-base');
            title = title || pick('h1') || meta('og:title');
            author = author || meta('author');
            if (text.length >= 200) { site = '今日头条'; }
          }
          if (text.length < 200) {
            text = pick('article') || pick('[role="main"]') || pick('main') || pick('.post-content');
            title = title || meta('og:title') || (document.title || '');
            author = author || meta('author');
            site = location.hostname || '';
          }
          if (text.length < 200) {
            var best = '', nodes = document.querySelectorAll('div,section,td');
            for (var i = 0; i < nodes.length; i++) {
              var t = nodes[i].innerText || '';
              if (t.length > best.length && nodes[i].children.length < 40) best = t;
            }
            text = best;
          }
          text = (text || '').replace(/\n{3,}/g, '\n\n').replace(/[ \t]{2,}/g, ' ').trim();
          title = (title || document.title || '').replace(/\s+/g, ' ').trim();
          return JSON.stringify({
            title: title.substring(0, 120),
            text: text,
            author: (author || '').substring(0, 60),
            site: (site || '').substring(0, 40)
          });
        })();
        """.trimIndent()
    }
}

sealed class ExtractOutcome {
    data class Success(
        val title: String,
        val text: String,
        val author: String?,
        val siteName: String?,
        val wordCount: Int,
    ) : ExtractOutcome()

    data class Failed(val reason: String) : ExtractOutcome()
}