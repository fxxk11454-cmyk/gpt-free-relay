package com.dsha.gptrelay.core

/**
 * 一个订阅节点。
 *
 * [params] 保留解析出来的原始字段（query 参数或 vmess JSON 字段），
 * 生成 sing-box outbound 时按协议取用，避免在上层写死各家机场的差异。
 */
data class Node(
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val params: Map<String, String> = emptyMap(),
) {
    val display: String
        get() = if (name.isBlank()) "$server:$port" else name

    /** 用于列表去重/恢复选择：同协议同服务器同端口视为同一节点。 */
    val key: String
        get() = "$protocol|$server|$port"
}
