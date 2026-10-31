package mignnlapin.traesignin

import android.content.Context
import mignnlapin.traesignin.hook.Creds
import mignnlapin.traesignin.hook.TraeApiSource
import mignnlapin.traesignin.util.L
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块 App 进程直签：su 读宿主 keva 凭证文件 → 解析 JWT/device-id（复用 hook 侧
 * Creds 的公开解析函数，App 进程经 su 读宿主文件为其注释预留用法）→ 直接请求
 * Trae 官方签到接口，全程不拉起 Trae。结果合并写回宿主状态文件，GUI 与 hook
 * 引擎状态保持一致；失败时由调用方回退拉起 Trae 走 hook 引擎。
 */
internal object LocalSigner {

    private const val TARGET_PKG = "com.bytedance.trae.cn"
    private const val TOKEN_BLK = "/data/data/$TARGET_PKG/files/keva/repo/default/default.blk"
    private const val DID_BLK = "/data/data/$TARGET_PKG/files/keva/repo/device_id_repo/device_id_repo.blk"
    private const val STATE_FILE_PATH = "/data/data/$TARGET_PKG/files/trae_signin_state.json"
    private const val APP_VERSION = "0.0.21"

    /**
     * 直签结果。balance：签到后查询到的余额（null=未知；-2.0=无限额度），
     * 通知与 Toast 直接显示，无需再打开通知细看。
     */
    class SignResult(
        val ok: Boolean,
        val alreadyChecked: Boolean = false,
        val message: String = "",
        val balance: Double? = null,
    )

    /** 同步直签（调用方须在后台线程）。成功发通知（含余额）；失败返回 ok=false 交回退。 */
    fun signNow(context: Context): SignResult {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        return try {
            val cred = loadCred()
            if (!cred.usable) {
                L.w("LocalSigner: 凭证不可用，交回退路径")
                return SignResult(false)
            }
            // 拉取最新接口源（失败静默回退），后续请求全走动态地址
            TraeApiSource.refresh(context)
            val api = TraeApiSource.current(context)

            // 1) 状态查询（免 device-id 校验）
            val st = post(api.statusUrl, cred)
            if (st.code == 0) {
                if (!st.data.optBoolean("enable", true)) {
                    return finishOk(context, cred, today, "签到功能未开启（服务端关闭）", 0)
                }
                if (st.data.optBoolean("checked_in", false)) {
                    return finishOk(context, cred, today, "今日已签到（App 内已签）", st.data.optInt("credits", 0), already = true)
                }
            } else {
                L.w("LocalSigner: status code=${st.code} ${st.message}")
            }

            // 2) claim 重试（0s / 10s / 30s）
            var last = st
            val delays = longArrayOf(0L, 10_000L, 30_000L)
            for (i in delays.indices) {
                if (delays[i] > 0) Thread.sleep(delays[i])
                last = post(api.claimUrl, cred)
                L.i("LocalSigner: claim#${i + 1} code=${last.code} msg=${last.message}")
                when {
                    last.code == 0 -> {
                        val st2 = post(api.statusUrl, cred)
                        return finishOk(context, cred, today, "签到成功", st2.data.optInt("credits", 0))
                    }
                    // 疑似并发已签：回查状态兜底
                    post(api.statusUrl, cred).data.optBoolean("checked_in", false) -> {
                        return finishOk(context, cred, today, "今日已签到（并发兜底）", 0, already = true)
                    }
                    // 9074 = 设备不匹配软拒，快速重试无意义
                    last.code == 9074 -> break
                }
            }
            mergeAndWrite(today = today, ok = false, result = "直签失败：code=${last.code} ${last.message}")
            L.e("LocalSigner: claim 失败 code=${last.code}")
            SignResult(false, message = "直签失败 code=${last.code}")
        } catch (t: Throwable) {
            L.e("LocalSigner: 异常: ${t.message}")
            SignResult(false, message = "异常: ${t.message}")
        }
    }

    // ---- 凭证（su 读宿主 keva 文件） ----

