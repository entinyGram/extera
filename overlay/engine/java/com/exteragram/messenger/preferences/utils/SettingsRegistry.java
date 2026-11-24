package com.exteragram.messenger.preferences.utils;

import org.telegram.ui.ActionBar.BaseFragment;

public class SettingsRegistry {
    public static final String SHRUG_MAN = "¯\\_(ツ)_/¯";
    public static final String deeplinkPrefix = "extera";
    public static final String deeplinkPluginIdQueryKey = "plugin";
    public static final String deeplinkSettingQueryKey = "setting";
    private static final SettingsRegistry INSTANCE = new SettingsRegistry();

    public static SettingsRegistry getInstance() {
        return INSTANCE;
    }

    public void onSettingNotFound(BaseFragment fragment) {
    }
}
