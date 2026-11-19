package desu.inugram.ui.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.exteraless.plugins.Plugin
import app.exteraless.plugins.PluginsController
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.IdentityHashMap

open class PythonPluginSettingsActivity(
    private val plugin: Plugin,
    private var targetSetting: String? = null,
    private val path: List<String> = emptyList(),
    private val pageTitle: String? = null,
) : SettingsPageActivity() {
    private val controller get() = PluginsController.getInstance()
    private var rows = emptyList<JSONObject>()
    private val rowsByItem = IdentityHashMap<UItem, JSONObject>()
    private val ids = HashMap<String, Int>()
    private val sliders = HashMap<Int, Pair<String, SliderCell>>()
    private var lastError: String? = null
    private val reloadListener = Runnable { reloadRows() }

    override fun getTitle(): CharSequence = pageTitle ?: plugin.displayName

    override fun onFragmentCreate(): Boolean {
        controller.addSettingsReloadListener(plugin.id, reloadListener)
        reloadRows()
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        controller.removeSettingsReloadListener(plugin.id, reloadListener)
        super.onFragmentDestroy()
    }

    override fun createView(context: Context): View {
        sliders.clear()
        return super.createView(context).also {
            if (path.isEmpty()) {
                resetItem = simpleActionBar.createMenu().addItem(0, R.drawable.msg_reset).also { item ->
                    item.contentDescription = LocaleController.getString(R.string.PluginsResetSettings)
                    item.setOnClickListener { reset() }
                }
                updateResetItem()
            }
            AndroidUtilities.runOnUIThread { showTarget() }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadRows()
    }

    private var resetItem: View? = null

    private fun updateResetItem() {
        resetItem?.visibility = if (controller.hasPluginSettingsPreferences(plugin.id)) View.VISIBLE else View.GONE
    }

    fun reloadRows() {
        try {
            var data = JSONArray(controller.getPluginSettingsJson(plugin.id) ?: "[]")
            for (owner in path) {
                val holder = (0 until data.length()).mapNotNull { data.optJSONObject(it) }.firstOrNull { identity(it) == owner }
                data = holder?.optJSONArray("sub_page") ?: JSONArray()
            }
            rows = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            lastError = null
        } catch (e: Exception) {
            FileLog.e(e)
            lastError = e.localizedMessage
        }
        updateResetItem()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        rowsByItem.clear()
        val installed = controller.getPlugin(plugin.id)
        if (installed == null) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginPermissionsGone)))
            return
        }
        installed.loadError?.takeIf { it.isNotBlank() }?.let { items.add(UItem.asShadow(it)) }
        if (!installed.loaded) items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsNotLoaded)))
        lastError?.let { items.add(UItem.asShadow(it)) }
        val occurrences = HashMap<String, Int>()
        for (row in rows) {
            val identity = identity(row)
            val count = occurrences[identity] ?: 0
            occurrences[identity] = count + 1
            val id = ids.getOrPut("$identity:$count") { ROW_BASE + ids.size }
            val item = rowItem(row, id) ?: continue
            rowsByItem[item] = row
            items.add(item)
        }
        if (rows.isEmpty() && installed.loaded && lastError == null) items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsNoSettings)))
        if (path.isEmpty()) {
            items.add(UItem.asShadow(null))
            items.add(mkSubPageButton(PERMISSIONS, R.drawable.msg_permissions, LocaleController.getString(R.string.PluginPermissions)))
            val watchdog = controller.watchdog
            items.add(UItem.asCheck(WATCHDOG, LocaleController.getString(R.string.PluginWatchdogWarnings)).also { it.checked = !watchdog.isWarningMuted(plugin.id) })
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginWatchdogWarningsInfo)))
        }
        items.add(UItem.asShadow(null))
    }

    private fun rowItem(row: JSONObject, id: Int): UItem? {
        val type = row.optString("type")
        val item = when (type) {
            "header" -> UItem.asHeader(row.optString("text"))
            "divider" -> UItem.asShadow(optional(row, "text"))
            "switch" -> {
                val subtext = optional(row, "subtext")
                if (subtext == null) {
                    UItem.asCheck(id, title(row)).also { it.checked = row.optBoolean("value") }
                } else {
                    UItem.asButtonCheck(id, title(row), subtext).also {
                        it.checked = row.optBoolean("value")
                        it.onBind { view -> (view as? org.telegram.ui.Cells.NotificationsCheckCell)?.setDrawLine(false) }
                    }
                }
            }
            "custom" -> customItem(row, id) ?: return null
            "slider" -> sliderItem(row, id) ?: return null
            else -> UItem.asButton(id, icon(row), title(row), when (type) {
                "selector" -> row.optJSONArray("items")?.optString(row.optInt("value", row.optInt("default")))
                "input", "edittext" -> row.optString("value")
                else -> null
            }).also { item ->
                item.red = row.optBoolean("red")
                item.accent = row.optBoolean("accent")
                item.onBind { view ->
                    (view as? TextCell)?.setSubtitle(optional(row, "subtext"))
                }
            }
        }
        item.id = id
        return item
    }

    private fun customItem(row: JSONObject, id: Int): UItem? {
        val ctx = context ?: return null
        val viewId = optional(row, "view_id") ?: return null
        val content = controller.getPluginSettingsCustomContent(plugin.id, viewId, ctx) ?: return null
        val raw = if (content is UItem) UItem(content.viewType, content.selectable).also { copy ->
            UItem::class.java.fields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                field.set(copy, field.get(content))
            }
        } else UItem.asCustom(content as? View ?: return null, LayoutHelper.WRAP_CONTENT)
        if (raw.viewType >= 0 || raw.view == null) return raw.also { it.id = id }
        val child = raw.view
        val wrapper = FrameLayout(ctx).apply {
            AndroidUtilities.removeFromParent(child)
            addView(child, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
            setOnClickListener { clickRow(row, raw, this) }
            setOnLongClickListener { longClickRow(row, raw, this) }
        }
        return UItem.asCustom(wrapper, raw.intValue.takeIf { it > 0 } ?: LayoutHelper.WRAP_CONTENT).also {
            it.id = id
            it.settingItem = raw.settingItem
        }
    }

    private fun sliderItem(row: JSONObject, id: Int): UItem? {
        val ctx = context ?: return null
        val min = row.optDouble("min", 0.0).toFloat()
        val max = row.optDouble("max", 100.0).toFloat()
        val step = row.optDouble("step", 1.0).toFloat()
        if (!min.isFinite() || !max.isFinite() || !step.isFinite() || max <= min || step <= 0f) return null
        val value = row.optDouble("value", min.toDouble()).toFloat().let { if (it.isFinite()) it.coerceIn(min, max) else min }
        val config = "$min:$max:$step:${title(row)}"
        val cached = sliders[id]?.takeIf { it.first == config }?.second
        val cell = cached ?: SliderCell(ctx, min, max, value, value, step, title(row),
            format = { number(it, row.optBoolean("integral")) },
            onChanged = {},
            onReleased = { changed ->
                val current = rows.firstOrNull { identity(it) == identity(row) } ?: row
                update(current, if (current.optBoolean("integral")) changed.toInt() else changed)
            }).also { sliders[id] = config to it }
        cell.updateValue(value)
        return UItem.asCustom(cell, LayoutHelper.WRAP_CONTENT).also { it.id = id }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            PERMISSIONS -> presentFragment(PythonPluginPermissionsActivity(plugin.id))
            WATCHDOG -> {
                val watchdog = controller.watchdog
                watchdog.setWarningMuted(plugin.id, !watchdog.isWarningMuted(plugin.id))
            }
            RESET -> reset()
            else -> rowsByItem[item]?.let { clickRow(it, item, view) }
        }
    }

    private fun clickRow(row: JSONObject, item: UItem, view: View) {
        when (row.optString("type")) {
            "switch" -> update(row, !row.optBoolean("value"))
            "selector" -> {
                val options = row.optJSONArray("items") ?: return
                if (options.length() == 0) return
                selector(row, options)
            }
            "input", "edittext" -> input(row)
            "custom", "text" -> {
                if (row.optJSONArray("sub_page") != null) openSubpage(row)
                else optional(row, "callback_id")?.let { controller.dispatchSettingClick(plugin.id, it, view) }
                    ?: controller.dispatchSettingsCustomClick(plugin.id, item, view, false)
                reloadRows()
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean =
        rowsByItem[item]?.let { longClickRow(it, item, view) } ?: false

    private fun longClickRow(row: JSONObject, item: UItem, view: View): Boolean {
        optional(row, "long_callback_id")?.let {
            controller.dispatchSettingClick(plugin.id, it, view)
            reloadRows()
            return true
        }
        if (controller.dispatchSettingsCustomClick(plugin.id, item, view, true)) return true
        val alias = optional(row, "link_alias") ?: return false
        val link = PluginsController.settingsLink(plugin.id, alias) ?: return false
        AndroidUtilities.addToClipboard(link)
        org.telegram.ui.Components.BulletinFactory.of(this).createCopyLinkBulletin().show()
        return true
    }

    private fun update(row: JSONObject, value: Any) {
        val key = optional(row, "key") ?: return
        row.put("value", value)
        controller.notifySettingChanged(plugin.id, key, if (value is String) JSONObject.quote(value) else value.toString())
        reloadRows()
    }

    private fun input(row: JSONObject) {
        val ctx = context ?: return
        val multiline = row.optBoolean("multiline")
        val field = EditTextBoldCursor(ctx).apply {
            background = null
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider))
            setLineColors(Theme.getColor(Theme.key_dialogInputField, resourceProvider),
                Theme.getColor(Theme.key_dialogInputFieldActivated, resourceProvider), Theme.getColor(Theme.key_text_RedBold, resourceProvider))
            setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            setSingleLine(!multiline)
            minLines = if (multiline) 4 else 1
            maxLines = if (multiline) 8 else 1
            gravity = Gravity.TOP or Gravity.START
            setText(row.optString("value"))
            setSelection(length())
            hint = optional(row, "hint") ?: LocaleController.getString(R.string.PluginsEnterValue)
            row.optInt("max_length").takeIf { it > 0 }?.let { filters = arrayOf(InputFilter.LengthFilter(it)) }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 8f, 24f, 12f))
        }
        val dialog = AlertDialog.Builder(ctx, resourceProvider).setTitle(title(row)).setView(content)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Done)) { _, _ -> update(row, field.text.toString()) }.create()
        dialog.setOnDismissListener { AndroidUtilities.hideKeyboard(field) }
        dialog.setOnShowListener { field.requestFocus(); AndroidUtilities.showKeyboard(field) }
        showDialog(dialog)
    }

    private fun selector(row: JSONObject, options: JSONArray) {
        val ctx = context ?: return
        val current = row.optInt("value", row.optInt("default")).coerceIn(0, options.length() - 1)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null
        for (i in 0 until options.length()) {
            val cell = org.telegram.ui.Cells.RadioColorCell(ctx, resourceProvider)
            cell.setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resourceProvider), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourceProvider))
            cell.setTextAndValue(options.optString(i), i == current)
            cell.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), Theme.RIPPLE_MASK_ALL)
            cell.setOnClickListener {
                dialog?.dismiss()
                update(row, i)
            }
            list.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(title(row))
            .setView(list)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create()
        showDialog(dialog)
    }

    private fun reset() {
        val ctx = context ?: return
        showDialog(AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.PluginsResetSettings))
            .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginsResetSettingsInfo, plugin.displayName)))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                controller.clearPluginSettingsPreferences(plugin.id, true)
                controller.reloadPlugin(plugin.id) { reloadRows() }
            }.create())
    }

    private fun openSubpage(row: JSONObject, target: String? = null) {
        presentFragment(PythonPluginSettingsActivity(plugin, target, path + identity(row), title(row)))
    }

    private fun showTarget() {
        val target = targetSetting ?: return
        targetSetting = null
        val alias = target.substringBefore(':')
        val row = rows.firstOrNull { optional(it, "link_alias") == alias } ?: return
        if (':' in target && row.optJSONArray("sub_page") != null) {
            openSubpage(row, target.substringAfter(':'))
            return
        }
        val item = rowsByItem.entries.firstOrNull { it.value === row }?.key ?: return
        var position = 0
        while (true) {
            val candidate = listView?.adapter?.getItem(position) ?: break
            if (candidate.id == item.id) {
                listView.layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60f))
                listView.highlightRow { position }
                break
            }
            position++
        }
    }

    private fun title(row: JSONObject): String = optional(row, "text") ?: optional(row, "hint").orEmpty()
    private fun identity(row: JSONObject): String = optional(row, "row_id") ?: optional(row, "key")
        ?: optional(row, "link_alias") ?: "${row.optString("type")}:${title(row)}"
    private fun optional(row: JSONObject, key: String): String? = if (row.isNull(key)) null else row.optString(key).takeIf { it.isNotEmpty() }
    private val iconAliases = mapOf(
        "msg_settings_ny" to listOf("msg_settings"),
        "help" to listOf("msg_help"),
        "msg_clock2" to listOf("menu_premium_clock"),
        "msg_groups_new2" to listOf("msg_groups_create"),
        "msg_paste" to listOf("msg_copy"),
        "msg_persons" to listOf("msg_contacts"),
        "input_suggest_s" to listOf("filled_bot_suggest_24"),
    )
    private fun drawableId(name: String): Int =
        try { R.drawable::class.java.getField(name).getInt(null) } catch (_: ReflectiveOperationException) { 0 }
    private fun icon(row: JSONObject): Int {
        val name = optional(row, "icon") ?: return 0
        val candidates = listOf(name, name.removeSuffix("_solar")) + (iconAliases[name] ?: emptyList())
        return candidates.firstNotNullOfOrNull { drawableId(it).takeIf { id -> id != 0 } } ?: 0
    }

    private fun number(value: Float, integral: Boolean): String = if (integral || value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    companion object {
        private const val PERMISSIONS = 1
        private const val WATCHDOG = 2
        private const val RESET = 3
        private const val ROW_BASE = 100
    }
}
