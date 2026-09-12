package com.dsha.gptrelay.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容接口的流式聊天客户端。
 *
 * 关键点：所有请求都经本机 Xray 的 SOCKS5 入站出去，
 * 所以国内直连不到的接口也能用 —— 这正是"内置梯子"的用处。
 */
class ChatClient(proxyPort: Int) {

    data class Msg(val role: String, val content: String)

    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))
        .connectTimeout(25, TimeUnit.SECONDS)
        // 流式响应不能设短的 read timeout，否则长回答会被掐断
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 发起一次流式补全。返回 Call 以便取消。 */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Msg>,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): Call {
        val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", arr)

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                onError("网络错误：${e.message}")
            }

            override fun onResponse(c: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val t = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        onError("HTTP ${resp.code}${if (t.isBlank()) "" else "：" + t.take(300)}")
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        onError("响应体为空")
                        return
                    }
                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val piece = try {
                                JSONObject(data)
                                    .optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("delta")
                                    ?.optString("content")
                            } catch (_: Throwable) {
                                null
                            }
                            if (!piece.isNullOrEmpty()) onDelta(piece)
                        }
                        onDone()
                    } catch (t: Throwable) {
                        onError("读取响应流失败：${t.message}")
                    }
                }
            }
        })
        return call
    }
}
