package bayern.kickner.argos.notify

/**
 * In-memory runtime state tracking consecutive failures for flapping protection.
 *
 * @property consecutiveFailures Number of consecutive unsuccessful check attempts.
 * @property currentlyDown Whether the monitor is currently considered in DOWN state.
 */
data class MonitorRuntimeState(
    val consecutiveFailures: Int = 0,
    val currentlyDown: Boolean = false
)

/**
 * Notification decision emitted by the trigger state machine.
 */
sealed interface TriggerDecision {
    /** No alert notification needs to be dispatched. */
    data object None : TriggerDecision

    /** Monitor has breached the failure threshold and is now DOWN. */
    data object SendDownNotification : TriggerDecision

    /** Monitor was previously DOWN and has now recovered to UP. */
    data object SendRecoveryNotification : TriggerDecision
}

/**
 * Evaluates the next monitor runtime state and trigger decision based on check outcome.
 *
 * @param previous Previous monitor state.
 * @param checkSucceeded Whether the latest check was successful.
 * @param flappingThreshold Threshold of consecutive failures before triggering DOWN.
 * @return Pair of new [MonitorRuntimeState] and [TriggerDecision].
 */
fun evaluateTrigger(
    previous: MonitorRuntimeState,
    checkSucceeded: Boolean,
    flappingThreshold: Int
): Pair<MonitorRuntimeState, TriggerDecision> {
    if (checkSucceeded) {
        val recovered = previous.currentlyDown
        val newState = MonitorRuntimeState(consecutiveFailures = 0, currentlyDown = false)
        val decision = if (recovered) TriggerDecision.SendRecoveryNotification else TriggerDecision.None
        return newState to decision
    }

    val newFailureCount = previous.consecutiveFailures + 1
    val crossesThreshold = newFailureCount >= flappingThreshold && previous.currentlyDown.not()
    val newState = MonitorRuntimeState(
        consecutiveFailures = newFailureCount,
        currentlyDown = previous.currentlyDown || crossesThreshold
    )
    val decision = if (crossesThreshold) TriggerDecision.SendDownNotification else TriggerDecision.None
    return newState to decision
}

/**
 * Rebuilds the runtime state from stored results so a restart neither repeats a DOWN alert
 * nor swallows the recovery of a monitor that was DOWN before the restart.
 *
 * Reading the newest [flappingThreshold] results is sufficient: a monitor is DOWN exactly when
 * that many consecutive most-recent checks failed.
 *
 * @param latestFirst Check outcomes, newest first.
 * @param flappingThreshold Threshold of consecutive failures before a monitor counts as DOWN.
 */
fun stateFromHistory(latestFirst: List<Boolean>, flappingThreshold: Int): MonitorRuntimeState {
    val leadingFailures = latestFirst.takeWhile { it.not() }.size
    return MonitorRuntimeState(
        consecutiveFailures = leadingFailures,
        currentlyDown = leadingFailures >= flappingThreshold
    )
}
