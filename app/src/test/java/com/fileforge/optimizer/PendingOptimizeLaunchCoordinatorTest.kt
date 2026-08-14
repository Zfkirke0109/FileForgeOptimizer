package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOptimizeLaunchCoordinatorTest {
    @Test
    fun deniedPermissionResultSurvivesControllerRecreationUntilReplayThenConsumesOnce() {
        val storage = MemoryPendingLaunchStorage()
        val request = request(
            treeUri = "content://tree/exact",
            mode = OptimizeMode.AGGRESSIVE,
            dryRun = true,
            apkLab = true,
            textMinify = true
        )
        PendingOptimizeLaunchCoordinator(storage).beginPermissionRequest(request)

        val recreated = PendingOptimizeLaunchCoordinator(storage)
        recreated.recordPermissionResult(granted = false)

        assertNull(recreated.takeReady(replayReady = false, startAllowed = true))
        val launch = recreated.takeReady(replayReady = true, startAllowed = true)
            ?: error("Expected deferred launch")
        assertEquals(request, launch.request)
        assertTrue(launch.explainReducedVisibility)
        assertNull(recreated.takeReady(replayReady = true, startAllowed = true))
        assertNull(storage.read())
    }

    @Test
    fun replayBeforePermissionResultKeepsExactRequestUntilResultArrives() {
        val storage = MemoryPendingLaunchStorage()
        val request = request("content://tree/read-write", OptimizeMode.SAFE, false, false, true)
        val coordinator = PendingOptimizeLaunchCoordinator(storage)
        coordinator.beginPermissionRequest(request)

        assertNull(coordinator.takeReady(replayReady = true, startAllowed = true))
        coordinator.recordPermissionResult(granted = true)
        val launch = coordinator.takeReady(replayReady = true, startAllowed = true)
            ?: error("Expected deferred launch")

        assertEquals(request, launch.request)
        assertFalse(launch.explainReducedVisibility)
        assertNull(coordinator.takeReady(replayReady = true, startAllowed = true))
    }

    @Test
    fun unavailableStartKeepsResolvedRequestDeferredInsteadOfDiscardingIt() {
        val storage = MemoryPendingLaunchStorage()
        val coordinator = PendingOptimizeLaunchCoordinator(storage)
        val request = request("content://tree/busy", OptimizeMode.SAFE, true, false, false)
        coordinator.beginPermissionRequest(request)
        coordinator.recordPermissionResult(granted = true)

        assertNull(coordinator.takeReady(replayReady = true, startAllowed = false))
        assertEquals(request, storage.read()?.request)
        assertEquals(
            request,
            coordinator.takeReady(replayReady = true, startAllowed = true)?.request
        )
    }

    private fun request(
        treeUri: String,
        mode: OptimizeMode,
        dryRun: Boolean,
        apkLab: Boolean,
        textMinify: Boolean
    ) = ServiceRunRequest.Optimize(
        treeUri = treeUri,
        runIntent = RunIntent(
            mode = mode,
            dryRun = dryRun,
            apkLabMode = apkLab,
            textMinify = textMinify
        )
    )

    private class MemoryPendingLaunchStorage : PendingOptimizeLaunchStorage {
        private var value: PendingOptimizeLaunch? = null

        override fun read(): PendingOptimizeLaunch? = value

        override fun write(value: PendingOptimizeLaunch) {
            this.value = value
        }

        override fun clear() {
            value = null
        }
    }
}
