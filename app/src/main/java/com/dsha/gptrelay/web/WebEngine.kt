package com.dsha.gptrelay.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * 网页引擎：跑真正的 GPT 网页，并把页面上的对话**流式转发**到原生界面。
 *
 * 思路就是"派一个值守的人"：WebView 负责真实的登录态与流式回答，
 * 注入的 MutationObserver 盯着对话区，一有变化就把整轮对话快照丢回原生层。
 * 原生层只做展示与输入，不碰 DOM 细节。
 *
 * 出网走本机 sing-box 的 mixed 代理（由 ProxyController 指向 127.0.0.1）。
 */
class WebEngine(private val context: Context) {

    data class Turn(val role: String, val text: String)

    var onTurns: ((List<Turn>) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    val view: WebView = WebView(context)

    @SuppressLint("SetJavaScriptEnabled")
    fun configure() {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // 登录会开 OAuth 弹窗，WebView 默认直接丢弃 → 表现为"点登录就卡住"
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // 去掉 webview 标记，避免站点把内置浏览器当成不受支持的客户端
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }
        // 第三方 cookie 必须开：OAuth 回跳要靠它才能把登录态带回来
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                // 非 http(s)（intent://、mailto:、第三方 App 的 scheme）交给系统处理
                if (!u.startsWith("http://") && !u.startsWith("https://")) {
                    openExternal(u)   // 非 http(s) 交给系统
                    return true
                }
                // http(s) 一律放行 —— 不拦截任何跳转
                return false
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                // 关键：每次文档加载完都重新注入。只在 loadUrl 后延时注入会被新文档冲掉，
                // 这正是"消息带不回来"的原因。
                inject()
            }
        }
        // 把弹窗地址接管到主 WebView，保证登录流程不断链
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                v: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val probe = WebView(context)
                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v2: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        // 弹窗地址接管到主 WebView，登录链不断
                        view.post { view.loadUrl(u) }
                        probe.destroy()
                        return true
                    }
                }
                transport.webView = probe
                resultMsg?.sendToTarget()
                return true
            }
        }
        view.webViewClient = view.webViewClient   // no-op，保持可读性
        view.addJavascriptInterface(Bridge(), "AndroidRelay")
    }

    fun load(url: String) {
        view.loadUrl(url)
    }

    fun reload() = view.reload()

    fun inject() {
        view.evaluateJavascript(RELAY_JS, null)
    }

    /** 由原生输入框发起：把文字塞进页面输入框并触发发送。 */
    fun send(text: String) {
        val escaped = org.json.JSONObject.quote(text)
        view.evaluateJavascript(
            """
            (function(){
              try {
                var box = document.querySelector('#prompt-textarea')
                       || document.querySelector('div[contenteditable="true"]')
                       || document.querySelector('textarea');
                if (!box) { AndroidRelay.onStatus('页面里找不到输入框（可能未登录或被拦）'); return; }
                box.focus();
                if (box.tagName === 'TEXTAREA') {
                  box.value = $escaped;
                  box.dispatchEvent(new Event('input', {bubbles:true}));
                } else {
                  box.innerHTML = '';
                  var p = document.createElement('p');
                  p.textContent = $escaped;
                  box.appendChild(p);
                  box.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:$escaped}));
                }
                setTimeout(function(){
                  var btn = document.querySelector('[data-testid="send-button"]')
                         || document.querySelector('button[aria-label*="Send"]')
                         || document.querySelector('button[data-testid*="send"]');
                  if (btn) { btn.click(); AndroidRelay.onStatus('已转发到网页'); }
                  else { AndroidRelay.onStatus('找不到发送按钮，已填入但未发送'); }
                }, 350);
              } catch (e) { AndroidRelay.onStatus('发送失败: ' + e); }
            })();
            """.trimIndent(),
            null,
        )
    }

    fun newChat() {
        view.evaluateJavascript(
            """
            (function(){
              try {
                var b = document.querySelector('[data-testid="create-new-chat-button"]')
                     || document.querySelector('a[href="/"]');
                if (b) { b.click(); AndroidRelay.onStatus('已新建对话'); }
                else AndroidRelay.onStatus('找不到新建按钮');
              } catch(e) { AndroidRelay.onStatus('新建失败: ' + e); }
            })();
            """.trimIndent(),
            null,
        )
    }

    fun currentUrl(): String = view.url.orEmpty()

    /** 手动重新注入并立即扫描一次。 */
    fun rescan() {
        inject()
        view.evaluateJavascript("window.__gptRelayScan && window.__gptRelayScan();", null)
        onStatus?.invoke("已重新注入守望脚本，等待页面消息…")
    }

    /** 用系统浏览器打开当前页（或指定地址）。登录走外部浏览器往往比 WebView 顺。 */
    fun openExternal(url: String? = null) {
        val target = url?.takeIf { it.isNotBlank() } ?: currentUrl()
        if (target.isBlank()) {
            onStatus?.invoke("网页还没加载，没有可打开的地址")
            return
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            onStatus?.invoke("已交给系统浏览器打开")
        } catch (t: Throwable) {
            onStatus?.invoke("没有可用的浏览器：${t.message}")
        }
    }

    fun destroy() {
        view.removeJavascriptInterface("AndroidRelay")
        view.destroy()
    }

    /** 页面 → 原生 的唯一通道。 */
    private inner class Bridge {
        @JavascriptInterface
        fun onTurns(json: String) {
            val list = ArrayList<Turn>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optString("role")
                    val text = o.optString("text")
                    if (text.isNotBlank()) list.add(Turn(role, text))
                }
            } catch (_: Throwable) {
                return
            }
            onTurns?.invoke(list)
        }

        @JavascriptInterface
        fun onStatus(msg: String) {
            onStatus?.invoke(msg)
        }
    }

    companion object {
        /**
         * 注入的守望脚本：
         *  - 用 [data-message-author-role] 抓整轮对话（这是该站点长期稳定的约定）
         *  - MutationObserver + 防抖，边流式输出边回传
         *  - 抓不到任何节点时明确上报，而不是静默失败
         */
        private val RELAY_JS = """
        (function(){
          if (window.__gptRelayInstalled) { window.__gptRelayScan && window.__gptRelayScan(); return; }
          window.__gptRelayInstalled = true;

          var SELECTORS = [
            '[data-message-author-role]',
            '[data-testid^="conversation-turn"]',
            'main article',
            'article'
          ];

          function pick() {
            for (var i = 0; i < SELECTORS.length; i++) {
              var n = document.querySelectorAll(SELECTORS[i]);
              if (n && n.length > 0) return { sel: SELECTORS[i], nodes: n };
            }
            return null;
          }

          function roleOf(el, idx) {
            var r = el.getAttribute && el.getAttribute('data-message-author-role');
            if (r) return r;
            var ts = (el.getAttribute && (el.getAttribute('data-testid') || '')) || '';
            if (/user/i.test(ts)) return 'user';
            if (/assistant/i.test(ts)) return 'assistant';
            var cls = (el.className && String(el.className)) || '';
            if (/user/i.test(cls)) return 'user';
            if (/assistant/i.test(cls)) return 'assistant';
            return (idx % 2 === 0) ? 'user' : 'assistant';
          }

          function scan() {
            try {
              var hit = pick();
              if (!hit) {
                AndroidRelay.onStatus('未命中任何选择器；title=' + document.title
                  + ' main=' + (document.querySelector('main') ? '有' : '无')
                  + ' bodyLen=' + (document.body ? document.body.innerText.length : 0));
                return;
              }
              var out = [];
              for (var i = 0; i < hit.nodes.length; i++) {
                var el = hit.nodes[i];
                var txt = (el.innerText || '').trim();
                if (txt) out.push({ role: roleOf(el, i), text: txt });
              }
              if (out.length > 0) {
                AndroidRelay.onTurns(JSON.stringify(out));
              } else {
                AndroidRelay.onStatus('命中 ' + hit.sel + '（' + hit.nodes.length + ' 个）但取不到文本');
              }
            } catch (e) {
              AndroidRelay.onStatus('扫描出错: ' + e);
            }
          }
          window.__gptRelayScan = scan;

          var timer = null;
          function schedule() {
            if (timer) clearTimeout(timer);
            timer = setTimeout(scan, 250);
          }

          function boot() {
            try {
              new MutationObserver(schedule).observe(document.body, {
                childList: true, subtree: true, characterData: true
              });
              schedule();
              AndroidRelay.onStatus('守望就绪，等待页面消息…');
            } catch (e) {
              AndroidRelay.onStatus('守望启动失败: ' + e);
            }
          }

          if (document.body) boot(); else document.addEventListener('DOMContentLoaded', boot);
        })();
        """.trimIndent()
    }
}
