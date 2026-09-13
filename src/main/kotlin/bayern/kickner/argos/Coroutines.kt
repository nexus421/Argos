package bayern.kickner.argos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * `runCatching` also catches coroutine cancellation, which must propagate so a shutdown does not get
 * reported as a failed check or a failed delivery. Timeouts stay regular failures.
 */
fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure {
    val isCancellation = it is CancellationException && it !is TimeoutCancellationException
    if (isCancellation) throw it
}
