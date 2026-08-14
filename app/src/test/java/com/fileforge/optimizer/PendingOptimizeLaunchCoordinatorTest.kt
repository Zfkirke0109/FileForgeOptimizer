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
            treeUri = "content://com.example.documents/tree/exact",
            mode = OptimizeMode.AGGRESSIVE,
            dryRun = true,
            apkLab = true,
            textMinify = true
        )
        PendingOptimizeLaunchCoordinator(storage, readWriteCapabilities)
            .beginPermissionRequest(request)

        val recreated = PendingOptimizeLaunchCoordinator(storage, readWriteCapabilities)
        recreated.recordPermissionResult(granted = false)

        assertNull(recreated.takeReady(replayReady = false, dispatchAvailable = true))
        val launch = recreated.takeReady(replayReady = true, dispatchAvailable = true)
            ?: error("Expected deferred launch")
        assertEquals(request, launch.request)
        assertTrue(launch.explainReducedVisibility)
        assertNull(recreated.takeReady(replayReady = true, dispatchAvailable = true))
        assertNull(storage.read())
    }

    @Test
    fun replayBeforePermissionResultKeepsExactRequestUntilResultArrives() {
        val storage = MemoryPendingLaunchStorage()
        val request = request(
            "content://com.example.documents/tree/read-write",
            OptimizeMode.SAFE,
            false,
            false,
            true
        )
        val coordinator = PendingOptimizeLaunchCoordinator(storage, readWriteCapabilities)
        coordinator.beginPermissionRequest(request)

        assertNull(coordinator.takeReady(replayReady = true, dispatchAvailable = true))
        coordinator.recordPermissionResult(granted = true)
        val launch = coordinator.takeReady(replayReady = true, dispatchAvailable = true)
            ?: error("Expected deferred launch")

        assertEquals(request, launch.request)
        assertFalse(launch.explainReducedVisibility)
        assertNull(coordinator.takeReady(replayReady = true, dispatchAvailable = true))
    }

    @Test
    fun unavailableStartKeepsResolvedRequestDeferredInsteadOfDiscardingIt() {
        val storage = MemoryPendingLaunchStorage()
        val coordinator = PendingOptimizeLaunchCoordinator(storage, readWriteCapabilities)
        val request = request(
            "content://com.example.documents/tree/busy",
            OptimizeMode.SAFE,
            true,
            false,
            false
        )
        coordinator.beginPermissionRequest(request)
        coordinator.recordPermissionResult(granted = true)

        assertNull(coordinator.takeReady(replayReady = true, dispatchAvailable = false))
        assertEquals(request, storage.read()?.request)
        assertEquals(
            request,
            coordinator.takeReady(replayReady = true, dispatchAvailable = true)?.request
        )
    }

    @Test
    fun oldWritableRequestDispatchesExactlyWhenNewSelectedTreeIsReadOnly() {
        val oldRequestUri = "content://com.example.documents/tree/old-writable"
        val newSelectedUri = "content://com.example.documents/tree/new-read-only"
        val queriedUris = mutableListOf<String>()
        val capabilities = mapOf(
            oldRequestUri to SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            newSelectedUri to SelectedTreeCapabilities.READ_ONLY_DIRECTORY
        )
        val storage = MemoryPendingLaunchStorage()
        val coordinator = PendingOptimizeLaunchCoordinator(storage) { treeUri ->
            queriedUris += treeUri
            capabilities[treeUri] ?: SelectedTreeCapabilities.NONE
        }
        val request = request(oldRequestUri, OptimizeMode.AGGRESSIVE, false, true, true)
        coordinator.beginPermissionRequest(request)
        coordinator.recordPermissionResult(granted = true)

        val launch = coordinator.takeReady(replayReady = true, dispatchAvailable = true)
            ?: error("Expected the persisted writable request to launch")

        assertEquals(listOf(oldRequestUri), queriedUris)
        assertEquals(request, launch.request)
    }

    @Test
    fun oldRevokedRequestFailsClosedWhenNewSelectedTreeIsWritable() {
        val oldRequestUri = "content://com.example.documents/tree/old-revoked"
        val newSelectedUri = "content://com.example.documents/tree/new-writable"
        val queriedUris = mutableListOf<String>()
        val capabilities = mapOf(
            oldRequestUri to SelectedTreeCapabilities.NONE,
            newSelectedUri to SelectedTreeCapabilities.READ_WRITE_DIRECTORY
        )
        val storage = MemoryPendingLaunchStorage()
        val coordinator = PendingOptimizeLaunchCoordinator(storage) { treeUri ->
            queriedUris += treeUri
            capabilities[treeUri] ?: SelectedTreeCapabilities.NONE
        }
        coordinator.beginPermissionRequest(
            request(oldRequestUri, OptimizeMode.SAFE, false, false, false)
        )
        coordinator.recordPermissionResult(granted = false)

        assertNull(coordinator.takeReady(replayReady = true, dispatchAvailable = true))
        assertEquals(listOf(oldRequestUri), queriedUris)
        assertNull(storage.read())
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

    private companion object {
        val readWriteCapabilities: (String) -> SelectedTreeCapabilities = {
            SelectedTreeCapabilities.READ_WRITE_DIRECTORY
        }
    }
}
