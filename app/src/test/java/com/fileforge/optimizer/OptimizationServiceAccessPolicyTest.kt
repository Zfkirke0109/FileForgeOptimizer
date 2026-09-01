package com.fileforge.optimizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationServiceAccessPolicyTest {
    @Test
    fun dryRunRequiresReadableDirectoryButNotWriteCapability() {
        val readOnlyTree = TreeAccessCapabilities(
            exists = true,
            isDirectory = true,
            canRead = true,
            canWrite = false
        )

        assertTrue(
            OptimizationServiceTreeAccessPolicy.allows(
                optimizeRequest(dryRun = true),
                readOnlyTree
            )
        )
        listOf(
            readOnlyTree.copy(exists = false),
            readOnlyTree.copy(isDirectory = false),
            readOnlyTree.copy(canRead = false)
        ).forEach { unavailable ->
            assertFalse(
                OptimizationServiceTreeAccessPolicy.allows(
                    optimizeRequest(dryRun = true),
                    unavailable
                )
            )
        }
    }

    @Test
    fun realOptimizationAndRestoreRequireReadableWritableDirectory() {
        val readOnlyTree = TreeAccessCapabilities(
            exists = true,
            isDirectory = true,
            canRead = true,
            canWrite = false
        )
        val writableTree = readOnlyTree.copy(canWrite = true)
        val realOptimization = optimizeRequest(dryRun = false)
        val restore = ServiceRunRequest.Restore(
            treeUri = "content://tree/restore",
            undoLogId = "undo-id",
            selection = RestoreSelection.All
        )

        assertFalse(OptimizationServiceTreeAccessPolicy.allows(realOptimization, readOnlyTree))
        assertFalse(OptimizationServiceTreeAccessPolicy.allows(restore, readOnlyTree))
        assertTrue(OptimizationServiceTreeAccessPolicy.allows(realOptimization, writableTree))
        assertTrue(OptimizationServiceTreeAccessPolicy.allows(restore, writableTree))
    }

    private fun optimizeRequest(dryRun: Boolean) = ServiceRunRequest.Optimize(
        treeUri = "content://tree/optimize",
        runIntent = RunIntent(
            mode = OptimizeMode.SAFE,
            dryRun = dryRun,
            apkLabMode = false,
            textMinify = false
        )
    )
}
