package com.dsha.gptrelay.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [Node] 翻译成 Xray-core 的配置。
 *
 * 为什么用 Xray 而不是 sing-box：
 * sing-box 的网络管理器在启动时会为网卡监控建立 **netlink 多播订阅**，
 * 而这需要 CAP_NET_ADMIN —— Android 普通应用没有，于是启动即
 * `FATAL start service: subscribe route updates: permission denied`。
 * Xray 默认不监控网卡，作为子进程在 Android 上运行是成熟路径（v2rayNG 就是这么做的）。
 *
 * 同时开两个入站：
 *  - SOCKS5（2080）：给 OkHttp 用
 *  - HTTP  （2081）：给 WebView 的 ProxyController 用
 */
object XrayConfig {

    const val SOCKS_PORT = 2080
    const val HTTP_PORT = 2081

    /** Xray 不支持的协议，界面上需要明确告知而不是静默失败。 */
    val UNSUPPORTED = setOf("hysteria2", "tuic", "hysteria")

    fun build(node: Node, logLevel: String = "warning"): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", logLevel))

        val inbounds = JSONArray()
        inbounds.put(
            JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", SOCKS_PORT)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true)),
        )
        inbounds.put(
            JSONObject()
                .put("tag", "http-in")
                .put("listen", "127.0.0.1")
                .put("port", HTTP_PORT)
                .put("protocol", "http")
                .put("settings", JSONObject()),
        )
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(buildOutbound(node))
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        root.put("outbounds", outbounds)

        root.put(
            "routing",
            JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()),
        )
        return root.toString(2)
    }

    // ---------- outbound ----------

    private fun buildOutbound(node: Node): JSONObject {
        val p = node.params

        val o = JSONObject().put("tag", "proxy").put("protocol", node.protocol)
        when (node.protocol) {
            "vmess" -> {
                val user = JSONObject()
                    .put("id", p["id"].orEmpty())
                    .put("alterId", p["aid"].orEmpty().toIntOrNull() ?: 0)
                    .put("security", p["scy"].orEmpty().ifBlank { "auto" })
                o.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject()
                                .put("address", node.server)
                                .put("port", node.port)
                                .put("users", JSONArray().put(user)),
                        ),
                    ),
                )
            }
            "vless" -> {
                val user = JSONObject().put("id", p["uuid"].orEmpty()).put("encryption", "none")
                val flow = p["flow"].orEmpty()
                if (flow.isNotBlank()) user.put("flow", flow)
                o.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject()
                                .put("address", node.server)
                                .put("port", node.port)
                                .put("users", JSONArray().put(user)),
                        ),
                    ),
                )
            }
            "trojan" -> o.put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", node.server)
                            .put("port", node.port)
                            .put("password", p["password"].orEmpty()),
                    ),
                ),
            )
            "shadowsocks" -> o.put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", node.server)
                            .put("port", node.port)
                            .put("method", p["method"].orEmpty())
                            .put("password", p["password"].orEmpty()),
                    ),
                ),
            )
            else -> {
                // Xray 不支持的协议：退化成 freedom，界面上会提示换节点
                return JSONObject().put("tag", "proxy").put("protocol", "freedom")
            }
        }

        o.put("streamSettings", streamSettings(node))
        return o
    }

    private fun streamSettings(node: Node): JSONObject {
        val p = node.params
        val s = JSONObject()

        val net = p["net"].orEmpty().ifBlank { p["type"].orEmpty() }.ifBlank { "tcp" }.lowercase()
        val network = when (net) {
            "ws", "websocket" -> "ws"
            "grpc" -> "grpc"
            "http", "h2" -> "h2"
            else -> "tcp"
        }
        s.put("network", network)

        val realityPk = p["pbk"].orEmpty()
        val security = when {
            p["security"].orEmpty() == "reality" || realityPk.isNotBlank() -> "reality"
            p["security"].orEmpty() == "tls" ||
                p["tls"].orEmpty().equals("tls", true) ||
                node.protocol == "trojan" -> "tls"
            else -> "none"
        }
        s.put("security", security)

        val sni = p["sni"].orEmpty()
            .ifBlank { p["host"].orEmpty() }
            .ifBlank { node.server }
        val fp = p["fp"].orEmpty().ifBlank { "chrome" }
        val insecure = p["insecure"].orEmpty() in listOf("1", "true")
        val alpn = p["alpn"].orEmpty()

        when (security) {
            "tls" -> {
                val tls = JSONObject().put("serverName", sni).put("fingerprint", fp)
                if (insecure) tls.put("allowInsecure", true)
                if (alpn.isNotBlank()) {
                    tls.put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                }
                s.put("tlsSettings", tls)
            }
            "reality" -> {
                val r = JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", fp)
                    .put("publicKey", realityPk)
                    .put("shortId", p["sid"].orEmpty())
                    .put("spiderX", "/")
                s.put("realitySettings", r)
            }
        }

        when (network) {
            "ws" -> {
                val ws = JSONObject()
                val path = p["path"].orEmpty()
                ws.put("path", path.ifBlank { "/" })
                val host = p["host"].orEmpty()
                if (host.isNotBlank()) ws.put("headers", JSONObject().put("Host", host))
                s.put("wsSettings", ws)
            }
            "grpc" -> {
                val sn = p["serviceName"].orEmpty().ifBlank { p["path"].orEmpty() }
                s.put("grpcSettings", JSONObject().put("serviceName", sn))
            }
            "h2" -> {
                val host = p["host"].orEmpty()
                val h = JSONObject()
                if (host.isNotBlank()) {
                    h.put("host", JSONArray(host.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                }
                val path = p["path"].orEmpty()
                if (path.isNotBlank()) h.put("path", path)
                s.put("httpSettings", h)
            }
        }

        return s
    }
}
