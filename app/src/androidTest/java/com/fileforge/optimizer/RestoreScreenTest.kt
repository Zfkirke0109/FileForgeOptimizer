package com.fileforge.optimizer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Activity-owned restore presentation with an in-memory, no-write discovery seam. */
@RunWith(AndroidJUnit4::class)
class RestoreScreenTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetStateAndInstallSafeFixture() {
        context.getSharedPreferences("fileforge_optimize", Context.MODE_PRIVATE).edit().clear().commit()
        RunStateRepository.forAndroid(context).publish(RunState.Idle)
        RestoreScreenTestHooks.install(
            selectedTree = SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            discovery = restoreFixture()
        )
    }

    @After
    fun clearFixtureAndLeaveRepositoryIdle() {
        RestoreScreenTestHooks.clear()
        RunStateRepository.forAndroid(context).publish(RunState.Idle)
    }

    @Test
    fun emptyDiscoveryShowsEmptyStateAndDisablesRestore() {
        RestoreScreenTestHooks.install(
            selectedTree = SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            discovery = RestoreDiscoveryResult(emptyList(), emptyList())
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())

            onView(withId(R.id.restore_empty_state)).check(matches(isDisplayed()))
            onView(withId(R.id.restore_selected)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun cardsShowVerificationBadgesAndSelectionConfirmationUsesExactSelectedCount() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())

            onView(withText("SHA-256 verified")).check(matches(isDisplayed()))
            onView(withText("Legacy size-only verification")).check(matches(isDisplayed()))
            onView(withText("run-42")).check(matches(isDisplayed()))
            onView(withId(R.id.restore_select_all)).perform(click())
            onView(withContentDescription("Select docs/report.txt")).perform(click())
            onView(withId(R.id.restore_selected)).check(matches(isEnabled())).perform(click())

            onView(withText("Restore 1 file?")).check(matches(isDisplayed()))
            onView(withText("Backups and undo logs remain after restore.")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun activeRunDisablesRestoreAndTerminalReceiptFailuresSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.navigation_restore)).perform(click())
            RunStateRepository.forAndroid(context).publish(
                RunState.Running(
                    ProgressSnapshot(phase = "restoring", filesDiscovered = 2, filesProcessed = 1),
                    dryRun = false,
                    operationKind = RunOperationKind.RESTORE
                )
            )
            onView(withId(R.id.restore_selected)).check(matches(not(isEnabled())))

            RunStateRepository.forAndroid(context).publish(
                RunState.Terminal(
                    OptimizationReport(
                        scanned = 2,
                        optimized = 1,
                        errors = 1,
                        status = RunStatus.COMPLETED_WITH_ERRORS,
                        terminalFailures = listOf("photos/holiday.jpg: BACKUP_HASH_MISMATCH"),
                        restoreReceiptName = "FileForge_Restore_run-42_20260813T200000Z.jsonl"
                    ),
                    dryRun = false,
                    operationKind = RunOperationKind.RESTORE
                )
            )
            scenario.recreate()

            onView(withText("Restored 1 of 2 files")).check(matches(isDisplayed()))
            onView(withText("photos/holiday.jpg: BACKUP_HASH_MISMATCH")).check(matches(isDisplayed()))
            onView(withText("FileForge_Restore_run-42_20260813T200000Z.jsonl")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun restoreLaunchUsesServiceRestoreRequestWithTheSelectedEntryOnly() {
        val launches = mutableListOf<ServiceRunRequest.Restore>()
        RestoreScreenTestHooks.install(
            selectedTree = SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            discovery = restoreFixture(),
            startRestore = launches::add
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withContentDescription("Select photos/holiday.jpg")).perform(click())
            onView(withId(R.id.restore_selected)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())

            assertEquals(
                listOf(
                    ServiceRunRequest.Restore(
                        treeUri = "content://test/selected-root",
                        undoLogId = "FileForge_Undo_v2_run-42.jsonl",
                        selection = RestoreSelection.Entries(setOf("photos/holiday.jpg"))
                    )
                ),
                launches
            )
        }
    }

    private fun restoreFixture(): RestoreDiscoveryResult = RestoreDiscoveryResult(
        runs = listOf(
            DiscoveredUndoLog(
                undoLogId = "FileForge_Undo_v2_run-42.jsonl",
                run = UndoRun(
                    UndoHeader("run-42", "2026-08-13T19:42:00Z"),
                    listOf(
                        UndoEntry(
                            "photos/holiday.jpg", 4_096, 2_048,
                            "FileForge_Backups_run-42/photos/holiday.jpg",
                            "a".repeat(64), "verified backup",
                            optimizedSha256 = "b".repeat(64), fileKind = FileKind.JPEG,
                            toolId = "image-optimizer", completedAt = "2026-08-13T19:43:00Z"
                        ),
                        UndoEntry(
                            "docs/report.txt", 1_024, 512,
                            "FileForge_Backups_run-42/docs/report.txt",
                            null, "legacy backup", UndoVerificationLevel.LEGACY_SIZE_ONLY,
                            optimizedSha256 = null, fileKind = FileKind.TEXT,
                            toolId = "legacy", completedAt = ""
                        )
                    ),
                    RunStatus.COMPLETED
                )
            )
        ),
        failures = emptyList()
    )
}
