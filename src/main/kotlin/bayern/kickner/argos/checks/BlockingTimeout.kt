package bayern.kickner.argos.checks

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "BlockingTimeout"

/** Threads for blocking JDK network calls. A saturated pool is logged; it never touches database or mail threads. */
const val CHECK_THREADS = 64

/** Queueing longer than this means the pool is exhausted by calls stuck in the OS (typically a DNS outage). */
private const val QUEUE_WARN_MILLIS = 1_000L

/**
 * Own view of Dispatchers.IO: views are not bounded by the 64-thread IO limit, so exhausting this pool leaves
 * `db/Database.kt`'s read view and the mail view untouched — a DNS outage no longer stalls status pages.
 */
private val checkDispatcher = Dispatchers.IO.limitedParallelism(CHECK_THREADS)

/**
 * Scope for blocking JDK network calls (name resolution, sockets, ICMP) that cannot be interrupted.
 * Detached from the caller so a timed-out call does not hold the caller until the OS gives up.
 */
private val blockingIoScope = CoroutineScope(SupervisorJob() + checkDispatcher)

/**
 * Runs a blocking, non-interruptible [block] on the check pool and returns its result, or null once
 * [timeoutMillis] elapsed. The blocking thread finishes on its own later (bounded by OS timeouts).
 */
suspend fun <T> blockingWithTimeout(timeoutMillis: Long, block: () -> T): T? {
    val submittedNanos = System.nanoTime()
    val deferred = blockingIoScope.async {
        val queuedMillis = (System.nanoTime() - submittedNanos) / 1_000_000
        if (queuedMillis > QUEUE_WARN_MILLIS) {
            staticLog(KLogger.Level.WARN, TAG) { "Check thread pool saturated: waited $queuedMillis ms for one of $CHECK_THREADS threads; other checks are stuck in blocking OS calls. Results of this second may report timeouts that are Argos-internal." }
        }
        block()
    }
    return withTimeoutOrNull(timeoutMillis) { deferred.await() }
}

/**
 * Milliseconds for a check timeout given in seconds.
 */
fun Long.secondsToMillis(): Long = this * 1000L
