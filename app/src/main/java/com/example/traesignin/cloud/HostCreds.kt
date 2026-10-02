package com.example.traesignin.cloud

import com.example.traesignin.hook.Creds

/**
 * App 进程读取宿主（Trae 手机端）凭证：su -mm cat keva blk 文件 → 复用 Creds 解析。
 * 供云端凭证上传使用（PUT /v1/credentials）。
 */
object HostCreds {

    private const val HOST_FILES = "/data/data/com.bytedance.trae.cn/files"
    private const val TOKEN_BLK = "$HOST_FILES/keva/repo/default/default.blk"
    private const val DID_BLK = "$HOST_FILES/keva/repo/device_id_repo/device_id_repo.blk"

    data class Cred(
        val token: String?,
        val deviceId: String?,
        /** JWT exp（毫秒），解析失败为 0 */
        val expAt: Long,
    ) {
        val usable: Boolean get() = !token.isNullOrBlank() && !deviceId.isNullOrBlank()
    }

    /** 同步读取（阻塞 su，调用方须在子线程） */
    fun readViaSu(): Cred {
        val token = runSuCat(TOKEN_BLK)?.let { Creds.parseTokenFromText(it) }
        val did = runSuCat(DID_BLK)?.let { Creds.parseDeviceIdFromText(it) }
        val exp = token?.let { Creds.jwtExpAt(it) } ?: 0L
        return Cred(token, did, exp)
    }

    /** cat 二进制文件：按字节原样读出后以 ISO_8859_1 还原（1 字节 = 1 字符，不破坏 JWT ASCII 段） */
    private fun runSuCat(path: String): String? = try {
        // -mm：切到 Magisk 全局 mount namespace（HyperOS 给 app 进程做了命名空间隔离）
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "cat $path"))
        val bytes = proc.inputStream.readBytes()
        proc.waitFor()
        if (proc.exitValue() == 0) String(bytes, Charsets.ISO_8859_1) else null
    } catch (_: Throwable) {
        null
    }
}
