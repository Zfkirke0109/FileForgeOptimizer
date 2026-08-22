package com.fileforge.optimizer

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetAboutState() {
        context.getSharedPreferences("fileforge_theme", Context.MODE_PRIVATE).edit().clear().commit()
        AboutScreenTestHooks.clear()
    }

    @After
    fun clearHooks() {
        AboutScreenTestHooks.clear()
        ThemePreferences.forAndroid(context).select(ThemeMode.SYSTEM)
    }

    @Test
    fun aboutShowsExactCreditBuildIdentityLinksAndHonestNativeInventory() {
        AboutScreenTestHooks.install(
            checker = LatestReleaseChecker { UpdateCheckResult.NoRelease },
            worker = Executor { it.run() }
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("About")).perform(click())

            onView(withText("Created by Zachary Kirke")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("FileForge Optimizer")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(startsWith("Version name:"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(startsWith("Version code:"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(startsWith("Build variant:"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Distribution: standard")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Release asset: standard")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Packaged ABI: none")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Repository")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Issues")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("GitHub profile")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(containsString("Unavailable in this build"))).perform(scrollTo()).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                assertEquals(4, activity.window.decorView.descendants().filterIsInstance<RadioButton>().count())
            }
            assertEquals("standard", BuildConfig.DISTRIBUTION_VARIANT)
            assertEquals("STANDARD", BuildConfig.RELEASE_ASSET_KIND)
            assertEquals("none", BuildConfig.PACKAGED_ABI)
        }
    }

    @Test
    fun linksUseExactHttpsTargetsAndMissingBrowserIsReportedWithoutCrash() {
        val launched = mutableListOf<Intent>()
        AboutScreenTestHooks.install(
            checker = LatestReleaseChecker { UpdateCheckResult.NoRelease },
            worker = Executor { it.run() },
            startIntent = { intent ->
                launched += Intent(intent)
                intent.dataString != AboutLinks.ISSUES
            }
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("About")).perform(click())
            onView(withText("Repository")).perform(scrollTo(), click())
            onView(withText("GitHub profile")).perform(scrollTo(), click())
            onView(withText("Issues")).perform(scrollTo(), click())

            val expectedUrls = listOf(
                "https://github.com/Zfkirke0109/FileForgeOptimizer",
                "https://github.com/Zfkirke0109",
                "https://github.com/Zfkirke0109/FileForgeOptimizer/issues"
            )
            assertEquals(expectedUrls, launched.map { it.dataString })
            launched.forEach { intent ->
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("https", intent.data?.scheme)
                assertEquals(true, intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            }
            onView(withText("No browser is available to open this link."))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun exactlyFourSingleChoiceThemesPersistAndSurviveNavigationAndRecreation() {
        val cases = listOf(
            Triple(ThemeMode.SYSTEM, ThemeMode.DARK, "System"),
            Triple(ThemeMode.LIGHT, ThemeMode.SYSTEM, "Light"),
            Triple(ThemeMode.DARK, ThemeMode.LIGHT, "Dark"),
            Triple(ThemeMode.AMOLED, ThemeMode.DARK, "AMOLED")
        )

        cases.forEach { (selected, initial, label) ->
            ThemePreferences.forAndroid(context).save(initial)
            AboutScreenTestHooks.install(
                checker = LatestReleaseChecker { UpdateCheckResult.NoRelease },
                worker = Executor { it.run() }
            )
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withText("About")).perform(click())
                onView(withText(label)).perform(scrollTo(), click())
                assertEquals(selected, ThemePreferences.forAndroid(context).read())

                scenario.recreate()
                onView(withText("About")).perform(click())
                onView(withText(label)).perform(scrollTo()).check(matches(isChecked()))
                onView(withText("Optimize")).perform(click())
                onView(withText("About")).perform(click())
                onView(withText(label)).perform(scrollTo()).check(matches(isChecked()))
            }
        }
    }

    @Test
    fun manualUpdateCheckRendersEveryTerminalStatus() {
        val cases = listOf(
            UpdateCheckResult.Current(SemanticVersion(1, 0, 0)) to "Current: 1.0.0",
            UpdateCheckResult.Available(
                SemanticVersion(2, 0, 0),
                "https://github.com/Zfkirke0109/FileForgeOptimizer/releases/tag/v2.0.0",
                "FileForgeOptimizer-standard.apk",
                "v2.0.0"
            ) to "Update available: v2.0.0",
            UpdateCheckResult.NoRelease to "No release found",
            UpdateCheckResult.Offline to "Offline",
            UpdateCheckResult.RateLimited to "Rate limited",
            UpdateCheckResult.Invalid to "Invalid release response"
        )

        cases.forEach { (result, expectedText) ->
            AboutScreenTestHooks.install(
                checker = LatestReleaseChecker { result },
                worker = Executor { it.run() }
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withText("About")).perform(click())
                onView(withText("Check for updates")).perform(scrollTo(), click())
                onView(withText(expectedText)).perform(scrollTo()).check(matches(isDisplayed()))
            }
        }
    }

    @Test
    fun generatedStandardVariantDrivesDisplayAndProductionCheckerAssetSelection() {
        AboutScreenTestHooks.install(
            worker = Executor { it.run() },
            transport = UpdateHttpTransport {
                UpdateHttpResponse(
                    200,
                    ByteArrayInputStream(releaseWithBothAssets().toByteArray())
                )
            }
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("About")).perform(click())
            onView(withText("Distribution: standard")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Release asset: standard")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Packaged ABI: none")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Check for updates")).perform(scrollTo(), click())
            onView(withText("Selected asset: FileForgeOptimizer-standard.apk"))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun manualUpdateCheckShowsAvailableReleaseAndOnlyOpensItAfterExplicitClick() {
        val launched = mutableListOf<Intent>()
        val releaseUrl = "https://github.com/Zfkirke0109/FileForgeOptimizer/releases/tag/v2.0.0"
        AboutScreenTestHooks.install(
            checker = LatestReleaseChecker {
                UpdateCheckResult.Available(
                    latestVersion = SemanticVersion(2, 0, 0),
                    releasePageUrl = releaseUrl,
                    selectedAssetName = "FileForgeOptimizer-standard.apk"
                )
            },
            worker = Executor { it.run() },
            startIntent = { launched += Intent(it); true }
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("About")).perform(click())
            onView(withText("Check for updates")).perform(scrollTo(), click())
            onView(withText("Update available: 2.0.0")).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(emptyList<Intent>(), launched)

            onView(withText("View release")).perform(scrollTo(), click())
            assertEquals(listOf(releaseUrl), launched.map { it.dataString })
            assertEquals(Intent.ACTION_VIEW, launched.single().action)
            assertEquals(true, launched.single().hasCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    private fun releaseWithBothAssets(): String = """
        {
          "tag_name":"v2.0.0",
          "html_url":"https://github.com/Zfkirke0109/FileForgeOptimizer/releases/tag/v2.0.0",
          "assets":[
            {"name":"FileForgeOptimizer-standard.apk"},
            {"name":"FileForgeOptimizer-native-arm64.apk"}
          ]
        }
    """.trimIndent()

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        val group = this@descendants as? ViewGroup
        if (group != null) {
            for (index in 0 until group.childCount) {
                yieldAll(group.getChildAt(index).descendants())
            }
        }
    }
}
