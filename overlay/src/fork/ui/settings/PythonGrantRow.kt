package desu.inugram.ui.settings

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Switch

internal class PythonGrantRow(context: Context) : FrameLayout(context) {
    private val grant = GrantRowView(context).also {
        it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
    }
    private val toggle = Switch(context).apply {
        setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite)
        isClickable = false
        visibility = android.view.View.GONE
    }

    init {
        addView(grant, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        addView(toggle, LayoutHelper.createFrame(37, 20f, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 22f, 0f))
    }

    fun bind(token: String, checked: Boolean, divider: Boolean) {
        grant.bind(token, null, divider)
        toggle.setChecked(checked, false)
    }

    fun setChecked(checked: Boolean, animated: Boolean) {
        toggle.setChecked(checked, animated)
    }
}
