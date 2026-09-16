package bayern.kickner.argos.web

import bayern.kickner.argos.db.DaySummary
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val BAR_STEP = 10
private const val BAR_WIDTH = 8
private const val CHART_HEIGHT = 40
private const val MIN_BAR_HEIGHT = 2
private const val MAX_BAR_HEIGHT = CHART_HEIGHT - MIN_BAR_HEIGHT

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Inline SVG with one bar per entry of [history]: height is the day's average latency relative to the slowest
 * day, colour tells whether any check failed, a grey stub marks a day without results (`checks == 0`). A day
 * whose checks all failed has no latency and is drawn full height so the outage is not a two-pixel line. The chart
 * stretches to its container (`preserveAspectRatio="none"`), which is harmless because it holds only rectangles.
 *
 * Only numbers and dates end up in the markup, so nothing needs escaping and the chart is safe on public pages.
 */
fun renderHistorySvg(history: List<DaySummary>): String {
    val slowest = history.mapNotNull { it.averageOkMillis }.maxOrNull() ?: 0.0
    val bars = history.mapIndexed { index, day ->
        val average = day.averageOkMillis
        val (css, height) = when {
            day.checks == 0 -> "nodata" to MIN_BAR_HEIGHT
            average == null -> "failed" to MAX_BAR_HEIGHT
            else -> (if (day.failed > 0) "failed" else "ok") to (average / slowest * MAX_BAR_HEIGHT).roundToInt().coerceAtLeast(MIN_BAR_HEIGHT)
        }
        val x = index * BAR_STEP + (BAR_STEP - BAR_WIDTH) / 2
        """<rect class="bar-$css" x="$x" y="${CHART_HEIGHT - height}" width="$BAR_WIDTH" height="$height"><title>${day.tooltip()}</title></rect>"""
    }
    return """<svg class="history" viewBox="0 0 ${history.size * BAR_STEP} $CHART_HEIGHT" preserveAspectRatio="none" role="img" aria-label="${history.size}-day history">${bars.joinToString("")}</svg>"""
}

private fun DaySummary.tooltip(): String {
    val date = dayFormat.format(day)
    if (checks == 0) return "$date: no data"
    val uptime = String.format(Locale.ROOT, "%.1f %%", (checks - failed) * 100.0 / checks)
    val latency = averageOkMillis?.let { formatLatency(it) } ?: "–"
    return "$date: $uptime up, $failed/$checks failed, avg $latency"
}
