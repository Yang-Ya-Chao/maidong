package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.ceil

data class SongRowState(
    val current: Boolean = false,
    val queued: Boolean = false,
    val local: Boolean = false,
    val downloading: Boolean = false,
    val failed: Boolean = false,
    val paused: Boolean = false,
    val progress: Int = 0,
)

class SongListCallbacks(
    val onPrimary: (Song) -> Unit,
    val onDownload: (Song) -> Unit,
    val onMore: (Song) -> Unit,
    val onTop: (Song) -> Unit,
    val onDelete: (Song) -> Unit,
    val onPauseResume: (Song) -> Unit,
)

/** Stateful row renderer based on the original OTT song/rank/order/download layouts. */
class SongListAdapter(
    private val context: Context,
    private val songs: List<Song>,
    private val mode: String,
    private val pageOffset: Int,
    private val previousPageFocusId: Int,
    private val nextPageFocusId: Int,
    private val stateFor: (Song) -> SongRowState,
    private val callbacks: SongListCallbacks,
    private val catalogRows: Int = 5,
    private val headerHeightDp: Int = 0,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density
    private val columns = if (mode in SINGLE_COLUMN_MODES) 1 else 2
    private val cardIds = HashMap<String, Int>()
    private var renderedKeys = songs.map(::songKey)
    private data class CatalogBinding(
        val key: String, val title: String?, val singer: String?,
        val state: SongRowState, val index: Int, val lastRow: Boolean
    )
    private data class CatalogViews(
        var binding: CatalogBinding, val ordered: View, val downloading: TextView,
        val failed: View, val online: View,
    )

    private data class SingleViews(
        var binding: CatalogBinding, val render: (SongRowState) -> Unit,
    )

    private fun updateSingleRow(row: LinearLayout, song: Song, index: Int): Boolean {
        val views = row.tag as? SingleViews ?: return false
        val binding = CatalogBinding(songKey(song), song.title, song.singer, stateFor(song), index, index == count - 1)
        if (views.binding.copy(state = binding.state) != binding) return false
        if (views.binding.state != binding.state) {
            views.binding = binding
            views.render(binding.state)
            row.contentDescription = songSpokenLabel(song, binding.state)
        }
        return true
    }

    private fun bindSingleRow(row: LinearLayout, song: Song, index: Int,
                              actions: List<View> = emptyList(), render: (SongRowState) -> Unit) {
        val state = stateFor(song)
        row.tag = SingleViews(
            CatalogBinding(songKey(song), song.title, song.singer, state, index, index == count - 1), render)
        configureSongCard(row, song, index)
        installSingleNavigation(row, song, index, actions)
        render(state)
    }

    private fun installSingleNavigation(row: LinearLayout, song: Song, index: Int, actions: List<View>) {
        row.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        fun visibleActions() = actions.filter { it.visibility == View.VISIBLE && it.isEnabled }
        fun vertical(key: Int): Boolean {
            val next = index + if (key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
            if (next !in songs.indices) {
                if (mode == "rank" && key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    return row.rootView.findViewById<View>(previousPageFocusId)?.requestFocus() == true
                }
                return false
            }
            val list = row.parent as? android.widget.ListView ?: return false
            val target = list.getChildAt(next - list.firstVisiblePosition)
            if (target?.requestFocus() != true) {
                list.setSelectionFromTop(next, if (next > index) row.height else 0)
                list.post { list.getChildAt(next - list.firstVisiblePosition)?.requestFocus() }
            }
            return true
        }
        row.setOnKeyListener { _, key, event ->
            when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val target = visibleActions().firstOrNull()
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) target?.requestFocus() == true
                    else target != null
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) vertical(key) else true
                else -> false
            }
        }
        actions.forEachIndexed { actionIndex, button ->
            button.setAccessibleFocus(FOCUS_PREFIX + songKey(song) + "|action|" + actionIndex,
                "歌曲操作，${song.title.orEmpty()}")
            button.setOnKeyListener { _, key, event ->
                when (key) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                            val visible = visibleActions()
                            val at = visible.indexOf(button)
                            val target = if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                                visible.getOrNull(at - 1) ?: row
                            else visible.getOrNull(at + 1) ?: button
                            target.requestFocus()
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) vertical(key) else true
                    else -> false
                }
            }
        }
    }

    override fun notifyDataSetChanged() {
        renderedKeys = songs.map(::songKey)
        super.notifyDataSetChanged()
    }

    /** State-only updates keep the focused card attached, including the right column. */
    fun refreshVisibleStates(list: android.widget.ListView, rowOffset: Int = 0): Boolean {
        if (renderedKeys != songs.map(::songKey)) return false
        repeat(list.childCount) rowLoop@ { child ->
            val position = list.firstVisiblePosition + child - rowOffset
            if (position < 0) return@rowLoop
            val row = list.getChildAt(child) as? LinearLayout ?: return false
            if (columns == 1) {
                val song = songs.getOrNull(position) ?: return false
                if (!updateSingleRow(row, song, position)) return false
                return@rowLoop
            }
            repeat(columns) cell@ { column ->
                val index = position * columns + column
                val song = songs.getOrNull(index) ?: return@cell
                val cell = row.getChildAt(column) as? LinearLayout ?: return false
                createCatalogCell(song, index, column, position == count - 1, cell)
            }
        }
        return true
    }

    private fun pageRowHeight(parent: ViewGroup, position: Int, rows: Int): Int {
        val available = (maxOf(parent.height, parent.measuredHeight) -
            parent.paddingTop - parent.paddingBottom - dp(headerHeightDp)).coerceAtLeast(0)
        val index = position % rows
        return maxOf(dp(60), available * (index + 1) / rows - available * index / rows)
    }

    private fun cardId(index: Int): Int = cardIds.getOrPut(songKey(songs[index])) { View.generateViewId() }

    override fun getCount(): Int = ceil(songs.size / columns.toDouble()).toInt()
    override fun getItem(position: Int): Any = position
    override fun getItemId(position: Int): Long = songKey(songs[position * columns]).hashCode().toLong()
    override fun hasStableIds(): Boolean = true

    fun matches(renderMode: String, renderPageOffset: Int): Boolean =
        mode == renderMode && pageOffset == renderPageOffset

    fun adapterPositionFor(focusMarker: String): Int? {
        val key = focusMarker.removePrefix(FOCUS_PREFIX).substringBefore("|action|")
        val index = songs.indexOfFirst { songKey(it) == key }
        return index.takeIf { it >= 0 }?.div(columns)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (columns == 1) {
            val attached = (convertView as? LinearLayout)?.takeIf { it.isAttachedToWindow }
            val row = if (attached != null && updateSingleRow(attached, songs[position], position)) attached
                else createSingleRow(songs[position], position)
            return row.apply {
                if (mode == "rank") layoutParams =
                    android.widget.AbsListView.LayoutParams(-1, pageRowHeight(parent, position, 6))
                if (position == count - 1) nextFocusDownId = previousPageFocusId
            }
        }
        // A measured scrap row has never attached its descendants to the window.
        // Reuse only attached rows so focus never targets a detached measured card.
        val row = (convertView as? LinearLayout)?.takeIf { it.isAttachedToWindow }
            ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        row.layoutParams = android.widget.AbsListView.LayoutParams(-1, pageRowHeight(parent, position, catalogRows))
        while (row.childCount > columns) row.removeViewAt(row.childCount - 1)
        repeat(columns) { column ->
            val index = position * columns + column
            val cell = songs.getOrNull(index)?.let {
                createCatalogCell(it, index, column, position == count - 1,
                    row.getChildAt(column) as? LinearLayout)
            } ?: row.getChildAt(column)?.takeUnless { it is LinearLayout } ?: View(context)
            if (row.getChildAt(column) !== cell) {
                if (row.childCount > column) row.removeViewAt(column)
                row.addView(cell, column, LinearLayout.LayoutParams(0, -1, 1f))
            } else cell.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        return row
    }

    private fun createSingleRow(song: Song, index: Int): View = when (mode) {
        "rank" -> createRankRow(song, index)
        "ordered" -> createOrderedRow(song, index)
        "downloads" -> createDownloadRow(song, index)
        "sang" -> createSangRow(song, index)
        else -> createCatalogCell(song, index, 0, index == count - 1)
    }

    private fun createCatalogCell(
        song: Song, songIndex: Int, column: Int, lastRow: Boolean, recycled: LinearLayout? = null
    ): View {
        val state = stateFor(song)
        val binding = CatalogBinding(songKey(song), song.title, song.singer, state, songIndex, lastRow)
        val card = recycled ?: LinearLayout(context)
        val previous = card.tag as? CatalogViews
        if (previous?.binding?.copy(state = state) == binding) {
            if (previous.binding.state != state) {
                previous.binding = binding
                updateCatalogState(card, song, state, previous)
            }
            return card
        }
        card.removeAllViews()
        return card.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), 0, dp(4))
            if (background == null) setBackgroundResource(R.drawable.ott_bg_item)
            val info = songInfo(song, stacked = true)
            addView(info, LinearLayout.LayoutParams(0, dp(52), 1f))
            val views = addStateSlot(this, song, binding)
            tag = views
            addView(icon(R.drawable.ott_ic_more, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            configureSongCard(this, song, songIndex)
            if (songIndex >= columns) nextFocusUpId = cardId(songIndex - columns)
            if (column == 1) nextFocusLeftId = cardId(songIndex - 1)
            if (column == 0 && songIndex + 1 < songs.size) nextFocusRightId = cardId(songIndex + 1)
            if (!lastRow && songIndex + columns < songs.size) nextFocusDownId = cardId(songIndex + columns)
            if (lastRow) {
                nextFocusDownId = if (column == 0) previousPageFocusId else nextPageFocusId
            }
            setOnKeyListener { view, key, event ->
                val targetId = when (key) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> view.nextFocusUpId
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> view.nextFocusDownId
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> view.nextFocusLeftId
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> view.nextFocusRightId
                    else -> View.NO_ID
                }
                val target = if (targetId != View.NO_ID) view.rootView.findViewById<View>(targetId) else null
                if (target == null || !target.isAttachedToWindow || !target.isShown) false
                else if (event.action == android.view.KeyEvent.ACTION_DOWN) target.requestFocus()
                else event.action == android.view.KeyEvent.ACTION_UP
            }
        }
    }

    private fun addStateSlot(card: LinearLayout, song: Song, binding: CatalogBinding): CatalogViews {
        val slot = android.widget.FrameLayout(context)
        val ordered = icon(R.drawable.ic_order_song, null)
        val downloading = text("", 14, Color.WHITE, Gravity.CENTER)
        val failed = icon(R.drawable.ic_download_retry, null)
        val online = icon(R.drawable.ott_ic_download, null)
        listOf(ordered, failed, online).forEach { view ->
            slot.addView(view, android.widget.FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        slot.addView(downloading, android.widget.FrameLayout.LayoutParams(-1, -1))
        card.addView(slot, LinearLayout.LayoutParams(dp(64), dp(40)))
        return CatalogViews(binding, ordered, downloading, failed, online).also {
            updateCatalogState(card, song, binding.state, it)
        }
    }

    private fun updateCatalogState(card: View, song: Song, state: SongRowState, views: CatalogViews) {
        val ordered = state.queued || state.current
        views.ordered.visibility = if (ordered) View.VISIBLE else View.INVISIBLE
        views.downloading.visibility = if (!ordered && state.downloading) View.VISIBLE else View.INVISIBLE
        views.failed.visibility = if (!ordered && !state.downloading && state.failed) View.VISIBLE else View.INVISIBLE
        views.online.visibility = if (!ordered && !state.downloading && !state.failed && !state.local)
            View.VISIBLE else View.INVISIBLE
        val progress = "↓ ${state.progress}%"
        if (views.downloading.text.toString() != progress) views.downloading.text = progress
        card.contentDescription = songSpokenLabel(song, state)
    }

    private fun songSpokenLabel(song: Song, state: SongRowState): String {
        val stateText = when {
            state.current -> "正在播放"
            state.queued -> "已点"
            state.downloading -> "正在下载百分之${state.progress}"
            state.local -> "已下载"
            else -> "未下载"
        }
        return "歌曲，${song.title.orEmpty()}，歌手，${song.singer.orEmpty()}，$stateText，双击点歌，长按查看更多"
    }

    private fun createRankRow(song: Song, index: Int): View {
        val order = pageOffset + index + 1
        return singleRow(R.drawable.ott_bg_item).apply {
            val badge = if (order <= 3) icon(
                intArrayOf(R.drawable.ic_rank_order_1, R.drawable.ic_rank_order_2, R.drawable.ic_rank_order_3)[order - 1], null)
                else text(order.toString(), 16, Color.WHITE, Gravity.CENTER)
            addView(badge, LinearLayout.LayoutParams(dp(47), dp(48)))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
            val views = addStateSlot(this, song, CatalogBinding(
                songKey(song), song.title, song.singer, stateFor(song), index, index == count - 1))
            addView(icon(R.drawable.ott_ic_more, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            bindSingleRow(this, song, index) { state -> updateCatalogState(this, song, state, views) }
        }
    }

    private fun singleRow(backgroundId: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(backgroundId)
    }

    private fun createOrderedRow(song: Song, index: Int): View =
        singleRow(R.drawable.ott_bg_download).apply {
            val slot = android.widget.FrameLayout(context)
            val number = text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER)
            val playing = icon(R.drawable.ott_ic_playing, null)
            slot.addView(number, android.widget.FrameLayout.LayoutParams(-1, -1))
            slot.addView(playing, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(slot, LinearLayout.LayoutParams(dp(47), dp(48)))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
            val top = icon(R.drawable.ic_add_to_top) { callbacks.onTop(song) }
            val delete = icon(R.drawable.ic_delete) { callbacks.onDelete(song) }
            listOf(top, delete).forEach { addView(it, LinearLayout.LayoutParams(dp(36), dp(36))) }
            bindSingleRow(this, song, index, listOf(top, delete)) { state ->
                number.visibility = if (state.current) View.INVISIBLE else View.VISIBLE
                playing.visibility = if (state.current) View.VISIBLE else View.INVISIBLE
                if (state.current && (top.hasFocus() || delete.hasFocus())) requestFocus()
                top.visibility = if (state.current) View.INVISIBLE else View.VISIBLE
                delete.visibility = top.visibility
            }
        }

    private fun createDownloadRow(song: Song, index: Int): View =
        singleRow(R.drawable.ott_bg_download).apply {
            addView(text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER),
                LinearLayout.LayoutParams(dp(47), dp(48)))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressDrawable = context.getDrawable(R.drawable.bg_download_progress)
            }
            addView(progress, LinearLayout.LayoutParams(dp(90), dp(8)).apply { marginEnd = dp(6) })
            val primary = android.widget.FrameLayout(context).apply {
                id = View.generateViewId()
                installPressAnimation(this)
                setOnClickListener {
                    val state = stateFor(song)
                    if (state.failed || state.paused || state.downloading) callbacks.onPauseResume(song)
                    else callbacks.onPrimary(song)
                }
            }
            val retry = icon(R.drawable.ic_download_retry, null)
            val pause = icon(R.drawable.ic_pause, null)
            val play = icon(R.drawable.ott_ic_play, null)
            listOf(retry, pause, play).forEach {
                primary.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            val delete = icon(R.drawable.ic_delete) { callbacks.onDelete(song) }
            addView(primary, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(delete, LinearLayout.LayoutParams(dp(36), dp(36)))
            bindSingleRow(this, song, index, listOf(primary, delete)) { state ->
                progress.visibility = if (state.downloading) View.VISIBLE else View.INVISIBLE
                if (progress.progress != state.progress) progress.progress = state.progress
                val retrying = state.failed || state.paused
                retry.visibility = if (retrying) View.VISIBLE else View.INVISIBLE
                pause.visibility = if (!retrying && state.downloading) View.VISIBLE else View.INVISIBLE
                play.visibility = if (!retrying && !state.downloading && state.local) View.VISIBLE else View.INVISIBLE
                val active = retrying || state.downloading || state.local
                if (!active && primary.hasFocus()) requestFocus()
                primary.visibility = if (active) View.VISIBLE else View.INVISIBLE
                val name = when {
                    retrying -> "重试"
                    state.downloading -> "暂停"
                    else -> "播放"
                }
                primary.contentDescription = "$name，${song.title.orEmpty()}"
                delete.contentDescription = "删除，${song.title.orEmpty()}"
            }
        }

    private fun createSangRow(song: Song, index: Int): View =
        singleRow(R.drawable.ott_bg_item).apply {
            addView(text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER),
                LinearLayout.LayoutParams(dp(47), dp(48)))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
            val order = icon(R.drawable.ic_order_song) { callbacks.onPrimary(song) }
            addView(order, LinearLayout.LayoutParams(dp(36), dp(36)))
            bindSingleRow(this, song, index, listOf(order)) { }
        }

    private fun songInfo(song: Song, stacked: Boolean): View = LinearLayout(context).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (stacked) {
            addView(text(song.title.orEmpty(), 16, Color.WHITE, Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(text(song.singer.orEmpty(), 14, Color.rgb(193, 193, 193), Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            addView(text(song.title.orEmpty(), 16, Color.WHITE, Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) })
            addView(text(song.singer.orEmpty(), 16, Color.rgb(193, 193, 193), Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun text(value: String, size: Int, color: Int, gravityValue: Int) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = gravityValue
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun icon(resource: Int, action: (() -> Unit)?): ImageView = ImageView(context).apply {
        setImageResource(resource)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(7), dp(7), dp(7), dp(7))
        isFocusable = action != null
        isClickable = action != null
        action?.let { callback ->
            id = View.generateViewId()
            installPressAnimation(this)
            setOnClickListener { callback() }
        }
    }

    private fun configureSongCard(card: View, song: Song, songIndex: Int) {
        card.id = cardId(songIndex)
        card.setAccessibleFocus(FOCUS_PREFIX + songKey(song), songSpokenLabel(song, stateFor(song)))
        (card as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        installPressAnimation(card)
        card.setOnClickListener { callbacks.onPrimary(song) }
        card.setOnLongClickListener { callbacks.onMore(song); true }
    }

    private fun installPressAnimation(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        TvFocusStyler.install(view)
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.82f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    companion object {
        const val FOCUS_PREFIX = "focus:song:"
        private val SINGLE_COLUMN_MODES = setOf("rank", "ordered", "downloads", "sang")

        private fun songKey(song: Song): String =
            (song.id ?: song.dbId ?: song.filename ?: "${song.title}|${song.singer}").toString()
    }
}
