package com.example.traesignin.hook

import android.content.Context
import android.util.Base64
import com.example.traesignin.util.L
import org.json.JSONObject
import java.io.File

/**
 * Trae 手机端凭证提取（宿主进程内，同 uid 直接读自己 files 目录，无需 root）。
 *
 * - JWT token: files/keva/repo/default/default.blk（Keva 二进制 KV，JWT 明文内嵌，eyJ 开头）
 * - device_id: files/keva/repo/device_id_repo/device_id_repo.blk → key latest_did（纯数字）
 */
internal object Creds {

    private const val TOKEN_BLK = "keva/repo/default/default.blk"
    private const val DID_BLK = "keva/repo/device_id_repo/device_id_repo.blk"

    class Cred(
        val token: String?,
        val deviceId: String?,
        /** JWT exp（毫秒），解析失败为 0 */
        val tokenExpAt: Long,
    ) {
        val usable: Boolean get() = !token.isNullOrBlank() && !deviceId.isNullOrBlank()
    }

    fun load(ctx: Context): Cred {
        val token = readToken(ctx)
        val did = readDeviceId(ctx)
        var exp = 0L
        if (token != null) {
            exp = jwtExpAt(token)
            if (exp > 0) {
                L.i("Creds: token exp=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(exp))}")
            }
        }
        if (token == null) L.w("Creds: 未从 keva 提取到 JWT token")
        if (did == null) L.w("Creds: 未从 keva 提取到 device_id")
        return Cred(token, did, exp)
    }

    // ---- JWT token ----

    private fun readToken(ctx: Context): String? {
        val f = File(ctx.filesDir, TOKEN_BLK)
        if (!f.isFile) return null
        return try {
            parseTokenFromText(String(f.readBytes(), Charsets.ISO_8859_1))
        } catch (t: Throwable) {
            L.e("Creds: 读 token 失败: ${t.message}")
            null
        }
    }

    /**
     * 从 keva blk 文本中提取 JWT（App 进程经 su 读取宿主文件后同样调用）。
     */
    fun parseTokenFromText(text: String): String? {
        // ISO_8859_1 让二进制字节与字符 1:1，JWT ASCII 段不被破坏
        val matches = JWT_REGEX.findAll(text).toList()
        // 优先：keva 块头感知。keva 值按块存储，JWT 值前 4 字节为块头
        // （LE24 值长 + 类型字节），值长精确到字节。贪婪正则第三段会越过值尾
        // 吞入邻条目字节污染签名（1004→1020），服务端拒绝 → code=1001
        for (m in matches) {
            val start = m.range.first
            if (start < 4) continue
            val b0 = text[start - 4].code
            val b1 = text[start - 3].code
            val b2 = text[start - 2].code
            val len = b0 or (b1 shl 8) or (b2 shl 16)
            if (len !in 200..4000 || len > m.value.length) continue
            val cand = m.value.take(len)
            if (isStructurallyValidJwt(cand)) {
                L.i("Creds: keva 块头定位 token @$start len=$len（regex 贪婪匹配为 ${m.value.length}）")
                return cand
            }
        }
        return matches.maxByOrNull { it.value.length }?.value
    }

    /** 三段结构 + payload 可 base64 解码为含 exp 的 JSON（keva 残段/邻条目误匹配会被拒） */
    private fun isStructurallyValidJwt(tok: String): Boolean {
        val parts = tok.split('.')
        if (parts.size != 3) return false
        if (parts.any { it.isEmpty() }) return false
        return try {
            val json = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
            JSONObject(json).has("exp")
        } catch (_: Throwable) { false }
    }

    /** 解析 JWT payload 的 exp（秒 → 毫秒） */
    fun jwtExpAt(token: String): Long = try {
        val parts = token.split('.')
        if (parts.size < 2) 0L else {
            val json = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
            JSONObject(json).optLong("exp", 0L) * 1000L
        }
    } catch (_: Throwable) { 0L }

    // ---- device_id ----

    private fun readDeviceId(ctx: Context): String? {
        val f = File(ctx.filesDir, DID_BLK)
        if (!f.isFile) return null
        return try {
            parseDeviceIdFromText(String(f.readBytes(), Charsets.ISO_8859_1))
        } catch (t: Throwable) {
            L.e("Creds: 读 device_id 失败: ${t.message}")
            null
        }
    }

    /**
     * 从 keva blk 文本中提取 device_id（App 进程经 su 读取宿主文件后同样调用）。
     * Keva blk：key 区（如 latest_did）与 value 数据区分离，专用 repo 里
     * 唯一的 10-20 位纯数字串即 device_id（实测 16 位，位于独立 value 区）。
     */
    fun parseDeviceIdFromText(text: String): String? = try {
        DIGIT_RUN_REGEX.findAll(text)
            .maxWithOrNull(compareBy<MatchResult>({ it.value.length }, { -it.range.first }))
            ?.value
            ?.takeIf { it.length in 10..20 }
    } catch (_: Throwable) { null }

    private val DIGIT_RUN_REGEX = Regex("""\d{10,20}""")

    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
}
