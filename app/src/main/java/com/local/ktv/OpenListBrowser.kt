package com.local.ktv

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService

/** 网盘配置入口；扫描、检索和保存复用统一歌曲流程。 */
class OpenListBrowser(
    private val activity: Activity,
    private val executor: ExecutorService,
    private val onChanged: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = mutableListOf<AlertDialog>()

    fun close() {
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
        main.removeCallbacksAndMessages(null)
    }

    private fun present(dialog: AlertDialog) {
        dialogs.removeAll { !it.isShowing }
        dialogs.add(dialog)
        dialog.show()
        dialog.window?.decorView?.let(TvFocusStyler::installTree)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .mapNotNull { dialog.getButton(it) }.forEach(TvFocusStyler::installAction)
    }
    private val preferences get() = activity.getSharedPreferences("openlist", Activity.MODE_PRIVATE)

    fun config(): OpenListConfig = OpenListConfig(
        preferences.getString("server", "").orEmpty(),
        preferences.getString("token", "").orEmpty(),
        preferences.getString("root", "/").orEmpty(),
        preferences.getString("password", "").orEmpty(),
    )

    fun show() = configure()

    private fun configure() {
        val saved = config()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        fun input(label: String, value: String, secret: Boolean = false): EditText {
            layout.addView(TextView(activity).apply { text = label })
            return EditText(activity).apply {
                setSingleLine(true)
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT
                setText(value)
                layout.addView(this)
            }
        }
        val server = input("服务地址（如 http://192.168.1.10:5244）", saved.server)
        val token = input("访问令牌（访客访问可留空）", saved.token, true)
        val root = input("歌曲目录（如 /KTV）", saved.root)
        val password = input("目录密码（可留空）", saved.password, true)
        val dialog = AlertDialog.Builder(activity).setTitle("OpenList 连接设置")
            .setView(android.widget.ScrollView(activity).apply { addView(layout) }).setPositiveButton("连接并保存", null)
            .setNeutralButton("清除连接", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                preferences.edit().clear().apply()
                dialog.dismiss()
                onChanged()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = runCatching {
                    OpenListConfig(OpenListPaths.server(server.text.toString()), token.text.toString().trim(),
                        OpenListPaths.normalize(root.text.toString()), password.text.toString())
                }.getOrElse { toast(it.message ?: "连接设置无效"); return@setOnClickListener }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                button.text = "正在连接…"
                executor.execute {
                    val result = runCatching { OpenListClient(candidate).list(candidate.root, 1) }
                    main.post {
                        if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@post
                        button.isEnabled = true
                        button.text = "连接并保存"
                        result.onSuccess {
                            preferences.edit().putString("server", candidate.server)
                                .putString("token", candidate.token).putString("root", candidate.root)
                                .putString("password", candidate.password).apply()
                            dialog.dismiss()
                            onChanged()
                        }.onFailure { toast("连接失败，请检查地址、令牌、目录密码和服务状态") }
                    }
                }
            }
        }
        present(dialog)
        // 遥控器样式会关闭触摸焦点；输入框仍需支持点击编辑。
        listOf(server, token, root, password).forEach { it.isFocusableInTouchMode = true }
    }

    // 歌曲加载由数据设置中的“立即加歌”统一执行，不提供独立点歌列表。

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
