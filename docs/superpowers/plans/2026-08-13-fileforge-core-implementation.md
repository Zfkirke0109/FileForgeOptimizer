# FileForge Streaming and Recovery Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 300 MB ZIP guard with bounded-memory streaming, add exact zero-write dry runs, and provide crash-resilient backups, v2 undo logs, and verified restoration.

**Architecture:** Pure Kotlin coordinators operate through a small document gateway so the transaction and restore rules are JVM-testable. ZIP-family files stream through app-private candidates; byte-array formats retain an explicit per-format limit. Every real replacement is backup-first, verify-before-commit, and append-only logged.

**Tech Stack:** Kotlin 2.0.21, Android SDK 35, Java 17, Android SAF/DocumentFile, `java.util.zip`, SHA-256, JSON Lines, JUnit 4.13.2.

## Global Constraints

- Keep `applicationId 'com.fileforge.optimizer'`, `minSdk 26`, `targetSdk 35`, and Java/Kotlin target 17.
- Do not request root or `MANAGE_EXTERNAL_STORAGE`; all selected-tree access stays behind SAF.
- ZIP-family and APK Lab inputs and candidates must never become whole-file `ByteArray` values.
- Dry run must perform zero writes under the selected SAF tree.
- Non-ZIP Kotlin optimizers keep a named per-format memory limit and report `SKIPPED_MEMORY_LIMIT`.
- Originals are writable only after a streamed backup passes size and SHA-256 verification.
- Paths that are absolute, contain `..`, or escape the selected root are rejected.
- Preserve legacy `FileForge_Undo_*.txt` restore compatibility.
- Every task follows red-green-refactor and ends in a focused commit.

---

## File Structure

- Modify `app/build.gradle`: JVM-test dependencies and test options.
- Modify `app/src/main/java/com/fileforge/optimizer/Models.kt`: immutable run, progress, result, and undo models.
- Modify `app/src/main/java/com/fileforge/optimizer/FileTypeDetector.kt`: bounded-header detection.
- Create `app/src/main/java/com/fileforge/optimizer/CancellationToken.kt`: cooperative cancellation contract.
- Create `app/src/main/java/com/fileforge/optimizer/StreamingZipOptimizer.kt`: bounded ZIP transformation and verification.
- Create `app/src/main/java/com/fileforge/optimizer/DocumentGateway.kt`: tree/document abstraction.
- Create `app/src/main/java/com/fileforge/optimizer/SafDocumentGateway.kt`: DocumentFile implementation.
- Create `app/src/main/java/com/fileforge/optimizer/CandidateStore.kt`: app-cache candidate lifecycle.
- Create `app/src/main/java/com/fileforge/optimizer/UndoLogRepository.kt`: v2 JSONL writer plus v1 reader.
- Create `app/src/main/java/com/fileforge/optimizer/RestoreCoordinator.kt`: verified per-entry restoration.
- Create `app/src/main/java/com/fileforge/optimizer/OptimizationCoordinator.kt`: dry-run and real transaction policy.
- Modify `app/src/main/java/com/fileforge/optimizer/OptimizerEngine.kt`: scanning orchestration and progress emission.
- Create corresponding tests under `app/src/test/java/com/fileforge/optimizer/`.

### Task 1: Test Harness, Models, and Header Detection

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/java/com/fileforge/optimizer/Models.kt`
- Modify: `app/src/main/java/com/fileforge/optimizer/FileTypeDetector.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/FileTypeDetectorTest.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/RunModelsTest.kt`

**Interfaces:**
- Produces: `RunIntent(mode, dryRun, apkLabMode, textMinify)`, `RunStatus`, `SkipReason`, `ProgressSnapshot`, `FileHeader`.
- Produces: `FileTypeDetector.detect(name: String, header: ByteArray): FileKind` with no full-file assumption.

- [ ] **Step 1: Add the failing model and detector tests**

```kotlin
@Test fun detectsZipFromBoundedHeader() {
    val header = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(12)
    assertEquals(FileKind.ZIP_LIKE, FileTypeDetector.detect("huge.zip", header))
}

