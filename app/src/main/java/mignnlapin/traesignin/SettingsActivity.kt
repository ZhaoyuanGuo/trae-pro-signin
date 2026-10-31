package mignnlapin.traesignin

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.libxposed.service.XposedService
import mignnlapin.traesignin.util.L
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

        // Android 13+ 通知运行时授权（App 启动时 root 静默 grant 的兜底）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

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
            rerenderStatus()
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
                rerenderStatus()
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
                rerenderStatus()
            }
        }

        // ---- ③ 深色主题：跟随系统开关（关闭展开深/浅选择）+ Trae 深色独立开关 ----
        val swDark = findViewById<MaterialSwitch>(R.id.swDark)
        val darkBody = findViewById<View>(R.id.darkBody)
        val tgDark = findViewById<MaterialButtonToggleGroup>(R.id.tgDarkMode)
        val swTraeDark = findViewById<MaterialSwitch>(R.id.swTraeDark)

        val followSystem = prefs.getBoolean("dark_follow_system", true)
        swDark.isChecked = followSystem
        darkBody.visibility = if (followSystem) View.GONE else View.VISIBLE
        tgDark.check(if (prefs.getBoolean("dark_force_dark", false)) R.id.btnDarkDark else R.id.btnDarkLight)
        swTraeDark.isChecked = prefs.getBoolean("trae_dark_enabled", false)

        swDark.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("dark_follow_system", checked).apply()
            if (checked) {
                darkBody.animate().alpha(0f).setDuration(120).withEndAction {
                    darkBody.visibility = View.GONE
                }.start()
            } else {
                darkBody.visibility = View.VISIBLE
                darkBody.alpha = 0f
                darkBody.animate().alpha(1f).setDuration(150).start()
            }
            transitionTheme()
        }
        tgDark.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean("dark_force_dark", tgDark.checkedButtonId == R.id.btnDarkDark).apply()
                transitionTheme()
            }
        }
        swTraeDark.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("trae_dark_enabled", checked).apply()
            syncRemoteSettings()
        }

        // ---- ⑤ 权限设置：先弹说明（用途 + 无权限后果），用户确认后跳转对应系统页面 ----
        findViewById<View>(R.id.rowPermNotify).setOnClickListener { showPermDialog(
            title = "通知权限",
            message = "用途：签到完成后发送结果通知（成功/失败、本次积分、剩余余额）。\n\n" +
                "若不开启：签到仍会正常进行，但你收不到任何结果提示，也无法在通知栏查看余额。",
            action = "去设置"
        ) {
            val granted = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 已授权也允许进入系统页管理（关掉/开横幅等）
                openNotificationSettings()
            } else if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            } else {
                Toast.makeText(this, "Android 13 以下默认有通知权限", Toast.LENGTH_SHORT).show()
            }
        } }
        findViewById<View>(R.id.rowPermAutostart).setOnClickListener { showPermDialog(
            title = "自启动权限",
            message = "用途：到点定时签到需要系统把模块拉起来执行。开启「自启动」后，即使模块已被系统清理，" +
                "签到闹钟到点也能照常工作。\n\n" +
                "若不开启：定时签到可能因模块进程被杀而失效（到点无人执行）。\n\n" +
                "请在接下来的页面中找到本模块并允许「自启动」。",
            action = "去设置"
        ) { openAutostartSettings() } }
        refreshPermRows()

        // ---- ⑤ 立即签到（App 直签；失败回退拉起 Trae，3 分钟后通知结果） ----
        findViewById<View>(R.id.btnSigninNow).setOnClickListener { triggerManualSignin() }

        // ---- ⑥ 导出日志（App 侧持久化文件为主体，hook 侧 log_ring 为附加） ----
        findViewById<View>(R.id.btnExportLog).setOnClickListener { exportLog() }

        playEntranceAnimation(savedInstanceState == null)
        settleThemeMask()
        // 首次进入即同步一次：写入 remote prefs 镜像 + 深色开关落盘（v1.7 首装后文件才存在）
        syncRemoteSettings()
        refreshStatus(force = true)
        refreshServiceStatus()
        // 首次打开检测 Root + LSPosed 授权（缺任一项弹窗引导设置，不满足无法使用）
        checkEnvGate()
    }

    override fun onStart() {
        super.onStart()
        App.addServiceListener(this)
        // 回到前台强制刷新，避免 30s 缓存展示旧状态
        refreshStatus(force = true)
        refreshServiceStatus()
        // 每次打开都刷新积分余额（App 直连 ent_usage 接口，写回后重读展示）
        refreshBalanceAsync()
        // 权限状态可能变化（用户从系统页面返回），重绘权限行
        refreshPermRows()
    }

    override fun onStop() {
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

    /** 定时设置（开关/时间/偏移）变化后立即重绘状态卡：定时行读本地 prefs，用缓存状态重绘即可，不重新 su */
    private fun rerenderStatus() {
        cachedState?.let { if (!isFinishing) renderStatus(it) }
    }

    /** 每次打开页面：App 直连 ent_usage 刷新余额 → 写回宿主状态文件 → 重读展示 */
    private fun refreshBalanceAsync() {
        Thread {
            LocalSigner.refreshBalance(this)
            mainHandler.post { if (!isFinishing) refreshStatus(force = true) }
        }.start()
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
            when {
                lastOkDay == today ->
                    sb.append("今日签到：✅ $lastResult")
                        .append(if (lastCredits > 0) "（+$lastCredits 积分）" else "")
                failCount > 0 ->
                    sb.append("今日签到：⚠️ $lastResult")
                lastResult.isNotEmpty() ->
                    sb.append("上次签到：$lastResult（${fmt.format(Date(lastSigninAt))}）")
                else ->
                    sb.append("签到状态：等待首次签到执行")
            }
            if (prefs.getBoolean("sched_enabled", false)) {
                sb.append("\n定时签到：每日 ")
                    .append(String.format(Locale.CHINA, "%02d:%02d",
                        prefs.getInt("sched_hour", 8), prefs.getInt("sched_minute", 0)))
                val nl = prefs.getLong("next_launch_at", 0L)
                if (nl > System.currentTimeMillis()) {
                    sb.append(" · 下次执行 ")
                        .append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(nl)))
                }
                sb.append("（随机偏移 ≤ ").append(prefs.getInt("sched_offset_min", 30)).append(" 分钟）")
            }
            tvHookStatus.text = sb.toString()
        } else {
            tvHookStatus.text =
                "无法读取宿主状态文件。\n请打开 Trae App 触发一次模块运行；若仍无效，请在 Magisk 中授予本模块 su 权限。"
        }
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
                .putBoolean("trae_dark", prefs.getBoolean("trae_dark_enabled", false))
                .putBoolean("sched_enabled", prefs.getBoolean("sched_enabled", false))
                .putInt("sched_hour", prefs.getInt("sched_hour", 8))
                .putInt("sched_minute", prefs.getInt("sched_minute", 0))
                .putInt("sched_offset_min", prefs.getInt("sched_offset_min", 30))
                .apply()
        } catch (_: Throwable) { }
        // 定时设置可能变化，重新布防到点拉起 Trae 的闹钟
        SchedLauncher.arm(this)
        // 深色开关落盘到宿主 filesDir：hook 进程启动即 File 预读（同 uid，无框架就绪延迟），
        // 确保 Trae 冷启动首屏就是深色（remote prefs 首读未就绪会误报 false 导致等 ~13s）
        writeDarkFlag(prefs.getBoolean("trae_dark_enabled", false))
    }

    /** 应用模块自身深浅：跟随系统 → FOLLOW_SYSTEM；否则按手动选择 YES/NO（触发 recreate） */
    private fun applyAppTheme() {
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.getBoolean("dark_follow_system", true)) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            } else if (prefs.getBoolean("dark_force_dark", false)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    /**
     * 主题切换过渡：先淡入同底色遮罩盖住界面，再切换深浅（recreate），
     * 新界面 onCreate 末尾检测标记后淡出——交叉淡入淡出，消除生硬跳变。
     */
    private fun transitionTheme() {
        val mask = findViewById<View>(R.id.themeMask)
        mask.visibility = View.VISIBLE
        mask.alpha = 0f
        mask.animate().alpha(1f).setDuration(120).withEndAction {
            prefs.edit().putBoolean("theme_mask_up", true).apply()
            applyAppTheme()
        }.start()
    }

    /** recreate 后主题遮罩淡出（配合 transitionTheme） */
    private fun settleThemeMask() {
        if (!prefs.getBoolean("theme_mask_up", false)) return
        prefs.edit().putBoolean("theme_mask_up", false).apply()
        val mask = findViewById<View>(R.id.themeMask)
        mask.visibility = View.VISIBLE
        mask.alpha = 1f
        mask.postDelayed({
            mask.animate().alpha(0f).setDuration(180).withEndAction {
                mask.visibility = View.GONE
            }.start()
        }, 80)
    }

    /** 深色开关写入宿主 filesDir 的 trae_dark_flag（"1"/"0"，su 覆盖写） */
    private fun writeDarkFlag(dark: Boolean) {
        Thread {
            try {
                val v = if (dark) "1" else "0"
                Runtime.getRuntime().exec(arrayOf(
                    "su", "-mm", "-c",
                    "printf %s $v > /data/data/com.bytedance.trae.cn/files/trae_dark_flag"
                )).waitFor()
            } catch (_: Throwable) { }
        }.start()
    }

    private fun refreshServiceStatus() {
        // 服务状态读的是已绑定的 LSPosed 服务对象，不涉及 su，主线程安全
        val tv = findViewById<TextView>(R.id.tvServiceStatus)
        val service = App.service
        tv.text = if (service == null) {
            "LSPosed 服务：未连接（框架未激活本模块）"
        } else {
            "LSPosed 已连接：${service.frameworkName} ${service.frameworkVersion}"
        }
    }

    // ---- 权限设置 ----

    /** 权限说明对话框（M3 样式）：向用户说明权限用途与无权限后果，确认后才跳系统页面 */
    private fun showPermDialog(title: String, message: String, action: String, onGo: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onGo() }
            .setNegativeButton("暂不", null)
            .show()
    }

    /** 通知权限行状态刷新（Android 13+ 运行时权限，可真实检测） */
    private fun refreshPermRows() {
        val tv = findViewById<TextView>(R.id.tvPermNotifyState)
        val granted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        tv.text = if (granted) "已授权" else "未授权"
        tv.setTextColor(getColor(if (granted) R.color.ok_text else R.color.err_text))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshPermRows()
    }

    /** 跳系统通知设置页（已授权时也可进入管理）：应用专属通知设置（全 ROM 通用），失败回退应用详情 */
    private fun openNotificationSettings() {
        try {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            }.also { startActivity(it) }
            return
        } catch (_: Throwable) { }
        try {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }.also { startActivity(it) }
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开设置页：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 跳转自启动设置：直接跳通用应用详情页（所有 ROM 都有，MIUI 的应用信息里
     * 即含「自启动」开关），不针对特定厂商硬编码。
     */
    private fun openAutostartSettings() {
        try {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }.also { startActivity(it) }
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开设置页：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 立即签到：模块 App 直接请求签到接口（凭证经 root 从 Trae 数据读取），
     * 不拉起 Trae；直签失败（凭证不可用/网络异常）回退拉起 Trae 走 hook 引擎
     * （不压回桌面——用户正看着屏幕，3 分钟后检查结果并通知）。
     * 成功/失败均 Toast 提示（成功带余额），成功另有系统通知（含"今日已签"场景）。
     */
    // ---- 环境门槛检测（root + LSPosed） ----

    @Volatile private var gateDialogShowing = false

    /**
     * 首次打开检测 Root 与 LSPosed 授权（自启动/通知不在此列——不授也能用）。
     * 缺任一项直接弹不可关闭对话框说明并引导去设置；延迟 1.5s 等服务绑定避免误报。
     */
    private fun checkEnvGate() {
        if (gateDialogShowing || isFinishing) return
        mainHandler.postDelayed({
            if (gateDialogShowing || isFinishing) return@postDelayed
            Thread {
                val rootOk = checkRoot()
                mainHandler.post {
                    if (gateDialogShowing || isFinishing) return@post
                    val lspOk = App.service != null
                    if (rootOk && lspOk) return@post
                    gateDialogShowing = true
                    val missing = when {
                        !rootOk && !lspOk -> "Root 权限与 LSPosed 授权"
                        !rootOk -> "Root 权限"
                        else -> "LSPosed 授权"
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle("无法使用")
                        .setMessage(
                            "本模块需要 $missing 才能工作。\n\n" +
                                "· Root（Magisk 授予 su）：读取 Trae 凭证完成直接签到\n" +
                                "· LSPosed（激活模块并勾选作用域）：注入 Trae\n\n" +
                                "缺少任一项签到功能均无法运行，请前往设置后返回重试。"
                        )
                        .setCancelable(false)
                        .setPositiveButton("去设置") { _, _ ->
                            gateDialogShowing = false
                            if (!rootOk) openMagisk() else openLsposed()
                        }
                        .setNegativeButton("重试") { _, _ ->
                            gateDialogShowing = false
                            checkEnvGate()
                        }
                        .show()
                }
            }.start()
        }, 1500)
    }

    /** su 可用性检测：su -c id 退出码 0 且输出 uid=0 */
    private fun checkRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        p.exitValue() == 0 && out.contains("uid=0")
    } catch (_: Throwable) {
        false
    }

    private fun openMagisk() {
        try {
            startActivity(packageManager.getLaunchIntentForPackage("com.topjohnwu.magisk")
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            Toast.makeText(this, "请手动打开 Magisk 为本模块授予 Root", Toast.LENGTH_LONG).show()
        }
    }

    private fun openLsposed() {
        try {
            startActivity(Intent().apply {
                setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            Toast.makeText(this, "请手动打开 LSPosed 管理器激活本模块并勾选作用域", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerManualSignin() {
        Toast.makeText(this, "正在直接签到（无需打开 Trae）…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = LocalSigner.signNow(this)
            if (!r.ok) {
                SchedLauncher.launchTrae(this, pressHomeAfter = false)
                SchedLauncher.armResultCheck(this, 3 * 60_000L)
                mainHandler.post {
                    Toast.makeText(this, "直签失败，已拉起 Trae 补签，稍后通知结果", Toast.LENGTH_LONG).show()
                }
            } else {
                // Toast 直接带余额，不用再点开通知看
                val balText = when {
                    r.balance == -2.0 -> "剩余 ∞ 积分"
                    r.balance != null -> "剩余 ${r.balance!!.toInt()} 积分"
                    else -> "余额查询失败"
                }
                mainHandler.post {
                    Toast.makeText(this, "${r.message} · $balText", Toast.LENGTH_LONG).show()
                }
            }
            mainHandler.post { refreshStatus(force = true) }
        }.start()
    }

    /**
     * 导出日志（保存到 Download 目录）：日志已移到模块软件自身存储。
     * 主体 = App 进程持久化文件（files/logs/app_*.log，L 在 App 进程实时落盘，
     * 直签/余额/定时布防都在这里）；附加 = hook 侧日志镜像（LSPosed 框架侧 remote
     * prefs 的 log_ring，Trae 进程被杀后仍可读，标注区分）。全程不依赖 Trae 运行。
     */
    private fun exportLog() {
        val appLog = L.readPersisted()
        var hookLog: String? = null
        val service = App.service
        if (service != null) {
            hookLog = try {
                service.getRemotePreferences(STATE_PREFS).getString("log_ring", null)
                    ?.takeIf { it.isNotEmpty() && it != "（暂无日志）" }
            } catch (_: Throwable) {
                null
            }
        }
        if (appLog.isBlank() && hookLog == null) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("===== 模块 App 侧日志（App 进程持久化） =====\n")
        sb.append(if (appLog.isBlank()) "（暂无）\n" else appLog.trimEnd() + "\n")
        sb.append("\n===== hook 侧日志（Trae 进程镜像） =====\n")
        sb.append(hookLog?.trimEnd() ?: "（LSPosed 服务未连接或暂无镜像）")
        saveLogToDownloads(sb.toString())
    }

    /**
     * 日志落盘到公共 Download 目录（MediaStore.Downloads，Android 10+ 无需存储权限），
     * 文件名带时间戳，保存完成后 Toast 提示路径。
     */
    @SuppressLint("NewApi")
    private fun saveLogToDownloads(text: String) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "仅支持 Android 10+ 保存到 Download", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val name = "traesignin_log_${SimpleDateFormat("MMdd_HHmmss", Locale.CHINA).format(Date())}.txt"
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("创建文件失败")
                contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("输出流为空")
                mainHandler.post {
                    Toast.makeText(this, "日志已保存到 Download/$name", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    Toast.makeText(this, "保存失败：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
