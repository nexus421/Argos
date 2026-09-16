package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.StatusPageConfig
import bayern.kickner.argos.db.DaySummary
import bayern.kickner.argos.db.dailySummaries
import bayern.kickner.argos.db.latestResults
import bayern.kickner.argos.notify.MonitorRuntimeState
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Days of history shown per monitor on a status page, today included. */
const val HISTORY_DAYS = 30

/**
 * Trigger-level state shown on a status page. DOWN follows the flapping threshold, so a single failed
 * check keeps a monitor UP while the last result is still shown.
 */
enum class MonitorState { UP, DOWN, UNKNOWN }

/**
 * One row of a status page.
 *
 * @property id Monitor ID.
 * @property name Display name.
 * @property state Trigger-level state.
 * @property lastCheck Time of the newest stored result, null if none yet.
 * @property responseTimeMs Latency of the newest result in milliseconds.
 * @property message Failure detail of the newest result.
 * @property history One entry per UTC day for the last [HISTORY_DAYS] days, oldest first; `checks == 0` marks a day without results.
 */
data class MonitorStatus(
    val id: String,
    val name: String,
    val state: MonitorState,
    val lastCheck: Instant?,
    val responseTimeMs: Double?,
    val message: String?,
    val history: List<DaySummary>
)

/**
 * Supplies the rows of a status page; abstracted so the web module can be tested without a scheduler.
 */
fun interface StatusSource {
    suspend fun statusesFor(page: StatusPageConfig): List<MonitorStatus>
}

/**
 * Combines the newest stored check result with the scheduler's runtime trigger state.
 *
 * @param config Application configuration, for monitor names.
 * @param database Database holding the check history.
 * @param stateOf Lookup of the current runtime state per monitor ID.
 */
class StatusService(
    private val config: AppConfig,
    private val database: Database,
    private val stateOf: (String) -> MonitorRuntimeState?
) : StatusSource {

    override suspend fun statusesFor(page: StatusPageConfig): List<MonitorStatus> {
        val monitorsById = config.monitors.associateBy { it.id }
        val today = LocalDate.now(ZoneOffset.UTC)
        val firstDay = today.minusDays(HISTORY_DAYS - 1L)
        return page.monitorIds.mapNotNull { monitorsById[it] }.map { monitor ->
            val latest = latestResults(database, monitor.id, limit = 1).firstOrNull()
            val state = when {
                latest == null -> MonitorState.UNKNOWN
                stateOf(monitor.id)?.currentlyDown == true -> MonitorState.DOWN
                else -> MonitorState.UP
            }
            val byDay = dailySummaries(database, monitor.id, from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant()).associateBy { it.day }
            val history = (0 until HISTORY_DAYS).map { firstDay.plusDays(it.toLong()) }.map { byDay[it] ?: DaySummary(it, 0, 0, null) }
            MonitorStatus(monitor.id, monitor.name, state, latest?.timestamp, latest?.responseTimeMs, latest?.errorMessage, history)
        }
    }
}

/**
 * Renders a latency for humans: two decimals below 1 ms, one below 10 ms, whole milliseconds above.
 */
fun formatLatency(millis: Double): String = when {
    millis < 1.0 -> String.format(Locale.ROOT, "%.2f ms", millis)
    millis < 10.0 -> String.format(Locale.ROOT, "%.1f ms", millis)
    else -> String.format(Locale.ROOT, "%.0f ms", millis)
}
