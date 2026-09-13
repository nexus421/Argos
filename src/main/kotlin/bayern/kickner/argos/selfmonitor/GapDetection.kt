package bayern.kickner.argos.selfmonitor

import java.time.Duration
import java.time.Instant

/**
 * Checks whether the duration since [lastHeartbeat] exceeds the allowed [thresholdMinutes].
 *
 * @param lastHeartbeat Timestamp of the previous recorded heartbeat, or null if first boot.
 * @param now Current timestamp.
 * @param thresholdMinutes Allowed gap tolerance in minutes.
 * @return True if an unexpected downtime gap is detected, false otherwise.
 */
fun hasUnplannedGap(lastHeartbeat: Instant?, now: Instant, thresholdMinutes: Int): Boolean {
    if (lastHeartbeat == null) return false
    return Duration.between(lastHeartbeat, now).toMinutes() >= thresholdMinutes
}
