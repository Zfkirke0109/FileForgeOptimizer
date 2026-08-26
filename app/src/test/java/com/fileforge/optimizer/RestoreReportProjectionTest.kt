package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreReportProjectionTest {
    @Test
    fun terminalProjectionBoundsFailureDetailsWithoutLosingTheFailureCount() {
        val failures = List(25) { index ->
            RestoreEntryResult(
                relativePath = "broken-$index.txt",
                status = RestoreEntryStatus.WRITE_FAILED,
                verification = UndoVerificationLevel.SHA_256,
                message = "provider failure ${"x".repeat(1_024)}"
            )
        }
        val report = RestoreReport(
            run = UndoRun(UndoHeader("run", "now"), emptyList(), RunStatus.COMPLETED_WITH_ERRORS),
            entries = failures,
            status = RunStatus.COMPLETED_WITH_ERRORS,
            restoredCount = 0,
            selectedCount = failures.size,
            failedCount = failures.size
        )

        val projection = report.toOptimizationReport()

        assertEquals(25, projection.errors)
        assertEquals(20, projection.terminalFailures.size)
        assertTrue(projection.terminalFailures.first().startsWith("broken-0.txt: WRITE_FAILED — provider failure"))
        assertTrue(projection.terminalFailures.all { it.length <= 512 })
    }
}