@Test fun dryRunIntentNeverRequestsWrites() {
    val intent = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = false)
    assertFalse(intent.allowsSelectedTreeWrites)
}
```

- [ ] **Step 2: Run the focused tests and confirm red**

Run: `gradle :app:testDebugUnitTest --tests '*FileTypeDetectorTest' --tests '*RunModelsTest'`

Expected: compilation fails because `RunIntent`, `SkipReason`, `ProgressSnapshot`, and `allowsSelectedTreeWrites` do not exist.

- [ ] **Step 3: Add JUnit and immutable core models**

Add to `app/build.gradle`:

```groovy
android { testOptions { unitTests.returnDefaultValues = true } }
dependencies {
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.json:json:20240303'
}
```

Define exact core properties:

```kotlin
data class RunIntent(
    val mode: OptimizeMode,
    val dryRun: Boolean,
    val apkLabMode: Boolean,
    val textMinify: Boolean
) { val allowsSelectedTreeWrites: Boolean get() = !dryRun }

enum class RunStatus { RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, FAILED }
enum class SkipReason { UNSUPPORTED, NO_CHANGE, NO_GAIN, APK_GUARD, MEMORY_LIMIT, VERIFICATION_FAILED }
```

Extend `OptimizationReport` with `candidates`, `potentialSavingsBytes`, `bytesRead`, `bytesWritten`, `status`, and `skipsByReason` while retaining current summary fields during migration.

- [ ] **Step 4: Make detector header-only and pass tests**

Remove any assumption that `bytes` contains the complete file. Guard every magic read by header length. Run the focused tests again and expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/java/com/fileforge/optimizer/Models.kt app/src/main/java/com/fileforge/optimizer/FileTypeDetector.kt app/src/test
git commit -m "test: establish optimizer core contracts"
```

### Task 2: Bounded Streaming ZIP Optimizer

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/CancellationToken.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/StreamingZipOptimizer.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/StreamingZipOptimizerTest.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/LargeStreamingZipTest.kt`

**Interfaces:**
- Consumes: `OptimizeMode`, `RunIntent` from Task 1.
- Produces: `CancellationToken.throwIfCancelled()`, `ZipOptimizationSummary(entries: Int, inputBytes: Long, outputBytes: Long, note: String)`.
- Produces: `ArchivePathPolicy.requireSafe(path)`, `UnsafeArchivePathException`, and `ZipVerification(entries: Int, bytesRead: Long)`.
- Produces: `StreamingZipOptimizer.optimize(input, output, mode, cancellation, onBytes): ZipOptimizationSummary` and `verify(input, cancellation): ZipVerification`.

- [ ] **Step 1: Write failing behavioral tests**

Cover nested files, directory entries, empty entries, duplicate names, entry comments/extras, malformed CRC, absolute names, `../` traversal, cancellation, and an archive whose source stream returns chunks no larger than 8 KiB.

```kotlin
@Test fun rejectsTraversalEntry() {
    val zip = zipBytes("../escape.txt" to "no".toByteArray())
    assertThrows(UnsafeArchivePathException::class.java) {
        optimizer.optimize(zip.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, NeverCancelled) {}
    }
}
```

- [ ] **Step 2: Run tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*StreamingZipOptimizerTest'`

Expected: compilation fails because the streaming optimizer and cancellation contracts are absent.

- [ ] **Step 3: Implement the minimal streaming loop**

Use `ZipInputStream` and `ZipOutputStream` with one reusable `ByteArray(32 * 1024)`. Validate names before `putNextEntry`, preserve directory/empty entries, select deflate level 7 or 9, call cancellation before each entry and buffer transfer, and never call `readBytes()`, `copyOf()` on payloads, or `ByteArrayOutputStream` in production ZIP code.

Define cancellation exactly as:

```kotlin
fun interface CancellationToken { fun throwIfCancelled() }
object NeverCancelled : CancellationToken { override fun throwIfCancelled() = Unit }
```

```kotlin
while (true) {
    cancellation.throwIfCancelled()
    val source = zipIn.nextEntry ?: break
    ArchivePathPolicy.requireSafe(source.name)
    zipOut.putNextEntry(copyMetadata(source, mode))
    var read: Int
    while (zipIn.read(buffer).also { read = it } >= 0) {
        cancellation.throwIfCancelled()
        if (read > 0) { zipOut.write(buffer, 0, read); onBytes(read.toLong()) }
    }
    zipOut.closeEntry()
}
```

- [ ] **Step 4: Add streaming verification and pass focused tests**

Verification must drain every entry, surface CRC/format exceptions, require at least one entry, and reject unsafe names. Run the focused test command and expect PASS.

- [ ] **Step 5: Add the former-guard regression test**

Generate a 301 MiB archive to a temporary file by streaming deterministic pseudo-random blocks; do not retain payload blocks. Optimize it to a second temporary file and assert successful verification and heap growth below 64 MiB after GC stabilization.

Run: `gradle :app:testDebugUnitTest --tests '*LargeStreamingZipTest'`

