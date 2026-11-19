package desu.inugram.helpers.plugins

import app.exteraless.plugins.PluginPermissions
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

object PythonPluginTitles {

    @JvmStatic
    fun titleOf(perm: String?): CharSequence {
        if (perm == null) {
            return ""
        }
        return when (perm) {
            PluginPermissions.UI -> LocaleController.getString(R.string.PluginPermUi)
            PluginPermissions.MESSAGES_READ -> LocaleController.getString(R.string.PluginPermMessagesRead)
            PluginPermissions.MESSAGES_SEND -> LocaleController.getString(R.string.PluginPermMessagesSend)
            PluginPermissions.NETWORK -> LocaleController.getString(R.string.PluginPermNetwork)
            PluginPermissions.FILES -> LocaleController.getString(R.string.PluginPermFiles)
            PluginPermissions.INTENTS -> LocaleController.getString(R.string.PluginPermIntents)
            PluginPermissions.SETTINGS -> LocaleController.getString(R.string.PluginPermSettings)
            PluginPermissions.HOOKS -> LocaleController.getString(R.string.PluginPermHooks)
            PluginPermissions.NATIVE -> LocaleController.getString(R.string.PluginPermNative)
            else -> PluginPermissions.describe(perm)
        }
    }

    @JvmStatic
    fun shortTitleOf(perm: String?): CharSequence {
        if (perm == null) {
            return ""
        }
        return when (perm) {
            PluginPermissions.MESSAGES_READ -> LocaleController.getString(R.string.PluginPermShortMessagesRead)
            PluginPermissions.MESSAGES_SEND -> LocaleController.getString(R.string.PluginPermShortMessagesSend)
            PluginPermissions.NETWORK -> LocaleController.getString(R.string.PluginPermShortNetwork)
            PluginPermissions.FILES -> LocaleController.getString(R.string.PluginPermShortFiles)
            PluginPermissions.INTENTS -> LocaleController.getString(R.string.PluginPermShortIntents)
            PluginPermissions.SETTINGS -> LocaleController.getString(R.string.PluginPermShortSettings)
            PluginPermissions.HOOKS -> LocaleController.getString(R.string.PluginPermShortHooks)
            PluginPermissions.NATIVE -> LocaleController.getString(R.string.PluginPermShortNative)
            else -> titleOf(perm)
        }
    }
}
