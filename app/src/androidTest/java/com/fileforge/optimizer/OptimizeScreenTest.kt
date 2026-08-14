package com.fileforge.optimizer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptimizeScreenTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetUiState() {
        context.getSharedPreferences("fileforge_optimize", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit()
        RunStateRepository.forAndroid(context).publish(RunState.Idle)
    }

    @After
    fun leaveRepositoryIdle() {
        RunStateRepository.forAndroid(context).publish(RunState.Idle)
    }

    @Test
    fun materialHostExposesOptimizeControlsAndAllDestinations() {
        ActivityScenario.launch(MainActivity::class.java).use {
            listOf(R.id.toolbar, R.id.bottom_navigation).forEach { id ->
                onView(withId(id)).check(matches(isDisplayed()))
            }
            listOf(
                R.id.pick_folder,
                R.id.mode_safe,
                R.id.mode_aggressive,
                R.id.dry_run,
                R.id.apk_lab,
                R.id.text_minify,
                R.id.start_optimization,
                R.id.cancel_optimization,
                R.id.run_progress,
                R.id.live_counters
            ).forEach { id ->
                onView(withId(id)).perform(scrollTo()).check(matches(isDisplayed()))
            }

            onView(withId(R.id.navigation_restore)).perform(click())
            onView(allOf(withId(R.id.placeholder_title), withText("Restore")))
                .check(matches(isDisplayed()))
            onView(withId(R.id.navigation_about)).perform(click())
            onView(allOf(withId(R.id.placeholder_title), withText("About")))
                .check(matches(isDisplayed()))
            onView(withId(R.id.navigation_optimize)).perform(click())
            onView(withId(R.id.start_optimization)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun startIsDisabledWithoutASelectedTree() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.start_optimization)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun runningStateRebindsWithCancelAndSurvivesActivityRecreation() {
        RunStateRepository.forAndroid(context).publish(
            RunState.Running(
                ProgressSnapshot(
                    phase = "optimizing",
                    currentRelativePath = "Movies/sample.mkv",
                    filesDiscovered = 20,
                    filesProcessed = 6,
                    optimized = 2,
                    savedBytes = 2_048,
                    totalWork = 20
                ),
                dryRun = false
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertRunningControlsVisible()
            scenario.recreate()
            assertRunningControlsVisible()
        }
    }

    private fun assertRunningControlsVisible() {
        onView(withId(R.id.start_optimization)).check(matches(not(isEnabled())))
        onView(withId(R.id.cancel_optimization)).check(matches(isEnabled()))
        onView(withId(R.id.cancel_optimization)).check(matches(isClickable()))
        onView(withText("Movies/sample.mkv")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.cancel_optimization)).perform(scrollTo(), click())
        onView(withId(R.id.start_optimization)).check(matches(not(isEnabled())))
    }
}
