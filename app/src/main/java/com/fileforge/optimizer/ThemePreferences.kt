package com.fileforge.optimizer

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode(internal val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    AMOLED("amoled");

    internal companion object {
        fun fromPersistedValue(value: String?): ThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

internal enum class ThemeNightMode {
    FOLLOW_SYSTEM,
    FORCE_LIGHT,
    FORCE_DARK
}

internal interface ThemeModeStorage {
    fun read(): String?
    fun write(value: String)
}

internal interface ThemeRuntime {
    fun apply(nightMode: ThemeNightMode, amoledOverlay: Boolean)
    fun requestRecreation()
}

class ThemePreferences internal constructor(
    private val storage: ThemeModeStorage,
    private val runtime: ThemeRuntime
) {
    fun read(): ThemeMode = ThemeMode.fromPersistedValue(storage.read())

    fun save(mode: ThemeMode) {
        storage.write(mode.persistedValue)
    }

    fun apply(mode: ThemeMode) {
        when (mode) {
            ThemeMode.SYSTEM -> runtime.apply(ThemeNightMode.FOLLOW_SYSTEM, amoledOverlay = false)
            ThemeMode.LIGHT -> runtime.apply(ThemeNightMode.FORCE_LIGHT, amoledOverlay = false)
            ThemeMode.DARK -> runtime.apply(ThemeNightMode.FORCE_DARK, amoledOverlay = false)
            ThemeMode.AMOLED -> runtime.apply(ThemeNightMode.FORCE_DARK, amoledOverlay = true)
        }
    }

    fun select(mode: ThemeMode) {
        val previous = read()
        save(mode)
        apply(mode)
        if (isAmoledOnlyTransition(previous, mode)) runtime.requestRecreation()
    }

    internal fun applySavedMode(): ThemeMode = read().also(::apply)

    private fun isAmoledOnlyTransition(previous: ThemeMode, selected: ThemeMode): Boolean =
        (previous == ThemeMode.DARK && selected == ThemeMode.AMOLED) ||
            (previous == ThemeMode.AMOLED && selected == ThemeMode.DARK)

    companion object {
        fun forAndroid(context: Context): ThemePreferences = ThemePreferences(
            SharedPreferencesThemeModeStorage(context.applicationContext),
            ApplicationThemeRuntime
        )

        fun forActivity(activity: Activity): ThemePreferences = ThemePreferences(
            SharedPreferencesThemeModeStorage(activity.applicationContext),
            ActivityThemeRuntime(activity)
        )

        /** Call at the start of Activity.onCreate, before super.onCreate and before inflating content. */
        fun applyActivityThemeBeforeOnCreate(activity: Activity): ThemeMode {
            val mode = forActivity(activity).applySavedMode()
            if (mode == ThemeMode.AMOLED) {
                activity.theme.applyStyle(R.style.ThemeOverlay_FileForge_Amoled, true)
            }
            return mode
        }
    }
}

private class SharedPreferencesThemeModeStorage(context: Context) : ThemeModeStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(THEME_MODE_KEY, null)

    override fun write(value: String) {
        preferences.edit().putString(THEME_MODE_KEY, value).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "fileforge_theme"
        const val THEME_MODE_KEY = "theme_mode"
    }
}

private object ApplicationThemeRuntime : ThemeRuntime {
    override fun apply(nightMode: ThemeNightMode, amoledOverlay: Boolean) {
        applyAppCompatNightMode(nightMode)
    }

    override fun requestRecreation() = Unit
}

private class ActivityThemeRuntime(private val activity: Activity) : ThemeRuntime {
    override fun apply(nightMode: ThemeNightMode, amoledOverlay: Boolean) {
        applyAppCompatNightMode(nightMode)
    }

    override fun requestRecreation() {
        activity.recreate()
    }
}

private fun applyAppCompatNightMode(nightMode: ThemeNightMode) {
    AppCompatDelegate.setDefaultNightMode(
        when (nightMode) {
            ThemeNightMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeNightMode.FORCE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeNightMode.FORCE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
    )
}
