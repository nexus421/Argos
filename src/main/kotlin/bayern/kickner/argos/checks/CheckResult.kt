package bayern.kickner.argos.checks

/**
 * Result of an executed monitor check.
 *
 * @property success Whether the target check was successful.
 * @property responseTimeMs Duration in milliseconds with sub-millisecond precision — the ICMP round trip
 * for ping, otherwise the total duration of the check attempt.
 * @property message Error details or explanation if the check failed.
 */
data class CheckResult(
    val success: Boolean,
    val responseTimeMs: Double,
    val message: String?
)

/**
 * Milliseconds elapsed since [startNanos] (a `System.nanoTime()` reading).
 */
fun elapsedMillisSince(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0
