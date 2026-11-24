package desu.inugram.helpers.plugins

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import desu.inugram.InuConfig
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.LaunchActivity
import desu.inugram.ui.settings.SettingsPageActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object PythonEngineBridge {

    data class PluginEntry(
        val id: String,
        val name: String,
        val description: String?,
        val author: String?,
        val version: String?,
        val loadError: String?,
        val hasSettings: Boolean,
        val enabled: Boolean,
        val pinned: Boolean,
        val icon: String? = null,
    )

    @JvmStatic
    fun exteraIconSpec(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.startsWith("inu://") || s.startsWith("tg://")) return s
        val slash = s.lastIndexOf('/')
        if (slash <= 0) return null
        val idx = s.substring(slash + 1).trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return "tg://addstickers?set=${Uri.encode(s.substring(0, slash))}&idx=$idx"
    }

    class HookResult(
        @JvmField val isCancel: Boolean,
        @JvmField val value: Any?
    ) {
        @Suppress("UNCHECKED_CAST")
        fun <T> replacement(type: Class<T>): T? =
            if (!isCancel && type.isInstance(value)) type.cast(value) else null

        companion object {
            @JvmField
            val DEFAULT = HookResult(false, null)
        }
    }

    private val hookNames = ConcurrentHashMap<Class<*>, String>()

    @Volatile
    private var controllerClass: Class<*>? = null
    @Volatile
    private var configClass: Class<*>? = null

    @JvmStatic
    fun available(): Boolean = InuConfig.PYTHON_PLUGINS.value && controller() != null

    @JvmStatic
    fun createSettingsActivity(): SettingsPageActivity? = try {
        Class.forName("desu.inugram.helpers.plugins.PythonPluginUi")
            .getMethod("createActivity").invoke(null) as? SettingsPageActivity
    } catch (t: Throwable) {
        FileLog.e(t)
        null
    }

    @JvmStatic
    fun init(context: Context) {
        setConfigFlag("pluginsEngine", InuConfig.PYTHON_PLUGINS.value)
        setDeveloperMode(InuConfig.PLUGINS_DEV_MODE.value)
        setCompactView(InuConfig.PLUGINS_COMPACT_LIST.value)
        observeUpdates()
        if (!InuConfig.PYTHON_PLUGINS.value) return
        try {
            controller()?.let { c ->
                c.javaClass.getMethod("init").invoke(c)
            }
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun onAppEvent(event: String) {
        if (event == "app_start" || !InuConfig.PYTHON_PLUGINS.value) return
        invokeController("executeOnAppEvent", arrayOf(String::class.java), arrayOf(event))
    }

    @JvmStatic
    fun setEngineEnabled(enabled: Boolean) {
        InuConfig.PYTHON_PLUGINS.value = enabled
        setConfigFlag("pluginsEngine", enabled)
        val c = controller() ?: return
        try {
            if (enabled) {
                c.javaClass.getMethod("init").invoke(c)
            } else {
                c.javaClass.getMethod("shutdown", Runnable::class.java).invoke(c, null)
            }
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun isEngineEnabled(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("isInitialized").invoke(c) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun setDeveloperMode(enabled: Boolean) {
        setConfigProp("setPluginsDevMode", enabled)
    }

    @JvmStatic
    fun setCompactView(enabled: Boolean) {
        setConfigProp("setPluginsCompactView", enabled)
    }

    @JvmStatic
    fun setSafeMode(enabled: Boolean) {
        setConfigProp("setPluginsSafeMode", enabled)
    }

    @JvmStatic
    fun syncSafeMode(enabled: Boolean) {
        if (!InuConfig.PYTHON_PLUGINS.value) return
        val c = controller() ?: return
        try {
            val current = getConfigProp("getPluginsSafeMode")
            if (current != enabled) {
                setSafeMode(enabled)
                c.javaClass.getMethod("restart", Boolean::class.javaPrimitiveType).invoke(c, enabled)
            }
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun plugins(): List<PluginEntry> {
        val c = controller() ?: return emptyList()
        return try {
            val map = c.javaClass.getMethod("getPlugins").invoke(c) as? Map<*, *> ?: return emptyList()
            val pinnedSet = getPinnedSet()
            map.values.mapNotNull { plugin ->
                if (plugin == null) return@mapNotNull null
                val type = plugin.javaClass
                val id = type.getMethod("getId").invoke(plugin) as? String ?: return@mapNotNull null
                val name = type.getMethod("getName").invoke(plugin) as? String ?: id
                val error = type.getMethod("getError").invoke(plugin) as? Throwable
                val hasSettings = c.javaClass.getMethod("hasPluginSettings", String::class.java)
                    .invoke(c, id) as? Boolean ?: false
                PluginEntry(
                    id = id,
                    name = name,
                    description = type.getMethod("getDescription").invoke(plugin) as? String,
                    author = type.getMethod("getAuthor").invoke(plugin) as? String,
                    version = type.getMethod("getVersion").invoke(plugin) as? String,
                    loadError = error?.localizedMessage ?: error?.toString(),
                    hasSettings = hasSettings,
                    enabled = type.getMethod("isEnabled").invoke(plugin) as? Boolean ?: false,
                    pinned = id in pinnedSet,
                    icon = runCatching { type.getMethod("getIcon").invoke(plugin) as? String }.getOrNull(),
                )
            }.sortedWith(compareByDescending<PluginEntry> { it.pinned }.thenBy { it.name.lowercase() })
        } catch (t: Throwable) {
            FileLog.e(t)
            emptyList()
        }
    }

    private const val PY_PREFIX = "py:"
    private val mirrorCache = ConcurrentHashMap<String, Plugin>()
    private val settingsIds = ConcurrentHashMap.newKeySet<String>()
    private val pinnedIds = ConcurrentHashMap.newKeySet<String>()

    private val updatesDelegate = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (id == NotificationCenter.pluginsUpdated) changed()
        }
    }

    @JvmStatic
    fun observeUpdates() {
        NotificationCenter.getGlobalInstance().removeObserver(updatesDelegate, NotificationCenter.pluginsUpdated)
        NotificationCenter.getGlobalInstance().addObserver(updatesDelegate, NotificationCenter.pluginsUpdated)
    }

    private fun changed() {
        mirrors()
        PluginManager.notifyChanged()
    }

    @JvmStatic
    fun grantDisplayToken(key: String): String? = null

    @JvmStatic
    fun isPython(plugin: Plugin): Boolean = plugin.id.startsWith(PY_PREFIX)

    private fun pyId(plugin: Plugin): String = plugin.id.removePrefix(PY_PREFIX)

    @JvmStatic
    fun hasSettings(plugin: Plugin): Boolean = isPython(plugin) && settingsIds.contains(pyId(plugin))

    @JvmStatic
    fun isPinned(plugin: Plugin): Boolean = pinnedIds.contains(pyId(plugin))

    @JvmStatic
    fun mirrors(): List<Plugin> {
        val entries = plugins()
        val live = HashSet<String>()
        val result = entries.map { entry ->
            val key = PY_PREFIX + entry.id
            live.add(key)
            val manifest = desu.inugram.core.plugins.PluginManifest(
                name = entry.name,
                author = entry.author?.takeIf { it.isNotBlank() },
                declaredId = entry.id.takeIf { id -> id.isNotEmpty() && id.none { it.isWhitespace() || it.isISOControl() } },
                version = entry.version?.takeIf { it.isNotBlank() },
                description = entry.description?.takeIf { it.isNotBlank() },
                localizedDescriptions = emptyMap(),
                icon = exteraIconSpec(entry.icon),
                grants = emptyList(),
                pluginApi = null,
                platform = null,
                raw = emptyMap(),
            )
            val plugin = mirrorCache.getOrPut(key) { Plugin(key, File(entry.id), "", manifest) }
            plugin.manifest = manifest
            plugin.enabled = entry.enabled
            plugin.failure = entry.loadError?.takeIf { it.isNotBlank() }?.let { PluginFailure(PluginFailure.Site.LOAD, it) }
            if (entry.hasSettings) settingsIds.add(entry.id) else settingsIds.remove(entry.id)
            if (entry.pinned) pinnedIds.add(entry.id) else pinnedIds.remove(entry.id)
            plugin
        }
        mirrorCache.keys.retainAll(live)
        return result
    }

    @JvmStatic
    fun setEnabled(plugin: Plugin, enabled: Boolean) {
        setPluginEnabled(pyId(plugin), enabled) { changed() }
    }

    @JvmStatic
    fun reload(plugin: Plugin) {
        reloadPlugin(pyId(plugin)) { changed() }
    }

    @JvmStatic
    fun remove(plugin: Plugin) {
        deletePlugin(pyId(plugin)) { changed() }
    }

    @JvmStatic
    fun openSettings(plugin: Plugin, fragment: BaseFragment) = openPluginSettings(pyId(plugin), fragment)

    @JvmStatic
    fun openPermissions(plugin: Plugin, fragment: BaseFragment) = openPluginPermissions(pyId(plugin), fragment)

    @JvmStatic
    fun togglePinned(plugin: Plugin) {
        setPluginPinned(pyId(plugin), !isPinned(plugin))
        changed()
    }

    @JvmStatic
    fun setPluginEnabled(id: String, enabled: Boolean, onDone: (String?) -> Unit) {
        val c = controller() ?: return onDone(id)
        try {
            val callback = Utilities.Callback<String> { error -> onDone(error) }
            c.javaClass.getMethod(
                "setPluginEnabled", String::class.java, Boolean::class.javaPrimitiveType,
                Utilities.Callback::class.java,
            ).invoke(c, id, enabled, callback)
        } catch (t: Throwable) {
            FileLog.e(t)
            onDone(t.message ?: id)
        }
    }

    @JvmStatic
    fun reloadPlugin(id: String, onDone: () -> Unit) {
        setPluginEnabled(id, false) {
            setPluginEnabled(id, true) { onDone() }
        }
    }

    @JvmStatic
    fun deletePlugin(id: String, onDone: (String?) -> Unit) {
        val c = controller() ?: return onDone(id)
        try {
            val callback = Utilities.Callback<String> { error -> onDone(error) }
            c.javaClass.getMethod("deletePlugin", String::class.java, Utilities.Callback::class.java)
                .invoke(c, id, callback)
        } catch (t: Throwable) {
            FileLog.e(t)
            onDone(t.message ?: id)
        }
    }

    @JvmStatic
    fun setPluginPinned(id: String, pinned: Boolean) {
        try {
            val cls = getConfigClass() ?: return
            val prefs = cls.getMethod("getPinnedPlugins").invoke(null) as? MutableSet<*> ?: return
            @Suppress("UNCHECKED_CAST")
            val next = HashSet(prefs as Set<String>)
            if (pinned) next.add(id) else next.remove(id)
            val editor = cls.getMethod("getEditor").invoke(null) as? android.content.SharedPreferences.Editor
            editor?.putStringSet("pinnedPlugins", next)?.apply()
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun openPluginSettings(id: String, fragment: BaseFragment) {
        val c = controller() ?: return
        try {
            c.javaClass.getMethod("openPluginSettings", BaseFragment::class.java, String::class.java, String::class.java)
                .invoke(c, fragment, id, null)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun openPluginPermissions(id: String, fragment: BaseFragment) {
        try {
            Class.forName("desu.inugram.helpers.plugins.PythonPluginUi")
                .getMethod("openPermissions", BaseFragment::class.java, String::class.java)
                .invoke(null, fragment, id)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun handleMessageTap(activity: Activity?, message: MessageObject?): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value || activity == null || message == null) return false
        val name = message.documentName ?: return false
        if (!name.endsWith(".plugin", ignoreCase = true)) return false
        val fragment = LaunchActivity.getSafeLastFragment() ?: return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("showInstallDialog", BaseFragment::class.java, MessageObject::class.java, String::class.java)
                .invoke(c, fragment, message, null)
            true
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun handleViewIntent(activity: Activity?, uri: Uri?): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value || activity == null || uri == null) return false
        val path = uri.path ?: return false
        if (!path.endsWith(".plugin", ignoreCase = true)) return false
        val fragment = LaunchActivity.getSafeLastFragment() ?: return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("showInstallDialog", BaseFragment::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(c, fragment, path, false)
            true
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun isSettingsLink(path: String?, uri: Uri?): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value || path == null || uri == null) return false
        return !uri.getQueryParameter("plugin").isNullOrEmpty()
    }

    @JvmStatic
    fun openSettingsLink(uri: Uri?) {
        if (!InuConfig.PYTHON_PLUGINS.value || uri == null) return
        val pluginId = uri.getQueryParameter("plugin") ?: return
        val setting = uri.getQueryParameter("setting")
        val fragment = LaunchActivity.getSafeLastFragment() ?: return
        val c = controller() ?: return
        try {
            c.javaClass.getMethod("openPluginSettings", BaseFragment::class.java, String::class.java, String::class.java)
                .invoke(c, fragment, pluginId, setting)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun hasIntentHandlers(): Boolean = false

    @JvmStatic
    fun buildIntentContext(intent: Intent?, account: Int, fromIntent: Boolean): Any? = null

    @JvmStatic
    fun buildUriContext(uri: Uri?, account: Int, fromIntent: Boolean): Any? = null

    @JvmStatic
    fun dispatchIntentBefore(context: Any?): Boolean = false

    @JvmStatic
    fun dispatchIntentAfter(context: Any?) {}

    @JvmStatic
    fun allowDatabaseAccess(): Boolean = true

    @JvmStatic
    fun hasSendMessageHooks(): Boolean = isEngineEnabled()

    @JvmStatic
    fun executeOnSendMessageHook(account: Int, params: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value || params == null) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod(
                "executeSendMessageHook",
                Int::class.javaPrimitiveType,
                org.telegram.messenger.SendMessagesHelper.SendMessageParams::class.java,
            )
            val res = m.invoke(c, account, params)
            when {
                res == null -> HookResult(true, null)
                res !== params -> HookResult(false, res)
                else -> HookResult.DEFAULT
            }
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun hasAnyRequestHooks(): Boolean = isEngineEnabled()

    @JvmStatic
    fun hookName(obj: Any?): String {
        if (obj == null) return ""
        val cls = obj.javaClass
        return hookNames.getOrPut(cls) { cls.simpleName }
    }

    @JvmStatic
    fun executePreRequestHook(account: Int, name: String, request: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value || request !is TLObject) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod(
                "executePreRequestHook",
                String::class.java,
                Int::class.javaPrimitiveType,
                TLObject::class.java,
            )
            val res = m.invoke(c, name, account, request)
            when {
                res == null -> HookResult(true, null)
                res !== request -> HookResult(false, res)
                else -> HookResult.DEFAULT
            }
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun executePostRequestHook(account: Int, name: String, response: Any?, error: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod(
                "executePostRequestHook",
                String::class.java,
                Int::class.javaPrimitiveType,
                TLObject::class.java,
                TLRPC.TL_error::class.java,
            )
            val res = m.invoke(c, name, account, response as? TLObject, error as? TLRPC.TL_error) ?: return HookResult.DEFAULT
            val newResponse = res.javaClass.getMethod("getResponse").invoke(res)
            if (newResponse !== response) HookResult(false, newResponse) else HookResult.DEFAULT
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun hasAnyUpdatesContainerHooks(): Boolean = isEngineEnabled()

    @JvmStatic
    fun executeOnUpdatesHook(account: Int, name: String, updates: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value || updates !is TLRPC.Updates) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod(
                "executeUpdatesHook",
                String::class.java,
                Int::class.javaPrimitiveType,
                TLRPC.Updates::class.java,
            )
            val res = m.invoke(c, name, account, updates)
            when {
                res == null -> HookResult(true, null)
                res !== updates -> HookResult(false, res)
                else -> HookResult.DEFAULT
            }
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun hasAnyUpdateHooks(): Boolean = isEngineEnabled()

    @JvmStatic
    fun executeOnUpdateHook(account: Int, name: String, update: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value || update !is TLRPC.Update) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod(
                "executeUpdateHook",
                String::class.java,
                Int::class.javaPrimitiveType,
                TLRPC.Update::class.java,
            )
            val res = m.invoke(c, name, account, update)
            when {
                res == null -> HookResult(true, null)
                res !== update -> HookResult(false, res)
                else -> HookResult.DEFAULT
            }
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun dispatchOpenFile(
        file: File?, fileName: String?, mimeType: String?,
        message: MessageObject?, activity: Activity?, parentFragment: Any?, account: Int
    ): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value || file == null || fileName == null) return false
        if (!fileName.endsWith(".plugin", ignoreCase = true)) return false
        val fragment = (parentFragment as? BaseFragment) ?: LaunchActivity.getSafeLastFragment() ?: return false
        val c = controller() ?: return false
        return try {
            if (message != null) {
                c.javaClass.getMethod("showInstallDialog", BaseFragment::class.java, MessageObject::class.java, String::class.java)
                    .invoke(c, fragment, message, file.absolutePath)
            } else {
                c.javaClass.getMethod("showInstallDialog", BaseFragment::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(c, fragment, file.absolutePath, false)
            }
            true
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun fillMessageMenu(items: ArrayList<CharSequence>, options: ArrayList<Int>, icons: ArrayList<Int>, activity: ChatActivity, message: MessageObject) {
        invokeMenu("fillMessageMenu", arrayOf(ArrayList::class.java, ArrayList::class.java, ArrayList::class.java, ChatActivity::class.java, MessageObject::class.java), arrayOf(items, options, icons, activity, message))
    }

    @JvmStatic
    fun handleMessageMenuOption(option: Int, activity: ChatActivity, message: MessageObject): Boolean =
        invokeMenu("handleMessageMenuOption", arrayOf(Int::class.javaPrimitiveType, ChatActivity::class.java, MessageObject::class.java), arrayOf(option, activity, message)) as? Boolean ?: false

    @JvmStatic
    fun addActionMenu(fragment: BaseFragment, header: ActionBarMenuItem) {
        invokeMenu("addActionMenu", arrayOf(BaseFragment::class.java, ActionBarMenuItem::class.java), arrayOf(fragment, header))
    }

    @JvmStatic
    fun handleActionMenuOption(option: Int, fragment: BaseFragment?, account: Int, dialogId: Long): Boolean =
        invokeMenu("handleActionMenuOption", arrayOf(Int::class.javaPrimitiveType, BaseFragment::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType), arrayOf(option, fragment, account, dialogId)) as? Boolean ?: false

    @JvmStatic
    fun addGlobalMenu(fragment: BaseFragment, menu: ItemOptions) {
        invokeMenu("addGlobalMenu", arrayOf(BaseFragment::class.java, ItemOptions::class.java), arrayOf(fragment, menu))
    }

    private fun invokeMenu(method: String, types: Array<Class<*>?>, args: Array<Any?>): Any? {
        if (!InuConfig.PYTHON_PLUGINS.value) return null
        return try {
            Class.forName("desu.inugram.helpers.plugins.PythonMenuBridge").getMethod(method, *types).invoke(null, *args)
        } catch (t: Throwable) {
            FileLog.e(t)
            null
        }
    }

    private fun invokeController(method: String, types: Array<Class<*>?>, args: Array<Any?>) {
        try {
            val c = controller() ?: return
            c.javaClass.getMethod(method, *types).invoke(c, *args)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    private fun controller(): Any? = try {
        getControllerClass()?.getMethod("getInstance")?.invoke(null)
    } catch (_: Throwable) {
        null
    }

    private fun getControllerClass(): Class<*>? {
        if (controllerClass == null) {
            try {
                controllerClass = Class.forName("com.exteragram.messenger.plugins.PluginsController")
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
        return controllerClass
    }

    private fun getConfigClass(): Class<*>? {
        if (configClass == null) {
            try {
                configClass = Class.forName("com.exteragram.messenger.ExteraConfigKt")
            } catch (_: Throwable) {
            }
        }
        return configClass
    }

    private fun setConfigFlag(field: String, value: Boolean) {
        try {
            getConfigClass()?.getField(field)?.setBoolean(null, value)
        } catch (_: Throwable) {
        }
    }

    private fun setConfigProp(setter: String, value: Boolean) {
        try {
            getConfigClass()?.getMethod(setter, Boolean::class.javaPrimitiveType)?.invoke(null, value)
        } catch (_: Throwable) {
        }
    }

    private fun getConfigProp(getter: String): Boolean = try {
        getConfigClass()?.getMethod(getter)?.invoke(null) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    private fun getPinnedSet(): Set<String> = try {
        @Suppress("UNCHECKED_CAST")
        (getConfigClass()?.getMethod("getPinnedPlugins")?.invoke(null) as? Set<String>) ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }
}
