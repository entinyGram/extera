package com.exteragram.messenger.plugins.catalog

import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.BaseFragment

object PluginRepositoryLinks {
    @JvmStatic
    fun open(fragment: BaseFragment, username: String?) {
        if (username.isNullOrBlank()) return
        fragment.messagesController.openByUserName(username, fragment, 0)
    }

    @JvmStatic
    fun openGit(fragment: BaseFragment, url: String?) {
        val ctx = fragment.parentActivity ?: return
        if (url.isNullOrBlank()) return
        Browser.openUrl(ctx, url)
    }
}
