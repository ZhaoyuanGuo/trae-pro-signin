package mignnlapin.traesignin

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 定时签到自动拉起（修复：签到时间到了 Trae 未运行时无人干活）。
 *
 * 根因：签到引擎与定时闹钟全部挂在 Trae 宿主进程内，Trae 被杀后闹钟失效、
 * hook 代码不存在，到点后没有任何组件会启动 Trae。
 *
 * 方案：模块 App 侧静态注册 BroadcastReceiver（进程被杀系统也会拉起执行），
 * AlarmManager 到点后：
 *   1. su 读宿主状态文件，当日已签 → 跳过（不白白干活）
 *   2. 模块 App 直签（LocalSigner：su 读凭证 → 直接请求签到接口），不拉起 Trae
 *   3. 直签失败（凭证/网络）→ 回退 su monkey 拉起 Trae，hook 引擎启动补签兜底
 *
 * 触发后自动重新布防。偏移量与 hook 侧独立抽取（两边各自随机，互不影响：
 * 提前拉起 → 引擎 startup 补签兜底；延后拉起 → 拉起即签）。
 */
object SchedLauncher {

    private const val ACTION_LAUNCH = "mignnlapin.traesignin.LAUNCH_SCHED"
    private const val ACTION_RESULT_CHECK = "mignnlapin.traesignin.RESULT_CHECK"
    private const val REQUEST_CODE = 3001
    private const val RESULT_CHECK_RC = 3002
    private const val CHANNEL_RESULT = "trae_sched_result"
    private const val TARGET_PKG = "com.bytedance.trae.cn"
    private const val STATE_FILE_PATH = "/data/data/com.bytedance.trae.cn/files/trae_signin_state.json"

