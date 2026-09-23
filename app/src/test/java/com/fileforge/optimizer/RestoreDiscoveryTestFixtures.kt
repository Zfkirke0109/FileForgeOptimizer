package com.fileforge.optimizer

internal fun restoreV2Log(
    runId: String,
    path: String,
    originalBytes: Long,
    optimizedBytes: Long
): ByteArray = (
    restoreV2Header(runId) + "\n" +
        "{\"schemaVersion\":2,\"recordType\":\"entry\",\"relativePath\":\"$path\",\"originalBytes\":$originalBytes," +
        "\"optimizedBytes\":$optimizedBytes,\"backupPath\":\"FileForge_Backups_$runId/$path\"," +
        "\"originalSha256\":\"${"a".repeat(64)}\",\"optimizedSha256\":\"${"b".repeat(64)}\"," +
        "\"fileKind\":\"TEXT\",\"toolId\":\"test-tool\",\"verificationLevel\":\"SHA_256\"," +
        "\"note\":\"verified\",\"completedAt\":\"2026-08-13T19:43:00Z\"}\n" +
        "{\"schemaVersion\":2,\"recordType\":\"terminal\",\"status\":\"COMPLETED\"," +
        "\"completedAt\":\"2026-08-13T19:44:00Z\",\"entriesCommitted\":1,\"scanned\":1," +
        "\"optimized\":1,\"skipped\":0,\"errors\":0,\"savedBytes\":1,\"bytesRead\":1," +
        "\"bytesWritten\":1,\"potentialSavingsBytes\":1}\n"
    ).encodeToByteArray()

internal fun restoreV2Header(runId: String): String =
    "{\"schemaVersion\":2,\"recordType\":\"header\",\"runId\":\"$runId\"," +
        "\"startedAt\":\"2026-08-13T19:42:00Z\",\"mode\":\"SAFE\",\"apkLabMode\":false," +
        "\"textMinify\":false,\"dryRun\":false,\"appVersion\":\"1.0\"," +
        "\"buildVariant\":\"debug\",\"status\":\"RUNNING\"}"
