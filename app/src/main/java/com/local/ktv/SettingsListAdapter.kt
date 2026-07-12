package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch

data class SettingsEntry(
    val icon: Int,
    val title: String,
    val subtitle: String = "",
    val actionText: String = "设置",
    val checked: Boolean? = null,
    val action: () -> Unit,
)

class SettingsListAdapter(
    private val context: Context,
    private val entries: List<SettingsEntry>,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): Any = entries[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val entry = entries[position]
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundResource(R.drawable.ott_bg_item)
            isFocusable = entry.checked == null
            isClickable = entry.checked == null

            addView(ImageView(context).apply {
                setImageResource(entry.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(28), dp(28)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(entry.title, 16, Color.WHITE), LinearLayout.LayoutParams(-1, 0, 1f))
                if (entry.subtitle.isNotEmpty()) {
                    addView(label(entry.subtitle, 14, Color.rgb(193, 193, 193)), LinearLayout.LayoutParams(-1, 0, 1f))
                }
            }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(10) })

            if (entry.checked != null) {
                addView(Switch(context).apply {
                    isChecked = entry.checked
                    showText = false
                    isFocusable = true
                    setOnClickListener { entry.action() }
                }, LinearLayout.LayoutParams(dp(70), dp(42)))
            } else {
                addView(label(entry.actionText, 16, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_btn_glass)
                    isFocusable = false
                    isClickable = false
                }, LinearLayout.LayoutParams(dp(130), dp(33)))
            }

            if (entry.checked == null) setOnClickListener { entry.action() }
            onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                view.animate()
                    .scaleX(if (focused) 1.015f else 1f)
                    .scaleY(if (focused) 1.015f else 1f)
                    .alpha(if (focused) 1f else 0.96f)
                    .setDuration(100L)
                    .start()
                view.isSelected = focused
            }
        }
    }

    private fun label(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
