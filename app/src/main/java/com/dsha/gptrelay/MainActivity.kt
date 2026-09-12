package com.dsha.gptrelay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.dsha.gptrelay.core.ChatClient
import com.dsha.gptrelay.core.CoreManager
import com.dsha.gptrelay.core.Node
import com.dsha.gptrelay.core.Prefs
import com.dsha.gptrelay.core.XrayConfig
import com.dsha.gptrelay.core.SubscriptionParser
import com.dsha.gptrelay.ui.AuroraBackground
import com.dsha.gptrelay.ui.Glass
import com.dsha.gptrelay.web.WebEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 主界面。
 *
 * 两种引擎，共用一套原生聊天界面：
 *  - **网页模式**：WebView 跑真正的 GPT 网页，注入守望脚本把页面消息流式转发到原生界面
 *  - **接口模式**：直接调 OpenAI 兼容接口（需要 API Key）
 *
 * 布局上背景全屏铺满，内容按 window insets 避让状态栏与输入法。
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: Prefs
    private lateinit var core: CoreManager
    private lateinit var chat: ChatClient
    private lateinit var web: WebEngine
    private val mainExecutor = Executor { r -> Handler(Looper.getMainLooper()).post(r) }

    private var nodes: List<Node> = emptyList()
    private val history = ArrayList<ChatClient.Msg>()
    private var inflight: Call? = null
    private var relaySignature = ""

    /** 引擎：web=网页转发，api=直连接口 */
    private var engine = "web"

    /** 中间区域显示：chat=转发的原生消息，page=网页本体 */
    private var showPage = false

    // 视图
    private lateinit var drawer: DrawerLayout
    private lateinit var contentCol: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatBox: LinearLayout
    private lateinit var inputBar: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var subtitle: TextView
    private lateinit var modeBtn: TextView
    private lateinit var drawerCol: LinearLayout

    // 抽屉
    private lateinit var subInput: EditText
    private lateinit var nodeSpinner: Spinner
    private lateinit var apiBaseInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var statusView: TextView
    private lateinit var connectBtn: TextView
    private lateinit var engineBtn: TextView

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 背景要铺到状态栏底下，内容自己避让
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = Prefs(this)
        core = CoreManager(this)
        chat = ChatClient(XrayConfig.SOCKS_PORT)
        web = WebEngine(this)
        web.configure()
        web.onTurns = { turns -> runOnUiThread { renderTurns(turns) } }
        web.onStatus = { msg -> runOnUiThread { status("[网页] $msg") } }

        engine = prefs.engine

        setContentView(buildRoot())
        applyInsets()
        restore()
    }

    // ==================== 布局 ====================

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        // 极光背景全屏铺满，不参与 insets 避让
        root.addView(AuroraBackground(this), FrameLayout.LayoutParams(match, match))

        drawer = DrawerLayout(this)
        drawer.setScrimColor(0x66000000)
        drawer.addView(buildContent(), DrawerLayout.LayoutParams(match, match))
        drawer.addView(
            buildDrawer(),
            DrawerLayout.LayoutParams(px(316f), match).apply { gravity = Gravity.START },
        )
        root.addView(drawer, FrameLayout.LayoutParams(match, match))
        return root
    }

    /** 内容按 insets 内缩：顶部让开状态栏，底部让开导航栏/输入法。 */
    private fun applyInsets() {
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(sys.bottom, ime.bottom)

            contentCol.setPadding(0, sys.top, 0, 0)
            inputBar.setPadding(px(12f), px(10f), px(12f), px(12f) + bottom)
            drawerCol.setPadding(px(14f), px(22f) + sys.top, px(14f), px(22f) + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun buildContent(): View {
        contentCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14f), px(10f), px(14f), px(10f))
        }
        bar.addView(iconButton("☰") { drawer.openDrawer(Gravity.START) })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10f), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "GPT Free 代理"
            textSize = 16f
            setTextColor(Color.parseColor("#F2F6FF"))
            typeface = Typeface.DEFAULT_BOLD
        })
        subtitle = TextView(this).apply {
            text = "网页模式"
            textSize = 11f
            setTextColor(Color.parseColor("#9FB0CC"))
        }
        titles.addView(subtitle)
        bar.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f))
        modeBtn = iconButton("🌐") { toggleView() }
        bar.addView(modeBtn)
        bar.addView(iconButton("🔁") { web.rescan() })
        bar.addView(iconButton("🔄") {
            web.reload()
            status("已刷新页面")
        })
        bar.addView(iconButton("🗑") { clearChat() })
        contentCol.addView(bar, LinearLayout.LayoutParams(match, wrap))

        // 中间：原生消息列表 与 网页本体 叠放，按需切换
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(4f), px(14f), px(10f))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(chatBox)
            clipToPadding = false
        }
        web.view.layoutParams = FrameLayout.LayoutParams(match, match)
        web.view.visibility = View.INVISIBLE

        val area = FrameLayout(this)
        area.addView(chatScroll, FrameLayout.LayoutParams(match, match))
        area.addView(web.view, FrameLayout.LayoutParams(match, match))
        contentCol.addView(area, LinearLayout.LayoutParams(match, 0, 1f))

        // 输入栏
        inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        input = EditText(this).apply {
            hint = "说点什么…"
            textSize = 14f
            setTextColor(Color.parseColor("#F2F6FF"))
            setHintTextColor(Color.parseColor("#7C8CA8"))
            setBackgroundColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            setPadding(px(14f), px(12f), px(14f), px(12f))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else false
            }
        }
        val inputWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.panel(this@MainActivity, 22f, 0x26FFFFFF, 0x33FFFFFF)
            gravity = Gravity.BOTTOM
        }
        inputWrap.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        sendBtn = TextView(this).apply {
            text = "发送"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0B1020"))
            typeface = Typeface.DEFAULT_BOLD
            background = Glass.primaryButton(this@MainActivity, 18f)
            setPadding(px(18f), px(11f), px(18f), px(11f))
            setOnClickListener { send() }
        }
        inputWrap.addView(
            sendBtn,
            LinearLayout.LayoutParams(wrap, wrap).apply {
                marginStart = px(8f)
                topMargin = px(5f)
                bottomMargin = px(5f)
            },
        )
        inputBar.addView(inputWrap, LinearLayout.LayoutParams(match, wrap))
        contentCol.addView(inputBar, LinearLayout.LayoutParams(match, wrap))

        return contentCol
    }

    private fun buildDrawer(): View {
        val scroll = ScrollView(this)
        drawerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        drawerCol.addView(TextView(this).apply {
            text = "配置"
            textSize = 21f
            setTextColor(Color.parseColor("#F2F6FF"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(4f), 0, 0, px(14f))
        })

        // 引擎
        drawerCol.addView(sectionTitle("运行方式"))
        val enginePanel = glassColumn()
        engineBtn = actionButton("") { toggleEngine() }
        enginePanel.addView(engineBtn, lp(match, wrap))
        enginePanel.addView(TextView(this).apply {
            text = "网页：用真实 GPT 网页，无需 Key，页面消息会实时转发到这里\n接口：直连 OpenAI 兼容接口，需要填 Key"
            textSize = 10f
            setTextColor(Color.parseColor("#7C8CA8"))
            setPadding(px(2f), px(8f), 0, 0)
        }, lp(match, wrap))
        drawerCol.addView(enginePanel, lp(match, wrap, bottom = 18))

        // 机场
        drawerCol.addView(sectionTitle("机场订阅"))
        val subPanel = glassColumn()
        subInput = field("订阅地址", "https://…/subscribe?token=…", InputType.TYPE_TEXT_VARIATION_URI)
        subPanel.addView(subInput, lp(match, wrap))
        subPanel.addView(actionButton("拉取并解析") { fetchSubscription() }, lp(match, wrap, top = 10))
        subPanel.addView(label("节点"), lp(match, wrap, top = 14))
        nodeSpinner = Spinner(this)
        subPanel.addView(nodeSpinner, lp(match, wrap))
        connectBtn = actionButton("连接") { toggleConnect() }
        subPanel.addView(connectBtn, lp(match, wrap, top = 12))
        drawerCol.addView(subPanel, lp(match, wrap, bottom = 18))

        // 接口
        drawerCol.addView(sectionTitle("接口配置（接口模式用）"))
        val apiPanel = glassColumn()
        apiBaseInput = field("Base URL", "https://api.openai.com/v1", InputType.TYPE_TEXT_VARIATION_URI)
        apiPanel.addView(apiBaseInput, lp(match, wrap))
        apiKeyInput = field("API Key", "sk-…", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        apiPanel.addView(apiKeyInput, lp(match, wrap, top = 10))
        modelInput = field("模型名", "gpt-4o-mini", InputType.TYPE_CLASS_TEXT)
        apiPanel.addView(modelInput, lp(match, wrap, top = 10))
        apiPanel.addView(actionButton("保存") { saveApi() }, lp(match, wrap, top = 12))
        drawerCol.addView(apiPanel, lp(match, wrap, bottom = 18))

        // 状态
        drawerCol.addView(sectionTitle("状态"))
        val statusPanel = glassColumn()
        statusView = TextView(this).apply {
            text = "就绪"
            textSize = 11f
            setTextColor(Color.parseColor("#BFD8F5"))
            typeface = Typeface.MONOSPACE
        }
        statusPanel.addView(statusView, lp(match, wrap))
        statusPanel.addView(
            actionButton("复制诊断信息（配置 + 日志）") { copyDiagnostics() },
            lp(match, wrap, top = 10),
        )
        drawerCol.addView(statusPanel, lp(match, wrap))

        scroll.addView(drawerCol)
        return scroll
    }

    // ---------- 小部件 ----------

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = px(top.toFloat())
            bottomMargin = px(bottom.toFloat())
        }

    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(Color.parseColor("#8FA6C4"))
        setPadding(px(6f), 0, 0, px(8f))
    }

    private fun glassColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.panel(this@MainActivity, 24f)
        setPadding(px(14f), px(14f), px(14f), px(14f))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 11f
        setTextColor(Color.parseColor("#9FB0CC"))
        setPadding(px(2f), 0, 0, px(6f))
    }

    private fun field(hintText: String, sample: String, type: Int) = EditText(this).apply {
        hint = "$hintText  例：$sample"
        textSize = 12f
        inputType = type
        setTextColor(Color.parseColor("#F2F6FF"))
        setHintTextColor(Color.parseColor("#63748F"))
        background = Glass.panel(this@MainActivity, 14f, 0x14FFFFFF, 0x26FFFFFF)
        setPadding(px(12f), px(11f), px(12f), px(11f))
    }

    private fun actionButton(text0: String, onClick: () -> Unit) = TextView(this).apply {
        text = text0
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#EAF1FF"))
        background = Glass.panel(this@MainActivity, 16f, 0x2EFFFFFF, 0x40FFFFFF)
        setPadding(px(10f), px(11f), px(10f), px(11f))
        setOnClickListener { onClick() }
    }

    private fun iconButton(glyph: String, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#DCE6FF"))
        background = Glass.panel(this@MainActivity, 14f, 0x1FFFFFFF, 0x2EFFFFFF)
        setPadding(px(10f), px(7f), px(10f), px(7f))
        setOnClickListener { onClick() }
    }

    private inner class DarkAdapter(ctx: Context, items: List<String>) :
        ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent) as TextView
            v.setTextColor(Color.parseColor("#F2F6FF"))
            v.textSize = 13f
            return v
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getDropDownView(position, convertView, parent) as TextView
            v.setTextColor(Color.parseColor("#F2F6FF"))
            v.setBackgroundColor(Color.parseColor("#141B2B"))
            v.textSize = 13f
            v.setPadding(px(14f), px(12f), px(14f), px(12f))
            return v
        }
    }

    // ==================== 引擎切换 ====================

    private fun toggleEngine() {
        engine = if (engine == "web") "api" else "web"
        prefs.engine = engine
        applyEngine()
    }

    private fun applyEngine() {
        engineBtn.text = if (engine == "web") "当前：网页模式（点击切换到接口）" else "当前：接口模式（点击切换到网页）"
        subtitle.text = if (engine == "web") {
            if (core.isRunning()) "网页模式 · 已连接" else "网页模式 · 未连接"
        } else {
            "接口模式 · ${prefs.model}"
        }
        if (engine == "web") {
            relaySignature = ""
            chatBox.removeAllViews()
            addBubble(greetingWeb(), mine = false)
            web.onStatus?.invoke("准备加载网页…")
        } else {
            chatBox.removeAllViews()
            for (m in history) addBubble(m.content, mine = m.role == "user")
            if (history.isEmpty()) addBubble(greetingApi(), mine = false)
        }
    }

    private fun toggleView() {
        showPage = !showPage
        web.view.visibility = if (showPage) View.VISIBLE else View.INVISIBLE
        chatScroll.visibility = if (showPage) View.INVISIBLE else View.VISIBLE
        modeBtn.text = if (showPage) "💬" else "🌐"
        if (showPage) status("显示网页本体") else status("显示转发的消息")
    }

    private fun startWeb() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            status("当前 WebView 不支持代理设置（PROXY_OVERRIDE），网页模式无法走梯子")
            return
        }
        try {
            val cfg = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:${XrayConfig.HTTP_PORT}")
                .build()
            ProxyController.getInstance().setProxyOverride(cfg, mainExecutor) { }
        } catch (t: Throwable) {
            status("设置网页代理失败：${t.message}")
        }
        web.load(prefs.webUrl)
        web.view.postDelayed({ web.inject() }, 2500)
    }

    // ==================== 状态恢复 ====================

    private fun restore() {
        subInput.setText(prefs.subscriptionUrl)
        apiBaseInput.setText(prefs.apiBase)
        apiKeyInput.setText(prefs.apiKey)
        modelInput.setText(prefs.model)
        nodes = decodeNodes(prefs.nodesCache)
        bindNodes(prefs.selectedKey)
        history.clear()
        history.addAll(decodeHistory(prefs.history))
        applyEngine()
        refreshSubtitle()
        status("节点缓存 ${nodes.size} 个")
        scrollBottom()
    }

    private fun greetingWeb() =
        "网页模式：正在加载真实 GPT 网页，页面上的对话会自动转发到这里。\n\n若网页要求登录，点右上角 🌐 切到网页本体登录一次即可。"
    private fun greetingApi() =
        "接口模式：填好 Base URL / API Key / 模型名后直接聊天（走你的机场线路出网）。"

    private fun refreshSubtitle() {
        subtitle.text = when {
            engine == "api" -> "接口模式 · ${prefs.model}"
            core.isRunning() -> "网页模式 · 已连接"
            else -> "网页模式 · 未连接"
        }
    }

    // ==================== 消息渲染 ====================

    private fun addBubble(text: String, mine: Boolean): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, px(5f), 0, px(5f))
        }
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (mine) Color.parseColor("#0B1020") else Color.parseColor("#EAF1FF"))
            background = Glass.bubble(this@MainActivity, mine)
            setPadding(px(14f), px(11f), px(14f), px(11f))
            setTextIsSelectable(true)
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        row.addView(bubble, LinearLayout.LayoutParams(maxW, wrap))
        chatBox.addView(row)
        scrollBottom()
        return bubble
    }

    /** 网页转发的整轮快照 → 原生气泡。只在内容变化时重建。 */
    private fun renderTurns(turns: List<WebEngine.Turn>) {
        if (turns.isEmpty()) return
        val sig = turns.joinToString("|") { "${it.role}#${it.text.length}#${it.text.hashCode()}" }
        if (sig == relaySignature) return
        relaySignature = sig
        chatBox.removeAllViews()
        for (t in turns) addBubble(t.text, mine = t.role == "user")
        scrollBottom()
    }

    private fun scrollBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearChat() {
        if (engine == "web") {
            relaySignature = ""
            chatBox.removeAllViews()
            addBubble(greetingWeb(), mine = false)
        } else {
            history.clear()
            prefs.history = ""
            chatBox.removeAllViews()
            addBubble(greetingApi(), mine = false)
        }
    }

    // ==================== 订阅 ====================

    private fun fetchSubscription() {
        val url = subInput.text.toString().trim()
        if (url.isEmpty()) {
            status("请先填写订阅地址")
            return
        }
        prefs.subscriptionUrl = url
        status("正在拉取订阅…")

        scope.launch {
            var r = withContext(Dispatchers.IO) { download(url, viaProxy = false) }
            var how = "直连"
            if ((r.body == null || r.code !in 200..299) && core.isRunning()) {
                r = withContext(Dispatchers.IO) { download(url, viaProxy = true) }
                how = "经代理"
            }
            val body = r.body
            if (body == null || r.code !in 200..299) {
                status(
                    "拉取失败（$how）\nHTTP ${r.code}\n" +
                        if (body.isNullOrEmpty()) "响应为空：网络不可达、地址无效，或被运营商拦截" else "响应开头：\n${body.take(200)}",
                )
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) { SubscriptionParser.parseDetailed(body) }
            if (parsed.nodes.isEmpty()) {
                status(
                    "解析出 0 个节点（$how，HTTP ${r.code}，响应 ${body.length} 字）\n" +
                        "识别为：${parsed.kind}\n${parsed.detail}\n" +
                        "--- 响应开头 ---\n${body.take(220)}",
                )
                return@launch
            }
            nodes = parsed.nodes
            prefs.nodesCache = encodeNodes(parsed.nodes)
            bindNodes(prefs.selectedKey)
            status("✅ ${how}拉取成功\n${parsed.kind}：${parsed.detail}")
        }
    }

    /** 把配置与日志打包进剪贴板，便于直接把问题贴出来。 */
    private fun copyDiagnostics() {
        val text = buildString {
            appendLine("=== GPT Free 代理 · 诊断 ===")
            appendLine("引擎: $engine")
            appendLine("核心运行中: ${core.isRunning()}")
            appendLine("节点数: ${nodes.size}")
            appendLine("已选节点: ${currentSelection()?.display ?: "无"}")
            appendLine("订阅地址: ${prefs.subscriptionUrl}")
            appendLine("接口: ${prefs.apiBase} / ${prefs.model} / key长度=${prefs.apiKey.length}")
            appendLine()
            appendLine("=== config.json ===")
            appendLine(core.configText().take(1500))
            appendLine()
            appendLine("=== core.log（错误行）===")
            appendLine(core.errorSummary(20))
        }
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("gptrelay-diag", text))
            status("诊断信息已复制到剪贴板，直接粘贴发出去即可")
        } catch (t: Throwable) {
            status("复制失败：${t.message}")
        }
    }

    private class Fetch(val code: Int, val body: String?)

    private fun download(url: String, viaProxy: Boolean): Fetch = try {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
        if (viaProxy) {
            b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT)))
        }
        val req = Request.Builder()
            .url(url)
            // 用 Clash 的 UA，机场通常直接返回 YAML，省掉一层 base64
            .header("User-Agent", "ClashMeta/1.18.0")
            .build()
        b.build().newCall(req).execute().use { resp ->
            Fetch(resp.code, runCatching { resp.body?.string() }.getOrNull())
        }
    } catch (_: Throwable) {
        Fetch(-1, null)
    }

    private fun bindNodes(selectedKey: String) {
        val labels = nodes.map { "${it.protocol} · ${it.display}" }
        nodeSpinner.adapter = DarkAdapter(this, labels.ifEmpty { listOf("（暂无节点）") })
        val idx = nodes.indexOfFirst { it.key == selectedKey }
        if (idx >= 0) nodeSpinner.setSelection(idx)
    }

    private fun currentSelection(): Node? {
        if (nodes.isEmpty()) return null
        return nodes.getOrNull(nodeSpinner.selectedItemPosition)
    }

    // ==================== 连接 ====================

    private fun toggleConnect() {
        if (core.isRunning()) {
            core.stop()
            connectBtn.text = "连接"
            status("已断开")
            refreshSubtitle()
            return
        }
        val node = currentSelection()
        if (node == null) {
            status("没有可用节点，请先拉取订阅")
            return
        }
        if (node.protocol in XrayConfig.UNSUPPORTED) {
            status("该节点是 ${node.protocol}，Xray 核心不支持。\n请在节点列表里换一个 vmess / vless / trojan / ss 节点。")
            return
        }
        prefs.selectedKey = node.key
        core.writeConfig(XrayConfig.build(node))
        val err = core.start()
        if (err == null) {
            connectBtn.text = "断开"
            status("已连接：${node.display}\n本地代理 127.0.0.1:${XrayConfig.SOCKS_PORT}")
            refreshSubtitle()
            if (engine == "web") startWeb()
        } else {
            status("连接失败\n$err")
        }
    }

    // ==================== 发送 ====================

    private fun saveApi() {
        prefs.apiBase = apiBaseInput.text.toString().trim()
        prefs.apiKey = apiKeyInput.text.toString().trim()
        prefs.model = modelInput.text.toString().trim()
        status("接口配置已保存")
        refreshSubtitle()
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")

        if (engine == "web") {
            if (!web.view.url.isNullOrBlank()) {
                web.send(text)
                status("已把消息转发到网页…")
            } else {
                status("网页还没加载，请先在左侧连接节点")
                drawer.openDrawer(Gravity.START)
            }
            return
        }

        if (!core.isRunning()) {
            status("尚未连接节点 —— 请先拉取订阅并点「连接」")
            drawer.openDrawer(Gravity.START)
            return
        }
        if (prefs.apiKey.isBlank()) {
            status("请先填写 API Key（左侧「接口配置」）")
            drawer.openDrawer(Gravity.START)
            return
        }

        addBubble(text, mine = true)
        history.add(ChatClient.Msg("user", text))
        val bubble = addBubble("", mine = false)
        sendBtn.isEnabled = false
        sendBtn.alpha = 0.5f

        inflight = chat.stream(
            baseUrl = prefs.apiBase,
            apiKey = prefs.apiKey,
            model = prefs.model,
            messages = history.toList(),
            onDelta = { piece ->
                runOnUiThread {
                    bubble.text = bubble.text.toString() + piece
                    scrollBottom()
                }
            },
            onDone = {
                runOnUiThread {
                    val answer = bubble.text.toString()
                    if (answer.isNotEmpty()) history.add(ChatClient.Msg("assistant", answer))
                    prefs.history = encodeHistory(history)
                    sendBtn.isEnabled = true
                    sendBtn.alpha = 1f
                }
            },
            onError = { msg ->
                runOnUiThread {
                    bubble.text = "⚠️ $msg"
                    sendBtn.isEnabled = true
                    sendBtn.alpha = 1f
                }
            },
        )
    }

    private fun status(s: String) {
        statusView.text = s
    }

    // ==================== 编解码 ====================

    private fun encodeNodes(list: List<Node>): String =
        list.joinToString("\n") { n ->
            val params = n.params.entries.joinToString("&") { "${it.key}=${it.value}" }
            "${n.protocol}\t${n.server}\t${n.port}\t${n.name}\t$params"
        }

    private fun decodeNodes(raw: String): List<Node> {
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) return@mapNotNull null
            val params = if (f.size >= 5 && f[4].isNotEmpty()) {
                f[4].split('&').mapNotNull { kv ->
                    val i = kv.indexOf('=')
                    if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
                }.toMap()
            } else emptyMap()
            Node(
                name = f[3],
                protocol = f[0],
                server = f[1],
                port = f[2].toIntOrNull() ?: return@mapNotNull null,
                params = params,
            )
        }
    }

    private fun encodeHistory(list: List<ChatClient.Msg>): String {
        val arr = org.json.JSONArray()
        for (m in list) {
            arr.put(org.json.JSONObject().put("role", m.role).put("content", m.content))
        }
        return arr.toString()
    }

    private fun decodeHistory(raw: String): List<ChatClient.Msg> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChatClient.Msg(o.optString("role"), o.optString("content"))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun onDestroy() {
        inflight?.cancel()
        core.stop()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                ProxyController.getInstance().clearProxyOverride(mainExecutor) { }
            } catch (_: Throwable) {
            }
        }
        web.destroy()
        super.onDestroy()
    }
}
