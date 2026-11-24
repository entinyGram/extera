package com.exteragram.messenger.plugins.utils

import android.app.Activity
import androidx.core.content.edit
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.io.File

object NativeCrashHandler {
    private const val CRASH_FLAG_FILENAME = "native_crash.flag"

    @JvmStatic
    fun init(flagPath: String) {
        // entiny: no-op without exteraGram's native crash signal handler
    }

    @JvmStatic
    fun checkAndHandleNativeCrash() {
        val flagFile = File(ApplicationLoader.getFilesDirFixed(), CRASH_FLAG_FILENAME)
        if (flagFile.exists()) {
            FileLog.e("Native crash detected. Enabling safe mode for plugins.")
            val preferences = ApplicationLoader.applicationContext.getSharedPreferences(
                "plugin_settings",
                Activity.MODE_PRIVATE
            )
            preferences.edit { putBoolean("had_crash", true) }
            flagFile.delete()
        }
    }

    @JvmStatic
    fun getCrashFlagPath(): String =
        File(ApplicationLoader.getFilesDirFixed(), CRASH_FLAG_FILENAME).absolutePath
}