    private fun suCat(path: String): String? = try {
        // -mm：切到 Magisk 全局 mount namespace（HyperOS 给 app 进程做了命名空间隔离，
        // 缺 -mm 时读不到宿主 /data/data，凭证必败 → 全部走回退路径）
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat $path"))
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        if (proc.exitValue() == 0 && out.isNotEmpty()) out else null
    } catch (_: Throwable) {
        null
    }

    private fun loadCred(): Creds.Cred {
        val token = suCat(TOKEN_BLK)?.let { Creds.parseTokenFromText(it) }
        val did = suCat(DID_BLK)?.let { Creds.parseDeviceIdFromText(it) }
        return Creds.Cred(token, did, if (token != null) Creds.jwtExpAt(token) else 0L)
    }

    // ---- 网络（与 hook 引擎同规格：Cloud-IDE-JWT + x-device-id） ----

    private class Resp(val code: Int, val message: String, val data: JSONObject)

    private fun post(url: String, cred: Creds.Cred, body: String = "{\"req_source\":1}"): Resp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Cloud-IDE-JWT ${cred.token}")
                if (!cred.deviceId.isNullOrBlank()) setRequestProperty("x-device-id", cred.deviceId)
                setRequestProperty("x-device-brand", android.os.Build.BRAND)
                setRequestProperty("x-device-type", android.os.Build.MODEL)
                setRequestProperty("x-os-version", "Android ${android.os.Build.VERSION.RELEASE}")
                setRequestProperty("x-app-version", APP_VERSION)
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val respText = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
            val obj = JSONObject(respText)
            // status/claim 响应为扁平结构（checked_in / credits / enable 在顶层）
            Resp(obj.optInt("code", -1), obj.optString("message", ""), obj)
        } catch (t: Throwable) {
            L.w("LocalSigner: 请求异常 ${url.substringAfterLast('/')}: ${t.message}")
            Resp(-1, t.message ?: "network error", JSONObject())
        } finally {
            conn?.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return "{}"
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Throwable) {
            "{}"
        }
    }

    // ---- 状态合并写回 + 通知 ----

    /**
     * App 进程查询积分余额（签到成功后 / 打开模块 App 时刷新显示）：
     * ent_usage → Σ max(credits_limit - used, 0)（对齐 hook 引擎 refreshBalance 口径），
     * 只合并写回 balance/balanceAt，不触碰签到状态字段。
     * 返回余额（-2.0=无限额度；null=查询失败）。
     */
    fun refreshBalance(context: Context, cred: Creds.Cred? = null): Double? {
        return try {
            val c = cred ?: loadCred()
            if (!c.usable) {
                L.w("LocalSigner: 余额刷新跳过（凭证不可用）")
                return null
            }
            TraeApiSource.refresh(context)
            val api = TraeApiSource.current(context)
            val r = post(api.entUsageUrl, c, """{"require_usage":true,"req_source":1}""")
            val data = r.data
            if (data.length() == 0) {
                L.w("LocalSigner: 余额查询失败 code=${r.code} ${r.message}")
                return null
            }
            var unlimited = false
            var remaining = 0.0
            var any = false
            val packs = data.optJSONArray("user_entitlement_pack_list")
            for (i in 0 until (packs?.length() ?: 0)) {
                val p = packs!!.optJSONObject(i) ?: continue
                val quota = p.optJSONObject("entitlement_base_info")?.optJSONObject("quota") ?: continue
                val limit = quota.optDouble("credits_limit", 0.0)
                val used = p.optJSONObject("usage")?.optDouble("credits_amount", 0.0) ?: 0.0
                if (limit == -1.0) { unlimited = true; any = true }
                else if (limit > 0) { any = true; remaining += maxOf(limit - used, 0.0) }
            }
            if (!any) {
                // 兜底：usage_summary.total - consumed
                data.optJSONObject("usage_summary")?.let { us ->
                    remaining = maxOf(us.optDouble("total_amount", 0.0) - us.optDouble("consumed_amount", 0.0), 0.0)
                    any = true
                }
            }
            if (!any) return null
            val bal = if (unlimited) -2.0 else Math.round(remaining).toDouble()
            if (writeBalance(bal)) {
                L.i("LocalSigner: 余额已刷新 → ${if (unlimited) "无限额度" else remaining.toInt()}")
            }
            bal
        } catch (t: Throwable) {
            L.w("LocalSigner: 余额刷新异常: ${t.message}")
            null
        }
    }

    /** 只更新宿主状态文件的 balance/balanceAt（不覆盖签到/失败状态等其余字段） */
    private fun writeBalance(balance: Double): Boolean = try {
        val state = readHostState() ?: return false
        state.put("balance", balance).put("balanceAt", System.currentTimeMillis())
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat > $STATE_FILE_PATH"))
        proc.outputStream.use { it.write(state.toString().toByteArray(Charsets.UTF_8)) }
        proc.waitFor()
        proc.exitValue() == 0
    } catch (_: Throwable) {
        false
    }

    /**
     * 成功收尾：状态合并写回 → 签到后立即查询一次余额 → 发通知（直接带余额，
     * 无需点开通知细看）。返回 SignResult 给调用方（Toast 用）。
     */
    private fun finishOk(
        context: Context,
        cred: Creds.Cred,
        today: String,
        result: String,
        credits: Int,
        already: Boolean = false,
    ): SignResult {
        mergeAndWrite(today = today, ok = true, result = result, credits = credits)
        val bal = refreshBalance(context, cred)
        val text = StringBuilder(result)
        if (credits > 0) text.append(" · +").append(credits).append(" 积分")
        when {
            bal == -2.0 -> text.append(" · 剩余 ∞")
            bal != null -> text.append(" · 剩余 ").append(bal.toInt()).append(" 积分")
        }
        SchedLauncher.notifyResult(context, true, text.toString())
        L.i("LocalSigner: ✅ $result（$today）credits=$credits bal=$bal")
        return SignResult(true, alreadyChecked = already, message = result, balance = bal)
    }

    /** 读宿主状态文件 → 合并更新 → su 写回（保留 history 等其余字段） */
    private fun mergeAndWrite(today: String? = null, ok: Boolean = false, result: String, credits: Int = 0) {
        try {
            val state = readHostState() ?: JSONObject()
            val now = System.currentTimeMillis()
            state.put("lastResult", result)
            if (today != null) {
                if (ok) {
                    state.put("lastOkDay", today)
                        .put("lastSigninAt", now)
                        .put("lastCredits", credits)
                        .put("failCount", 0)
                        .put("nextRetryAt", 0L)
                } else {
                    state.put("failCount", state.optInt("failCount", 0) + 1)
                        .put("failDay", today)
                        .put("nextRetryAt", now + 3_600_000L)
                }
                // history 当日 upsert（对齐 hook 引擎格式）
                val history = state.optJSONArray("history") ?: JSONArray()
                val entry = JSONObject()
                    .put("day", today)
                    .put("ok", if (ok) 1 else 0)
                    .put("result", result)
                    .put("credits", credits)
                    .put("at", now)
                var replaced = false
                for (i in 0 until history.length()) {
                    if (history.optJSONObject(i)?.optString("day") == today) {
                        history.put(i, entry)
                        replaced = true
                        break
                    }
                }
                if (!replaced) history.put(entry)
                while (history.length() > 30) history.remove(0)
                state.put("history", history)
            }
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat > $STATE_FILE_PATH"))
            proc.outputStream.use { it.write(state.toString().toByteArray(Charsets.UTF_8)) }
            proc.waitFor()
        } catch (t: Throwable) {
            L.w("LocalSigner: 状态写回失败: ${t.message}")
        }
    }

    private fun readHostState(): JSONObject? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat $STATE_FILE_PATH"))
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val trimmed = out.trim()
        if (trimmed.startsWith("{")) JSONObject(trimmed) else null
    } catch (_: Throwable) {
        null
    }
}
