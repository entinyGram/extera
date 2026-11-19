package desu.inugram.helpers.plugins

import app.exteraless.plugins.MenuItemRecord
import app.exteraless.plugins.PluginRuntime
import app.exteraless.plugins.PluginsController
import desu.inugram.InuConfig
import org.mvel2.MVEL
import org.mvel2.optimizers.OptimizerFactory
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object PythonMenuBridge {
    private const val MESSAGE_OPTION_BASE = -8_000_000
    private const val ACTION_OPTION = -8_200_000
    private val messageMenus = WeakHashMap<ChatActivity, List<MenuItemRecord>>()
    private val actionHeaders = WeakHashMap<BaseFragment, WeakReference<ActionBarMenuItem>>()
    private val unsafeCondition = Regex("""\b(?:new|import|def|function|foreach|for|while|do|with|until|class|getClass|forName|getClassLoader|loadClass|invoke|getMethod|getMethods|getDeclared\w*|getField|getFields|newInstance|getRuntime|exec|setAccessible|getConstructor\w*)\b|[;@${'$'}#`{}]|(?<![=!<>])=(?!=)|\+\+|--|[+\-*/%]=""")
    private val quotedText = Regex("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"")
    private val rootName = Regex("""(?<![.\w])([A-Za-z_]\w*)""")
    private val conditionRoots = setOf("account", "chat", "chatId", "context", "dialog_id", "encryptedChat", "fragment", "message", "user", "userId", "true", "false", "null", "nil", "empty", "is", "isnot", "contains", "instanceof", "soundslike", "strsim")

    init {
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE)
    }

    private fun enabled() = InuConfig.PYTHON_PLUGINS.value && PluginsController.getInstance().isEngineEnabled

    private fun context(fragment: BaseFragment, account: Int, dialogId: Long, message: MessageObject? = null): Map<String, Any?> {
        val controller = MessagesController.getInstance(account)
        val chat = if (fragment is ChatActivity) fragment.currentChat else if (dialogId < 0) controller.getChat(-dialogId) else null
        val user = if (fragment is ChatActivity) fragment.currentUser else if (dialogId > 0) controller.getUser(dialogId) else null
        return hashMapOf<String, Any?>(
            "fragment" to fragment, "context" to fragment.parentActivity, "account" to account,
            "dialog_id" to dialogId, "message" to message, "chat" to chat, "user" to user,
            "encryptedChat" to (fragment as? ChatActivity)?.currentEncryptedChat,
        ).apply {
            chat?.let { put("chatId", it.id) }
            user?.let { put("userId", it.id) }
        }
    }

    private fun visible(item: MenuItemRecord, context: Map<String, Any?>): Boolean {
        if (item.text.isNullOrEmpty()) return false
        val condition = item.condition?.takeIf { it.isNotEmpty() } ?: return true
        val code = quotedText.replace(condition, "''")
        if (unsafeCondition.containsMatchIn(code) || rootName.findAll(code).any { it.groupValues[1] !in conditionRoots }) return false
        val previous = PluginRuntime.enter(item.pluginId)
        return try {
            MVEL.eval(condition, context) == true
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        } finally {
            PluginRuntime.exit(previous)
        }
    }

    private fun rows(type: MenuItemRecord.MenuType, context: Map<String, Any?>): List<MenuItemRecord> =
        if (enabled()) PluginsController.getInstance().getMenuItemsFor(type).filter { visible(it, context) } else emptyList()

    private fun icon(fragment: BaseFragment, item: MenuItemRecord): Int {
        val ctx = fragment.parentActivity ?: return R.drawable.msg_settings_old
        return item.icon?.let { ctx.resources.getIdentifier(it, "drawable", ctx.packageName) }
            ?.takeIf { it != 0 } ?: R.drawable.msg_settings_old
    }

    private fun click(item: MenuItemRecord, context: Map<String, Any?>) {
        if (enabled()) PluginsController.getInstance().dispatchMenuClick(item.pluginId, item.itemId, context)
    }

    @JvmStatic
    fun fillMessageMenu(items: ArrayList<CharSequence>, options: ArrayList<Int>, icons: ArrayList<Int>, activity: ChatActivity, message: MessageObject) {
        val records = rows(MenuItemRecord.MenuType.MESSAGE_CONTEXT_MENU, context(activity, activity.currentAccount, activity.dialogId, message))
        messageMenus[activity] = records
        records.forEachIndexed { index, item ->
            items.add(item.text)
            options.add(MESSAGE_OPTION_BASE - index)
            icons.add(icon(activity, item))
        }
    }

    @JvmStatic
    fun handleMessageMenuOption(option: Int, activity: ChatActivity, message: MessageObject): Boolean {
        val index = MESSAGE_OPTION_BASE - option
        val item = messageMenus[activity]?.getOrNull(index) ?: return false
        click(item, context(activity, activity.currentAccount, activity.dialogId, message))
        return true
    }

    @JvmStatic
    fun addActionMenu(fragment: BaseFragment, header: ActionBarMenuItem) {
        actionHeaders[fragment] = WeakReference(header)
        if (!enabled()) return
        val type = if (fragment is ChatActivity) MenuItemRecord.MenuType.CHAT_ACTION_MENU else MenuItemRecord.MenuType.PROFILE_ACTION_MENU
        if (PluginsController.getInstance().getMenuItemsFor(type).isEmpty()) return
        val title = LocaleController.getString(R.string.InuPlugins)
        if (fragment is ChatActivity) {
            header.lazilyAddSubItem(ACTION_OPTION, R.drawable.msg_settings_old, title)
        } else {
            header.addSubItem(ACTION_OPTION, R.drawable.msg_settings_old, title)
        }
    }

    @JvmStatic
    fun handleActionMenuOption(option: Int, fragment: BaseFragment?, account: Int, dialogId: Long): Boolean {
        if (option != ACTION_OPTION || fragment == null) return false
        val header = actionHeaders[fragment]?.get() ?: return true
        val context = context(fragment, account, dialogId)
        val type = if (fragment is ChatActivity) MenuItemRecord.MenuType.CHAT_ACTION_MENU else MenuItemRecord.MenuType.PROFILE_ACTION_MENU
        val records = rows(type, context)
        header.closeSubMenu()
        if (records.isNotEmpty()) {
            val menu = ItemOptions.makeOptions(fragment, header)
            records.forEach { item -> menu.add(icon(fragment, item), item.text, item.subtext) { click(item, context) } }
            menu.show()
        }
        return true
    }

    @JvmStatic
    fun addGlobalMenu(fragment: BaseFragment, menu: ItemOptions) {
        val context = context(fragment, fragment.currentAccount, 0L)
        val records = (rows(MenuItemRecord.MenuType.MAIN_MENU, context) + rows(MenuItemRecord.MenuType.DRAWER_MENU, context))
            .sortedByDescending { it.priority }
        if (records.isNotEmpty()) menu.addGap()
        records.forEach { item -> menu.add(icon(fragment, item), item.text, item.subtext) { click(item, context) } }
    }
}