Expected: PASS without `OutOfMemoryError` and without a 300 MB skip.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/fileforge/optimizer/CancellationToken.kt app/src/main/java/com/fileforge/optimizer/StreamingZipOptimizer.kt app/src/test/java/com/fileforge/optimizer
git commit -m "feat: stream ZIP optimization with bounded memory"
```

### Task 3: Document Gateway, Candidates, and Exact Dry Run

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/DocumentGateway.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/SafDocumentGateway.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/CandidateStore.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/OptimizationCoordinator.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/OptimizationCoordinatorDryRunTest.kt`

**Interfaces:**
- Produces: `DocumentNode(id: String, name: String, isDirectory: Boolean, length: Long)` and `DocumentGateway.openRead`, `openWrite`, `list`, `resolve`, `createDirectory`, `createFile`, `length`.
- Produces: `CandidateStore.create(runId, suffix): CandidateFile` whose `close()` deletes the cache file.
- Produces: `OptimizationCoordinator.process(node, relativePath, runIntent, cancellation): FileOutcome`.

`FileOutcome` is a sealed type with `WouldOptimize`, `Optimized`, `Skipped`, and `Failed`; each carries the relative path, and savings-bearing outcomes expose old/new bytes plus tool and note.

- [ ] **Step 1: Write a recording gateway and failing zero-write test**

```kotlin
@Test fun dryRunCalculatesExactSavingsWithoutTreeWrites() {
    val gateway = RecordingDocumentGateway(zipFixture)
    val outcome = coordinator.process(gateway.rootFile, "archive.zip", dryRunIntent, NeverCancelled)
    assertTrue(outcome.potentialSavingsBytes > 0)
    assertEquals(emptyList<String>(), gateway.writeOperations)
}
```

- [ ] **Step 2: Run focused test and confirm red**

Run: `gradle :app:testDebugUnitTest --tests '*OptimizationCoordinatorDryRunTest'`

Expected: compilation fails because gateway/coordinator types do not exist.

- [ ] **Step 3: Implement gateway and private candidates**

Keep SAF objects inside `SafDocumentGateway`; tests use an in-memory or temp-file gateway. `CandidateStore` writes only below `context.cacheDir/fileforge/<run-id>` and deletes recursively on success, error, cancellation, and startup stale-cache cleanup.

- [ ] **Step 4: Implement dry-run branch first**

The dry-run path detects, produces, verifies, measures, and closes a candidate. It must not call any mutating `DocumentGateway` method. Return `FileOutcome.WouldOptimize(oldBytes, candidateBytes, tool, note)` only when smaller and verified.

- [ ] **Step 5: Run test and inspect mutation log**

Run the focused test and expect PASS with `writeOperations == []`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/fileforge/optimizer/DocumentGateway.kt app/src/main/java/com/fileforge/optimizer/SafDocumentGateway.kt app/src/main/java/com/fileforge/optimizer/CandidateStore.kt app/src/main/java/com/fileforge/optimizer/OptimizationCoordinator.kt app/src/test/java/com/fileforge/optimizer/OptimizationCoordinatorDryRunTest.kt
git commit -m "feat: add exact zero-write dry runs"
```

### Task 4: Append-Only Undo Log v2 and Legacy Parsing

**Files:**
- Create: `app/src/main/java/com/fileforge/optimizer/UndoLogRepository.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/UndoLogRepositoryTest.kt`

**Interfaces:**
- Produces: `UndoHeader`, `UndoEntry`, `UndoTerminalSummary`, `UndoRun`.
- Produces: `UndoLogRepository.start`, `appendEntry`, `appendTerminal`, `read`.

- [ ] **Step 1: Write failing round-trip and legacy tests**

Include paths containing spaces, Unicode, and `|`; interrupted logs without terminal summaries; invalid lines; and the current legacy header/record format.

```kotlin
@Test fun interruptedV2LogKeepsCompletedEntriesRestorable() {
    val text = headerJson + "\n" + entryJson + "\n"
    val run = repository.read(text.reader())
    assertEquals(RunStatus.RUNNING, run.status)
    assertEquals(1, run.entries.size)
}
```

- [ ] **Step 2: Run focused tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*UndoLogRepositoryTest'`

- [ ] **Step 3: Implement schema-discriminated JSON Lines**

Write one header with `schemaVersion=2` and `RUNNING`, one JSON object per committed entry, and one terminal summary. Flush after every line. Never rewrite prior lines. Parse legacy pipe-delimited records conservatively and label `verificationLevel=LEGACY_SIZE_ONLY`.

