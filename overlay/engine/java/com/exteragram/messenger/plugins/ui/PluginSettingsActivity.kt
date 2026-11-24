package com.exteragram.messenger.plugins.ui

import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.models.SettingItem
import desu.inugram.ui.settings.PythonPluginSettingsActivity
import org.telegram.messenger.AndroidUtilities

class PluginSettingsActivity @JvmOverloads constructor(
    plugin: Plugin,
    title: String? = null,
    settings: List<SettingItem> = emptyList(),
    onBackPressed: Any? = null,
    target: String? = null,
) : PythonPluginSettingsActivity(plugin, title ?: target, settings, onBackPressed as? Runnable) {

    constructor(pluginId: String) : this(requirePlugin(pluginId))

    constructor(plugin: Plugin, target: String?) : this(plugin, null, emptyList(), null, target)

    fun setSettingsLinkPrefix(prefix: String?): PluginSettingsActivity = this

    fun updateItems() {
        AndroidUtilities.runOnUIThread { reloadRows() }
    }

    companion object {
        private fun requirePlugin(pluginId: String): Plugin {
            return PluginsController.getInstance().plugins[pluginId]
                ?: throw IllegalArgumentException("Plugin is not installed: $pluginId")
        }
    }
}
