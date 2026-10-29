package com.local.ktv

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

internal object IgebaAccountDialog {
    private val io = Executors.newSingleThreadExecutor()
    fun show(activity: Activity, changed: () -> Unit) {
        val current = IgebaAccount.session
        if (current != null) {
            AlertDialog.Builder(activity).setTitle("i歌霸账号")
                .setMessage("当前账号：${current.nickname}\n账号编号：${current.userId}\n会话已加密保存，重启后自动恢复；服务方使会话失效时需要重新登录。")
                .setPositiveButton("关闭", null)
                .setNeutralButton("退出登录") { _, _ -> IgebaAccount.logout(); changed() }
                .setNegativeButton("切换账号") { _, _ -> form(activity, false, changed) }.show()
        } else form(activity, false, changed)
    }

    private fun form(activity: Activity, registration: Boolean, changed: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        fun input(label: String, type: Int) = EditText(activity).apply {
            hint = label
            inputType = type
            isSingleLine = true
            isSaveEnabled = false
            body.addView(this)
        }
        val account = input(if (registration) "中国大陆手机号" else "手机号、邮箱或用户名",
            if (registration) InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT)
        val password = input("密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val code = if (registration) input("短信验证码", InputType.TYPE_CLASS_NUMBER) else null
        val status = TextView(activity).apply {
            text = "密码不会保存；登录会话加密保存在本机，重启后自动恢复。"
            body.addView(this)
        }
        val send = if (registration) Button(activity).apply { text = "获取验证码"; body.addView(this) } else null
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (registration) "注册 i歌霸账号" else "登录 i歌霸")
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton(if (registration) "注册" else "登录", null)
            .setNeutralButton(if (registration) "返回登录" else "注册账号", null)
            .setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val switch = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            var busy = false
            var registrationCompleted = false
            val main = Handler(Looper.getMainLooper())
            fun work(action: () -> String, success: () -> Unit = {}) {
                if (busy) return
                busy = true
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                submit.isEnabled = false
                switch.isEnabled = false
                send?.isEnabled = false
                status.text = "正在请求..."
                io.execute {
                    val result = runCatching(action)
                    main.post {
                        if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@post
                        busy = false
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        submit.isEnabled = true
                        switch.isEnabled = true
                        send?.isEnabled = true
                        if (registrationCompleted) submit.text = "登录"
                        status.text = result.getOrElse {
                            val reason = it.message?.take(160) ?: "账号服务请求失败"
                            if (registrationCompleted) "注册成功，自动登录失败：$reason；可直接重试登录。" else reason
                        }
                        if (result.isSuccess) success()
                    }
                }
            }
            switch.setOnClickListener {
                dialog.dismiss()
                form(activity, !registration, changed)
            }
            send?.setOnClickListener {
                val phone = account.text.toString().trim()
                work({ IgebaAccount.sendCode(phone); "验证码已发送，请查收；120 秒后可重新获取。" })
            }
            submit.setOnClickListener {
                val name = account.text.toString().trim()
                val secret = password.text.toString()
                val verification = code?.text?.toString().orEmpty()
                if (registration) {
                    work({
                        if (!registrationCompleted) {
                            IgebaAccount.register(name, secret, verification)
                            registrationCompleted = true
                        }
                        IgebaAccount.login(name, secret)
                        "注册并登录成功"
                    }, {
                        password.text.clear()
                        code?.text?.clear()
                        dialog.dismiss()
                        changed()
                    })
                } else {
                    work({ IgebaAccount.login(name, secret); "登录成功" }, {
                        password.text.clear()
                        dialog.dismiss()
                        changed()
                    })
                }
            }
            dialog.setOnDismissListener { password.text.clear(); code?.text?.clear() }
        }
        dialog.show()
    }
}
