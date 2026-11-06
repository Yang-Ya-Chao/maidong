package com.local.ktv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Debug-only D-pad regression using production adapters; never changes saved user data. */
class FocusNavigationTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: ListView
    private lateinit var back: TextView
    private lateinit var previous: TextView
    private lateinit var next: TextView
    private lateinit var report: TextView
    private val results = mutableListOf<String>()
    private val cases = listOf("catalog:1", "catalog:9", "catalog:10", "catalog:23",
        "rank:6", "sang:6", "ordered:6", "downloads:6", "singer:9", "playlist:13", "quick:5")
    private var caseIndex = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: String) = TextView(this).apply {
            id = View.generateViewId(); text = label; isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener {}; setPadding(12, 8, 12, 8)
        }
        back = button("返回")
        previous = button("上一页")
        next = button("下一页")
        report = TextView(this).apply { text = "正在验证方向键导航" }
        list = ListView(this).apply {
            itemsCanFocus = true
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            dividerHeight = 0
        }
        root.addView(back)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply { addView(previous); addView(next) })
        root.addView(report)
        setContentView(root)
        main.postDelayed({ runCase() }, 500)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (event.action == KeyEvent.ACTION_DOWN &&
            TvListNavigation.handle(list, back, previous, next, event.keyCode)) true
        else super.dispatchKeyEvent(event)

    private fun rowPosition(): Int {
        val row = generateSequence(window.decorView.findFocus()) { it.parent as? View }
            .firstOrNull { it.parent === list } ?: return -1
        return list.firstVisiblePosition + list.indexOfChild(row)
    }

    private fun press(key: Int) {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    private fun runCase() {
        if (caseIndex >= cases.size) {
            report.text = "完成：${results.size} 项\n" + results.joinToString("\n")
            android.util.Log.i("FocusNavigationTest", report.text.toString())
            return
        }
        val name = cases[caseIndex]
        val mode = name.substringBefore(":")
        val count = name.substringAfter(":").toInt()
        val songs = (0 until count).map { n -> Song().apply {
            id = "焦点测试$n"; title = "焦点测试$n"; singer = "测试"
        } }
        val callbacks = SongListCallbacks({}, {}, {}, {}, {}, {})
        val people = songs.map { arrayOf<String?>(it.id, it.title, null, null, null, null) }
        list.adapter = when (mode) {
            "singer" -> SingerGridAdapter(this, people) {}
            "playlist" -> PlaylistGridAdapter(this, people) {}
            "quick" -> QuickSearchAdapter(this, people.take(4), songs, {}, {}, {},
                { SongRowState() }, previous.id, next.id)
            else -> SongListAdapter(this, songs, mode, 0, previous.id, next.id,
                { SongRowState(local = true) }, callbacks)
        }
        main.postDelayed({
            back.requestFocus()
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            main.postDelayed({ walk(name, 0) }, 100)
        }, 200)
    }

    private fun walk(name: String, expected: Int) {
        if (rowPosition() != expected) return finishCase(name, "失败：行 $expected 实际 ${rowPosition()}")
        if (expected < list.count - 1) {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            main.postDelayed({ walk(name, expected + 1) }, 100)
            return
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        if (!previous.hasFocus()) return finishCase(name, "失败：底部未进入上一页")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (!next.hasFocus()) return finishCase(name, "失败：未进入下一页")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        if (!previous.hasFocus()) return finishCase(name, "失败：未返回上一页")
        press(KeyEvent.KEYCODE_DPAD_UP)
        main.postDelayed({
            if (rowPosition() != list.count - 1) finishCase(name, "失败：翻页区未返回列表")
            else climb(name, list.count - 1)
        }, 100)
    }

    private fun climb(name: String, expected: Int) {
        if (rowPosition() != expected) return finishCase(name, "失败：向上行 $expected 实际 ${rowPosition()}")
        press(KeyEvent.KEYCODE_DPAD_UP)
        main.postDelayed({
            if (expected > 0) climb(name, expected - 1)
            else if (!back.hasFocus()) finishCase(name, "失败：顶部未移出列表")
            else finishCase(name, "通过")
        }, 100)
    }

    private fun finishCase(name: String, result: String) {
        results += "$name $result"
        android.util.Log.i("FocusNavigationTest", results.last())
        report.text = results.joinToString("\n")
        caseIndex++
        main.postDelayed({ runCase() }, 100)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
