package desu.inugram.ui.settings

import android.view.View
import app.exteraless.plugins.PluginCapabilityScan
import app.exteraless.plugins.PluginPermissions
import app.exteraless.plugins.PluginTrustLevel
import app.exteraless.plugins.PluginsController
import desu.inugram.helpers.plugins.PythonPluginTitles
import desu.inugram.helpers.plugins.PythonPluginUi
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class PythonPluginPermissionsActivity(private val pluginId: String) : SettingsPageActivity() {
    private val controller get() = PluginsController.getInstance()
    private var updating = false
    private val permissions = HashMap<Int, String>()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.PluginPermissions)

    override fun onFragmentCreate(): Boolean {
        controller.getPlugin(pluginId)?.let { plugin ->
            PluginCapabilityScan.ensureScanned(plugin) { listView?.adapter?.update(true) }
        }
        return super.onFragmentCreate()
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val plugin = controller.getPlugin(pluginId)
        if (plugin == null) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginPermissionsGone)))
            return
        }
        items.add(UItem.asHeader(plugin.displayName))
        val level = PluginTrustLevel.getLevel(pluginId)
        items.add(UItem.asButton(LEVEL, LocaleController.getString(R.string.PluginLevelHeader), levelNames()[level]).also {
            it.enabled = !updating
        })
        items.add(UItem.asShadow(LocaleController.getString(when (level) {
            PluginTrustLevel.TRUSTED -> R.string.PluginLevelTrustedInfo
            PluginTrustLevel.GATED -> R.string.PluginLevelGatedInfo
            else -> R.string.PluginLevelIsolatedInfo
        })))
        if (controller.isUnsafeMode) items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsUnsafeModeActive)))
        val scan = PluginCapabilityScan.load(pluginId)
        val requested = if (plugin.permissionsDeclared && !PluginCapabilityScan.isObfuscated(scan))
            PluginPermissions.getRequested(plugin) else PluginPermissions.REQUESTABLE
        val keys = PluginPermissions.sanitize(requested + PluginCapabilityScan.ordered(scan) + PluginPermissions.getEffective(pluginId))
        permissions.clear()
        keys.filter { it != PluginPermissions.UI }.forEachIndexed { index, key ->
            val id = PERMISSION_BASE + index
            permissions[id] = key
            items.add(UItem.asButtonCheck(id, PythonPluginTitles.shortTitleOf(key), PythonPluginUi.permissionInfo(key)).also {
                it.checked = PluginPermissions.has(pluginId, key)
                it.enabled = !updating && !controller.isUnsafeMode && PluginTrustLevel.allows(level, key)
            })
        }
        items.add(UItem.asShadow(LocaleController.getString(
            if (keys.none { it != PluginPermissions.UI }) R.string.PluginPermissionsNothing else R.string.PluginPermissionsHooksWarning)))
        if (!plugin.permissionsDeclared) items.add(UItem.asShadow(LocaleController.getString(R.string.PluginPermissionsLegacyInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (updating) return
        if (item.id == LEVEL) {
            RadioItemOptions.show(this, view, levelNames(), PluginTrustLevel.getLevel(pluginId)) { level ->
                val apply = { PluginTrustLevel.setLevel(pluginId, level); reload() }
                if (level == PluginTrustLevel.TRUSTED) {
                    val ctx = context ?: return@show
                    showDialog(AlertDialog.Builder(ctx, resourceProvider)
                        .setTitle(LocaleController.getString(R.string.PluginLevelTrusted))
                        .setMessage(LocaleController.getString(R.string.PluginLevelTrustedInfo))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(LocaleController.getString(R.string.PluginLevelApply)) { _, _ -> apply() }.create())
                } else apply()
            }
            return
        }
        val permission = permissions[item.id] ?: return
        if (controller.isUnsafeMode || !PluginTrustLevel.allows(pluginId, permission)) return
        if (PluginPermissions.has(pluginId, permission)) PluginPermissions.revoke(pluginId, permission)
        else PluginPermissions.grant(pluginId, permission)
        reload()
    }

    private fun reload() {
        val plugin = controller.getPlugin(pluginId) ?: return
        if (!plugin.enabled) {
            listView?.adapter?.update(true)
            return
        }
        updating = true
        listView?.adapter?.update(true)
        controller.reloadPlugin(pluginId) {
            updating = false
            listView?.adapter?.update(true)
            if (fragmentView != null) {
                val error = controller.getPlugin(pluginId)?.loadError
                if (!error.isNullOrBlank()) BulletinFactory.of(this).createErrorBulletin(error).show()
                else BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(R.string.PluginPermissionsApplied)).show()
            }
        }
    }

    private fun levelNames(): List<CharSequence> = listOf(R.string.PluginLevelIsolated, R.string.PluginLevelGated,
        R.string.PluginLevelTrusted).map { LocaleController.getString(it) }

    companion object {
        private const val LEVEL = 1
        private const val PERMISSION_BASE = 100
    }
}
