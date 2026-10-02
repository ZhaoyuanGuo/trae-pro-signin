package com.example.traesignin.hook

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.Toast
import com.example.traesignin.XposedEntry
import com.example.traesignin.util.L
import com.example.traesignin.util.Prefs
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 自动签到引擎（宿主进程内主动签到，已真机验证的路径）。
 *
 * 流程：读 keva 凭证（Creds）→ status 查询 → 未签则 claim → 通知结果。
 * 调度：进程启动补签 + 每分钟 tick（手动触发/失败重试）+ 每日 00:05 闹钟。
 * 去重：宿主 filesDir 状态文件为权威源，RemotePreferences 仅作 UI 镜像与指令通道。
 */
internal object SignInEngine {

    private const val STATE_PREFS = "trae_state"       // hook → App：结果上报（UI 镜像）
    private const val SETTINGS_PREFS = "trae_settings" // App → hook：设置镜像
    private const val STATE_FILE = "trae_signin_state.json"
    private const val CHANNEL_ID = "trae_signin"
    private const val ACTION_TICK = "io.github.zhaoyuanguo.traesignin.TICK"

    // 接口源不再硬编码：启动/签到前从 COS 拉取配置（TraeApiSource），失败回退缓存或内置地址
    // 积分余额（Trae 手机端设置面板同源接口，已实测）：Σ max(credits_limit - used, 0)

    private const val STARTUP_DELAY_MS = 15_000L
    private const val TICK_INTERVAL_MS = 60_000L
    private const val RETRY_INTERVAL_MS = 3_600_000L
    private const val ATTEMPT_GUARD_MS = 120_000L
    private const val MAX_FAILS_PER_DAY = 6
    private const val TOKEN_WARN_MS = 7L * 86_400_000L

    @Volatile private var started = false
    private val running = AtomicBoolean(false)
    private var appCtx: Context? = null
    private var handler: Handler? = null

    // ---- 状态（宿主 filesDir 权威 + remote prefs 镜像） ----

    private class State {
        var hookStartedAt: Long = 0L
        var lastOkDay: String = ""
        var lastAttemptAt: Long = 0L
        var lastSigninAt: Long = 0L
        var lastResult: String = ""
        var lastCredits: Int = 0
        var failCount: Int = 0
        var failDay: String = ""
        var tokenWarnDay: String = ""
        var lastManualHandled: Long = 0L
        var nextRetryAt: Long = 0L
        /** 近 30 天签到记录：[{day, ok, result, credits, at}] */
        var history = org.json.JSONArray()
        /** 剩余积分余额（-1 未知，-2 无限额度） */
        var balance: Double = -1.0
        var balanceAt: Long = 0L
        /** 定时签到：当日已抽偏移 / 当日是否已触发 / 下次触发时间（UI 展示） */
        var schedOffsetDay: String = ""
        var schedOffsetVal: Int = -1
        var schedFiredDay: String = ""
        var nextSchedAt: Long = 0L
        /** 漏签检测：近 7 天内无成功记录的日期（不含今天、不早于注入日），如 ["2026-09-28"] */
        var missedDays = org.json.JSONArray()
        /** 漏签检测完成时间（0 = 未检测） */
        var missedCheckedAt: Long = 0L
    }

    // ---- 启动 ----

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        appCtx = ctx

        val thread = HandlerThread("trae-signin")
        thread.start()
        handler = Handler(thread.looper)

        // 闹钟接收器（进程存活时 Doze 也能触发 00:05 签到）
        try {
            val filter = IntentFilter(ACTION_TICK)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(tickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(tickReceiver, filter)
            }
        } catch (t: Throwable) {
            L.e("SignInEngine: 注册闹钟接收器失败: ${t.message}")
        }
        armScheduledAlarm(ctx)

        // 注入时间并入宿主状态文件（模块 App 经 su 读取展示）
        val st0 = loadState(ctx)
        if (st0.hookStartedAt == 0L) {
            st0.hookStartedAt = System.currentTimeMillis()
            saveState(ctx, st0)
        }

