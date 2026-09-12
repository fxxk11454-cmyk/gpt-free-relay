package com.dsha.gptrelay.core

import android.content.Context
import java.io.File

/**
 * 管理内置 Xray 核心进程。
 *
 * 二进制放在 jniLibs 里伪装成 `libxray.so`：Android 10+ 禁止从应用数据目录
 * 执行文件，而系统会把 native 库解压到可执行的 `nativeLibraryDir`，这是唯一
 * 稳妥的落点。配置文件只是数据，放 filesDir 即可。
 */
class CoreManager(private val context: Context) {

    private var process: Process? = null

    val workDir: File get() = File(context.filesDir, "core").apply { mkdirs() }
    val configFile: File get() = File(workDir, "config.json")
    val logFile: File get() = File(workDir, "core.log")

    /** 可执行文件路径：优先 nativeLibraryDir 里的 libxray.so。 */
    fun binaryPath(): String? {
        val candidate = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        return if (candidate.exists()) candidate.absolutePath else null
    }

    fun writeConfig(json: String) {
        workDir.mkdirs()
        configFile.writeText(json, Charsets.UTF_8)
    }

    fun isRunning(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    /** 启动核心。返回 null 表示成功，否则是错误描述。 */
    fun start(): String? {
        if (isRunning()) return null
        val bin = binaryPath() ?: return "未找到内置核心（nativeLibraryDir 里没有 libxray.so）"
        if (!configFile.exists()) return "配置文件不存在"

        return try {
            val pb = ProcessBuilder(bin, "run", "-c", configFile.absolutePath)
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            // 覆盖写而不是追加：否则看到的是上一轮的残留输出
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile))
            val p = pb.start()
            process = p

            // 给核心一点启动时间，顺便捕捉最常见的"配置不合法"退出
            Thread.sleep(900)
            try {
                val code = p.exitValue()
                return "核心启动即退出（code=$code）\n" + errorSummary()
            } catch (_: IllegalThreadStateException) {
                // 还在跑，正常
            }
            null
        } catch (t: Throwable) {
            "启动核心失败：${t.message}"
        }
    }

    /**
     * 从日志里挑出真正有用的错误行。
     *
     * 之前只取最后 6 行，结果全是 panic 的栈帧，真正的错误信息被截掉了 ——
     * 这里优先取 panim/FATAL/ERROR 行，并过滤掉 linker、网卡之类的噪声。
     */
    fun errorSummary(max: Int = 6): String {
        val lines = logFile.takeIf { it.exists() }?.readLines().orEmpty()
        if (lines.isEmpty()) return "（核心没有输出任何日志）"
        val cleaned = lines.map { stripAnsi(it) }
        val hits = cleaned.filter { l ->
            val s = l.lowercase()
            (s.contains("panic") || s.contains("fatal") || s.contains("error") ||
                s.contains("invalid") || s.contains("unknown") || s.contains("required") ||
                s.contains("conflict")) &&
                !s.contains("missing default interface") &&
                !s.contains("package manager") &&
                !s.contains("linker")
        }
        return (if (hits.isNotEmpty()) hits else cleaned).takeLast(max).joinToString("\n")
    }

    private fun stripAnsi(s: String): String = s.replace(Regex("\u001B\\[[;\\d]*m"), "")

    fun stop() {
        val p = process ?: return
        process = null
        try {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Throwable) {
            p.destroyForcibly()
        }
    }

    fun logTail(lines: Int = 40): String =
        logFile.takeIf { it.exists() }?.readLines()?.takeLast(lines)?.joinToString("\n").orEmpty()

    /** 把当前配置原文交出来，方便排查"核心为什么起不来"。 */
    fun configText(): String =
        configFile.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
}
