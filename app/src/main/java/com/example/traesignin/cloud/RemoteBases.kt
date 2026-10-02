package com.example.traesignin.cloud

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * 云端服务地址远程配置（与 hook 侧 TraeApiSource 同源同方案）。
 *
 * auth-center / trae-worker 的地址不打包死在 APK：首次访问时从 COS 拉取 JSON
 * 并落盘缓存；拉取失败沿用缓存；均不可用则返回空串（CloudApi 侧给出明确提示）。
 * 服务器迁移只需更新 COS 上的 JSON（version 递增），无需发版。
 */
object RemoteBases {

    /** COS 公有读配置地址（与 hook 侧 TraeApiSource 同一份 JSON） */
    private const val CONFIG_URL =
        "https://laser-api-2026-1494458480.cos.ap-guangzhou.myqcloud.com/traesignin/api_source.json"

    /** 同一进程内成功拉取后的静默期：30 分钟内不重复请求 */
    private const val REFRESH_INTERVAL_MS = 30L * 60_000L

    private const val CACHE_FILE = "cloud_bases.json"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    @Volatile private var appCtx: Context? = null
    @Volatile private var mem: Pair<String, String>? = null
    private val lastFetchOkAt = AtomicLong(0L)

    /** App.onCreate 注入上下文并后台预热 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        Thread {
            try { authBase() } catch (_: Throwable) { }
        }.start()
    }

    /** auth-center 地址（未就绪抛 ApiException，提示用户检查网络） */
    fun authBase(): String = requireReady(ensure().first)

    /** trae-worker 地址（未就绪抛 ApiException） */
    fun workerBase(): String = requireReady(ensure().second)

    private fun requireReady(base: String): String {
        if (base.isEmpty()) {
            throw CloudApi.ApiException(-2, "云端服务地址未就绪，请检查网络后重试")
        }
        return base
    }

    private fun ensure(): Pair<String, String> {
        mem?.let { return it }
        val ctx = appCtx
        if (ctx == null) return "" to ""
        readCache(ctx)?.let {
            mem = it
            return it
        }
        refresh(ctx)
        return mem ?: ("" to "")
    }

    /** 拉取远端地址并覆盖缓存；任何异常静默回退（调用方为网络线程，阻塞安全） */
    fun refresh(ctx: Context) {
        if (!CONFIG_URL.startsWith("https://")) return
        if (System.currentTimeMillis() - lastFetchOkAt.get() < REFRESH_INTERVAL_MS) return
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Cache-Control", "no-cache")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(text)
            val auth = validBase(obj.optString("auth_base"))
            val worker = validBase(obj.optString("worker_base"))
            if (auth.isEmpty() || worker.isEmpty()) return
            mem = auth to worker
            lastFetchOkAt.set(System.currentTimeMillis())
            try { File(ctx.filesDir, CACHE_FILE).writeText(obj.toString()) } catch (_: Throwable) { }
        } catch (_: Throwable) {
            // 沿用内存/缓存/空串
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCache(ctx: Context): Pair<String, String>? = try {
        val f = File(ctx.filesDir, CACHE_FILE)
        if (f.isFile) {
            val obj = JSONObject(f.readText())
            val auth = validBase(obj.optString("auth_base"))
            val worker = validBase(obj.optString("worker_base"))
            if (auth.isEmpty() || worker.isEmpty()) null else auth to worker
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 仅接受 http(s) 绝对地址 */
    private fun validBase(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url.trim().trimEnd('/') else ""
}
