package com.example.traesignin.cloud

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 云端 API 客户端（同步阻塞式，调用方须在子线程执行）。
 *
 * 统一响应包：成功 {code:0, data}；失败 {code:非0, message}（HTTP 401 = token 失效）。
 * 业务接口自动携带 Bearer access_token；401 时刷新轮换一次并重放，仍失败抛 AuthExpired。
 */
object CloudApi {

    /** 云端地址由 COS 远程配置下发（RemoteBases），服务器迁移无需发版 */
    val AUTH_BASE: String get() = RemoteBases.authBase()
    val WORKER_BASE: String get() = RemoteBases.workerBase()
    const val APP_ID = "traesignin"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 业务错误（服务端返回非 0 code） */
    class ApiException(val code: Int, message: String) : Exception(message)

    /** 登录态彻底失效（刷新也失败），需重新登录 */
    class AuthExpired(message: String) : Exception(message)

    // ================= 账号中心 =================

    /** 注册（成功返回 user_id，失败抛 ApiException） */
    fun register(phone: String, password: String): Int {
        val body = JSONObject().put("phone", phone).put("password", password).put("app_id", APP_ID)
        val resp = postJson(null, "$AUTH_BASE/v1/register", body)
        return resp.optJSONObject("data")?.optInt("user_id") ?: 0
    }

    /** 登录（成功后 token 落库 TokenManager） */
    fun login(context: Context, phone: String, password: String) {
        val body = JSONObject()
            .put("phone", phone)
            .put("password", password)
            .put("app_id", APP_ID)
            .put("device_name", deviceName())
        val resp = postJson(null, "$AUTH_BASE/v1/login", body)
        val data = resp.optJSONObject("data") ?: throw ApiException(-1, "登录响应缺少 data")
        TokenManager.saveLogin(context, data)
    }

    /** 登出（吊销服务端 refresh token 并清空本地） */
    fun logout(context: Context) {
        try {
            val rt = TokenManager.refreshToken(context)
            if (!rt.isNullOrBlank()) {
                postJson(null, "$AUTH_BASE/v1/logout", JSONObject().put("refresh_token", rt))
            }
        } catch (_: Throwable) {
            // 本地登出不受网络影响
        } finally {
            TokenManager.clear(context)
        }
    }

