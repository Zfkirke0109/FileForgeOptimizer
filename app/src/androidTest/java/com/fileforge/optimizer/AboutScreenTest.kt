package com.fileforge.optimizer

import android.content.Context
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
            onView(withText(startsWith("ABI:"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Repository")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Issues")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("GitHub profile")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(containsString("Unavailable in this build"))).perform(scrollTo()).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                assertEquals(4, activity.window.decorView.descendants().filterIsInstance<RadioButton>().size)
            }
        }
    }

    @Test
    fun linksUseExactHttpsTargetsAndMissingBrowserIsReportedWithoutCrash() {
        val opened = mutableListOf<String>()
        AboutScreenTestHooks.install(
            checker = LatestReleaseChecker { UpdateCheckResult.NoRelease },
            worker = Executor { it.run() },
            openLink = { url -> opened += url; url != AboutLinks.ISSUES }
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("About")).perform(click())
            onView(withText("Repository")).perform(scrollTo(), click())
            onView(withText("GitHub profile")).perform(scrollTo(), click())
            onView(withText("Issues")).perform(scrollTo(), click())

            assertEquals(
                listOf(
                    "https://github.com/Zfkirke0109/FileForgeOptimizer",
                    "https://github.com/Zfkirke0109",
                    "https://github.com/Zfkirke0109/FileForgeOptimizer/issues"
                ),
                opened
            )
            onView(withText("No browser is available to open this link."))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun exactlyFourSingleChoiceThemesPersistAndSurviveNavigationAndRecreation() {
        AboutScreenTestHooks.install(
            checker = LatestReleaseChecker { UpdateCheckResult.NoRelease },
            worker = Executor { it.run() }
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("About")).perform(click())
            listOf("System", "Light", "Dark", "AMOLED").forEach { label ->
                onView(withText(label)).perform(scrollTo()).check(matches(isDisplayed()))
            }

            onView(withText("AMOLED")).perform(scrollTo(), click())
            assertEquals(ThemeMode.AMOLED, ThemePreferences.forAndroid(context).read())

            scenario.recreate()
            onView(withText("About")).perform(click())
            onView(withText("AMOLED")).perform(scrollTo()).check(matches(isChecked()))
            onView(withText("Optimize")).perform(click())
            onView(withText("About")).perform(click())
            onView(withText("AMOLED")).perform(scrollTo()).check(matches(isChecked()))
        }
    }

    @Test
    fun manualUpdateCheckShowsAvailableReleaseAndOnlyOpensItAfterExplicitClick() {
        val opened = mutableListOf<String>()
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
            openLink = { opened += it; true }
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("About")).perform(click())
            onView(withText("Check for updates")).perform(scrollTo(), click())
            onView(withText("Update available: 2.0.0")).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(emptyList<String>(), opened)

            onView(withText("View release")).perform(scrollTo(), click())
            assertEquals(listOf(releaseUrl), opened)
        }
    }

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
