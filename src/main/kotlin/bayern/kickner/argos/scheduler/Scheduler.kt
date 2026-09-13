package bayern.kickner.argos.scheduler

import bayern.kickner.argos.checks.executeCheck
import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.db.CheckHistoryEntry
import bayern.kickner.argos.db.deleteHistoryOlderThan
import bayern.kickner.argos.db.insertCheckResult
import bayern.kickner.argos.db.latestResults
import bayern.kickner.argos.rethrowCancellation
import bayern.kickner.argos.notify.MonitorEvent
import bayern.kickner.argos.notify.MonitorRuntimeState
import bayern.kickner.argos.notify.NotificationDispatcher
import bayern.kickner.argos.notify.TriggerDecision
import bayern.kickner.argos.notify.evaluateTrigger
import bayern.kickner.argos.notify.stateFromHistory
import bayern.kickner.argos.selfmonitor.writeHeartbeat
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Scheduler"
private const val HEARTBEAT_INTERVAL_SECONDS = 30L
private const val CLEANUP_INTERVAL_SECONDS = 86_400L

/**
 * Epoch-aligned periodic scheduler that runs due checks, writes heartbeats, and cleans up old history.
 *
 * Missed ticks are never replayed: after a pause every monitor runs once and re-joins the grid.
 * A monitor whose previous check is still running skips the tick, so checks of one monitor never overlap.
 *
 * @property config Application configuration.
 * @property database Database connection.
 * @property scope Coroutine scope for launching concurrent check jobs.
 * @property notificationDispatcher Dispatcher for routing alert notifications.
 */
