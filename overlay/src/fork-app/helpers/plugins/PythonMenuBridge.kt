package desu.inugram.helpers.plugins

import com.exteragram.messenger.plugins.PluginsConstants
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.hooks.MenuItemRecord
import desu.inugram.InuConfig
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

    private fun enabled() = InuConfig.PYTHON_PLUGINS.value && PluginsController.getInstance().isInitialized()

    private fun context(fragment: BaseFragment, account: Int, dialogId: Long, message: MessageObject? = null): HashMap<String, Any> {
        val controller = MessagesController.getInstance(account)
        val chat = if (fragment is ChatActivity) fragment.currentChat else if (dialogId < 0) controller.getChat(-dialogId) else null
        val user = if (fragment is ChatActivity) fragment.currentUser else if (dialogId > 0) controller.getUser(dialogId) else null
        return hashMapOf<String, Any>(
            "fragment" to fragment,
            "account" to account,
            "dialog_id" to dialogId,
        ).apply {
            fragment.parentActivity?.let { put("context", it) }
            message?.let { put("message", it) }
            chat?.let {
                put("chat", it)
                put("chatId", it.id)
            }
            user?.let {
                put("user", it)
                put("userId", it.id)
            }
            (fragment as? ChatActivity)?.currentEncryptedChat?.let { put("encryptedChat", it) }
        }
    }

    private fun rows(menuType: String, context: Map<String, Any>): List<MenuItemRecord> =
        if (enabled()) PluginsController.getInstance().getMenuItemsForLocation(menuType, context) else emptyList()

    private fun actionRows(fragment: BaseFragment, context: Map<String, Any>): List<MenuItemRecord> {
        if (fragment is ChatActivity) return rows(PluginsConstants.MenuItemTypes.CHAT_ACTION_MENU, context)
        return (rows(PluginsConstants.MenuItemTypes.PROFILE_ACTION_MENU, context) +
            rows(PluginsConstants.MenuItemTypes.CHAT_ACTION_MENU, context))
            .distinctBy { it.pluginId + "/" + it.itemId }
    }

    private fun icon(item: MenuItemRecord): Int =
        item.iconResId.takeIf { it != 0 } ?: R.drawable.msg_settings_old

    @JvmStatic
    fun fillMessageMenu(items: ArrayList<CharSequence>, options: ArrayList<Int>, icons: ArrayList<Int>, activity: ChatActivity, message: MessageObject) {
        val records = rows(PluginsConstants.MenuItemTypes.MESSAGE_CONTEXT_MENU, context(activity, activity.currentAccount, activity.dialogId, message))
        messageMenus[activity] = records
        records.forEachIndexed { index, item ->
            items.add(item.text ?: return@forEachIndexed)
            options.add(MESSAGE_OPTION_BASE - index)
            icons.add(icon(item))
        }
    }

    @JvmStatic
    fun handleMessageMenuOption(option: Int, activity: ChatActivity, message: MessageObject): Boolean {
        val index = MESSAGE_OPTION_BASE - option
        val item = messageMenus[activity]?.getOrNull(index) ?: return false
        item.executeClick(context(activity, activity.currentAccount, activity.dialogId, message))
        return true
    }

    @JvmStatic
    fun addActionMenu(fragment: BaseFragment, header: ActionBarMenuItem) {
        actionHeaders[fragment] = WeakReference(header)
        if (!enabled()) return
        val ctx = context(fragment, fragment.currentAccount, 0L)
        if (actionRows(fragment, ctx).isEmpty()) return
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
        val ctx = context(fragment, account, dialogId)
        val records = actionRows(fragment, ctx)
        header.closeSubMenu()
        if (records.isNotEmpty()) {
            val menu = ItemOptions.makeOptions(fragment, header)
            records.forEach { item ->
                menu.add(icon(item), item.text ?: return@forEach, item.subtext) {
                    item.executeClick(ctx)
                }
            }
            menu.show()
        }
        return true
    }

    @JvmStatic
    fun addGlobalMenu(fragment: BaseFragment, menu: ItemOptions) {
        val ctx = context(fragment, fragment.currentAccount, 0L)
        val records = (rows(PluginsConstants.MenuItemTypes.MAIN_MENU, ctx) +
            rows(PluginsConstants.MenuItemTypes.DRAWER_MENU, ctx))
            .sortedByDescending { it.priority }
        if (records.isNotEmpty()) menu.addGap()
        records.forEach { item ->
            menu.add(icon(item), item.text ?: return@forEach, item.subtext) {
                item.executeClick(ctx)
            }
        }
    }
}
