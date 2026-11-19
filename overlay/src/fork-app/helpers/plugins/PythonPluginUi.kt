package desu.inugram.helpers.plugins

import android.app.Activity
import app.exteraless.plugins.Plugin
import app.exteraless.plugins.PluginPermissions
import app.exteraless.plugins.PluginsController
import desu.inugram.core.plugins.ObfuscationDetector
import desu.inugram.core.plugins.PluginAssetsParser
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.ui.settings.ExternalInstallPermission
import desu.inugram.ui.settings.PluginInstallSheet
import desu.inugram.ui.settings.PythonPluginPermissionsActivity
import desu.inugram.ui.settings.PythonPluginSettingsActivity
import desu.inugram.ui.settings.PluginsActivity
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import java.io.File

object PythonPluginUi {
    private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

    data class ExternalInstallResult(val granted: List<String>, val enableAfterInstall: Boolean)

    @JvmStatic
    fun createActivity(): SettingsPageActivity = PluginsActivity()

    @JvmStatic
    fun openPluginSettings(plugin: com.exteragram.messenger.plugins.Plugin?, fragment: BaseFragment?, targetSetting: String?) {
        val target = fragment ?: org.telegram.ui.LaunchActivity.getSafeLastFragment() ?: return
        val resolved = plugin as? Plugin ?: plugin?.id?.let { PluginsController.getInstance().getPlugin(it) } ?: return
        target.presentFragment(app.exteraless.plugins.ui.PluginSettingsActivity(resolved, targetSetting))
    }

    @JvmStatic
    fun openPluginSettingsById(pluginId: String, fragment: BaseFragment) {
        val plugin = PluginsController.getInstance().getPlugin(pluginId) ?: return
        openPluginSettings(plugin, fragment, null)
    }

    @JvmStatic
    fun openPermissions(fragment: BaseFragment, pluginId: String) {
        fragment.presentFragment(PythonPluginPermissionsActivity(pluginId))
    }

    fun permissionInfo(key: String): CharSequence = LocaleController.getString(when (key) {
        PluginPermissions.MESSAGES_READ -> R.string.PluginPermMessagesReadInfo
        PluginPermissions.MESSAGES_SEND -> R.string.PluginPermMessagesSendInfo
        PluginPermissions.NETWORK -> R.string.PluginPermNetworkInfo
        PluginPermissions.FILES -> R.string.PluginPermFilesInfo
        PluginPermissions.INTENTS -> R.string.PluginPermIntentsInfo
        PluginPermissions.SETTINGS -> R.string.PluginPermSettingsInfo
        PluginPermissions.HOOKS -> R.string.PluginPermHooksInfo
        PluginPermissions.NATIVE -> R.string.PluginPermNativeInfo
        else -> R.string.PluginPermUiInfo
    })

    @JvmStatic
    fun showInuInstallConsent(
        activity: Activity,
        filePath: String,
        plugin: Plugin?,
        offeredKeys: List<String>,
        onInstall: Utilities.Callback<ExternalInstallResult>,
    ) {
        Utilities.globalQueue.postRunnable {
            val file = File(filePath)
            val textual = !filePath.endsWith(".elyx", true) && !filePath.endsWith(".eaf", true) && file.length() <= MAX_TEXT_BYTES
            val source = if (textual) runCatching { file.readText() }.getOrNull() ?: return@postRunnable else ""
            val assets = if (textual) runCatching { PluginAssetsParser.parse(source) }.getOrDefault(emptyList()) else emptyList()
            val obfuscation = if (textual) ObfuscationDetector.detect(source.substring(0, PluginAssetsParser.findFooter(source))) else null
            AndroidUtilities.runOnUIThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUIThread
                val fragment = org.telegram.ui.LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread
                val permissions = offeredKeys
                    .filter { PluginPermissions.isKnown(it) && it != PluginPermissions.UI }
                    .distinct()
                    .map { key ->
                        ExternalInstallPermission(
                            key,
                            PythonPluginTitles.shortTitleOf(key),
                            permissionInfo(key),
                            selected = plugin?.id?.let { id ->
                                PluginsController.getInstance().getPlugin(id) != null &&
                                    key in PluginPermissions.getEffective(id)
                            } == true,
                        )
                    }
                val manifest = PluginManifest(
                    name = plugin?.displayName ?: LocaleController.getString(R.string.PluginsInstallTitle),
                    author = plugin?.author,
                    declaredId = plugin?.id,
                    version = plugin?.version,
                    description = plugin?.description,
                    localizedDescriptions = emptyMap(),
                    icon = PythonEngineBridge.exteraIconSpec(plugin?.icon),
                    grants = emptyList(),
                    pluginApi = null,
                    platform = null,
                    raw = emptyMap(),
                )
                val warning = if (offeredKeys.any { PluginPermissions.isDangerous(it) }) {
                    LocaleController.getString(R.string.PluginPermissionsHooksWarning)
                } else null
                PluginInstallSheet(
                    activity,
                    fragment,
                    manifest,
                    source,
                    assets,
                    obfuscation,
                    onInstall = {},
                    externalPermissions = permissions,
                    externalWarning = warning,
                    onExternalInstall = { granted, enable ->
                        onInstall.run(ExternalInstallResult(granted, enable))
                    },
                ).show()
            }
        }
    }

}
