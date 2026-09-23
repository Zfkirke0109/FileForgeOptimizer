package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationServiceRequestCodecTest {
    @Test
    fun optimizationRoundTripsEveryModeAndBooleanCombination() {
        OptimizeMode.entries.forEach { mode ->
            listOf(false, true).forEach { dryRun ->
                listOf(false, true).forEach { apkLabMode ->
                    listOf(false, true).forEach { textMinify ->
                        val request = ServiceRunRequest.Optimize(
                            treeUri = "content://tree/primary?mode=${mode.name}",
                            runIntent = RunIntent(mode, dryRun, apkLabMode, textMinify)
                        )

                        val encoded = OptimizationServiceRequestCodec.encode(request)

                        assertEquals(OptimizationServiceContract.ACTION_START, encoded.action)
                        assertEquals(
                            request,
                            OptimizationServiceRequestCodec.decode(encoded.action, encoded.extras)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun restoreRoundTripsAllAndUnicodeEntrySelections() {
        val selections = listOf(
            RestoreSelection.All,
            RestoreSelection.ConfirmedAll(
                undoDocumentId = "content://provider/document/undo-42",
                entryCount = 42,
                undoSha256 = "a".repeat(64)
            ),
            RestoreSelection.Entries(
                relativePaths = linkedSetOf(
                    "docs/雪 and space.txt",
                    "quoted/\"report\".json",
                    "emoji/📦.zip"
                ),
                undoDocumentId = "content://provider/document/undo-42",
                entryCount = 3,
                undoSha256 = "a".repeat(64)
            )
        )

        selections.forEach { selection ->
            val request = ServiceRunRequest.Restore(
                treeUri = "content://tree/恢复",
                undoLogId = "FileForge_Undo_\"夏\".jsonl",
                selection = selection
            )

            val encoded = OptimizationServiceRequestCodec.encode(request)

            assertEquals(OptimizationServiceContract.ACTION_RESTORE, encoded.action)
            assertEquals(
                request,
                OptimizationServiceRequestCodec.decode(encoded.action, encoded.extras)
            )
        }
    }

    @Test
    fun confirmedSelectionsRejectChangedUndoIdentityCountOrContentForAllAndPartialRestores() {
        val run = UndoRun(
            header = UndoHeader("run-1", "start"),
            entries = listOf(
                UndoEntry(
                    relativePath = "docs/a.zip",
                    originalBytes = 2,
                    optimizedBytes = 1,
                    backupPath = "FileForge_Backups_run-1/docs/a.zip",
                    originalSha256 = "a".repeat(64),
                    note = "test",
                    optimizedSha256 = "b".repeat(64)
                )
            ),
            status = RunStatus.RUNNING
        )
        val selection = RestoreSelection.ConfirmedAll(
            undoDocumentId = "undo-node",
            entryCount = 1,
            undoSha256 = "c".repeat(64)
        )

        selection.requireMatchingSnapshot(DocumentNode("undo-node", "undo.jsonl", false, 0), run, "c".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            selection.requireMatchingSnapshot(
                DocumentNode("replacement", "undo.jsonl", false, 0),
                run,
                "c".repeat(64)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            selection.copy(entryCount = 0)
                .requireMatchingSnapshot(DocumentNode("undo-node", "undo.jsonl", false, 0), run, "c".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            selection.requireMatchingSnapshot(DocumentNode("undo-node", "undo.jsonl", false, 0), run, "d".repeat(64))
        }

        RestoreSelection.Entries(
            relativePaths = setOf("docs/a.zip"),
            undoDocumentId = "undo-node",
            entryCount = 1,
            undoSha256 = "c".repeat(64)
        ).requireMatchingSnapshot(DocumentNode("undo-node", "undo.jsonl", false, 0), run, "c".repeat(64))
    }

    @Test
    fun restoreEncodingKeepsAllCompactAndRejectsOversizedIndividualSubsetsBeforeBinderDispatch() {
        val all = ServiceRunRequest.Restore(
            treeUri = "content://tree/root",
            undoLogId = "FileForge_Undo_v2_large.jsonl",
            selection = RestoreSelection.All
        )
        val tooMany = all.copy(
            selection = RestoreSelection.Entries(
                (0 until 20_000).mapTo(linkedSetOf()) { "docs/$it.txt" },
                "undo-node",
                20_000,
                "a".repeat(64)
            )
        )
        val tooLarge = all.copy(
            selection = RestoreSelection.Entries(
                setOf("docs/${"x".repeat(600_000)}.txt"),
                "undo-node",
                1,
                "a".repeat(64)
            )
        )

        val encodedAll = OptimizationServiceRequestCodec.encode(all)

        assertEquals(all, OptimizationServiceRequestCodec.decode(encodedAll.action, encodedAll.extras))
        assertThrows(IllegalArgumentException::class.java) {
            OptimizationServiceRequestCodec.encode(tooMany)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OptimizationServiceRequestCodec.encode(tooLarge)
        }
    }

    @Test
    fun optimizationDecodeRejectsMissingWrongTypedMalformedUnknownAndExtraFields() {
        val valid = optimizeExtras(
            """{"mode":"SAFE","dryRun":false,"apkLabMode":true,"textMinify":false}"""
        )

        val invalid = listOf(
            valid - OptimizationServiceContract.EXTRA_TREE_URI,
            valid - OptimizationServiceContract.EXTRA_RUN_INTENT,
            valid + (OptimizationServiceContract.EXTRA_TREE_URI to 42),
            valid + (OptimizationServiceContract.EXTRA_RUN_INTENT to false),
            optimizeExtras("{"),
            optimizeExtras(
                """{"mode":"FUTURE","dryRun":false,"apkLabMode":true,"textMinify":false}"""
            ),
            optimizeExtras(
                """{"mode":"SAFE","dryRun":"false","apkLabMode":true,"textMinify":false}"""
            ),
            optimizeExtras(
                """{"mode":"SAFE","dryRun":false,"apkLabMode":true}"""
            ),
            optimizeExtras(
                """{"mode":"SAFE","dryRun":false,"apkLabMode":true,"textMinify":false,"future":1}"""
            ),
            valid + ("com.fileforge.optimizer.extra.FUTURE" to "value")
        )

        invalid.forEach { extras ->
            assertNull(
                extras.toString(),
                OptimizationServiceRequestCodec.decode(
                    OptimizationServiceContract.ACTION_START,
                    extras
                )
            )
        }
        assertNull(OptimizationServiceRequestCodec.decode("unknown.action", valid))
        assertNull(OptimizationServiceRequestCodec.decode(null, valid))
    }

    @Test
    fun restoreDecodeRejectsMissingWrongTypedMalformedUnknownAndExtraFields() {
        val validAll = restoreExtras("""{"kind":"ALL"}""")
        val validEntries = restoreExtras(
            """{"kind":"ENTRIES","relativePaths":["docs/a.txt"]}"""
        )

        val invalid = listOf(
            validAll - OptimizationServiceContract.EXTRA_TREE_URI,
            validAll - OptimizationServiceContract.EXTRA_UNDO_LOG_ID,
            validAll - OptimizationServiceContract.EXTRA_RESTORE_SELECTION,
            validAll + (OptimizationServiceContract.EXTRA_TREE_URI to false),
            validAll + (OptimizationServiceContract.EXTRA_UNDO_LOG_ID to 7),
            validAll + (OptimizationServiceContract.EXTRA_RESTORE_SELECTION to listOf("a")),
            restoreExtras("not-json"),
            restoreExtras("""{"kind":"FUTURE"}"""),
            restoreExtras("""{"kind":"ENTRIES"}"""),
            restoreExtras("""{"kind":"ENTRIES","relativePaths":"docs/a.txt"}"""),
            restoreExtras("""{"kind":"ENTRIES","relativePaths":["a",9]}"""),
            restoreExtras("""{"kind":"ALL","relativePaths":[]}"""),
            restoreExtras("""{"kind":"ALL","future":true}"""),
            validEntries + ("com.fileforge.optimizer.extra.FUTURE" to true)
        )

        invalid.forEach { extras ->
            assertNull(
                extras.toString(),
                OptimizationServiceRequestCodec.decode(
                    OptimizationServiceContract.ACTION_RESTORE,
                    extras
                )
            )
        }
    }

    @Test
    fun encodedRequestsContainOnlyTheStableExtrasForTheirAction() {
        val optimization = OptimizationServiceRequestCodec.encode(
            ServiceRunRequest.Optimize(
                "content://tree/one",
                RunIntent(OptimizeMode.AGGRESSIVE, true, true, true)
            )
        )
        val restore = OptimizationServiceRequestCodec.encode(
            ServiceRunRequest.Restore(
                "content://tree/two",
                "undo-id",
                RestoreSelection.All
            )
        )

        assertEquals(
            setOf(
                OptimizationServiceContract.EXTRA_TREE_URI,
                OptimizationServiceContract.EXTRA_RUN_INTENT
            ),
            optimization.extras.keys
        )
        assertEquals(
            setOf(
                OptimizationServiceContract.EXTRA_TREE_URI,
                OptimizationServiceContract.EXTRA_UNDO_LOG_ID,
                OptimizationServiceContract.EXTRA_RESTORE_SELECTION
            ),
            restore.extras.keys
        )
        assertTrue(optimization.extras.values.all { it is String })
        assertTrue(restore.extras.values.all { it is String })
    }

    @Test
    fun strictSyntaxGuardRejectsAndroidJsonExtensionsAndDuplicateKeysAtEveryDepth() {
        val valid = """{"outer":{"name":"value"},"items":[1,2,{"id":3}]}"""
        assertTrue(StrictServiceJsonSyntax.isObject(valid))
        val invalid = listOf(
            "{'name':'value'}",
            "{name:\"value\"}",
            "{\"name\":value}",
            "{\"name\":/* comment */\"value\"}",
            "{\"name\":\"value\"// comment\n}",
            "{\"name\"=\"value\"}",
            "{\"name\"=>\"value\"}",
            "{\"a\":1;\"b\":2}",
            "{\"number\":0x10}",
            "{\"number\":010}",
            "{\"name\":\"value\",}",
            "{\"items\":[1,2,]}",
            "{\"name\":\"first\",\"name\":\"second\"}",
            "{\"outer\":{\"name\":\"first\",\"name\":\"second\"}}",
            "{\"items\":[{\"id\":1,\"id\":2}]}"
        )

        invalid.forEach { serialized ->
            assertFalse(serialized, StrictServiceJsonSyntax.isObject(serialized))
        }
    }

    @Test
    fun codecRejectsEveryLenientAndroidJsonFormWithoutDefaulting() {
        val invalidRunIntents = listOf(
            "{'mode':'SAFE','dryRun':false,'apkLabMode':false,'textMinify':true}",
            "{mode:\"SAFE\",dryRun:false,apkLabMode:false,textMinify:true}",
            "{\"mode\":SAFE,\"dryRun\":false,\"apkLabMode\":false,\"textMinify\":true}",
            "{\"mode\":\"SAFE\",/*comment*/\"dryRun\":false,\"apkLabMode\":false,\"textMinify\":true}",
            "{\"mode\"=\"SAFE\",\"dryRun\"=>false,\"apkLabMode\":false,\"textMinify\":true}",
            "{\"mode\":\"SAFE\";\"dryRun\":false;\"apkLabMode\":false;\"textMinify\":true}",
            "{\"mode\":\"SAFE\",\"dryRun\":0x0,\"apkLabMode\":false,\"textMinify\":true}",
            "{\"mode\":\"SAFE\",\"dryRun\":00,\"apkLabMode\":false,\"textMinify\":true}",
            "{\"mode\":\"SAFE\",\"dryRun\":false,\"apkLabMode\":false,\"textMinify\":true,}",
            "{\"mode\":\"SAFE\",\"mode\":\"AGGRESSIVE\",\"dryRun\":false,\"apkLabMode\":false,\"textMinify\":true}"
        )
        invalidRunIntents.forEach { serialized ->
            assertNull(
                serialized,
                OptimizationServiceRequestCodec.decode(
                    OptimizationServiceContract.ACTION_START,
                    optimizeExtras(serialized)
                )
            )
        }

        listOf(
            "{'kind':'ALL'}",
            "{kind:\"ALL\"}",
            "{\"kind\"=\"ALL\"}",
            "{\"kind\":\"ALL\",}",
            "{\"kind\":\"ALL\",\"kind\":\"ENTRIES\"}",
            "{\"kind\":\"ENTRIES\",\"relativePaths\":[\"a\",]}"
        ).forEach { serialized ->
            assertNull(
                serialized,
                OptimizationServiceRequestCodec.decode(
                    OptimizationServiceContract.ACTION_RESTORE,
                    restoreExtras(serialized)
                )
            )
        }
    }

    private fun optimizeExtras(serializedIntent: Any?): Map<String, Any?> = mapOf(
        OptimizationServiceContract.EXTRA_TREE_URI to "content://tree/primary",
        OptimizationServiceContract.EXTRA_RUN_INTENT to serializedIntent
    )

    private fun restoreExtras(serializedSelection: Any?): Map<String, Any?> = mapOf(
        OptimizationServiceContract.EXTRA_TREE_URI to "content://tree/restore",
        OptimizationServiceContract.EXTRA_UNDO_LOG_ID to "undo-id",
        OptimizationServiceContract.EXTRA_RESTORE_SELECTION to serializedSelection
    )
}
