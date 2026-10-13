package com.local.ktv

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService

/** 保留遥控器可操作的原生列表；请求只回写仍可见的弹窗。 */
class OpenListBrowser(
    private val activity: Activity,
    private val executor: ExecutorService,
    private val onOrder: (Song) -> Unit,
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

    fun show() {
        val saved = config()
        if (saved.server.isBlank()) {
            configure()
            return
        }
        present(AlertDialog.Builder(activity).setTitle("OpenList 网盘歌曲")
            .setItems(arrayOf("浏览歌曲并点播", "连接设置")) { _, index ->
                if (index == 0) browse(saved) else configure()
            }.setNegativeButton("关闭", null).create())
    }

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
                            browse(candidate)
                        }.onFailure { toast("连接失败，请检查地址、令牌、目录密码和服务状态") }
                    }
                }
            }
        }
        present(dialog)
        // 遥控器样式会关闭触摸焦点；输入框仍需支持点击编辑。
        listOf(server, token, root, password).forEach { it.isFocusableInTouchMode = true }
    }

    private fun browse(config: OpenListConfig) {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val info = TextView(activity)
        layout.addView(info)
        val tools = LinearLayout(activity)
        layout.addView(tools)
        fun button(label: String): Button = Button(activity).apply {
            text = label
            tools.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val up = button("上级目录")
        val previous = button("上一页")
        val next = button("下一页")
        val refresh = button("重新加载")
        val list = ListView(activity)
        layout.addView(list, LinearLayout.LayoutParams(-1,
            (activity.resources.displayMetrics.heightPixels * 0.4f).toInt()))
        val dialog = AlertDialog.Builder(activity).setTitle("OpenList 网盘点歌")
            .setView(layout).setNegativeButton("关闭", null).create()
        var path = config.root
        var page = 1
        var serial = 0
        var entries = emptyList<OpenListClient.Entry>()
        fun load() {
            val request = ++serial
            val requestedPath = path
            val requestedPage = page
            list.isEnabled = false
            up.isEnabled = false
            previous.isEnabled = false
            next.isEnabled = false
            refresh.isEnabled = false
            info.text = "正在读取 $path …"
            executor.execute {
                val result = runCatching { OpenListClient(config).list(requestedPath, requestedPage) }
                main.post {
                    if (!dialog.isShowing || request != serial || activity.isFinishing || activity.isDestroyed) return@post
                    refresh.isEnabled = true
                    up.isEnabled = path != config.root
                    previous.isEnabled = page > 1
                    result.onSuccess { data ->
                        entries = data.entries
                        list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1,
                            entries.map { if (it.directory) "【目录】" + it.name
                                else if (OpenListPaths.media(it.name)) "【点歌】" + it.name else it.name })
                        list.isEnabled = true
                        next.isEnabled = page.toLong() * OpenListClient.PAGE_SIZE < data.total
                        info.text = "$path  第 $page 页，共 ${data.total} 项" +
                            if (entries.isEmpty()) "（空目录）" else ""
                        list.requestFocus()
                    }.onFailure {
                        entries = emptyList()
                        list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, emptyList<String>())
                        info.text = "读取失败，请检查连接和目录权限；可重新加载"
                    }
                }
            }
        }
        up.setOnClickListener { path = OpenListPaths.parent(path, config.root); page = 1; load() }
        previous.setOnClickListener { page--; load() }
        next.setOnClickListener { page++; load() }
        refresh.setOnClickListener { load() }
        list.setOnItemClickListener { _, _, index, _ ->
            val entry = entries.getOrNull(index) ?: return@setOnItemClickListener
            val selected = runCatching { OpenListPaths.child(path, entry.name) }.getOrElse {
                toast("文件名无效"); return@setOnItemClickListener
            }
            if (entry.directory) {
                path = selected
                page = 1
                load()
            } else if (OpenListPaths.media(entry.name)) {
                val song = Song.local(selected, entry.name).apply {
                    id = OpenListPaths.songId(config.server, selected)
                    this.path = null
                    remote = true
                    category = "网盘"
                    openListServer = config.server
                    openListPath = selected
                }
                onOrder(song)
            } else toast("此文件不是支持的音视频文件")
        }
        dialog.setOnDismissListener { serial++ }
        present(dialog)
        load()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
