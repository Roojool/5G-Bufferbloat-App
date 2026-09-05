package com.bufferbloatshaper.vpn

import com.bufferbloatshaper.nativeengine.NativeEngineStopFailureException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A race-safe deadline for one native lifecycle call.
 *
 * JNI cannot safely be cancelled. When the deadline wins, [onTimeout] must
 * perform process-level containment; when normal completion wins, the future
 * is cancelled. The callback runs independently of Android's main looper.
 */
internal class NativeLifecycleWatchdog(
    executor: ScheduledExecutorService,
    timeoutMs: Long,
    private val operation: String,
    private val onTimeout: (NativeEngineStopFailureException) -> Unit
) {
    private val lock = Any()
    private var completed = false
    private var timeoutFailure: NativeEngineStopFailureException? = null

    private val future: ScheduledFuture<*> = executor.schedule({
        timeout()?.let(onTimeout)
    }, timeoutMs, TimeUnit.MILLISECONDS)

    private fun timeout(): NativeEngineStopFailureException? = synchronized(lock) {
        if (completed) {
            null
        } else {
            NativeEngineStopFailureException(
                "Native engine $operation exceeded the safety timeout."
            ).also { failure ->
                completed = true
                timeoutFailure = failure
            }
        }
    }

    /**
     * Marks the operation complete and returns a timeout that won its race, if
     * any. The caller must surface that failure rather than trusting a late JNI
     * return value.
     */
    fun complete(): NativeEngineStopFailureException? {
        val failure = synchronized(lock) {
            completed = true
            timeoutFailure
        }
        future.cancel(false)
        return failure
    }
}