    /** 刷新轮换一次（单飞：synchronized 防并发多次刷新）。成功返回 true */
    fun refreshOnce(context: Context): Boolean {
        val rt = TokenManager.refreshToken(context) ?: return false
        return synchronized(this) {
            // 双检：并发等待期间可能已被其他线程刷新
            if (TokenManager.refreshToken(context) != rt) true else try {
                val resp = postJson(null, "$AUTH_BASE/v1/refresh", JSONObject().put("refresh_token", rt))
                val data = resp.optJSONObject("data") ?: return false
                TokenManager.updateTokens(context, data)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** 用户信息（含多应用登录记录） */
    fun me(context: Context): JSONObject {
        val resp = authed(context) { req -> req.url("$AUTH_BASE/v1/me").get() }
        return resp.optJSONObject("data") ?: JSONObject()
    }

    // ================= 代签服务（trae-worker，一个友爱账号可托管多个 Trae 账号）=================

    /** Trae 账号列表（含调度配置、今日执行状态；永不回传明文 token） */
    fun accounts(context: Context): JSONArray {
        val resp = authed(context) { req -> req.url("$WORKER_BASE/v1/accounts").get() }
        return resp.optJSONArray("data") ?: JSONArray()
    }

    /** 创建 Trae 账号（alias 可空；token/device_id 可选同时上传），返回创建后的账号对象 */
    fun createAccount(context: Context, alias: String?, token: String?, deviceId: String?): JSONObject {
        val body = JSONObject()
        if (!alias.isNullOrBlank()) body.put("alias", alias.trim().take(30))
        if (!token.isNullOrBlank()) body.put("token", token.trim())
        if (!deviceId.isNullOrBlank()) body.put("device_id", deviceId.trim())
        val resp = authed(context) { req ->
            req.url("$WORKER_BASE/v1/accounts").post(body.toString().toRequestBody(JSON))
        }
        return resp.optJSONObject("data") ?: JSONObject()
    }

    /** 账号改名 */
    fun renameAccount(context: Context, accountId: Long, alias: String): JSONObject {
        val body = JSONObject().put("alias", alias.trim().take(30))
        val resp = authed(context) { req ->
            req.url("$WORKER_BASE/v1/accounts/$accountId").put(body.toString().toRequestBody(JSON))
        }
        return resp.optJSONObject("data") ?: JSONObject()
    }

    /** 删除 Trae 账号（其调度/执行/流水一并删除） */
    fun deleteAccount(context: Context, accountId: Long) {
        authed(context) { req -> req.url("$WORKER_BASE/v1/accounts/$accountId").delete() }
    }

    /** 上传/更新指定 Trae 账号凭证（AES-256-GCM 加密落库；token 过期会被服务端拒绝） */
    fun putCredentials(context: Context, accountId: Long, token: String, deviceId: String): JSONObject {
        val body = JSONObject()
            .put("token", token)
            .put("device_id", deviceId)
            .put("device_brand", Build.BRAND)
            .put("device_type", Build.MODEL)
            .put("os_version", "Android ${Build.VERSION.RELEASE}")
        val resp = authed(context) { req ->
            req.url("$WORKER_BASE/v1/accounts/$accountId/credentials").put(body.toString().toRequestBody(JSON))
        }
        return resp.optJSONObject("data") ?: JSONObject()
    }

    /** 指定账号的调度配置（含今日执行时刻/状态） */
    fun getSchedule(context: Context, accountId: Long): JSONObject? {
        val resp = authed(context) { req -> req.url("$WORKER_BASE/v1/accounts/$accountId/schedule").get() }
        return resp.optJSONObject("data")
    }

    /** 配置指定账号的云端代签计划（mode: gaussian=集中早起 / uniform=均匀散开） */
    fun putSchedule(
        context: Context,
        accountId: Long,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        jitterMin: Int,
        mode: String,
    ): JSONObject {
        val body = JSONObject()
            .put("enabled", enabled)
            .put("hour", hour.coerceIn(0, 23))
            .put("minute", minute.coerceIn(0, 59))
            .put("jitter_min", jitterMin.coerceIn(0, 120))
            .put("mode", if (mode == "uniform") "uniform" else "gaussian")
        val resp = authed(context) { req ->
            req.url("$WORKER_BASE/v1/accounts/$accountId/schedule").put(body.toString().toRequestBody(JSON))
        }
        return resp.optJSONObject("data") ?: JSONObject()
    }

    /** 立即云端代签指定账号（同步返回结果） */
    fun runNow(context: Context, accountId: Long): JSONObject {
        val resp = authed(context) { req ->
            req.url("$WORKER_BASE/v1/accounts/$accountId/run-now").post("{}".toRequestBody(JSON))
        }
        return resp.optJSONObject("data") ?: JSONObject()
    }

    /** 近 N 天签到流水（全部账号） */
    fun logs(context: Context, days: Int): JSONArray {
        val resp = authed(context) { req -> req.url("$WORKER_BASE/v1/logs?days=$days").get() }
        return resp.optJSONArray("data") ?: JSONArray()
    }

    /** 手机端手动签到成功后补录（服务端据此修正当日 run 状态，避免两端抢签） */
    fun postLog(context: Context, accountId: Long, day: String, result: String, delta: Int?, detail: String) {
        val body = JSONObject()
            .put("account_id", accountId)
            .put("day", day)
            .put("result", result)
            .put("delta", delta ?: JSONObject.NULL)
            .put("detail", detail)
        authed(context) { req ->
            req.url("$WORKER_BASE/v1/logs").post(body.toString().toRequestBody(JSON))
        }
    }

    // ================= 内部 =================

    /** 带 Bearer 的请求执行：401 → 刷新轮换一次 → 重放一次；仍失败抛 AuthExpired */
    private fun authed(context: Context, build: (Request.Builder) -> Request.Builder): JSONObject {
        val token = TokenManager.accessToken(context) ?: throw AuthExpired("未登录")
        return execJson(context, build(Request.Builder()).header("Authorization", "Bearer $token").build())
    }

    private fun postJson(context: Context?, url: String, body: JSONObject): JSONObject =
        execJson(context, Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build())

    private fun execJson(context: Context?, request: Request): JSONObject {
        var call = client.newCall(request)
        var resp = call.execute()
        var text = resp.body?.string().orEmpty()

        // 401：刷新一次并重放一次（仅业务接口）
        if (resp.code == 401 && context != null) {
            resp.close()
            if (!refreshOnce(context)) {
                TokenManager.clear(context)
                throw AuthExpired("登录已过期，请重新登录")
            }
            call = client.newCall(
                request.newBuilder()
                    .header("Authorization", "Bearer ${TokenManager.accessToken(context)}")
                    .build()
            )
            resp = call.execute()
            text = resp.body?.string().orEmpty()
            if (resp.code == 401) {
                resp.close()
                TokenManager.clear(context)
                throw AuthExpired("登录已过期，请重新登录")
            }
        }

        try {
            val obj = try { JSONObject(text) } catch (_: Throwable) { JSONObject() }
            if (obj.length() == 0) throw ApiException(-1, "服务器响应异常（HTTP ${resp.code}）")
            val code = obj.optInt("code", -1)
            if (code != 0) throw ApiException(code, obj.optString("message", "请求失败"))
            return obj
        } finally {
            resp.close()
        }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".take(60)
}