- [ ] **Step 4: Run focused tests and expect PASS**

Also scan production code to ensure no log line concatenates user paths with `|` for v2.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/fileforge/optimizer/UndoLogRepository.kt app/src/test/java/com/fileforge/optimizer/UndoLogRepositoryTest.kt
git commit -m "feat: add crash-resilient undo logs"
```

### Task 5: Backup-First Commit and Verified Restore

**Files:**
- Modify: `app/src/main/java/com/fileforge/optimizer/OptimizationCoordinator.kt`
- Create: `app/src/main/java/com/fileforge/optimizer/RestoreCoordinator.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/OptimizationTransactionTest.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/RestoreCoordinatorTest.kt`

**Interfaces:**
- Consumes: gateway/candidates from Task 3 and undo models from Task 4.
- Produces: `RestoreSelection`, `RestoreEntryResult`, `RestoreReport`, and `restore(run, selection, cancellation)`.

- [ ] **Step 1: Write failing transaction-order tests**

Use the recording gateway to assert this order: candidate verified, backup written, backup verified, recovery entry appended and flushed, live original revalidated, original written, original verified. Inject failures at every stage and assert no earlier unsafe mutation occurs.

- [ ] **Step 2: Write failing restore tests**

Cover v2 SHA-256 success, hash mismatch, missing backup, path traversal, selected subset, one-entry failure with continuation, legacy size-only restore, and idempotent repeated restore.

- [ ] **Step 3: Run focused tests and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*OptimizationTransactionTest' --tests '*RestoreCoordinatorTest'`

- [ ] **Step 4: Implement streamed hashing and backup-first commit**

Use `MessageDigest.getInstance("SHA-256")` with the same 32 KiB copy buffer. Create backup directories lazily on the first accepted candidate. On post-write failure, restore immediately from the already verified backup and record the rollback result.

- [ ] **Step 5: Implement independent restore results and receipts**

Validate relative paths before resolution, verify v2 hashes, restore selected files independently, and write `FileForge_Restore_<run-id>_<timestamp>.jsonl` only for real restore actions. Never delete backups or undo logs.

- [ ] **Step 6: Run tests and expect PASS**

Run the focused transaction and restore command, then the full unit suite: `gradle :app:testDebugUnitTest`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/fileforge/optimizer/OptimizationCoordinator.kt app/src/main/java/com/fileforge/optimizer/RestoreCoordinator.kt app/src/test/java/com/fileforge/optimizer
git commit -m "feat: add verified backup and restore transactions"
```

### Task 6: Integrate the Scanner and Engine

**Files:**
- Modify: `app/src/main/java/com/fileforge/optimizer/OptimizerEngine.kt`
- Modify: `app/src/main/java/com/fileforge/optimizer/Optimizers.kt`
- Test: `app/src/test/java/com/fileforge/optimizer/OptimizerEngineIntegrationTest.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: all Tasks 1-5.
- Produces: `OptimizerEngine.run(cancellation, onProgress): OptimizationReport` without activity ownership.

- [ ] **Step 1: Write failing integration tests**

Test recursive scanning, FileForge artifact exclusion, APK guard, non-ZIP memory-limit skip, dry-run totals, real-run totals, cancellation between files, and interrupted-run terminal handling.

- [ ] **Step 2: Run integration test and verify red**

Run: `gradle :app:testDebugUnitTest --tests '*OptimizerEngineIntegrationTest'`

- [ ] **Step 3: Reduce `OptimizerEngine` to orchestration**

Remove `maxBytes`, direct byte-array ZIP reads, direct backup writes, and UI logging ownership. Emit immutable `ProgressSnapshot` values. Route ZIP-family/APK Lab to the streaming coordinator; route other supported kinds to the existing `Optimizers` adapter with a named `64L * 1024L * 1024L` default limit.

- [ ] **Step 4: Update documentation and run all core checks**

Update README limitations and workflows. Run:

```bash
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

Expected: all commands exit 0; README no longer says ZIP files are subject to a 300 MB guard.

- [ ] **Step 5: Commit**

```bash
git add app/src/main README.md
git commit -m "refactor: integrate streaming recovery engine"
```

## Core Plan Verification Gate

- [ ] Run `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` from a clean checkout.
- [ ] Run the 301 MiB streaming regression independently and record wall time and maximum heap delta.
- [ ] Confirm a dry run creates no `FileForge_*` node under the recording selected tree.
- [ ] Confirm every real replacement test has a verified backup and v2 undo record.
- [ ] Commit any verification-only fixes separately with the failing test that motivated them.
