package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

class QuickSearchAdapter(
    private val context: Context,
    private val singers: List<Array<String?>>,
    private val songs: List<Song>,
    private val onSingerClick: (Array<String?>) -> Unit,
    private val onSongClick: (Song) -> Unit,
    private val onSongMore: (Song) -> Unit,
    private val stateFor: (Song) -> SongRowState,
    private val previousPageFocusId: Int,
    private val nextPageFocusId: Int,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density
    private val songRows = ceil(songs.size / 2.0).toInt()
    private val headerRows = if (singers.isEmpty()) 0 else 1
    private val songAdapter = SongListAdapter(
        context, songs, "quick", 0, previousPageFocusId, nextPageFocusId, stateFor,
        SongListCallbacks(
            onPrimary = onSongClick, onDownload = onSongMore, onMore = onSongMore,
            onTop = onSongClick, onDelete = {}, onPauseResume = onSongClick,
        ),
        catalogRows = 3, headerHeightDp = if (singers.isEmpty()) 0 else 132,
    )

    fun refreshVisibleStates(list: android.widget.ListView): Boolean =
        songAdapter.refreshVisibleStates(list, headerRows)

    override fun getCount(): Int = (if (singers.isEmpty()) 0 else 1) + songRows
    override fun getItem(position: Int): Any = position
    override fun getItemId(position: Int): Long =
        if (position < headerRows) Long.MIN_VALUE else songAdapter.getItemId(position - headerRows)
    override fun hasStableIds(): Boolean = true
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (position < headerRows) 0 else 1

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (singers.isNotEmpty() && position == 0) return createSingerRow()
        val songRow = position - if (singers.isEmpty()) 0 else 1
        return songAdapter.getView(songRow, convertView, parent)
    }

    private fun createSingerRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        singers.take(4).forEach { singer ->
            addView(createSingerTile(singer), LinearLayout.LayoutParams(0, dp(132), 1f))
        }
        repeat((4 - singers.size.coerceAtMost(4)).coerceAtLeast(0)) {
            addView(View(context), LinearLayout.LayoutParams(0, dp(132), 1f))
        }
    }

    private fun createSingerTile(singer: Array<String?>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        val name = singer.getOrNull(1).orEmpty()
        setAccessibleFocus("focus:singer:${singer.getOrNull(0) ?: name}", "歌星，$name")
        addView(ImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            SingerAvatarLoader.load(this, singer.getOrNull(5), R.drawable.ic_singer_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(5) })
        addView(TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        installPress(this)
        setOnClickListener { onSingerClick(singer) }
        TvFocusStyler.install(this)
    }


    private fun installPress(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.86f).setDuration(70L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
