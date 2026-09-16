package bayern.kickner.argos.scheduler

import bayern.kickner.argos.checks.executeCheck
import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.db.CheckHistoryEntry
import bayern.kickner.argos.db.PendingAlert
import bayern.kickner.argos.db.deleteAlert
import bayern.kickner.argos.db.deleteHistoryOlderThan
import bayern.kickner.argos.db.failingSince
import bayern.kickner.argos.db.keepAlertChannels
import bayern.kickner.argos.db.latestResults
import bayern.kickner.argos.db.pendingAlertById
import bayern.kickner.argos.db.pendingAlerts
import bayern.kickner.argos.db.recordCheck
import bayern.kickner.argos.notify.MonitorEvent
import bayern.kickner.argos.notify.MonitorRuntimeState
import bayern.kickner.argos.notify.NotificationDispatcher
import bayern.kickner.argos.notify.TriggerDecision
import bayern.kickner.argos.notify.evaluateTrigger
import bayern.kickner.argos.notify.stateFromHistory
import bayern.kickner.argos.rethrowCancellation
import bayern.kickner.argos.selfmonitor.writeHeartbeat
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Scheduler"
private const val HEARTBEAT_INTERVAL_SECONDS = 30L
private const val CLEANUP_INTERVAL_SECONDS = 86_400L

/** How often alerts whose delivery failed are retried while the process runs. */
const val ALERT_RETRY_INTERVAL_SECONDS = 300L

/** Queued alerts older than this are dropped instead of re-delivered: yesterday's DOWN is noise, not news. */
val PENDING_ALERT_MAX_AGE: Duration = Duration.ofHours(24)

/** Subject and body of an alert before it is queued. */
private data class AlertText(val event: MonitorEvent, val subject: String, val body: String)

/**
 * Epoch-aligned periodic scheduler that runs due checks, writes heartbeats, cleans up old history and
 * re-delivers queued alerts.
 *
 * Missed ticks are never replayed: after a pause every monitor runs once and re-joins the grid.
 * A monitor whose previous check is still running skips the tick, so checks of one monitor never overlap.
 * Alert delivery runs in [notificationScope], detached from the check, so a slow SMTP server neither blocks
 * the next check nor is cancelled together with the checks on shutdown.
 *
 * @property config Application configuration.
 * @property database Database connection.
 * @property scope Scope for the ticker and the checks; cancelled first on shutdown.
 * @property notificationScope Scope for alert deliveries; drained (with a timeout) on shutdown.
 * @property notificationDispatcher Dispatcher for routing alert notifications.
 */
