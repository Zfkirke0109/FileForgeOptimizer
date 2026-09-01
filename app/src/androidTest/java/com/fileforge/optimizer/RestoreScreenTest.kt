package com.fileforge.optimizer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises the Activity-owned restore presentation with an in-memory, no-write discovery seam. */
@RunWith(AndroidJUnit4::class)
class RestoreScreenTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetStateAndInstallSafeFixture() {
        releasePendingRestoreClaim()
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
        releasePendingRestoreClaim()
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
            onView(withText("Legacy record — view only")).check(matches(isDisplayed()))
            onView(withText("run-42")).check(matches(isDisplayed()))
            onView(withContentDescription("Select docs/report.txt")).check(matches(not(isEnabled())))
            onView(withContentDescription("Select all from run-42")).perform(click())
            onView(withId(R.id.restore_selected)).check(matches(isEnabled())).perform(click())

            onView(withText("Restore 1 file?")).check(matches(isDisplayed()))
            onView(withText("Backups and undo logs remain after restore.")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun largeUndoRunCreatesEntryViewsOnePageAtATime() {
        val discovered = restoreFixture().runs.single()
        val template = discovered.run.entries.first()
        val entries = List(101) { index ->
            template.copy(
                relativePath = "bulk/$index.txt",
                backupPath = "FileForge_Backups_run-42/bulk/$index.txt"
            )
        }
        RestoreScreenTestHooks.install(
            selectedTree = SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            discovery = RestoreDiscoveryResult(
                listOf(discovered.copy(run = discovered.run.copy(entries = entries))),
                emptyList()
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())

            onView(withContentDescription("Select bulk/100.txt")).check(doesNotExist())
            onView(withContentDescription("Show more entries from run-42")).perform(scrollTo(), click())
            onView(withContentDescription("Select bulk/100.txt")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun activeRunDisablesRestoreAndTerminalReceiptFailuresSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withContentDescription("Select photos/holiday.jpg")).perform(click())
            onView(withId(R.id.restore_selected)).check(matches(isEnabled()))
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
    fun recreationDoesNotCopyOneDynamicEntryCheckboxStateOntoAnotherEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withContentDescription("Select photos/holiday.jpg")).perform(click())

            scenario.recreate()

            onView(withContentDescription("Select docs/report.txt"))
                .check(matches(not(isChecked()))).check(matches(not(isEnabled())))
            onView(withId(R.id.restore_selected)).perform(click())
            onView(withText("Restore 1 file?")).check(matches(isDisplayed()))
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
                        selection = RestoreSelection.Entries(
                            relativePaths = setOf("photos/holiday.jpg"),
                            undoDocumentId = "provider/document/undo-run-42",
                            entryCount = 2,
                            undoSha256 = "c".repeat(64)
                        )
                    )
                ),
                launches
            )
        }
    }

    @Test
    fun restoreShowsLiveProgressAndTerminalSuccessCancellationAndParseFailures() {
        RestoreScreenTestHooks.install(
            selectedTree = SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            discovery = RestoreDiscoveryResult(
                restoreFixture().runs,
                listOf(RestoreDiscoveryFailure("FileForge_Undo_v2_bad.jsonl", "Malformed entry"))
            )
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withText(containsString("Could not read FileForge_Undo_v2_bad.jsonl"))).check(matches(isDisplayed()))
            RunStateRepository.forAndroid(context).publish(
                RunState.Running(
                    ProgressSnapshot("restoring", "photos/holiday.jpg", filesDiscovered = 2, filesProcessed = 1),
                    false,
                    RunOperationKind.RESTORE
                )
            )
            onView(withText("Restoring 1 of 2 • photos/holiday.jpg")).check(matches(isDisplayed()))
            RunStateRepository.forAndroid(context).publish(
                RunState.Terminal(OptimizationReport(scanned = 2, optimized = 2, status = RunStatus.COMPLETED), false, RunOperationKind.RESTORE)
            )
            onView(withText("Restored 2 of 2 files")).check(matches(isDisplayed()))
            RunStateRepository.forAndroid(context).publish(
                RunState.Terminal(OptimizationReport(scanned = 2, optimized = 1, skipped = 1, status = RunStatus.CANCELLED), false, RunOperationKind.RESTORE)
            )
            onView(withText("Restore cancelled")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun unreadableAndReadOnlyTreesKeepRestoreDisabled() {
        listOf(SelectedTreeCapabilities.NONE, SelectedTreeCapabilities.READ_ONLY_DIRECTORY).forEach { tree ->
            RestoreScreenTestHooks.install(tree, restoreFixture())
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.navigation_restore)).perform(click())
                onView(withContentDescription("Select photos/holiday.jpg")).perform(click())
                onView(withId(R.id.restore_selected)).check(matches(not(isEnabled())))
            }
        }
    }

    @Test
    fun selectAllTargetsTheChosenRunCardInsteadOfAlwaysTheFirstRun() {
        val launches = mutableListOf<ServiceRunRequest.Restore>()
        val second = restoreFixture().runs.single().copy(
            undoLogId = "FileForge_Undo_v2_second.jsonl",
            documentId = "provider/document/undo-second",
            contentSha256 = "d".repeat(64),
            run = restoreFixture().runs.single().run.let { run ->
                run.copy(
                    header = UndoHeader("second", "2026-08-13T20:00:00Z"),
                    entries = run.entries.map { entry ->
                        if (entry.isRestoreEligible) entry else entry.copy(
                            originalSha256 = "c".repeat(64),
                            optimizedSha256 = "d".repeat(64),
                            originalDocumentId = "provider/document/${entry.relativePath}",
                            verificationLevel = UndoVerificationLevel.SHA_256
                        )
                    }
                )
            }
        )
        RestoreScreenTestHooks.install(
            SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            RestoreDiscoveryResult(restoreFixture().runs + second, emptyList()),
            startRestore = launches::add
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withContentDescription("Select all from second")).perform(click())
            onView(withId(R.id.restore_selected)).perform(click())
            onView(withText("Restore 2 files?")).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).perform(click())

            assertEquals(
                ServiceRunRequest.Restore(
                    treeUri = "content://test/selected-root",
                    undoLogId = "FileForge_Undo_v2_second.jsonl",
                    selection = RestoreSelection.ConfirmedAll(
                        undoDocumentId = "provider/document/undo-second",
                        entryCount = 2,
                        undoSha256 = "d".repeat(64)
                    )
                ),
                launches.single()
            )
        }
    }

    @Test
    fun synchronousRestoreStartFailureReleasesTheClaimAndShowsAnErrorWithoutCrashing() {
        RestoreScreenTestHooks.install(
            SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            restoreFixture(),
            startRestore = { throw IllegalStateException("synthetic start failure") }
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withContentDescription("Select photos/holiday.jpg")).perform(click())
            onView(withId(R.id.restore_selected)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())

            onView(withText(containsString("synthetic start failure"))).check(matches(isDisplayed()))
            onView(withId(R.id.restore_selected)).check(matches(isEnabled()))
        }
    }

    @Test
    fun oversizedIndividualSelectionFailsVisiblyWithoutDispatchOrWideningToAll() {
        val hugePath = "docs/${"x".repeat(600_000)}.txt"
        val run = restoreFixture().runs.single()
        val hugeEntry = run.run.entries.first().copy(
            relativePath = hugePath,
            backupPath = "FileForge_Backups_run-42/$hugePath"
        )
        val launches = mutableListOf<ServiceRunRequest.Restore>()
        RestoreScreenTestHooks.install(
            SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            RestoreDiscoveryResult(listOf(run.copy(run = run.run.copy(entries = listOf(hugeEntry)))), emptyList()),
            startRestore = launches::add
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withContentDescription("Select $hugePath")).perform(click())
            onView(withId(R.id.restore_selected)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())

            onView(withText(containsString("too large"))).check(matches(isDisplayed()))
            assertTrue(launches.isEmpty())
        }
    }

    @Test
    fun navigationAwayForwardsHiddenBeforeTheRealControllerQueuedDiscoveryCanApply() {
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val executorDrained = CountDownLatch(1)
        lateinit var executor: ExecutorService
        lateinit var session: RestoreDiscoverySession

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.navigation_restore)).perform(click())
            scenario.onActivity { activity ->
                val controller = privateField<RestoreScreenController>(activity, "restoreController")
                executor = privateField(controller, "executor")
                session = privateField(controller, "discoverySession")
                executor.execute {
                    blockerStarted.countDown()
                    releaseBlocker.await(5, TimeUnit.SECONDS)
                }
                assertTrue(blockerStarted.await(1, TimeUnit.SECONDS))
                session.onVisible()
            }

            onView(withId(R.id.navigation_about)).perform(click())
            releaseBlocker.countDown()
            executor.execute { executorDrained.countDown() }
            assertTrue(executorDrained.await(1, TimeUnit.SECONDS))

            scenario.onActivity { activity ->
                val controller = privateField<RestoreScreenController>(activity, "restoreController")
                val result = privateField<RestoreDiscoveryResult>(controller, "discoveryResult")
                val visibility = privateField<RestoreDiscoveryVisibilityGate>(session, "visibility")
                assertEquals(listOf("FileForge_Undo_v2_run-42.jsonl"), result.runs.map { it.undoLogId })
                assertEquals(null, visibility.currentGeneration())
            }
        }
    }

    @Test
    fun successfulControllerDispatchKeepsExactOwnershipAcrossRecreationWithoutRedispatch() {
        val launches = mutableListOf<ServiceRunRequest.Restore>()
        RestoreScreenTestHooks.install(
            selectedTree = SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            discovery = restoreFixture(),
            startRestore = launches::add
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withContentDescription("Select photos/holiday.jpg")).perform(click())
            onView(withId(R.id.restore_selected)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())
            val acceptedClaim = ProcessRestoreLaunchOwnership.instance.current()

            assertNotNull(acceptedClaim)
            assertEquals(1, launches.size)
            RunStateRepository.forAndroid(context).publish(
                RunState.Running(
                    ProgressSnapshot("restoring", filesDiscovered = 1),
                    dryRun = false,
                    operationKind = RunOperationKind.RESTORE
                )
            )
            scenario.recreate()

            assertSame(acceptedClaim, ProcessRestoreLaunchOwnership.instance.current())
            assertEquals(1, launches.size)
            onView(withId(R.id.navigation_restore)).perform(click())
            onView(withId(R.id.restore_selected)).check(matches(not(isEnabled())))
        }
    }

    private fun releasePendingRestoreClaim() {
        ProcessRestoreLaunchOwnership.instance.current()?.let {
            ProcessRestoreLaunchOwnership.instance.onServiceCompleted(it)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target) as T
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
                            toolId = "image-optimizer", completedAt = "2026-08-13T19:43:00Z",
                            originalDocumentId = "provider/document/photos-holiday"
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
                ),
                documentId = "provider/document/undo-run-42",
                contentSha256 = "c".repeat(64)
            )
        ),
        failures = emptyList()
    )
}
