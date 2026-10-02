package com.example.traesignin.hook

import android.content.Context
import com.example.traesignin.util.L
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Trae 接口源（参考 Hazuki 远端源方案）。
 *
 * 签到接口地址不打包死在 APK 里：进程启动/每次签到前从 COS 拉取 JSON 配置，
 * 成功后覆盖本地缓存；拉取失败沿用上次缓存，从未成功过则用内置兜底地址。
 * 更换接口源只需更新 COS 上的 JSON（version 递增），无需发版。
 *
 * 仅作用于签到链路三个地址：status / claim / ent_usage。
 */
internal object TraeApiSource {

    /** COS 公有读配置地址（公有读桶，无签名，仅 https） */
    private const val CONFIG_URL =
        "https://laser-api-2026-1494458480.cos.ap-guangzhou.myqcloud.com/traesignin/api_source.json"

    /** 同一进程内成功拉取后的静默期：30 分钟内不重复请求 */
    private const val REFRESH_INTERVAL_MS = 30L * 60_000L

    private const val CACHE_FILE = "trae_api_source.json"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    class Snapshot(
        val statusUrl: String,
        val claimUrl: String,
        val entUsageUrl: String,
        val version: Int,
        /** 来源：remote（本次拉取）/ cache（上次缓存）/ builtin（内置兜底） */
        val from: String,
    )

    /** 内置兜底（与 1.0.7 线上行为一致，仅在从未拉取成功时使用） */
    private val builtin = Snapshot(
        "https://api.trae.cn/trae/api/v2/ug/checkin_credits/status",
        "https://api.trae.cn/trae/api/v2/ug/checkin_credits/claim",
        "https://api.trae.cn/trae/api/v2/pay/ide_user_ent_usage",
        0,
        "builtin",
    )

    @Volatile private var mem: Snapshot? = null
    private val lastFetchOkAt = AtomicLong(0L)

    /** 当前生效源：内存 > 磁盘缓存 > 内置兜底（同步方法，签到线程调用） */
    fun current(ctx: Context): Snapshot {
        mem?.let { return it }
        readCache(ctx)?.let {
            mem = it
            return it
        }
        return builtin
    }

    /**
     * 拉取远端源并覆盖本地缓存。任何异常静默回退，调用方无需关心结果。
     * 成功后 30 分钟静默期内直接跳过，避免每次签到都请求 COS。
     */
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
            val s = Snapshot(
                requireHttps(obj.optString("status_url")),
                requireHttps(obj.optString("claim_url")),
                requireHttps(obj.optString("ent_usage_url")),
                obj.optInt("version", 0),
                "remote",
            )
            // 字段缺失视为配置无效，保留旧源
            if (s.statusUrl.isEmpty() || s.claimUrl.isEmpty() || s.entUsageUrl.isEmpty()) {
                L.w("TraeApiSource: 远端源字段缺失，忽略")
                return
            }
            // 版本回退保护：远端 version 低于当前生效版本时不覆盖
            val old = current(ctx)
            if (s.version < old.version) {
                L.w("TraeApiSource: 远端源版本(${s.version})低于当前(${old.version})，忽略")
                return
            }
            mem = s
            lastFetchOkAt.set(System.currentTimeMillis())
            try { File(ctx.filesDir, CACHE_FILE).writeText(obj.toString()) } catch (_: Throwable) { }
            L.i("TraeApiSource: 接口源已更新 v${s.version}（原 ${old.from} v${old.version}）")
        } catch (t: Throwable) {
            L.w("TraeApiSource: 拉取失败，沿用 ${current(ctx).from} 源: ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCache(ctx: Context): Snapshot? = try {
        val f = File(ctx.filesDir, CACHE_FILE)
        if (f.isFile) {
            val obj = JSONObject(f.readText())
            Snapshot(
                requireHttps(obj.optString("status_url")),
                requireHttps(obj.optString("claim_url")),
                requireHttps(obj.optString("ent_usage_url")),
                obj.optInt("version", 0),
                "cache",
            )
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 仅接受 https，防配置注入明文地址 */
    private fun requireHttps(url: String): String = if (url.startsWith("https://")) url.trim() else ""
}
