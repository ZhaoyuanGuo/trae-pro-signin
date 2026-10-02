package com.example.traesignin.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 云端登录凭证存储（access/refresh token）。
 *
 * 首选 EncryptedSharedPreferences（AES256-GCM，硬件 Keystore 派生密钥）；
 * 个别设备 Keystore 异常时降级为应用私有普通 SharedPreferences（仍不出应用沙箱）。
 */
object TokenManager {

    private const val PREFS_NAME = "trae_cloud_auth"
    private const val PLAIN_FALLBACK = "trae_cloud_auth_plain"

    private const val K_ACCESS = "access_token"
    private const val K_REFRESH = "refresh_token"
    private const val K_USER_ID = "user_id"
    private const val K_PHONE = "phone_masked"
    private const val K_EXPIRES_AT = "access_expires_at"

    @Volatile
    private var cached: SharedPreferences? = null

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        val p = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Throwable) {
            context.applicationContext.getSharedPreferences(PLAIN_FALLBACK, 0)
        }
        cached = p
        return p
    }

    fun isLoggedIn(context: Context): Boolean =
        !accessToken(context).isNullOrBlank() && !refreshToken(context).isNullOrBlank()

    fun accessToken(context: Context): String? =
        prefs(context).getString(K_ACCESS, null)

    fun refreshToken(context: Context): String? =
        prefs(context).getString(K_REFRESH, null)

    fun userId(context: Context): Int = prefs(context).getInt(K_USER_ID, 0)

    fun phoneMasked(context: Context): String? = prefs(context).getString(K_PHONE, null)

    /** access_token 过期时刻（毫秒），0 = 未知 */
    fun accessExpiresAt(context: Context): Long =
        prefs(context).getLong(K_EXPIRES_AT, 0L)

    /** 登录成功落库（CloudApi /v1/login 的 data） */
    fun saveLogin(context: Context, data: org.json.JSONObject) {
        val user = data.optJSONObject("user")
        prefs(context).edit()
            .putString(K_ACCESS, data.optString("access_token"))
            .putString(K_REFRESH, data.optString("refresh_token"))
            .putInt(K_USER_ID, user?.optInt("id") ?: 0)
            .putString(K_PHONE, user?.optString("phone_masked") ?: "")
            .putLong(K_EXPIRES_AT, System.currentTimeMillis() + data.optLong("expires_in", 0L) * 1000L)
            .apply()
    }

    /** 刷新轮换后更新 token 对（保持用户信息不变） */
    fun updateTokens(context: Context, data: org.json.JSONObject) {
        prefs(context).edit()
            .putString(K_ACCESS, data.optString("access_token"))
            .putString(K_REFRESH, data.optString("refresh_token"))
            .putLong(K_EXPIRES_AT, System.currentTimeMillis() + data.optLong("expires_in", 0L) * 1000L)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