    /** 布防 / 取消布防（设置变化、App 启动、闹钟触发后调用） */
    fun arm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context)
        val p = context.getSharedPreferences("trae_signin_prefs", 0)
        if (!p.getBoolean("sched_enabled", false)) {
            am.cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))
        // 每日抽一次随机偏移（当日进程重启不变）
        if (p.getString("sched_launch_day", "") != today) {
            val max = p.getInt("sched_offset_min", 30).coerceIn(0, 120)
            val offset = if (max > 0) Random.nextInt(0, max + 1) else 0
            p.edit().putString("sched_launch_day", today).putInt("sched_launch_offset", offset).apply()
        }
        val offset = p.getInt("sched_launch_offset", 0)
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt("sched_hour", 8).coerceIn(0, 23))
            set(Calendar.MINUTE, p.getInt("sched_minute", 0).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fireAt = if (base.timeInMillis > now) base.timeInMillis
        else if (p.getString("sched_catchup_day", "") != today &&
            readHostState()?.optString("lastOkDay", "") != today
        ) {
            // 今日目标已过且尚未签（开机太晚/进程长期未活）：1 分钟后补拉，当日仅补一次
            p.edit().putString("sched_catchup_day", today).apply()
            now + 60_000L
        } else base.apply { add(Calendar.DATE, 1) }.timeInMillis + offset * 60_000L
        try {
            // setAlarmClock：闹钟级语义（通知栏出现闹钟图标），MIUI 不按"自启动"限制拦截，
            // 应用被杀系统也会拉起进程执行广播，且不要求 SCHEDULE_EXACT_ALARM 权限；
            // 普通 setExactAndAllowWhileIdle 会被 MIUI 的自启动管控静默吞掉
            val showPi = PendingIntent.getActivity(
                context, 3002,
                Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, showPi), pi)
        } catch (_: Throwable) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } catch (_: Throwable) {
                try {
                    am.setWindow(AlarmManager.RTC_WAKEUP, fireAt, 15 * 60_000L, pi)
                } catch (_: Throwable) {
                    am.set(AlarmManager.RTC_WAKEUP, fireAt, pi)
                }
            }
        }
        // 记录下次执行时间（GUI 状态卡显示，替代 hook 侧陈旧的 nextSchedAt）
        p.edit().putLong("next_launch_at", fireAt).apply()
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(ACTION_LAUNCH).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 开机完成：恢复定时布防（此前重启后模块 App 不活过一次，闹钟一直缺失）
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                try { arm(context) } catch (_: Throwable) { }
                return
            }
            // 拉起后的结果检查：以模块自身身份通知签到结果
            if (intent.action == ACTION_RESULT_CHECK) {
                val r2 = goAsync()
                Thread {
                    try { reportResult(context) } catch (_: Throwable) { } finally { r2.finish() }
                }.start()
                return
            }
            if (intent.action != ACTION_LAUNCH) return
            val result = goAsync()
            Thread {
                try {
                    launchIfNeeded(context)
                } catch (_: Throwable) {
                } finally {
                    try { SchedLauncher.arm(context) } catch (_: Throwable) { }
                    result.finish()
                }
            }.start()
        }
    }

    /** 当日未签：模块 App 直签（不拉起 Trae）；直签失败回退拉起 Trae 走 hook 引擎 */
    private fun launchIfNeeded(context: Context) {
        val p = context.getSharedPreferences("trae_signin_prefs", 0)
        if (!p.getBoolean("sched_enabled", false)) return
        // 当日已签则不处理（状态读取失败视为未签，保守执行）
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val state = readHostState()
        if (state?.optString("lastOkDay", "") == today) return

        if (!LocalSigner.signNow(context).ok) {
            // 直签失败（凭证不可用/网络异常）：回退拉起 Trae，3 分钟后检查结果并通知
            launchTrae(context)
            armResultCheck(context)
        }
    }

    /**
     * 拉起 Trae（su monkey 优先，回退 getLaunchIntentForPackage）；供定时与手动"立即签到"共用。
     * pressHomeAfter：拉起成功后是否按 HOME 压回后台——定时签到 true（后台静默完成）；
     * 手动"立即签到"传 false（用户正看着屏幕，Trae 留在前台展示签到过程，不退回桌面）。
     */
    fun launchTrae(context: Context, pressHomeAfter: Boolean = true) {
        // su + monkey：root 身份拉起，不受后台启动 Activity 限制
        var ok = false
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("su", "-mm", "-c", "monkey -p $TARGET_PKG -c android.intent.category.LAUNCHER 1")
            )
            proc.waitFor()
            ok = proc.exitValue() == 0
        } catch (_: Throwable) { }
        if (ok) {
            // 拉起到后台完成签到：进程已活（hook 会注入），1.5s 后 HOME 压回，前台停留最小化
            if (pressHomeAfter) pressHome()
            return
        }
        // 回退：普通启动（模块处于前台或已授权悬浮窗时可成功）
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TARGET_PKG)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
        } catch (_: Throwable) { }
    }

    /** 拉起 1.5s 后按 HOME 把 Trae 压回后台（root 可注入按键） */
    private fun pressHome() {
        Thread {
            try {
                Thread.sleep(1500)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent KEYCODE_HOME")).waitFor()
            } catch (_: Throwable) { }
        }.start()
    }

    /** 布防结果检查：delayMs 后读宿主状态文件，以模块身份通知签到结果 */
    fun armResultCheck(context: Context, delayMs: Long = 180_000L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, RESULT_CHECK_RC,
            Intent(ACTION_RESULT_CHECK).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + delayMs
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Throwable) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** 读宿主状态文件并通知本次拉起的签到结果（成功 / 未完成）；读取失败也必须通知，保证结果闭环 */
    private fun reportResult(context: Context) {
        val state = readHostState()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        if (state == null) {
            notifyResult(context, false, "无法读取签到状态，请打开 Trae 查看")
            return
        }
        val credits = state.optInt("lastCredits", 0)
        val result = state.optString("lastResult", "")
        if (state.optString("lastOkDay", "") == today) {
            notifyResult(context, true,
                (if (credits > 0) "+$credits 积分 · " else "") + result)
        } else {
            notifyResult(context, false,
                result.ifEmpty { "拉起后未见签到成功记录，可打开 Trae 查看" })
        }
    }

    /** 模块 App 自身身份发通知（不依赖 Trae 通知权限；权限由 App 启动时 root 静默授予）。LocalSigner 完成后亦调用 */
    fun notifyResult(context: Context, ok: Boolean, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_RESULT, "签到结果", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val openSelf = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val pi = PendingIntent.getActivity(
                context, 2004, openSelf,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
            val n = Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(icon)
                .setContentTitle(if (ok) "TRAE 签到成功" else "TRAE 签到未完成")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(3001, n)
        } catch (_: Throwable) { }
    }

    private fun readHostState(): JSONObject? = try {
        // -mm：切到 Magisk 全局 mount namespace（HyperOS 命名空间隔离，缺它读不到宿主目录）
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat $STATE_FILE_PATH"))
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val trimmed = out.trim()
        if (trimmed.startsWith("{")) JSONObject(trimmed) else null
    } catch (_: Throwable) { null }
}
