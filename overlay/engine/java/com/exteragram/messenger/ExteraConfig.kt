@file:JvmName("ExteraConfigKt")

package com.exteragram.messenger

import android.app.Activity
import android.content.SharedPreferences
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader

val preferences: SharedPreferences by lazy {
    ApplicationLoader.applicationContext.getSharedPreferences("exteraconfig", Activity.MODE_PRIVATE)
}

val editor: SharedPreferences.Editor
    get() = preferences.edit()

@JvmField
var pluginsEngine: Boolean = false

var pluginsDevMode: Boolean
    get() = preferences.getBoolean("pluginsDevMode", false)
    set(value) { preferences.edit().putBoolean("pluginsDevMode", value).apply() }

var pluginsSafeMode: Boolean
    get() = preferences.getBoolean("pluginsSafeMode", false)
    set(value) { preferences.edit().putBoolean("pluginsSafeMode", value).apply() }

var pluginsCompactView: Boolean
    get() = preferences.getBoolean("pluginsCompactView", false)
    set(value) { preferences.edit().putBoolean("pluginsCompactView", value).apply() }

var pluginsPySdkAutoUpdate: Boolean
    get() = preferences.getBoolean("pluginsPySdkAutoUpdate", false)
    set(value) { preferences.edit().putBoolean("pluginsPySdkAutoUpdate", value).apply() }

var pluginsPySdkBetaVersions: Boolean
    get() = preferences.getBoolean("pluginsPySdkBetaVersions", false)
    set(value) { preferences.edit().putBoolean("pluginsPySdkBetaVersions", value).apply() }

var pluginsDisableArtOpts: Boolean
    get() = preferences.getBoolean("pluginsDisableArtOpts", false)
    set(value) { preferences.edit().putBoolean("pluginsDisableArtOpts", value).apply() }

var pluginsUnknownSources: Boolean
    get() = preferences.getBoolean("pluginsUnknownSources", true)
    set(value) { preferences.edit().putBoolean("pluginsUnknownSources", value).apply() }

var pluginsStaticAnalysis: Boolean
    get() = preferences.getBoolean("pluginsStaticAnalysis", false)
    set(value) { preferences.edit().putBoolean("pluginsStaticAnalysis", value).apply() }

var sdkUpdateScheduleTimestamp: Long
    get() = preferences.getLong("sdkUpdateScheduleTimestamp", 0L)
    set(value) { preferences.edit().putLong("sdkUpdateScheduleTimestamp", value).apply() }

var pinnedPlugins: Set<String>
    get() = HashSet(preferences.getStringSet("pinnedPlugins", emptySet()) ?: emptySet())
    set(value) { preferences.edit().putStringSet("pinnedPlugins", HashSet(value)).apply() }

object ExteraConfig {
    @JvmStatic
    var pluginsEngine: Boolean
        get() = com.exteragram.messenger.pluginsEngine
        set(value) { com.exteragram.messenger.pluginsEngine = value }

    @JvmStatic
    var pluginsDevMode: Boolean
        get() = com.exteragram.messenger.pluginsDevMode
        set(value) { com.exteragram.messenger.pluginsDevMode = value }

    @JvmStatic
    var pluginsSafeMode: Boolean
        get() = com.exteragram.messenger.pluginsSafeMode
        set(value) { com.exteragram.messenger.pluginsSafeMode = value }

    @JvmStatic
    var pluginsCompactView: Boolean
        get() = com.exteragram.messenger.pluginsCompactView
        set(value) { com.exteragram.messenger.pluginsCompactView = value }

    @JvmStatic
    fun getAvatarCorners(): Float = AndroidUtilities.dp(100f) / 2f

    @JvmStatic
    fun setAvatarCorners(value: Float) {}

    @JvmStatic
    fun getAvatarCorners(size: Float): Int = getAvatarCorners(size, false)

    @JvmStatic
    fun getAvatarCorners(size: Float, inPixels: Boolean): Int {
        val value = if (inPixels) size else AndroidUtilities.dp(size).toFloat()
        return Math.round(value / 2f)
    }

    @JvmStatic
    fun getAvatarCorners(size: Float, inPixels: Boolean, forum: Boolean): Int =
        getAvatarCorners(size, inPixels)

    @JvmStatic
    fun getAvatarCorners(size: Float, inPixels: Boolean, forum: Boolean, hasStories: Boolean): Int =
        getAvatarCorners(size, inPixels)
}
