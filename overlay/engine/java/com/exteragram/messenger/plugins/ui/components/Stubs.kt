package com.exteragram.messenger.plugins.ui.components

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.PythonPluginsEngine
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.ui.ActionBar.BaseFragment
import java.io.File

data class RequiredPluginInfo(
    val id: String,
    val name: String? = null,
)

interface PluginCellDelegate {
    fun onPluginToggle(plugin: Plugin, enabled: Boolean) {}
    fun onPluginSettings(plugin: Plugin) {}
}

class PluginRequirementsView(context: Context) : FrameLayout(context)

object PluginFileViewer {
    @JvmStatic
    fun open(fragment: BaseFragment?, file: File?, name: String?) {
        val activity = fragment?.parentActivity ?: return
        if (file == null || !file.exists()) return
        AndroidUtilities.openForView(file, name ?: file.name, "text/plain", activity, fragment.resourceProvider, false)
    }
}

class SafeModeBottomSheet(private val fragment: BaseFragment) {
    fun show() {
        FileLog.d("SafeModeBottomSheet stubbed")
    }
}

class PythonSdkUpdateAlert(
    private val fragment: BaseFragment,
    private val update: Any?,
) : android.app.Dialog(fragment.parentActivity) {
    override fun show() {
        FileLog.d("PythonSdkUpdateAlert stubbed")
    }
}

class InstallPluginBottomSheet(
    private val fragment: BaseFragment,
    private val validationResult: Any?,
    private val params: PluginInstallParams,
) {
    fun show() {
        try {
            Class.forName("desu.inugram.helpers.plugins.PythonPluginUi")
                .getMethod("showInstallSheet", BaseFragment::class.java, String::class.java)
                .invoke(null, fragment, params.filePath)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    class PluginInstallParams(
        val filePath: String,
        source: MessageObject?,
    ) {
        @JvmOverloads
        constructor(filePath: String, @Suppress("UNUSED_PARAMETER") trusted: Boolean = false) : this(filePath, null as MessageObject?)

        val trusted: Boolean = false
        var iconDrawable: Drawable? = null
        var requiredPlugins: List<RequiredPluginInfo>? = null
        var bundledPackages: List<String>? = null
        var blockedTitle: String? = null
        var blockedSubtitle: String? = null

        companion object {
            @JvmStatic
            @JvmOverloads
            fun of(messageObject: MessageObject, path: String? = null): PluginInstallParams {
                val resolved = path ?: com.exteragram.messenger.utils.chats.ChatUtils.getInstance().getPathToMessage(messageObject) ?: ""
                return PluginInstallParams(resolved, messageObject)
            }

            @JvmStatic
            fun untrusted(filePath: String): PluginInstallParams = PluginInstallParams(filePath)
        }
    }
}
