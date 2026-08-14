package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOptimizeLaunchStorageTest {
    @Test
    fun everyMissingPendingLaunchFieldFailsClosedAndClearsTheCompleteRecord() {
        validRecord().keys.forEach { missingKey ->
            val store = MemoryPendingLaunchRecordStore(
                validRecord().filterKeys { it != missingKey }
            )

            assertNull(StrictPendingOptimizeLaunchStorage(store).read())

            assertTrue("Record was not cleared after missing $missingKey", store.values.isEmpty())
        }
    }

    @Test
    fun everyWrongPendingLaunchFieldTypeFailsClosedAndClearsTheCompleteRecord() {
        validRecord().forEach { (key, value) ->
            val wrongType = if (value is Boolean) "true" else 7
            val store = MemoryPendingLaunchRecordStore(validRecord() + (key to wrongType))

            assertNull(StrictPendingOptimizeLaunchStorage(store).read())

            assertTrue("Record was not cleared after wrong type for $key", store.values.isEmpty())
        }
    }

    @Test
    fun blankTreeUriFailsClosedAndClearsTheCompleteRecord() {
        listOf("", "   ").forEach { blankUri ->
            val store = MemoryPendingLaunchRecordStore(
                validRecord() + ("pending_launch_tree_uri" to blankUri)
            )

            assertNull(StrictPendingOptimizeLaunchStorage(store).read())
            assertTrue(store.values.isEmpty())
        }
    }

    @Test
    fun invalidModeAndPermissionResultFailClosedAndClearTheCompleteRecord() {
        listOf(
            "pending_launch_mode" to "UNSAFE",
            "pending_launch_permission_result" to "allowed"
        ).forEach { invalidField ->
            val store = MemoryPendingLaunchRecordStore(validRecord() + invalidField)

            assertNull(StrictPendingOptimizeLaunchStorage(store).read())
            assertTrue(store.values.isEmpty())
        }
    }

    @Test
    fun nonfatalRecordReadFailureIsCaughtAndClearsTheCompleteRecord() {
        val store = MemoryPendingLaunchRecordStore(validRecord()).apply {
            readFailure = ClassCastException("legacy preference type")
        }

        assertNull(StrictPendingOptimizeLaunchStorage(store).read())

        assertTrue(store.values.isEmpty())
    }

    @Test
    fun completeRecordPreservesDryRunTrueAndEveryExactRequestOption() {
        val store = MemoryPendingLaunchRecordStore(validRecord())

        val pending = StrictPendingOptimizeLaunchStorage(store).read()
            ?: error("Expected a valid pending launch")

        assertEquals(
            PendingOptimizeLaunch(
                request = ServiceRunRequest.Optimize(
                    treeUri = "content://tree/exact",
                    runIntent = RunIntent(
                        mode = OptimizeMode.AGGRESSIVE,
                        dryRun = true,
                        apkLabMode = true,
                        textMinify = false
                    )
                ),
                permissionGranted = false
            ),
            pending
        )
    }

    private fun validRecord(): Map<String, Any?> = linkedMapOf(
        "pending_launch_present" to true,
        "pending_launch_tree_uri" to "content://tree/exact",
        "pending_launch_mode" to "AGGRESSIVE",
        "pending_launch_dry_run" to true,
        "pending_launch_apk_lab" to true,
        "pending_launch_text_minify" to false,
        "pending_launch_permission_result" to "denied"
    )

    private class MemoryPendingLaunchRecordStore(
        initial: Map<String, Any?>
    ) : PendingOptimizeLaunchRecordStore {
        val values = LinkedHashMap(initial)
        var readFailure: RuntimeException? = null

        override fun read(): Map<String, Any?> {
            readFailure?.let { throw it }
            return LinkedHashMap(values)
        }

        override fun write(record: Map<String, Any?>) {
            values.clear()
            values.putAll(record)
        }

        override fun clear() {
            values.clear()
        }
    }
}
