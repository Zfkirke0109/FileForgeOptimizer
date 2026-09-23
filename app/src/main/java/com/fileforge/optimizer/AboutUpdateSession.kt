package com.fileforge.optimizer

import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AboutUpdateSession(
    private val checker: LatestReleaseChecker,
    private val worker: Executor,
    deliverOnMain: Executor,
    deliver: (UpdateCheckResult) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var delivery: ((UpdateCheckResult) -> Unit)? = deliver
    @Volatile private var mainExecutor: Executor? = deliverOnMain
    @Volatile private var future: Future<*>? = null

    fun checkNow() {
        if (closed.get()) return
        val task = Runnable {
            if (closed.get() || Thread.currentThread().isInterrupted) return@Runnable
            val result = checker.check()
            if (closed.get() || Thread.currentThread().isInterrupted) return@Runnable
            mainExecutor?.execute {
                if (!closed.get()) delivery?.invoke(result)
            }
        }
        val executorService = worker as? ExecutorService
        if (executorService != null) future = executorService.submit(task) else worker.execute(task)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        delivery = null
        mainExecutor = null
        (checker as? Closeable)?.close()
        future?.cancel(true)
        future = null
    }
}
