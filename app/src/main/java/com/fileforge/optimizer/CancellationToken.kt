package com.fileforge.optimizer

import java.util.concurrent.CancellationException

/** Implementations signal a run-level cancellation by throwing [OptimizationCancelledException]. */
fun interface CancellationToken {
    fun throwIfCancelled()
}

class OptimizationCancelledException(message: String = "Optimization cancelled") : CancellationException(message)

object NeverCancelled : CancellationToken {
    override fun throwIfCancelled() = Unit
}
