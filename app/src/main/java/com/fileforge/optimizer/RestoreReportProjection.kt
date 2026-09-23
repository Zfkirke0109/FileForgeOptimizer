package com.fileforge.optimizer

internal fun RestoreReport.toOptimizationReport(): OptimizationReport {
    var skipped = 0
    var failureDetails: List<String> = emptyList()
    entries.forEach { result ->
        if (result.status == RestoreEntryStatus.UNPROCESSED_CANCELLED ||
            result.status == RestoreEntryStatus.UNPROCESSED_AUDIT_STOPPED
        ) {
            skipped += 1
        }
        if (result.status != RestoreEntryStatus.RESTORED) {
            val detail = buildString {
                append(result.relativePath).append(": ").append(result.status.name)
                if (result.message.isNotBlank()) append(" — ").append(result.message)
            }
            failureDetails = TerminalFailureDetails.append(failureDetails, detail)
        }
    }
    return OptimizationReport(
        scanned = selectedCount,
        optimized = restoredCount,
        skipped = skipped,
        errors = failedCount,
        status = status,
        terminalError = criticalError ?: receiptError,
        terminalFailures = failureDetails,
        rollbackFailure = criticalError,
        restoreReceiptName = receiptName
    )
}
