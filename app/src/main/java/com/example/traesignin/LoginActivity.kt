package com.example.traesignin

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.traesignin.cloud.CloudApi
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 友爱账号（Uniai）登录 / 注册（手机号 + 密码，一套账号多应用通用）。
 * 登录成功后返回设置页，由设置页负责凭证上传与云端代签配置。
 */
class LoginActivity : AppCompatActivity() {

    private var registerMode = false
    private var submitting = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tilPhone: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilConfirm: TextInputLayout
    private lateinit var etPhone: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirm: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var tvToggleMode: TextView
    private lateinit var btnSubmit: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = WindowInsetsCompat.Type.systemBars()
            v.setPadding(insets.getInsets(bars).left, insets.getInsets(bars).top,
                insets.getInsets(bars).right, insets.getInsets(bars).bottom)
            insets
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = ""
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tilPhone = findViewById(R.id.tilPhone)
        tilPassword = findViewById(R.id.tilPassword)
        tilConfirm = findViewById(R.id.tilConfirm)
        etPhone = findViewById(R.id.etPhone)
        etPassword = findViewById(R.id.etPassword)
        etConfirm = findViewById(R.id.etConfirm)
        tvError = findViewById(R.id.tvError)
        tvToggleMode = findViewById(R.id.tvToggleMode)
        btnSubmit = findViewById(R.id.btnSubmit)
        progress = findViewById(R.id.progress)

        btnSubmit.setOnClickListener { submit() }
        tvToggleMode.setOnClickListener { setMode(!registerMode) }

        playEntrance()
    }

    private fun setMode(register: Boolean) {
        registerMode = register
        tilConfirm.visibility = if (register) View.VISIBLE else View.GONE
        btnSubmit.text = if (register) "注册并登录" else "登录"
        tvToggleMode.text =
            if (register) "已有账号？返回登录" else "还没有账号？注册一个"
        tvError.visibility = View.GONE
    }

    private fun validate(): Boolean {
        val phone = etPhone.text?.toString()?.trim().orEmpty()
        val pass = etPassword.text?.toString().orEmpty()
        var ok = true
        if (!Regex("^1\\d{10}$").matches(phone)) {
            tilPhone.error = "手机号格式不正确"
            ok = false
        } else tilPhone.error = null
        if (pass.length !in 8..64) {
            tilPassword.error = "密码需为 8-64 位"
            ok = false
        } else tilPassword.error = null
        if (registerMode && etConfirm.text?.toString() != pass) {
            tilConfirm.error = "两次输入的密码不一致"
            ok = false
        } else tilConfirm.error = null
        return ok
    }

    private fun submit() {
        if (submitting) return
        if (!validate()) return
        submitting = true
        setError(null)
        progress.visibility = View.VISIBLE
        btnSubmit.isEnabled = false

        val phone = etPhone.text?.toString()?.trim().orEmpty()
        val pass = etPassword.text?.toString().orEmpty()

        Thread {
            val result = try {
                if (registerMode) {
                    CloudApi.register(phone, pass) // 已注册会被服务端拒绝，走登录分支
                }
                CloudApi.login(applicationContext, phone, pass)
                Result.success(Unit)
            } catch (t: Throwable) {
                // 注册模式下的“手机号已注册”降级为提示
                Result.failure(t)
            }
            mainHandler.post {
                submitting = false
                progress.visibility = View.GONE
                btnSubmit.isEnabled = true
                result.fold(onSuccess = {
                    Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, Intent())
                    finish()
                }, onFailure = { t ->
                    val msg = when {
                        t is CloudApi.ApiException && t.code == 4 -> "该手机号已注册，请切换到登录"
                        t is CloudApi.ApiException && t.code == 2 -> "手机号或密码错误"
                        t is CloudApi.ApiException && t.code == 3 -> t.message ?: "请求过于频繁，稍后再试"
                        t.message?.contains("注册", true) == true -> t.message!!
                        else -> "登录失败：${t.message ?: "网络异常，请检查网络"}"
                    }
                    setError(msg)
                })
            }
        }.start()
    }

    private fun setError(msg: String?) {
        if (msg == null) {
            tvError.visibility = View.GONE
        } else {
            tvError.text = msg
            tvError.visibility = View.VISIBLE
        }
    }

    private fun playEntrance() {
        val views = listOf<View>(
            findViewById(R.id.headerSection),
            findViewById(R.id.cardForm),
            findViewById(R.id.tvToggleMode),
            findViewById(R.id.tvFooter),
        )
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 40f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(50L * i)
                .setDuration(340L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }
}
