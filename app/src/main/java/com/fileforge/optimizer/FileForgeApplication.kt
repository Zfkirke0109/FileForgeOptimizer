package com.fileforge.optimizer

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors

class FileForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            CandidateStore.cleanupStale(cacheDir)
        } catch (_: CandidateCleanupException) {
            Log.w(LOG_TAG, "Could not remove stale native candidate files")
        }
        ThemePreferences.forAndroid(this).applySavedMode()
        DynamicColors.applyToActivitiesIfAvailable(this)
        OptimizationNotification.createChannel(this)
    }

    private companion object {
        const val LOG_TAG = "FileForge"
    }
}
