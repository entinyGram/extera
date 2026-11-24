package desu.inugram.helpers.plugins

import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsController
import desu.inugram.core.plugins.ObfuscationDetector
import desu.inugram.core.plugins.PluginAssetsParser
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.ui.settings.PluginInstallSheet
import desu.inugram.ui.settings.PythonPluginPermissionsActivity
import desu.inugram.ui.settings.PluginsActivity
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File

object PythonPluginUi {
    private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

    @JvmStatic
    fun createActivity(): SettingsPageActivity = PluginsActivity()

    @JvmStatic
    fun openPluginSettings(plugin: Plugin?, fragment: BaseFragment?, targetSetting: String?) {
        val target = fragment ?: org.telegram.ui.LaunchActivity.getSafeLastFragment() ?: return
        val resolved = plugin ?: return
        PluginsController.getInstance().openPluginSettings(target, resolved.getId(), targetSetting)
    }

    @JvmStatic
    fun openPluginSettingsById(pluginId: String, fragment: BaseFragment) {
        PluginsController.getInstance().openPluginSettings(fragment, pluginId, null)
    }

    @JvmStatic
    fun openPermissions(fragment: BaseFragment, pluginId: String) {
        fragment.presentFragment(PythonPluginPermissionsActivity(pluginId))
    }

    @JvmStatic
    fun showInstallSheet(fragment: BaseFragment, filePath: String) {
        val activity = fragment.parentActivity ?: return
        Utilities.globalQueue.postRunnable {
            val file = File(filePath)
            val textual = file.isFile && file.length() <= MAX_TEXT_BYTES
            val source = if (textual) runCatching { file.readText() }.getOrNull() ?: "" else ""
            val assets = if (textual) runCatching { PluginAssetsParser.parse(source) }.getOrDefault(emptyList()) else emptyList()
            val obfuscation = if (textual) ObfuscationDetector.detect(source.substring(0, PluginAssetsParser.findFooter(source))) else null
            val meta = PluginsController.getInstance().plugins.values.firstOrNull()
            val manifest = PluginManifest(
                name = file.nameWithoutExtension.ifBlank { LocaleController.getString(R.string.PluginsInstallTitle) },
                author = null,
                declaredId = null,
                version = null,
                description = null,
                localizedDescriptions = emptyMap(),
                icon = null,
                grants = emptyList(),
                pluginApi = null,
                platform = null,
                raw = emptyMap(),
            )
            AndroidUtilities.runOnUIThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUIThread
                PluginInstallSheet(
                    activity,
                    fragment,
                    manifest,
                    source,
                    assets,
                    obfuscation,
                    onInstall = {},
                    externalPermissions = emptyList(),
                    externalWarning = null,
                    onExternalInstall = { _, enable ->
                        val engine = PluginsController.getInstance().getPluginEngine("python")
                        if (engine != null) {
                            val params = com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams.untrusted(filePath)
                            engine.loadPluginFromFile(filePath, params, enable) { _, error ->
                                AndroidUtilities.runOnUIThread {
                                    if (!error.isNullOrBlank()) {
                                        BulletinFactory.of(fragment).createErrorBulletin(error).show()
                                    } else {
                                        PluginsController.getInstance().notifyPluginsChanged()
                                    }
                                }
                            }
                        }
                    },
                ).show()
            }
        }
    }
}
