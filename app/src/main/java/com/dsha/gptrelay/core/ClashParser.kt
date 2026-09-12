package com.dsha.gptrelay.core

/**
 * 解析 Clash / Clash Meta 订阅（YAML 里的 proxies 段）。
 *
 * 只实现我们真正需要的 YAML 子集，不引入额外依赖：
 *  - 区块风格：`- name: x` + 缩进键值 + 一层嵌套映射（ws-opts / reality-opts / headers）
 *  - **流式风格**：`- {name: x, type: vmess, server: ..., port: 443}`
 *  - 值里内联的流式映射：`ws-opts: {path: /p, headers: {Host: h}}`
 *
 * 机场订阅这两种写法都很常见，少支持一种就会"解析出 0 个节点"。
 */
object ClashParser {

    fun looksLikeClash(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("\nproxies:") || lower.startsWith("proxies:") ||
            lower.contains("\nproxy-groups:") || lower.contains("\nproxy-providers:")
    }

    fun parse(text: String): List<Node> {
        val out = ArrayList<Node>()
        var inProxies = false
        var current: LinkedHashMap<String, String>? = null
        val cursor = Cursor()

        fun flush() {
            val m = current ?: return
            current = null
            if (m.isNotEmpty()) toNode(m)?.let { out.add(it) }
        }

        for (raw in text.split('\n')) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            var indent = line.length - line.trimStart().length
            var content = line.trim()
            if (content.startsWith("#")) continue

            // 顶层键：决定是否进入 proxies 段
            if (indent == 0) {
                flush()
                val head = content.substringBefore(':').trim().lowercase()
                inProxies = head == "proxies"
                cursor.reset()
                continue
            }
            if (!inProxies) continue

            // proxies 列表的新条目
            if (content.startsWith("- ") || content == "-") {
                flush()
                cursor.reset()
                content = content.removePrefix("-").trim()
                indent += 2
                current = LinkedHashMap()
                if (content.isEmpty()) continue

                // 流式条目：- {name: x, type: vmess, ...}
                if (content.startsWith("{")) {
                    current!!.putAll(inlineMap(content))
                    continue
                }
            }

            val cur = current ?: continue
            val colon = content.indexOf(':')
            if (colon <= 0) continue
            val key = content.substring(0, colon).trim()
            val value = content.substring(colon + 1).trim()

            if (value.isEmpty()) {
                cursor.push(indent, key)
            } else if (value.startsWith("{")) {
                // 值本身是流式映射，展平后挂到 key 前缀下
                val inner = inlineMap(value)
                for ((ik, iv) in inner) cur["$key.$ik"] = iv
            } else {
                val prefix = cursor.prefixFor(indent)
                val full = if (prefix.isEmpty()) key else "$prefix.$key"
                cur[full] = value.trim('\'', '"')
            }
        }
        flush()
        return out
    }

    /**
     * 解析内联映射 `{k: v, k2: v2}`，支持引号与一层嵌套。
     * 嵌套值会以 `父键.子键` 的形式展平。
     */
    private fun inlineMap(raw: String): Map<String, String> {
        var body = raw.trim()
        if (body.startsWith("{")) body = body.substring(1)
        if (body.endsWith("}")) body = body.dropLast(1)

        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var depth = 0
        for (ch in body) {
            when {
                quote != null -> {
                    cur.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    cur.append(ch)
                }
                ch == '{' -> {
                    depth++
                    cur.append(ch)
                }
                ch == '}' -> {
                    depth--
                    cur.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    parts.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) parts.add(cur.toString())

        val out = LinkedHashMap<String, String>()
        for (p in parts) {
            val i = p.indexOf(':')
            if (i <= 0) continue
            val k = p.substring(0, i).trim().trim('\'', '"')
            val v = p.substring(i + 1).trim()
            if (k.isEmpty()) continue
            if (v.startsWith("{")) {
                for ((ik, iv) in inlineMap(v)) out["$k.$ik"] = iv
            } else {
                out[k] = v.trim('\'', '"')
            }
        }
        return out
    }

    /** 维护嵌套层级：把 "ws-opts" / "headers" 之类的父键还原成点号前缀。 */
    private class Cursor {
        private val path = ArrayList<Pair<Int, String>>()

        fun reset() = path.clear()

        fun prefixFor(indent: Int): String {
            while (path.isNotEmpty() && path.last().first >= indent) path.removeAt(path.size - 1)
            return path.joinToString(".") { it.second }
        }

        fun push(indent: Int, key: String) {
            while (path.isNotEmpty() && path.last().first >= indent) path.removeAt(path.size - 1)
            path.add(indent to key)
        }
    }

    // ---------- 翻译成 sing-box 能消费的参数 ----------

    private fun toNode(m: Map<String, String>): Node? {
        val type = m["type"]?.lowercase()?.trim() ?: return null
        val server = m["server"]?.trim() ?: return null
        val port = m["port"]?.trim()?.toIntOrNull() ?: return null
        if (server.isEmpty() || port !in 1..65535) return null
        val name = m["name"].orEmpty()

        val p = LinkedHashMap<String, String>()
        val sni = m["servername"].orEmpty()
            .ifBlank { m["sni"].orEmpty() }
            .ifBlank { m["ws-opts.headers.Host"].orEmpty() }
            .ifBlank { m["ws-opts.headers.host"].orEmpty() }
        val wsPath = m["ws-opts.path"].orEmpty().ifBlank { m["ws-path"].orEmpty() }
        val wsHost = m["ws-opts.headers.Host"].orEmpty()
            .ifBlank { m["ws-opts.headers.host"].orEmpty() }
            .ifBlank { m["ws-headers.Host"].orEmpty() }
        val insecure = m["skip-cert-verify"].orEmpty()
        val net = m["network"].orEmpty().ifBlank { "tcp" }

        when (type) {
            "vmess" -> {
                p["id"] = m["uuid"].orEmpty()
                p["aid"] = m["alterId"].orEmpty().ifBlank { "0" }
                p["scy"] = m["cipher"].orEmpty().ifBlank { "auto" }
                p["net"] = net
                if (m["tls"].equals("true", true)) p["tls"] = "tls"
                p["sni"] = sni
                p["host"] = wsHost
                p["path"] = wsPath
                p["serviceName"] = m["grpc-opts.grpc-service-name"].orEmpty()
                p["insecure"] = insecure
            }
            "vless" -> {
                p["uuid"] = m["uuid"].orEmpty()
                p["flow"] = m["flow"].orEmpty()
                p["net"] = net
                p["security"] = if (m["reality-opts.public-key"].orEmpty().isNotEmpty()) "reality"
                else if (m["tls"].equals("true", true)) "tls" else ""
                p["sni"] = sni
                p["host"] = wsHost
                p["path"] = wsPath
                p["serviceName"] = m["grpc-opts.grpc-service-name"].orEmpty()
                p["pbk"] = m["reality-opts.public-key"].orEmpty()
                p["sid"] = m["reality-opts.short-id"].orEmpty()
                p["fp"] = m["client-fingerprint"].orEmpty().ifBlank { "chrome" }
                p["insecure"] = insecure
            }
            "trojan" -> {
                p["password"] = m["password"].orEmpty()
                p["net"] = net
                p["sni"] = sni
                p["host"] = wsHost
                p["path"] = wsPath
                p["serviceName"] = m["grpc-opts.grpc-service-name"].orEmpty()
                p["insecure"] = insecure
                p["security"] = "tls"
            }
            "ss", "shadowsocks" -> {
                p["method"] = m["cipher"].orEmpty()
                p["password"] = m["password"].orEmpty()
            }
            "hysteria2", "hy2" -> {
                p["password"] = m["password"].orEmpty().ifBlank { m["auth"].orEmpty() }
                p["sni"] = sni
                p["insecure"] = insecure
                p["alpn"] = m["alpn"].orEmpty()
            }
            else -> return null
        }

        val protocol = when (type) {
            "ss" -> "shadowsocks"
            "hy2" -> "hysteria2"
            else -> type
        }
        return Node(name = name, protocol = protocol, server = server, port = port, params = p)
    }
}
