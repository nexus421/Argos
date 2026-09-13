package bayern.kickner.argos.scheduler

import bayern.kickner.argos.config.MonitorConfig

/** Ticks arriving later than this after the previous one are reported as a scheduler pause. */
const val PAUSE_WARN_SECONDS = 5L

/**
 * Smallest multiple of [intervalSeconds] that is not before [fromSecond].
 */
fun nextAlignedSecond(fromSecond: Long, intervalSeconds: Long): Long =
    ((fromSecond + intervalSeconds - 1) / intervalSeconds) * intervalSeconds

/**
 * Per-monitor due-time bookkeeping on the epoch grid. Owned by the ticker coroutine; not thread-safe.
 *
 * A monitor is due once its aligned due second has been reached. Skipped or late ticks therefore never
 * lose a run, and after a long pause every monitor runs exactly once before re-joining the grid.
 *
 * @param monitors Configured monitors.
 * @param now Epoch second at construction; first runs are aligned from here.
 */
class DueTracker(private val monitors: List<MonitorConfig>, now: Long) {
    private val nextDue = HashMap<String, Long>()

    init {
        realign(now)
    }

    /**
     * Returns the monitors due at [now] and moves their next due time to the following grid second.
     */
    fun due(now: Long): List<MonitorConfig> = monitors.filter { monitor ->
        val isDue = nextDue.getValue(monitor.id) <= now
        if (isDue) nextDue[monitor.id] = nextAlignedSecond(now + 1, monitor.intervalSeconds)
        isDue
    }

    /**
     * Re-plans every monitor from [now], e.g. after the wall clock jumped backwards.
     */
    fun realign(now: Long) {
        monitors.forEach { nextDue[it.id] = nextAlignedSecond(now, it.intervalSeconds) }
    }
}

/**
 * Classification of the time elapsed between two scheduler ticks.
 */
sealed interface TickGap {
    /** Regular tick, including small jitter. */
    data object None : TickGap

    /** The ticker did not run for [seconds]; [notify] is set when the gap reaches the heartbeat threshold. */
    data class Paused(val seconds: Long, val notify: Boolean) : TickGap

    /** The wall clock moved backwards by [seconds]. */
    data class ClockJumpedBack(val seconds: Long) : TickGap
}

/**
 * Classifies the gap between [lastTick] and [now] (epoch seconds).
 *
 * @param thresholdMinutes Gap length from which a system notification is warranted.
 */
fun classifyTickGap(lastTick: Long, now: Long, thresholdMinutes: Int): TickGap {
    val elapsed = now - lastTick
    return when {
        elapsed < -1 -> TickGap.ClockJumpedBack(-elapsed)
        elapsed > PAUSE_WARN_SECONDS -> TickGap.Paused(elapsed, notify = elapsed >= thresholdMinutes * 60L)
        else -> TickGap.None
    }
}
