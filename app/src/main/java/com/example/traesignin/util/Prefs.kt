package com.example.traesignin.util

/**
 * 跨进程偏好读取。
 * libxposed (API 102) 进程无经典 Xposed API，XSharedPreferences 通过反射加载。
 */
object Prefs {
    @Volatile
    private var xsp: Any? = null
    private val lock = Any()

    private fun obtain(): Any? {
        if (xsp == null) {
            synchronized(lock) {
                if (xsp == null) {
                    xsp = try {
                        val cls = Class.forName("de.robv.android.xposed.XSharedPreferences")
                        cls.getConstructor(String::class.java, String::class.java)
                            .newInstance("com.example.traesignin", "trae_signin_prefs")
                    } catch (_: Throwable) { null }
                }
            }
        }
        return xsp
    }

    @Suppress("unused")
    fun init(moduleContext: android.content.Context?) { /* 兼容保留：反射已无需 init */ }

    private fun callGet(name: String, paramTypes: Array<Class<*>>, args: Array<Any?>): Any? = try {
        val p = obtain() ?: return null
        p.javaClass.getMethod("reload").invoke(p)
        p.javaClass.getMethod(name, *paramTypes).invoke(p, *args)
    } catch (_: Throwable) { null }

    fun isSignInEnabled(): Boolean =
        (callGet("getBoolean", arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!),
            arrayOf("signin_enabled", true)) as? Boolean) ?: true

    fun lastSignInAt(): Long =
        (callGet("getLong", arrayOf(String::class.java, Long::class.javaPrimitiveType!!),
            arrayOf("last_signin_at", 0L)) as? Long) ?: 0L

    /** Hook 工作模式：EXPLORER / PASSIVE / MONITOR */
    fun hookMode(): String =
        (callGet("getString", arrayOf(String::class.java, String::class.java),
            arrayOf("hook_mode", "EXPLORER")) as? String) ?: "EXPLORER"

    @Synchronized
    fun writeDirectly(context: android.content.Context?, key: String, value: Any?) {
        if (context == null) return
        val editor = context.getSharedPreferences("trae_signin_prefs", 0).edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
            null -> editor.remove(key)
        }
        editor.apply()
    }
}
