package com.fileforge.optimizer

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class StreamIntegrityTest {
    @Test
    fun checkedByteCounterRejectsOverflowBeforeItWraps() {
        assertThrows(ArithmeticException::class.java) {
            StreamIntegrityChecker.checkedAdd(Long.MAX_VALUE, 1)
        }
    }

    @Test
    fun copyAndHashChecksCancellationAroundEveryTransferBuffer() {
        var checks = 0
        val cancellation = CancellationToken {
            if (++checks == 2) throw OptimizationCancelledException()
        }

        assertThrows(OptimizationCancelledException::class.java) {
            StreamIntegrityChecker.copyAndHash(ByteArray(64 * 1024).inputStream(), ByteArrayOutputStream(), cancellation)
        }
    }

    @Test
    fun hashChecksCancellationAroundEveryTransferBuffer() {
        var checks = 0
        val cancellation = CancellationToken {
            if (++checks == 2) throw OptimizationCancelledException()
        }

        assertThrows(OptimizationCancelledException::class.java) {
            StreamIntegrityChecker.hash(ByteArray(64 * 1024).inputStream(), cancellation)
        }
    }
}
