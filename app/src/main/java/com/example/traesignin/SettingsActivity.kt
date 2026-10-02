package com.example.traesignin

import android.animation.ValueAnimator
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.traesignin.cloud.CloudApi
import com.example.traesignin.cloud.HostCreds
import com.example.traesignin.cloud.TokenManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity(), App.ServiceListener {

    companion object {
        private const val TAG_UI = "TraeSigninUI"
        private const val STATE_FILE_PATH = "/data/data/com.bytedance.trae.cn/files/trae_signin_state.json"
        /** App → hook 设置镜像（hook 侧优先读取） */
        const val SETTINGS_PREFS = "trae_settings"
        /** hook → App 状态镜像（manual_trigger 指令通道：App 写，hook 每分钟 tick 读） */
        private const val STATE_PREFS = "trae_state"
        private const val CACHE_TTL = 30_000L
    }

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedState: JSONObject? = null
    private var cachedStateAt = 0L
    private var signinPoll: Runnable? = null
    private var logPoll: Runnable? = null

    /** 云端代签：程序化更新 UI 时置 true，避免 switch/slider 监听器反向触发 PUT */
    private var cloudUiSyncing = false
    private var cloudSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 透明状态栏下让内容避开系统栏
        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = WindowInsetsCompat.Type.systemBars()
            v.setPadding(insets.getInsets(bars).left, insets.getInsets(bars).top,
                insets.getInsets(bars).right, insets.getInsets(bars).bottom)
            insets
        }

        prefs = getSharedPreferences("trae_signin_prefs", 0)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "" // 双标题去重：只保留内容区大标题
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // ---- ① 开屏自动签到 ----
        val swSignin = findViewById<MaterialSwitch>(R.id.swSignin)
        swSignin.isChecked = prefs.getBoolean("signin_enabled", true)
        swSignin.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("signin_enabled", checked).apply()
            syncRemoteSettings()
        }

        // ---- ② 定时签到（时间 + 随机偏移） ----
        val swScheduled = findViewById<MaterialSwitch>(R.id.swScheduled)
        val schedBody = findViewById<View>(R.id.schedBody)
        swScheduled.isChecked = prefs.getBoolean("sched_enabled", false)
        schedBody.visibility = if (swScheduled.isChecked) View.VISIBLE else View.GONE
        swScheduled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("sched_enabled", checked).apply()
            syncRemoteSettings()
            // 展开动画
            if (checked) {
                schedBody.visibility = View.VISIBLE
                schedBody.alpha = 0f
                schedBody.translationY = -12f
                schedBody.animate().alpha(1f).translationY(0f).setDuration(220L)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                schedBody.animate().alpha(0f).translationY(-12f).setDuration(180L)
                    .withEndAction { schedBody.visibility = View.GONE }.start()
            }
        }

        renderSchedTime()
        findViewById<View>(R.id.rowSchedTime).setOnClickListener {
            val hour = prefs.getInt("sched_hour", 8)
            val minute = prefs.getInt("sched_minute", 0)
            TimePickerDialog(this, { _, h, m ->
                prefs.edit().putInt("sched_hour", h).putInt("sched_minute", m).apply()
                renderSchedTime()
                syncRemoteSettings()
            }, hour, minute, true).show()
        }

        val slider = findViewById<Slider>(R.id.sliderOffset)
        val tvOffset = findViewById<TextView>(R.id.tvOffsetVal)
        slider.value = prefs.getInt("sched_offset_min", 30).toFloat()
        tvOffset.text = "≤ ${slider.value.toInt()} 分钟"
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                tvOffset.text = "≤ ${value.toInt()} 分钟"
                prefs.edit().putInt("sched_offset_min", value.toInt()).apply()
                syncRemoteSettings()
            }
        }

        // ---- ③ 深色主题（控制模块 App 自身 + Trae 深色；关闭 = 跟随系统） ----
        val swDark = findViewById<MaterialSwitch>(R.id.swDark)
        swDark.isChecked = prefs.getBoolean("dark_mode", false)
        swDark.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("dark_mode", checked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
            // 同步 Trae：hook 侧读取 trae_dark → Force Dark + 夜间配置
            syncRemoteSettings()
        }

        // ---- ④ 立即签到（写 remote prefs manual_trigger，hook 侧分钟 tick 内执行） ----
        findViewById<View>(R.id.btnSigninNow).setOnClickListener { triggerManualSignin() }

        // ---- ⑤ 导出日志（写 remote settings log_request_at，hook 侧 tick 内回写 log_dump） ----
        findViewById<View>(R.id.btnExportLog).setOnClickListener { exportLog() }

        // ---- ⑥ 云端代签（账号中心 + 服务器定时代签） ----
        setupCloudSection()

        playEntranceAnimation(savedInstanceState == null)
        refreshStatus(force = true)
        refreshServiceStatus()
    }

    override fun onStart() {
        super.onStart()
        App.addServiceListener(this)
        // 回到前台强制刷新，避免 30s 缓存展示旧状态
        refreshStatus(force = true)
        refreshServiceStatus()
        // 云端代签：登录态/计划/凭证自动续传（含登录页返回后的刷新）
        refreshCloudStatus()
    }

    override fun onStop() {
        signinPoll?.let { mainHandler.removeCallbacks(it) }
        signinPoll = null
        logPoll?.let { mainHandler.removeCallbacks(it) }
        logPoll = null
        App.removeServiceListener(this)
        super.onStop()
    }

    override fun onServiceChanged(service: XposedService?) {
        mainHandler.post {
            refreshServiceStatus()
            // 服务绑定晚于页面创建时补同步设置镜像
            if (service != null) syncRemoteSettings()
        }
    }

    // ---- 状态显示（全部异步，杜绝主线程 su ANR） ----

    private fun refreshStatus(force: Boolean) {
        readHostStateAsync(force) { state -> if (!isFinishing) renderStatus(state) }
    }

    private fun renderStatus(state: JSONObject?) {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val hmFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

        val hookAt = state?.optLong("hookStartedAt", 0L) ?: 0L
        val lastOkDay = state?.optString("lastOkDay", "") ?: ""
        val lastResult = state?.optString("lastResult", "") ?: ""
        val lastSigninAt = state?.optLong("lastSigninAt", 0L) ?: 0L
        val lastCredits = state?.optInt("lastCredits", 0) ?: 0
        val failCount = state?.optInt("failCount", 0) ?: 0
        val balance = state?.optDouble("balance", -1.0) ?: -1.0
        val balanceAt = state?.optLong("balanceAt", 0L) ?: 0L
        val nextSchedAt = state?.optLong("nextSchedAt", 0L) ?: 0L

        // 徽章语义色：绿=今日已签 / 橙=今日有失败 / 蓝=已注入无签到记录 / 红=未注入 / 蓝灰=未知
        when {
            state == null -> setBadge("未知", R.color.badge_bg, R.color.badge_text)
            hookAt <= 0L -> setBadge("未注入", R.color.err_bg, R.color.err_text)
            lastOkDay == today -> setBadge("已签", R.color.ok_bg, R.color.ok_text)
            failCount > 0 -> setBadge("有失败", R.color.warn_bg, R.color.warn_text)
            else -> setBadge("已注入", R.color.badge_bg, R.color.badge_text)
        }

        // 剩余积分大数字（-2 = 无限额度）
        val tvCredits = findViewById<TextView>(R.id.tvCredits)
        val tvExtra = findViewById<TextView>(R.id.tvCreditsExtra)
        when {
            balance == -2.0 -> tvCredits.text = "∞"
            balance >= 0 -> tvCredits.text = java.text.NumberFormat
                .getInstance(Locale.CHINA).format(Math.round(balance))
            else -> tvCredits.text = "—"
        }
        tvExtra.text = if (balanceAt > 0) "更新于 ${fmt.format(Date(balanceAt))}" else ""

        val tvHookStatus = findViewById<TextView>(R.id.tvHookStatus)
        if (state != null) {
            val sb = StringBuilder()
            sb.append("模块已注入 Trae 手机端（最近：${fmt.format(Date(hookAt))}）")
            when {
                lastOkDay == today ->
                    sb.append("\n今日签到：✅ $lastResult")
                        .append(if (lastCredits > 0) "（+$lastCredits 积分）" else "")
                failCount > 0 ->
                    sb.append("\n今日签到：⚠️ $lastResult")
                lastResult.isNotEmpty() ->
                    sb.append("\n上次签到：$lastResult（${fmt.format(Date(lastSigninAt))}）")
                else ->
                    sb.append("\n签到状态：等待 Trae 进程运行后自动执行")
            }
            // 漏签提示（hook 侧每日检测近 7 天，历史失败日无法补签，仅告知）
            val missedDays = state.optJSONArray("missedDays")
            if (missedDays != null && missedDays.length() > 0) {
                val list = (0 until missedDays.length())
                    .mapNotNull { missedDays.optString(it).takeIf { d -> d.isNotEmpty() } }
                    .joinToString("、") { it.substringAfterLast('-') }
                if (list.isNotEmpty()) {
                    sb.append("\n⚠️ 最近漏签 ${missedDays.length()} 天（$list）")
                }
            }
            if (prefs.getBoolean("sched_enabled", false) && nextSchedAt > 0) {
                val offMax = prefs.getInt("sched_offset_min", 30)
                val c = Calendar.getInstance().apply { timeInMillis = nextSchedAt }
                val isToday = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.time) == today
                sb.append("\n定时签到：${if (isToday) "今日" else "明日"} ${hmFmt.format(c.time)}")
                    .append("（随机偏移 ≤ $offMax 分钟）")
            }
            tvHookStatus.text = sb.toString()
        } else {
            tvHookStatus.text =
                "无法读取宿主状态文件。\n请打开 Trae App 触发一次模块运行；若仍无效，请在 Magisk 中授予本模块 su 权限。"
        }

        // 手机端签到成功后向云端补录（云端未签时），防两端抢签
        maybePostMobileLog(state)
    }

    private fun renderSchedTime() {
        val hour = prefs.getInt("sched_hour", 8)
        val minute = prefs.getInt("sched_minute", 0)
        findViewById<TextView>(R.id.tvSchedTime).text = String.format(Locale.CHINA, "%02d:%02d", hour, minute)
    }

    /** 徽章换色：背景/文字颜色做 300ms 过渡，两个徽章同步 */
    private fun setBadge(text: String, bgRes: Int, textRes: Int) {
        val targetBg = getColor(bgRes)
        val targetText = getColor(textRes)
        for (id in intArrayOf(R.id.tvStatusBadge, R.id.tvStatusBadge2)) {
            val badge = findViewById<TextView>(id) ?: continue
            if (badge.text.toString() == text &&
                badge.backgroundTintList?.defaultColor == targetBg) continue
            badge.text = text
            val fromBg = badge.backgroundTintList?.defaultColor ?: targetBg
            val fromText = badge.currentTextColor
            ValueAnimator.ofArgb(0, 1).apply {
                duration = 300
                addUpdateListener {
                    val f = it.animatedFraction
                    badge.backgroundTintList = ColorStateList.valueOf(blend(fromBg, targetBg, f))
                    badge.setTextColor(blend(fromText, targetText, f))
                }
                start()
            }
        }
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val a = Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * fraction).toInt()
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * fraction).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * fraction).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * fraction).toInt()
        return Color.argb(a, r, g, b)
    }

    /** 卡片入场：整体上浮渐显，逐个错峰 */
    private fun playEntranceAnimation(firstCreate: Boolean) {
        if (!firstCreate) return
        val views = listOf<View>(
            findViewById(R.id.headerSection),
            findViewById(R.id.cardCredits),
            findViewById(R.id.btnSigninNow),
            findViewById(R.id.btnExportLog),
            findViewById(R.id.cardSwitches),
            findViewById(R.id.cardCloud),
            findViewById(R.id.tvFooter),
        )
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 48f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L * i)
                .setDuration(360L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** su 读取宿主状态文件（异步；force=false 时 30s 内复用缓存，force=true 绕过缓存） */
    private fun readHostStateAsync(force: Boolean, onResult: (JSONObject?) -> Unit) {
        cachedState?.let {
            if (!force && System.currentTimeMillis() - cachedStateAt < CACHE_TTL) {
                onResult(it)
                return
            }
        }
        Thread {
            val obj = try {
                // -mm：切到 Magisk 全局 mount namespace（HyperOS 给 app 进程做了命名空间隔离）
                val proc = Runtime.getRuntime()
                    .exec(arrayOf("su", "-mm", "-c", "cat $STATE_FILE_PATH"))
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                proc.waitFor()
                val trimmed = out.trim()
                android.util.Log.i(TAG_UI, "su read: exit=${proc.exitValue()} outLen=${trimmed.length} err=${err.trim().take(120)}")
                if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            } catch (t: Throwable) {
                android.util.Log.e(TAG_UI, "su read failed", t)
                null
            }
            if (obj != null) {
                cachedState = obj
                cachedStateAt = System.currentTimeMillis()
            }
            mainHandler.post { onResult(obj) }
        }.start()
    }

    private fun syncRemoteSettings() {
        val service = App.service ?: return
        try {
            service.getRemotePreferences(SETTINGS_PREFS).edit()
                .putBoolean("signin_enabled", prefs.getBoolean("signin_enabled", true))
                .putBoolean("trae_dark", prefs.getBoolean("dark_mode", false))
                .putBoolean("sched_enabled", prefs.getBoolean("sched_enabled", false))
                .putInt("sched_hour", prefs.getInt("sched_hour", 8))
                .putInt("sched_minute", prefs.getInt("sched_minute", 0))
                .putInt("sched_offset_min", prefs.getInt("sched_offset_min", 30))
                // 云端代签开启 → 本机引擎当日跳过 claim（手动签到不受限）
                .putBoolean(
                    "cloud_signin_enabled",
                    prefs.getBoolean("cloud_enabled", false) && TokenManager.isLoggedIn(this)
                )
                .apply()
        } catch (_: Throwable) { }
    }

    private fun refreshServiceStatus() {
        // 服务状态读的是已绑定的 LSPosed 服务对象，不涉及 su，主线程安全
        val tv = findViewById<TextView>(R.id.tvServiceStatus)
        val service = App.service
        if (service == null) {
            tv.text = "LSPosed 服务：未连接\n（框架未激活本模块，或管理器/框架异常）"
            return
        }
        val sb = StringBuilder("LSPosed 已连接：${service.frameworkName} ${service.frameworkVersion}")
        val target = try { traeTarget(service) } catch (_: Throwable) { null }
        if (target == null) {
            sb.append("\nTrae 目标进程：未运行")
        } else {
            sb.append("\n目标进程运行中：${target.processName} pid=${target.pid} state=${target.state}")
        }
        tv.text = sb.toString()
    }

    private fun traeTarget(service: XposedService): HookedTarget? = try {
        service.runningTargets.firstOrNull {
            it.processName.startsWith("com.bytedance.trae.cn")
        }
    } catch (_: Throwable) { null }

    /**
     * 立即签到：Trae 未运行则提示；否则写 trae_state.manual_trigger，
     * 由 hook 侧 SignInEngine.onTick()（每分钟）读取并立即执行一次签到。
     */
    private fun triggerManualSignin() {
        val service = App.service
        if (service == null || traeTarget(service) == null) {
            Toast.makeText(this, "Trae 未运行，请先打开", Toast.LENGTH_SHORT).show()
            return
        }
        val triggerAt = System.currentTimeMillis()
        try {
            service.getRemotePreferences(STATE_PREFS).edit()
                .putLong("manual_trigger", triggerAt)
                .apply()
        } catch (t: Throwable) {
            Toast.makeText(this, "指令下发失败：${t.message}", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "已下发立即签到指令", Toast.LENGTH_SHORT).show()
        startSigninPoll(triggerAt)
    }

    /** 轮询宿主状态直到指令被 hook 处理（lastManualHandled ≥ triggerAt），上限 75s */
    private fun startSigninPoll(triggerAt: Long) {
        signinPoll?.let { mainHandler.removeCallbacks(it) }
        val startedAt = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing) return
                readHostStateAsync(force = true) { st ->
                    if (isFinishing) return@readHostStateAsync
                    renderStatus(st)
                    val handled = st?.optLong("lastManualHandled", 0L) ?: 0L
                    if (handled >= triggerAt) {
                        val r = st?.optString("lastResult", "") ?: ""
                        if (r.isNotEmpty()) {
                            Toast.makeText(this@SettingsActivity, r, Toast.LENGTH_LONG).show()
                        }
                    } else if (System.currentTimeMillis() - startedAt < 75_000L) {
                        mainHandler.postDelayed(this, 3_000L)
                    }
                }
            }
        }
        signinPoll = runnable
        mainHandler.postDelayed(runnable, 3_000L)
    }

    /**
     * 导出日志：写 trae_settings.log_request_at，
     * hook 侧 SignInEngine.onTick()（每分钟）dump 缓冲日志回写 trae_state.log_dump，
     * 本侧轮询到 log_dump_at ≥ 请求时间后经系统分享导出。
     */
    private fun exportLog() {
        val service = App.service
        if (service == null || traeTarget(service) == null) {
            Toast.makeText(this, "Trae 未运行，请先打开", Toast.LENGTH_SHORT).show()
            return
        }
        val reqAt = System.currentTimeMillis()
        try {
            service.getRemotePreferences(SETTINGS_PREFS).edit()
                .putLong("log_request_at", reqAt)
                .apply()
        } catch (t: Throwable) {
            Toast.makeText(this, "导出失败：${t.message}", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在收集日志…", Toast.LENGTH_SHORT).show()
        logPoll?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing) return
                val dump = try {
                    service.getRemotePreferences(STATE_PREFS)
                        .getString("log_dump", null)?.takeIf { it.isNotEmpty() }
                } catch (_: Throwable) { null }
                val dumpAt = try {
                    service.getRemotePreferences(STATE_PREFS).getLong("log_dump_at", 0L)
                } catch (_: Throwable) { 0L }
                if (dump != null && dumpAt >= reqAt) {
                    shareText(dump)
                } else if (System.currentTimeMillis() - reqAt < 75_000L) {
                    mainHandler.postDelayed(this, 3_000L)
                } else {
                    Toast.makeText(this@SettingsActivity, "日志收集超时，请重试", Toast.LENGTH_LONG).show()
                }
            }
        }
        logPoll = runnable
        mainHandler.postDelayed(runnable, 1_500L)
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Trae 自动签到日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, "导出签到日志"))
        } catch (t: Throwable) {
            Toast.makeText(this, "分享失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= 云端代签 =================

    private fun setupCloudSection() {
        val swCloud = findViewById<MaterialSwitch>(R.id.swCloud)
        val slider = findViewById<Slider>(R.id.sliderCloudJitter)
        val tvJitter = findViewById<TextView>(R.id.tvCloudJitterVal)

        findViewById<View>(R.id.btnCloudAction).setOnClickListener {
            if (TokenManager.isLoggedIn(this)) confirmLogout() else startActivity(Intent(this, LoginActivity::class.java))
        }
        findViewById<View>(R.id.btnCloudNow).setOnClickListener { runCloudNow() }

        findViewById<View>(R.id.rowCloudTime).setOnClickListener {
            val hour = prefs.getInt("cloud_hour", 8)
            val minute = prefs.getInt("cloud_minute", 0)
            TimePickerDialog(this, { _, h, m ->
                prefs.edit().putInt("cloud_hour", h).putInt("cloud_minute", m).apply()
                renderCloudTime()
                putCloudSchedule(prefs.getBoolean("cloud_enabled", false))
            }, hour, minute, true).show()
        }

        slider.value = prefs.getInt("cloud_jitter", 30).toFloat()
        tvJitter.text = "≤ ${slider.value.toInt()} 分钟"
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                tvJitter.text = "≤ ${value.toInt()} 分钟"
                prefs.edit().putInt("cloud_jitter", value.toInt()).apply()
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                putCloudSchedule(prefs.getBoolean("cloud_enabled", false))
            }
        })

        swCloud.setOnCheckedChangeListener { _, checked ->
            if (cloudUiSyncing) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("cloud_enabled", checked).apply()
            if (checked) {
                // 首次开启：先上传最新凭证再启用（服务器无凭证时代签空跑）
                uploadCredsAsync(silent = false)
            }
            syncRemoteSettings()
            putCloudSchedule(checked)
        }

        renderCloudTime()
    }

    /** 拉取登录态 + 云端计划 + 凭证元信息 + 近 7 天流水，并顺带自动续传凭证 */
    private fun refreshCloudStatus() {
        val loggedIn = TokenManager.isLoggedIn(this)
        if (!loggedIn) {
            renderCloudLoggedOut()
            syncRemoteSettings()
            return
        }
        val swCloud = findViewById<MaterialSwitch>(R.id.swCloud)
        swCloud.isEnabled = true
        findViewById<View>(R.id.cloudBody).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvCloudAccount).text =
            "已登录：${TokenManager.phoneMasked(this) ?: ""}"
        (findViewById<View>(R.id.btnCloudAction) as com.google.android.material.button.MaterialButton).text = "退出"

        Thread {
            var schedule: JSONObject? = null
            var meta: JSONObject? = null
            var logsArr: JSONArray? = null
            var err: String? = null
            try {
                schedule = CloudApi.getSchedule(this)
                meta = CloudApi.credentialsMeta(this)
                logsArr = CloudApi.logs(this, 7)
            } catch (t: Throwable) {
                err = t.message ?: "网络异常"
            }
            mainHandler.post { if (!isFinishing) renderCloudStatus(schedule, meta, logsArr, err) }
        }.start()

        // 每次进入页面自动续传凭证（Trae JWT 约 2 个月有效，靠手机端续命）
        uploadCredsAsync(silent = true)
    }

    private fun renderCloudLoggedOut() {
        cloudUiSyncing = true
        findViewById<TextView>(R.id.tvCloudAccount).text = "未登录友爱账号 · 登录后手机关机也能代签"
        (findViewById<View>(R.id.btnCloudAction) as com.google.android.material.button.MaterialButton).text = "登录"
        val swCloud = findViewById<MaterialSwitch>(R.id.swCloud)
        swCloud.isEnabled = false
        swCloud.isChecked = false
        findViewById<View>(R.id.cloudBody).visibility = View.GONE
        cloudUiSyncing = false
    }

    private fun renderCloudStatus(
        schedule: JSONObject?,
        meta: JSONObject?,
        logsArr: JSONArray?,
        err: String?,
    ) {
        cloudUiSyncing = true
        val tvStatus = findViewById<TextView>(R.id.tvCloudStatus)

        if (err != null) {
            tvStatus.text = "云端状态：$err"
            cloudUiSyncing = false
            return
        }
        val enabled = schedule?.optBoolean("enabled", false) ?: false
        val hour = schedule?.optInt("hour", 8) ?: 8
        val minute = schedule?.optInt("minute", 0) ?: 0
        val jitter = schedule?.optInt("jitter_min", 30) ?: 30

        // 服务器为权威源，反向同步本地 prefs
        prefs.edit()
            .putBoolean("cloud_enabled", enabled)
            .putInt("cloud_hour", hour)
            .putInt("cloud_minute", minute)
            .putInt("cloud_jitter", jitter)
            .apply()
        renderCloudTime()
        findViewById<Slider>(R.id.sliderCloudJitter).value = jitter.toFloat()
        findViewById<TextView>(R.id.tvCloudJitterVal).text = "≤ $jitter 分钟"
        findViewById<MaterialSwitch>(R.id.swCloud).isChecked = enabled
        syncRemoteSettings()

        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val sb = StringBuilder("云端状态：")
        val run = schedule?.optJSONObject("today_run")
        if (run == null) {
            sb.append("\n今日执行时刻：等待服务器抽取")
        } else {
            val execAt = try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).parse(run.optString("exec_at"))?.time ?: 0L } catch (_: Throwable) { 0L }
            val when_ = if (execAt > 0) fmt.format(Date(execAt)) else "—"
            val st = run.optString("status", "pending")
            val stText = when (st) {
                "pending" -> "待执行"
                "done" -> "✅ 已代签"
                "checked_in" -> "✅ 今日已签"
                "failed" -> "❌ 失败（${run.optString("detail", "").take(40)}）"
                "skip_no_cred" -> "⚠️ 未上传凭证"
                "skip_token_expired" -> "⚠️ 凭证已过期"
                else -> st
            }
            sb.append("\n今日执行：$when_ · $stText")
        }
        val tokenExp = meta?.optString("token_exp", "") ?: ""
        if (tokenExp.isEmpty()) {
            sb.append("\n凭证：未上传（开启代签后自动上传）")
        } else {
            val expAt = try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).parse(tokenExp)?.time ?: 0L } catch (_: Throwable) { 0L }
            val days = if (expAt > 0) ((expAt - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) else 0
            sb.append(if (meta?.optBoolean("expired", false) == true) {
                "\n⚠️ 凭证已过期：请打开 Trae 重新登录后回到本页"
            } else {
                "\n凭证有效期：约剩 $days 天"
            })
        }
        if (logsArr != null && logsArr.length() > 0) {
            var ok = 0; var already = 0; var failed = 0
            for (i in 0 until logsArr.length()) {
                when (logsArr.optJSONObject(i)?.optString("result")) {
                    "ok" -> ok++; "already" -> already++; "failed" -> failed++
                }
            }
            sb.append("\n近 7 天：✅ $ok · 已签 $already · ❌ $failed")
        }
        tvStatus.text = sb.toString()
        cloudUiSyncing = false
    }

    private fun renderCloudTime() {
        val hour = prefs.getInt("cloud_hour", 8)
        val minute = prefs.getInt("cloud_minute", 0)
        findViewById<TextView>(R.id.tvCloudTime).text = String.format(Locale.CHINA, "%02d:%02d", hour, minute)
    }

    /** 上传云端计划（子线程），完成后回读刷新 */
    private fun putCloudSchedule(enabled: Boolean) {
        if (cloudSubmitting) return
        cloudSubmitting = true
        val hour = prefs.getInt("cloud_hour", 8)
        val minute = prefs.getInt("cloud_minute", 0)
        val jitter = prefs.getInt("cloud_jitter", 30)
        Thread {
            val err = try {
                CloudApi.putSchedule(this, enabled, hour, minute, jitter)
                null
            } catch (t: Throwable) {
                t.message ?: "同步失败"
            }
            cloudSubmitting = false
            mainHandler.post {
                if (!isFinishing) {
                    if (err != null) Toast.makeText(this, "云端同步失败：$err", Toast.LENGTH_SHORT).show()
                    refreshCloudStatus()
                }
            }
        }.start()
    }

    /** su 读取宿主 keva → PUT /v1/credentials（silent=true 静默续传） */
    private fun uploadCredsAsync(silent: Boolean, onDone: (() -> Unit)? = null) {
        Thread {
            val err = try {
                val cred = HostCreds.readViaSu()
                when {
                    !cred.usable -> if (silent) null else "未能读取 Trae 凭证（请确认已授予 su 且 Trae 已登录）"
                    else -> {
                        CloudApi.putCredentials(this, cred.token!!, cred.deviceId!!)
                        null
                    }
                }
            } catch (t: Throwable) {
                t.message
            }
            mainHandler.post {
                if (!isFinishing) {
                    if (!silent && err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    onDone?.invoke()
                }
            }
        }.start()
    }

    private fun runCloudNow() {
        if (cloudSubmitting) return
        cloudSubmitting = true
        Toast.makeText(this, "正在请求云端代签…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val data = CloudApi.runNow(this)
                val status = data.optJSONObject("result")?.optString("status") ?: "?"
                when (status) {
                    "done" -> "✅ 云端代签成功（+${data.optJSONObject("log")?.optInt("delta", 0) ?: 0} 积分）"
                    "checked_in" -> "今日已签到（App 内或此前云端已签）"
                    "failed" -> "❌ 云端代签失败：${data.optJSONObject("log")?.optString("detail", "")?.take(60)}"
                    "skip_no_cred" -> "⚠️ 请先上传凭证（开启代签开关会自动上传）"
                    "skip_token_expired" -> "⚠️ 凭证已过期，请打开 Trae 重新登录"
                    else -> "结果：$status"
                }
            } catch (t: Throwable) {
                "云端代签失败：${t.message ?: "网络异常"}"
            }
            cloudSubmitting = false
            mainHandler.post {
                if (!isFinishing) {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshCloudStatus()
                }
            }
        }.start()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后云端代签将停止，已保存的代签计划保留在服务器。")
            .setPositiveButton("退出") { _, _ ->
                Thread {
                    CloudApi.logout(this)
                    mainHandler.post {
                        if (!isFinishing) {
                            syncRemoteSettings()
                            refreshCloudStatus()
                            Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 手机端签到成功补录：当日宿主状态 lastOkDay=今天 且 云端未签时，
     * 向云端 POST /v1/logs（服务端据此把当日 run 置为已签，避免两端抢签）。
     * 每日只尝试一次（cloud_synced_day 水位）。
     */
    private fun maybePostMobileLog(state: JSONObject?) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        if (state?.optString("lastOkDay", "") != today) return
        if (!TokenManager.isLoggedIn(this)) return
        if (!prefs.getBoolean("cloud_enabled", false)) return
        if (prefs.getString("cloud_synced_day", "") == today) return

        val credits = state.optInt("lastCredits", 0)
        val result = state.optString("lastResult", "")
        Thread {
            val settled = try {
                val runStatus = CloudApi.getSchedule(this)
                    ?.optJSONObject("today_run")?.optString("status") ?: ""
                when (runStatus) {
                    "done", "checked_in" -> true // 云端已签，无需补录
                    else -> {
                        CloudApi.postLog(this, today, "ok", credits.takeIf { it > 0 }, "[手机端] $result")
                        true
                    }
                }
            } catch (_: Throwable) {
                false // 网络失败下次刷新重试
            }
            if (settled) prefs.edit().putString("cloud_synced_day", today).apply()
        }.start()
    }
}
