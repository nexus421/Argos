package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.StatusPageConfig
import bayern.kickner.argos.db.DaySummary
import bayern.kickner.argos.db.dailySummaries
import bayern.kickner.argos.db.latestResults
import bayern.kickner.argos.notify.MonitorRuntimeState
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Days of history shown per monitor on a status page, today included. */
const val HISTORY_DAYS = 30

/**
 * A monitor's daily history is recomputed at most this often. The aggregation reads a month of rows (~12 ms per
 * monitor at minute checks); without a cap every open tab and every anonymous request would run it again.
 */
val HISTORY_CACHE_TTL: Duration = Duration.ofSeconds(60)

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
 * @param clock Current time; replaceable for tests of the history cache.
 */
class StatusService(
    private val config: AppConfig,
    private val database: Database,
    private val stateOf: (String) -> MonitorRuntimeState?,
    private val clock: () -> Instant = Instant::now
) : StatusSource {
    private val historyCache = ConcurrentHashMap<String, Pair<Instant, List<DaySummary>>>()

    override suspend fun statusesFor(page: StatusPageConfig): List<MonitorStatus> {
        val monitorsById = config.monitors.associateBy { it.id }
        val now = clock()
        return page.monitorIds.mapNotNull { monitorsById[it] }.map { monitor ->
            val latest = latestResults(database, monitor.id, limit = 1).firstOrNull()
            val state = when {
                latest == null -> MonitorState.UNKNOWN
                stateOf(monitor.id)?.currentlyDown == true -> MonitorState.DOWN
                else -> MonitorState.UP
            }
            MonitorStatus(monitor.id, monitor.name, state, latest?.timestamp, latest?.responseTimeMs, latest?.errorMessage, history(monitor.id, now))
        }
    }

    private suspend fun history(monitorId: String, now: Instant): List<DaySummary> {
        val cached = historyCache[monitorId]?.takeIf { Duration.between(it.first, now) < HISTORY_CACHE_TTL }
        if (cached != null) return cached.second

        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val firstDay = today.minusDays(HISTORY_DAYS - 1L)
        val byDay = dailySummaries(database, monitorId, from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant()).associateBy { it.day }
        val history = (0 until HISTORY_DAYS).map { firstDay.plusDays(it.toLong()) }.map { byDay[it] ?: DaySummary(it, 0, 0, null) }
        historyCache[monitorId] = now to history
        return history
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
