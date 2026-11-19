package desu.inugram.helpers.plugins

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import desu.inugram.InuConfig
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions
import desu.inugram.ui.settings.SettingsPageActivity
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
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
    private var installHelperClass: Class<*>? = null
    @Volatile
    private var intentsDispatcherClass: Class<*>? = null
    @Volatile
    private var sinkGateClass: Class<*>? = null
    @Volatile
    private var filesControllerClass: Class<*>? = null

    @Volatile
    private var hookResultCancelMethod: Method? = null
    @Volatile
    private var hookResultValueField: Field? = null

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
        try {
            controller()?.javaClass?.getMethod("init", Context::class.java)?.invoke(controller(), context)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
        setDeveloperMode(InuConfig.PLUGINS_DEV_MODE.value)
        setCompactView(InuConfig.PLUGINS_COMPACT_LIST.value)
        observeUpdates()
    }

    @JvmStatic
    fun onAppEvent(event: String) {
        if (event == "app_start") return
        invokeController("executeOnAppEvent", arrayOf(String::class.java), arrayOf(event))
    }

    @JvmStatic
    fun setEngineEnabled(enabled: Boolean) {
        if (enabled) InuConfig.PYTHON_PLUGINS.value = true
        invokeController("setEngineEnabled", arrayOf(Boolean::class.javaPrimitiveType), arrayOf(enabled))
        if (!enabled) InuConfig.PYTHON_PLUGINS.value = false
    }

    @JvmStatic
    fun isEngineEnabled(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("isEngineEnabled").invoke(c) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun setDeveloperMode(enabled: Boolean) {
        invokeController("setDeveloperMode", arrayOf(Boolean::class.javaPrimitiveType), arrayOf(enabled))
    }

    @JvmStatic
    fun setCompactView(enabled: Boolean) {
        invokeController("setCompactView", arrayOf(Boolean::class.javaPrimitiveType), arrayOf(enabled))
    }

    @JvmStatic
    fun setSafeMode(enabled: Boolean) {
        invokeController("setSafeMode", arrayOf(Boolean::class.javaPrimitiveType), arrayOf(enabled))
    }

    @JvmStatic
    fun syncSafeMode(enabled: Boolean) {
        if (!InuConfig.PYTHON_PLUGINS.value) return
        val c = controller() ?: return
        try {
            val current = c.javaClass.getMethod("isSafeMode").invoke(c) as? Boolean ?: false
            if (current != enabled) {
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
            val values = c.javaClass.getMethod("getPluginsSnapshot").invoke(c) as? Iterable<*> ?: return emptyList()
            values.mapNotNull { plugin ->
                if (plugin == null) return@mapNotNull null
                val type = plugin.javaClass
                val id = type.getField("id").get(plugin) as? String ?: return@mapNotNull null
                val name = type.getField("name").get(plugin) as? String ?: id
                val pinned = getControllerClass()?.getMethod("isPluginPinned", String::class.java)
                    ?.invoke(null, id) as? Boolean ?: false
                PluginEntry(
                    id = id,
                    name = name,
                    description = type.getField("description").get(plugin) as? String,
                    author = type.getField("author").get(plugin) as? String,
                    version = type.getField("version").get(plugin) as? String,
                    loadError = type.getField("loadError").get(plugin) as? String,
                    hasSettings = type.getField("hasSettings").getBoolean(plugin),
                    enabled = type.getField("enabled").getBoolean(plugin),
                    pinned = pinned,
                    icon = runCatching { type.getField("icon").get(plugin) as? String }.getOrNull(),
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

    private val PY_GRANTS = mapOf(
        "messages.read" to listOf("account.read", "onUpdate"),
        "messages.send" to listOf("account.write", "interceptSendMessage"),
        "network" to listOf("fetch"),
        "files" to listOf("fs", "unsafe.fs"),
        "intents" to listOf("openUrl"),
        "hooks" to listOf("unsafe.xposed"),
        "native" to listOf("unsafe.jvm"),
    )

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

    private val PY_DISPLAY = mapOf(
        "messages.read" to "account.read",
        "messages.send" to "account.write",
        "network" to "fetch",
        "files" to "unsafe.fs",
        "intents" to "openUrl",
        "hooks" to "unsafe.xposed",
        "native" to "unsafe.jvm",
    )

    @JvmStatic
    fun grantDisplayToken(key: String): String? = PY_DISPLAY[key]

    @JvmStatic
    fun isPython(plugin: Plugin): Boolean = plugin.id.startsWith(PY_PREFIX)

    private fun pyId(plugin: Plugin): String = plugin.id.removePrefix(PY_PREFIX)

    @JvmStatic
    fun hasSettings(plugin: Plugin): Boolean = isPython(plugin) && settingsIds.contains(pyId(plugin))

    @JvmStatic
    fun isPinned(plugin: Plugin): Boolean = pinnedIds.contains(pyId(plugin))

    private fun grantsFor(id: String): List<String> = try {
        val cls = Class.forName("app.exteraless.plugins.PluginPermissions")
        val keys = cls.getMethod("getEffective", String::class.java).invoke(null, id) as? Collection<*>
        keys.orEmpty().filterIsInstance<String>().flatMap { PY_GRANTS[it].orEmpty() }.distinct()
    } catch (t: Throwable) {
        FileLog.e(t)
        emptyList()
    }

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
                grants = grantsFor(entry.id),
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
            val callback = org.telegram.messenger.Utilities.Callback<String> { error -> onDone(error) }
            c.javaClass.getMethod(
                "setPluginEnabled", String::class.java, Boolean::class.javaPrimitiveType,
                org.telegram.messenger.Utilities.Callback::class.java,
            ).invoke(c, id, enabled, callback)
        } catch (t: Throwable) {
            FileLog.e(t)
            onDone(t.message ?: id)
        }
    }

    @JvmStatic
    fun reloadPlugin(id: String, onDone: () -> Unit) {
        val c = controller() ?: return onDone()
        try {
            c.javaClass.getMethod("reloadPlugin", String::class.java, Runnable::class.java)
                .invoke(c, id, Runnable { onDone() })
        } catch (t: Throwable) {
            FileLog.e(t)
            onDone()
        }
    }

    @JvmStatic
    fun deletePlugin(id: String, onDone: (String?) -> Unit) {
        val c = controller() ?: return onDone(id)
        try {
            val callback = org.telegram.messenger.Utilities.Callback<String> { error -> onDone(error) }
            c.javaClass.getMethod("deletePlugin", String::class.java, org.telegram.messenger.Utilities.Callback::class.java)
                .invoke(c, id, callback)
        } catch (t: Throwable) {
            FileLog.e(t)
            onDone(t.message ?: id)
        }
    }

    @JvmStatic
    fun setPluginPinned(id: String, pinned: Boolean) {
        try {
            getControllerClass()?.getMethod("setPluginPinned", String::class.java, Boolean::class.javaPrimitiveType)
                ?.invoke(null, id, pinned)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    @JvmStatic
    fun openPluginSettings(id: String, fragment: BaseFragment) {
        try {
            Class.forName("desu.inugram.helpers.plugins.PythonPluginUi")
                .getMethod("openPluginSettingsById", String::class.java, BaseFragment::class.java)
                .invoke(null, id, fragment)
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
        if (activity == null || message == null) return false
        return try {
            val cls = getInstallHelperClass() ?: return false
            val m = cls.getMethod("handleMessageTap", Activity::class.java, MessageObject::class.java)
            m.invoke(null, activity, message) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun handleViewIntent(activity: Activity?, uri: Uri?): Boolean {
        if (activity == null || uri == null) return false
        return try {
            val cls = getInstallHelperClass() ?: return false
            val m = cls.getMethod("handleViewIntent", Activity::class.java, Uri::class.java)
            m.invoke(null, activity, uri) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun isSettingsLink(path: String?, uri: Uri?): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value || path == null || uri == null) return false
        val isOurHost = "entinysettings".equals(path, ignoreCase = true) ||
                "entinygram".equals(path, ignoreCase = true) ||
                "inugram".equals(path, ignoreCase = true)
        val p = uri.getQueryParameter("p")
        if (isOurHost && !p.isNullOrEmpty()) return true
        return try {
            val cls = getControllerClass() ?: return false
            val m = cls.getMethod("isSettingsLink", String::class.java, Uri::class.java)
            m.invoke(null, path, uri) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun openSettingsLink(uri: Uri?) {
        if (!InuConfig.PYTHON_PLUGINS.value || uri == null) return
        try {
            val cls = getControllerClass() ?: return
            val m = cls.getMethod("openSettingsLink", Uri::class.java)
            m.invoke(null, uri)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }


    @JvmStatic
    fun hasIntentHandlers(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return false
        return try {
            val cls = getIntentsDispatcherClass() ?: return false
            cls.getMethod("hasHandlers").invoke(null) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun buildIntentContext(intent: Intent?, account: Int, fromIntent: Boolean): Any? {
        if (!InuConfig.PYTHON_PLUGINS.value) return null
        return try {
            val cls = getIntentsDispatcherClass() ?: return null
            val m = cls.getMethod("buildIntentContext", Intent::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            m.invoke(null, intent, account, fromIntent)
        } catch (t: Throwable) {
            FileLog.e(t)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun buildUriContext(uri: Uri?, account: Int, fromIntent: Boolean): Any? {
        if (!InuConfig.PYTHON_PLUGINS.value) return null
        return try {
            val cls = getIntentsDispatcherClass() ?: return null
            val m = cls.getMethod("buildUriContext", Uri::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            m.invoke(null, uri, account, fromIntent)
        } catch (t: Throwable) {
            FileLog.e(t)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun dispatchIntentBefore(context: Any?): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value || context == null) return false
        return try {
            val cls = getIntentsDispatcherClass() ?: return false
            val m = cls.getMethod("dispatchBefore", Map::class.java)
            m.invoke(null, context) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun dispatchIntentAfter(context: Any?) {
        if (!InuConfig.PYTHON_PLUGINS.value || context == null) return
        try {
            val cls = getIntentsDispatcherClass() ?: return
            val m = cls.getMethod("dispatchAfter", Map::class.java)
            m.invoke(null, context)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }


    @JvmStatic
    fun allowDatabaseAccess(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return true
        return try {
            val cls = getSinkGateClass() ?: return true
            cls.getMethod("allowDatabaseAccess").invoke(null) as? Boolean ?: true
        } catch (t: Throwable) {
            FileLog.e(t)
            true
        }
    }

    @JvmStatic
    fun hasSendMessageHooks(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("hasSendMessageHooks").invoke(c) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun executeOnSendMessageHook(account: Int, params: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod("executeOnSendMessageHook", Int::class.javaPrimitiveType, Any::class.java)
            val res = m.invoke(c, account, params)
            unwrapHookResult(res)
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun hasAnyRequestHooks(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("hasAnyRequestHooks").invoke(c) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun hookName(obj: Any?): String {
        if (obj == null) return ""
        val cls = obj.javaClass
        var name = hookNames[cls]
        if (name == null) {
            name = cls.simpleName
            hookNames[cls] = name
        }
        return name
    }

    @JvmStatic
    fun executePreRequestHook(account: Int, name: String, request: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod("executePreRequestHook", Int::class.javaPrimitiveType, String::class.java, Any::class.java)
            val res = m.invoke(c, account, name, request)
            unwrapHookResult(res)
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
            val m = c.javaClass.getMethod("executePostRequestHook", Int::class.javaPrimitiveType, String::class.java, Any::class.java, Any::class.java)
            val res = m.invoke(c, account, name, response, error)
            unwrapHookResult(res)
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun hasAnyUpdatesContainerHooks(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("hasAnyUpdatesContainerHooks").invoke(c) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun executeOnUpdatesHook(account: Int, name: String, updates: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod("executeOnUpdatesHook", Int::class.javaPrimitiveType, String::class.java, Any::class.java)
            val res = m.invoke(c, account, name, updates)
            unwrapHookResult(res)
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }

    @JvmStatic
    fun hasAnyUpdateHooks(): Boolean {
        if (!InuConfig.PYTHON_PLUGINS.value) return false
        val c = controller() ?: return false
        return try {
            c.javaClass.getMethod("hasAnyUpdateHooks").invoke(c) as? Boolean ?: false
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    @JvmStatic
    fun executeOnUpdateHook(account: Int, name: String, update: Any?): HookResult {
        if (!InuConfig.PYTHON_PLUGINS.value) return HookResult.DEFAULT
        val c = controller() ?: return HookResult.DEFAULT
        return try {
            val m = c.javaClass.getMethod("executeOnUpdateHook", Int::class.javaPrimitiveType, String::class.java, Any::class.java)
            val res = m.invoke(c, account, name, update)
            unwrapHookResult(res)
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
        return try {
            val cls = getFilesControllerClass() ?: return false
            val m = cls.getMethod(
                "dispatchOpenFile",
                File::class.java, String::class.java, String::class.java,
                MessageObject::class.java, Activity::class.java,
                org.telegram.ui.ActionBar.BaseFragment::class.java, Int::class.javaPrimitiveType
            )
            m.invoke(null, file, fileName, mimeType, message, activity, parentFragment, account) as? Boolean ?: false
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
        if (!InuConfig.PYTHON_PLUGINS.value && method !in setOf("init", "setEngineEnabled", "setDeveloperMode", "setCompactView", "setSafeMode")) return
        try {
            val c = controller() ?: return
            c.javaClass.getMethod(method, *types).invoke(c, *args)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    private fun controller(): Any? {
        return try {
            val cls = getControllerClass() ?: return null
            cls.getMethod("getInstance").invoke(null)
        } catch (t: Throwable) {
            null
        }
    }

    private fun getControllerClass(): Class<*>? {
        if (controllerClass == null) {
            try {
                controllerClass = Class.forName("app.exteraless.plugins.PluginsController")
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
        return controllerClass
    }

    private fun getInstallHelperClass(): Class<*>? {
        if (installHelperClass == null) {
            try {
                installHelperClass = Class.forName("app.exteraless.plugins.PluginInstallHelper")
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
        return installHelperClass
    }

    private fun getIntentsDispatcherClass(): Class<*>? {
        if (intentsDispatcherClass == null) {
            try {
                intentsDispatcherClass = Class.forName("app.exteraless.plugins.intents.IntentsDispatcher")
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
        return intentsDispatcherClass
    }

    private fun getSinkGateClass(): Class<*>? {
        if (sinkGateClass == null) {
            try {
                sinkGateClass = Class.forName("app.exteraless.plugins.PluginSinkGate")
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
        return sinkGateClass
    }

    private fun getFilesControllerClass(): Class<*>? {
        if (filesControllerClass == null) {
            try {
                filesControllerClass = Class.forName("app.exteraless.plugins.files.FilesControllerJava")
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
        return filesControllerClass
    }

    private fun unwrapHookResult(result: Any?): HookResult {
        if (result == null) return HookResult.DEFAULT
        return try {
            var m = hookResultCancelMethod
            if (m == null) {
                m = result.javaClass.getMethod("isCancel")
                hookResultCancelMethod = m
            }
            var f = hookResultValueField
            if (f == null) {
                f = result.javaClass.getField("value")
                hookResultValueField = f
            }
            val isCancel = m.invoke(result) as? Boolean ?: false
            val strategy = result.javaClass.getField("strategy").get(result).toString()
            val value = if (strategy == "MODIFY" || strategy == "MODIFY_FINAL") f.get(result) else null
            HookResult(isCancel, value)
        } catch (t: Throwable) {
            FileLog.e(t)
            HookResult.DEFAULT
        }
    }
}
