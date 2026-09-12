package com.dsha.gptrelay.core

import android.content.Context

/** 配置存储：机场订阅、节点缓存、选中的节点、以及聊天接口参数。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("gpt_relay", Context.MODE_PRIVATE)

    // ---------- 机场 ----------

    var subscriptionUrl: String
        get() = sp.getString("sub_url", "").orEmpty()
        set(v) = sp.edit().putString("sub_url", v).apply()

    /** 节点列表以制表符分隔的文本行缓存，避免引入序列化依赖。 */
    var nodesCache: String
        get() = sp.getString("nodes", "").orEmpty()
        set(v) = sp.edit().putString("nodes", v).apply()

    var selectedKey: String
        get() = sp.getString("selected", "").orEmpty()
        set(v) = sp.edit().putString("selected", v).apply()

    /** 引擎：web=网页转发（无需 Key），api=直连接口（需 Key）。 */
    var engine: String
        get() = sp.getString("engine", "web").orEmpty().ifBlank { "web" }
        set(v) = sp.edit().putString("engine", v).apply()

    /** 网页模式加载的地址。 */
    var webUrl: String
        get() = sp.getString("web_url", "").orEmpty().ifBlank { DEFAULT_WEB_URL }
        set(v) = sp.edit().putString("web_url", v).apply()

    // ---------- 接口 ----------

    var apiBase: String
        get() = sp.getString("api_base", "").orEmpty().ifBlank { DEFAULT_API_BASE }
        set(v) = sp.edit().putString("api_base", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "").orEmpty()
        set(v) = sp.edit().putString("api_key", v).apply()

    var model: String
        get() = sp.getString("model", "").orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString("model", v).apply()

    /** 聊天记录（JSON 行），退出后仍能看回来。 */
    var history: String
        get() = sp.getString("history", "").orEmpty()
        set(v) = sp.edit().putString("history", v).apply()

    companion object {
        const val DEFAULT_API_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_WEB_URL = "https://chatgpt.com/"
    }
}
