package com.fileforge.optimizer

fun interface CancellationToken {
    fun throwIfCancelled()
}

object NeverCancelled : CancellationToken {
    override fun throwIfCancelled() = Unit
}
