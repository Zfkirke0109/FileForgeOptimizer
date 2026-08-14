package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeServiceSessionTest {
    @Test
    fun visibleLifecycleBindsOnceAndBackgroundingOnlyUnbinds() {
        val port = RecordingServicePort()
        val session = OptimizeServiceSession(port)

        session.onVisible()
        session.onVisible()
        session.onHidden()
        session.onHidden()

        assertEquals(1, port.bindCalls)
        assertEquals(1, port.unbindCalls)
        assertEquals(0, port.cancelCalls)
        assertFalse(session.isBound)
    }

    @Test
    fun failedBindCanRetryOnNextVisibleLifecycle() {
        val port = RecordingServicePort(bindResults = ArrayDeque(listOf(false, true)))
        val session = OptimizeServiceSession(port)

        session.onVisible()
        assertFalse(session.isBound)
        session.onVisible()

        assertEquals(2, port.bindCalls)
        assertTrue(session.isBound)
    }

    @Test
    fun startAndCancelCommandsCrossTheServicePortExactlyOnce() {
        val port = RecordingServicePort()
        val session = OptimizeServiceSession(port)
        val request = ServiceRunRequest.Optimize(
            treeUri = "content://tree/test",
            runIntent = RunIntent(
                mode = OptimizeMode.SAFE,
                dryRun = true,
                apkLabMode = false,
                textMinify = false
            )
        )

        session.start(request)
        session.cancel()

        assertEquals(listOf(request), port.started)
        assertEquals(1, port.cancelCalls)
    }

    private class RecordingServicePort(
        private val bindResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true))
    ) : OptimizeServicePort {
        var bindCalls = 0
        var unbindCalls = 0
        var cancelCalls = 0
        val started = mutableListOf<ServiceRunRequest.Optimize>()

        override fun bind(): Boolean {
            bindCalls += 1
            return if (bindResults.isEmpty()) true else bindResults.removeFirst()
        }

        override fun unbind() {
            unbindCalls += 1
        }

        override fun start(request: ServiceRunRequest.Optimize) {
            started += request
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }
}
