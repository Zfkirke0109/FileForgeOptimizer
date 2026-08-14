package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun exposesExactlyTheFourProductThemeChoices() {
        assertEquals(
            setOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED),
            ThemeMode.entries.toSet()
        )
    }

    @Test
    fun everySavedModeIsReadByAFreshPreferencesInstanceOverSharedStorage() {
        val storage = RecordingThemeModeStorage()

        ThemeMode.entries.forEach { mode ->
            ThemePreferences(storage, RecordingThemeRuntime()).save(mode)
            val restartedPreferences = ThemePreferences(storage, RecordingThemeRuntime())

            assertEquals(mode, restartedPreferences.read())
        }
    }

    @Test
    fun appliesSystemLightDarkAndAmoledThroughTheAndroidFreeRuntimeBoundary() {
        val expectedApplications = linkedMapOf(
            ThemeMode.SYSTEM to ThemeApplication(ThemeNightMode.FOLLOW_SYSTEM, amoledOverlay = false),
            ThemeMode.LIGHT to ThemeApplication(ThemeNightMode.FORCE_LIGHT, amoledOverlay = false),
            ThemeMode.DARK to ThemeApplication(ThemeNightMode.FORCE_DARK, amoledOverlay = false),
            ThemeMode.AMOLED to ThemeApplication(ThemeNightMode.FORCE_DARK, amoledOverlay = true)
        )
        val storage = RecordingThemeModeStorage()
        val runtime = RecordingThemeRuntime()
        val preferences = ThemePreferences(storage, runtime)

        expectedApplications.forEach { (mode, expected) ->
            runtime.applications.clear()

            preferences.apply(mode)

            assertEquals(listOf(expected), runtime.applications)
        }
    }

    @Test
    fun startupReadsAndAppliesSavedAmoledOrFallsBackToSystemForMissingAndInvalidState() {
        val amoledStorage = RecordingThemeModeStorage()
        ThemePreferences(amoledStorage, RecordingThemeRuntime()).save(ThemeMode.AMOLED)
        val amoledRuntime = RecordingThemeRuntime()
        val restartedAmoled = ThemePreferences(amoledStorage, amoledRuntime)

        restartedAmoled.apply(restartedAmoled.read())

        assertEquals(
            listOf(ThemeApplication(ThemeNightMode.FORCE_DARK, amoledOverlay = true)),
            amoledRuntime.applications
        )

        listOf(null, "not-a-theme-mode").forEach { storedValue ->
            val fallbackRuntime = RecordingThemeRuntime()
            val restartedFallback = ThemePreferences(RecordingThemeModeStorage(storedValue), fallbackRuntime)

            val startupMode = restartedFallback.read()
            restartedFallback.apply(startupMode)

            assertEquals(ThemeMode.SYSTEM, startupMode)
            assertEquals(
                listOf(ThemeApplication(ThemeNightMode.FOLLOW_SYSTEM, amoledOverlay = false)),
                fallbackRuntime.applications
            )
        }
    }

    private class RecordingThemeModeStorage(initialValue: String? = null) : ThemeModeStorage {
        var value: String? = initialValue

        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
        }
    }

    private class RecordingThemeRuntime : ThemeRuntime {
        val applications = mutableListOf<ThemeApplication>()

        override fun apply(nightMode: ThemeNightMode, amoledOverlay: Boolean) {
            applications += ThemeApplication(nightMode, amoledOverlay)
        }
    }

    private data class ThemeApplication(
        val nightMode: ThemeNightMode,
        val amoledOverlay: Boolean
    )
}
