package com.exteragram.messenger.plugins.ui

import org.telegram.ui.ActionBar.BaseFragment

class PluginsActivity : BaseFragment() {
    override fun onFragmentCreate(): Boolean {
        presentFragment(desu.inugram.ui.settings.PluginsActivity(), true)
        return false
    }
}

class PluginsInfoActivity : BaseFragment()