        L.i("SignInEngine: 已启动（启动补签 + 每分钟巡检 + 定时签到闹钟）")
        handler?.postDelayed({ runNow("startup") }, STARTUP_DELAY_MS)
        loop()
    }

    private fun loop() {
        handler?.postDelayed({
            onTick()
            loop()
        }, TICK_INTERVAL_MS)
    }

    private fun onTick() {
        val ctx = appCtx ?: return
        handleLogRequest()
        val state = loadState(ctx)
        // 手动触发（App 侧经 remote prefs 下发）
        val trigger = remote(STATE_PREFS) { it.getLong("manual_trigger", 0L) } ?: 0L
        if (trigger > state.lastManualHandled && trigger > System.currentTimeMillis() - 86_400_000L) {
            state.lastManualHandled = trigger
            saveState(ctx, state)
            L.i("SignInEngine: 收到手动签到指令")
            runNow("manual")
            return
        }
        // 失败重试到期
        if (state.nextRetryAt in 1 until System.currentTimeMillis()) {
            L.i("SignInEngine: 到达重试时间")
            runNow("retry")
        }
        // 定时签到检查
        schedCheck(ctx, state)
        // 每日一次的漏签检测（跨天后首次 tick 重扫近 7 天）
        if (dayKey(state.missedCheckedAt) != dayKey(System.currentTimeMillis())) {
            checkMissedDays(ctx)
        }
    }

    /**
     * 漏签检测：扫近 7 天（不含今天，且不早于注入日——未安装前的日子无法签到，不算漏签），
     * history 中无记录或 ok=0 记为漏签。服务端只签当天，历史失败日无法补试，检测仅用于告知。
     */
    private fun checkMissedDays(ctx: Context) {
        val s = loadState(ctx)
        val hookDay = s.hookStartedAt.takeIf { it > 0L }?.let { dayKey(it) } ?: ""
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val missed = org.json.JSONArray()
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        for (i in 7 downTo 1) {
            val day = fmt.format((base.clone() as Calendar).apply { add(Calendar.DATE, -i) }.time)
            if (hookDay.isNotEmpty() && day < hookDay) continue // 注入前的日子不追究
            var ok = false
            for (j in 0 until s.history.length()) {
                val e = s.history.optJSONObject(j) ?: continue
                if (e.optString("day") == day && e.optInt("ok", 0) == 1) { ok = true; break }
            }
            if (!ok) missed.put(day)
        }
        val changed = missed.toString() != s.missedDays.toString()
        s.missedDays = missed
        s.missedCheckedAt = System.currentTimeMillis()
        saveState(ctx, s)
        if (missed.length() > 0) {
            L.i("SignInEngine: 漏签检测完成：最近漏签 ${missed.length()} 天（$missed）" + if (changed) "" else "（与上次一致）")
        } else {
            L.i("SignInEngine: 漏签检测完成：近 7 天无漏签")
        }
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            L.i("SignInEngine: 闹钟触发")
            runNow("alarm")
        }
    }

    /**
     * 日志导出请求：App 侧写 trae_settings.log_request_at，
     * 本进程 dump 缓冲日志回写 trae_state.log_dump / log_dump_at 供 App 分享。
     * 水位 logReqHandled 防重复处理（同 manual_trigger 模式）。
     */
    private fun handleLogRequest() {
        val req = remote(SETTINGS_PREFS) { it.getLong("log_request_at", 0L) } ?: 0L
        if (req <= 0L) return
        val handled = remote(STATE_PREFS) { it.getLong("logReqHandled", 0L) } ?: 0L
        if (req <= handled) return
        val dump = L.dump()
        remote(STATE_PREFS) { p ->
            p.edit()
                .putString("log_dump", dump)
                .putLong("log_dump_at", System.currentTimeMillis())
                .putLong("logReqHandled", req)
                .apply()
        }
        L.i("SignInEngine: 日志导出请求已处理（${dump.length} 字符）")
    }

    fun runNow(reason: String) {
        if (!running.compareAndSet(false, true)) {
            L.i("SignInEngine: 已有任务在跑，跳过（$reason）")
            return
        }
        handler?.post {
            try {
                doRun(reason)
            } catch (t: Throwable) {
                L.e("SignInEngine: 执行异常: ${t.message}", t)
            } finally {
                running.set(false)
            }
        }
    }

    // ---- 主流程 ----

    private fun doRun(reason: String) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val today = dayKey(now)

        if (!isEnabled()) {
            L.i("SignInEngine: 自动签到已关闭（$reason）")
            return
        }
        // 云端代签开启：本机当日跳过 claim（手动签到优先不受限），由服务器定时执行，
        // 避免两端抢签；结果经 App 补录/回读保持状态一致
        if (reason != "manual") {
            val cloudOn = remote(SETTINGS_PREFS) { it.getBoolean("cloud_signin_enabled", false) } ?: false
            if (cloudOn) {
                L.i("SignInEngine: 云端代签已开启，本机跳过 claim（$reason）")
                return
            }
        }
        val state = loadState(ctx)
        if (reason != "manual" && state.lastOkDay == today) {
            L.i("SignInEngine: 今日($today)已签到，跳过（$reason）")
            // 已签仍刷新余额，保持模块 App 显示新鲜
            val cred0 = Creds.load(ctx)
            if (cred0.usable) refreshBalance(ctx, state, cred0)
            return
        }
        if (reason != "manual" && state.failCount >= MAX_FAILS_PER_DAY && state.failDay == today) {
            L.i("SignInEngine: 今日失败已达上限(${state.failCount})，放弃（$reason）")
            return
        }
        if (reason != "manual" && now - state.lastAttemptAt < ATTEMPT_GUARD_MS) {
            L.i("SignInEngine: 另一进程刚尝试过，跳过（$reason）")
            return
        }
        state.lastAttemptAt = now
        saveState(ctx, state)

        val cred = Creds.load(ctx)
        if (!cred.usable) {
            fail(ctx, state, today, "未找到 Trae 登录凭证，请打开 Trae 确认已登录后重试")
            return
        }
        checkTokenExpiry(ctx, state, cred.tokenExpAt)

        // 拉取最新接口源（失败静默回退），后续请求全部走动态地址
        TraeApiSource.refresh(ctx)
        val api = TraeApiSource.current(ctx)

        // 1) 状态查询（免 device-id 校验）
        val st = post(api.statusUrl, cred)
        if (st.code == 0) {
            val data = st.data
            refreshBalance(ctx, state, cred)
            if (!data.optBoolean("enable", true)) {
                markOk(ctx, state, today, "签到功能未开启（服务端关闭）", 0)
                return
            }
            if (data.optBoolean("checked_in", false)) {
                markOk(ctx, state, today, "今日已签到（App 内已签）", data.optInt("credits", 0))
                return
            }
        } else {
            L.w("SignInEngine: status code=${st.code} ${st.message}")
        }

        // 2) 执行签到（3 次尝试：0s / 10s / 30s）
        var last = st
        val delays = longArrayOf(0L, 10_000L, 30_000L)
        for (i in delays.indices) {
            if (delays[i] > 0) {
                try { Thread.sleep(delays[i]) } catch (_: InterruptedException) { return }
            }
            last = post(api.claimUrl, cred)
            L.i("SignInEngine: claim#${i + 1} code=${last.code} msg=${last.message}")
            when {
                last.code == 0 -> {
                    // 成功后回查积分余额
                    val st2 = post(api.statusUrl, cred)
                    val credits = if (st2.code == 0) st2.data.optInt("credits", 0) else 0
                    refreshBalance(ctx, state, cred)
                    markOk(ctx, state, today, "签到成功", credits)
                    return
                }
                // 疑似并发已签：回查状态兜底
                post(api.statusUrl, cred).data.optBoolean("checked_in", false) -> {
                    val st3 = post(api.statusUrl, cred)
                    refreshBalance(ctx, state, cred)
                    markOk(ctx, state, today, "今日已签到（并发兜底）", st3.data.optInt("credits", 0))
                    return
                }
                // 9074 = 设备不匹配软拒，快速重试无意义，交给下一轮重试
                last.code == 9074 -> break
            }
        }
        fail(ctx, state, today, "签到失败：code=${last.code} ${last.message}（将于 1 小时后重试）")
    }

    private fun markOk(ctx: Context, state: State, today: String, result: String, credits: Int) {
        // 合并磁盘最新状态再写回，避免覆盖另一进程刚写入的字段（双进程引擎写竞争）
        val s = loadState(ctx)
        s.lastOkDay = today
        s.lastSigninAt = System.currentTimeMillis()
        s.lastResult = result
        s.lastCredits = credits
        s.failCount = 0
        s.nextRetryAt = 0L
        upsertHistory(s, today, true, result, credits)
        saveState(ctx, s)
        L.i("✅ SignInEngine: $result（$today）credits=$credits")
        val bal = balanceText(s)
        val extra = if (credits > 0) "+$credits 积分$bal" else bal
        toast(ctx, "✅ $result（$extra）")
        notify(ctx, "TRAE 签到成功", "$result（$extra）", false)
        appCtx?.let { armScheduledAlarm(it) }
    }

    /** 余额文案（App 内展示用千分位） */
    private fun balanceText(state: State): String = when {
        state.balance == -2.0 -> "，余额无限"
        state.balance >= 0 -> "，余额 ${java.text.NumberFormat.getInstance(Locale.CHINA).format(Math.round(state.balance))}"
        else -> ""
    }

    /** 查询积分余额并写入状态文件（ide_user_ent_usage，无需 device-id 校验） */
    private fun refreshBalance(ctx: Context, state: State, cred: Creds.Cred) {
        try {
            val api = TraeApiSource.current(ctx)
            val r = post(api.entUsageUrl, cred, """{"require_usage":true,"req_source":1}""")
            val data = r.data
            if (data.length() == 0) {
                L.w("SignInEngine: 余额查询失败 code=${r.code} ${r.message}")
                return
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
            if (any) {
                // 合并写回：只更新余额字段，避免覆盖另一进程刚写入的签到/失败状态
                val s = loadState(ctx)
                s.balance = if (unlimited) -2.0 else Math.round(remaining).toDouble()
                s.balanceAt = System.currentTimeMillis()
                saveState(ctx, s)
                L.i("SignInEngine: 余额查询成功 → ${balanceText(s).removePrefix("，")}")
            }
        } catch (t: Throwable) {
            L.w("SignInEngine: 余额查询异常: ${t.message}")
        }
    }

    private fun fail(ctx: Context, state: State, today: String, msg: String) {
        // 合并磁盘最新状态再写回：本进程持有的快照可能早于另一进程的最近写入（网络调用期间），
        // 直接整份写回会丢掉对方的失败计数/重试计划（实测导致失败态被旧态覆盖、重试丢失）
        val s = loadState(ctx)
        s.lastResult = msg
        s.failCount = if (s.failDay == today) s.failCount + 1 else 1
        s.failDay = today
        s.nextRetryAt = System.currentTimeMillis() + RETRY_INTERVAL_MS
        upsertHistory(s, today, false, msg, 0)
        saveState(ctx, s)
        L.e("❌ SignInEngine: $msg（第 ${s.failCount} 次失败）")
        toast(ctx, "❌ $msg")
        notify(ctx, "TRAE 签到失败", msg, true)
    }

    /** 宿主进程内弹 Toast（引擎跑在 HandlerThread，Toast 需主线程 Looper） */
    private fun toast(ctx: Context, msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
                    L.i("🔔 SignInEngine: Toast 已展示: $msg")
                } catch (t: Throwable) {
                    L.w("SignInEngine: Toast 失败: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            L.w("SignInEngine: Toast 调度失败: ${t.message}")
        }
    }

    private fun checkTokenExpiry(ctx: Context, state: State, expAt: Long) {
        if (expAt <= 0L) return
        val today = dayKey(System.currentTimeMillis())
        if (expAt - System.currentTimeMillis() > TOKEN_WARN_MS) return
        val s = loadState(ctx) // 合并写回，理由同上
        if (s.tokenWarnDay == today) return
        s.tokenWarnDay = today
        saveState(ctx, s)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        notify(ctx, "TRAE 登录凭证即将过期",
            "Token 将于 ${fmt.format(Date(expAt))} 过期，请打开 Trae App 重新登录，否则自动签到将失效。",
            true)
    }

    // ---- 网络（真机验证规格：Cloud-IDE-JWT + x-device-id） ----

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
                setRequestProperty("x-device-brand", Build.BRAND)
                setRequestProperty("x-device-type", Build.MODEL)
                setRequestProperty("x-os-version", "Android ${Build.VERSION.RELEASE}")
                setRequestProperty("x-app-version", appVersion() ?: "0.0.21")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val respText = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
            val obj = JSONObject(respText)
            // 注意：status/claim 响应为扁平结构（checked_in / credits / enable 在顶层，无 data 包裹）
            Resp(obj.optInt("code", -1), obj.optString("message", ""), obj)
        } catch (t: Throwable) {
            L.e("SignInEngine: 请求异常 ${url.substringAfterLast('/')}: ${t.message}")
            Resp(-1, t.message ?: "network error", JSONObject())
        } finally {
            conn?.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return "{}"
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Throwable) { "{}" }
    }

    private fun appVersion(): String? = try {
        val ctx = appCtx ?: return null
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (_: Throwable) { null }

    // ---- 开关 ----

    private fun isEnabled(): Boolean {
        // App 侧镜像优先（实时生效），XSharedPreferences 兜底
        remote(SETTINGS_PREFS) { it.getBoolean("signin_enabled", true) }?.let { return it }
        return Prefs.isSignInEnabled()
    }

    // ---- 定时签到（用户自选时间 + 每日随机偏移模拟真实操作） ----

    /** App → hook 设置镜像里的定时签到配置 */
    private class SchedSet(val enabled: Boolean, val hour: Int, val minute: Int, val offsetMin: Int)

    private fun schedSettings(): SchedSet =
        remote(SETTINGS_PREFS) { p ->
            SchedSet(
                p.getBoolean("sched_enabled", false),
                p.getInt("sched_hour", 8).coerceIn(0, 23),
                p.getInt("sched_minute", 0).coerceIn(0, 59),
                p.getInt("sched_offset_min", 30).coerceIn(0, 120),
            )
        } ?: SchedSet(false, 8, 0, 30)

    /** 每分钟巡检：当日偏移抽取 + 到点触发 + 闹钟布防 */
    private fun schedCheck(ctx: Context, state: State) {
        val s = schedSettings()
        if (!s.enabled) {
            if (state.nextSchedAt != 0L || state.schedFiredDay.isNotEmpty()) {
                state.nextSchedAt = 0L
                saveState(ctx, state)
            }
            lastArmedAt = 0L
            return
        }
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        var dirty = false
        // 每日重抽一次随机偏移（当日进程重启不变）
        if (state.schedOffsetDay != today) {
            state.schedOffsetDay = today
            state.schedOffsetVal = if (s.offsetMin > 0) Random.nextInt(0, s.offsetMin + 1) else 0
            dirty = true
            L.i("SignInEngine: 今日($today)定时偏移已抽定 ${state.schedOffsetVal} 分钟")
        }
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.hour)
            set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, state.schedOffsetVal)
        }.timeInMillis
        val next = if (now < target) target else target + 86_400_000L
        if (state.nextSchedAt != next) {
            state.nextSchedAt = next
            dirty = true
        }
        // 到点触发（当日一次；已签/已触发均跳过）
        if (now >= target && state.schedFiredDay != today && state.lastOkDay != today) {
            state.schedFiredDay = today
            saveState(ctx, state)
            L.i("SignInEngine: 到达定时签到时间（$today ${"%02d:%02d".format(s.hour, s.minute)} +${state.schedOffsetVal}min）")
            armScheduledAlarm(ctx)
            runNow("scheduled")
            return
        }
        if (dirty) saveState(ctx, state)
        armScheduledAlarm(ctx)
    }

    @Volatile private var lastArmedAt: Long = 0L

    /** 布防 AlarmManager 到下一次定时签到时刻（进程存活时含 Doze 精确触发） */
    private fun armScheduledAlarm(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 2001,
                Intent(ACTION_TICK).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val s = schedSettings()
            if (!s.enabled) {
                if (lastArmedAt != 0L) { am.cancel(pi); lastArmedAt = 0L }
                return
            }
            val now = System.currentTimeMillis()
            val today = dayKey(now)
            val st = loadState(ctx)
            val offToday = if (st.schedOffsetDay == today && st.schedOffsetVal >= 0) st.schedOffsetVal
            else if (s.offsetMin > 0) Random.nextInt(0, s.offsetMin + 1) else 0
            val base = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, s.hour)
                set(Calendar.MINUTE, s.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayTarget = (base.clone() as Calendar).apply { add(Calendar.MINUTE, offToday) }
            val fireAt = if (todayTarget.timeInMillis > now) todayTarget.timeInMillis
            else (base.clone() as Calendar).apply {
                add(Calendar.DATE, 1)
                // 明日偏移尚未抽定，按偏移上限布防（早于等于真实目标，仅作兜底唤醒）
                add(Calendar.MINUTE, s.offsetMin)
            }.timeInMillis
            if (fireAt == lastArmedAt) return
            lastArmedAt = fireAt
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } catch (_: Throwable) {
                try {
                    am.setWindow(AlarmManager.RTC_WAKEUP, fireAt, 15 * 60_000L, pi)
                } catch (_: Throwable) {
                    am.set(AlarmManager.RTC_WAKEUP, fireAt, pi)
                }
            }
            L.i("SignInEngine: 已布防定时签到闹钟 → ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(fireAt))}")
        } catch (t: Throwable) {
            L.e("SignInEngine: 布防定时闹钟失败: ${t.message}")
        }
    }

    // ---- 通知 ----

    private fun notify(ctx: Context, title: String, text: String, fail: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "自动签到", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val icon = ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            nm.notify(if (fail) 2002 else 2001, n)
        } catch (t: Throwable) {
            L.w("SignInEngine: 通知失败: ${t.message}")
        }
    }

    // ---- 持久化 ----

    /** 同一天覆盖写入（重试成功后把失败记录改为成功） */
    @Synchronized
    private fun upsertHistory(s: State, day: String, ok: Boolean, result: String, credits: Int) {
        val entry = JSONObject()
            .put("day", day)
            .put("ok", if (ok) 1 else 0)
            .put("result", result)
            .put("credits", credits)
            .put("at", System.currentTimeMillis())
        val arr = org.json.JSONArray()
        var replaced = false
        for (i in 0 until s.history.length()) {
            val e = s.history.optJSONObject(i) ?: continue
            if (e.optString("day") == day) {
                arr.put(entry)
                replaced = true
            } else {
                arr.put(e)
            }
        }
        if (!replaced) arr.put(entry)
        while (arr.length() > 30) arr.remove(0)
        s.history = arr
    }

    @Synchronized
    private fun loadState(ctx: Context): State {
        val s = State()
        try {
            val f = File(ctx.filesDir, STATE_FILE)
            if (f.isFile) {
                val obj = JSONObject(f.readText())
                s.hookStartedAt = obj.optLong("hookStartedAt", 0L)
                s.lastOkDay = obj.optString("lastOkDay", "")
                s.lastAttemptAt = obj.optLong("lastAttemptAt", 0L)
                s.lastSigninAt = obj.optLong("lastSigninAt", 0L)
                s.lastResult = obj.optString("lastResult", "")
                s.lastCredits = obj.optInt("lastCredits", 0)
                s.failCount = obj.optInt("failCount", 0)
                s.failDay = obj.optString("failDay", "")
                s.tokenWarnDay = obj.optString("tokenWarnDay", "")
                s.lastManualHandled = obj.optLong("lastManualHandled", 0L)
                s.nextRetryAt = obj.optLong("nextRetryAt", 0L)
                s.history = obj.optJSONArray("history") ?: org.json.JSONArray()
                s.balance = obj.optDouble("balance", -1.0)
                s.balanceAt = obj.optLong("balanceAt", 0L)
                s.schedOffsetDay = obj.optString("schedOffsetDay", "")
                s.schedOffsetVal = obj.optInt("schedOffsetVal", -1)
                s.schedFiredDay = obj.optString("schedFiredDay", "")
                s.nextSchedAt = obj.optLong("nextSchedAt", 0L)
                s.missedDays = obj.optJSONArray("missedDays") ?: org.json.JSONArray()
                s.missedCheckedAt = obj.optLong("missedCheckedAt", 0L)
            }
        } catch (_: Throwable) { }
        return s
    }

    @Synchronized
    private fun saveState(ctx: Context, s: State) {
        try {
            val obj = JSONObject()
                .put("hookStartedAt", s.hookStartedAt)
                .put("lastOkDay", s.lastOkDay)
                .put("lastAttemptAt", s.lastAttemptAt)
                .put("lastSigninAt", s.lastSigninAt)
                .put("lastResult", s.lastResult)
                .put("lastCredits", s.lastCredits)
                .put("failCount", s.failCount)
                .put("failDay", s.failDay)
                .put("tokenWarnDay", s.tokenWarnDay)
                .put("lastManualHandled", s.lastManualHandled)
                .put("nextRetryAt", s.nextRetryAt)
                .put("history", s.history)
                .put("balance", s.balance)
                .put("balanceAt", s.balanceAt)
                .put("schedOffsetDay", s.schedOffsetDay)
                .put("schedOffsetVal", s.schedOffsetVal)
                .put("schedFiredDay", s.schedFiredDay)
                .put("nextSchedAt", s.nextSchedAt)
                .put("missedDays", s.missedDays)
                .put("missedCheckedAt", s.missedCheckedAt)
            File(ctx.filesDir, STATE_FILE).writeText(obj.toString())
        } catch (t: Throwable) {
            L.w("SignInEngine: 状态保存失败: ${t.message}")
        }
    }

    // ---- RemotePreferences 工具（App→hook 只读方向；hook→App 走 StateProvider） ----

    private inline fun <T> remote(name: String, block: (SharedPreferences) -> T): T? = try {
        XposedEntry.instance?.getRemotePreferences(name)?.let(block)
    } catch (_: Throwable) { null }

    private fun dayKey(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))
}
