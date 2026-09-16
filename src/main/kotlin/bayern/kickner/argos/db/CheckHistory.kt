package bayern.kickner.argos.db

import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate

/**
 * One stored check outcome.
 *
 * @property monitorId Monitor the result belongs to.
 * @property timestamp When the check finished.
 * @property success Whether the check passed.
 * @property responseTimeMs Duration of the check in milliseconds (sub-millisecond precision).
 * @property errorMessage Failure detail, truncated to [ERROR_MESSAGE_MAX_LENGTH].
 */
data class CheckHistoryEntry(
    val monitorId: String,
    val timestamp: Instant,
    val success: Boolean,
    val responseTimeMs: Double,
    val errorMessage: String?
)

/**
 * Returns the newest [limit] results of one monitor, newest first.
 */
suspend fun latestResults(database: Database, monitorId: String, limit: Int): List<CheckHistoryEntry> = dbRead(database) {
    CheckHistoryTable.selectAll()
        .where { CheckHistoryTable.monitorId eq monitorId }
        .orderBy(CheckHistoryTable.timestamp, SortOrder.DESC)
        .limit(limit)
        .map {
            CheckHistoryEntry(
                monitorId = it[CheckHistoryTable.monitorId],
                timestamp = it[CheckHistoryTable.timestamp],
                success = it[CheckHistoryTable.success],
                responseTimeMs = it[CheckHistoryTable.responseTimeMs],
                errorMessage = it[CheckHistoryTable.errorMessage]
            )
        }
}

/**
 * One UTC day of a monitor's history, aggregated in SQL.
 *
 * @property checks Number of stored results that day.
 * @property failed Number of failed results that day.
 * @property averageOkMillis Mean latency of the successful results, null when none succeeded.
 */
data class DaySummary(val day: LocalDate, val checks: Int, val failed: Int, val averageOkMillis: Double?)

/**
 * Per-day totals of one monitor from [from] on, oldest day first. Grouping happens in SQLite on the stored text
 * (`date(timestamp)`), so a month of minute checks costs one indexed query instead of 43 000 rows. The JVM runs
 * in UTC (Main.kt), which makes those days UTC days.
 */
suspend fun dailySummaries(database: Database, monitorId: String, from: Instant): List<DaySummary> = dbRead(database) {
    val day = CustomFunction("date", TextColumnType(), CheckHistoryTable.timestamp)
    val checks = CheckHistoryTable.id.count()
    val failed = Case().When(CheckHistoryTable.success eq false, intLiteral(1)).Else(intLiteral(0)).sum()
    val okMillis = Case().When(CheckHistoryTable.success eq true, CheckHistoryTable.responseTimeMs).Else(doubleLiteral(0.0)).sum()

    CheckHistoryTable.select(day, checks, failed, okMillis)
        .where { (CheckHistoryTable.monitorId eq monitorId) and (CheckHistoryTable.timestamp greaterEq from) }
        .groupBy(day)
        .orderBy(day, SortOrder.ASC)
        .map { row ->
            val total = row[checks].toInt()
            val failures = row[failed] ?: 0
            val succeeded = total - failures
            DaySummary(
                day = LocalDate.parse(row[day]),
                checks = total,
                failed = failures,
                averageOkMillis = if (succeeded > 0) (row[okMillis] ?: 0.0) / succeeded else null
            )
        }
}

/**
 * Start of the current failure streak: the oldest failed result after the newest successful one. Call it BEFORE
 * the recovering success is stored, otherwise the streak is already closed and the answer is null.
 */
suspend fun failingSince(database: Database, monitorId: String): Instant? = dbRead(database) {
    val lastSuccess = CheckHistoryTable.select(CheckHistoryTable.timestamp)
        .where { (CheckHistoryTable.monitorId eq monitorId) and (CheckHistoryTable.success eq true) }
        .orderBy(CheckHistoryTable.timestamp, SortOrder.DESC)
        .limit(1)
        .firstOrNull()?.get(CheckHistoryTable.timestamp)

    val failures = CheckHistoryTable.select(CheckHistoryTable.timestamp)
        .where { (CheckHistoryTable.monitorId eq monitorId) and (CheckHistoryTable.success eq false) }
    if (lastSuccess != null) failures.andWhere { CheckHistoryTable.timestamp greater lastSuccess }

    failures.orderBy(CheckHistoryTable.timestamp, SortOrder.ASC)
        .limit(1)
        .firstOrNull()?.get(CheckHistoryTable.timestamp)
}

/**
 * Deletes history older than [cutoff] in batches of [batchSize] rows so the write lock is released
 * between batches and the WAL file does not balloon.
 *
 * @return Number of deleted rows.
 */
suspend fun deleteHistoryOlderThan(database: Database, cutoff: Instant, batchSize: Int = 5000): Long {
    var deletedTotal = 0L
    while (true) {
        val deleted = dbWrite(database) {
            val oldest = CheckHistoryTable.select(CheckHistoryTable.id)
                .where { CheckHistoryTable.timestamp less cutoff }
                .limit(batchSize)
            CheckHistoryTable.deleteWhere { CheckHistoryTable.id inSubQuery oldest }
        }
        deletedTotal += deleted
        val finished = deleted < batchSize
        if (finished) return deletedTotal
    }
}