class Scheduler(
    private val config: AppConfig,
    private val database: Database,
    private val scope: CoroutineScope,
    private val notificationScope: CoroutineScope,
    private val notificationDispatcher: NotificationDispatcher
) {
    private val runtimeState = ConcurrentHashMap<String, MonitorRuntimeState>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** IDs of queued alerts with a delivery in progress, so the periodic retry never sends them a second time. */
    private val deliveringAlerts: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val monitorsById = config.monitors.associateBy { it.id }

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
            // First pass right away: it re-delivers what the previous process left behind
            var nextAlertRetry = lastTick

            while (isActive) {
                val now = epochSecond()

                // A single failing tick must never end monitoring for good; log it and carry on with the next second
                runCatching {
                    when (val gap = classifyTickGap(lastTick, now, config.heartbeatGapMinutesThreshold)) {
                        TickGap.None -> Unit
                        is TickGap.Paused -> {
                            staticLog(KLogger.Level.WARN, TAG) { "Scheduler paused for ${gap.seconds} s (process suspended or clock jumped forward); resuming without replaying missed checks." }
                            if (gap.notify) notificationScope.launch { notifyPause(lastTick, now) }
                        }
                        is TickGap.ClockJumpedBack -> {
                            staticLog(KLogger.Level.WARN, TAG) { "Clock jumped backwards by ${gap.seconds} s; re-aligning schedule." }
                            tracker.realign(now)
                            nextHeartbeat = now + HEARTBEAT_INTERVAL_SECONDS
                            nextCleanup = nextAlignedSecond(now + 1, CLEANUP_INTERVAL_SECONDS)
                            nextAlertRetry = now + ALERT_RETRY_INTERVAL_SECONDS
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
                    if (now >= nextAlertRetry) {
                        scope.launch { redeliverPendingAlerts() }
                        nextAlertRetry = now + ALERT_RETRY_INTERVAL_SECONDS
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
        // Released exactly once, here. Delivery runs in notificationScope, so the monitor is free again as soon
        // as its result is stored — no early release that a later `finally` could undo for the next check.
        scope.launch {
            try {
                runCheck(monitor)
            } finally {
                inFlight.remove(monitor.id)
            }
        }
    }

    /**
     * Executes a single monitor check, evaluates the trigger, stores result and alert atomically and hands the
     * alert to [notificationScope]. A failing history write is logged but never suppresses the alert.
     */
    private suspend fun runCheck(monitor: MonitorConfig) {
        val result = executeCheck(monitor.check, monitor.timeoutSeconds)
        val finishedAt = Instant.now()

        // For the recovery text; read before the success is stored, otherwise the streak is already closed
        val wasDown = runtimeState[monitor.id]?.currentlyDown == true
        val downSince = if (result.success && wasDown) runCatching { failingSince(database, monitor.id) }.rethrowCancellation().getOrNull() else null

        val text = when (transition(monitor.id, result.success)) {
            TriggerDecision.SendDownNotification ->
                AlertText(MonitorEvent.DOWN, "${monitor.name} is DOWN", "Detected at $finishedAt. ${result.message ?: "No detail provided"}")
            TriggerDecision.SendRecoveryNotification ->
                AlertText(MonitorEvent.UP, "${monitor.name} is UP again", recoveryBody(downSince, finishedAt))
            TriggerDecision.None -> null
        }
        val channelIds = monitor.notificationChannelIds ?: notificationDispatcher.allChannelIds
        val alert = text?.takeIf { channelIds.isNotEmpty() }
        val queued = alert?.let { PendingAlert(null, monitor.id, it.event.name, it.subject, it.body, channelIds, finishedAt) }

        val entry = CheckHistoryEntry(monitor.id, finishedAt, result.success, result.responseTimeMs, result.message)
        val alertId = runCatching { recordCheck(database, entry, queued) }
            .rethrowCancellation()
            .onFailure { staticLog(KLogger.Level.ERROR, TAG) { "Monitor '${monitor.id}': could not store check result: ${it.message}" } }
            .getOrNull()
        if (alert == null || queued == null) return

        // Claimed before the delivery coroutine exists, so a redelivery pass that reads the row in between leaves it
        // alone — and if that pass won the claim first, it delivers and this check must not send a second copy.
        // With a failed write the alert has no row: it is delivered once, without restart protection.
        val claimed = alertId == null || deliveringAlerts.add(alertId)
        if (claimed.not()) return
        notificationScope.launch {
            try {
                deliverAlert(queued.copy(id = alertId), monitor, alert.event)
            } finally {
                alertId?.let { deliveringAlerts.remove(it) }
            }
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

    /**
     * Delivers one alert, then deletes its row or narrows it to the channels that failed. The caller owns the
     * [deliveringAlerts] marker.
     */
    private suspend fun deliverAlert(alert: PendingAlert, monitor: MonitorConfig, event: MonitorEvent) {
        val undelivered = notificationDispatcher.sendMonitorNotification(
            monitor.id, monitor.name, alert.subject, alert.body, event, alert.channelIds
        )
        val id = alert.id ?: return

        runCatching {
            if (undelivered.isEmpty()) deleteAlert(database, id) else keepAlertChannels(database, id, alert.channelIds.filter { it in undelivered })
        }.rethrowCancellation().onFailure {
            staticLog(KLogger.Level.ERROR, TAG) { "Could not update queued alert $id: ${it.message}" }
        }
        if (undelivered.isNotEmpty()) {
            staticLog(KLogger.Level.ERROR, TAG) { "Alert '${alert.subject}' stays queued for channels $undelivered; next retry in $ALERT_RETRY_INTERVAL_SECONDS s or at the next start." }
        }
    }

    /**
     * Re-delivers queued alerts. Alerts of one monitor go out in order; one with a delivery in progress is
     * skipped. Alerts for unknown monitors, unknown events or older than [PENDING_ALERT_MAX_AGE] are dropped
     * with an error log.
     */
    suspend fun redeliverPendingAlerts() {
        val pending = runCatching { pendingAlerts(database) }
            .rethrowCancellation()
            .onFailure { staticLog(KLogger.Level.ERROR, TAG) { "Could not read queued alerts: ${it.message}" } }
            .getOrElse { return }
        if (pending.isEmpty()) return

        val now = Instant.now()
        pending.groupBy { it.monitorId }.values.forEach { alerts ->
            notificationScope.launch { alerts.forEach { alert -> redeliver(alert, now) } }
        }
    }

    private suspend fun redeliver(snapshot: PendingAlert, now: Instant) {
        val id = snapshot.id ?: return
        // Taken by a running delivery (a fresh alert or an earlier pass): leave it alone
        if (deliveringAlerts.add(id).not()) return
        try {
            // The snapshot may be stale: the row is gone once a concurrent delivery confirmed it, or narrowed to the open channels
            val alert = pendingAlertById(database, id) ?: return
            val monitor = monitorsById[alert.monitorId]
            val event = MonitorEvent.entries.firstOrNull { it.name == alert.event }
            val tooOld = Duration.between(alert.createdAt, now) > PENDING_ALERT_MAX_AGE

            if (monitor == null || event == null || tooOld) {
                val reason = when {
                    monitor == null -> "its monitor is no longer configured"
                    event == null -> "its event '${alert.event}' is unknown"
                    else -> "it is older than ${PENDING_ALERT_MAX_AGE.toHours()} h"
                }
                staticLog(KLogger.Level.ERROR, TAG) { "Dropping queued alert '${alert.subject}' (monitor '${alert.monitorId}', queued ${alert.createdAt}): $reason." }
                runCatching { deleteAlert(database, id) }.rethrowCancellation()
                    .onFailure { staticLog(KLogger.Level.ERROR, TAG) { "Could not delete queued alert $id: ${it.message}" } }
                return
            }

            staticLog(KLogger.Level.INFO, TAG) { "Re-delivering queued alert '${alert.subject}' (queued ${alert.createdAt}) to ${alert.channelIds}." }
            deliverAlert(alert, monitor, event)
        } finally {
            deliveringAlerts.remove(id)
        }
    }

    private fun recoveryBody(downSince: Instant?, now: Instant): String {
        downSince ?: return "Recovered"
        return "Recovered. Down since $downSince (${formatDuration(Duration.between(downSince, now))})."
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

/**
 * Human-readable outage length for the recovery alert.
 */
fun formatDuration(duration: Duration): String {
    val seconds = duration.seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    }
}