class Scheduler(
    private val config: AppConfig,
    private val database: Database,
    private val scope: CoroutineScope,
    private val notificationDispatcher: NotificationDispatcher
) {
    private val runtimeState = ConcurrentHashMap<String, MonitorRuntimeState>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Current trigger state of a monitor, or null if it has not been evaluated yet.
     */
    fun stateOf(monitorId: String): MonitorRuntimeState? = runtimeState[monitorId]

    /**
     * Rebuilds the trigger state of every monitor from the stored history. Call before [start].
     */
    suspend fun restoreState() {
        config.monitors.forEach { monitor ->
            val latest = latestResults(database, monitor.id, config.flappingThreshold)
            runtimeState[monitor.id] = stateFromHistory(latest.map { it.success }, config.flappingThreshold)
        }
    }

    /**
     * Starts the background scheduler loop with second-aligned drift compensation.
     */
    fun start() {
        scope.launch {
            var lastTick = epochSecond()
            val tracker = DueTracker(config.monitors, lastTick)
            var nextHeartbeat = lastTick + HEARTBEAT_INTERVAL_SECONDS
            var nextCleanup = lastTick

            while (isActive) {
                val now = epochSecond()

                // A single failing tick must never end monitoring for good; log it and carry on with the next second
                runCatching {
                    when (val gap = classifyTickGap(lastTick, now, config.heartbeatGapMinutesThreshold)) {
                        TickGap.None -> Unit
                        is TickGap.Paused -> {
                            staticLog(KLogger.Level.WARN, TAG) { "Scheduler paused for ${gap.seconds} s (process suspended or clock jumped forward); resuming without replaying missed checks." }
                            if (gap.notify) scope.launch { notifyPause(lastTick, now) }
                        }
                        is TickGap.ClockJumpedBack -> {
                            staticLog(KLogger.Level.WARN, TAG) { "Clock jumped backwards by ${gap.seconds} s; re-aligning schedule." }
                            tracker.realign(now)
                            nextHeartbeat = now + HEARTBEAT_INTERVAL_SECONDS
                            nextCleanup = nextAlignedSecond(now + 1, CLEANUP_INTERVAL_SECONDS)
                        }
                    }

                    tracker.due(now).forEach { monitor -> launchCheck(monitor) }

                    if (now >= nextHeartbeat) {
                        scope.launch { writeHeartbeat(database, Instant.ofEpochSecond(now)) }
                        nextHeartbeat = now + HEARTBEAT_INTERVAL_SECONDS
                    }
                    if (now >= nextCleanup) {
                        scope.launch { cleanupOldHistory() }
                        nextCleanup = nextAlignedSecond(now + 1, CLEANUP_INTERVAL_SECONDS)
                    }
                }.rethrowCancellation().onFailure {
                    staticLog(KLogger.Level.ERROR, TAG) { "Tick failed, continuing: ${it::class.simpleName}: ${it.message}" }
                }

                lastTick = now
                delay(millisUntilNextSecond())
            }
        }
    }

    private fun launchCheck(monitor: MonitorConfig) {
        val started = inFlight.add(monitor.id)
        if (started.not()) {
            staticLog(KLogger.Level.WARN, TAG) { "Monitor '${monitor.id}': previous check still running, skipping this tick." }
            return
        }
        scope.launch {
            try {
                runCheck(monitor)
            } finally {
                inFlight.remove(monitor.id)
            }
        }
    }

    /**
     * Executes a single monitor check, records the result in SQLite, and evaluates triggers.
     * A failing history write is logged but never suppresses the alert decision. The monitor is released
     * for its next check before the (possibly slow) notification is delivered.
     */
    private suspend fun runCheck(monitor: MonitorConfig) {
        val result = executeCheck(monitor.check, monitor.timeoutSeconds)

        runCatching {
            insertCheckResult(database, CheckHistoryEntry(monitor.id, Instant.now(), result.success, result.responseTimeMs, result.message))
        }.rethrowCancellation().onFailure { staticLog(KLogger.Level.ERROR, TAG) { "Monitor '${monitor.id}': could not store check result: ${it.message}" } }

        val decision = transition(monitor.id, result.success)
        inFlight.remove(monitor.id)

        when (decision) {
            TriggerDecision.SendDownNotification -> notificationDispatcher.sendMonitorNotification(
                monitor.id, monitor.name, "${monitor.name} is DOWN", result.message ?: "No detail provided", MonitorEvent.DOWN, monitor.notificationChannelIds
            )
            TriggerDecision.SendRecoveryNotification -> notificationDispatcher.sendMonitorNotification(
                monitor.id, monitor.name, "${monitor.name} is UP again", "Recovered", MonitorEvent.UP, monitor.notificationChannelIds
            )
            TriggerDecision.None -> Unit
        }
    }

    /**
     * Atomically advances the trigger state of [monitorId] and returns the resulting decision.
     */
    private fun transition(monitorId: String, checkSucceeded: Boolean): TriggerDecision {
        var decision: TriggerDecision = TriggerDecision.None
        runtimeState.compute(monitorId) { _, previous ->
            val (next, nextDecision) = evaluateTrigger(previous ?: MonitorRuntimeState(), checkSucceeded, config.flappingThreshold)
            decision = nextDecision
            next
        }
        return decision
    }

    private suspend fun notifyPause(lastTick: Long, now: Long) {
        notificationDispatcher.sendSystemNotification(
            subject = "Argos: scheduler paused",
            body = "No checks ran between ${Instant.ofEpochSecond(lastTick)} and ${Instant.ofEpochSecond(now)} " +
                "(${now - lastTick} s) — the process was suspended or the clock jumped forward. Checks resumed normally."
        )
    }

    /**
     * Deletes check history rows older than the configured retention period.
     */
    private suspend fun cleanupOldHistory() {
        val cutoff = Instant.now().minusSeconds(config.retentionDays * CLEANUP_INTERVAL_SECONDS)
        val deleted = deleteHistoryOlderThan(database, cutoff)
        if (deleted > 0) staticLog(KLogger.Level.INFO, TAG) { "Retention cleanup removed $deleted check results older than $cutoff." }
    }

    private fun epochSecond(): Long = Instant.now().epochSecond

    private fun millisUntilNextSecond(): Long = (1000L - (System.currentTimeMillis() % 1000L)).coerceAtLeast(50L)
}
