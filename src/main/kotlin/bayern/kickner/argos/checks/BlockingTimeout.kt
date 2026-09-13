package bayern.kickner.argos.checks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Scope for blocking JDK network calls (name resolution, sockets, ICMP) that cannot be interrupted.
 * Detached from the caller so a timed-out call does not hold the caller until the OS gives up.
 */
private val blockingIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs a blocking, non-interruptible [block] on the IO pool and returns its result, or null once
 * [timeoutMillis] elapsed. The blocking thread finishes on its own later (bounded by OS timeouts).
 */
suspend fun <T> blockingWithTimeout(timeoutMillis: Long, block: () -> T): T? {
    val deferred = blockingIoScope.async { block() }
    return withTimeoutOrNull(timeoutMillis) { deferred.await() }
}

/**
 * Milliseconds for a check timeout given in seconds.
 */
fun Long.secondsToMillis(): Long = this * 1000L
