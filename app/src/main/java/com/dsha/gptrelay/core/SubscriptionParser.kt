package com.dsha.gptrelay.core

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 订阅解析总入口。识别三种常见形态：
 *  1. Clash / Clash Meta 的 YAML（含 `proxies:`）
 *  2. 整段 base64（解码后是 URI 列表或 YAML）
 *  3. 明文 URI 列表（vmess/vless/trojan/ss/hysteria2）
 */
object SubscriptionParser {

    /** 解析结果 + 诊断信息，方便界面把"为什么 0 个节点"讲清楚。 */
    data class Result(val nodes: List<Node>, val kind: String, val detail: String)

    fun parse(body: String): List<Node> = parseDetailed(body).nodes

    fun parseDetailed(body: String): Result {
        val raw = body.trim()
        if (raw.isEmpty()) return Result(emptyList(), "空内容", "订阅返回内容为空")

        // 1) 直接就是 Clash YAML
        if (ClashParser.looksLikeClash(raw)) {
            val nodes = ClashParser.parse(raw)
            return Result(nodes, "Clash YAML", "识别为 Clash 配置，proxies 段解析出 ${nodes.size} 个")
        }

        // 2) 明文 URI 列表
        if (raw.contains("://")) {
            val nodes = parseUriList(raw)
            return Result(nodes, "URI 列表", "明文链接列表，解析出 ${nodes.size} 个")
        }

        // 3) base64
        val decoded = tryBase64(raw)
        if (decoded != null) {
            if (ClashParser.looksLikeClash(decoded)) {
                val nodes = ClashParser.parse(decoded)
                return Result(nodes, "base64 → Clash", "解码后是 Clash 配置，解析出 ${nodes.size} 个")
            }
            if (decoded.contains("://")) {
                val nodes = parseUriList(decoded)
                return Result(nodes, "base64 → URI", "解码后是链接列表，解析出 ${nodes.size} 个")
            }
            return Result(
                emptyList(), "base64（无法识别）",
                "base64 解出来了但内容既不是 Clash 也不是链接，前 80 字：${decoded.take(80)}"
            )
        }

        return Result(
            emptyList(), "未知格式",
            "既不是 Clash YAML，也不是 base64 或链接列表。前 80 字：${raw.take(80)}"
        )
    }

    private fun parseUriList(text: String): List<Node> {
        val out = ArrayList<Node>()
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            parseUri(t)?.let { out.add(it) }
        }
        return out
    }

    private fun tryBase64(raw: String): String? {
        val compact = raw.replace(Regex("\\s+"), "")
        if (compact.length < 8) return null
        for (flags in intArrayOf(
            Base64.DEFAULT or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
        )) {
            try {
                val decoded = String(Base64.decode(compact, flags), Charsets.UTF_8)
                if (decoded.contains("://") || ClashParser.looksLikeClash(decoded)) return decoded
            } catch (_: Throwable) {
                // 换下一种编码再试
            }
        }
        return null
    }

    private fun parseUri(uri: String): Node? = try {
        when {
            uri.startsWith("vmess://", true) -> parseVmess(uri)
            uri.startsWith("vless://", true) -> parseVless(uri)
            uri.startsWith("trojan://", true) -> parseTrojan(uri)
            uri.startsWith("ss://", true) -> parseShadowsocks(uri)
            uri.startsWith("hysteria2://", true) || uri.startsWith("hy2://", true) -> parseHysteria2(uri)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    // ---------- 工具 ----------

    private fun splitFragment(uri: String): Pair<String, String> {
        val i = uri.indexOf('#')
        if (i < 0) return uri to ""
        return uri.substring(0, i) to decode(uri.substring(i + 1))
    }

    private fun queryOf(uri: String): Map<String, String> {
        val q = uri.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            if (i <= 0) map[decode(part)] = "" else map[decode(part.substring(0, i))] = decode(part.substring(i + 1))
        }
        return map
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    private fun authority(rest: String): Triple<String, String, Int>? {
        val at = rest.lastIndexOf('@')
        val userinfo = if (at >= 0) rest.substring(0, at) else ""
        val hostPort = if (at >= 0) rest.substring(at + 1) else rest
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        val host = hostPort.substring(0, colon).trim('[', ']')
        val port = hostPort.substring(colon + 1).substringBefore('/').toIntOrNull() ?: return null
        return Triple(userinfo, host, port)
    }

    private fun decodeB64(s: String): String {
        val v = s.trim()
        return try {
            String(Base64.decode(v, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: Throwable) {
            try {
                String(Base64.decode(v, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }
    }

    // ---------- 各协议 ----------

    private fun parseVmess(uri: String): Node? {
        val payload = uri.substring("vmess://".length).trim()
        val jsonText = decodeB64(payload)
        if (jsonText.isBlank()) return null
        val j = JSONObject(jsonText)
        val host = j.optString("add")
        val port = j.optString("port").toIntOrNull() ?: return null
        if (host.isBlank()) return null
        val params = LinkedHashMap<String, String>()
        for (k in j.keys()) params[k] = j.optString(k, "")
        return Node(j.optString("ps", "").ifBlank { "$host:$port" }, "vmess", host, port, params)
    }

    private fun parseVless(uri: String): Node? {
        val (body, name) = splitFragment(uri)
        val rest = body.substring("vless://".length)
        val (userinfo, host, port) = authority(rest) ?: return null
        val params = LinkedHashMap(queryOf(rest))
        params["uuid"] = userinfo
        return Node(name.ifBlank { "$host:$port" }, "vless", host, port, params)
    }

    private fun parseTrojan(uri: String): Node? {
        val (body, name) = splitFragment(uri)
        val rest = body.substring("trojan://".length)
        val (userinfo, host, port) = authority(rest) ?: return null
        val params = LinkedHashMap(queryOf(rest))
        params["password"] = userinfo
        return Node(name.ifBlank { "$host:$port" }, "trojan", host, port, params)
    }

    private fun parseShadowsocks(uri: String): Node? {
        val (body, name) = splitFragment(uri)
        val rest = body.substring("ss://".length)
        var method = ""
        var password = ""
        var host: String
        var port: Int
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val cred = decodeB64(rest.substring(0, at))
            val c = cred.indexOf(':')
            if (c > 0) {
                method = cred.substring(0, c)
                password = cred.substring(c + 1)
            }
            val hostPort = rest.substring(at + 1)
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return null
            host = hostPort.substring(0, colon).trim('[', ']')
            port = hostPort.substring(colon + 1).substringBefore('/').toIntOrNull() ?: return null
        } else {
            val decoded = decodeB64(rest)
            val a = decoded.lastIndexOf('@')
            if (a < 0) return null
            val cred = decoded.substring(0, a)
            val c = cred.indexOf(':')
            if (c > 0) {
                method = cred.substring(0, c)
                password = cred.substring(c + 1)
            }
            val hostPort = decoded.substring(a + 1)
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return null
            host = hostPort.substring(0, colon).trim('[', ']')
            port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        }
        if (method.isBlank() || host.isBlank()) return null
        val params = LinkedHashMap(queryOf(rest))
        params["method"] = method
        params["password"] = password
        return Node(name.ifBlank { "$host:$port" }, "shadowsocks", host, port, params)
    }

    private fun parseHysteria2(uri: String): Node? {
        val (body, name) = splitFragment(uri)
        val rest = body.substringAfter("://")
        val (userinfo, host, port) = authority(rest) ?: return null
        val params = LinkedHashMap(queryOf(rest))
        params["password"] = userinfo
        return Node(name.ifBlank { "$host:$port" }, "hysteria2", host, port, params)
    }
}
