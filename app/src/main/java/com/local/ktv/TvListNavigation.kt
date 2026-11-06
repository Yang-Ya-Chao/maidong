package com.local.ktv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ListView
import java.util.WeakHashMap

/** Own row boundaries before ListView's spatial search can wrap back into its children. */
internal object TvListNavigation {
    private val generations = WeakHashMap<ListView, Int>()
    fun handle(list: ListView?, back: View?, previous: View?, next: View?, key: Int): Boolean {
        if (list == null || !list.isShown || key !in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) return false
        generations[list] = (generations[list] ?: 0) + 1
        val focused = list.rootView.findFocus() ?: return false
        fun focus(view: View?): Boolean =
            view?.takeIf { it.isShown && it.isEnabled && it.isFocusable }?.requestFocus() == true

        if (focused === previous || focused === next) {
            return when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> focusRow(list, list.count - 1, if (focused === next) Int.MAX_VALUE else 0)
                KeyEvent.KEYCODE_DPAD_LEFT -> { focus(previous); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { focus(next); true }
                else -> false
            }
        }
        if (focused === list) return when (key) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> focus(back)
            KeyEvent.KEYCODE_DPAD_DOWN -> focus(previous)
            KeyEvent.KEYCODE_DPAD_RIGHT -> focus(next)
            else -> false
        }
        if (focused === back && key == KeyEvent.KEYCODE_DPAD_DOWN) return focusRow(list, 0, 0)
        val row = generateSequence(focused) { it.parent as? View }
            .firstOrNull { it.parent === list } ?: return false
        val position = list.firstVisiblePosition + list.indexOfChild(row)
        val cells = targets(row)
        val column = cells.indexOfFirst { cell ->
            generateSequence(focused) { it.parent as? View }.any { it === cell }
        }.coerceAtLeast(0)
        return when (key) {
            KeyEvent.KEYCODE_DPAD_UP ->
                if (position == 0) focus(back) else focusRow(list, position - 1, column)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (position == list.count - 1) focus(if (column == 0) previous else next) || focus(back)
                else focusRow(list, position + 1, column)
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (focused === cells.firstOrNull()) focus(back) else false
            else -> false
        }
    }

    private fun targets(view: View): List<View> {
        if (!view.isShown || !view.isEnabled) return emptyList()
        if (view.isFocusable) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { targets(view.getChildAt(it)) }
    }

    private fun focusRow(list: ListView, position: Int, column: Int): Boolean {
        if (position !in 0 until list.count) return false
        fun target(): View? {
            val row = list.getChildAt(position - list.firstVisiblePosition) ?: return null
            val cells = targets(row)
            return cells.getOrNull(column.coerceAtMost(cells.lastIndex)) ?: list
        }
        if (target()?.requestFocus() == true) return true
        val adapter = list.adapter
        val generation = generations[list]
        val observer = list.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                if (list.adapter === adapter && list.isShown && generations[list] == generation) {
                    target()?.requestFocus()
                }
            }
        })
        list.setSelectionFromTop(position, 0)
        return true
    }
}
