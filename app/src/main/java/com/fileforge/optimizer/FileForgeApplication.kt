package com.fileforge.optimizer

import android.app.Application
import com.google.android.material.color.DynamicColors

class FileForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePreferences.forAndroid(this).applySavedMode()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
