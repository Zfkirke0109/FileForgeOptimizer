package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun persistsEverySupportedModeWithAStableValueAndReadsItBack() {
        val expectedValues = linkedMapOf(
            ThemeMode.SYSTEM to "system",
            ThemeMode.LIGHT to "light",
            ThemeMode.DARK to "dark",
            ThemeMode.AMOLED to "amoled"
        )
        val storage = RecordingThemeModeStorage()
        val runtime = RecordingThemeRuntime()
        val preferences = ThemePreferences(storage, runtime)

        expectedValues.forEach { (mode, storedValue) ->
            preferences.save(mode)

            assertEquals(storedValue, storage.value)
            assertEquals(mode, preferences.read())
        }
        assertTrue("saving a preference must not change the active theme", runtime.applications.isEmpty())
    }

    @Test
    fun missingOrUnrecognizedStoredModeFallsBackToSystemWithoutApplyingIt() {
        listOf(null, "", "sepia", "DARK").forEach { storedValue ->
            val storage = RecordingThemeModeStorage(storedValue)
            val runtime = RecordingThemeRuntime()
            val preferences = ThemePreferences(storage, runtime)

            assertEquals(ThemeMode.SYSTEM, preferences.read())
            assertTrue(runtime.applications.isEmpty())
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
        val storage = RecordingThemeModeStorage("light")
        val runtime = RecordingThemeRuntime()
        val preferences = ThemePreferences(storage, runtime)

        expectedApplications.forEach { (mode, expected) ->
            runtime.applications.clear()

            preferences.apply(mode)

            assertEquals(listOf(expected), runtime.applications)
            assertEquals("applying a mode must not persist it", "light", storage.value)
            assertTrue("applying a mode must not write preferences", storage.writes.isEmpty())
        }
    }

    private class RecordingThemeModeStorage(initialValue: String? = null) : ThemeModeStorage {
        var value: String? = initialValue
        val writes = mutableListOf<String>()

        override fun read(): String? = value

        override fun write(value: String) {
            writes += value
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
